package com.lab.troubleshoot.fault.slowapi;

import com.lab.troubleshoot.core.AbstractFaultScenario;
import com.lab.troubleshoot.core.FaultCategory;
import com.lab.troubleshoot.repository.LabRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 场景 12：慢 SQL（缺索引）—— 带「修复 → 验证」完整闭环的教学场景。
 *
 * <p>【病灶原型】orders 表 100 万行，查询条件 user_id 没建索引 → 每次查询
 * 全表扫描。上线时数据量小没暴露，数据涨到百万后接口秒级超时——
 * 「索引是随数据长出来的」这一课，多数团队都用事故补交学费。</p>
 *
 * <p>【演练闭环】trigger（确保无索引+后台持续查询制造现场）→
 * 手动 curl 业务接口感受秒级耗时 → GET /api/order/explain 看执行计划(type=ALL) →
 * POST /api/order/fix-index 建索引 → 再查询毫秒级 + explain 变 ref →
 * 复盘：为什么上线时没发现（数据量）、如何预防（慢查询日志+压测）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlowSqlNoIndexScenario extends AbstractFaultScenario {

    private final LabRepository labRepository;

    private volatile boolean running;
    private final AtomicLong backgroundQueries = new AtomicLong();
    private volatile long lastQueryMs = -1;

    @Override
    public String id() { return "slow-sql-no-index"; }

    @Override
    public String name() { return "慢 SQL：缺索引全表扫描"; }

    @Override
    public FaultCategory category() { return FaultCategory.SLOW_API; }

    @Override
    public int order() { return 12; }

    @Override
    public String alertText() {
        return "【P1告警】/api/order/query-by-user P99 2.8s(阈值500ms), 慢查询日志新增 214 条/5min, "
                + "均命中 orders 表全表扫描; MySQL 服务器 read CPU 升高";
    }

    @Override
    public String symptom() {
        return "orders 表(user_id 无索引)全表扫描 100 万行，查询秒级。EXPLAIN 看 type=ALL，"
                + "POST /api/order/fix-index 建索引后毫秒级(type=ref) —— 完整修复闭环。对照 docs/12-慢SQL缺索引.md。";
    }

    @Override
    public String docFile() { return "12-慢SQL缺索引.md"; }

    @Override
    public void doTrigger(Map<String, String> params) {
        // 激活故障 = 确保索引不存在（若上次演练建过索引，这里删掉恢复病灶）
        if (labRepository.userIndexExists()) {
            labRepository.dropUserIndex();
        }
        running = true;
        backgroundQueries.set(0);

        // 后台低速持续查询：制造「持续有慢查询在跑」的线上现场，DB 侧 CPU/慢查询日志有持续信号
        Thread worker = new Thread(() -> {
            while (running) {
                try {
                    long cost = System.currentTimeMillis();
                    labRepository.findByUserSlow(1 + java.util.concurrent.ThreadLocalRandom.current().nextInt(500_000));
                    lastQueryMs = System.currentTimeMillis() - cost;
                    backgroundQueries.incrementAndGet();
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("[slow-sql] 后台慢查询异常(不影响教学): {}", e.getMessage());
                    break;
                }
            }
        }, "slow-sql-worker");
        worker.setDaemon(true);
        worker.start();

        log.warn("[slow-sql-no-index] 故障已激活(索引已确保不存在)。手动体验: "
                + "curl 'http://localhost:8080/api/order/query-by-user?userId=12345' (秒级); "
                + "执行计划: curl 'http://localhost:8080/api/order/explain?userId=12345'");
    }

    @Override
    public void doStop() {
        running = false;
        // 索引状态保留：修复(建索引)与否由演练者自己决定，stop 只停后台查询
        log.info("[slow-sql-no-index] 后台查询已停止。当前索引状态: {} —— "
                        + "(若已建索引可 POST /api/order/reset-index 恢复故障态再玩一次)",
                labRepository.userIndexExists() ? "已存在(已修复)" : "不存在(故障态)");
    }

    @Override
    public Map<String, Object> buildStatus() {
        return Map.of(
                "userIndexExists", labRepository.userIndexExists(),
                "backgroundQueries", backgroundQueries.get(),
                "lastQueryMs", lastQueryMs,
                "fixEndpoint", "POST /api/order/fix-index",
                "explainEndpoint", "GET /api/order/explain?userId=12345"
        );
    }
}
