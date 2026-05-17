package com.myxhs.common.trace;

import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 压测流量影子表路由 MyBatis 拦截器
 * <p>
 * 当 TraceContextHolder.isPressureTest() == true 时，
 * 自动将 SQL 中的表名替换为影子表（加 _shadow 后缀）。
 * </p>
 * <p>
 * 例如：
 * - SELECT * FROM t_order WHERE id = 1
 * → SELECT * FROM t_order_shadow WHERE id = 1
 * </p>
 * <p>
 * 影子表要求：
 * 1. 结构与生产表完全一致（包括索引）
 * 2. 表名 = 生产表名 + _shadow
 * 3. 压测结束后 TRUNCATE 影子表
 * </p>
 * <p>
 * 安全保障：
 * - 默认关闭，需配置 myxhs.shadow.enabled=true 才生效
 * - 只有 X-Pressure-Test=true 的请求才路由到影子表
 * - 正常流量完全不受影响
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "myxhs.shadow.enabled", havingValue = "true", matchIfMissing = false)
@Intercepts({
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})
})
public class ShadowTableInterceptor implements Interceptor {

    /** 影子表后缀 */
    private static final String SHADOW_SUFFIX = "_shadow";

    /**
     * 匹配 SQL 中的表名（FROM/INTO/UPDATE/JOIN 后面的表名）
     * <p>
     * 正则说明：
     * - (?i) 忽略大小写
     * - (FROM|INTO|UPDATE|JOIN)\s+ 匹配关键字 + 至少一个空白
     * - `? 可选的反引号（兼容 MyBatis-Plus 生成的带反引号 SQL）
     * - (t_\w+) 匹配以 t_ 开头的表名（项目约定所有表以 t_ 开头）
     * </p>
     */
    private static final Pattern TABLE_PATTERN =
            Pattern.compile("(?i)(FROM|INTO|UPDATE|JOIN)\\s+`?(t_\\w+)`?");

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        // 非压测流量，直接放行
        if (!TraceContextHolder.isPressureTest()) {
            return invocation.proceed();
        }

        // 压测流量：替换表名为影子表
        MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
        Object parameter = invocation.getArgs()[1];
        BoundSql boundSql = ms.getBoundSql(parameter);
        String originalSql = boundSql.getSql();

        // 替换表名
        String shadowSql = rewriteToShadowTable(originalSql);

        if (!originalSql.equals(shadowSql)) {
            log.debug("[影子表] SQL 重写: {} → {}", originalSql.substring(0, Math.min(100, originalSql.length())),
                    shadowSql.substring(0, Math.min(100, shadowSql.length())));

            // 用反射修改 BoundSql 中的 sql 字段
            Field sqlField = BoundSql.class.getDeclaredField("sql");
            sqlField.setAccessible(true);
            sqlField.set(boundSql, shadowSql);
        }

        return invocation.proceed();
    }

    /**
     * 将 SQL 中的表名替换为影子表名
     * <p>
     * t_order → t_order_shadow
     * t_order_item → t_order_item_shadow
     * </p>
     */
    private String rewriteToShadowTable(String sql) {
        Matcher matcher = TABLE_PATTERN.matcher(sql);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String keyword = matcher.group(1);
            String tableName = matcher.group(2);
            // 避免重复添加 _shadow 后缀
            if (!tableName.endsWith(SHADOW_SUFFIX)) {
                // 保持原始 SQL 中的反引号风格
                String matched = matcher.group(0);
                boolean hasBacktick = matched.contains("`");
                String replacement = hasBacktick
                        ? keyword + " `" + tableName + SHADOW_SUFFIX + "`"
                        : keyword + " " + tableName + SHADOW_SUFFIX;
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
        // 无需额外配置
    }
}
