# my-xhs 架构级改造详细规划

> 基于对全部相关代码的逐行阅读，针对 M2/M4/M8/M9 四个架构级问题给出详细改造方案。  
> 编写时间：2026-06-03  
> **二次 Review 状态**：已验证，方案已修正（见附录）

---

## 一、M2：Feed 推送失败静默 — 补偿机制设计

### 1.1 现状分析

**当前链路**：
```
NoteService.publishNote() → afterCommit → asyncSend("FEED_TOPIC") 
    → FeedPushConsumer.onMessage() → pushToFollowers() 逐粉丝Lua写入收件箱
```

**问题**：
1. **发送端**：`asyncSend` 失败仅打日志，无重试/补偿 → 消息永久丢失
2. **消费端**：`pushToFollowers()` 逐粉丝执行 Lua，中间任一粉丝写入失败被 catch → 该粉丝的收件箱缺失该笔记，但整个消息被 ACK
3. **影响**：笔记已发布，但部分/全部粉丝的 Feed 流永远看不到这条笔记

### 1.2 改造方案

**核心思路**：发送端本地消息表兜底 + 消费端分批进度记录

#### 1.2.1 发送端：本地消息表补偿

在 `NoteService.publishNote()` 的事务内写入本地消息表，事务提交后异步发 MQ，发送成功后标记消息为已发送。定时任务扫描未发送成功的消息重试。

```java
// NoteService.publishNote() 修改后的事务内逻辑
@Transactional(rollbackFor = Exception.class)
public Long publishNote(Long userId, NotePublishRequest request) {
    noteMapper.insert(note);
    
    // 在事务内写入本地消息表（与笔记入库同一事务）
    LocalMessage localMsg = new LocalMessage();
    localMsg.setTopic("FEED_TOPIC");
    localMsg.setBody(JSON.toJSONString(event));
    localMsg.setStatus(0); // 待发送
    localMsg.setRetryCount(0);
    localMessageMapper.insert(localMsg);
    
    // 事务提交后异步发MQ
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            try {
                rocketMQTemplate.asyncSend("FEED_TOPIC", event, new SendCallback() {
                    @Override
                    public void onSuccess(SendResult result) {
                        // 标记本地消息为已发送
                        localMessageMapper.updateStatus(localMsg.getId(), 1);
                    }
                    @Override
                    public void onException(Throwable e) {
                        log.error("Feed MQ发送失败，等待补偿任务重试", e);
                    }
                });
            } catch (Exception e) {
                log.error("Feed MQ发送异常", e);
            }
        }
    });
}
```

**补偿任务**（每 30s 执行）：
```java
@XxlJob("feedMessageRetryJob")
public void retryFailedMessages() {
    // 扫描 status=0 且 created_at < NOW()-60s 且 retry_count < 3 的消息
    List<LocalMessage> messages = localMessageMapper.selectPending(60, 3);
    for (LocalMessage msg : messages) {
        try {
            rocketMQTemplate.syncSend(msg.getTopic(), msg.getBody(), 3000);
            localMessageMapper.updateStatus(msg.getId(), 1); // 成功
        } catch (Exception e) {
            localMessageMapper.incrementRetry(msg.getId()); // 重试+1
        }
    }
}
```

#### 1.2.2 消费端：Pipeline 批量推送 + 失败记录

```java
// FeedPushConsumer.pushToFollowers() 改造
private void pushToFollowers(Long authorId, Long noteId, long publishTime) {
    long cursor = 0;
    int batchSize = 500;
    
    while (true) {
        Set<String> followers = stringRedisTemplate.opsForZSet()
                .range(fansKey, cursor, cursor + batchSize - 1);
        if (followers == null || followers.isEmpty()) break;
        
        // Pipeline 批量写入收件箱（替代逐个 EVALSHA）
        List<Object> results = stringRedisTemplate.executePipelined(
            (RedisCallback<Object>) connection -> {
                for (String followerId : followers) {
                    byte[] inboxKey = ("myxhs:feed:inbox:" + followerId).getBytes();
                    connection.zSetCommands().zAdd(inboxKey, publishTime, 
                        String.valueOf(noteId).getBytes());
                }
                return null;
            }
        );
        
        // 检查 Pipeline 结果，记录失败的粉丝（后续可补偿）
        // Pipeline 返回值为 List，与 followers 一一对应
        
        cursor += batchSize;
    }
}
```

#### 1.2.3 收件箱裁剪改为异步 Job

当前 Lua 脚本中 ZADD 后立即 ZREMRANGEBYRANK 裁剪。改造后分离关注点：
- **推送阶段**：只做 ZADD（Pipeline 批量，性能极好）
- **裁剪阶段**：定时任务每小时扫描收件箱，ZCARD > 600 时裁剪到 500

### 1.3 改造文件清单

| 文件 | 改动 |
|------|------|
| `content` 模块新增 `t_local_message` 表 | 本地消息表 DDL |
| `NoteService.java` | 事务内写本地消息 |
| 新增 `FeedMessageRetryJob.java` | 补偿任务 |
| `FeedPushConsumer.java` | Pipeline 批量推送替代逐个 Lua |
| 新增 `FeedCleanupJob.java` | 收件箱裁剪独立 Job |

### 1.4 预期收益

- MQ 发送失败：本地消息表兜底，最多 30s 后重试
- 消费端推送：Pipeline 批量写入，10万粉丝从 200 次 Lua → 200 次 Pipeline（性能提升 3-5x）
- 裁剪分离后推送延迟降低

---

## 二、M4：IM 广播消费 CPU 浪费 — 定向路由改造

### 2.1 现状分析

**当前方案**：
```
ChatService.handleChat() 
    → 查 Redis 路由表 im:route:{receiverId} → 获取 targetServerId
    → rocketMQTemplate.convertAndSend("IM_ROUTE_TOPIC", routeMsg)  // 同步发送
    → ImRouteConsumer (BROADCASTING模式) 所有实例都收到
    → if (!localServerId.equals(targetServerId)) return;  // N-1 个实例白消费
```

**问题**：
- 10 个实例部署时，每条跨实例消息产生 10 次消费，仅 1 次有效
- CPU 浪费线性增长：实例数 × 消息数

### 2.2 改造方案对比

| 方案 | 优点 | 缺点 | 推荐度 |
|------|------|------|--------|
| A. RocketMQ Tag 过滤 | 改动最小 | Tag 只支持一级过滤，serverId 动态变化不适合 | ⭐⭐ |
| B. Redis Pub/Sub 替代 MQ | 精准投递，零浪费 | Redis 宕机丢消息，无持久化 | ⭐⭐⭐ |
| C. RocketMQ SQL 过滤 | 精准过滤 | 需开启 Broker enablePropertyFilter | ⭐⭐⭐ |
| D. 每实例独立 Topic | 精准投递 | 实例动态扩缩时 Topic 管理复杂 | ⭐ |

**推荐方案 B：Redis Pub/Sub**

理由：
1. IM 场景已有 Redis 路由表，Pub/Sub 与现有架构高度契合
2. 消息已通过 DB 持久化（ChatService 在路由前已写 DB），Pub/Sub 丢消息不影响数据完整性（用户重连拉离线消息）
3. 改动范围最小，且项目中 Notification 模块已有 Pub/Sub 使用经验

### 2.3 详细设计

#### 2.3.1 发送端改造（ChatService）

```java
// 原：MQ 广播
rocketMQTemplate.syncSend("IM_ROUTE_TOPIC", routeMsg);

// 改：Redis Pub/Sub 定向投递到目标实例的 Channel
String channel = "im:route:" + targetServerId;
stringRedisTemplate.convertAndSend(channel, JSON.toJSONString(routeMsg));
```

#### 2.3.2 消费端改造（替换 ImRouteConsumer）

```java
@Component
@RequiredArgsConstructor
public class ImRouteSubscriber {
    
    private final OnlineRouteService onlineRouteService;
    private final ImWebSocketHandler webSocketHandler;
    private final StringRedisTemplate stringRedisTemplate;
    private final ChatService chatService;  // 用于降级存离线
    
    @PostConstruct
    public void subscribe() {
        String localServerId = onlineRouteService.getServerId();
        String channel = "im:route:" + localServerId;
        
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(stringRedisTemplate.getConnectionFactory());
        container.addMessageListener((message, pattern) -> {
            RouteMessage routeMsg = JSON.parseObject(
                new String(message.getBody()), RouteMessage.class);
            
            // 组装推送 JSON（原 ImRouteConsumer 的 buildPushJson 内联逻辑）
            String pushJson = routeMsg.getMsgType() == 99
                ? routeMsg.getContent()  // 已读回执：content 已是完整 JSON
                : "{\"type\":\"CHAT\",\"data\":{\"msgId\":" + routeMsg.getMsgId()
                    + ",\"senderId\":" + routeMsg.getSenderId()
                    + ",\"content\":\"" + routeMsg.getContent() 
                    + "\",\"timestamp\":" + routeMsg.getTimestamp() + "}}";
            
            boolean pushed = webSocketHandler.pushToUser(routeMsg.getReceiverId(), pushJson);
            if (!pushed) {
                // 推送失败（用户已断连），降级存离线消息
                chatService.storeOfflineMessage(routeMsg.getReceiverId(), routeMsg.getMsgId());
            }
        }, new ChannelTopic(channel));
        
        container.afterPropertiesSet();
        container.start();
    }
}
```

#### 2.3.3 降级策略

Redis Pub/Sub 是 fire-and-forget 模式。当 Redis 连接抖动时：
- 消息已持久化到 DB（ChatService 先写 DB 再路由）
- 接收者重连后会拉取离线消息（ChatService.pullOfflineMessages）
- Pub/Sub 恢复后自动重新订阅

#### 2.3.4 【补充】handleRead（已读回执）和 handleTyping 一并改造

除了 `handleChat`，ChatService 还有两个发往 IM_ROUTE_TOPIC 的场景，改造时不能遗漏：

| 方法 | 当前实现 | 改造 |
|------|---------|------|
| `handleRead`（L226） | `convertAndSend("IM_ROUTE_TOPIC")`，失败仅 warn | 改为 Redis Pub/Sub `convertAndSend("im:route:" + targetServerId)` |
| `handleTyping`（L237） | **不支持跨实例**，仅做本实例 `pushToUser` | 改为 Pub/Sub，补全跨实例 TYPING 通知 |

```java
// handleRead 改造
public void handleRead(Long userId, ImMessage imMsg, WebSocketSession session) {
    // ... 原逻辑（更新未读计数）...
    String targetServerId = onlineRouteService.getRoute(peerId);
    if (targetServerId != null && !targetServerId.equals(localServerId)) {
        RouteMessage routeMsg = RouteMessage.builder()
                .receiverId(peerId).targetServerId(targetServerId)
                .msgType(99).content(readNotifyJson).build();
        try {
            stringRedisTemplate.convertAndSend("im:route:" + targetServerId,
                    JSON.toJSONString(routeMsg));
        } catch (Exception e) {
            log.warn("[IM] 已读回执Pub/Sub失败: peerId={}", peerId, e);
        }
    }
}

// handleTyping 改造（新增跨实例支持）
public void handleTyping(Long senderId, ImMessage imMsg) {
    Long receiverId = imMsg.getReceiverId();
    String targetServerId = onlineRouteService.getRoute(receiverId);
    if (targetServerId == null) return; // 用户不在线，无需发送

    String localServerId = onlineRouteService.getServerId();
    if (targetServerId.equals(localServerId)) {
        // 同实例直推
        webSocketHandler.pushToUser(receiverId, typingJson);
    } else {
        // 跨实例 Pub/Sub
        try {
            stringRedisTemplate.convertAndSend("im:route:" + targetServerId, typingJson);
        } catch (Exception e) {
            log.warn("[IM] TYPING Pub/Sub失败: receiverId={}", receiverId, e);
        }
    }
}
```

### 2.4 改造文件清单

| 文件 | 改动 |
|------|------|
| `ChatService.java` handleChat() | MQ 发送改为 Redis Pub/Sub |
| `ChatService.java` handleRead() | 同上，已读回执走 Pub/Sub |
| `ChatService.java` handleTyping() | 新增跨实例 TYPING 支持（Pub/Sub） |
| 删除 `ImRouteConsumer.java` | 不再需要 MQ 消费者 |
| 新增 `ImRouteSubscriber.java` | Redis Pub/Sub 订阅器 |
| `application.yml` | 移除 IM_ROUTE_TOPIC 相关配置 |

### 2.5 预期收益

- 10 实例部署：10 次无效消费 → 0 次无效消费（100% 精准投递）
- 延迟降低：MQ Broker 中转(2-5ms) → Redis Pub/Sub 直达(<1ms)
- CPU 节省：N 个实例每秒处理 M 条消息，节省 (N-1)×M 次反序列化

---

## 三、M8：IM 消息跨实例不保序 — 客户端重排 + 服务端有序保障

### 3.1 现状分析

**不保序原因**：
1. 用户 A 在实例 1 发消息 m1，用户 B 在实例 2 发消息 m2
2. m1 和 m2 经过不同的 MQ/Pub/Sub 通道到达接收者
3. 网络延迟不同，m2 可能先到

**当前处理**：客户端需要按 `timestamp` 或 `msgId` 重排序

### 3.2 改造方案

**核心思路**：服务端通过全局有序 ID + 会话级序列号保证消息顺序，客户端按序列号排序。

#### 3.2.1 消息ID改造：雪花ID保证全局单调递增

当前 `msgId` 使用 MyBatis-Plus 雪花算法（`IdWorker.getId()`）生成，64位分布式唯一ID，天然按时间单调递增。

```java
// ChatService.handleChat() 修改
ChatMessage message = new ChatMessage();
message.setId(IdWorker.getId()); // 当前已有雪花ID，无需改动
message.setConversationId(conversationId);
message.setSeqNo(generateSeqNo(conversationId)); // 【新增】会话级序列号
```

#### 3.2.2 会话级序列号

每个会话维护一个 Redis INCR 计数器，保证同一会话内消息严格有序：

```java
private long generateSeqNo(long conversationId) {
    String seqKey = "im:seq:" + conversationId;
    return stringRedisTemplate.opsForValue().increment(seqKey);
}
```

客户端按 `seqNo` 排序展示，遇到空洞（如收到 seq=5 但没收到 seq=4）时等待短暂窗口后向服务端请求补全。

#### 3.2.3 推送时携带序列号

```json
{
  "msgId": 1234567890123456789,
  "conversationId": 12345678901234,
  "seqNo": 42,
  "senderId": 1001,
  "content": "hello",
  "timestamp": 1717401600000
}
```

#### 3.2.4 【补充】存量数据 seqNo 回填策略

**问题**：新增 `seq_no` 列后，历史消息的 `seq_no` 为 NULL，离线消息拉取时排序会出错（NULL 值被排到最前或最后，打乱正常顺序）。

**方案**：上线前执行一回性回填脚本，按 `(conversation_id, created_at)` 分配序列号：

```sql
-- Step 1: 添加列（允许 NULL）
ALTER TABLE t_chat_message ADD COLUMN seq_no BIGINT DEFAULT NULL 
    COMMENT '会话内消息序列号';

-- Step 2: 回填历史数据（按会话 + 时间递增分配）
UPDATE t_chat_message m
JOIN (
    SELECT id, 
           ROW_NUMBER() OVER (PARTITION BY conversation_id ORDER BY created_at ASC) AS row_num
    FROM t_chat_message WHERE seq_no IS NULL
) t ON m.id = t.id
SET m.seq_no = t.row_num;

-- Step 3: 添加 NOT NULL 约束 + 联合索引
ALTER TABLE t_chat_message 
    MODIFY seq_no BIGINT NOT NULL COMMENT '会话内消息序列号',
    ADD INDEX idx_conversation_seq (conversation_id, seq_no);
```

**Redis 计数器初始化**：回填完成后，对每个会话设置 Redis 计数器为当前最大 seqNo：
```java
// 对每个 conversationId，初始化为 msg_seq_no 的最大值
List<Map<String, Object>> conversations = jdbcTemplate.queryForList(
    "SELECT conversation_id, MAX(seq_no) AS max_seq FROM t_chat_message GROUP BY conversation_id");
for (Map<String, Object> row : conversations) {
    String seqKey = "im:seq:" + row.get("conversation_id");
    stringRedisTemplate.opsForValue().set(seqKey, String.valueOf(row.get("max_seq")));
}
```

### 3.3 改造文件清单

| 文件 | 改动 |
|------|------|
| `t_chat_message` 表 | 新增 `seq_no` 列 + 联合索引 |
| `ChatMessage.java` | 新增 `seqNo` 字段 |
| `ChatService.java` | 消息创建时生成 seqNo |
| 客户端 | 按 seqNo 排序 + 空洞检测 |

### 3.4 预期收益

- 同会话消息严格有序（Redis INCR 原子保证）
- 跨会话消息通过雪花 ID 保证全局大致有序
- 客户端排序逻辑简化（seqNo 是纯数字比较）

---

## 四、M9：库存分桶数硬编码 — 动态分桶 + 平滑迁移

### 4.1 现状分析

**当前设计**：
```yaml
inventory:
  bucket:
    default-count: 2    # 普通SKU
    hot-count: 8         # 热点SKU（配置存在但未使用！）
```

**问题**：
1. `hot-count: 8` 是死配置，代码中未引用
2. 初始化后桶数写入 Redis `inventory:bucket:count:{skuId}`，但无法动态修改
3. 无扩缩容机制：SKU 突然成为热点时，2 个桶无法应对高并发
4. `initStock()` 有幂等检查（totalKey 存在则拒绝），不支持重新初始化

### 4.2 改造方案

**核心思路**：热点自动检测 + 分桶平滑扩容 + 数据无损迁移

#### 4.2.1 热点检测机制

基于滑动窗口检测 SKU 请求频率：

```java
@Component
public class HotSkuDetector {
    
    private static final String HOT_SKU_WINDOW = "inventory:hot:window:";
    private static final int HOT_THRESHOLD = 100; // 10秒内超过100次预扣
    
    /**
     * 记录一次预扣请求，返回是否为热点
     */
    public boolean recordAndCheck(Long skuId) {
        String key = HOT_SKU_WINDOW + skuId;
        long now = System.currentTimeMillis();
        
        // 滑动窗口：ZADD + ZREMRANGEBYSCORE + ZCARD
        stringRedisTemplate.opsForZSet().add(key, String.valueOf(now), now);
        stringRedisTemplate.opsForZSet().removeRangeByScore(key, 0, now - 10000);
        Long count = stringRedisTemplate.opsForZSet().zCard(key);
        stringRedisTemplate.expire(key, 15, TimeUnit.SECONDS);
        
        return count != null && count >= HOT_THRESHOLD;
    }
}
```

#### 4.2.2 平滑扩容流程

```
1. 热点检测触发 → 决定新桶数（如 2 → 8）
2. 获取分布式锁 inventory:resize:{skuId}
3. 暂停该 SKU 的预扣（SET inventory:paused:{skuId} 1, 5s TTL）
4. 读取当前各桶库存 → 计算总可用库存
5. 按新桶数重新均匀分配
6. 写入新桶 Key + 更新 bucket:count + 删除旧桶多余的 Key
7. 删除暂停标记
8. 释放锁
```

```java
public void resizeBuckets(Long skuId, int newBucketCount) {
    String lockKey = "inventory:resize:" + skuId;
    RLock lock = redissonClient.getLock(lockKey);
    
    try {
        if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
            throw new BizException("扩容操作进行中，请稍后重试");
        }
        
        // 1. 暂停预扣
        String pauseKey = "inventory:paused:" + skuId;
        stringRedisTemplate.opsForValue().set(pauseKey, "1", 30, TimeUnit.SECONDS);
        
        // 获取当前桶数
    int oldBucketCount = Integer.parseInt(stringRedisTemplate.opsForValue()
            .get(BUCKET_COUNT_KEY_PREFIX + skuId));
        int totalStock = 0;
        for (int i = 0; i < oldBucketCount; i++) {
            String val = stringRedisTemplate.opsForValue().get(bucketKey(skuId, i));
            totalStock += (val != null ? Integer.parseInt(val) : 0);
        }
        
        // 3. 按新桶数重新分配
        int perBucket = totalStock / newBucketCount;
        int remainder = totalStock % newBucketCount;
        
        // 4. Pipeline 原子写入
        stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            var cmd = connection.stringCommands();
            for (int i = 0; i < newBucketCount; i++) {
                int stock = perBucket + (i == 0 ? remainder : 0);
                cmd.set(bucketKey(skuId, i).getBytes(), String.valueOf(stock).getBytes());
            }
            // 删除多余的旧桶
            for (int i = newBucketCount; i < oldBucketCount; i++) {
                connection.keyCommands().del(bucketKey(skuId, i).getBytes());
            }
            // 更新桶数
            cmd.set((BUCKET_COUNT_KEY_PREFIX + skuId).getBytes(),
                     String.valueOf(newBucketCount).getBytes());
            // 更新总库存
            cmd.set(totalKey(skuId).getBytes(), String.valueOf(totalStock).getBytes());
            return null;
        });
        
        // 5. 恢复预扣
        stringRedisTemplate.delete(pauseKey);
        
        log.info("[库存] 分桶扩容完成: skuId={}, {}桶→{}桶, total={}",
                skuId, oldBucketCount, newBucketCount, totalStock);
                
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    } finally {
        if (lock.isHeldByCurrentThread()) lock.unlock();
    }
}
```

#### 4.2.3 preDeduct 中检查暂停标记

```java
public void preDeduct(PreDeductRequest request) {
    Long skuId = request.getSkuId();

    // 检查是否正在扩容（注意：preDeduct 是 MQ 消费者触发的本地调用，不能抛 BizException）
    String pauseKey = "inventory:paused:" + skuId;
    if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(pauseKey))) {
        // MQ 消费者场景：抛异常让 RocketMQ 延迟重试（利用重试延迟机制）
        log.info("[库存] SKU正在扩容中，延迟消费: skuId={}", skuId);
        throw new RuntimeException("SKU扩容中，稍后重试");
    }

    // 热点检测（异步触发扩容，不阻塞当前请求）
    if (hotSkuDetector.recordAndCheck(skuId)) {
        int currentBuckets = Integer.parseInt(stringRedisTemplate.opsForValue()
                .get(BUCKET_COUNT_KEY_PREFIX + skuId));
        if (currentBuckets < hotBucketCount) {
            // 使用本地线程池，不阻塞主流程
            inventoryAsyncExecutor.execute(() -> resizeBuckets(skuId, hotBucketCount));
        }
    }

    // ... 原有预扣逻辑
}
```

#### 4.2.4 启用 hot-count 配置

```java
@Value("${inventory.bucket.hot-count:8}")
private int hotBucketCount;
```

#### 4.2.5 【补充】inventoryAsyncExecutor 独立定义

`InventoryService` 是独立微服务，不能引用 `SpuService.SPU_ASYNC_EXECUTOR`。需在本模块创建独立线程池：

```java
/** 【M9】异步扩容线程池（核心2，最大4，队列50，CallerRunsPolicy 防 OOM） */
private static final ExecutorService inventoryAsyncExecutor =
    new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(50),
        r -> { Thread t = new Thread(r, "inventory-async"); t.setDaemon(true); return t; },
        new ThreadPoolExecutor.CallerRunsPolicy());
```

#### 4.2.6 【补充】resizeBuckets 异常处理完善

当前代码只在 `catch (InterruptedException e)` 中恢复中断标志，但 Redis 连接断开等异常会导致 pauseKey 残留（TTL 30s 后自动清除，但期间所有预扣都会延迟重试）。用 try-finally 保证清理：

```java
public void resizeBuckets(Long skuId, int newBucketCount) {
    String pauseKey = "inventory:paused:" + skuId;
    String lockKey = "inventory:resize:" + skuId;
    RLock lock = redissonClient.getLock(lockKey);

    try {
        if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) return;

        // 暂停预扣
        stringRedisTemplate.opsForValue().set(pauseKey, "1", 30, TimeUnit.SECONDS);
        try {
            // ... 扩容逻辑（收集桶库存 + Pipeline 重新分配）...
        } finally {
            // 无论成功与否，恢复预扣
            stringRedisTemplate.delete(pauseKey);
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    } finally {
        if (lock.isHeldByCurrentThread()) lock.unlock();
    }
}
```

#### 4.2.7 【补充】HotSkuDetector 内存优化

当前用 `System.currentTimeMillis()` 做 ZSet member，毫秒级唯一，10 秒窗口积累大量 member。改为**秒级时间戳**减少 member 数量：

```java
// 优化：用秒级时间戳 + count 后缀，大幅减少 member 数量
long nowSec = System.currentTimeMillis() / 1000;
String member = nowSec + ":" + count; // 每秒合并同秒请求
stringRedisTemplate.opsForZSet().add(key, member, nowSec);
// ZREMRANGEBYSCORE 不需要改（score 是秒级，对应 10s 前也是秒级）
stringRedisTemplate.opsForZSet().removeRangeByScore(key, 0, nowSec - 10);
```

#### 4.2.8 【补充】initStock 重新初始化 API

当前 `initStock()` 有幂等检查（totalKey 存在即拒绝），扩容时需要重新初始化。新增管理接口：

```java
/**
 * 重新初始化库存（仅管理后台调用）
 * 流程：清除 Redis → 从 MySQL 恢复 → 重新分桶
 */
public void reinitStock(Long skuId, int newBucketCount) {
    // 1. 从 MySQL 获取权威库存
    Inventory inventory = inventoryMapper.selectBySkuId(skuId);
    if (inventory == null) throw new BizException("SKU库存不存在");

    // 2. 清除该 SKU 的所有 Redis Key
    Set<String> keys = stringRedisTemplate.keys("inventory:{*}:bucket:" + skuId);
    if (keys != null) stringRedisTemplate.delete(keys);
    stringRedisTemplate.delete(totalKey(skuId));
    stringRedisTemplate.delete("inventory:bucket:count:" + skuId);
    stringRedisTemplate.delete("inventory:paused:" + skuId);

    // 3. 重新初始化
    InventoryInitRequest req = new InventoryInitRequest();
    req.setSkuId(skuId);
    req.setTotalStock(inventory.getAvailableStock() + inventory.getLockedStock());
    req.setBucketCount(newBucketCount);
    initStock(req);
}
```

Controller 暴露：
```java
@PostMapping("/api/inventory/reinit")
public Result<Void> reinitStock(@RequestBody @Valid InventoryInitRequest request) {
    inventoryService.reinitStock(request.getSkuId(), 
        request.getBucketCount() != null ? request.getBucketCount() : defaultBucketCount);
    return Result.success();
}
```

### 4.3 对账任务增强

在 `InventoryReconcileJob` 中增加分桶完整性检查：

```java
// 新增：检查各桶库存之和 == 总库存
private void reconcileBuckets(Long skuId) {
    String countStr = stringRedisTemplate.opsForValue()
            .get("inventory:bucket:count:" + skuId);
    if (countStr == null) return;
    int bucketCount = Integer.parseInt(countStr);
    int bucketTotal = 0;
    for (int i = 0; i < bucketCount; i++) {
        String val = stringRedisTemplate.opsForValue().get(bucketKey(skuId, i));
        bucketTotal += (val != null ? Integer.parseInt(val) : 0);
    }
    
    String totalVal = stringRedisTemplate.opsForValue().get(totalKey(skuId));
    int total = totalVal != null ? Integer.parseInt(totalVal) : 0;
    
    if (bucketTotal != total) {
        log.warn("[对账] 分桶总量不一致: skuId={}, bucketSum={}, total={}", 
                skuId, bucketTotal, total);
        // 以分桶之和为准修正总库存
        stringRedisTemplate.opsForValue().set(totalKey(skuId), String.valueOf(bucketTotal));
    }
}
```

### 4.4 改造文件清单

| 文件 | 改动 |
|------|------|
| 新增 `HotSkuDetector.java` | 热点检测组件 |
| `InventoryService.java` | 新增 resizeBuckets() + reinitStock() + inventoryAsyncExecutor |
| `InventoryService.java` preDeduct() | 检查暂停标记 + 热点检测触发 |
| `InventoryController.java` | 新增 `POST /api/inventory/reinit` 接口 |
| `InventoryReconcileJob.java` | 新增分桶完整性对账 |
| `InventoryInitRequest.java` | bucketCount 添加 @Min(1) @Max(32) 校验（已修复） |
| `application.yml` | 新增热点阈值配置 |

### 4.5 预期收益

- 普通 SKU 2 桶低资源消耗，热点 SKU 自动扩容到 8 桶
- 扩容过程数据无损（暂停窗口 < 1s）
- 对账任务覆盖分桶一致性检查

---

## 五、改造优先级与排期建议

| 优先级 | 改造项 | 复杂度 | 预估工期 | 价值 |
|--------|--------|--------|---------|------|
| **P0** | M4: IM 广播→Pub/Sub | 低 | 1天 | 消除 N 倍 CPU 浪费 |
| **P1** | M2: Feed 推送补偿 | 中 | 2天 | 消除消息丢失风险 |
| **P1** | M8: IM 消息保序 | 中 | 2天 | 消除消息乱序体验问题 |
| **P2** | M9: 库存动态分桶 | 高 | 3天 | 热点SKU自动扩容能力 |

**建议执行顺序**：M4 → M2 → M8 → M9

- M4 改动最小（替换 MQ 为 Pub/Sub），收益最直接
- M2 涉及本地消息表和 Pipeline 改造，需要新建表
- M8 需要客户端配合改造（seqNo 排序）
- M9 最复杂，涉及热点检测+平滑迁移，可作为后续优化

---

## 六、风险评估

| 改造项 | 主要风险 | 缓解措施 |
|--------|---------|---------|
| 改造项 | 主要风险 | 缓解措施 |
|--------|---------|---------|
| M4 Pub/Sub | Redis 宕机时跨实例消息丢失 | 消息已持久化DB，重连拉离线兜底 |
| M4 handleTyping 跨实例 | TYPING 通知频率高，Pub/Sub 流量增加 | TYPING 不需要可靠性，发送失败不补发 |
| M2 本地消息表 | 补偿任务扫描对 DB 有压力 | 游标分页 + 限制每次扫描条数 |
| M8 序列号 | Redis INCR 高并发压力 | 会话粒度隔离，单会话并发低 |
| M8 存量数据回填 | 回填脚本执行期间不能有写入 | 安排维护窗口执行，或分批次回填 |
| M9 扩容暂停 | 暂停窗口内预扣延迟 | MQ 自动重试 + 暂停时间 < 1s，异常时 TTL 兜底 |
| M9 reinitStock | 误操作清除生产数据 | 仅管理后台可调用 + 限流 + Redis Key 匹配精确化 |

---

## 附录：二次 Review 验证结论与方案修正

> 以下是对架构方案逐一验证后的修正和补充。

### A. M4 方案验证结论

| 验证项 | 结论 |
|--------|------|
| ChatService MQ 发送方式 | `convertAndSend`（同步），发送失败有降级：存离线消息 ✅ |
| 改 Pub/Sub 后发送端容错 | 可沿用现有降级逻辑（catch 异常 → 存离线）✅ |
| 接收端容错 | ⚠️ Pub/Sub fire-and-forget，目标实例网络抖动时消息丢失 |
| ImRouteConsumer 是否有副作用 | 无（纯路由+推送），可直接替换 ✅ |
| pushToUser 线程安全 | `synchronized(session)` 保护，多线程并发安全 ✅ |
| Redis 连接资源 | Spring RedisMessageListenerContainer 自动管理，与业务连接隔离 ✅ |

**方案修正**：Pub/Sub 订阅端推送失败后需增加"存离线消息"兜底：
```java
// ImRouteSubscriber 订阅回调中
boolean pushed = webSocketHandler.pushToUser(receiverId, pushJson);
if (!pushed) {
    // 推送失败（用户已断连），降级存离线
    chatService.storeOfflineMessage(receiverId, routeMsg.getMsgId());
}
```

### B. M2 方案验证结论

| 验证项 | 结论 |
|--------|------|
| Pipeline 中能否执行 Lua | ❌ 不能！代码注释已说明 |
| 收件箱裁剪时机 | 当前在 Lua 中与 ZADD 一起原子执行 |
| 改为 Pipeline 的方式 | 先 Pipeline 批量 ZADD，裁剪改为异步 Job |
| content 模块是否有 LocalMessage | ❌ 没有，需新建 |
| order 模块本地消息表可复用 | ✅ 实体/Mapper/Job 设计均可参考 |
| publishDraft 缺少 Feed 推送 | ✅ 确认是功能缺失，需补充 |
| RocketMQ 重试机制 | 正常工作，ZADD 幂等不怕重复消费 ✅ |

**方案修正**：
1. 明确 Pipeline 方案：先 `Pipeline 批量 ZADD + EXPIRE`（一批 500 粉丝 1 次网络往返），裁剪逻辑移到 `FeedCleanupJob`（已存在类似 Job）
2. 补充 `publishDraft` 方法需添加 Feed 推送逻辑

### C. M8 方案验证结论

| 验证项 | 结论 |
|--------|------|
| ChatMessage 有无 seqNo | ❌ 没有，需新增字段 |
| 消息ID生成方式 | 雪花算法 `IdWorker.getId()`，全局单调递增 ✅ |
| 离线消息排序 | ⚠️ `selectBatchIds` 无 ORDER BY，当前已存在乱序 Bug |
| 改 seqNo 对离线消息影响 | 需在 `pushOfflineMessages` 中按 seqNo 排序后再推送 |

**方案修正**：
1. 新增字段时需同步修复离线消息的排序 Bug（`pushOfflineMessages` 中添加 `.sorted(Comparator.comparing(ChatMessage::getSeqNo))`）
2. 历史消息查询 `getMessageHistory` 的 `orderByDesc(ChatMessage::getCreatedAt)` 应改为 `orderByDesc(ChatMessage::getSeqNo)`

### D. M9 方案验证结论

| 验证项 | 结论 |
|--------|------|
| preDeduct 调用入口 | MQ 消费触发本地调用（非 Feign 同步），暂停标记不影响 Feign 超时 ✅ |
| 暂停期间 MQ 行为 | 消息积压在 Broker，恢复后自动消费 ✅ |
| PreDeductTimeoutJob 的 release 调用 | ❌ **仍只传 2 KEYS**（已在本次修复） |
| PreDeductTimeoutJob `:bucket` 过滤 | ❌ **遗漏**（已在本次修复） |

**已执行修复**：
- `PreDeductTimeoutJob.releasePreDeduct()` 已更新为 3 KEYS + `:bucket` 过滤 + 新 Key 格式

**方案修正**：
- preDeduct 入口是 MQ 消费而非 Feign 同步调用，暂停标记方案需调整：
  - 不能在 preDeduct 入口抛 `BizException`（MQ 消费者会无限重试）
  - 改为：消费者检测到暂停标记时，`Thread.sleep(1000)` 短暂等待后重试，或抛异常让 MQ 延迟重试（利用 RocketMQ 的重试延迟机制）

---

### E. 额外发现的 Bug（已修复）

| Bug | 位置 | 修复 |
|-----|------|------|
| PreDeductTimeoutJob release 只传 2 KEYS | `PreDeductTimeoutJob.java` 第 142 行 | 改为传 3 KEYS（totalKey + predeductKey + bucketKey） |
| PreDeductTimeoutJob 未过滤 `:bucket` 字段 | 同上 第 136 行 | 添加 `fieldName.contains(":bucket")` 过滤 |
| PreDeductTimeoutJob Key 前缀为旧格式 | 第 46 行 | 更新为 `inventory:{%d}:total` 新格式 |

