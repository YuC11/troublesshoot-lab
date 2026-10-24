package com.lab.troubleshoot.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 演练数据初始化器：应用启动时自动建表并灌入测试数据。
 *
 * <p><b>表结构与故障场景的对应关系（教学设计核心，勿随意加索引）：</b></p>
 * <ul>
 *   <li>{@code orders}（100 万行）：{@code user_id} 列<b>故意不建索引</b> —— 场景 12「慢 SQL 缺索引」、
 *       场景 16「连接池耗尽」的病灶。修复端点会动态建/删 {@code idx_orders_user_id} 索引形成教学闭环。</li>
 *   <li>{@code users}（2000 行）+ {@code user_orders}（2 万行，{@code user_id} <b>有</b>索引）：
 *       场景 13「N+1 查询」用——单条查询快（索引），但 1000 次循环查询累积成秒级慢接口，
 *       把「慢」的锅精确隔离到 N+1 模式本身，而不是缺索引。</li>
 * </ul>
 *
 * <p>幂等设计：启动时检查目标行数，达标即跳过，避免重启重复灌数。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseInitializer implements ApplicationRunner {

    /** 教学参数集中处：方便按机器性能调整数据规模 */
    private static final int ORDERS_ROWS = 1_000_000;
    private static final int USERS_ROWS = 2_000;
    private static final int USER_ORDERS_ROWS = 20_000;
    private static final int BATCH_SIZE = 5_000;

    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        createTables();
        seedOrders();
        seedUsers();
        seedUserOrders();
        log.info("[初始化完成] 演练数据就绪，打开控制台 http://localhost:8080 开始演练");
    }

    private void createTables() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS orders (
                    order_id   BIGINT        NOT NULL COMMENT '主键(无业务含义)',
                    user_id    INT           NOT NULL COMMENT '用户ID —— 故意无索引, 场景12病灶',
                    amount     DECIMAL(10,2) NOT NULL,
                    status     VARCHAR(16)   NOT NULL,
                    remark     VARCHAR(64)   NOT NULL,
                    created_at DATETIME      NOT NULL,
                    PRIMARY KEY (order_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表(慢SQL演练)'
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id         INT           NOT NULL,
                    username   VARCHAR(32)   NOT NULL,
                    email      VARCHAR(64)   NOT NULL,
                    created_at DATETIME      NOT NULL,
                    PRIMARY KEY (id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表(N+1演练)'
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS user_orders (
                    id         BIGINT        NOT NULL,
                    user_id    INT           NOT NULL,
                    product    VARCHAR(64)   NOT NULL,
                    amount     DECIMAL(10,2) NOT NULL,
                    created_at DATETIME      NOT NULL,
                    PRIMARY KEY (id),
                    KEY idx_uo_user (user_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户订单表(N+1演练, user_id有索引)'
                """);
    }

    private void seedOrders() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM orders", Long.class);
        if (count != null && count >= ORDERS_ROWS) {
            log.info("[初始化] orders 已有 {} 行, 跳过灌数", count);
            return;
        }
        log.info("[初始化] 开始灌入 orders {} 行（rewriteBatchedStatements 批量, 预计 15~40s）...", ORDERS_ROWS);
        long start = System.currentTimeMillis();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        String[] statuses = {"PAID", "PENDING", "CANCELLED", "REFUNDED"};
        LocalDateTime base = LocalDateTime.of(2025, 1, 1, 0, 0, 0);
        for (int from = 1; from <= ORDERS_ROWS; ) {
            int to = Math.min(from + BATCH_SIZE, ORDERS_ROWS + 1);
            jdbc.batchUpdate("INSERT INTO orders(order_id, user_id, amount, status, remark, created_at) VALUES (?,?,?,?,?,?)",
                    range(from, to).mapToObj(i -> new Object[]{
                            (long) i,
                            // user_id 范围 1~50万: 保证等值查询命中约 2 行, 模拟真实稀疏度
                            1 + rnd.nextInt(500_000),
                            rnd.nextInt(10_000) / 100.0,
                            statuses[rnd.nextInt(statuses.length)],
                            randomRemark(rnd),
                            base.plusSeconds(rnd.nextInt(60_000_000))
                    }).toList());
            from = to;
            if (from % 200_000 == 0 || from > ORDERS_ROWS) {
                log.info("[初始化] orders 进度 {}/{} ({} ms)", Math.min(from, ORDERS_ROWS), ORDERS_ROWS,
                        System.currentTimeMillis() - start);
            }
        }
        log.info("[初始化] orders 灌数完成, 耗时 {} ms", System.currentTimeMillis() - start);
    }

    private void seedUsers() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        if (count != null && count >= USERS_ROWS) {
            log.info("[初始化] users 已有 {} 行, 跳过灌数", count);
            return;
        }
        jdbc.batchUpdate("INSERT INTO users(id, username, email, created_at) VALUES (?,?,?,?)",
                java.util.stream.IntStream.rangeClosed(1, USERS_ROWS)
                        .mapToObj(i -> new Object[]{i, "user_" + i, "user" + i + "@lab.com",
                                LocalDateTime.of(2024, 1, 1, 0, 0).plusMinutes(i)})
                        .toList());
        log.info("[初始化] users 灌入 {} 行", USERS_ROWS);
    }

    private void seedUserOrders() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM user_orders", Long.class);
        if (count != null && count >= USER_ORDERS_ROWS) {
            log.info("[初始化] user_orders 已有 {} 行, 跳过灌数", count);
            return;
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        jdbc.batchUpdate("INSERT INTO user_orders(id, user_id, product, amount, created_at) VALUES (?,?,?,?,?)",
                java.util.stream.IntStream.rangeClosed(1, USER_ORDERS_ROWS)
                        .mapToObj(i -> new Object[]{(long) i, 1 + rnd.nextInt(USERS_ROWS),
                                "product-" + rnd.nextInt(1000), rnd.nextInt(50_000) / 100.0,
                                LocalDateTime.of(2025, 6, 1, 0, 0).plusMinutes(i)})
                        .toList());
        log.info("[初始化] user_orders 灌入 {} 行", USER_ORDERS_ROWS);
    }

    /** remark 定长 64 字符 —— 拉宽行体积, 让无索引全表扫描的代价更真实(纯随机文本) */
    private static String randomRemark(ThreadLocalRandom rnd) {
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < 64; i++) {
            sb.append((char) ('a' + rnd.nextInt(26)));
        }
        return sb.toString();
    }

    private static java.util.stream.IntStream range(int fromInclusive, int toExclusive) {
        return java.util.stream.IntStream.range(fromInclusive, toExclusive);
    }
}
