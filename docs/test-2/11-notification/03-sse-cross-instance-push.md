# SSE 跨实例推送原理

> 模块：notification | 核心类：SseEmitterManager、SseCrossInstanceSubscriber、SseTicketService

---

## 1. 业务背景

通知服务的核心价值是"用户在线时实时推送"。单实例部署没有问题——SSE 连接都在一个 JVM 里，`ConcurrentHashMap` O(1) 查找直接推送。

多实例部署后出现根本问题：

```
用户 A 的 SSE 连接在实例 1（登录时 Nginx 分配到实例 1）
用户 B 点赞用户 A 的笔记 → MQ 事件在实例 2 上被消费
问题：实例 2 如何把通知推送到实例 1 上的用户 A？
```

不解决这个问题，用户在线也收不到实时通知——只能等主动刷新列表。

**答案**：Redis Pub/Sub 做消息总线。每个实例启动时订阅一个公共 Channel，推送时先查 Redis 路由表确定目标用户在哪台机器，不在本地则发布到 Channel，目标机器的订阅者收到后执行本地推送。

---

## 2. 架构决策

### 2.1 SSE vs WebSocket

| 维度 | SSE | WebSocket |
|---|---|---|
| 协议 | HTTP/1.1 长连接，单方向 | 协议升级 101，全双工 |
| 浏览器 API | `EventSource`（原生，自动重连） | `WebSocket`（需手动重连） |
| 代理/防火墙穿透 | 100% 兼容 HTTP | 部分代理需要配置 |
| 二进制数据 | 不支持（仅 text） | 支持 Blob/ArrayBuffer |
| 服务端实现 | `SseEmitter`（Spring 原生） | 需 WebSocket Handler |

选择 SSE 的理由：通知场景是**单向推送**（服务端→客户端），不需要客户端发送消息。`EventSource` 自动重连和 HTTP 代理穿透是免费的，不需要额外代码。

### 2.2 跨实例推送：Redis Pub/Sub vs 其他

| 方案 | 延迟 | 持久化 | 运维负担 | 适用场景 |
|---|---|---|---|---|
| **Redis Pub/Sub** | 毫秒级 | ❌ 不持久化 | 低 | 即发即忘 |
| RocketMQ | 数十毫秒 | ✅ 持久化+重试 | 中 | 需要持久化的业务消息 |
| HTTP 直连 | 几毫秒 | ❌ | 高（需拓扑感知） | 已知目标的点对点 |

选择 Redis Pub/Sub 的理由：SSE 推送丢失一次不影响——用户下次打开列表就能看到。不需要持久化和重试，Pub/Sub 延迟最低。

### 2.3 Ticket 两步认证

浏览器 `EventSource` API 不支持自定义 HTTP Header。如果把 JWT Token 放在 URL 参数中 `?token=xxx`，会被记录在：
- 浏览器历史记录
- Nginx/Gateway 访问日志
- CDN/代理日志

两步法解决：
1. 用 HTTP POST（Header 携带 Token）获取 30 秒一次性 Ticket
2. 用 Ticket（通过 URL 参数 `?ticket=xxx`）建立 SSE 连接
3. `getAndDelete` 保证 Ticket 只被使用一次

---

## 3. 源码追踪

### 3.1 连接建立（SseEmitterManager.java:71）

```java
public SseEmitter createConnection(Long userId) {
    SseEmitter emitter = new SseEmitter(0L);  // ① 永不超时

    SseEmitter oldEmitter = emitters.put(userId, emitter);
    if (oldEmitter != null) oldEmitter.complete();  // ② 关闭旧连接

    // ③ 清理回调：remove(key, value) 双参数版本
    emitter.onCompletion(() -> {
        if (emitters.remove(userId, emitter)) {
            stringRedisTemplate.delete("notify:sse:" + userId);
        }
    });

    // ④ 注册 Redis 路由，30s TTL
    stringRedisTemplate.opsForValue().set(
        "notify:sse:" + userId, getServerId(), Duration.ofSeconds(30));

    // ⑤ 发送连接确认事件
    emitter.send(SseEmitter.event().name("connected")
        .data("{\"msg\":\"SSE连接建立成功\"}"));
    return emitter;
}
```

**关键设计点**（代码为简化版，完整源码含 3 个清理回调：onCompletion 86-91 / onTimeout 93-98 / onError 100-105）：

① `new SseEmitter(0L)`：参数 0 表示永不超时。连接存活由心跳保证——如果 10 秒内没收到心跳，客户端 EventSource 会断线重连。

② `emitters.put(userId, emitter)`：`ConcurrentHashMap.put` 是原子的。返回旧值后调用 `complete()` 关闭，保证同一 userId 只有一个活跃连接。不需要额外加锁。

③ `emitters.remove(userId, emitter)` 双参数版本：如果只用单参数 `remove(userId)`，存在以下竞态：
```
T1: 旧连接 em1 的 onTimeout 回调被触发
T2: 用户重连，emitters.put(userId, em2)  ← 新连接
T3: onTimeout 回调执行 emitters.remove(userId)  ← 删除了新连接！
```
双参数版本只在 `map[userId] == emitter` 时才删除——T3 时 map[userId] 已经是 em2，不等于 em1，不会误删。

④ serverId 格式：`InetAddress.getLocalHost().getHostAddress() + ":" + System.getProperty("server.port", "19013")`（源码 294-296 行）。

### 3.2 三步推送策略（SseEmitterManager.java:135）

```java
public boolean pushNotification(Long userId, Object data) {
    // Step 1: 本实例直推（O(1) ConcurrentHashMap 查找）
    SseEmitter emitter = emitters.get(userId);
    if (emitter != null) return pushToLocalUser(userId, emitter, "notification", data);

    // Step 2: 查 Redis 路由，跨实例推送
    String targetServerId = stringRedisTemplate.opsForValue()
        .get("notify:sse:" + userId);  // 30s TTL，心跳续期
    if (targetServerId != null) return publishCrossInstance(userId, "notification", data);

    // Step 3: 离线
    return false;
}
```

三步走：本地内存（最快）→ Redis 路由（毫秒级）→ 确认离线。每一步都让下一步不需要执行。

### 3.3 Redis Pub/Sub 跨实例通道（SseEmitterManager.java:213）

```java
private boolean publishCrossInstance(Long userId, String eventName, Object data) {
    Map<String, Object> message = Map.of("userId", userId, "event", eventName, "data", data);
    String json = objectMapper.writeValueAsString(message);
    stringRedisTemplate.convertAndSend("notify:sse:channel", json);
    return true;
}
```

消息格式：`{"userId":456,"event":"notification","data":{...}}`。`convertAndSend` 是即发即忘——不关心有几个订阅者、谁收到了。

### 3.4 跨实例消息订阅（SseCrossInstanceSubscriber.java:52）

```java
@PostConstruct
public void init() {
    container = new RedisMessageListenerContainer();
    container.setConnectionFactory(stringRedisTemplate.getConnectionFactory());
    container.addMessageListener(this, new ChannelTopic("notify:sse:channel"));
    container.afterPropertiesSet();  // 仅验证配置，不启动
    container.start();                // 真正启动监听线程
}
```

**关键**：`afterPropertiesSet()` 只验证 `ConnectionFactory` 不为 null，不启动线程。必须手动调用 `start()` 才会创建监听线程并建立 Redis 订阅。

收到消息后（SseCrossInstanceSubscriber.java:71）：
```java
public void onMessage(Message message, byte[] pattern) {
    Map<String, Object> msgMap = objectMapper.readValue(body, Map.class);
    Long userId = toLong(msgMap.get("userId"));
    // 委托给 SseEmitterManager——只有本实例在线才推送
    sseEmitterManager.handleCrossInstanceMessage(userId, eventName, data);
}
```

`handleCrossInstanceMessage` 会再次检查 `emitters.get(userId)`——如果用户在本实例不在线直接忽略。收到消息的所有实例都会执行这个检查，只有用户在线的那个实例才实际推送。

### 3.5 心跳保活（SseEmitterManager.java:236）

```java
@Scheduled(fixedRate = 10000)
public void heartbeat() {
    if (emitters.isEmpty()) return;

    // Part 1: Pipeline 批量续期 Redis（N 次 SET → 1 次网络往返）
    stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
        StringRedisConnection stringConn = (StringRedisConnection) connection;
        for (Long userId : emitters.keySet()) {
            stringConn.set("notify:sse:" + userId, getServerId(),
                Expiration.seconds(30), SetOption.UPSERT);
        }
        return null;
    });

    // Part 2: 发送心跳事件给客户端
    for (Map.Entry<Long, SseEmitter> entry : emitters.entrySet()) {
        try {
            entry.getValue().send(SseEmitter.event().name("heartbeat")
                .data("{\"ts\":" + System.currentTimeMillis() + "}"));
        } catch (Exception e) {
            // 发送失败 → 连接已断开，清理
            emitters.remove(entry.getKey(), entry.getValue());
            stringRedisTemplate.delete("notify:sse:" + entry.getKey());
        }
    }
}
```

**Pipeline 批量 SET 的收益**：如果 1000 个在线用户，不用 Pipeline 需要 1000 次 Redis 往返（RTT×1000），用 Pipeline 只需要 1 次往返。减少 ~1000 倍的网络开销。

### 3.6 Ticket 一次性消费（SseTicketService.java:56）

```java
public Long validateAndConsume(String ticket) {
    String key = "notify:sse:ticket:" + ticket;
    String userIdStr = stringRedisTemplate.opsForValue().getAndDelete(key);
    if (userIdStr == null) return null;  // 不存在或已被消费
    return Long.parseLong(userIdStr);
}
```

`getAndDelete` 是 Spring Data Redis 的原子操作——内部执行 `GET` + `DEL` 两个命令，但 Redis 单线程保证中间不会被其他操作打断。Ticket 即使被截获也无法重用：第一次使用后 key 已被删除，第二次 `getAndDelete` 返回 null。

---

## 4. 面试 Q&A

### Q1：SSE 连接断开后，服务端多久感知到？

**答**：最坏情况 10 秒。

1. 正常断开（客户端调用 `EventSource.close()`）：TCP FIN → Servlet 容器通知 → `onCompletion` 回调 → 立即清理
2. 异常断开（网络中断、浏览器崩溃）：服务端不感知。下一个心跳周期（10 秒），`emitter.send()` 抛出 `IOException` → catch 块清理连接
3. Redis 路由过期：心跳停止 30 秒后 Redis key 过期 → 其他实例查路由时发现用户不在线 → `pushNotification` 返回 false

**追问**：为什么 Redis TTL 是 30 秒而不是 10 秒？

**答**：给心跳留容错窗口。心跳每 10 秒一次，如果某次心跳因为网络抖动失败，下次在 20 秒时续期仍然在 30 秒 TTL 内。2 次连续失败（20 秒）才会触发 Redis 过期，防止单次网络波动导致误判离线。

### Q2：为什么 `ConcurrentHashMap.remove(key, value)` 双参数版本是必需的？

**答**：防止"回调误删新连接"竞态。

场景：
```
1. 用户 A 断开 SSE 连接，浏览器自动重连
2. 事件序列：
   T0: 旧连接 em1 的 TCP 连接断开
   T1: 重连请求到达，createConnection 创建新连接 em2
   T2: emitters.put(userId, em2)，旧连接 em1 被 complete()
   T3: em1 的 onCompletion 回调执行 ← 注意：回调是异步的
```

如果 T3 使用单参数 `emitters.remove(userId)`：会删掉 T2 刚创建的 em2。
使用双参数 `emitters.remove(userId, em1)`：T3 时 map[userId]=em2≠em1 → 不删除。

**追问**：`ConcurrentHashMap.compute` 能解决这个问题吗？

**答**：能，但过度了。`compute` 的 lambda 在锁内执行，阻塞其他线程。`remove(key, value)` 在 `ConcurrentHashMap` 内使用 bin 级别的同步检查——只在值匹配时才删除，性能更高。这里只需要简单的"删除时检查值"语义，不需要原子计算。

### Q3：为什么不直接用 RocketMQ 的广播消费模式做跨实例推送？

**答**：四个理由：

1. **延迟**：RocketMQ 消费链路（Broker 接收 → 存储 → 消费者拉取 → 反序列化）比 Redis Pub/Sub（push 模型，直接内存转发）多一个数量级的延迟。
2. **持久化开销**：RocketMQ 的 CommitLog 写入和刷盘是 SSE 推送不需要的——推送消息丢失没有业务影响，用户下次打开列表就能看到。
3. **消费堆积**：如果某个实例 CPU 繁忙导致消费者滞后，RocketMQ 会产生消息堆积告警。Pub/Sub 没有堆积概念——未消费的消息直接丢弃。
4. **资源隔离**：RocketMQ 的 Consumer 线程池与业务线程共享资源，Pub/Sub 的 `RedisMessageListenerContainer` 有独立的监听线程池。

**追问**：什么场景下应该用 MQ 而不是 Pub/Sub？

**答**：需要保证消息至少被消费一次的场景——订单状态变更、库存扣减、支付通知。这些场景的消息丢失会导致数据不一致，必须用持久化消息队列。

---

## 5. 生产实验

### 实验：验证心跳断开检测

```bash
# 步骤 1：获取 Ticket 并建立 SSE 连接
TICKET=$(curl -s -X POST http://localhost:19013/api/notification/sse/ticket \
  -H "X-User-Id: 10001" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['ticket'])")

# 步骤 2：后台运行 SSE 连接
timeout 30 curl -s -N "http://localhost:19013/api/notification/sse?ticket=$TICKET" > /tmp/sse.log &
SSE_PID=$!
sleep 3

# 步骤 3：验证连接在线
curl -s http://localhost:19013/api/notification/sse/online-count
# 预期：{"data":{"onlineCount":1}}

# 步骤 4：强制杀掉连接
kill -9 $SSE_PID

# 步骤 5：等待最多 15 秒
sleep 15

# 步骤 6：验证自动清理
curl -s http://localhost:19013/api/notification/sse/online-count
# 预期：{"data":{"onlineCount":0}}

# 步骤 7：检查应用日志
grep "心跳失败(清理)" logs/my-xhs-notification/info.log | tail -1
# 预期：[SSE] 心跳失败(清理): userId=10001
```

---

## 6. 发散思考

### 6.1 如果不要 HTTP，直接写 TCP Socket 长连接会怎样？

Google FCM（Firebase Cloud Messaging）和 Apple APNs 都是以长连接为基础的推送系统，但它们用的是自定义 TCP 协议（XMPP/HTTP2 Stream），而不是标准 HTTP SSE。

**对比**：

| 维度 | SSE（当前） | TCP 长连接 | FCM/APNs |
|---|---|---|---|
| 协议 | HTTP/1.1 | 自定义 | XMPP/HTTP2 |
| 穿透代理 | ✅ 天然 | ❌ 需要独立端口 | 独立通道 |
| 移动端省电 | 一般 | 差（活跃 TCP） | ✅ 极优（共享通道） |
| 开发成本 | 低（EventSource） | 高（Netty 自定义） | 中（SDK 集成） |
| 多端同步 | 需自行设计 | 需自行设计 | 内置 |

SSE 适合 Web 端。移动端应使用厂商推送通道——Android 用 FCM，iOS 用 APNs，后台通过 `firebase-admin` SDK 发送推送。

### 6.2 如果有 10000 个 SSE 连接，心跳 Pipeline 有什么限制？

当前实现遍历 `emitters.keySet()` 全部放入一个 Pipeline。Redis Pipeline 本质上是在一个 TCP 连接上连续发送多个命令然后批量读取结果，**不保证原子性**——Pipeline 中的某个命令失败不影响其他命令。

**限制**：
1. Jetty/Tomcat 默认最大连接数（`max-connections=10000`），10000 个 SSE 连接就是 10000 个 HTTP 连接
2. Pipeline 单次命令数建议 < 1000（Lettuce 客户端默认 flush 大小）
3. 10000 次的 `emitter.send()` 在单线程 `for` 循环中执行——如果每个 send 耗时 1ms，总耗时 10 秒，会超过 `fixedRate=10000ms`

**优化方向**：分片 Pipeline + 线程池并行 send

---

## 7. 跨文档引用

- 架构文档：`01-notification-module.md` §2 架构图、§6.3 SSE 连接流程
- 测试记录：`02-notification-test-record.md` 测试 1-3（online-count/ticket/SSE connect）
