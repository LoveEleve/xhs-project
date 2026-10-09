package com.myxhs.im.service;

import com.myxhs.im.dto.RouteMessage;
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

    // 键加同一 hash tag（{u:userId}）：route 与 online 两键落同一 slot，
    // 使"续期/注销"可用单脚本原子完成（Cluster 下不再 CROSSSLOT）；键 90s TTL，格式变更自愈
    private static String routeKey(Long userId) {
        return ROUTE_KEY_PREFIX + "{u:" + userId + "}";
    }

    private static String onlineKey(Long userId) {
        return ONLINE_KEY_PREFIX + "{u:" + userId + "}";
    }

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
     *
     * @return 注册前的旧路由（用户此前所在实例的 serverId）；null=此前不在线
     */
    public String registerRoute(Long userId) {
        Duration ttl = Duration.ofMillis(heartbeatInterval * 3); // 3 倍心跳间隔作为 TTL
        String previous = stringRedisTemplate.opsForValue().get(routeKey(userId));
        stringRedisTemplate.opsForValue().set(routeKey(userId), serverId, ttl);
        stringRedisTemplate.opsForValue().set(onlineKey(userId), "1", ttl);
        return previous;
    }

    /**
     * 跨实例踢线：通知旧实例关闭该用户的本地连接（msgType=97=KICK）
     * <p>
     * 背景：单用户单连接原实现只在本实例内生效——用户在 A、B 两实例各有一条连接时，
     * 旧连接（A）不会收到任何关闭指令，成为"僵尸连接"（收不到消息还占资源）。
     * 路由删除由 {@link #unregisterRoute} 的 Lua 比较删除保护，旧实例关闭连接不会误删新路由。
     * </p>
     */
    public void kickRemote(String targetServerId, Long userId) {
        RouteMessage kick = RouteMessage.builder()
                .receiverId(userId)
                .targetServerId(targetServerId)
                .msgType(97)
                .timestamp(System.currentTimeMillis())
                .build();
        try {
            stringRedisTemplate.convertAndSend(ROUTE_KEY_PREFIX + targetServerId,
                    com.alibaba.fastjson2.JSON.toJSONString(kick));
            log.info("[IM] 已通知旧实例踢线: userId={}, target={}", userId, targetServerId);
        } catch (Exception e) {
            log.warn("[IM] 跨实例踢线通知失败(旧连接将随心跳过期): userId={}, target={}", userId, targetServerId, e);
        }
    }

    /**
     * 注销用户在线路由（Lua 脚本原子操作）
     * <p>
     * 只删除属于本实例的路由，防止新实例的路由被旧实例误删。
     * </p>
     */
    public void unregisterRoute(Long userId) {
        List<String> keys = List.of(routeKey(userId), onlineKey(userId));
        stringRedisTemplate.execute(UNREGISTER_SCRIPT, keys, serverId);
    }

    /**
     * 续期路由（心跳时调用）
     */
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "redis.call('EXPIRE', KEYS[1], ARGV[1]) " +
            "redis.call('EXPIRE', KEYS[2], ARGV[1]) " +
            "return 1", Long.class);

    public void renewRoute(Long userId) {
        Duration ttl = Duration.ofMillis(heartbeatInterval * 3);
        // 原子续期（原实现两次 EXPIRE：中途异常会留下一个键提前过期 → 该用户被判离线转离线消息）
        stringRedisTemplate.execute(RENEW_SCRIPT, List.of(routeKey(userId), onlineKey(userId)),
                String.valueOf(ttl.getSeconds()));
    }

    /**
     * 查询用户路由（在哪个实例上）
     *
     * @return serverId，null 表示用户不在线
     */
    public String getRoute(Long userId) {
        return stringRedisTemplate.opsForValue().get(routeKey(userId));
    }

    /**
     * 判断用户是否在线
     */
    public boolean isOnline(Long userId) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(onlineKey(userId)));
    }

    /**
     * 获取当前实例 serverId
     */
    public String getServerId() {
        return serverId;
    }
}
