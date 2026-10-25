package com.lab.troubleshoot.fault.memory;

import com.lab.troubleshoot.core.AbstractFaultScenario;
import com.lab.troubleshoot.core.FaultCategory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 场景 02：Metaspace OOM —— 类加载器泄漏。
 *
 * <p>【病灶原型】动态代理/字节码增强（CGLib、Groovy 脚本、反射框架）不断生成新类，
 * 或热部署场景下旧 ClassLoader 卸不干净。Metaspace（JDK8+ 替代 PermGen）存放类元数据，
 * 由 -XX:MaxMetaspaceSize=64m 限制（scripts/lab.sh 启动参数已配置）。</p>
 *
 * <p>【实现手法】把 {@link MetaspaceMarker} 的 .class 字节码读出一份，循环用
 * <b>新的 ClassLoader</b> 反复 defineClass——同一个类名在不同加载器里是 16 个、
 * 1600 个、6000 个互相独立的类，Metaspace 线性上涨直到打爆上限
 * (-XX:MaxMetaspaceSize=96m, scripts/lab.sh 启动参数已配置)。
 * 与堆 OOM 的排查差异：jmap -histo 看不到元数据，要看 jcmd GC.heap_info / jstat -gcmetacapacity
 * 与已加载类数（每个类的元数据都在 Metaspace，类卸载条件是「类加载器可回收」）。</p>
 *
 * <p>【committed 才是撞墙口径】used 与 committed 两个数字在本场景差异巨大（裸 JVM 实测
 * 64m 上限下 10,765 个加载器时 used 仅 10.4MB、committed 恰 65,536KB 触发 OOM）：
 * JEP 387 弹性元空间给每个类加载器独立 arena，<b>每加载器 committed ≈ 6KB ≈ 6.7× used(0.9KB)</b>，
 * 而 MaxMetaspaceSize 限制的是 committed。所以温和模式必须带 committed 安全水位刹车
 * （见 doTrigger），crash 模式不刹车、真实打满触发 OOM 后自愈。</p>
 *
 * <p>类卸载教学点：必须持有 loader 引用防止提前卸载；doStop 释放引用并显式触发 GC
 * 完成类卸载（引用清空 ≠ 类卸载，卸载只发生在 GC 中——同场景 01 的「GC 惰性」）。</p>
 */
@Slf4j
@Component
public class MetaspaceOomScenario extends AbstractFaultScenario {

    /** 强引用持有所有加载器：阻止类被卸载，让泄漏坐实（这也正是线上泄漏代码干的事） */
    private final List<ClassLoader> loaders = new CopyOnWriteArrayList<>();

    private volatile Thread worker;
    private volatile boolean oomOccurred;

    /** 标记类字节码，构造时读取一次（类文件极小，反复 define 的成本主要在元数据） */
    private final byte[] markerBytes = readMarkerBytes();

    @Override
    public String id() { return "oom-metaspace"; }

    @Override
    public String name() { return "Metaspace OOM：动态类/类加载器泄漏"; }

    @Override
    public FaultCategory category() { return FaultCategory.MEMORY; }

    @Override
    public int order() { return 2; }

    @Override
    public String alertText() {
        return "【P1告警】troubleboot-lab JVM 已加载类数 1 小时内从 8,412 涨至 41,308, Metaspace 使用 92.8MB/96MB"
                + "(阈值85%), 存在 Metaspace OOM 风险";
    }

    @Override
    public String symptom() {
        return "循环用新 ClassLoader defineClass 同一字节码，模拟动态代理/脚本引擎的类加载器泄漏；"
                + "watch 已加载类数与 Metaspace 持续上涨。对照 docs/02-内存类场景手册.md#场景02。";
    }

    @Override
    public String docFile() { return "02-内存类场景手册.md#场景02"; }

    /** 温和模式安全水位：committed 达到 80MB(96m 上限的 83%)即刹车。
     *  4000 个加载器 committed ≈ 基线42.5MB + 4000×6KB ≈ 67MB, 正常到不了刹车线;
     *  刹车线只在基线异常偏高或 targetLoaders 要得过大时兜底, 保证温和模式永不触墙 */
    private static final long BRAKE_COMMITTED_KB = 80L << 10;

    @Override
    public void doTrigger(Map<String, String> params) {
        String mode = strParam(params, "mode", "gentle");
        // 双口径成本(裸 JVM 实测): 每加载器 used≈0.9KB / committed≈6KB —— 仪表盘涨的是 used,
        // 撞墙的是 committed。96m 上限下 4000 个 ≈ committed 67MB(余量29MB), used +3.6MB 一位小数可见
        int targetLoaders = intParam(params, "targetLoaders", 4000);
        oomOccurred = false;

        worker = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    long committedKb = metaspaceCommittedKb();
                    if (!"crash".equals(mode) && committedKb >= BRAKE_COMMITTED_KB) {
                        log.warn("[oom-metaspace] 安全水位刹车: Metaspace 已提交 {} KB (刹车线 {} KB, 上限 98304 KB)。"
                                        + "撞墙口径是 committed 而非 used —— 每个类加载器 arena 提交 ~6KB 却只占用 ~0.9KB。"
                                        + "已加载 {} 个, 未达目标 {}, 停止增长但保持持有",
                                committedKb, BRAKE_COMMITTED_KB, loaders.size(), targetLoaders);
                        break;
                    }
                    // 匿名子类访问父类 protected defineClass：每个加载器实例独立加载一份类
                    ClassLoader loader = new ClassLoader(null) {
                        {
                            defineClass(MetaspaceMarker.class.getName(), markerBytes, 0, markerBytes.length);
                        }
                    };
                    loaders.add(loader);
                    if (loaders.size() % 1000 == 0) {
                        log.info("[oom-metaspace] 已加载 {} 个泄漏类, 已加载类总数={}, Metaspace≈{} MB",
                                loaders.size(),
                                ManagementFactory.getClassLoadingMXBean().getLoadedClassCount(),
                                metaspaceUsedMb());
                    }
                    if (!"crash".equals(mode) && loaders.size() >= targetLoaders) {
                        log.warn("[oom-metaspace] gentle 模式达到目标 {} 个加载器, 停止增长但保持持有", targetLoaders);
                        break;
                    }
                }
            } catch (OutOfMemoryError oom) {
                // OOM 后的第一原则: 只做零分配的救命动作(置标志/清引用/GC), 全部成功后才能记账。
                // 反模式(本场景真实冻死事故的根因): catch 里先拼字符串再 clear —— 字符串拼接的
                // invokedynamic 首次链接要在仍被打满的元空间里定义新类 → 二次 OOM 从 catch 内抛出 →
                // loaders.clear() 永远执行不到 → 数千个加载器被永久持有 → 所有 HTTP 线程连环 OOM、
                // 停止接口不可达、连 SIGTERM 都无法分发, 只能 kill -9。
                oomOccurred = true;
                int leaked = loaders.size();
                loaders.clear();
                System.gc(); // 立刻卸载死加载器的类, 腾出元空间 —— 否则后续任何类链接都会再次 OOM
                try {
                    markDamaged("Metaspace OOM 已发生: " + oom.getMessage()
                            + " —— MaxMetaspaceSize=96m 已按 committed 口径打满, 已自动清引用+GC 自愈, 点停止复位");
                    log.error("[oom-metaspace] OutOfMemoryError: Metaspace! {} —— 已自愈: 清空 {} 个加载器并触发 GC 卸载类",
                            oom.getMessage(), leaked);
                } catch (Throwable bookkeeping) {
                    // 自愈(清引用+GC)已完成, 记账失败不再向上传播
                }
            }
        }, "metaspace-leak-worker");
        worker.start();
    }

    @Override
    public void doStop() {
        if (worker != null) {
            worker.interrupt();
        }
        // 与场景 01 同理: 引用清空 ≠ 类卸载 —— 类卸载只发生在 GC 中(前提: 加载器不可达),
        // 而停止后应用几乎零分配, 不主动 GC 的话仪表盘的 Metaspace/已加载类会长期停在高位。
        // 显式 System.gc() 触发 Full GC → 卸载死加载器的类 → Metaspace 立即回落。
        long freed = loaders.size();
        long beforeKb = metaspaceUsedKb();
        int classesBefore = ManagementFactory.getClassLoadingMXBean().getLoadedClassCount();
        loaders.clear();
        System.gc();
        try {
            Thread.sleep(500); // 给类卸载一点时间, 让日志里的前后对比拿到真实数字
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long afterKb = metaspaceUsedKb();
        int classesAfter = ManagementFactory.getClassLoadingMXBean().getLoadedClassCount();
        log.info("[oom-metaspace] 已释放 {} 个加载器并触发 Full GC(类卸载): Metaspace {} KB → {} KB, "
                + "已加载类 {} → {} —— 仪表盘应立即回落", freed, beforeKb, afterKb, classesBefore, classesAfter);
    }

    @Override
    public Map<String, Object> buildStatus() {
        return Map.of(
                "leakedLoaders", loaders.size(),
                "loadedClassTotal", ManagementFactory.getClassLoadingMXBean().getLoadedClassCount(),
                "metaspaceUsedMb", metaspaceUsedMb(),
                "metaspaceCommittedMb", metaspaceCommittedKb() / 1024, // 撞墙口径: MaxMetaspaceSize 限的是它
                "metaspaceMaxMb", 96, // 与 scripts/lab.sh 的 -XX:MaxMetaspaceSize 保持一致
                "oomOccurred", oomOccurred
        );
    }

    /** MB 一位小数：温和模式涨幅仅 ~3.6MB, 整数 MB 会把变化吞进取整（仪表盘/状态同口径） */
    private double metaspaceUsedMb() {
        return Math.round(metaspaceUsedKb() / 1024.0 * 10) / 10.0;
    }

    /** KB 精度：温和模式涨幅仅 ~2MB, MB 取整看不出差异, 前后对比日志用 KB 才有意义 */
    private long metaspaceUsedKb() {
        return metaspacePool().map(p -> p.getUsage().getUsed() >> 10).orElse(-1L);
    }

    /** Metaspace 池已提交 KB —— OOM 的真实判定口径(committed vs MaxMetaspaceSize), 温和模式据此刹车 */
    private long metaspaceCommittedKb() {
        return metaspacePool().map(p -> p.getUsage().getCommitted() >> 10).orElse(-1L);
    }

    private Optional<MemoryPoolMXBean> metaspacePool() {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(p -> p.getName().toLowerCase().contains("metaspace"))
                .findFirst();
    }

    private static byte[] readMarkerBytes() {
        try (InputStream in = MetaspaceMarker.class.getResourceAsStream("MetaspaceMarker.class")) {
            if (in == null) {
                throw new IllegalStateException("找不到 MetaspaceMarker.class 资源");
            }
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("读取标记类字节码失败", e);
        }
    }
}
