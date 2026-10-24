package com.lab.troubleshoot.fault.slowapi;

import com.lab.troubleshoot.core.AbstractFaultScenario;
import com.lab.troubleshoot.core.FaultCategory;
import com.lab.troubleshoot.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 场景 15：锁竞争（锁粒度过大）—— jstack 里最壮观的 BLOCKED 现场。
 *
 * <p>【病灶原型】synchronized 方法内做远程调用/IO（2s），并发请求全部排队。
 * jstack 现场：几十个线程 {@code BLOCKED (on object monitor)} 等同一把锁，
 * 栈顶全指向同一个业务方法——定位毫无悬念，修复却要动并发设计。</p>
 *
 * <p>【演练联动】后台 40 线程持续抢锁制造竞争；用户 curl
 * {@code GET /api/inventory/deduct} 亲身体验「排队 2s×N」。
 * 修复方向（Runbook 展开）：缩小锁范围只保护共享变量修改、
 * 锁内不做 IO、或改用无锁结构/分段锁。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LockContentionScenario extends AbstractFaultScenario {

    private final InventoryService inventoryService;

    private volatile boolean running;
    private final AtomicInteger contenders = new AtomicInteger();

    @Override
    public String id() { return "slow-lock-contention"; }

    @Override
    public String name() { return "锁竞争：锁内做慢 IO"; }

    @Override
    public FaultCategory category() { return FaultCategory.SLOW_API; }

    @Override
    public int order() { return 15; }

    @Override
    public String alertText() {
        return "【P1告警】/api/inventory/deduct P99 8.2s(正常 2.1s), 随并发升高线性恶化; "
                + "线程 dump 显示 40+ 线程 BLOCKED 等待同一 monitor —— 锁竞争";
    }

    @Override
    public String symptom() {
        return "40 个后台线程竞争 synchronized(锁内 sleep 2s)。jstack 看大量 BLOCKED + 一个 owner。"
                + "curl /api/inventory/deduct 体验排队。对照 docs/05-接口慢类场景手册.md#场景15。";
    }

    @Override
    public String docFile() { return "05-接口慢类场景手册.md#场景15"; }

    @Override
    public void doTrigger(Map<String, String> params) {
        int threads = intParam(params, "threads", 40);
        long holdMs = intParam(params, "holdMs", 2000);
        running = true;

        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "lock-contender");
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                contenders.incrementAndGet();
                try {
                    while (running) {
                        // 后台争抢线程与用户请求走同一把锁 —— 制造真实竞争
                        inventoryService.deductWithWideLock(holdMs);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    contenders.decrementAndGet();
                }
            });
        }
        log.warn("[slow-lock-contention] {} 个争抢线程已启动(锁内 hold {}ms)。体验: "
                + "curl http://localhost:8080/api/inventory/deduct (体验排队); "
                + "jstack {} | grep -A 5 BLOCKED", threads, holdMs, pid());
    }

    @Override
    public void doStop() {
        running = false;
        log.info("[slow-lock-contention] 争抢线程退出中(等待当次锁释放, 最多 {}ms) —— /api/inventory/deduct 将恢复",
                2000);
    }

    @Override
    public Map<String, Object> buildStatus() {
        return Map.of(
                "running", running,
                "contenders", contenders.get(),
                "totalDeductions", inventoryService.totalDeductions()
        );
    }
}
