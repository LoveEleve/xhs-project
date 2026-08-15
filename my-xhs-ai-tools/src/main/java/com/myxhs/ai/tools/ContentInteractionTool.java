package com.myxhs.ai.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 真实指标工具：content.interaction（内容互动，A3）。
 * 口径（指标字典 v0.2 §3）：每日新增互动 = t_user_behavior 中
 * **code 枚举 3=点赞 4=收藏 5=评论 6=分享**（deleted=0），按 CAST(created_at AS DATE) 分组；
 * 曝光（behavior_type=1，推荐流）单列返回。
 * 表：my_xhs_content.t_user_behavior（P1 修复后落 content 库；当前数据为 0，工具返回 0 属诚实）。
 */
public class ContentInteractionTool {

    private static final Logger log = LoggerFactory.getLogger(ContentInteractionTool.class);

    public static final String METRIC = "content.interaction";
    public static final String DEF_VERSION = "content.interaction/v1";
    public static final String SOURCE = "my_xhs_content.t_user_behavior";
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final int MAX_WINDOW_DAYS = 31;
    private static final String TABLE = "my_xhs_content.t_user_behavior";

    private final JdbcTemplate jdbc;

    public ContentInteractionTool(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Tool("查询指定时间窗内的内容互动量（口径：每日新增互动=点赞/收藏/评论/分享，code枚举3/4/5/6；曝光=1单列；窗口≤31天）")
    public String contentInteraction(
            @P("时间窗，格式 yyyy-MM-dd~yyyy-MM-dd，如 2026-08-01~2026-08-07，跨度不超过31天") String window) {
        LocalDateTime[] bounds = MetricTimeWindow.parse(window, MAX_WINDOW_DAYS);
        try {
            return doQuery(bounds, window);
        } catch (Exception e) {
            log.error("[metric] {} 查询失败: window={}, err={}", METRIC, window, e.getMessage());
            return ToolJson.error(METRIC, e.getMessage());
        }
    }

    private String doQuery(LocalDateTime[] bounds, String window) {
        // 按日互动
        List<DailyInteraction> daily = new java.util.ArrayList<>();
        long total = 0;
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT CAST(created_at AS DATE) AS d, " +
                        "  SUM(CASE WHEN behavior_type = 3 THEN 1 ELSE 0 END) AS likeCount, " +
                        "  SUM(CASE WHEN behavior_type = 4 THEN 1 ELSE 0 END) AS favCount, " +
                        "  SUM(CASE WHEN behavior_type = 5 THEN 1 ELSE 0 END) AS commentCount, " +
                        "  SUM(CASE WHEN behavior_type = 6 THEN 1 ELSE 0 END) AS shareCount " +
                        "FROM " + TABLE +
                        " WHERE behavior_type IN (3,4,5,6) AND deleted = 0 " +
                        "  AND created_at >= ? AND created_at < ? " +
                        "GROUP BY CAST(created_at AS DATE) ORDER BY d",
                bounds[0], bounds[1])) {
            long l = ((Number) r.get("likeCount")).longValue();
            long f = ((Number) r.get("favCount")).longValue();
            long c = ((Number) r.get("commentCount")).longValue();
            long s = ((Number) r.get("shareCount")).longValue();
            total += l + f + c + s;
            daily.add(new DailyInteraction(String.valueOf(r.get("d")), l, f, c, s, l + f + c + s));
        }

        // 曝光（behavior_type=1，推荐流）
        Long exposure = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + TABLE
                        + " WHERE behavior_type = 1 AND deleted = 0 AND created_at >= ? AND created_at < ?",
                Long.class, bounds[0], bounds[1]);

        log.info("[metric] {} window={} total={} dailyDays={} exposure={}",
                METRIC, window, total, daily.size(), exposure == null ? 0 : exposure);

        return ToolJson.write(new InteractionResult(
                "ok", METRIC, DEF_VERSION, window, "Asia/Shanghai", total, daily,
                exposure == null ? 0 : exposure, LocalDateTime.now(ZONE).toString(), SOURCE,
                "互动=code枚举3赞4藏5评6分享；曝光=1(推荐流)，关注流曝光待A6补"));
    }

    /** 内容互动结果（类型化契约） */
    public record InteractionResult(
            String status, String metric, String definitionVersion, String window, String zone,
            long total, List<DailyInteraction> daily, long exposure, String asOf, String source, String note) {
    }

    /** 按日互动 */
    public record DailyInteraction(String day, long like, long favorite, long comment, long share, long total) {
    }
}
