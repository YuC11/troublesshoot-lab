package com.lab.troubleshoot.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 演练数据访问层（表结构见 {@code DatabaseInitializer} 的注释，含「故意无索引」的教学设计）。
 *
 * <p>所有查询方法都打点到 {@link SqlCounter}——慢 SQL 的证据要从「耗时 + 执行次数」双维度呈现，
 * 这与线上开慢查询日志（long_query_time）是同一个思路。</p>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class LabRepository {

    private final JdbcTemplate jdbc;
    private final SqlCounter sqlCounter;

    // ==================== 正常业务查询（对照组） ====================

    /** 主键点查：正常情况 <5ms。场景 16 触发后，它会因拿不到连接而卡 10s 后报错——「无辜接口受害」的教具 */
    public Map<String, Object> findByPk(long orderId) {
        return timed("SELECT * FROM orders WHERE order_id=" + orderId,
                () -> jdbc.queryForMap("SELECT order_id, user_id, amount, status, created_at FROM orders WHERE order_id = ?",
                        orderId));
    }

    // ==================== 场景 12：慢 SQL（缺索引） ====================

    /**
     * 病灶查询：user_id 无索引 → 全表扫描 100 万行。
     * remark LIKE 条件进一步抬高每行判定成本（且 LIKE '%x%' 无法走索引，双重教学点）。
     */
    public List<Map<String, Object>> findByUserSlow(int userId) {
        return timed("SELECT order_id,amount,status,remark,created_at FROM orders WHERE user_id=" + userId
                        + " AND remark LIKE '%ab%'",
                () -> jdbc.queryForList("""
                        SELECT order_id, user_id, amount, status, remark, created_at
                        FROM orders
                        WHERE user_id = ? AND remark LIKE '%ab%'
                        ORDER BY created_at DESC
                        LIMIT 20""", userId));
    }

    /** EXPLAIN 执行计划（教学核心：type=ALL 全表扫描 vs ref 索引查找） */
    public List<Map<String, Object>> explainByUser(int userId) {
        return jdbc.queryForList("EXPLAIN SELECT order_id FROM orders WHERE user_id = ? AND remark LIKE '%ab%' "
                + "ORDER BY created_at DESC LIMIT 20", userId);
    }

    /** 修复动作：建索引（教学闭环的「修复 → 验证」一步） */
    public void createUserIndex() {
        jdbc.execute("CREATE INDEX idx_orders_user_id ON orders(user_id)");
        log.info("[场景12] 已创建索引 idx_orders_user_id —— 重新查询验证耗时应降到毫秒级");
    }

    /** 复位动作：删索引，恢复故障态 */
    public void dropUserIndex() {
        jdbc.execute("DROP INDEX idx_orders_user_id ON orders");
        log.info("[场景12] 已删除索引 idx_orders_user_id —— 故障态恢复");
    }

    /** 索引是否存在（status 展示当前库表状态） */
    public boolean userIndexExists() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA='troubleboot_lab' AND TABLE_NAME='orders' AND INDEX_NAME='idx_orders_user_id'",
                Integer.class);
        return n != null && n > 0;
    }

    // ==================== 场景 13：N+1 查询 ====================

    public List<Map<String, Object>> findUsers(int limit) {
        return timed("SELECT * FROM users LIMIT " + limit,
                () -> jdbc.queryForList("SELECT id, username, email FROM users ORDER BY id LIMIT ?", limit));
    }

    /** N+1 中的「1」次/次查询：user_orders.user_id 有索引，单条 <1ms——快查询也会积少成多 */
    public List<Map<String, Object>> findOrdersByUserIndexed(int userId) {
        return timed("SELECT * FROM user_orders WHERE user_id=" + userId,
                () -> jdbc.queryForList("SELECT id, product, amount, created_at FROM user_orders WHERE user_id = ?",
                        userId));
    }

    /** 修复版：一条 IN 批量查询代替 N 次循环查询 */
    public List<Map<String, Object>> findOrdersByUserIds(List<Integer> userIds) {
        String placeholders = String.join(",", java.util.Collections.nCopies(userIds.size(), "?"));
        return timed("SELECT * FROM user_orders WHERE user_id IN (...)",
                () -> jdbc.queryForList("SELECT id, user_id, product, amount, created_at FROM user_orders "
                        + "WHERE user_id IN (" + placeholders + ")", userIds.toArray()));
    }

    // ==================== 场景 16：连接池耗尽 ====================

    /**
     * SELECT SLEEP：在 MySQL 服务端睡指定秒数——<b>不占业务 CPU 却牢牢占住一个连接</b>，
     * 是模拟「慢 SQL 占坑」的最干净手段（真实等价物：大事务/慢查询持有连接不放）。
     */
    public void sleepOnConnection(double seconds) {
        timed("SELECT SLEEP(" + seconds + ")",
                () -> jdbc.queryForObject("SELECT SLEEP(?)", Integer.class, seconds));
    }

    // ==================== 打点基建 ====================

    /** 统一计时代码：抽成私有泛型方法，避免每个查询重复 System.currentTimeMillis 样板 */
    private <T> T timed(String displaySql, java.util.function.Supplier<T> query) {
        long start = System.currentTimeMillis();
        try {
            return query.get();
        } finally {
            sqlCounter.record(displaySql, System.currentTimeMillis() - start);
        }
    }
}
