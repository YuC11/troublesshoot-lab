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
 * 场景 12：慢 SQL —— <b>索引用不上</b>（复合索引列序错误）。
 *
 * <p>【病灶原型·企业真实形态】orders 是热点表，上面<b>有</b>一排索引，其中
 * {@code idx_created_user(created_at, user_id)} 列序错了——「我的订单」查询
 * {@code WHERE user_id=? ORDER BY created_at DESC} 的等值列 user_id 不在最左，
 * 违反<b>最左前缀原则</b>，优化器只能全表扫描。EXPLAIN 里
 * {@code possible_keys} 空洞 / {@code key=NULL}，「表上明明有索引却用不上」——
 * 这比「没建索引」常见得多，也隐蔽得多（建索引的人以为修好了）。</p>
 *
 * <p>【演练闭环】trigger（确保病灶索引存在+后台持续慢查询）→
 * curl {@code /api/order/my-orders?userId=12345} 亲测秒级 →
 * {@code /api/order/explain} 看 key=NULL → POST {@code /api/order/fix-index}
 * 建<b>正确列序</b>索引并<b>删除冗余的旧索引</b> → 复查毫秒级+key 命中 →
 * 进阶 {@code /api/order/fix-index?covering=true} 体验覆盖索引（Using index）→
 * {@code /api/order/index-killer?killer=cast|like|func} 看「四大索引杀手」对照。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlowSqlIndexFailureScenario extends AbstractFaultScenario {

    /** 正确列序索引与覆盖索引名：与 LabRepository 保持一致 */
    public static final String WRONG_INDEX = "idx_created_user";
    public static final String RIGHT_INDEX = "idx_user_created";
    public static final String COVERING_INDEX = "idx_user_covering";

    private final LabRepository labRepository;

    private volatile boolean running;
    private final AtomicLong backgroundQueries = new AtomicLong();
    private volatile long lastQueryMs = -1;

    @Override
    public String id() { return "slow-sql-index-failure"; }

    @Override
    public String name() { return "慢 SQL：索引列序错误（有索引用不上）"; }

    @Override
    public FaultCategory category() { return FaultCategory.SLOW_API; }

    @Override
    public int order() { return 12; }

    @Override
    public String alertText() {
        return "【P1告警】/api/order/my-orders P99 2.8s(阈值500ms), 慢查询日志新增 214 条/5min 命中 orders 全表扫描; "
                + "DBA 反馈 orders 表上索引齐全 —— 索引存在但优化器不选它";
    }

    @Override
    public String symptom() {
        return "orders(100万行)带着错误列序索引 idx_created_user(created_at,user_id)，我的订单查询违反最左前缀"
                + "→全表扫描。EXPLAIN 看 key=NULL；fix-index 建正确列序并删冗余旧索引（250ms→毫秒级闭环），"
                + "covering=true 进阶覆盖索引。对照 docs/05-接口慢类场景手册.md#场景12。";
    }

    @Override
    public String docFile() { return "05-接口慢类场景手册.md#场景12"; }

    @Override
    public void doTrigger(Map<String, String> params) {
        // 激活故障 = 清掉上次演练建的正确/覆盖索引, 确保只剩错误列序索引
        if (labRepository.indexExists(COVERING_INDEX)) {
            labRepository.dropIndex(COVERING_INDEX);
        }
        if (labRepository.indexExists(RIGHT_INDEX)) {
            labRepository.dropIndex(RIGHT_INDEX);
        }
        if (!labRepository.indexExists(WRONG_INDEX)) {
            // 库若被清重建或索引被误删, 兜底重建病灶索引
            labRepository.createWrongIndex();
        }
        running = true;
        backgroundQueries.set(0);

        Thread worker = new Thread(() -> {
            while (running) {
                try {
                    long cost = System.currentTimeMillis();
                    labRepository.findMyOrders(1 + java.util.concurrent.ThreadLocalRandom.current().nextInt(500_000));
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

        log.warn("[slow-sql-index-failure] 故障已激活(错误列序索引 {} 存在, 正确索引不存在)。体验: "
                        + "curl 'http://localhost:8080/api/order/my-orders?userId=12345'; 执行计划: /api/order/explain?userId=12345",
                WRONG_INDEX);
    }

    @Override
    public void doStop() {
        running = false;
        log.info("[slow-sql-index-failure] 后台查询已停止。索引状态: wrong={}, right={}, covering={} —— "
                        + "修复与否由演练者决定(POST /api/order/fix-index), reset-index 可恢复故障态",
                labRepository.indexExists(WRONG_INDEX), labRepository.indexExists(RIGHT_INDEX),
                labRepository.indexExists(COVERING_INDEX));
    }

    @Override
    public Map<String, Object> buildStatus() {
        return Map.of(
                "wrongIndexExists(" + WRONG_INDEX + ")", labRepository.indexExists(WRONG_INDEX),
                "rightIndexExists(" + RIGHT_INDEX + ")", labRepository.indexExists(RIGHT_INDEX),
                "coveringIndexExists", labRepository.indexExists(COVERING_INDEX),
                "backgroundQueries", backgroundQueries.get(),
                "lastQueryMs", lastQueryMs,
                "fixEndpoint", "POST /api/order/fix-index[?covering=true]",
                "killerEndpoint", "GET /api/order/index-killer?killer=cast|like|func"
        );
    }
}
