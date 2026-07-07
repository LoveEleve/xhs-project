package com.myxhs.common.datasource;

/**
 * 数据源上下文持有者，基于 ThreadLocal 存储当前线程的数据源类型。
 * 支持手动指定（优先级高于 @Transactional(readOnly) 自动路由）。
 */
public class DataSourceContextHolder {

    private static final ThreadLocal<DataSourceType> CONTEXT = new ThreadLocal<>();

    public static void set(DataSourceType type) {
        CONTEXT.set(type);
    }

    public static DataSourceType get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
