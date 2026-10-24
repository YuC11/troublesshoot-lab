package com.lab.troubleshoot.fault.memory;

import com.lab.troubleshoot.core.AbstractFaultScenario;
import com.lab.troubleshoot.core.FaultCategory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 场景 04：GC 风暴 —— 内存不足伪装成「CPU 使用率高」。
 *
 * <p>【病灶原型】线上最高频的<b>误诊案例</b>：告警说 CPU 95%，大家冲去查业务代码的
 * 死循环，实际是堆快满导致 GC 空转——高 CPU 的是 GC 线程不是业务线程。
 * top -Hp 能看到高 CPU 线程，但 jstack 里<b>找不到对应 nid</b>（GC 线程不是 Java 线程），
 * 这个「找不到」本身就是最重要的线索。</p>
 *
 * <p>【实现手法】两步制造真实的 GC 风暴：先把 ~170MB 长生命周期对象填进老年代（模拟
 * 缓存类泄漏把 Old 区占住），再用 2 个线程高速分配短命对象 + 周期性 System.gc()，
 * 256MB 小堆很快陷入「Full GC → 回收不掉 → 继续 Full GC」的空转循环。</p>
 */
@Slf4j
@Component
public class GcStormScenario extends AbstractFaultScenario {

    /** 老年代填料：强引用长期持有，让 Full GC 无货可收 */
    private final List<byte[]> oldGenFiller = new CopyOnWriteArrayList<>();

    private volatile boolean running;
    private final AtomicLong allocatedStormBytes = new AtomicLong();

    @Override
    public String id() { return "gc-storm"; }

    @Override
    public String name() { return "GC 风暴：内存不足伪装 CPU 高"; }

    @Override
    public FaultCategory category() { return FaultCategory.MEMORY; }

    @Override
    public int order() { return 4; }

    @Override
    public String alertText() {
        return "【P0告警】troubleboot-lab CPU 使用率 97%(阈值85%), 但 QPS 无明显上涨; 同期接口 RT 普通上涨, "
                + "无单接口异常——先别急着找业务代码, 这个组合更像 GC 问题";
    }

    @Override
    public String symptom() {
        return "长生命周期对象占满老年代 + 分配风暴 → 连续 Full GC，GC 线程吃满 CPU。"
                + "top -Hp 找到的最耗 CPU 线程在 jstack 中查无此 nid（GC 线程非 Java 线程）——"
                + "「找不到」即关键线索。对照 docs/02-内存类场景手册.md#场景04。";
    }

    @Override
    public String docFile() { return "02-内存类场景手册.md#场景04"; }

    @Override
    public void doTrigger(Map<String, String> params) {
        int fillMb = intParam(params, "fillMb", 170);
        int stormThreads = intParam(params, "stormThreads", 2);
        running = true;

        // 第一步：填老年代（一次性、同步完成，让故障状态立即可见）
        int chunk = 8;
        for (int mb = 0; mb < fillMb; mb += chunk) {
            oldGenFiller.add(new byte[chunk << 20]);
        }
        log.warn("[gc-storm] 老年代填料就位 {} MB（GC 回收不掉的『常驻缓存』）", fillMb);

        // 第二步：分配风暴线程 + 周期性 System.gc() —— 触发连续 Full GC
        for (int i = 1; i <= stormThreads; i++) {
            Thread t = new Thread(() -> {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                long seq = 0;
                while (running) {
                    // 256KB~2MB 短命对象：分配速率远超回收速率
                    byte[] tmp = new byte[256 * 1024 + rnd.nextInt(2 << 20)];
                    allocatedStormBytes.addAndGet(tmp.length);
                    if ((++seq & 0x3F) == 0 && running) {
                        // 每 64 次分配主动触发一次 Full GC 模拟「回收空转」
                        System.gc();
                    }
                }
            }, "gc-storm-burner-" + i);
            t.setDaemon(true);
            t.start();
        }
        log.warn("[gc-storm] {} 个分配风暴线程已启动。观察: top -Hp {} (高CPU线程 jstack 查无 nid) + jstat -gcutil {} 1000",
                stormThreads, pid(), pid());
    }

    @Override
    public void doStop() {
        running = false;
        oldGenFiller.clear();
        log.info("[gc-storm] 填料已清空、风暴线程退出 —— jstat -gcutil {} 1000 应看到 FGC 不再增长, CPU 回落", pid());
    }

    @Override
    public Map<String, Object> buildStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("oldGenFillerMb", oldGenFiller.stream().mapToLong(a -> a.length).sum() >> 20);
        m.put("stormAllocatedMb", allocatedStormBytes.get() >> 20);
        m.put("heapUsedMb", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() >> 20);

        // GC 次数/耗时按收集器分组展示（G1: Young/Old 两行）——等价 jstat -gcutil 的关键列
        Map<String, long[]> gc = new LinkedHashMap<>();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            gc.put(bean.getName(), new long[]{bean.getCollectionCount(), bean.getCollectionTime()});
        }
        m.put("gcCollectors(count,timeMs)", gc);
        return m;
    }
}
