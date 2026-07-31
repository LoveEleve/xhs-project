package com.myxhs.common.aspect;

import com.myxhs.common.datasource.DataSourceContextHolder;
import com.myxhs.common.datasource.DataSourceType;
import com.myxhs.common.datasource.ReadWriteRoutingDataSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Properties;

/**
 * 读写分离 SQL 分析路由拦截器（MyBatis Interceptor）
 * <p>
 * 在 Executor 层拦截（而非 StatementHandler）：
 * MyBatis 执行顺序是 Executor.query/update → getConnection() → StatementHandler.prepare()。
 * 如果在 StatementHandler.prepare 拦截，连接已被获取，路由设置无效。
 * Executor 层拦截可以在 getConnection() 之前设置路由标记。
 * <p>
 * 事务安全：仅在"非事务环境"或"只读事务"中按 SQL 路由。
 * 活跃写事务中不设置 SLAVE——事务内的 SELECT 必须与 INSERT/UPDATE 共用同一连接，
 * 否则会破坏事务隔离性（从库读不到未提交的主库数据）。
 * <p>
 * 修复说明：ReadWriteRoutingDataSource.isReadOperation() 原为孤儿方法，
 * SQL 分析路由未生效。本拦截器补上调用链。
 */
@Slf4j
@Component
@Intercepts({
        @Signature(type = Executor.class, method = "query", args = {
                MappedStatement.class, Object.class, org.apache.ibatis.session.RowBounds.class,
                org.apache.ibatis.session.ResultHandler.class}),
        @Signature(type = Executor.class, method = "query", args = {
                MappedStatement.class, Object.class, org.apache.ibatis.session.RowBounds.class,
                org.apache.ibatis.session.ResultHandler.class, org.apache.ibatis.cache.CacheKey.class,
                org.apache.ibatis.mapping.BoundSql.class}),
        @Signature(type = Executor.class, method = "update", args = {
                MappedStatement.class, Object.class})
})
public class ReadWriteRoutingInterceptor implements Interceptor {

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        // 事务安全：活跃写事务中不做 SQL 路由（保持单一连接）
        boolean inWriteTransaction = TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        if (inWriteTransaction) {
            return invocation.proceed();
        }

        boolean isRead = isReadMethod(invocation);
        if (!isRead) {
            return invocation.proceed();
        }

        DataSourceContextHolder.set(DataSourceType.SLAVE);
        try {
            return invocation.proceed();
        } finally {
            DataSourceContextHolder.clear();
        }
    }

    private boolean isReadMethod(Invocation invocation) {
        String methodName = invocation.getMethod().getName();
        if ("query".equals(methodName)) {
            return true;
        }
        if ("update".equals(methodName)) {
            // update 方法也可能是 SELECT 的变体（如 MyBatis-Plus select 走 Executor.update 的很少）
            // 兜底：解析 BoundSql 前缀
            try {
                Object[] args = invocation.getArgs();
                MappedStatement ms = (MappedStatement) args[0];
                Object parameter = args[1];
                BoundSql boundSql = ms.getBoundSql(parameter);
                return ReadWriteRoutingDataSource.isReadOperation(boundSql.getSql());
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
    }
}
