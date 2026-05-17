package com.myxhs.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.Collections;

/**
 * 支付模块 Bean 配置
 */
@Configuration
public class PaymentConfig {

    /**
     * 支付超时 Lua 脚本
     * <p>
     * 原子操作：检查支付单状态是否为"待支付(0)"且超时，
     * 如果是则标记为"支付失败(2)"。
     * </p>
     * KEYS[1] = 支付单 Redis Key（如 payment:status:{orderId}）
     * ARGV[1] = 超时时间戳（毫秒）
     * ARGV[2] = 当前时间戳（毫秒）
     * <p>
     * 返回：
     * - 0: 未超时或不满足条件，未更新
     * - 1: 成功标记超时
     */
    public static final String PAYMENT_TIMEOUT_SCRIPT =
            "local status = redis.call('GET', KEYS[1]) " +
            "if status == '0' then " +
            "  local timeoutTs = tonumber(ARGV[1]) " +
            "  local now = tonumber(ARGV[2]) " +
            "  if now > timeoutTs then " +
            "    redis.call('SET', KEYS[1], '2') " +
            "    return 1 " +
            "  end " +
            "end " +
            "return 0";

    @Bean
    public DefaultRedisScript<Long> paymentTimeoutScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(PAYMENT_TIMEOUT_SCRIPT);
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 定时任务线程池（用于支付超时检查、退款超时检查等）
     */
    @Bean
    public ThreadPoolTaskScheduler paymentTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("payment-scheduler-");
        scheduler.setAwaitTerminationSeconds(60);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        return scheduler;
    }
}
