package com.myxhs.test;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.*;

@SpringBootApplication
public class DbCheckApp {
    public static void main(String[] args) {
        // 不启动 Web，不用 yml，直接配置 DataSource
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:mysql://21.91.124.110:13306?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true");
        cfg.setUsername("root");
        cfg.setPassword("Xhs@2026#MySQL");
        cfg.setMaximumPoolSize(2);
        DataSource ds13306 = new HikariDataSource(cfg);

        HikariConfig cfg2 = new HikariConfig();
        cfg2.setJdbcUrl("jdbc:mysql://21.91.124.110:13307?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true");
        cfg2.setUsername("root");
        cfg2.setPassword("Xhs@2026#MySQL");
        cfg2.setMaximumPoolSize(2);
        DataSource ds13307 = new HikariDataSource(cfg2);

        HikariConfig cfg3 = new HikariConfig();
        cfg3.setJdbcUrl("jdbc:mysql://21.91.124.110:13308?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true");
        cfg3.setUsername("root");
        cfg3.setPassword("Xhs@2026#MySQL");
        cfg3.setMaximumPoolSize(2);
        DataSource ds13308 = new HikariDataSource(cfg3);

        HikariConfig cfg4 = new HikariConfig();
        cfg4.setJdbcUrl("jdbc:mysql://21.91.124.110:13309?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true");
        cfg4.setUsername("root");
        cfg4.setPassword("Xhs@2026#MySQL");
        cfg4.setMaximumPoolSize(2);
        DataSource ds13309 = new HikariDataSource(cfg4);

        JdbcTemplate jdbc13306 = new JdbcTemplate(ds13306);
        JdbcTemplate jdbc13307 = new JdbcTemplate(ds13307);
        JdbcTemplate jdbc13308 = new JdbcTemplate(ds13308);
        JdbcTemplate jdbc13309 = new JdbcTemplate(ds13309);

        // 查所有表
        var checks = new LinkedHashMap<String, JdbcTemplate>();
        checks.put("13306: user", jdbc13306);
        checks.put("13307: content/spu/sku/coupon", jdbc13307);
        checks.put("13308: order(分片)", jdbc13308);
        checks.put("13309: inventory", jdbc13309);

        for (var e : checks.entrySet()) {
            System.out.println("\n=== " + e.getKey() + " ===");
            try {
                JdbcTemplate j = e.getValue();
                for (var row : j.queryForList("SELECT TABLE_NAME, TABLE_ROWS FROM information_schema.TABLES WHERE TABLE_SCHEMA LIKE 'my_xhs_%' AND TABLE_ROWS > 0 ORDER BY TABLE_NAME")) {
                    System.out.printf("  %-30s %5s 行\n", row.get("TABLE_NAME"), row.get("TABLE_ROWS"));
                }
            } catch (Exception ex) {
                System.out.println("  ERR: " + ex.getMessage().split(" ")[0]);
            }
        }

        // 关键表详情
        System.out.println("\n=== 关键表详情 ===");
        for (String sql : new String[]{
            "SELECT * FROM my_xhs_product.t_spu LIMIT 5",
            "SELECT * FROM my_xhs_product.t_sku LIMIT 5",
            "SELECT * FROM my_xhs_coupon.t_coupon_template LIMIT 5",
        }) {
            try {
                System.out.println("\n--- " + sql.substring(0,50) + " ---");
                for (var row : jdbc13307.queryForList(sql)) {
                    System.out.println("  " + row);
                }
            } catch (Exception ex) {
                System.out.println("  ERR: " + ex.getMessage().split("\n")[0]);
            }
        }
        try {
            System.out.println("\n--- my_xhs_inventory.t_inventory ---");
            for (var row : jdbc13309.queryForList("SELECT * FROM my_xhs_inventory.t_inventory LIMIT 5")) {
                System.out.println("  " + row);
            }
        } catch (Exception ex) {
            System.out.println("  ERR: " + ex.getMessage().split("\n")[0]);
        }

        System.out.println("\nDone.");
        
        System.out.println("\n=== 13306: 地址 ===");
        for (var row : jdbc13306.queryForList("SELECT id,user_id,receiver_name,phone FROM t_user_address LIMIT 3")) {
            System.out.println("  "+row);
        }
        System.exit(0);
    }
}
