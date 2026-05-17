package com.myxhs.common.datagen;

import javax.sql.DataSource;

/**
 * 数据生成器接口
 * <p>
 * 所有表的数据生成器都实现此接口。
 * 通过 Spring 自动注入所有实现类，按 {@link #order()} 排序后依次执行。
 * </p>
 */
public interface DataGenerator {

    /**
     * 生成器名称（用于日志和命令行参数匹配）
     * <p>
     * 例如："user"、"product"、"note"
     * 命令行通过 --generate=user,product 指定要执行的生成器
     * </p>
     */
    String name();

    /**
     * 执行顺序（数字越小越先执行）
     * <p>
     * 有依赖关系的生成器必须按顺序执行：
     * - Step 1 (order=10): user, product, coupon_template（无依赖）
     * - Step 2 (order=20): note, inventory, user_coupon（依赖 Step 1）
     * - Step 3 (order=30): comment, like, order（依赖 Step 2）
     * </p>
     */
    int order();

    /**
     * 执行数据生成
     *
     * @param dataSource 数据源（JDBC 连接池）
     * @param scale      数据规模倍数（1.0 = 标准量，0.01 = 1% 用于测试）
     */
    void generate(DataSource dataSource, double scale);
}
