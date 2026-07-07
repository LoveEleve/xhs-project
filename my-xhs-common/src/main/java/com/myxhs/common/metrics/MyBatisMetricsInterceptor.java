package com.myxhs.common.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.ResultHandler;

import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * MyBatis SQL 耗时指标拦截器
 * 
 * 基于 MyBatis Interceptor 机制，在 SQL 执行前后记录耗时。
 * 
 * MyBatis 拦截器调用链：
 * Executor.update/query()
 *   → StatementHandler.prepare() → parameterize() → query/update()
 *     → ResultSetHandler.handleResultSets()
 * 
 * 指标示例：
 * - mybatis.sql.latency{command=SELECT, mapper=com.myxhs.order.mapper.OrderMapper} → Timer
 * - mybatis.sql.total{command=INSERT} → Counter
 * 
 * 注意：本拦截器与 SqlGuardInterceptor 配合使用。
 * SqlGuardInterceptor 负责慢 SQL 熔断，本拦截器负责指标收集。
 * 两个拦截器可以通过 @Intercepts 的签名区分不同阶段。
 */
@Slf4j
@Intercepts({
        @Signature(
                type = StatementHandler.class,
                method = "update",
                args = {Statement.class}
        ),
        @Signature(
                type = StatementHandler.class,
                method = "query",
                args = {Statement.class, ResultHandler.class}
        )
})
public class MyBatisMetricsInterceptor implements Interceptor {

    private final MeterRegistry meterRegistry;

    public MyBatisMetricsInterceptor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        StatementHandler handler = (StatementHandler) invocation.getTarget();
        String mapperId = getMapperId(handler);
        String sqlType = getSqlType(handler);
        long start = System.currentTimeMillis();

        Object result;
        try {
            result = invocation.proceed();
            return result;
        } finally {
            long duration = System.currentTimeMillis() - start;
            recordMetrics(mapperId, sqlType, duration);
        }
    }

    /**
     * 从 StatementHandler 中获取 MappedStatement
     * 
     * MyBatis 实际运行的是 RoutingStatementHandler，其 delegate 字段指向具体的 StatementHandler
     * （如 PreparedStatementHandler），mappedStatement 属性在 delegate 上。
     */
    private String getMapperId(StatementHandler handler) {
        try {
            MetaObject metaObject = SystemMetaObject.forObject(handler);
            MappedStatement ms = (MappedStatement) metaObject.getValue("delegate.mappedStatement");
            if (ms != null) {
                return ms.getId();
            }
        } catch (Exception e) {
            log.debug("[MyBatisMetrics] 获取 MapperId 失败: {}", e.getMessage());
        }
        return "unknown";
    }

    /**
     * 从 StatementHandler 的 BoundSql 中提取 SQL 类型
     */
    private String getSqlType(StatementHandler handler) {
        try {
            String sql = handler.getBoundSql().getSql().trim().toUpperCase();
            if (sql.startsWith("SELECT")) return "SELECT";
            if (sql.startsWith("INSERT")) return "INSERT";
            if (sql.startsWith("UPDATE")) return "UPDATE";
            if (sql.startsWith("DELETE")) return "DELETE";
        } catch (Exception e) {
            log.debug("[MyBatisMetrics] 获取 SQL 类型失败: {}", e.getMessage());
        }
        return "UNKNOWN";
    }

    /**
     * 记录 SQL 耗时指标
     */
    private void recordMetrics(String mapperId, String sqlType, long duration) {
        Timer.builder("mybatis.sql.latency")
                .description("MyBatis SQL 执行耗时")
                .tag("command", sqlType)
                .tag("mapper", mapperId)
                .publishPercentiles(0.5, 0.9, 0.99)
                .register(meterRegistry)
                .record(duration, TimeUnit.MILLISECONDS);

        if (duration > 200) {
            log.warn("[MyBatis 慢SQL] type={}, mapper={}, duration={}ms",
                    sqlType, mapperId, duration);
        }
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
    }
}
