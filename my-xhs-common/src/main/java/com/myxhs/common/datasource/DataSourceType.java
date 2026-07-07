package com.myxhs.common.datasource;

/**
 * 数据源类型枚举
 * MASTER - 写库（主库）
 * SLAVE  - 读库（从库）
 */
public enum DataSourceType {
    MASTER,
    SLAVE
}
