# SSE 跨实例推送原理

> 模块：notification | 端口：19013 | 日期：2026-07-29

---

## 1. 业务背景

通知服务需要在用户在线时**实时推送**通知（点赞、评论、关注等）。在单实例部署时，SSE 连接由单个 JVM 管理，推送直接通过内存中的 `ConcurrentHashMap` 完成。但生产环境是多实例部署，需要解决：

- **用户 A 的 SSE 连接在实例 1**，但通知事件可能在**实例 2** 上生成（MQ 消费者是竞争消费）
- 如何让实例 2 的推送到达实例 1 上的用户 A？

---

## 2. 架构决策

### 决策 1：SSE vs WebSocket

| 维度 | SSE | WebSocket |
|---|---|---|
| 协议 | HTTP/1.1 长连接 | WebSocket 协议升级 |
| 方向 | 单向（服务端→客户端） | 双向 |
| 浏览器支持 | EventSource API（原生） | WebSocket API |
| 代理/防火墙 | 100% 兼容（HTTP） | 部分需要配置 |
| 重连 | 自动（浏览器内置） | 需手动实现 |
| 复杂度 | 低（Spring SseEmitter） | 中 |

**选择 SSE**：通知场景是单向推送（服务端→客户端），不需要双向通信。SSE 天然穿透 HTTP 代理，浏览器 EventSource 自动重连，实现复杂度远低于 WebSocket。

### 决策 2：跨实例推送方案

| 方案 | 延迟 | 可靠性 | 复杂度 |
|---|---|---|---|
| Redis Pub/Sub | 毫秒级 | 不持久化 | 低 |
| RocketMQ | 几十毫秒 | 持久化+重试 | 中 |
| HTTP 直连 | 几毫秒 | 临时网络错误 | 高 |

**选择 Redis Pub/Sub**：SSE 推送是"即发即忘"场景（丢失一次推送影响不大，用户下次打开列表就能看到），不需要持久化和重试。Pub/Sub 延迟最低，实现最简单。

---

## 3. 源码追踪

### 3.1 连接建立（SseEmitterManager.createConnection）

```java
// 文件：sse/SseEmitterManager.java:71
public SseEmitter createConnection(Long userId) {
    SseEmitter emitter = new SseEmitter(0L); // 永不超时

    // 原子替换：ConcurrentHashMap.put 线程安全
    // 如果同一 userId 已有旧连接 → 关闭旧连接（防止重复连接）
    SseEmitter oldEmitter = emitters.put(userId, emitter);
    if (oldEmitter != null) {
        oldEmitter.complete(); // 关闭旧连接
    }

    // 注册回调（使用双参数 remove 防止误删新连接）
    emitter.onCompletion(() -> {
        if (emitters.remove(userId, emitter)) { // 仅匹配当前 emitter
            stringRedisTemplate.delete(SSE_KEY_PREFIX + userId);
        }
    });

    // 注册到 Redis（心跳续期，30 秒过期）
    stringRedisTemplate.opsForValue().set(
            SSE_KEY_PREFIX + userId, getServerId(), Duration.ofSeconds(30));

    // 发送连接成功事件
    emitter.send(SseEmitter.event()
            .name("connected")
            .data("{\"msg\":\"SSE连接建立成功\"}"));

    return emitter;
}
```

**关键设计点**：

1. **永不超时**：`new SseEmitter(0L)`，由心跳保活（每 10 秒一次）
2. **连接唯一性**：`ConcurrentHashMap.put` 天然原子，旧连接被 `complete()` 关闭
3. **回调安全性**：使用 `remove(userId, emitter)` 双参数版本——只有 `map[userId] == emitter` 时才删除，防止旧连接回调误删新连接
4. **Redis 路由**：`notify:sse:{userId}` → serverId，30 秒 TTL，心跳续期

### 3.2 推送逻辑（pushNotification）

```java
// 文件：sse/SseEmitterManager.java:135
public boolean pushNotification(Long userId, Object data) {
    // 1. 先尝试本实例直推
    SseEmitter emitter = emitters.get(userId);
    if (emitter != null) {
        return pushToLocalUser(userId, emitter, "notification", data);
    }

    // 2. 查询 Redis 路由，判断是否在其他实例在线
    String targetServerId = stringRedisTemplate.opsForValue()
            .get(SSE_KEY_PREFIX + userId);
    if (targetServerId != null) {
        // 用户在其他实例在线 → 通过 Redis Pub/Sub 跨实例推送
        return publishCrossInstance(userId, "notification", data);
    }

    // 3. 用户不在线 → 返回 false（通知列表 API 可查到）
    return false;
}
```

**三步推送策略**：
1. 本实例直推（内存速度，最快）
2. Redis Pub/Sub 跨实例（毫秒级延迟）
3. 离线（无操作，依赖数据库持久化）

### 3.3 跨实例消息发布

```java
// 文件：sse/SseEmitterManager.java:213
private boolean publishCrossInstance(Long userId, String eventName, Object data) {
    Map<String, Object> message = Map.of(
            "userId", userId,
            "event", eventName,
            "data", data);
    String json = objectMapper.writeValueAsString(message);
    stringRedisTemplate.convertAndSend(NOTIFY_SSE_CHANNEL, json);
    return true;
}
```

消息格式：`{"userId":123,"event":"notification","data":{...}}`

### 3.4 跨实例消息消费（SseCrossInstanceSubscriber）

```java
// 文件：sse/SseCrossInstanceSubscriber.java:35
@Component
public class SseCrossInstanceSubscriber implements MessageListener {

    @PostConstruct
    public void init() {
        container = new RedisMessageListenerContainer();
        container.setConnectionFactory(stringRedisTemplate.getConnectionFactory());
        container.addMessageListener(this, new ChannelTopic(NOTIFY_SSE_CHANNEL));
        container.afterPropertiesSet();
        container.start(); // 【关键】必须调用 start()，否则不监听
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        Map<String, Object> msgMap = objectMapper.readValue(body, Map.class);

        Long userId = toLong(msgMap.get("userId"));
        String eventName = (String) msgMap.get("event");
        Object data = msgMap.get("data");

        // 只有目标用户在本实例在线时才推送
        sseEmitterManager.handleCrossInstanceMessage(userId, eventName, data);
    }
}
```

**关键点**：
- `RedisMessageListenerContainer.start()` 必须手动调用——`afterPropertiesSet()` 只验证配置不启动监听
- 所有订阅了同一个 Channel 的实例都会收到消息，但只有目标用户在线的实例才会推送（通过 `handleCrossInstanceMessage` 过滤）

### 3.5 心跳保活

```java
// 文件：sse/SseEmitterManager.java:236
@Scheduled(fixedRate = 10000)
public void heartbeat() {
    // 1. Pipeline 批量续期 Redis（避免 N 次网络往返）
    stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
        StringRedisConnection stringConn = (StringRedisConnection) connection;
        for (Long userId : emitters.keySet()) {
            stringConn.set(SSE_KEY_PREFIX + userId, getServerId(),
                    Expiration.seconds(30), SetOption.UPSERT);
        }
        return null;
    });

    // 2. 发送心跳事件给客户端（检测连接存活）
    for (Map.Entry<Long, SseEmitter> entry : emitters.entrySet()) {
        try {
            entry.getValue().send(SseEmitter.event()
                    .name("heartbeat")
                    .data("{\"ts\":" + ts + "}"));
        } catch (Exception e) {
            // 心跳失败 → 连接已断开，清理
            emitters.remove(entry.getKey(), entry.getValue());
            stringRedisTemplate.delete(SSE_KEY_PREFIX + entry.getKey());
        }
    }
}
```

**关键点**：
- Pipeline 批量 SET：N 个用户只需 1 次网络往返（否则 N 次）
- 心跳发送失败时清理连接——利用发送异常的副作用发现已断开的连接

---

## 4. 面试 Q&A

### Q1: 为什么不用 RocketMQ 做跨实例推送？

**A**: RocketMQ 适合需要持久化和重试的场景（如订单消息），但 SSE 推送是"即发即忘"：
- 消息丢失影响小（用户下次打开列表就能看到通知）
- RocketMQ 需要额外的 Consumer Group、Topic 配置，增加运维负担
- Pub/Sub 延迟更低（毫秒级 vs 数十毫秒级）
- 不需要考虑消息堆积（Pub/Sub 不持久化）

### Q2: Redis Pub/Sub 消息丢失怎么办？

**A**: 
1. 丢失概率低：Redis 是本地部署，网络稳定
2. 影响小：通知持久化在 MySQL 中，用户下次打开 App 或刷新列表就能看到
3. 有限补偿：SSE 连接断开后，用户重连时会通过 `/api/notification/list` + `/api/notification/unread-count` 拉取最新状态

### Q3: 如何处理 Redis 主从切换导致的 SSE 路由丢失？

**A**:
1. 心跳机制：每 10 秒自动续期，切换后 10 秒内恢复
2. 客户端重连：EventSource 检测到连接断开后自动重连（浏览器内置机制）
3. 影响可控：30 秒后 Redis key 过期，路由查询返回 null → 离线状态 → 用户下次打开 App 拉取

### Q4: ConcurrentHashMap 的 remove(key, value) 双参数版本为什么重要？

**A**: 单参数 `remove(key)` 只检查 key 是否存在。如果有以下时序：
```
T1: 旧连接 em1 超时，触发 onTimeout 回调
T2: 用户重连，创建新连接 em2，emitters.put(userId, em2)
T3: onTimeout 回调执行 emitters.remove(userId) → 删除了新连接！
```

使用 `remove(userId, em1)` 双参数版本：
```
T3: onTimeout 回调执行 emitters.remove(userId, em1)
    → 此时 map[userId] 是 em2，不等于 em1 → 不删除
```

---

## 5. 生产实验

### 实验：验证心跳断开检测

```bash
# 1. 建立 SSE 连接
curl -N "http://localhost:19013/api/notification/sse?ticket=xxx" > /tmp/sse.log

# 2. 强制断开（kill curl 进程）
kill %1

# 3. 观察日志
tail -f logs/my-xhs-notification/info.log | grep "SSE"
# 预期：下一个心跳周期（≤10s）输出 "心跳失败(清理)"
```

### 实验：验证跨实例推送（需要双实例）

```
# 实例 A 上的用户 10001 连接 SSE
# 实例 B 上消费 MQ 消息 → 推送给用户 10001
# 预期：实例 A 的 SSE 流收到 notification 事件
```

---

## 6. 发散章节

### 6.1 如果有 10 万在线用户，心跳有什么性能瓶颈？

- Pipeline 批量 SET：10 万用户 = 1 次网络往返（Pipeline 最多 65536 条命令/批）
- 超过 65536 条需分批——当前 `emitters.keySet()` 遍历，如果超出建议分片处理
- 心跳事件发送：10 万次 `emitter.send()` 在单线程循环中执行——可改为线程池并行处理

### 6.2 如果想扩展到 100 万在线用户？

1. 心跳分批：按 userId hash 分片，每片独立 Pipeline
2. 连接管理：ConcurrentHashMap → 分段锁 + LRU 淘汰
3. Redis Pub/Sub：单个 Channel 无法承载 100 万实例——改用分片 Channel
