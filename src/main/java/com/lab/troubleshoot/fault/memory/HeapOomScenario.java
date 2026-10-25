package com.lab.troubleshoot.fault.memory;

import com.lab.troubleshoot.core.AbstractFaultScenario;
import com.lab.troubleshoot.core.FaultCategory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 场景 01：堆内存 OOM —— 无界缓存。
 *
 * <p>【病灶原型】线上最常见的堆 OOM：一个 static 集合当「缓存」用，只进不出。
 * 业务代码每次请求都往里塞对象（本场景为 10MB 字节数组），直到 256MB 小堆被打爆。</p>
 *
 * <p>【双模式设计】</p>
 * <ul>
 *   <li><b>gentle（默认）</b>：泄漏到 targetMb 后停手但<b>继续持有</b>——堆高位滞留。
 *       演练者用 jmap -histo 能看到 byte[] 是大头，stop 后清空、堆应回落。</li>
 *   <li><b>crash（?mode=crash）</b>：不限量塞到真正抛 {@code OutOfMemoryError: Java heap space}。
 *       启动参数 -XX:+HeapDumpOnOutOfMemoryError 会自动落 dump 文件到 dumps/ 目录，
 *       进程本身不退出（教学点：OOM 杀死的是线程，不一定是进程——但线上必须重启）。</li>
 * </ul>
 */
@Slf4j
@Component
public class HeapOomScenario extends AbstractFaultScenario {

    /** 模拟无界缓存：static 生命周期 = 类生命周期 = 进程生命周期，这就是泄漏的根源 */
    private final List<byte[]> unboundedCache = new CopyOnWriteArrayList<>();

    private volatile Thread worker;
    private volatile boolean oomOccurred;

    @Override
    public String id() { return "oom-heap"; }

    @Override
    public String name() { return "堆内存 OOM：无界缓存"; }

    @Override
    public FaultCategory category() { return FaultCategory.MEMORY; }

    @Override
    public int order() { return 1; }

    @Override
    public String alertText() {
        return "【P0告警】troubleboot-lab(10.0.0.11:8080) 堆内存使用率 93%(阈值85%), Old 区回收后不下降, "
                + "5 分钟内 Full GC 12 次, 接口 P99 从 45ms 升至 3.2s, 请立即处理";
    }

    @Override
    public String symptom() {
        return "static 集合只进不出，堆老年代持续上涨且 Full GC 后不回落；crash 模式真实抛出 "
                + "OOM 并自动生成堆 dump（dumps/ 目录）。对照 docs/02-内存类场景手册.md#场景01 排查。";
    }

    @Override
    public String docFile() { return "02-内存类场景手册.md#场景01"; }

    @Override
    public void doTrigger(Map<String, String> params) {
        String mode = strParam(params, "mode", "gentle");
        int chunkMb = intParam(params, "chunkMb", 10);
        int targetMb = intParam(params, "targetMb", 180);
        long intervalMs = intParam(params, "intervalMs", 150);
        oomOccurred = false;

        worker = new Thread(() -> {
            try {
                if ("crash".equals(mode)) {
                    // crash 模式：不限量，直到 OOM
                    while (!Thread.currentThread().isInterrupted()) {
                        unboundedCache.add(new byte[chunkMb << 20]);
                    }
                } else {
                    // gentle 模式：到目标量后停手，但保持持有（观察「高位滞留」）
                    while (heldMb() < targetMb && !Thread.currentThread().isInterrupted()) {
                        unboundedCache.add(new byte[chunkMb << 20]);
                        log.info("[oom-heap] 已泄漏 {} MB / 目标 {} MB", heldMb(), targetMb);
                        Thread.sleep(intervalMs);
                    }
                    log.warn("[oom-heap] 泄漏已就位: {} MB。堆将高位滞留——用 jstat -gcutil <pid> 观察, "
                            + "stop 后清空对比", heldMb());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (OutOfMemoryError oom) {
                // OOM 在业务线程里被捕获：进程不死，dump 已由 -XX:+HeapDumpOnOutOfMemoryError 生成
                oomOccurred = true;
                markDamaged("OOM 已发生: " + oom.getMessage()
                        + " —— dumps/ 目录已生成 heap dump, 进程未退出(教学环境), 线上必须立即重启");
                log.error("[oom-heap] OutOfMemoryError! {}, HeapDumpOnOutOfMemoryError 已落 dump 文件", oom.getMessage());
                unboundedCache.clear(); // 教学环境自愈，保证后续场景还能演练
            }
        }, "oom-heap-worker");
        worker.start();
    }

    @Override
    public void doStop() {
        if (worker != null) {
            worker.interrupt();
        }
        long freedMb = heldMb();
        // 教学核心: 引用清空 ≠ 内存释放。clear() 只让 byte[] 变得「不可达」,
        // jstat 的 O 列要等一次真实的老年代回收动作才会回落。而停止后应用几乎零分配,
        // Eden 装不满 → 不触发 Young GC → G1 不做 IHOP 检查 → 并发标记周期不启动,
        // 死掉的 humongous 对象(10MB > region 一半, 直接进老年代)会长期占着 O≈100%
        // (实测停止后 60s 内 YGC/CGC 全程冻结)。所以这里显式 System.gc()——
        // 等价生产上的 jcmd <pid> GC.run, 让「修复 → 验证」的回落立即可见。
        long beforeMb = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() >> 20;
        unboundedCache.clear();
        System.gc();
        try {
            Thread.sleep(500); // 给 GC 一点时间, 让日志里的前后对比拿到真实数字
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long afterMb = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() >> 20;
        log.info("[oom-heap] 已清空 {} MB 缓存并触发 Full GC: 堆 {} MB → {} MB"
                + " —— jstat -gcutil 的 O 列应立即回落", freedMb, beforeMb, afterMb);
    }

    @Override
    public Map<String, Object> buildStatus() {
        return Map.of(
                "heldMb", heldMb(),
                "chunks", unboundedCache.size(),
                "heapUsedMb", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() >> 20,
                "heapMaxMb", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax() >> 20,
                "oomOccurred", oomOccurred
        );
    }

    private int heldMb() {
        // 8MB 起步的估计算法太粗糙，直接精确求和（元素是 byte[]，length 即字节数）
        long bytes = unboundedCache.stream().mapToLong(a -> a.length).sum();
        return (int) (bytes >> 20);
    }
}
