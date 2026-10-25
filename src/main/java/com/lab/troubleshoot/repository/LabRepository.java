package com.lab.troubleshoot.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 演练数据访问层（企业级订单域 schema 见 {@code DatabaseInitializer}）。
 *
 * <p>场景 12 的病灶不再是「没索引」，而是<b>索引用不上</b>：orders 表带着
 * 错误列序索引 idx_created_user(created_at, user_id)，「我的订单」查询
 * WHERE user_id=? ORDER BY created_at DESC 违反最左前缀 → 依然全表扫描。
 * 这是企业慢 SQL 的真实形态。</p>
 *
 * <p>所有查询方法都打点到 {@link SqlCounter}——慢 SQL 的证据要从「耗时 + 执行次数」
 * 双维度呈现，这与线上开慢查询日志（long_query_time）是同一个思路。</p>
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
                () -> jdbc.queryForMap("""
                        SELECT order_id, order_no, user_id, order_status, pay_amount, created_at
                        FROM orders WHERE order_id = ?""", orderId));
    }

    // ==================== 场景 12：慢 SQL（索引用不上） ====================

    /**
     * 病灶查询：「我的订单」列表（C 端最热 SQL）。
     * 表上存在 idx_created_user(created_at, user_id)——但等值列 user_id 不在索引最左，
     * 违反最左前缀原则 → 优化器只能全表扫描。
     */
    public List<Map<String, Object>> findMyOrders(long userId) {
        return timed("SELECT order_id,order_status,pay_amount,created_at FROM orders WHERE user_id=" + userId
                        + " ORDER BY created_at DESC LIMIT 20",
                () -> jdbc.queryForList("""
                        SELECT order_id, order_status, pay_amount, created_at
                        FROM orders
                        WHERE user_id = ?
                        ORDER BY created_at DESC
                        LIMIT 20""", userId));
    }

    /** EXPLAIN 执行计划：教学核心看点是 possible_keys 有名字但 key=NULL（有索引却不用） */
    public List<Map<String, Object>> explainMyOrders(long userId) {
        return jdbc.queryForList("EXPLAIN SELECT order_id, order_status, pay_amount, created_at FROM orders "
                + "WHERE user_id = ? ORDER BY created_at DESC LIMIT 20", userId);
    }

    /** 修复①：建正确列序复合索引（等值列在前、范围/排序列在后） */
    public void createUserIndex() {
        jdbc.execute("CREATE INDEX idx_user_created ON orders(user_id, created_at)");
        log.info("[场景12] 已创建正确索引 idx_user_created(user_id, created_at) —— 复查耗时与执行计划");
    }

    /** 病灶兜底：重建错误列序索引（库被重建/误删时恢复故障态用） */
    public void createWrongIndex() {
        jdbc.execute("CREATE INDEX idx_created_user ON orders(created_at, user_id)");
        log.info("[场景12] 已重建错误列序索引 idx_created_user(created_at, user_id) —— 故障态就绪");
    }

    /** 修复②(进阶)：覆盖索引——列表页字段全部进索引, 免回表(EXPLAIN 出现 Using index) */
    public void createUserCoveringIndex() {
        jdbc.execute("CREATE INDEX idx_user_covering ON orders(user_id, created_at, order_status, pay_amount)");
        log.info("[场景12] 已创建覆盖索引 idx_user_covering —— EXPLAIN Extra 应出现 Using index");
    }

    /** 删除指定索引（建新删旧, 防止冗余索引拖累写入——企业索引治理基本功） */
    public void dropIndex(String indexName) {
        jdbc.execute("DROP INDEX " + indexName + " ON orders");
        log.info("[场景12] 已删除索引 {} ", indexName);
    }

    /** 指定索引是否存在 */
    public boolean indexExists(String indexName) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA='troubleboot_lab' AND TABLE_NAME='orders' AND INDEX_NAME=?",
                Integer.class, indexName);
        return n != null && n > 0;
    }

    // ==================== 场景 12 扩展：四大索引杀手对照 ====================

    /** 通用耗时执行器：返回 ms（索引杀手「坏写法 vs 好写法」对照用） */
    public long costOf(String sql, Object... args) {
        long start = System.currentTimeMillis();
        jdbc.queryForList(sql, args);
        long cost = System.currentTimeMillis() - start;
        sqlCounter.record(sql.length() > 90 ? sql.substring(0, 90) + "..." : sql, cost);
        return cost;
    }

    /** 坏写法：order_no 列是 VARCHAR 却用数字比较（隐式转换杀索引） */
    public String killerCastSqlBad(long orderNoNumeric) {
        return "SELECT order_id, order_no FROM orders WHERE order_no = " + orderNoNumeric;
    }

    /** 好写法：字符串等值命中 uk_order_no (const 级) */
    public String killerCastSqlGood(String orderNo) {
        return "SELECT order_id, order_no FROM orders WHERE order_no = '" + orderNo + "'";
    }

    /** 坏写法：前导通配 LIKE（无法走索引, 全表扫 remark） */
    public String killerLikeSqlBad() {
        return "SELECT order_id FROM orders WHERE buyer_remark LIKE '%生日%'";
    }

    /** 好写法：右匹配可走索引（此处用 uk_order_no 前缀演示） */
    public String killerLikeSqlGood() {
        return "SELECT order_id, order_no FROM orders WHERE order_no LIKE 'SO2025%'  LIMIT 20";
    }

    /** 坏写法：列上套函数 DATE()（索引列参与运算即失效） */
    public String killerFuncSqlBad() {
        return "SELECT COUNT(*) FROM orders WHERE DATE(created_at) = '2025-06-01'";
    }

    /** 好写法：改写为范围查询, 索引列保持「裸列」 */
    public String killerFuncSqlGood() {
        return "SELECT COUNT(*) FROM orders WHERE created_at >= '2025-06-01 00:00:00' AND created_at < '2025-06-02 00:00:00'";
    }

    // ==================== 场景 13：N+1 查询（order_item 300万行） ====================

    public List<Map<String, Object>> findUsers(int limit) {
        return timed("SELECT * FROM users LIMIT " + limit,
                () -> jdbc.queryForList("SELECT id, username, email FROM users ORDER BY id LIMIT ?", limit));
    }

    /** N+1 中的「N」次查询：order_item.user_id 有索引, 单条 <2ms——快查询也会积少成多 */
    public List<Map<String, Object>> findItemsByUserIndexed(long userId) {
        return timed("SELECT * FROM order_item WHERE user_id=" + userId,
                () -> jdbc.queryForList("SELECT id, order_id, sku_id, sku_name, price, quantity FROM order_item WHERE user_id = ?",
                        userId));
    }

    /** 修复版：一条 IN 批量查询代替 N 次循环查询 */
    public List<Map<String, Object>> findItemsByUserIds(List<Long> userIds) {
        String placeholders = String.join(",", java.util.Collections.nCopies(userIds.size(), "?"));
        return timed("SELECT * FROM order_item WHERE user_id IN (...)",
                () -> jdbc.queryForList("SELECT id, user_id, order_id, sku_id, sku_name, price, quantity FROM order_item "
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
