package com.lab.troubleshoot.fault.slowapi;

import com.lab.troubleshoot.core.AbstractFaultScenario;
import com.lab.troubleshoot.core.FaultCategory;
import com.lab.troubleshoot.repository.SqlCounter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 场景 13：N+1 查询 —— 「每条 SQL 都不慢，接口却秒级」的教学。
 *
 * <p>【病灶原型】先查 N 条主记录，再循环逐条查关联数据：N+1 次 SQL。
 * 单条查询有索引、毫秒级完成，慢查询日志<b>抓不到它</b>；但 500 用户 × 2ms
 * + 网络往返累积 = 秒级接口。N+1 是「慢查询日志正常、接口却慢」的头号嫌疑。</p>
 *
 * <p>【实现】本场景只负责打开 {@link SqlCounter} 的 verbose 开关（每条 SQL
 * 打带序号日志——让「第 501 条 SQL」能从控制台日志直接数出来）并展示状态；
 * 演练动作用业务接口：{@code GET /api/order/top-spenders?limit=500&mode=n+1}
 * （病灶版）vs {@code mode=batch}（修复版）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NPlusOneScenario extends AbstractFaultScenario {

    private final SqlCounter sqlCounter;

    @Override
    public String id() { return "slow-n-plus-1"; }

    @Override
    public String name() { return "N+1 查询：循环逐条查关联"; }

    @Override
    public FaultCategory category() { return FaultCategory.SLOW_API; }

    @Override
    public int order() { return 13; }

    @Override
    public String alertText() {
        return "【P2告警】/api/order/top-spenders P99 1.9s; 慢查询日志(long_query_time=1s)无新增记录, "
                + "DB CPU 正常 —— 接口慢但数据库『无辜』";
    }

    @Override
    public String symptom() {
        return "500 用户 × 逐条查订单 = 501 条 SQL，单条 <1ms 但接口秒级；慢查询日志抓不到。"
                + "触发后用 /api/order/top-spenders?mode=n+1 体验, mode=batch 是修复版。对照 docs/13-N+1查询.md。";
    }

    @Override
    public String docFile() { return "13-N+1查询.md"; }

    @Override
    public void doTrigger(Map<String, String> params) {
        // 打开逐条 SQL 日志：让 N+1 的「量」从日志里直接可见（等价线上开 SQL trace）
        sqlCounter.enableVerbose();
        log.warn("[slow-n-plus-1] SQL 逐条日志已开启。体验: curl 'http://localhost:8080/api/order/top-spenders?limit=500&mode=n%2B1' "
                + "—— 观察应用日志 SQL 刷屏与总耗时; 再用 mode=batch 对比");
    }

    @Override
    public void doStop() {
        sqlCounter.disableVerbose();
        log.info("[slow-n-plus-1] SQL 日志已关闭");
    }

    @Override
    public Map<String, Object> buildStatus() {
        return Map.of(
                "verboseSqlLog", sqlCounter.isVerbose(),
                "sqlCountThisWindow", sqlCounter.getCount().get(),
                "totalSqlCostMs", sqlCounter.getTotalCostMs().get(),
                "businessEndpoint", "GET /api/order/top-spenders?limit=500&mode=n+1 | mode=batch"
        );
    }
}
