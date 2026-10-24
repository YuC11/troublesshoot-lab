package com.lab.troubleshoot.fault.slowapi;

import com.lab.troubleshoot.core.AbstractFaultScenario;
import com.lab.troubleshoot.core.FaultCategory;
import com.lab.troubleshoot.repository.LabRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 场景 16：连接池耗尽 —— 综合考核关卡（慢 SQL + 池配置的连锁反应）。
 *
 * <p>【病灶原型】Hikari 连接池只有 2 个连接（application.yml 里教学故意配小，
 * 线上真实对应：池 size 配小 / 慢 SQL 并发上来 / 忘记归还）。慢查询占住连接不放，
 * <b>连正常的毫秒级查询也拿不到连接</b>——无辜接口全线报
 * {@code SQLTransientConnectionException}。单看任何一行代码都没错，
 * 组合起来就是事故。</p>
 *
 * <p>【实现】6 个后台线程循环执行 {@code SELECT SLEEP(0.5)}（占住连接的慢查询），
 * 远超池容量 2。演练者 curl 正常接口 {@code GET /api/order/1}：
 * 等 10s（connection-timeout）后报错——jstack 栈停在
 * {@code HikariPool.getConnection}，这就是「正常代码受害」的现场。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectionPoolExhaustedScenario extends AbstractFaultScenario {

    private final LabRepository labRepository;

    private volatile boolean running;
    private final AtomicInteger holders = new AtomicInteger();

    @Override
    public String id() { return "slow-db-pool-exhausted"; }

    @Override
    public String name() { return "连接池耗尽：慢 SQL 连锁拖垮（综合）"; }

    @Override
    public FaultCategory category() { return FaultCategory.SLOW_API; }

    @Override
    public int order() { return 16; }

    @Override
    public String alertText() {
        return "【P0告警】troubleboot-lab 所有 DB 接口批量报错: Connection is not available, request timed out "
                + "after 10000ms; 同一时段慢查询日志新增 orders 全表扫描记录 —— 疑似连接池耗尽";
    }

    @Override
    public String symptom() {
        return "6 个 SELECT SLEEP 慢查询占满 Hikari 池(=2)。curl /api/order/1 体验「无辜接口」10s 后报错; "
                + "jstack 栈在 HikariPool.getConnection。综合考核: 先定位慢SQL再发现池配置。对照 docs/16-连接池耗尽.md。";
    }

    @Override
    public String docFile() { return "16-连接池耗尽.md"; }

    @Override
    public void doTrigger(Map<String, String> params) {
        int holderCount = intParam(params, "holders", 6);
        running = true;

        ExecutorService pool = Executors.newFixedThreadPool(holderCount, r -> {
            Thread t = new Thread(r, "conn-holder");
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < holderCount; i++) {
            pool.submit(() -> {
                holders.incrementAndGet();
                try {
                    while (running) {
                        try {
                            // 每次 SLEEP 0.5s 占住一个连接；循环保持「持续有慢查询持有连接」
                            labRepository.sleepOnConnection(0.5);
                        } catch (Exception e) {
                            // 池竞争激烈时 holder 自己也可能拿不到连接，等一下再抢
                            Thread.sleep(500);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    holders.decrementAndGet();
                }
            });
        }
        log.warn("[slow-db-pool-exhausted] {} 个连接占坑线程已启动(池上限 2)。体验无辜接口受害: "
                + "curl http://localhost:8080/api/order/1 —— 10s 后 SQLTransientConnectionException", holderCount);
    }

    @Override
    public void doStop() {
        running = false;
        log.info("[slow-db-pool-exhausted] 占坑线程退出中, 连接将归还池 —— /api/order/1 应在 1s 内恢复");
    }

    @Override
    public Map<String, Object> buildStatus() {
        return Map.of(
                "running", running,
                "connHolders", holders.get(),
                "poolMaxSize", 2,
                "connectionTimeoutMs", 10_000,
                "victimEndpoint", "GET /api/order/1"
        );
    }
}
