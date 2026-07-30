# IM 跨实例消息路由 — 深度技术分析

> 关联源码：`ImRouteSubscriber.java` / `OnlineRouteService.java` / `ChatService.java`

---

## 业务背景

IM 服务多实例部署时，WebSocket 连接分散在不同的实例上。用户 A（实例1）发消息给用户 B（实例2），消息必须跨实例投递。需要一套低延迟、高可靠的跨实例消息路由方案。

候选方案对比：

| 方案 | 延迟 | 资源消耗 | 可靠性 | 复杂度 |
|---|---|---|---|---|
| Redis Pub/Sub 广播 | 毫秒级 | 高（全部实例唤醒） | 低（即发即忘） | 低 |
| Redis Pub/Sub 专属 Channel | 毫秒级 | 低（仅目标实例） | 低（即发即忘） | 中 |
| RocketMQ BROADCASTING | 毫秒级 | 高（全部实例消费） | 高（持久化） | 高 |
| RocketMQ CLUSTERING | 毫秒级 | 低（单实例消费） | 高（持久化） | 高 |

---

## 架构决策：Redis Pub/Sub 专属 Channel

最终选择**每实例一个专属 Channel**（`im:route:{serverId}`），而非全局广播 Channel。

### 为什么不是 RocketMQ？

1. **即时通讯场景**：消息生命周期短，不需要 MQ 的持久化保证
2. **延迟敏感**：Redis Pub/Sub 延迟 0.1-1ms，RocketMQ 端到端 5-20ms
3. **复杂度**：引入 MQ 需要管理 Topic/ConsumerGroup/重试/死信

### 为什么不是广播 Channel？

```java
// ❌ 方案 A：全局广播 Channel — 浪费
stringRedisTemplate.convertAndSend("im:route:global", routeMessage);
// 所有 IM 实例都收到消息，但只有目标实例需要处理

// ✅ 方案 B：专属 Channel — 精准投递
stringRedisTemplate.convertAndSend("im:route:" + targetServerId, routeMessage);
// 只有目标实例收到消息
```

---

## 实现详解

### 订阅机制

```java
@PostConstruct
public void start() {
    String localServerId = onlineRouteService.getServerId();
    String channel = "im:route:" + localServerId;
    // 动态创建 Channel，实例上线时自动开始订阅
    container.addMessageListener(this, new ChannelTopic(channel));
}
```

**关键点**：Channel 名称包含 `serverId`，每个实例唯一。实例启动时自动创建订阅，无需预配置。

### serverId 生成

```java
private final String serverId = UUID.randomUUID().toString().substring(0, 8)
        + "-" + ProcessHandle.current().pid();
```

格式：`60d744fd-3399467`（UUID 前 8 位 + "-" + PID）。保证多实例不冲突。

### 消息投递决策树

```java
// ChatService.handleChat() 中的路由逻辑（简化）
String targetServerId = onlineRouteService.getRoute(receiverId);
if (targetServerId == null) {
    storeOfflineMessage(receiverId, msgId);         // 离线
} else if (targetServerId.equals(onlineRouteService.getServerId())) {
    webSocketHandler.pushToUser(receiverId, json);  // 同实例
} else {
    // 跨实例：构建 RouteMessage 后序列化发送
    RouteMessage routeMsg = RouteMessage.builder()
            .receiverId(receiverId)
            .targetServerId(targetServerId)
            .msgId(msgId).senderId(senderId)
            .content(content).msgType(msgType)
            .timestamp(timestamp)
            .build();
    stringRedisTemplate.convertAndSend(
            "im:route:" + targetServerId,
            JSON.toJSONString(routeMsg));
}
```

---

## 时序图

```
IM 实例 A (serverId=A_12345)          Redis              IM 实例 B (serverId=B_67890)
         │                               │                         │
         │ ① A 上线                      │                         │
         │ SET im:route:10001 = A_12345  │                         │
         │──────────────────────────────>│                         │
         │                               │                         │
         │                               │ ② B 上线                │
         │                               │ SET im:route:10002      │
         │                               │ = B_67890              │
         │                               │<────────────────────────│
         │                               │                         │
         │ ③ A 发消息给 B                │                         │
         │ 查 im:route:10002 → B_67890    │                         │
         │──────────────────────────────>│                         │
         │                               │                         │
         │    ④ PUBLISH im:route:B_67890 │                         │
         │    {receiverId:10002, ...}    │                         │
         │──────────────────────────────>│                         │
         │                               │  ⑤ onMessage 回调      │
         │                               │────────────────────────>│
         │                               │                         │ ⑥ pushToUser(10002)
         │                               │                         │<── B 在线 → 投递成功
         │                               │                         │
         │                               │    ⑦ 投递失败           │
         │                               │    → storeOfflineMessage│
```

---

## 竞态条件审计

### 场景：用户刚断开连接，路由尚未过期

```
时间线：
  T0: B 在线 → im:route:10002 = B_67890 (TTL=90s)
  T1: B 断开 → afterConnectionClosed() → Lua 原子注销
  T2: A 发消息 → 查 im:route:10002 → null → 存离线 ✅
```

### 场景：用户刚连接，路由尚未写入

```
  T0: B 连接 WebSocket（握手完成）
  T1: afterConnectionEstablished → SET im:route:10002
  T2: A 发消息 → 查 im:route → B_67890 → 推送 ✅
```

### 场景：跨实例消息投递时目标实例崩溃

```
  T0: B 实例崩溃
  T1: OS 关闭 TCP → Redis 检测到连接断开
  T2: Redis 移除 im:route:B_67890 的订阅者
  T3: A 发消息 → 查 im:route:10002 → 仍指向 B_67890（TTL 未过）
  T4: A PUBLISH im:route:B_67890 → 0 subscribers → 消息丢弃
  T5: onMessage() 不触发 → 离线兜底不执行 → 消息丢失 ❌
  T6: 90s 后 im:route:10002 TTL 过期 → 后续消息走离线 ✅
```

**窗口期**：最长 90 秒（路由 TTL），期间发给崩溃实例的消息全部丢失。

**原因**：`onMessage()` 中的离线兜底是"投递后检查"，依赖目标实例的处理线程活着。如果目标实例已崩溃，订阅不存在，`onMessage()` 根本不会被调用。

**修复建议**：

方案 A（发送方兜底）：A 发送 PUBLISH 后，异步延迟检查目标用户是否在线。如果超时未 ACK，降级存离线。

方案 B（心跳探测）：实例间通过 Redis 定期心跳，探测到实例下线后主动清理其路由标记（当前 `unregisterRoute` 的 Lua 脚本只在进程正常关闭时执行）。

方案 C（接受丢失）：IM 场景可以接受少量丢消息（用户界面显示"网络不稳定"），当前实现可接受不修复。

---

## 生产实验

### 同实例直接投递验证

```
测试步骤：
1. A(10001) 连接 WebSocket
2. B(10002) 连接 WebSocket
3. A 发送 CHAT → B 收到 CHAT
4. A 收到 ACK
5. A 收到 READ_NOTIFY（B 发送 READ 后）
```

验证结果：同实例投递成功，`pushToUser()` 直接命中本地 `ConcurrentHashMap`。CHAT→ACK→PING→PONG 全部正常。

### 跨实例投递降级验证（B 不在线→离线）

```
测试步骤：
1. A(10001) 连接 WebSocket
2. B 未连接（不发 ticket，不连 WS）
3. A 发送 CHAT 给 B
4. onlineRouteService.getRoute(10002) → null
5. 进入 storeOfflineMessage() → Lua ZADD im:offline:10002
```

Redis 验证：
```bash
ZCARD im:offline:10002 → 1 ✅
ZRANGE im:offline:10002 0 -1 → msgId=2082716606909186049 ✅
```

确认离线消息已写入 Lua ZSet。

### 同实例 vs 离线路径对比

```
场景 A（B 在线）：A 发送 → B 收到 CHAT → 不入 ZSet
场景 B（B 离线）：A 发送 → 入 ZSet → B 上线收 OFFLINE
```

实际测试验证：
- B 在线时发送：`im:offline:10002 ZCARD` 不变 ✅
- B 离线时发送：`im:offline:10002 ZCARD += 1` ✅

两条路径代码覆盖确认。

### 崩溃场景模拟

无法实际 kill 进程，通过代码审计确认：
- `unregisterRoute()` 的 Lua 脚本只在 `afterConnectionClosed()` 执行
- 进程崩溃时 TCP 连接断开 → Redis 移除订阅 → 消息丢失窗口 90s
- 当前无实例心跳检测机制

### 崩溃场景模拟

无法实际 kill 进程，但可以通过 Redis CLI 验证"路由存在但订阅消失"的场景：

```
1. A 连接 → im:route:10001 写入 Redis
2. A 发送消息给 B → B 在线 → 直接投递
3. 模拟：手动删除 im:route:B_67890 的订阅（Redis 不可模拟）
4. 结论：缺乏多实例环境，崩溃场景只能代码审计
```

测试环境限制：当前仅部署单实例，跨实例崩溃场景无法实测。代码审计见竞态分析章节。

Redis Pub/Sub 跨实例路由增加约 1ms 延迟（同机 Redis）或 0.5-2ms（跨机 Redis），对比直接推送：

| 场景 | 估计延迟 |
|---|---|
| 同实例直接推送 | < 0.1ms（内存操作） |
| 跨实例（同机 Redis） | ~ 1ms（本地网络） |
| 跨实例（跨机 Redis） | ~ 1-2ms（内网） |

---

## 面试 Q&A

**Q: 为什么不用 RocketMQ BROADCASTING？**
A: Redis Pub/Sub 专属 Channel 延迟更低（<1ms vs 5-20ms），且 RocketMQ 广播模式会让所有实例都收到消息，每个实例收到后还要判断是否自己的用户（if 判断），浪费 CPU。专属 Channel 只有目标实例收到。

**Q: 消息丢失怎么办？**
A: 两层兜底 + 一个未覆盖场景。第一层：pushToUser 失败自动存离线（`ImRouteSubscriber.onMessage()` 中兜底）。第二层：上线时批量推送离线消息。未覆盖场景：目标实例崩溃时，Pub/Sub 订阅已消失，`onMessage()` 不被调用，离线兜底不触发，最长 90 秒消息丢失窗口。当前设计可接受（IM 允许少量丢消息），可修复方向见竞态审计章节。

**Q: 场景：目标实例刚好在 PUBLISH 到达前崩溃？**
A: 见竞态场景分析。`ImRouteSubscriber.onMessage()` 中 pushToUser 返回 false 时调用 `chatService.storeOfflineMessage()` 兜底。

---

## 发散

### 备选方案：Redis Stream（替代 Pub/Sub）

Redis 5.0+ 的 Stream 支持 Consumer Group 和消息持久化。如果未来需要消息可靠投递（不允许丢失），可以迁移：

```java
// Pub/Sub → Stream 迁移路径（概念示例，非实际 API）
// 生产者：Stream.add(StreamRecords.objectBacked(routeMessage).withStreamKey(key));
// 消费者：streamOperations.read(Consumer.from("im-route", "consumer-01"), 
//           StreamReadOptions.empty().count(1), StreamOffset.create(key, ReadOffset.lastConsumed()));
```

优点：消息持久化、消费者组、ACK 机制。缺点：增加 Redis 内存开销（持久化消息）。

### 备选方案：一致性 Hash + 固定路由

如果完全避免跨实例路由，可以用一致性 Hash 确保聊天双方在同一实例。但 IM 场景下 A 和 B 可能在不同实例登录，哈希无法保证双方在同一节点。

### 不推荐：WebSocket 直接互连

实例间建立 WS 连接池互推。优点：不依赖 Redis。缺点：需要维护实例拓扑（注册中心 + 心跳），实例扩容/缩容需要重建连接池，复杂度远高于 Redis Pub/Sub。
