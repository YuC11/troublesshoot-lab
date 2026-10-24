package com.lab.troubleshoot.fault.threadio;

import com.lab.troubleshoot.core.AbstractFaultScenario;
import com.lab.troubleshoot.core.FaultCategory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 场景 10：线程泄漏 —— 线程数只涨不跌。
 *
 * <p>【病灶原型】代码里 new Thread 而不用线程池（或线程池配置错误导致
 * 实际无复用），每个请求/任务都创建新线程且长期阻塞。线程是重资源：
 * 每个线程独占栈内存（-Xss512k）+ 内核调度实体，涨到几千个后
 * CPU 花在上下文切换、内存花在线程栈，最终 {@code unable to create new native thread}。</p>
 *
 * <p>【观察指标】jstack 输出的总线程数 / jconsole 线程曲线 / 本场景 status 的
 * aliveCount——「线程数单调上涨」就是最直接的泄漏信号。</p>
 */
@Slf4j
@Component
public class ThreadLeakScenario extends AbstractFaultScenario {

    /** 持有线程引用：既阻止 GC 干扰统计，也让 stop 能 interrupt 到所有线程 */
    private final List<Thread> leakedThreads = new CopyOnWriteArrayList<>();

    private volatile boolean spawning;

    @Override
    public String id() { return "thread-leak"; }

    @Override
    public String name() { return "线程泄漏：new Thread 不复用"; }

    @Override
    public FaultCategory category() { return FaultCategory.THREAD_IO; }

    @Override
    public int order() { return 10; }

    @Override
    public String alertText() {
        return "【P1告警】troubleboot-lab JVM 线程数 4 小时从 220 涨至 3,140 (阈值1000), 持续上探; "
                + "机器 load 升高但 CPU 总量正常 —— 疑似线程泄漏";
    }

    @Override
    public String symptom() {
        return "每 50ms 泄漏一个长睡线程（10 分钟寿命），线程总数持续上涨。"
                + "jstack | grep -c '^\"' 实时计数。对照 docs/10-线程泄漏.md。";
    }

    @Override
    public String docFile() { return "10-线程泄漏.md"; }

    @Override
    public void doTrigger(Map<String, String> params) {
        int count = intParam(params, "count", 300);
        long intervalMs = intParam(params, "intervalMs", 50);
        long lifeMs = intParam(params, "lifeMs", 600_000);
        spawning = true;

        Thread spawner = new Thread(() -> {
            try {
                for (int i = 1; i <= count && spawning; i++) {
                    Thread t = new Thread(() -> {
                        try {
                            // 模拟「挂着等消息」的长阻塞任务——不退出的线程才是泄漏
                            Thread.sleep(lifeMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }, "leaked-worker-" + i);
                    t.setDaemon(true);
                    t.start();
                    leakedThreads.add(t);
                    if (i % 50 == 0) {
                        log.info("[thread-leak] 已泄漏 {}/{} 个线程, 存活 {} —— 用 jstack {} | grep -c '^\\\"' 对照",
                                i, count, aliveCount(), pid());
                    }
                    Thread.sleep(intervalMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "thread-leak-spawner");
        spawner.setDaemon(true);
        spawner.start();
    }

    @Override
    public void doStop() {
        spawning = false;
        // interrupt 所有泄漏线程：sleep 可被中断 → 线程退出 → 线程数回落
        int n = leakedThreads.size();
        leakedThreads.forEach(Thread::interrupt);
        leakedThreads.clear();
        log.info("[thread-leak] 已 interrupt {} 个泄漏线程 —— 线程数将在 1s 内回落（对照 jstack 计数）", n);
    }

    @Override
    public Map<String, Object> buildStatus() {
        return Map.of(
                "createdTotal", leakedThreads.size(),
                "aliveNow", aliveCount(),
                "jvmTotalThreads", Thread.activeCount()
        );
    }

    private int aliveCount() {
        return (int) leakedThreads.stream().filter(Thread::isAlive).count();
    }
}
