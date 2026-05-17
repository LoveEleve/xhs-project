package com.myxhs.home;

import com.myxhs.common.id.IdGeneratorUtil;
import com.myxhs.common.id.SegmentIdGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 首页聚合服务启动类 - BFF 聚合层
 * <p>
 * 无数据库，纯聚合层：通过 Feign 调用下游服务 + Redis 管理 Feed 流收件箱/发件箱。
 * 排除需要 DataSource/JdbcTemplate 的组件（SegmentIdGenerator、IdGeneratorUtil）。
 * </p>
 */
@EnableAsync
@EnableFeignClients(basePackages = "com.myxhs.home.feign")
@SpringBootApplication
@ComponentScan(
        basePackages = {"com.myxhs.home", "com.myxhs.common"},
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {SegmentIdGenerator.class, IdGeneratorUtil.class}
        )
)
public class HomeApplication {
    public static void main(String[] args) {
        SpringApplication.run(HomeApplication.class, args);
    }
}