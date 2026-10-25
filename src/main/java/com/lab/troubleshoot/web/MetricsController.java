package com.lab.troubleshoot.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JVM 实时指标端点 —— 控制台顶部仪表盘的数据源（2s 轮询）。
 *
 * <p>教学意图：让演练者<b>不登机器</b>就能看到堆/线程/类加载/CPU 的实时变化，
 * 相当于内嵌了一个最小版「监控大盘」——先在面板上确认趋势（涨/不降/飙升），
 * 再带着假设去终端用 jstat/jstack 验证，这正是「可观测系统 → 机器级工具」的排查节奏。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    @GetMapping
    public Map<String, Object> jvm() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pid", ProcessHandle.current().pid());
        m.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);

        // 堆内存（等价 jstat -gc 的总量视角）
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        m.put("heapUsedMb", toMb(heap.getUsed()));
        m.put("heapCommittedMb", toMb(heap.getCommitted()));
        m.put("heapMaxMb", toMb(heap.getMax()));

        // Metaspace（场景 02 的关键指标，等价 jstat -gcmetacapacity；注意 -gcutil 的 M 列是 used/committed 口径）
        ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(p -> p.getName().toLowerCase().contains("metaspace"))
                .findFirst()
                .ifPresent(p -> {
                    m.put("metaspaceUsedMb", toMb(p.getUsage().getUsed()));
                    m.put("metaspaceMaxMb", toMb(p.getUsage().getMax()));
                });

        // 线程（场景 08/09/10 的关键指标）
        var threads = ManagementFactory.getThreadMXBean();
        m.put("threadCount", threads.getThreadCount());
        m.put("peakThreadCount", threads.getPeakThreadCount());

        // 类加载数（场景 02：动态生成类会让它持续上涨）
        m.put("loadedClassCount", ManagementFactory.getClassLoadingMXBean().getLoadedClassCount());

        // CPU（JDK 内置 com.sun.management 扩展，等价 top 的进程视角）
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        m.put("processCpuLoad", round(os.getCpuLoad()));   // 本进程 CPU 占比 [0,1]
        m.put("systemCpuLoad", round(os.getSystemCpuLoad())); // 整机 CPU 占比 [0,1]
        m.put("availableProcessors", os.getAvailableProcessors());

        m.put("timestamp", System.currentTimeMillis());
        return m;
    }

    private static long toMb(long bytes) {
        return bytes / 1024 / 1024;
    }

    private static double round(double v) {
        return v < 0 ? -1 : Math.round(v * 1000) / 1000.0;
    }
}
