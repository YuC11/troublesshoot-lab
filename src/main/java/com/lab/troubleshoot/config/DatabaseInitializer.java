package com.lab.troubleshoot.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 演练数据初始化器：按<b>企业级订单域</b>建模建表并灌数。
 *
 * <p><b>表结构与故障场景的对应关系（教学设计核心）：</b></p>
 * <ul>
 *   <li>{@code orders}（100 万行，29 字段）：热点订单主表。<b>故意只建了「错误列序」的
 *       user 维度索引 {@code idx_created_user(created_at, user_id)}</b>——表上有索引，
 *       但「我的订单」查询 {@code WHERE user_id=? ORDER BY created_at DESC} 违反最左前缀
 *       用不上 → 场景 12「索引用不上」的真实病灶。修复端点会建正确索引并删冗余。</li>
 *   <li>{@code order_item}（300 万行）：订单商品明细子表（含 SKU 快照），user_id 有索引 ——
 *       场景 13「N+1 查询」的载体：单条快、循环慢。</li>
 *   <li>{@code order_payment} / {@code order_status_log}：支付流水与状态流转日志，
 *       完整还原企业订单域（本演练不直接使用，供读表结构教学）。</li>
 * </ul>
 *
 * <p><b>幂等与自愈</b>：检测 orders 列结构，发现旧版简化表（无 order_no 列）自动
 * 重建 —— 升级 schema 不需要手工清库。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseInitializer implements ApplicationRunner {

    /** 教学参数集中处：方便按机器性能调整数据规模 */
    private static final int ORDERS_ROWS = 1_000_000;
    private static final int ORDER_ITEM_ROWS = 3_000_000;
    private static final int USERS_ROWS = 2_000;
    private static final int PAYMENT_ROWS = 200_000;
    private static final int STATUS_LOG_ROWS = 300_000;
    private static final int BATCH_SIZE = 5_000;

    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        migrateLegacyIfNeeded();
        createTables();
        seedUsers();
        seedOrders();
        seedOrderItem();
        seedPaymentAndLog();
        log.info("[初始化完成] 演练数据就绪，打开控制台 http://localhost:8080 开始演练");
    }

    /** 旧版简化 orders 表（无 order_no 列）自动升级：删旧表重建，避免手工清库 */
    private void migrateLegacyIfNeeded() {
        Integer cols = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA='troubleboot_lab' AND TABLE_NAME='orders' AND COLUMN_NAME='order_no'",
                Integer.class);
        if (cols != null && cols == 0) {
            log.warn("[初始化] 检测到旧版简化表结构, 自动重建为新企业级 schema（数据将重灌）");
            jdbc.execute("DROP TABLE IF EXISTS orders");
            jdbc.execute("DROP TABLE IF EXISTS user_orders"); // 旧版 N+1 表, 已由 order_item 取代
        }
    }

    private void createTables() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id             INT          NOT NULL,
                    username       VARCHAR(32)  NOT NULL,
                    email          VARCHAR(64)  NOT NULL,
                    status         TINYINT      NOT NULL DEFAULT 1 COMMENT '1正常 2冻结 3注销',
                    register_channel TINYINT    NOT NULL DEFAULT 1 COMMENT '1iOS 2Android 3H5 4小程序',
                    last_login_at  DATETIME     NULL,
                    created_at     DATETIME     NOT NULL,
                    PRIMARY KEY (id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表'
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS orders (
                    order_id        BIGINT        NOT NULL COMMENT '雪花ID主键',
                    order_no        VARCHAR(32)   NOT NULL COMMENT '对外业务单号(客服/支付回调)',
                    user_id         BIGINT        NOT NULL COMMENT '买家',
                    merchant_id     BIGINT        NOT NULL COMMENT '商家',
                    channel         TINYINT       NOT NULL COMMENT '1iOS 2Android 3H5 4小程序 5PC',
                    order_status    TINYINT       NOT NULL COMMENT '1待支付 2已支付 3已发货 4已完成 5已取消 6退款中',
                    pay_type        TINYINT       NULL COMMENT '1微信 2支付宝 3余额 4货到付款',
                    total_amount    DECIMAL(12,2) NOT NULL,
                    discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
                    pay_amount      DECIMAL(12,2) NOT NULL,
                    freight_amount  DECIMAL(10,2) NOT NULL DEFAULT 0,
                    currency        CHAR(3)       NOT NULL DEFAULT 'CNY',
                    coupon_id       BIGINT        NULL,
                    activity_id     BIGINT        NULL,
                    receiver_name   VARCHAR(64)   NOT NULL COMMENT '收货快照: 下单时刻固化',
                    receiver_phone  VARCHAR(20)   NOT NULL,
                    province_code   INT           NOT NULL,
                    city_code       INT           NOT NULL,
                    address         VARCHAR(255)  NOT NULL,
                    buyer_remark    VARCHAR(255)  NOT NULL DEFAULT '',
                    seller_remark   VARCHAR(255)  NOT NULL DEFAULT '',
                    pay_time        DATETIME      NULL,
                    delivery_time   DATETIME      NULL,
                    finish_time     DATETIME      NULL,
                    cancel_time     DATETIME      NULL,
                    is_deleted      TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
                    version         INT           NOT NULL DEFAULT 0 COMMENT '乐观锁',
                    created_at      DATETIME      NOT NULL,
                    updated_at      DATETIME      NOT NULL,
                    PRIMARY KEY (order_id),
                    UNIQUE KEY uk_order_no (order_no),
                    KEY idx_created_user (created_at, user_id),
                    KEY idx_merchant_status_created (merchant_id, order_status, created_at),
                    KEY idx_status_created (order_status, created_at),
                    KEY idx_pay_time (pay_time)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单主表(热点表)'
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS order_item (
                    id         BIGINT        NOT NULL,
                    order_id   BIGINT        NOT NULL,
                    user_id    BIGINT        NOT NULL COMMENT '冗余列: 分表后免join的常见设计',
                    sku_id     BIGINT        NOT NULL,
                    sku_name   VARCHAR(128)  NOT NULL COMMENT 'SKU快照: 商品改名不影响历史',
                    price      DECIMAL(10,2) NOT NULL,
                    quantity   INT           NOT NULL,
                    created_at DATETIME      NOT NULL,
                    PRIMARY KEY (id),
                    KEY idx_oi_user (user_id),
                    KEY idx_oi_order (order_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单商品明细(主子表)'
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS order_payment (
                    id            BIGINT        NOT NULL,
                    order_id      BIGINT        NOT NULL,
                    pay_type      TINYINT       NOT NULL,
                    pay_no        VARCHAR(64)   NOT NULL COMMENT '三方支付流水号',
                    amount        DECIMAL(12,2) NOT NULL,
                    pay_status    TINYINT       NOT NULL COMMENT '0处理中 1成功 2失败',
                    paid_at       DATETIME      NOT NULL,
                    created_at    DATETIME      NOT NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_pay_no (pay_no),
                    KEY idx_op_order (order_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付流水(一笔订单多次尝试)'
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS order_status_log (
                    id           BIGINT      NOT NULL,
                    order_id     BIGINT      NOT NULL,
                    from_status  TINYINT     NULL,
                    to_status    TINYINT     NOT NULL,
                    operator     VARCHAR(32) NOT NULL COMMENT 'system/客服工号',
                    created_at   DATETIME    NOT NULL,
                    PRIMARY KEY (id),
                    KEY idx_osl_order (order_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='状态流转日志(审计)'
                """);
    }

    // ==================== 灌数 ====================

    private void seedUsers() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        if (count != null && count >= USERS_ROWS) {
            log.info("[初始化] users 已有 {} 行, 跳过灌数", count);
            return;
        }
        jdbc.batchUpdate("INSERT INTO users(id, username, email, status, register_channel, last_login_at, created_at) VALUES (?,?,?,?,?,?,?)",
                java.util.stream.IntStream.rangeClosed(1, USERS_ROWS).mapToObj(i -> new Object[]{
                        i, "user_" + i, "user" + i + "@lab.com",
                        1 + (i % 19 == 0 ? 1 : 0), // 少量冻结用户
                        1 + i % 4,
                        LocalDateTime.of(2026, 10, 1, 0, 0).plusMinutes(i * 7L),
                        LocalDateTime.of(2024, 1, 1, 0, 0).plusMinutes(i)})
                        .toList());
        log.info("[初始化] users 灌入 {} 行", USERS_ROWS);
    }

    private void seedOrders() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM orders", Long.class);
        if (count != null && count >= ORDERS_ROWS) {
            log.info("[初始化] orders 已有 {} 行, 跳过灌数", count);
            return;
        }
        log.info("[初始化] 开始灌入 orders {} 行 × 29 列（企业级宽行, 预计 30~90s）...", ORDERS_ROWS);
        long start = System.currentTimeMillis();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        LocalDateTime base = LocalDateTime.of(2025, 1, 1, 0, 0, 0);
        // 预生成字符串池: 控制灌数速度（不逐行拼随机中文串）
        String[] names = {"张三", "李四", "王五", "赵六", "陈七", "周八", "吴九", "郑十"};
        String[] addrs = {"科技园南路88号A座", "人民大道1001号", "解放路56号3栋", "滨江东路9号", "长安街1号院", "环湖路77号"};
        String[] remarks = {"", "放门口", "周末送达", "家里有婴儿勿按铃", "生日礼物包装", "工作日送达", "易碎轻放", ""};

        for (int from = 1; from <= ORDERS_ROWS; ) {
            int to = Math.min(from + BATCH_SIZE, ORDERS_ROWS + 1);
            jdbc.batchUpdate("""
                            INSERT INTO orders(order_id, order_no, user_id, merchant_id, channel, order_status, pay_type,
                                               total_amount, discount_amount, pay_amount, freight_amount, currency,
                                               coupon_id, activity_id, receiver_name, receiver_phone, province_code,
                                               city_code, address, buyer_remark, seller_remark, pay_time, delivery_time,
                                               finish_time, cancel_time, is_deleted, version, created_at, updated_at)
                            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    range(from, to).mapToObj(i -> {
                        LocalDateTime created = base.plusSeconds(rnd.nextInt(60_000_000));
                        int status = weightedStatus(rnd);
                        boolean paid = status >= 2;
                        long total = 500 + rnd.nextInt(2_000_000); // 分为单位 → 5.00 ~ 20005.00
                        long discount = rnd.nextInt(10) == 0 ? rnd.nextInt(50_000) : 0;
                        return new Object[]{
                                (long) i,
                                "SO" + created.getYear() + String.format("%09d", i),   // 业务单号
                                1 + rnd.nextInt(500_000),                              // user_id 1~50万(稀疏)
                                1 + rnd.nextInt(3_000),                                // merchant
                                1 + rnd.nextInt(5),
                                status,
                                paid ? 1 + rnd.nextInt(4) : null,
                                total / 100.0, discount / 100.0, (total - discount) / 100.0,
                                rnd.nextInt(3) * 600 / 100.0, "CNY",
                                rnd.nextInt(5) == 0 ? (long) rnd.nextInt(100_000) : null,
                                rnd.nextInt(8) == 0 ? (long) rnd.nextInt(10_000) : null,
                                names[rnd.nextInt(names.length)] + i % 100,
                                "1" + String.format("%010d", rnd.nextLong(10_000_000_000L)),
                                100000 + rnd.nextInt(700_000), 1000000 + rnd.nextInt(3_000_000),
                                addrs[rnd.nextInt(addrs.length)],
                                remarks[rnd.nextInt(remarks.length)], "",
                                paid ? created.plusMinutes(2) : null,
                                status >= 3 ? created.plusHours(12) : null,
                                status >= 4 ? created.plusDays(3) : null,
                                status == 5 ? created.plusMinutes(30) : null,
                                rnd.nextInt(1000) == 0 ? 1 : 0, 0,
                                created, created.plusMinutes(rnd.nextInt(60))
                        };
                    }).toList());
            from = to;
            if (from % 200_000 == 0 || from > ORDERS_ROWS) {
                log.info("[初始化] orders 进度 {}/{} ({} ms)", Math.min(from, ORDERS_ROWS), ORDERS_ROWS,
                        System.currentTimeMillis() - start);
            }
        }
        log.info("[初始化] orders 灌数完成, 耗时 {} ms", System.currentTimeMillis() - start);
    }

    /** 状态分布按真实电商倾斜: 已完成/已支付占大头 */
    private static int weightedStatus(ThreadLocalRandom rnd) {
        int p = rnd.nextInt(100);
        if (p < 45) return 4; // 已完成
        if (p < 70) return 2; // 已支付
        if (p < 82) return 3; // 已发货
        if (p < 92) return 1; // 待支付
        if (p < 98) return 5; // 已取消
        return 6;             // 退款中
    }

    private void seedOrderItem() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM order_item", Long.class);
        if (count != null && count >= ORDER_ITEM_ROWS) {
            log.info("[初始化] order_item 已有 {} 行, 跳过灌数", count);
            return;
        }
        log.info("[初始化] 开始灌入 order_item {} 行（order_id 升序写入避免随机页分裂, 预计 1~3min）...", ORDER_ITEM_ROWS);
        long start = System.currentTimeMillis();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        String[] skus = {"iPhone 17 Pro", "MacBook Air M4", "AirPods Pro 3", "小米手环 9", "戴森吸尘器 V15",
                "雀巢金牌咖啡 200g", "三只松鼠坚果礼盒", "全棉时代毛巾 3条装", "乐高积木 911", "飞利浦电动牙刷"};
        // 灌数性能教学点: 随机二级索引 = 每行都可能触发 B+树页分裂(实测 300 万行随机 user_id/order_id 要 15min);
        // order_id 升序写入让 idx_oi_order 顺序追加, user_id 用 LCG 打散保持「均匀随机」查询分布——分布不变, 写入快 10 倍
        long id = 1;
        while (id <= ORDER_ITEM_ROWS) {
            int size = (int) Math.min(BATCH_SIZE, ORDER_ITEM_ROWS - id + 1);
            List<Object[]> batch = new java.util.ArrayList<>(size);
            for (int b = 0; b < size; b++, id++) {
                long orderId = (id - 1) / 3 + 1;                       // 每单固定 3 件, 升序
                long userId = lcgScramble(orderId, 500_000);           // 均匀但乱序, 查询分布保持真实
                batch.add(new Object[]{
                        id,
                        orderId,
                        userId,
                        1000L + rnd.nextInt(90_000),
                        skus[rnd.nextInt(skus.length)],
                        (100 + rnd.nextInt(99_900)) / 100.0,
                        1 + rnd.nextInt(3),
                        LocalDateTime.of(2025, 1, 1, 0, 0).plusSeconds(rnd.nextInt(60_000_000))
                });
            }
            jdbc.batchUpdate("INSERT INTO order_item(id, order_id, user_id, sku_id, sku_name, price, quantity, created_at) VALUES (?,?,?,?,?,?,?,?)", batch);
            if (id % 1_000_000 == 0) {
                log.info("[初始化] order_item 进度 {}/{} ({} ms)", id, ORDER_ITEM_ROWS, System.currentTimeMillis() - start);
            }
        }
        log.info("[初始化] order_item 灌数完成, 耗时 {} ms", System.currentTimeMillis() - start);
    }

    /** 线性同余打乱: 输出在 [1, range] 均匀分布且与输入无单调关系（保持索引键随机分布的查询真实感） */
    private static long lcgScramble(long input, int range) {
        return ((input * 48271L + 11L) % 2147483647L) % range + 1;
    }

    private void seedPaymentAndLog() {
        // 分批灌入（5000/批）：一次性 20 万行 batchUpdate 会被 rewriteBatchedStatements
        // 拼成巨型多值 INSERT（数十 MB SQL 文本）, 256m 教学小堆直接 OOM——分批后毫秒级心跳
        seedChunkedTable("order_payment", PAYMENT_ROWS,
                "INSERT INTO order_payment(id, order_id, pay_type, pay_no, amount, pay_status, paid_at, created_at) VALUES (?,?,?,?,?,?,?,?)");
        seedChunkedTable("order_status_log", STATUS_LOG_ROWS,
                "INSERT INTO order_status_log(id, order_id, from_status, to_status, operator, created_at) VALUES (?,?,?,?,?,?)");
    }

    /** 通用分批灌数：断点续灌（按 MAX(id) 续） + 固定批量, 内存恒定 */
    private void seedChunkedTable(String table, int totalRows, String insertSql) {
        Long existing = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM " + table, Long.class);
        int done = existing == null ? 0 : existing.intValue();
        if (done >= totalRows) {
            log.info("[初始化] {} 已有 {} 行, 跳过灌数", table, done);
            return;
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        long start = System.currentTimeMillis();
        for (int from = done + 1; from <= totalRows; ) {
            int to = Math.min(from + BATCH_SIZE, totalRows + 1);
            jdbc.batchUpdate(insertSql,
                    range(from, to).mapToObj(i -> {
                        if (table.equals("order_payment")) {
                            return new Object[]{(long) i, 1L + rnd.nextInt(ORDERS_ROWS), 1 + rnd.nextInt(4),
                                    "WX" + String.format("%016d", i), rnd.nextInt(500_000) / 100.0,
                                    rnd.nextInt(20) == 0 ? 2 : 1,
                                    LocalDateTime.of(2025, 1, 1, 0, 0).plusSeconds(rnd.nextInt(60_000_000)),
                                    LocalDateTime.of(2025, 1, 1, 0, 0).plusSeconds(rnd.nextInt(60_000_000))};
                        }
                        return new Object[]{(long) i, 1L + rnd.nextInt(ORDERS_ROWS),
                                rnd.nextInt(6), 1 + rnd.nextInt(6),
                                rnd.nextInt(10) == 0 ? "CS" + rnd.nextInt(500) : "system",
                                LocalDateTime.of(2025, 1, 1, 0, 0).plusSeconds(rnd.nextInt(60_000_000))};
                    }).toList());
            from = to;
        }
        log.info("[初始化] {} 灌入 {} 行, 耗时 {} ms", table, totalRows - done, System.currentTimeMillis() - start);
    }

    private static java.util.stream.IntStream range(int fromInclusive, int toExclusive) {
        return java.util.stream.IntStream.range(fromInclusive, toExclusive);
    }
}
