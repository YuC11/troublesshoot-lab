package com.lab.troubleshoot.repository;

import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SQL 执行计数器 —— 场景 13「N+1 查询」的示波器。
 *
 * <p>独立成组件而不是塞进 Repository，是为了避免「场景类 ↔ 数据访问类」的循环依赖：
 * 场景只翻转这里的 verbose 开关，Repository 只在这里打点。</p>
 */
@Component
public class SqlCounter {

    /** verbose=true 时每条 SQL 都打 INFO 日志 —— 演练者从日志里亲眼看到 SQL 一条条刷屏 */
    @Getter
    private volatile boolean verbose;

    /** 本次统计窗口内执行的 SQL 条数 */
    @Getter
    private final AtomicInteger count = new AtomicInteger();

    /** 累计 SQL 执行耗时（ms） */
    @Getter
    private final AtomicLong totalCostMs = new AtomicLong();

    public void enableVerbose() {
        this.verbose = true;
        reset();
    }

    public void disableVerbose() {
        this.verbose = false;
    }

    public void reset() {
        count.set(0);
        totalCostMs.set(0);
    }

    /** 数据访问层打点：verbose 时输出带序号的 SQL 日志，模拟慢查询日志的效果 */
    public void record(String sql, long costMs) {
        count.incrementAndGet();
        totalCostMs.addAndGet(costMs);
        if (verbose) {
            // 序号 + 耗时 + SQL 摘要 —— 让「第 1001 条 SQL」这种结论可以从日志直接读出
            System.out.printf("[SQL #%d] %d ms | %s%n", count.get(), costMs, sql);
        }
    }
}
