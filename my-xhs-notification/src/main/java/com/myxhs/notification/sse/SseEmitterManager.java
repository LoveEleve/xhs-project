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
import java.util.concurrent.TimeUnit;

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

    private static final String SSE_KEY_PREFIX = "myxhs:notification:sse:";
    private static final Duration SSE_TTL = Duration.ofSeconds(30);

    /**
     * Redis Pub/Sub Channel 前缀：跨实例 SSE 推送
     * <p>
     * 定向 Channel（每实例一个）：原实现所有实例订阅同一 Channel + 广播，
     * 非目标实例收到后丢弃（N-1 次无效投递）；改为按路由值定向发布，
     * 与 im 模块的路由模式一致。路由值由 {@link #getServerId()} 保证唯一。
     * </p>
     */
    private static final String NOTIFY_SSE_CHANNEL_PREFIX = "myxhs:notification:sse:channel:";

    /** 服务实例标识（IP:Port#PID，同主机多实例也可区分） */
    private volatile String serverId;

    @org.springframework.beans.factory.annotation.Value("${server.port:19013}")
    private int serverPort;

    /**
     * 建立 SSE 连接
     * <p>
     * 超时设为 0（永不超时），由心跳保活。
     * 注册 onCompletion/onTimeout/onError 回调清理资源。
     * </p>
     */
    /** 单实例 SSE 连接上限（与 IM 的 MAX_CONNECTIONS 同口径；连接 hold 的是 Tomcat async 请求，必须设帽） */
    private static final int MAX_CONNECTIONS = 50_000;

    public SseEmitter createConnection(Long userId) {
        // 容量保护：原实现无上限（已在线用户重复连接会 put 覆盖，但新用户可无限建连 → 打爆实例）
        if (emitters.size() >= MAX_CONNECTIONS && !emitters.containsKey(userId)) {
            log.warn("[SSE] 连接数超限: current={}, max={}, userId={}", emitters.size(), MAX_CONNECTIONS, userId);
            throw new com.myxhs.common.exception.BizException(
                    com.myxhs.common.response.ResultCode.SERVICE_UNAVAILABLE, "实时连接数已达上限，请稍后重试");
        }
        SseEmitter emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(30)); // 30分钟超时兜底

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
            // 用户在其他实例在线 → 通过 Redis Pub/Sub 定向推送到该实例
            return publishCrossInstance(targetServerId, userId, "notification", data);
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
            publishCrossInstance(targetServerId, userId, "unread-count", countData);
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
     * 处理跨实例推送（接收原始 JSON 字符串，避免 Map 反序列化导致 Long→Integer 类型丢失）
     */
    public void handleCrossInstanceMessageJson(Long userId, String eventName, String dataJson) {
        SseEmitter emitter = emitters.get(userId);
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(dataJson, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            if (emitters.remove(userId, emitter)) {
                stringRedisTemplate.delete(SSE_KEY_PREFIX + userId);
            }
            log.warn("[SSE] 跨实例推送失败: userId={}, event={}", userId, eventName);
        }
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
     * 通过 Redis Pub/Sub 定向发布跨实例推送消息
     * <p>
     * 消息格式：JSON {"userId":123,"event":"notification","data":{...}}
     * 只投递到路由指向的实例；返回值语义是"已发布"（Pub/Sub 即发即忘，
     * 不保证目标实例消费成功——通知的权威来源是列表 API，SSE 只是实时加速）。
     * </p>
     */
    private boolean publishCrossInstance(String targetServerId, Long userId, String eventName, Object data) {
        try {
            Map<String, Object> message = Map.of(
                    "userId", userId,
                    "event", eventName,
                    "data", data);
            String json = objectMapper.writeValueAsString(message);
            stringRedisTemplate.convertAndSend(NOTIFY_SSE_CHANNEL_PREFIX + targetServerId, json);
            log.debug("[SSE] 跨实例推送发布: userId={}, event={}, target={}", userId, eventName, targetServerId);
            return true;
        } catch (Exception e) {
            log.warn("[SSE] 跨实例推送发布失败: userId={}, event={}, target={}", userId, eventName, targetServerId, e);
            return false;
        }
    }

    /**
     * 本实例的订阅 Channel（订阅方与发布方共用同一命名规则）
     */
    public String getChannelName() {
        return NOTIFY_SSE_CHANNEL_PREFIX + getServerId();
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
        //    异常隔离：Redis 抖动时本轮续期失败不影响下面的"本地死连接清理"（原实现直接抛出，
        //    清理循环被跳过 → 死连接滞留到 emitter 30 分钟超时）
        try {
            stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                StringRedisConnection stringConn = (StringRedisConnection) connection;
                for (Long userId : emitters.keySet()) {
                    stringConn.set(SSE_KEY_PREFIX + userId, sid,
                            Expiration.seconds(30),
                            org.springframework.data.redis.connection.RedisStringCommands.SetOption.UPSERT);
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("[SSE] 心跳续期失败(本轮跳过, 下轮重试): 在线连接数={}", emitters.size(), e);
        }

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
     * 判断用户是否在**任一实例**在线（本地直连 或 Redis 路由键存在）
     * <p>
     * 2026-09-20 review：processEvent 原用 isOnline（仅本地）做前置判断，
     * 当通知事件被"非用户 SSE 连接所在实例"处理时，跨实例推送被短路
     * （多实例实测：实例B处理事件，连在实例A的 SSE 收不到推送）。
     * 推送给客户端的动作仍由 pushNotification/pushUnreadCount 内部按"本地→路由"处理。
     * </p>
     */
    public boolean isOnlineAnywhere(Long userId) {
        if (emitters.containsKey(userId)) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(SSE_KEY_PREFIX + userId));
        } catch (Exception e) {
            log.warn("[SSE] 在线检查(Redis)失败，按离线处理: userId={}", userId, e);
            return false;
        }
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
            synchronized (this) {
                if (serverId == null) {
                    try {
                        String host = InetAddress.getLocalHost().getHostAddress();
                        // 原实现 System.getProperty("server.port") 读不到 Spring 配置（恒取默认 19013），
                        // 同主机多实例 serverId 完全相同 → 跨实例路由值无法区分实例；追加 PID 保证唯一
                        serverId = host + ":" + serverPort + "#" + ProcessHandle.current().pid();
                    } catch (Exception e) {
                        serverId = "unknown:" + System.currentTimeMillis();
                    }
                }
            }
        }
        return serverId;
    }
}