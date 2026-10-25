package com.lab.troubleshoot.web;

import com.lab.troubleshoot.fault.slowapi.SlowSqlIndexFailureScenario;
import com.lab.troubleshoot.repository.LabRepository;
import com.lab.troubleshoot.repository.SqlCounter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单业务接口 —— 多个场景的「主战场」（企业级订单域 schema）：
 *
 * <ul>
 *   <li>{@code GET /api/health/ping}：永远快的探针——<b>分层判断</b>的第一锤。</li>
 *   <li>{@code GET /api/order/{id}}：主键毫秒级点查——场景 16 的「无辜受害接口」。</li>
 *   <li>{@code GET /api/order/my-orders}：场景 12 的慢 SQL 主体验接口（我的订单列表）。</li>
 *   <li>{@code GET /api/order/explain}：执行计划（教学看点：possible_keys 有名字 / key=NULL）。</li>
 *   <li>{@code POST /api/order/fix-index[?covering=true]}：修复——建正确列序/覆盖索引并删冗余旧索引。</li>
 *   <li>{@code POST /api/order/reset-index}：复位到故障态（只剩错误列序索引）。</li>
 *   <li>{@code GET /api/order/index-killer?killer=cast|like|func}：四大索引杀手「坏写法 vs 好写法」对照。</li>
 *   <li>{@code GET /api/order/top-spenders?mode=n+1|batch}：场景 13 的 N+1 vs 批量（order_item 300万行）。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class OrderController {

    private final LabRepository labRepository;
    private final SqlCounter sqlCounter;

    /** 永快的健康探针：不碰 DB 不碰池。线上排查先打它——快则问题在「链路」而非「进程」 */
    @GetMapping("/api/health/ping")
    public Map<String, Object> ping() {
        return Map.of("status", "pong", "ts", System.currentTimeMillis());
    }

    /** 正常接口：主键点查毫秒级。场景 16 触发后它会因拿不到连接 10s 报错——无辜受害 */
    @GetMapping("/api/order/{id}")
    public Map<String, Object> byId(@PathVariable long id) {
        long start = System.currentTimeMillis();
        Map<String, Object> order;
        try {
            order = labRepository.findByPk(id);
        } catch (EmptyResultDataAccessException e) {
            // 查无此单是正常业务分支而非服务器错误——404 与 500 的区分是线上 API 的基本修养
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在: " + id);
        }
        Map<String, Object> resp = new HashMap<>(order);
        resp.put("costMs", System.currentTimeMillis() - start);
        return resp;
    }

    // ==================== 场景 12：慢 SQL（索引用不上） ====================

    /** 场景 12 体验接口：C 端最热的「我的订单」列表。错误列序索引下全表扫描秒级；修复后毫秒级 */
    @GetMapping("/api/order/my-orders")
    public Map<String, Object> myOrders(@RequestParam long userId) {
        long start = System.currentTimeMillis();
        List<Map<String, Object>> rows = labRepository.findMyOrders(userId);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("userId", userId);
        resp.put("matched", rows.size());
        resp.put("costMs", System.currentTimeMillis() - start);
        resp.put("hint", "看执行计划: GET /api/order/explain?userId=" + userId
                + " | 修复: POST /api/order/fix-index");
        return resp;
    }

    /** 场景 12 定位接口：EXPLAIN——「possible_keys 有名字但 key=NULL」即「有索引用不上」的铁证 */
    @GetMapping("/api/order/explain")
    public Map<String, Object> explain(@RequestParam(defaultValue = "12345") long userId) {
        return Map.of("userId", userId, "plan", labRepository.explainMyOrders(userId));
    }

    /**
     * 场景 12 修复动作：建正确列序索引 + 删除冗余旧索引（建新必删旧）。
     * covering=true 时进阶为覆盖索引（并删掉普通正确索引——避免索引越堆越多）。
     */
    @PostMapping("/api/order/fix-index")
    public Map<String, Object> fixIndex(@RequestParam(defaultValue = "false") boolean covering) {
        String created;
        if (covering) {
            if (labRepository.indexExists(SlowSqlIndexFailureScenario.COVERING_INDEX)) {
                return Map.of("action", "fix-index", "result", "覆盖索引已存在, 无需重复创建");
            }
            labRepository.createUserCoveringIndex();
            created = SlowSqlIndexFailureScenario.COVERING_INDEX;
            // 覆盖索引已包含 (user_id, created_at) 前缀, 普通版变冗余 → 删（索引治理: 宁缺毋滥）
            if (labRepository.indexExists(SlowSqlIndexFailureScenario.RIGHT_INDEX)) {
                labRepository.dropIndex(SlowSqlIndexFailureScenario.RIGHT_INDEX);
            }
        } else {
            if (labRepository.indexExists(SlowSqlIndexFailureScenario.RIGHT_INDEX)
                    || labRepository.indexExists(SlowSqlIndexFailureScenario.COVERING_INDEX)) {
                return Map.of("action", "fix-index", "result", "正确索引已存在, 可试 ?covering=true 进阶");
            }
            labRepository.createUserIndex();
            created = SlowSqlIndexFailureScenario.RIGHT_INDEX;
        }
        // 建新必删旧：错误列序索引已完成历史使命, 留着只会拖累写入（冗余索引治理）
        if (labRepository.indexExists(SlowSqlIndexFailureScenario.WRONG_INDEX)) {
            labRepository.dropIndex(SlowSqlIndexFailureScenario.WRONG_INDEX);
        }
        return Map.of("action", "fix-index", "created", created,
                "result", "已建 " + created + " 并删除冗余旧索引 —— 重新 GET /api/order/my-orders 验证耗时");
    }

    /** 场景 12 复位动作：删除正确/覆盖索引, 恢复「只有错误列序索引」的故障态 */
    @PostMapping("/api/order/reset-index")
    public Map<String, Object> resetIndex() {
        if (labRepository.indexExists(SlowSqlIndexFailureScenario.COVERING_INDEX)) {
            labRepository.dropIndex(SlowSqlIndexFailureScenario.COVERING_INDEX);
        }
        if (labRepository.indexExists(SlowSqlIndexFailureScenario.RIGHT_INDEX)) {
            labRepository.dropIndex(SlowSqlIndexFailureScenario.RIGHT_INDEX);
        }
        if (!labRepository.indexExists(SlowSqlIndexFailureScenario.WRONG_INDEX)) {
            labRepository.createWrongIndex();
        }
        return Map.of("action", "reset-index", "result", "已恢复故障态(只剩错误列序索引 idx_created_user)");
    }

    /**
     * 四大索引杀手对照教具：同表同数据, 「坏写法 vs 好写法」的耗时与差值一目了然。
     * killer=cast 隐式类型转换 | like 前导通配 | func 列上套函数
     */
    @GetMapping("/api/order/index-killer")
    public Map<String, Object> indexKiller(@RequestParam(defaultValue = "cast") String killer) {
        long badMs;
        long goodMs;
        String badSql;
        String goodSql;
        String lesson;
        switch (killer) {
            case "like" -> {
                badSql = labRepository.killerLikeSqlBad();
                goodSql = labRepository.killerLikeSqlGood();
                badMs = labRepository.costOf(badSql);
                goodMs = labRepository.costOf(goodSql);
                lesson = "前导通配 LIKE '%x%' 无法走索引; 右匹配 'x%' 可以走索引(最左前缀)。要做全文检索请用全文索引/ES";
            }
            case "func" -> {
                badSql = labRepository.killerFuncSqlBad();
                goodSql = labRepository.killerFuncSqlGood();
                badMs = labRepository.costOf(badSql);
                goodMs = labRepository.costOf(goodSql);
                lesson = "索引列参与函数/运算 (DATE(created_at)=…) 即失效; 改写成范围查询, 保持「裸列」";
            }
            default -> {
                killer = "cast";
                // 坏写法: order_no 是 VARCHAR 却拿数字字面量比较 → 每行隐式转数字, uk_order_no 失效
                badSql = labRepository.killerCastSqlBad(202500000001L);
                goodSql = labRepository.killerCastSqlGood("SO202500000001");
                badMs = labRepository.costOf(badSql);
                goodMs = labRepository.costOf(goodSql);
                lesson = "字符串列与数字比较 → MySQL 把「每一行的列值」转成数字, 索引失效。参数类型必须与列类型一致";
            }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("killer", killer);
        resp.put("badSql", badSql);
        resp.put("badCostMs", badMs);
        resp.put("goodSql", goodSql);
        resp.put("goodCostMs", goodMs);
        resp.put("differenceTimes", badMs <= 0 ? "N/A" : Math.max(goodMs, 1) + "ms vs " + badMs + "ms");
        resp.put("lesson", lesson);
        return resp;
    }

    // ==================== 场景 13：N+1 查询（order_item 300万行） ====================

    /**
     * N+1（病灶）vs batch（修复）同屏对照。
     * mode=n+1：1 次查用户 + limit 次循环查明细 = limit+1 条 SQL；
     * mode=batch：1 次查用户 + 1 次 IN 批量 = 2 条 SQL。
     */
    @GetMapping("/api/order/top-spenders")
    public Map<String, Object> topSpenders(@RequestParam(defaultValue = "500") int limit,
                                           @RequestParam(defaultValue = "n+1") String mode) {
        sqlCounter.reset();
        long start = System.currentTimeMillis();
        int itemCount;

        List<Map<String, Object>> users = labRepository.findUsers(limit);
        if (mode.equals("batch")) {
            // 修复版：一条 IN 查询拿回全部明细
            List<Long> ids = users.stream().map(u -> ((Number) u.get("id")).longValue()).toList();
            itemCount = labRepository.findItemsByUserIds(ids).size();
        } else {
            // 病灶版：循环逐条查询 —— SQL 条数 = limit + 1（应用日志正在刷屏的就是它）
            itemCount = users.stream()
                    .mapToInt(u -> labRepository.findItemsByUserIndexed(((Number) u.get("id")).longValue()).size())
                    .sum();
        }
        return Map.of(
                "mode", mode,
                "users", users.size(),
                "orderItemsFetched", itemCount,
                "sqlCount", sqlCounter.getCount().get(),
                "costMs", System.currentTimeMillis() - start);
    }
}
