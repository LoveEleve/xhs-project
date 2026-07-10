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
 *   <li><b>单节点模式</b>（开发/演示）：spring.data.redis.sentinel.enabled=false
 *       <br>适用于开发/演示环境，直连 Redis 单节点</li>
 *   <li><b>Sentinel 模式</b>（生产推荐）：spring.data.redis.sentinel.enabled=true
 *       <br>适用于生产环境，通过 Sentinel 自动故障转移</li>
 * </ul>
 *
 * <h3>Sentinel 主从切换锁安全策略（P1-6 修复）</h3>
 * <p>
 * Redis Sentinel 主从切换期间（down-after-milliseconds=5000ms + failover-timeout=30000ms），
 * 旧 Master 上的锁数据可能尚未同步到新 Master，导致锁信息丢失。
 * </p>
 * <p>
 * 本配置采用<b>双层防御</b>策略：
 * </p>
 * <ul>
 *   <li><b>Redisson 层</b>：
 *     <ul>
 *       <li>{@code retryAttempts=5, retryInterval=1000ms}：主从切换期间自动重试获取 Sentinel 新 Master 地址</li>
 *       <li>{@code lockWatchdogTimeout=15000ms}：看门狗续期间隔缩短为 15 秒（默认 30 秒），
 *           减少切换窗口内的锁过期风险</li>
 *     </ul>
 *   </li>
 *   <li><b>应用层</b>（业务代码双重保障）：
 *     <ul>
 *       <li>所有关键操作使用 DB 乐观锁（WHERE status/version）作为最终防线</li>
 *       <li>Redisson 锁仅作为"快速失败"的第一道防线，锁失效时 DB 乐观锁保证数据正确性</li>
 *       <li>PaymentService 已同时使用 Redis 防重锁 + DB 乐观锁（WHERE status = expectStatus）</li>
 *     </ul>
 *   </li>
 * </ul>
 * <p>
 * 为什么不使用 RedLock（多节点锁）？
 * RedLock 需要多个独立 Redis 实例，运维复杂度高且 Martin Kleppmann 已论证其安全性局限。
 * 本项目选择"Redisson Sentinel + DB 乐观锁"的务实方案：运维简单 + 数据安全性由 DB 保证。
 * </p>
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

    /**
     * 看门狗续期间隔（毫秒），默认 15000ms。
     * <p>
     * Sentinel 主从切换窗口通常为 5-30 秒。将续期间隔缩短为 15 秒（默认 30 秒），
     * 减少切换期间锁因未续期而过期的风险。
     * </p>
     */
    @Value("${redisson.lock-watchdog-timeout:15000}")
    private long lockWatchdogTimeout;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();

        if (sentinelEnabled && sentinelNodes != null && !sentinelNodes.isEmpty()) {
            // ===== Sentinel 哨兵模式 =====
            // Redisson Sentinel 要求地址带 redis:// 前缀
            String[] nodes = sentinelNodes.split(",");
            String[] prefixedNodes = new String[nodes.length];
            for (int i = 0; i < nodes.length; i++) {
                String node = nodes[i].trim();
                prefixedNodes[i] = node.startsWith("redis://") ? node : "redis://" + node;
            }
            var serverConfig = config.useSentinelServers()
                    .setMasterName(sentinelMaster)
                    .addSentinelAddress(prefixedNodes)
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
                    .setPingConnectionInterval(0)
                    .setCheckSentinelsList(false);

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

        config.setLockWatchdogTimeout(lockWatchdogTimeout);
        return Redisson.create(config);
    }
}
