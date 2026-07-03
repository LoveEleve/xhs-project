package com.myxhs.notification.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 连接管理器
 * <p>
 * 核心职责：
 * 1. 管理 userId → SseEmitter 的映射（本实例在线用户）
 * 2. 心跳保活（每 10 秒发送心跳 + Redis 续期）
 * 3. 推送通知/未读计数给在线用户
 * 4. 多实例部署时通过 Redis Pub/Sub 实现跨实例推送
 * </p>
 * <p>
 * 跨实例推送架构：
 * - 每个 SSE 实例订阅 Redis Channel（NOTIFY_SSE_CHANNEL）
 * - 推送时先查 Redis 路由（notify:sse:{userId} → serverId）
 * - 如果目标用户在本实例 → 直接推送
 * - 如果目标用户在其他实例 → 发布 Redis 消息 → 目标实例消费后推送
 * - 如果用户不在线 → 不推送（通知列表 API 可以查到）
 * </p>
 * <p>
 * 线程安全：ConcurrentHashMap 保证并发安全。
 * 资源清理：onCompletion/onTimeout/onError 回调中清理映射。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseEmitterManager {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /** userId → SseEmitter（本机管理的连接） */
    private final ConcurrentHashMap<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    private static final String SSE_KEY_PREFIX = "notify:sse:";
    private static final Duration SSE_TTL = Duration.ofSeconds(30);

    /** Redis Pub/Sub Channel：跨实例 SSE 推送 */
    private static final String NOTIFY_SSE_CHANNEL = "notify:sse:channel";

    /** 服务实例标识（IP:Port） */
    private volatile String serverId;

    /**
     * 建立 SSE 连接
     * <p>
     * 超时设为 0（永不超时），由心跳保活。
     * 注册 onCompletion/onTimeout/onError 回调清理资源。
     * </p>
     */
    public SseEmitter createConnection(Long userId) {
        SseEmitter emitter = new SseEmitter(0L); // 永不超时

        // 原子替换：put 返回旧值，保证同一 userId 不会并发创建两个有效连接
        // ConcurrentHashMap.put 是线程安全的，不需要额外加锁
        SseEmitter oldEmitter = emitters.put(userId, emitter);
        if (oldEmitter != null) {
            try {
                oldEmitter.complete();
            } catch (Exception ignored) {
            }
        }

        // 【修复C7】使用 remove(key, value) 双参数版本，仅当 map 中存的仍是当前 emitter 时才删除
        // 避免旧连接 complete 触发回调时误删已替换的新连接
        emitter.onCompletion(() -> {
            if (emitters.remove(userId, emitter)) {
                stringRedisTemplate.delete(SSE_KEY_PREFIX + userId);
            }
            log.debug("[SSE] 连接完成: userId={}", userId);
        });

        emitter.onTimeout(() -> {
            if (emitters.remove(userId, emitter)) {
                stringRedisTemplate.delete(SSE_KEY_PREFIX + userId);
            }
            log.info("[SSE] 连接超时: userId={}", userId);
        });

        emitter.onError(e -> {
            if (emitters.remove(userId, emitter)) {
                stringRedisTemplate.delete(SSE_KEY_PREFIX + userId);
            }
            log.warn("[SSE] 连接异常: userId={}", userId);
        });

        // 注册到 Redis（心跳续期，30 秒过期）
        stringRedisTemplate.opsForValue().set(
                SSE_KEY_PREFIX + userId, getServerId(), SSE_TTL);

        // 发送连接成功事件
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data("{\"msg\":\"SSE连接建立成功\"}", MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            log.warn("[SSE] 发送连接确认失败: userId={}", userId);
        }

        log.info("[SSE] 连接建立: userId={}, 当前在线={}", userId, emitters.size());
        return emitter;
    }

    /**
     * 推送通知给在线用户（支持跨实例）
     * <p>
     * 推送策略：
     * 1. 检查用户是否在本实例在线 → 直接推送
     * 2. 检查用户是否在其他实例在线 → Redis Pub/Sub 跨实例推送
     * 3. 用户不在线 → 返回 false（通知列表 API 可查到）
     * </p>
     *
     * @return true=推送成功, false=用户不在线或推送失败
     */
    public boolean pushNotification(Long userId, Object data) {
        // 1. 先尝试本实例直推
        SseEmitter emitter = emitters.get(userId);
        if (emitter != null) {
            return pushToLocalUser(userId, emitter, "notification", data);
        }

        // 2. 查询 Redis 路由，判断是否在其他实例在线
        String targetServerId = stringRedisTemplate.opsForValue().get(SSE_KEY_PREFIX + userId);
        if (targetServerId != null) {
            // 用户在其他实例在线 → 通过 Redis Pub/Sub 跨实例推送
            return publishCrossInstance(userId, "notification", data);
        }

        // 3. 用户不在线
        return false;
    }

    /**
     * 推送未读计数变更（支持跨实例）
     */
    public void pushUnreadCount(Long userId, Object countData) {
        // 1. 先尝试本实例直推
        SseEmitter emitter = emitters.get(userId);
        if (emitter != null) {
            pushToLocalUser(userId, emitter, "unread-count", countData);
            return;
        }

        // 2. 查询 Redis 路由，判断是否在其他实例在线
        String targetServerId = stringRedisTemplate.opsForValue().get(SSE_KEY_PREFIX + userId);
        if (targetServerId != null) {
            publishCrossInstance(userId, "unread-count", countData);
        }
    }

    /**
     * 处理跨实例推送消息（由 Redis Pub/Sub 消费者调用）
     * <p>
     * 只有目标用户在本实例在线时才推送。
     * 非目标实例收到消息后直接忽略。
     * </p>
     */
    public void handleCrossInstanceMessage(Long userId, String eventName, Object data) {
        SseEmitter emitter = emitters.get(userId);
        if (emitter == null) {
            return; // 用户不在本实例，忽略
        }
        pushToLocalUser(userId, emitter, eventName, data);
    }

    /**
     * 推送到本实例在线用户
     */
    private boolean pushToLocalUser(Long userId, SseEmitter emitter, String eventName, Object data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(json, MediaType.APPLICATION_JSON));
            return true;
        } catch (Exception e) {
            // 推送失败，清理连接（双参数 remove 防止误删新连接）
            if (emitters.remove(userId, emitter)) {
                stringRedisTemplate.delete(SSE_KEY_PREFIX + userId);
            }
            log.warn("[SSE] 推送失败(清理连接): userId={}, event={}", userId, eventName);
            return false;
        }
    }

    /**
     * 通过 Redis Pub/Sub 发布跨实例推送消息
     * <p>
     * 消息格式：JSON {"userId":123,"event":"notification","data":{...}}
     * 所有 SSE 实例都会收到，只有目标用户在线的实例才会处理。
     * </p>
     */
    private boolean publishCrossInstance(Long userId, String eventName, Object data) {
        try {
            Map<String, Object> message = Map.of(
                    "userId", userId,
                    "event", eventName,
                    "data", data);
            String json = objectMapper.writeValueAsString(message);
            stringRedisTemplate.convertAndSend(NOTIFY_SSE_CHANNEL, json);
            log.debug("[SSE] 跨实例推送发布: userId={}, event={}", userId, eventName);
            return true;
        } catch (Exception e) {
            log.warn("[SSE] 跨实例推送发布失败: userId={}, event={}", userId, eventName, e);
            return false;
        }
    }

    /**
     * 心跳续期：每 10 秒批量刷新 Redis 中的 SSE 映射
     * <p>
     * 使用 Pipeline 批量 SET，避免 N 次网络往返。
     * 同时发送心跳事件给客户端，检测连接是否存活。
     * </p>
     */
    @Scheduled(fixedRate = 10000)
    public void heartbeat() {
        if (emitters.isEmpty()) {
            return;
        }

        String sid = getServerId();
        long ts = System.currentTimeMillis();

        // 1. Pipeline 批量续期 Redis
        stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisConnection stringConn = (StringRedisConnection) connection;
            for (Long userId : emitters.keySet()) {
                stringConn.set(SSE_KEY_PREFIX + userId, sid,
                        Expiration.seconds(30),
                        org.springframework.data.redis.connection.RedisStringCommands.SetOption.UPSERT);
            }
            return null;
        });

        // 2. 发送心跳事件给客户端（检测连接存活）
        for (Map.Entry<Long, SseEmitter> entry : emitters.entrySet()) {
            try {
                entry.getValue().send(SseEmitter.event()
                        .name("heartbeat")
                        .data("{\"ts\":" + ts + "}", MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                // 心跳发送失败 → 连接已断开，清理（双参数 remove 防误删）
                if (emitters.remove(entry.getKey(), entry.getValue())) {
                    stringRedisTemplate.delete(SSE_KEY_PREFIX + entry.getKey());
                }
                log.info("[SSE] 心跳失败(清理): userId={}", entry.getKey());
            }
        }

        log.debug("[SSE] 心跳完成: 在线连接数={}", emitters.size());
    }

    /**
     * 判断用户是否在本机在线
     */
    public boolean isOnline(Long userId) {
        return emitters.containsKey(userId);
    }

    /**
     * 获取当前在线连接数
     */
    public int getOnlineCount() {
        return emitters.size();
    }

    /**
     * 获取服务实例标识
     */
    public String getServerId() {
        if (serverId == null) {
            try {
                String host = InetAddress.getLocalHost().getHostAddress();
                String port = System.getProperty("server.port", "19013");
                serverId = host + ":" + port;
            } catch (Exception e) {
                serverId = "unknown:" + System.currentTimeMillis();
            }
        }
        return serverId;
    }
}