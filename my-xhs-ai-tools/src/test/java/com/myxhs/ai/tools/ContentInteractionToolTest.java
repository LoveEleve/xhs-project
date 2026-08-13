package com.myxhs.ai.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ContentInteractionTool 契约测试（H2 MySQL 模式，无网络）。
 * 口径：互动=code 枚举 3赞/4藏/5评/6分享；曝光=1；窗口半开；deleted=0。
 */
class ContentInteractionToolTest {

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:itest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS my_xhs_content");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS my_xhs_content.t_user_behavior (
                    id BIGINT PRIMARY KEY, user_id BIGINT, note_id BIGINT,
                    behavior_type TINYINT, duration INT, deleted TINYINT DEFAULT 0,
                    created_at DATETIME
                )""");
        jdbc.update("DELETE FROM my_xhs_content.t_user_behavior");
        // 窗口 08-01~08-07
        // 08-01: 赞2 藏1 评1 = 4
        jdbc.update("INSERT INTO my_xhs_content.t_user_behavior VALUES (1,100,10,3,NULL,0,'2026-08-01 09:00:00')");
        jdbc.update("INSERT INTO my_xhs_content.t_user_behavior VALUES (2,101,10,3,NULL,0,'2026-08-01 09:05:00')");
        jdbc.update("INSERT INTO my_xhs_content.t_user_behavior VALUES (3,102,11,4,NULL,0,'2026-08-01 09:10:00')");
        jdbc.update("INSERT INTO my_xhs_content.t_user_behavior VALUES (4,103,11,5,NULL,0,'2026-08-01 09:15:00')");
        // 08-03: 赞1 分享2 = 3
        jdbc.update("INSERT INTO my_xhs_content.t_user_behavior VALUES (5,104,12,3,NULL,0,'2026-08-03 10:00:00')");
        jdbc.update("INSERT INTO my_xhs_content.t_user_behavior VALUES (6,105,12,6,NULL,0,'2026-08-03 10:05:00')");
        jdbc.update("INSERT INTO my_xhs_content.t_user_behavior VALUES (7,106,12,6,NULL,0,'2026-08-03 10:06:00')");
        // 曝光 behavior_type=1（推荐流）
        jdbc.update("INSERT INTO my_xhs_content.t_user_behavior VALUES (8,107,13,1,NULL,0,'2026-08-02 09:00:00')");
        // 已删（排除）
        jdbc.update("INSERT INTO my_xhs_content.t_user_behavior VALUES (9,108,14,3,NULL,1,'2026-08-02 09:00:00')");
        // 窗口外（排除）
        jdbc.update("INSERT INTO my_xhs_content.t_user_behavior VALUES (10,109,15,3,NULL,0,'2026-07-31 23:59:59')");
        // 停留 type=7（非互动，不计）
        jdbc.update("INSERT INTO my_xhs_content.t_user_behavior VALUES (11,110,16,7,12,0,'2026-08-02 09:00:00')");
    }

    @Test
    void 口径_互动按日_曝光单列() {
        ContentInteractionTool tool = new ContentInteractionTool(jdbc);
        String result = tool.contentInteraction("2026-08-01~2026-08-07");
        System.out.println(result);
        // 互动 total=7（4+3），曝光=1，删除/窗外/停留不计
        assertTrue(result.contains("\"total\":7"), "total 应为 7，实际: " + result);
        assertTrue(result.contains("\"exposure\":1"), "exposure 应为 1，实际: " + result);
        assertTrue(result.contains("\"like\":2"), "08-01 赞应 2: " + result);
        assertTrue(result.contains("\"favorite\":1"), "08-01 藏应 1: " + result);
        assertTrue(result.contains("\"comment\":1"), "08-01 评应 1: " + result);
        assertTrue(result.contains("\"share\":2"), "08-03 分享应 2: " + result);
    }

    @Test
    void 空窗口返回0() {
        ContentInteractionTool tool = new ContentInteractionTool(jdbc);
        String result = tool.contentInteraction("2026-09-01~2026-09-07");
        assertTrue(result.contains("\"total\":0"), "空窗口应为 0: " + result);
    }

    @Test
    void 非法窗或超上限抛错() {
        ContentInteractionTool tool = new ContentInteractionTool(jdbc);
        assertThrows(IllegalArgumentException.class, () -> tool.contentInteraction("bad"));
        assertThrows(IllegalArgumentException.class,
                () -> tool.contentInteraction("2026-08-01~2026-09-01"));
    }
}
