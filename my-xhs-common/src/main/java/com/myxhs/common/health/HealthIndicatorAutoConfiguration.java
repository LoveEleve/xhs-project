package com.myxhs.common.health;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * 健康检查指示器装配
 * <p>
 * 修复点：三个 HealthIndicator 此前是"纯类"——无 @Component、无 @Bean、不在 AutoConfiguration.imports，
 * <b>永远不会被实例化</b>，导致 Cache Redis 宕机 / RocketMQ 不可达 / 堆将满时健康检查仍为 UP（注释宣称已覆盖）。
 * 这里按"依赖 Bean 存在"条件注册，避免在缺少对应依赖的服务里导致启动失败。
 * </p>
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
public class HealthIndicatorAutoConfiguration {

    /*
     * 注意：ApplicationReadinessIndicator 有意不在此注册——
     * 它在"堆使用率 > 90%"时返回 DOWN，而 docker-compose 的健康检查打的就是聚合 /actuator/health，
     * 注册后会在大堆服务 GC 前抖动成 503 → 容器被判不健康（重启循环/被摘流量）。
     * 需要时由运维按 health group 单独纳入（management.endpoint.health.group.readiness.include=applicationReadiness）。
     */

    /** Cache Redis（16380）健康检查：仅在存在该连接工厂时注册 */
    @Bean
    @ConditionalOnBean(name = "cacheRedisConnectionFactory")
    public CacheRedisHealthIndicator cacheRedisHealthIndicator(
            @Qualifier("cacheRedisConnectionFactory") RedisConnectionFactory cacheRedisConnectionFactory) {
        return new CacheRedisHealthIndicator(cacheRedisConnectionFactory);
    }

    /** RocketMQ 健康检查：仅在存在 RocketMQTemplate 时注册 */
    @Bean
    @ConditionalOnBean(RocketMQTemplate.class)
    public RocketMQHealthIndicator rocketMQHealthIndicator(RocketMQTemplate rocketMQTemplate) {
        return new RocketMQHealthIndicator(rocketMQTemplate);
    }
}
