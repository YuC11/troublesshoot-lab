package com.lab.troubleshoot.fault.threadio;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 业务共享线程池 —— 场景 09「线程池耗尽」的载体。
 *
 * <p>设计为<b>独立组件</b>而不是场景私有字段，是为了还原线上真相：
 * 同一个线程池被「正常业务」（/api/report/summary，见 ReportController）
 * 和「垃圾任务」（场景 09 注入的 60s 长任务）共享——线程池被谁污染，
 * 与故障代码往往不在同一个类里，这也是定位的难点所在。</p>
 *
 * <p>配置即病灶：core=max=4、无界队列。垃圾任务占满 4 个核心线程后，
 * 正常任务在队列里排队等待——「接口 hang 但线程池没报错」的经典现场。</p>
 */
@Slf4j
@Component
public class BizReportPool {

    /** 复位用的新池工厂参数：与线上配置一一对应，教学时可指给演练者看 */
    private static final int CORE = 4;
    private static final int MAX = 4;

    @Getter
    private volatile ThreadPoolExecutor executor;

    public BizReportPool() {
        reset();
    }

    /** 重建线程池（场景 stop / 应用启动时）。线程命名让 jstack 一眼可辨归属 */
    public synchronized void reset() {
        if (executor != null) {
            executor.shutdownNow();
        }
        AtomicInteger seq = new AtomicInteger();
        executor = new ThreadPoolExecutor(
                CORE, MAX, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(500),                    // 「伪无界」队列陷阱: 堆积不拒绝、只排队——
                //   容量 500 在本演练(30个垃圾任务)中不可能触达, 等价线上 new LinkedBlockingQueue() 无界写法;
                //   后果是任务无限堆积、接口 hang 而非快速报错(教学点: 队列有界性要显式设计)
                r -> new Thread(r, "biz-report-worker-" + seq.incrementAndGet()));
        log.info("[BizReportPool] 线程池已(重)建: core={}, max={}, queue=LinkedBlockingQueue(500)", CORE, MAX);
    }

    /** 正常业务任务：~100ms 的报表汇总计算 */
    public String runSummaryTask() throws Exception {
        long start = System.currentTimeMillis();
        try {
            return executor.submit(() -> {
                Thread.sleep(100); // 模拟汇总计算
                return "报表汇总完成: 共 1,024 行";
            }).get(10, TimeUnit.SECONDS); // 最多等 10s：队列堆积时这里先超时，接口 503
        } finally {
            log.info("[BizReportPool] summary 耗时 {} ms (active={}, queue={})",
                    System.currentTimeMillis() - start, executor.getActiveCount(), executor.getQueue().size());
        }
    }

    /** 场景 09 注入的垃圾长任务：占住一个 worker 整整 holdMs */
    public void submitJunkTask(long holdMs) {
        executor.submit(() -> {
            try {
                Thread.sleep(holdMs); // 模拟「卡死的导出任务」：不可中断也不释放线程
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** 教学状态快照：jstack 之外的第二证据源 */
    public java.util.Map<String, Object> stats() {
        return java.util.Map.of(
                "corePoolSize", executor.getCorePoolSize(),
                "activeWorkers", executor.getActiveCount(),
                "queueBacklog", executor.getQueue().size(),
                "completedTasks", executor.getCompletedTaskCount());
    }
}
