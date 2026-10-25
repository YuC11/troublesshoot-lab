package com.lab.troubleshoot.fault.memory;

import com.lab.troubleshoot.core.AbstractFaultScenario;
import com.lab.troubleshoot.core.FaultCategory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 场景 17：堆外内存 OOM —— Direct Memory 泄漏。
 *
 * <p>【病灶原型】{@code ByteBuffer.allocateDirect / Netty ByteBuf} 分配的堆外内存
 * 不经 GC 管辖（只受 -XX:MaxDirectMemorySize 限制），分配后不释放 → 打满后新分配抛
 * {@code OutOfMemoryError: Direct buffer memory}。高频成因：Netty ByteBuf 未 release、
 * DirectBuffer 池无界、MappedByteBuffer 缓存。</p>
 *
 * <p>【排查教学点】与堆 OOM 的三个分水岭：① 进程 RSS 高但堆平稳（堆外指纹）；
 * ② 堆直方图<b>看不见大头</b>（DirectByteBuffer 壳在堆里只有几十字节，真身是 native 内存）；
 * ③ 释放条件 = 引用不可达 + <b>真实的 GC 执行 Cleaner</b>——与类卸载同款纪律，
 * 引用清空后不 GC，BufferPool 水位纹丝不动。</p>
 *
 * <p>【观察手段】lab.sh 已开 -XX:NativeMemoryTracking=summary（jcmd VM.native_memory）；
 * 状态接口用 {@link BufferPoolMXBean}（direct 池 used/count，与 NMT 同源不同口径）。</p>
 */
@Slf4j
@Component
public class DirectMemoryOomScenario extends AbstractFaultScenario {

    /** 每块 1MB；-XX:MaxDirectMemorySize=64m（scripts/lab.sh）——温和模式 48 块 ≈ 50MB 逼近但不撞墙 */
    private static final int CHUNK_BYTES = 1 << 20;

    /** 强引用持有所有 DirectByteBuffer：阻止 Cleaner 回收，让泄漏坐实（这正是线上泄漏代码干的事） */
    private final List<ByteBuffer> buffers = new CopyOnWriteArrayList<>();

    private volatile Thread worker;
    private volatile boolean oomOccurred;

    @Override
    public String id() { return "oom-direct-memory"; }

    @Override
    public String name() { return "堆外内存 OOM：Direct Memory 泄漏"; }

    @Override
    public FaultCategory category() { return FaultCategory.MEMORY; }

    @Override
    public int order() { return 17; }

    @Override
    public String alertText() {
        return "【P1告警】troubleboot-lab 进程 RSS 3.2GB 但堆只用 800MB；NIO 直接内存 61MB/64MB, "
                + "BufferPool direct_count 单调上涨 —— 疑似堆外内存泄漏";
    }

    @Override
    public String symptom() {
        return "循环 allocateDirect(1MB) 不释放。RSS 与堆使用背离 + 堆直方图无大头（堆外对象不占堆）——"
                + "堆 OOM 排查法在此失效。对照 docs/02-内存类场景手册.md#场景17。";
    }

    @Override
    public String docFile() { return "02-内存类场景手册.md#场景17"; }

    @Override
    public void doTrigger(Map<String, String> params) {
        String mode = strParam(params, "mode", "gentle");
        // 温和 48 块≈50MB（基线 direct ~2.5MB, 距 64m 有余量）; crash 无上限, 真实 OOM
        int targetBuffers = intParam(params, "targetBuffers", 48);
        oomOccurred = false;

        worker = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    buffers.add(ByteBuffer.allocateDirect(CHUNK_BYTES));
                    if (buffers.size() % 12 == 0) {
                        log.info("[oom-direct-memory] 已持有 {} 块堆外内存(≈{} MB), direct 池 used={} MB",
                                buffers.size(), buffers.size(), directUsedMb());
                    }
                    if (!"crash".equals(mode) && buffers.size() >= targetBuffers) {
                        log.warn("[oom-direct-memory] gentle 模式达到目标 {} 块, 停止增长但保持持有", targetBuffers);
                        break;
                    }
                }
            } catch (OutOfMemoryError oom) {
                // 与场景 01/02 同款纪律: 先做零分配救命动作(置标志/清引用/GC), 再记账(兜底)。
                // 堆外释放条件 = 不可达 + 真实 GC 执行 Cleaner —— 清引用后必须显式 System.gc()
                oomOccurred = true;
                int leaked = buffers.size();
                buffers.clear();
                System.gc();
                try {
                    markDamaged("Direct buffer memory 已打满(" + leaked + " 块×1MB) —— 已自动清引用+GC 自愈, 点停止复位");
                    log.error("[oom-direct-memory] OutOfMemoryError: Direct buffer memory! "
                            + "已自愈: 清空 {} 块并触发 GC 执行 Cleaner", leaked);
                } catch (Throwable bookkeeping) {
                    // 自愈已完成, 记账失败不传播
                }
            }
        }, "direct-memory-leak-worker");
        worker.start();
    }

    @Override
    public void doStop() {
        if (worker != null) {
            worker.interrupt();
        }
        int freed = buffers.size();
        long beforeUsed = directUsedBytes() >> 20;
        buffers.clear();
        // 引用清空 ≠ 堆外释放: Cleaner 只在 GC 中执行, 停止后零分配不会自发 GC(同 01 §4.1 GC 惰性)
        System.gc();
        try {
            Thread.sleep(500); // 给 Cleaner 一点时间, 让前后对比拿到真实数字
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long afterUsed = directUsedBytes() >> 20;
        log.info("[oom-direct-memory] 已释放 {} 块堆外内存并触发 GC(Cleaner 回收): direct {} MB → {} MB"
                + " —— jcmd VM.native_memory / 仪表盘应立即回落", freed, beforeUsed, afterUsed);
    }

    @Override
    public Map<String, Object> buildStatus() {
        return Map.of(
                "heldBuffers", buffers.size(),
                "heldMb", (buffers.size() * CHUNK_BYTES) >> 20,
                "directUsedMb", directUsedMb(),
                "directMaxMb", 64, // 与 scripts/lab.sh 的 -XX:MaxDirectMemorySize 保持一致
                "oomOccurred", oomOccurred
        );
    }

    private static List<BufferPoolMXBean> bufferPools() {
        return ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
    }

    /** direct 池已用 MB 一位小数（BufferPoolMXBean, JDK7+ 公开 API） */
    private static double directUsedMb() {
        return Math.round(directUsedBytes() / 1048576.0 * 10) / 10.0;
    }

    private static long directUsedBytes() {
        return bufferPools().stream()
                .filter(p -> p.getName().toLowerCase().contains("direct"))
                .findFirst()
                .map(BufferPoolMXBean::getMemoryUsed)
                .orElse(-1L);
    }
}
