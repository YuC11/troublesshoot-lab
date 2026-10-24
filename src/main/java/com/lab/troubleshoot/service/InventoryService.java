package com.lab.troubleshoot.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 库存服务 —— 场景 15「锁粒度过大」的病灶现场。
 *
 * <p>【病灶原型】方法级 synchronized 包住了一段<b>远程调用</b>（此处用 2s sleep
 * 模拟扣减 RPC）。锁内做慢 IO 是并发的大忌：同一时刻只有一个请求能进方法，
 * 其余全部 BLOCKED 排队——吞吐从理论值 500/s 直接跌到 0.5/s。</p>
 *
 * <p>独立成 Service 而不是写在 Controller：让「场景后台压测线程」和「用户
 * curl 的 HTTP 请求」走<b>同一把锁</b>，才能复现真实的锁竞争现场。</p>
 */
@Slf4j
@Service
public class InventoryService {

    private final AtomicLong deductions = new AtomicLong();

    /** 总扣减数（含被阻塞后完成的）——衡量吞吐恶化的直观指标 */
    @Getter
    private volatile long lastCostMs;

    /**
     * 病灶方法：锁住整个方法体（含 2s 的慢操作）。
     * jstack 现场：大量线程 BLOCKED 在 monitor 上，owner 线程正 sleep。
     */
    public synchronized void deductWithWideLock(long holdMs) throws InterruptedException {
        long start = System.currentTimeMillis();
        try {
            // 模拟锁内的远程扣减调用 —— 慢 IO 不该在锁内，但它就在这
            Thread.sleep(holdMs);
            deductions.incrementAndGet();
        } finally {
            lastCostMs = System.currentTimeMillis() - start;
        }
    }

    /** 统计口径 */
    public long totalDeductions() {
        return deductions.get();
    }
}
