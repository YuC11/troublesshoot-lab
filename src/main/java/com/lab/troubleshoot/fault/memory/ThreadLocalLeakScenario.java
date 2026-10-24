package com.lab.troubleshoot.fault.memory;

import com.lab.troubleshoot.core.AbstractFaultScenario;
import com.lab.troubleshoot.core.FaultCategory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 场景 03：ThreadLocal 内存泄漏 —— 线程池 + 忘记 remove()。
 *
 * <p>【病灶原型】Web 容器/线程池的线程<b>常驻不死</b>，任务里 {@code ThreadLocal.set(大对象)}
 * 用完不 {@code remove()}。ThreadLocalMap 的 key 是弱引用会被 GC 清掉，但 <b>value 还被
 * 线程持有</b>——于是「看起来没引用了、堆却降不下去」的隐性泄漏。</p>
 *
 * <p>【实现手法】每一轮创建一个<b>新的</b> ThreadLocal 实例（否则同一线程的 set 会互相覆盖，
 * 泄漏不成），让线程池 4 个常驻线程各自 set 10MB。轮数越多泄漏越多。
 * stop 时把线程池关掉——<b>线程死亡才是这种泄漏唯一的自然出口</b>（这也是为什么线上
 * 线程池场景的 ThreadLocal 泄漏永远不自愈）。</p>
 */
@Slf4j
@Component
public class ThreadLocalLeakScenario extends AbstractFaultScenario {

    /** 强引用持有 ThreadLocal 实例：保证 key 不被弱引用回收，泄漏稳定可观察 */
    private final List<ThreadLocal<byte[]>> locals = new java.util.concurrent.CopyOnWriteArrayList<>();

    private volatile ExecutorService pool;

    @Override
    public String id() { return "leak-threadlocal"; }

    @Override
    public String name() { return "ThreadLocal 内存泄漏：线程池 + 未 remove"; }

    @Override
    public FaultCategory category() { return FaultCategory.MEMORY; }

    @Override
    public int order() { return 3; }

    @Override
    public String alertText() {
        return "【P1告警】troubleboot-lab 堆内存锯齿上移(Old 区 GC 后基线每 30min +18MB), 无 OOM 但 Full GC "
                + "频率从 2 次/天 升至 9 次/小时, 疑似内存泄漏";
    }

    @Override
    public String symptom() {
        return "线程池常驻线程 set 大对象后不 remove：堆涨且 jcmd GC.run 后不回落（弱引用 key 已清、"
                + "value 仍被线程持有）。对照 docs/02-内存类场景手册.md#场景03。";
    }

    @Override
    public String docFile() { return "02-内存类场景手册.md#场景03"; }

    @Override
    public void doTrigger(Map<String, String> params) throws InterruptedException {
        int rounds = intParam(params, "rounds", 4);
        int chunkMb = intParam(params, "chunkMb", 10);

        if (pool == null || pool.isShutdown()) {
            // 4 个常驻线程：模拟 Web 容器线程池——线程不死，泄漏就不愈
            pool = Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "tl-leak-pool");
                t.setDaemon(true);
                return t;
            });
        }

        for (int r = 1; r <= rounds && !Thread.currentThread().isInterrupted(); r++) {
            // 新 ThreadLocal 实例是关键：同一线程对同一 TL 的 set 是覆盖，泄漏必须换新实例
            ThreadLocal<byte[]> tl = new ThreadLocal<>();
            locals.add(tl);

            // 让 4 个常驻线程都 set 一份：每轮泄漏 4 × chunkMb
            CountDownLatch done = new CountDownLatch(4);
            AtomicInteger created = new AtomicInteger();
            for (int i = 0; i < 4; i++) {
                pool.submit(() -> {
                    try {
                        tl.set(new byte[chunkMb << 20]); // set 完任务就结束，但 value 挂在线程上不走
                        created.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            done.await(10, TimeUnit.SECONDS);
            log.warn("[leak-threadlocal] 第 {}/{} 轮: {} 个常驻线程各泄漏 {} MB, 累计约 {} MB (无任何引用可到达, "
                    + "但堆不会释放)", r, rounds, created.get(), chunkMb, estimatedLeakMb(chunkMb));
            Thread.sleep(200);
        }
        log.warn("[leak-threadlocal] 泄漏就位。验证: jcmd {} GC.run 前后各看一次 jmap -histo —— byte[] 数量不降", pid());
    }

    @Override
    public void doStop() {
        // 教学点：locals.clear() 并不能释放——value 仍挂在存活线程的 ThreadLocalMap 里；
        // 唯一自然出口是线程死亡，因此直接关闭线程池。remove() 是代码层面的正解。
        List<ThreadLocal<byte[]>> toClear = new ArrayList<>(locals);
        locals.clear();
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        log.info("[leak-threadlocal] 已关闭线程池(线程死亡释放 ThreadLocalMap)。本次演练共 {} 个 ThreadLocal 实例被修复"
                + "——生产正解: finally 中 tl.remove()", toClear.size());
    }

    @Override
    public Map<String, Object> buildStatus() {
        int threads = (pool == null || pool.isShutdown()) ? 0 : 4;
        return Map.of(
                "leakedThreadLocals", locals.size(),
                "estimatedLeakedMb", estimatedLeakMb(10),
                "poolThreadsAlive", threads,
                "note", "value 挂在 4 个常驻线程上, jcmd " + pid() + " GC.run 后堆不降即为泄漏证据"
        );
    }

    /** 泄漏量估算：每个 TL 实例 × 4 线程 × 最后一次 chunk 大小（简化按 10MB 展示） */
    private int estimatedLeakMb(int chunkMb) {
        return locals.size() * 4 * chunkMb;
    }
}
