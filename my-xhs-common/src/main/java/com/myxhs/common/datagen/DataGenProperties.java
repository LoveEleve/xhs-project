package com.myxhs.common.datagen;

import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 数据生成配置属性
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "myxhs.datagen")
@ConditionalOnProperty(name = "myxhs.datagen.enabled", havingValue = "true", matchIfMissing = false)
public class DataGenProperties {

    /** 是否启用数据生成（默认 false） */
    private boolean enabled = false;

    /** 要执行的生成器（逗号分隔，all=全部） */
    private String generators = "all";

    /**
     * 数据规模倍数
     * <p>
     * 1.0 = 标准量（1000万用户、5000万笔记等）
     * 0.01 = 1%（10万用户、50万笔记，用于快速测试）
     * 0.001 = 0.1%（1万用户、5万笔记，用于开发调试）
     * </p>
     */
    private double scale = 0.001;
}
