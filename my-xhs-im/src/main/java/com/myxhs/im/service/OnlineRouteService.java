package com.myxhs.im.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 在线路由服务
 * <p>
 * 管理用户在线状态和路由信息（用户在哪个 IM 实例上）。
 * 多实例部署时，通过 Redis 路由表实现跨实例消息投递。
 * </p>
 * <p>
 * Key 设计：
 * - im:route:{userId} → serverId（90s TTL，心跳续期）
 * - im:online:{userId} → "1"（90s TTL，心跳续期）
 * </p>
 * <p>
 * 分布式安全：
 * - 注销路由使用 Lua 脚本保证"检查 serverId + 删除"的原子性，
 *   防止新实例的路由被旧实例误删。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnlineRouteService {

    private final StringRedisTemplate stringRedisTemplate;

    /** 当前实例唯一标识（启动时生成，用于区分多实例） */
    private final String serverId = UUID.randomUUID().toString().substring(0, 8)
            + "-" + ProcessHandle.current().pid();

    @Value("${im.websocket.heartbeat-interval:30000}")
    private long heartbeatInterval;

    private static final String ROUTE_KEY_PREFIX = "myxhs:im:route:";
    private static final String ONLINE_KEY_PREFIX = "myxhs:im:online:";

    /**
     * Lua 脚本：原子性地"检查 serverId 匹配后再删除"
     * <p>
     * 解决竞态条件：GET 和 DELETE 之间如果用户在另一个实例重新上线，
     * 新实例写入了新路由，旧实例的 DELETE 会误删新路由。
     * Lua 脚本在 Redis 单线程中原子执行，不存在这个问题。
     * </p>
     */
    private static final String UNREGISTER_LUA =
            "local routeKey = KEYS[1] " +
            "local onlineKey = KEYS[2] " +
            "local expectedServerId = ARGV[1] " +
            "local currentServerId = redis.call('GET', routeKey) " +
            "if currentServerId == expectedServerId then " +
            "  redis.call('DEL', routeKey) " +
            "  redis.call('DEL', onlineKey) " +
            "  return 1 " +
            "end " +
            "return 0";

    private static final DefaultRedisScript<Long> UNREGISTER_SCRIPT =
            new DefaultRedisScript<>(UNREGISTER_LUA, Long.class);

    /**
     * 注册用户在线路由
     */
    public void registerRoute(Long userId) {
        Duration ttl = Duration.ofMillis(heartbeatInterval * 3); // 3 倍心跳间隔作为 TTL
        stringRedisTemplate.opsForValue().set(ROUTE_KEY_PREFIX + userId, serverId, ttl);
        stringRedisTemplate.opsForValue().set(ONLINE_KEY_PREFIX + userId, "1", ttl);
    }

    /**
     * 注销用户在线路由（Lua 脚本原子操作）
     * <p>
     * 只删除属于本实例的路由，防止新实例的路由被旧实例误删。
     * </p>
     */
    public void unregisterRoute(Long userId) {
        List<String> keys = List.of(ROUTE_KEY_PREFIX + userId, ONLINE_KEY_PREFIX + userId);
        stringRedisTemplate.execute(UNREGISTER_SCRIPT, keys, serverId);
    }

    /**
     * 续期路由（心跳时调用）
     */
    public void renewRoute(Long userId) {
        Duration ttl = Duration.ofMillis(heartbeatInterval * 3);
        stringRedisTemplate.expire(ROUTE_KEY_PREFIX + userId, ttl);
        stringRedisTemplate.expire(ONLINE_KEY_PREFIX + userId, ttl);
    }

    /**
     * 查询用户路由（在哪个实例上）
     *
     * @return serverId，null 表示用户不在线
     */
    public String getRoute(Long userId) {
        return stringRedisTemplate.opsForValue().get(ROUTE_KEY_PREFIX + userId);
    }

    /**
     * 判断用户是否在线
     */
    public boolean isOnline(Long userId) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(ONLINE_KEY_PREFIX + userId));
    }

    /**
     * 获取当前实例 serverId
     */
    public String getServerId() {
        return serverId;
    }
}
