package com.lab.troubleshoot.web;

import com.lab.troubleshoot.repository.LabRepository;
import com.lab.troubleshoot.repository.SqlCounter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单业务接口 —— 多个场景的「主战场」：
 *
 * <ul>
 *   <li>{@code GET /api/health/ping}：永远快的探针——<b>分层判断</b>的第一锤
 *       （它快 = 进程没死透/GC 没风暴，问题在特定链路）。</li>
 *   <li>{@code GET /api/order/{id}}：主键毫秒级点查——场景 16 的「无辜受害接口」。</li>
 *   <li>{@code GET /api/order/query-by-user}：场景 12 的慢 SQL 主体验接口。</li>
 *   <li>{@code GET /api/order/explain | fix-index | reset-index}：场景 12 的定位/修复/复位闭环。</li>
 *   <li>{@code GET /api/order/top-spenders}：场景 13 的 N+1 vs 批量对照。</li>
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
        Map<String, Object> order = labRepository.findByPk(id);
        Map<String, Object> resp = new HashMap<>(order);
        resp.put("costMs", System.currentTimeMillis() - start);
        return resp;
    }

    /** 场景 12 体验接口：user_id 无索引 → 全表扫描 100 万行（建索引后变毫秒级） */
    @GetMapping("/api/order/query-by-user")
    public Map<String, Object> queryByUser(@RequestParam int userId) {
        long start = System.currentTimeMillis();
        List<Map<String, Object>> rows = labRepository.findByUserSlow(userId);
        return Map.of("userId", userId, "matched", rows.size(), "costMs", System.currentTimeMillis() - start);
    }

    /** 场景 12 定位接口：EXPLAIN 执行计划——type=ALL(全表扫描) vs ref(索引) 的铁证 */
    @GetMapping("/api/order/explain")
    public Map<String, Object> explain(@RequestParam(defaultValue = "12345") int userId) {
        return Map.of("userId", userId, "plan", labRepository.explainByUser(userId));
    }

    /** 场景 12 修复动作：建索引。建完再查 query-by-user，耗时秒级 → 毫秒级 */
    @PostMapping("/api/order/fix-index")
    public Map<String, Object> fixIndex() {
        if (labRepository.userIndexExists()) {
            return Map.of("action", "fix-index", "result", "索引已存在, 无需重复创建");
        }
        labRepository.createUserIndex();
        return Map.of("action", "fix-index", "result", "idx_orders_user_id 已创建 —— "
                + "重新 GET /api/order/query-by-user?userId=12345 验证耗时");
    }

    /** 场景 12 复位动作：删索引回到故障态（反复演练用） */
    @PostMapping("/api/order/reset-index")
    public Map<String, Object> resetIndex() {
        if (labRepository.userIndexExists()) {
            labRepository.dropUserIndex();
        }
        return Map.of("action", "reset-index", "result", "已恢复无索引故障态");
    }

    /**
     * 场景 13 体验接口：N+1（病灶）vs batch（修复）同屏对照。
     * mode=n+1：1 次查用户 + limit 次循环查订单 = limit+1 条 SQL；
     * mode=batch：1 次查用户 + 1 次 IN 批量 = 2 条 SQL。
     */
    @GetMapping("/api/order/top-spenders")
    public Map<String, Object> topSpenders(@RequestParam(defaultValue = "500") int limit,
                                           @RequestParam(defaultValue = "n+1") String mode) {
        sqlCounter.reset();
        long start = System.currentTimeMillis();
        int orderCount;

        List<Map<String, Object>> users = labRepository.findUsers(limit);
        if (mode.equals("batch")) {
            // 修复版：一条 IN 查询拿回全部关联订单
            List<Integer> ids = users.stream().map(u -> ((Number) u.get("id")).intValue()).toList();
            orderCount = labRepository.findOrdersByUserIds(ids).size();
        } else {
            // 病灶版：循环逐条查询 —— SQL 条数 = limit + 1（应用日志正在刷屏的就是它）
            orderCount = users.stream()
                    .mapToInt(u -> labRepository.findOrdersByUserIndexed(((Number) u.get("id")).intValue()).size())
                    .sum();
        }
        return Map.of(
                "mode", mode,
                "users", users.size(),
                "ordersFetched", orderCount,
                "sqlCount", sqlCounter.getCount().get(),
                "costMs", System.currentTimeMillis() - start);
    }
}
