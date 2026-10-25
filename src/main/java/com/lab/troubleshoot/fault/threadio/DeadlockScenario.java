package com.lab.troubleshoot.fault.threadio;

import com.lab.troubleshoot.core.AbstractFaultScenario;
import com.lab.troubleshoot.core.FaultCategory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 场景 08：死锁 —— jstack 的「自动报警」教学。
 *
 * <p>【病灶原型】两个线程以相反顺序获取两把锁（转账经典场景：A 转 B 与 B 转 A 并发），
 * 互相持有对方需要的锁且互不释放。接口表现为「挂死不超时、不报错」，
 * 线程 dump 末尾会直接打印 {@code Found one Java-level deadlock}——
 * 死锁是少数几个 JVM 会「主动告诉你答案」的故障。</p>
 *
 * <p>【可恢复设计】线上死锁只能重启；但演练场要能反复玩，所以用
 * {@code tryLock(200ms) 循环}实现「可退出的死锁」：running=true 期间，
 * 两个线程都持有自己的锁并持续尝试获取对方的锁（jstack 看到的就是真死锁：
 * locked &lt;0x...&gt; + waiting to lock &lt;0x...&gt; 交叉持有）；
 * stop 时开关翻转，tryLock 循环退出、各自释放——现场可反复重建。</p>
 */
@Slf4j
@Component
public class DeadlockScenario extends AbstractFaultScenario {

    /** 转账业务的两把账户锁（现实中是 per-account 的锁对象） */
    private final ReentrantLock accountLockA = new ReentrantLock();
    private final ReentrantLock accountLockB = new ReentrantLock();

    private volatile boolean running;
    private final AtomicBoolean t1HoldingA = new AtomicBoolean();
    private final AtomicBoolean t2HoldingB = new AtomicBoolean();

    @Override
    public String id() { return "thread-deadlock"; }

    @Override
    public String name() { return "死锁：双锁交叉获取"; }

    @Override
    public FaultCategory category() { return FaultCategory.THREAD_IO; }

    @Override
    public int order() { return 8; }

    @Override
    public String alertText() {
        return "【P0告警】/api/transfer 接口 3 分钟内成功率跌至 71%, 失败请求全部为「等待响应超时」(网关 30s), "
                + "无 5xx 无异常日志; 实例活跃线程数不变但 Tomcat 工作线程逐步耗尽";
    }

    @Override
    public String symptom() {
        return "两线程互持对方需要的锁，接口挂死不报错。jstack 直接输出 Found one Java-level deadlock。"
                + "对照 docs/04-线程与IO类场景手册.md#场景08（含 Arthas thread -b 找阻塞源头）。";
    }

    @Override
    public String docFile() { return "04-线程与IO类场景手册.md#场景08"; }

    @Override
    public void doTrigger(Map<String, String> params) {
        running = true;

        // 线程 1：先拿 A，再要 B（模拟「A 账户向 B 转账」）；staggerMs 错峰确保双方都先持有第一把锁
        Thread t1 = new Thread(() -> holdAndTryLock("transfer-A2B", accountLockA, t1HoldingA, accountLockB, 150),
                "transfer-A2B");
        // 线程 2：先拿 B，再要 A（模拟「B 账户向 A 转账」）—— 锁顺序相反即死锁配方
        Thread t2 = new Thread(() -> holdAndTryLock("transfer-B2A", accountLockB, t2HoldingB, accountLockA, 300),
                "transfer-B2A");
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();

        log.warn("[thread-deadlock] 两个转账线程已启动并互相持锁等待。验证: jstack {} 输出末尾 "
                + "'Found one Java-level deadlock' 段落", pid());
    }

    /**
     * 持有 first 锁后持续 tryLock second——running 期间表现为真死锁；
     * 开关翻转后退出循环并按获取的反序释放（Lock 规范的 unlock 顺序）。
     *
     * @param staggerMs 拿到第一把锁后的错峰休眠：保证两个线程都先持稳自己的锁再去抢对方的——
     *                  消除「启动瞬间先抢到两把锁」的竞态，让死锁现场确定可复现
     */
    private void holdAndTryLock(String bizName, ReentrantLock first, AtomicBoolean firstHeld,
                                ReentrantLock second, long staggerMs) {
        first.lock();
        firstHeld.set(true);
        try {
            try {
                Thread.sleep(staggerMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return; // finally 兜底释放
            }
            while (running) {
                try {
                    // tryLock 超时循环：jstack 抓取瞬间线程 PARK 在这里，同时持有 first 锁
                    if (second.tryLock(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        try {
                            log.info("[thread-deadlock] {} 竟然拿到了第二把锁（不应发生）", bizName);
                        } finally {
                            second.unlock();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            firstHeld.set(false);
            first.unlock();
        }
    }

    @Override
    public void doStop() {
        running = false;
        // 等两个线程走完 finally 释放锁，现场可再次触发
        log.info("[thread-deadlock] 开关已翻转, 两线程将退出 tryLock 循环并释放锁（约 0.2s）—— 死锁现场可重建");
    }

    @Override
    public Map<String, Object> buildStatus() {
        boolean deadlocked = running && t1HoldingA.get() && t2HoldingB.get();
        return Map.of(
                "deadlocked", deadlocked,
                "t1HoldsA_waitsB", t1HoldingA.get(),
                "t2HoldsB_waitsA", t2HoldingB.get(),
                "verifyCommand", "jcmd " + pid() + " Thread.print | tail -30"
        );
    }
}
