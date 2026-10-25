package com.lab.troubleshoot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * 线上故障排查演练场启动入口。
 *
 * <p>教学要点：Spring 的数据源在首次获取连接时才会真正连库，而连接 URL 指向的
 * {@code troubleboot_lab} 库可能尚不存在（首次演练）。因此这里在 Spring 上下文启动
 * <b>之前</b>，先用 JDBC 直连 MySQL 完成建库——模拟线上「初始化环境」与「应用启动」
 * 两个阶段的分离。建库失败（MySQL 未启动/密码错误）时给出中文排障提示，而不是
 * 抛一串 Spring 嵌套异常，这也是线上应友好的实践。</p>
 */
@Slf4j
@SpringBootApplication
public class TroubleShootApplication {

    public static void main(String[] args) {
        createDatabaseIfAbsent();
        SpringApplication.run(TroubleShootApplication.class, args);
    }

    /**
     * 在 Spring 启动前确保 troubleboot_lab 库存在。
     * 凭据与环境变量覆盖规则与 application.yml 保持一致。
     */
    private static void createDatabaseIfAbsent() {
        String host = env("MYSQL_HOST", "localhost");
        String port = env("MYSQL_PORT", "3306");
        String user = env("MYSQL_USER", "root");
        String password = env("MYSQL_PASSWORD", "rootroot");
        String url = "jdbc:mysql://%s:%s/?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000"
                .formatted(host, port);
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE DATABASE IF NOT EXISTS troubleboot_lab "
                    + "DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            log.info("[初始化] 数据库 troubleboot_lab 已就绪 ({}:{})", host, port);
        } catch (Exception e) {
            log.error("[初始化失败] 无法连接 MySQL {}:{} —— 请确认: 1) MySQL 已启动 2) 凭据正确(默认 root/rootroot, "
                    + "可用环境变量 MYSQL_HOST/MYSQL_PORT/MYSQL_USER/MYSQL_PASSWORD 覆盖) 3) 或使用 docker/docker-compose.yml 起一个备用库", host, port, e);
            throw new IllegalStateException("MySQL 不可用，演练场依赖本地 MySQL", e);
        }
    }

    private static String env(String key, String defaultValue) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? defaultValue : v;
    }
}
