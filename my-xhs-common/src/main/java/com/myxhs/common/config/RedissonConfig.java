package com.myxhs.common.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 配置
 * <p>
 * 提供分布式锁（RLock）能力，支持 Watchdog 自动续期。
 * 支持单节点模式和 Sentinel 哨兵模式，通过配置开关切换。
 * </p>
 *
 * <h3>模式选择</h3>
 * <ul>
 *   <li><b>单节点模式</b>（默认）：spring.data.redis.sentinel.enabled=false
 *       <br>适用于开发/演示环境，直连 Redis 单节点</li>
 *   <li><b>Sentinel 模式</b>（生产推荐）：spring.data.redis.sentinel.enabled=true
 *       <br>适用于生产环境，通过 Sentinel 自动故障转移</li>
 * </ul>
 *
 * <h3>Sentinel 配置示例</h3>
 * <pre>{@code
 * spring:
 *   data:
 *     redis:
 *       host: 21.91.124.110
 *       port: 16379
 *       password: Xhs@2026#Redis
 *       sentinel:
 *         enabled: true
 *         master: mymaster
 *         nodes: 21.91.124.110:26379,21.91.124.110:26380,21.91.124.110:26381
 * }</pre>
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Value("${spring.data.redis.database:0}")
    private int redisDatabase;

    /**
     * Sentinel 模式开关
     * 生产环境建议设为 true，默认启用
     */
    @Value("${spring.data.redis.sentinel.enabled:true}")
    private boolean sentinelEnabled;

    @Value("${spring.data.redis.sentinel.master:mymaster}")
    private String sentinelMaster;

    @Value("${spring.data.redis.sentinel.nodes:}")
    private String sentinelNodes;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();

        if (sentinelEnabled && sentinelNodes != null && !sentinelNodes.isEmpty()) {
            // ===== Sentinel 哨兵模式 =====
            var serverConfig = config.useSentinelServers()
                    .setMasterName(sentinelMaster)
                    .addSentinelAddress(sentinelNodes.split(","))
                    .setDatabase(redisDatabase)
                    .setMasterConnectionMinimumIdleSize(2)
                    .setMasterConnectionPoolSize(16)
                    .setSlaveConnectionMinimumIdleSize(2)
                    .setSlaveConnectionPoolSize(16)
                    .setIdleConnectionTimeout(10000)
                    .setConnectTimeout(10000)
                    .setTimeout(10000)
                    .setRetryAttempts(5)
                    .setRetryInterval(1000)
                    .setPingConnectionInterval(0);

            if (redisPassword != null && !redisPassword.isEmpty()) {
                serverConfig.setPassword(redisPassword);
            }
        } else {
            // ===== 单节点模式（开发/演示） =====
            String address = "redis://" + redisHost + ":" + redisPort;
            var serverConfig = config.useSingleServer()
                    .setAddress(address)
                    .setDatabase(redisDatabase)
                    .setConnectionMinimumIdleSize(1)
                    .setConnectionPoolSize(8)
                    .setIdleConnectionTimeout(10000)
                    .setConnectTimeout(10000)
                    .setTimeout(10000)
                    .setRetryAttempts(5)
                    .setRetryInterval(1000)
                    .setPingConnectionInterval(0);

            if (redisPassword != null && !redisPassword.isEmpty()) {
                serverConfig.setPassword(redisPassword);
            }
        }

        return Redisson.create(config);
    }
}
