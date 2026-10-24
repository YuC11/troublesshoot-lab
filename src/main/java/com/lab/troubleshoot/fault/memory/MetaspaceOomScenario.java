package com.lab.troubleshoot.fault.memory;

import com.lab.troubleshoot.core.AbstractFaultScenario;
import com.lab.troubleshoot.core.FaultCategory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 场景 02：Metaspace OOM —— 类加载器泄漏。
 *
 * <p>【病灶原型】动态代理/字节码增强（CGLib、Groovy 脚本、反射框架）不断生成新类，
 * 或热部署场景下旧 ClassLoader 卸不干净。Metaspace（JDK8+ 替代 PermGen）存放类元数据，
 * 由 -XX:MaxMetaspaceSize=64m 限制（start.sh 已配置）。</p>
 *
 * <p>【实现手法】把 {@link MetaspaceMarker} 的 .class 字节码读出一份，循环用
 * <b>新的 ClassLoader</b> 反复 defineClass——同一个类名在不同加载器里是 16 个、
 * 1600 个、6000 个互相独立的类，Metaspace 线性上涨直到 64m 打爆。
 * 与堆 OOM 的排查差异：jmap -histo 看不到元数据，要看 jstat -gcmetaspace
 * 与已加载类数（每个类的元数据都在 Metaspace，类卸载条件是「类加载器可回收」）。</p>
 *
 * <p>类卸载教学点：必须持有 loader 引用防止提前卸载；stop 后置空引用，
 * 类在下一次 GC 才可能卸载（验证：jcmd &lt;pid&gt; GC.run 后 Metaspace 回落）。</p>
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
        return "【P1告警】troubleboot-lab JVM 已加载类数 1 小时内从 8,412 涨至 41,308, Metaspace 使用 61.9MB/64MB"
                + "(阈值85%), 存在 Metaspace OOM 风险";
    }

    @Override
    public String symptom() {
        return "循环用新 ClassLoader defineClass 同一字节码，模拟动态代理/脚本引擎的类加载器泄漏；"
                + "watch 已加载类数与 Metaspace 持续上涨。对照 docs/02-MetaspaceOOM.md。";
    }

    @Override
    public String docFile() { return "02-MetaspaceOOM.md"; }

    @Override
    public void doTrigger(Map<String, String> params) {
        String mode = strParam(params, "mode", "gentle");
        int targetLoaders = intParam(params, "targetLoaders", 6000);
        oomOccurred = false;

        worker = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
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
                oomOccurred = true;
                markDamaged("Metaspace OOM 已发生: " + oom.getMessage() + " —— -XX:MaxMetaspaceSize=64m 已打满");
                log.error("[oom-metaspace] OutOfMemoryError: Metaspace! {}", oom.getMessage());
                loaders.clear(); // 教学环境自愈
            }
        }, "metaspace-leak-worker");
        worker.start();
    }

    @Override
    public void doStop() {
        if (worker != null) {
            worker.interrupt();
        }
        // 置空强引用 → 加载器可回收 → 类可卸载。验证: jcmd <pid> GC.run 后再看 Metaspace
        loaders.clear();
        log.info("[oom-metaspace] 加载器引用已释放, 类将在 GC 后卸载 —— 用 jcmd {} GC.run + jstat -gcmetaspace 验证回落",
                pid());
    }

    @Override
    public Map<String, Object> buildStatus() {
        return Map.of(
                "leakedLoaders", loaders.size(),
                "loadedClassTotal", ManagementFactory.getClassLoadingMXBean().getLoadedClassCount(),
                "metaspaceUsedMb", metaspaceUsedMb(),
                "metaspaceMaxMb", 64, // 与 start.sh 的 -XX:MaxMetaspaceSize 保持一致
                "oomOccurred", oomOccurred
        );
    }

    private int metaspaceUsedMb() {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(p -> p.getName().toLowerCase().contains("metaspace"))
                .findFirst()
                .map(p -> (int) (p.getUsage().getUsed() >> 20))
                .orElse(-1);
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
