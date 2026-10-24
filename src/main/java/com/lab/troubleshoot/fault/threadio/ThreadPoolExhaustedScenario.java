package com.lab.troubleshoot.fault.threadio;

import com.lab.troubleshoot.core.AbstractFaultScenario;
import com.lab.troubleshoot.core.FaultCategory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 场景 09：线程池耗尽 —— 「接口 hang 但不报错」的经典事故。
 *
 * <p>【病灶原型】某个低频后台功能（报表导出/批量推送）突然被触发或被刷量，
 * 长任务占满共享线程池，<b>正常接口跟着一起 hang</b>。表象极具迷惑性：
 * 无异常日志、无 GC 问题、无慢 SQL，线程 dump 才能看到 worker 全在
 * TIMED_WAITING(sleep)，正常任务在队列里排队。</p>
 *
 * <p>【演练联动】触发本场景后，请另开终端压测正常业务接口
 * {@code GET /api/report/summary}（走同一个 BizReportPool）——
 * 观察它从 100ms 变成 10s 超时，体验「一个功能拖死全部业务」。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ThreadPoolExhaustedScenario extends AbstractFaultScenario {

    private final BizReportPool bizReportPool;

    @Override
    public String id() { return "thread-pool-exhausted"; }

    @Override
    public String name() { return "线程池耗尽：垃圾任务拖死业务接口"; }

    @Override
    public FaultCategory category() { return FaultCategory.THREAD_IO; }

    @Override
    public int order() { return 9; }

    @Override
    public String alertText() {
        return "【P0告警】troubleboot-lab /api/report/* 全线 P99 10s+ 网关超时, 但 /api/order/* 正常; "
                + "无 5xx 无异常日志无 GC 告警 —— 仅报表类接口 hang";
    }

    @Override
    public String symptom() {
        return "30 个 60s 垃圾任务占满 biz-report 线程池（core=4），正常接口 /api/report/summary 排队 10s 超时。"
                + "jstack 看 biz-report-worker 全部 TIMED_WAITING。对照 docs/09-线程池耗尽.md。";
    }

    @Override
    public String docFile() { return "09-线程池耗尽.md"; }

    @Override
    public void doTrigger(Map<String, String> params) {
        int junkTasks = intParam(params, "junkTasks", 30);
        long holdMs = intParam(params, "holdMs", 60_000);

        // 一次性灌入垃圾任务：4 个 worker 立刻占满，其余 26 个进队列排队
        for (int i = 1; i <= junkTasks; i++) {
            bizReportPool.submitJunkTask(holdMs);
        }
        log.warn("[thread-pool-exhausted] 已注入 {} 个 {}ms 垃圾任务。联动验证: 另开终端 "
                        + "curl 'http://localhost:8080/api/report/summary' 观察 10s 超时; jstack {} 看 biz-report-worker",
                junkTasks, holdMs, pid());
    }

    @Override
    public void doStop() {
        // shutdownNow 打断 sleep 中的垃圾任务并重建线程池——等价线上「回滚 + 重启」的组合动作
        bizReportPool.reset();
        log.info("[thread-pool-exhausted] 线程池已重建 —— /api/report/summary 应恢复 100ms 量级");
    }

    @Override
    public Map<String, Object> buildStatus() {
        return bizReportPool.stats();
    }
}
