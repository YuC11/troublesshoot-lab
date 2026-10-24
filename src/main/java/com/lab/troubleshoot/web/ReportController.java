package com.lab.troubleshoot.web;

import com.lab.troubleshoot.fault.threadio.BizReportPool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 报表业务接口 —— 场景 09「线程池耗尽」的<b>受害业务</b>。
 *
 * <p>它本身代码毫无问题（100ms 的正常任务），却因为与垃圾任务共享
 * {@link BizReportPool} 而被拖死。「无辜接口受害」是演练的重点体验：
 * jstack 看到 biz-report-worker 被 sleep 占满、正常请求在队列里排队。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
public class ReportController {

    private final BizReportPool bizReportPool;

    /** 正常耗时 ~150ms；线程池被污染后 10s 超时 503 */
    @GetMapping("/summary")
    public Map<String, Object> summary() throws Exception {
        long start = System.currentTimeMillis();
        try {
            String result = bizReportPool.runSummaryTask();
            return Map.of("result", result, "costMs", System.currentTimeMillis() - start);
        } catch (Exception e) {
            // 队列等待超时 → 503 语义：服务端资源耗尽而非业务错误
            throw new IllegalStateException("报表任务排队超时(10s) —— 线程池被长任务占满? 看场景09: "
                    + e.getClass().getSimpleName());
        }
    }
}
