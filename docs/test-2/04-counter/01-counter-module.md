# my-xhs-counter 计数服务模块

## 模块概览

| 项目 | 内容 |
|------|------|
| 模块路径 | `my-xhs-counter/` |
| 端口 | 19004 |
| 服务名 | `my-xhs-counter`（Nacos） |
| 数据库 | `my_xhs_counter`（MySQL 13307，独立数据库） |
| Java 源文件 | 15 个（含 DTO/Entity/Enum） |
| 启动类 | `CounterApplication.java` |
| 扫描包 | `com.myxhs.counter`, `com.myxhs.common` |

**职责边界**：统一管理所有计数（点赞数、收藏数、评论数、粉丝数、关注数、浏览数等），提供增减和查询接口。使用独立数据库 `my_xhs_counter@13307`，与 content 模块通过 MQ 事件解耦数据同步。

**核心设计理念**：

```
写链路：业务事件 → MQ → Redis INCR/DECR（实时）→ Buffer 攒批 → 定时/满量刷盘 MySQL
读链路：Redis GET（< 1ms）→ MySQL 兜底
一致性：Buffer-Trigger 正常刷盘 → 失败重试 3 次 → 凌晨对账修复
```

---

## 1. 数据模型

### 1.1 数据库表 — `t_counter`

```sql
id            BIGINT PRIMARY KEY        -- 雪花 ID（IdGeneratorUtil.nextId()）
target_type   INT                       -- 目标类型：1=笔记 2=用户
target_id     BIGINT                    -- 目标 ID
count_type    INT                       -- 计数类型：1=点赞 2=收藏 3=评论 4=分享 5=浏览 6=粉丝 7=关注
count_value   BIGINT                    -- 计数当前值
deleted       TINYINT DEFAULT 0         -- 逻辑删除（@TableLogic）
created_at    DATETIME
updated_at    DATETIME
```

唯一索引：`uk_target_count (target_type, target_id, count_type)`

**采用软删除**（`@TableLogic`），删除操作只标记 `deleted=1` 而非物理删除。

### 1.2 计数类型枚举（CountType）

| Code | 英文名 | 中文 |
|:--:|--------|------|
| 1 | `like` | 点赞数 |
| 2 | `collect` | 收藏数 |
| 3 | `comment` | 评论数 |
| 4 | `share` | 分享数 |
| 5 | `view` | 浏览数 |
| 6 | `follower` | 粉丝数 |
| 7 | `following` | 关注数 |

### 1.3 目标类型枚举（TargetType）

| Code | 描述 |
|:--:|------|
| 1 | 笔记 |
| 2 | 用户 |

### 1.4 Redis Key 设计

```
格式：myxhs:counter:{targetType}:{targetId}:{countType}
示例：myxhs:counter:1:20001:1 （笔记 20001 的点赞数）
      myxhs:counter:2:1001:6  （用户 1001 的粉丝数）
```

**实际写入端口**：16379（Sentinel Master）— 经实测验证，`CounterService` 注入的 `StringRedisTemplate` 是 Spring Boot 默认 Bean，通过 Sentinel 发现 Master 写入。`cache.port=16380` 和 `business.port=16381` 是 common 模块自定义 Bean 的分端口，Counter 直接使用默认连接。数据永久保留，不设 TTL。

---

## 2. 接口清单（5 个 REST 端点）

| 方法 | 路径 | 说明 | 可见性 | 限流 |
|:----:|------|------|:------:|------|
| POST | `/api/counter/increment` | 计数 +1 | 内部 | @RateLimit(60s/500次) |
| POST | `/api/counter/decrement` | 计数 -1（带归零保护） | 内部 | @RateLimit(60s/500次) |
| GET | `/api/counter/get` | 查询单个计数 | 公开 | 无（只读） |
| POST | `/api/counter/batch-get` | 批量查询计数（Pipeline） | 公开 | 无（只读） |
| POST | `/api/counter/reconcile` | 手动触发对账修复 | 管理 | @RateLimit(60s/2次) |

### 2.1 POST /api/counter/increment

```json
// 请求
POST /api/counter/increment
{
    "targetType": 1,     // 1=笔记 2=用户
    "targetId": 20001,   // 笔记ID 或 用户ID
    "countType": 1       // 1=点赞 2=收藏 3=评论 ...
}

// 响应
{"code": 200, "message": "success"}
```

### 2.2 POST /api/counter/decrement

同 increment 参数，但带归零保护——计数为 0 时拒绝扣减，返回 `{"code": 500, "message": "计数已为 0，无法继续减少"}`。

### 2.3 GET /api/counter/get

```
GET /api/counter/get?targetType=1&targetId=20001&countType=1

// 响应
{"code": 200, "data": 42}
```

### 2.4 POST /api/counter/batch-get

使用 Redis Pipeline 批量查询，一次往返即可获取多个计数。

```json
// 请求
POST /api/counter/batch-get
{
    "queries": [
        {"targetType": 1, "targetId": 20001, "countTypes": [1, 2, 3]},
        {"targetType": 1, "targetId": 20002, "countTypes": [1]}
    ]
}

// 响应
{
    "code": 200,
    "data": {
        "1:20001": {"like": 42, "collect": 18, "comment": 128},
        "1:20002": {"like": 7}
    }
}
```

### 2.5 POST /api/counter/reconcile

管理接口，手动触发全量对账修复。返回修复条数。

---

## 3. 内部架构

```
┌──────────────────────────────────────────────────────┐
│                    Counter 服务                       │
│                                                      │
│  ┌──────────────────┐  ┌─────────────────────────┐   │
│  │ CounterController│  │ CounterEventConsumer     │   │
│  │  ├─ increment    │  │  ├─ handleLikeEvent      │   │
│  │  ├─ decrement    │  │  └─ handleFavoriteEvent   │   │
│  │  ├─ get          │  │  MQ: SOCIAL_TOPIC         │   │
│  │  ├─ batch-get    │  │  Tags: LIKE/UNLIKE/        │   │
│  │  └─ reconcile    │  │  FAVORITE/UNFAVORITE       │   │
│  └────────┬─────────┘  └────────────┬────────────┘   │
│           │                         │                │
│           └──────────┬──────────────┘                │
│                      ▼                               │
│           ┌─────────────────────┐                    │
│           │   CounterService    │                    │
│           │  ├─ increment()     │  Redis INCR/DECR   │
│           │  ├─ decrement()     │  Lua 归零保护      │
│           │  ├─ getCount()      │  Pipeline 批量 GET │
│           │  ├─ batchGetCounts()│  MySQL 兜底        │
│           │  └─ reconcile()     │  游标分页对账      │
│           └────────┬────────────┘                    │
│                    │                                 │
│     ┌──────────────┼──────────────┐                  │
│     ▼              ▼              ▼                  │
│ ┌─────────┐  ┌──────────┐  ┌──────────────┐         │
│ │ Redis   │  │ Counter  │  │ CounterMapper │         │
│ │16379    │  │ Buffer   │  │  ├ batchUpsert  │         │
│ │业务库   │  │ 双Buffer │  │  └ selectBy..  │         │
│ └─────────┘  │ 满量+定时 │  └──────┬───────┘         │
│              └─────┬─────┘         │                  │
│                    │               │                  │
│                    └───────┬───────┘                  │
│                            ▼                          │
│                    ┌──────────────┐                   │
│                    │   MySQL      │                   │
│                    │ t_counter    │                   │
│                    └──────────────┘                   │
│                                                      │
│  ┌─────────────────────────┐                         │
│  │ CounterReconcileJob     │                         │
│  │ XXL-Job: 凌晨 3:00      │                         │
│  │ 游标分页 → Pipeline     │                         │
│  │ Redis↔MySQL 逐条比对    │                         │
│  └─────────────────────────┘                         │
└──────────────────────────────────────────────────────┘
```

### 三层一致性保障

| 层级 | 机制 | 触发条件 |
|:--:|------|------|
| 第一层 | Buffer-Trigger 正常刷盘 | 满 100 条 或 每 5 秒 |
| 第二层 | 失败重试（递增退避） | 刷盘失败时，最多 3 次 |
| 第三层 | XXL-Job 对账修复 | 每天凌晨 3:00，全量扫描 |

### 为什么不直接用 INCR 后立马写 DB？

高并发场景下，每秒钟数万次点赞 → 数万次 UPDATE `t_counter` → DB 成为瓶颈。Buffer-Trigger 方案将数万次写入合并为数百个 Key，一次 `INSERT ON DUPLICATE KEY UPDATE` 完成，DB 压力降低 10~50 倍。

---

## 4. 业务代码详解

### 4.1 写链路 — increment / decrement

```
increment(targetType, targetId, countType):
    1. Redis INCR → 实时生效，用户立即可见
    2. Buffer.add(targetType, targetId, countType, +1) → 攒批后刷盘 DB

decrement(targetType, targetId, countType):
    1. Lua 脚本：GET 当前值 → 若 ≤0 → 返回 0（拒绝扣减）
    2. 若 > 0 → DECR
    3. Buffer.add(targetType, targetId, countType, -1)
```

**Lua 归零保护脚本**：保证「检查+扣减」原子性，防止并发 DECR 导致计数变为负数。

```lua
local current = tonumber(redis.call('GET', KEYS[1]) or '0')
if current <= 0 then return 0 end
redis.call('DECR', KEYS[1])
return 1
```

**设计细节**：Lua 脚本提为静态常量 `DECREMENT_SCRIPT`，复用 Redis SHA1 缓存，走 EVALSHA 命令避免每次发送完整脚本体。

### 4.2 读链路 — 两级缓存

```
getCount(targetType, targetId, countType):
    L1: redisValue = stringRedisTemplate.opsForValue().get(redisKey)
        → 有值 → 直接返回
    L2: counter = counterMapper.selectByTarget(...)
        → 回填 Redis → 返回
```

**为什么要两级缓存？** Redis 虽然持久化（AOF/RDB），但极端情况下可能数据丢失。MySQL 作为 L2 兜底，确保重启后计数不丢失。回填 Redis 确保后续查询走快速路径。

**为什么不用 Caffeine 本地缓存？** 计数是高频变更数据，Caffeine 在多实例部署时会导致各节点看到不同值（实例 A 点赞后只能失效自己的 Caffeine，实例 B 仍返回旧值）。Redis GET 延迟 < 1ms，完全满足需求。

### 4.3 批量查询 — Pipeline 优化

```
batchGetCounts(request):
    1. 展开所有查询项 → redisKeys[]（如查询 3 个笔记各 2 种计数 → 6 个 Key）
    2. executePipelined(GET × 6) → 1 次网络往返
    3. Pipeline 未命中的 → 收集 → selectByTargets(queries) → 1 次 MySQL 查询
    4. 回填 Redis → 组装 response
```

相比逐条 GET 的 N+1 往返，Pipeline 将延迟从 N×RTT 降为 RTT+1。

### 4.4 MySQL 批量兜底 — 避免 N+1

当 Pipeline 结果中某些 Key 未命中时，**不是逐条查 MySQL**（N+1 问题），而是：

```
List<CounterBatchQuery> missedQueries = [未命中的所有项]
List<Counter> dbResults = counterMapper.selectByTargets(missedQueries)
                       // WHERE (target_type, target_id, count_type) IN ((1,20001,1),(1,20001,2),...)
```

1 次 SQL 替代 N 次循环单查，利用复合 WHERE IN 优化。

---

## 5. CounterBuffer — Buffer-Trigger 攒批刷盘

### 5.1 设计动机

| 方案 | 每次点赞 SQL | 10000 QPS SQL | 结果 |
|------|:----------:|:-------------:|------|
| 直接写 DB | 1 次 UPDATE | 10000 次/秒 | DB 扛不住 |
| Buffer-Trigger | 0 次（攒批） | 约 200 次/秒 | 降低 50 倍 |

### 5.2 核心数据结构

```java
// 缓冲区：Key = "targetType:targetId:countType", Value = 累计增量
volatile ConcurrentHashMap<String, AtomicLong> buffer
AtomicInteger bufferSize  // 写入计数
ReentrantLock flushLock   // 刷盘锁（tryLock 非阻塞）
```

### 5.3 合并策略

同一个 Key 的多次增减合并为一次：

```
buffer.computeIfAbsent(key, k -> new AtomicLong(0)).addAndGet(delta);

+1, +1, -1, +1 → AtomicLong = +2 → 1 次 SQL delta=+2
```

4 次写入变 1 次 SQL，写入效率提升 4 倍。

### 5.4 双 Buffer 交换方案

```
flush() 执行时：
    1. 将当前 buffer 换出 → snapshot
    2. 立即创建新 buffer → 接收后续写入
    3. 对 snapshot 进行合并、排序、分批写入 DB
```

**为什么要双 Buffer？** 如果 flush 时直接在原 buffer 上清空（clear），清理瞬间写入的数据会丢失。双 Buffer 交换消除了这个窗口。

### 5.5 触发条件（先到先触发）

| 条件 | 触发方式 | 阈值 |
|------|------|:--:|
| 满量触发 | 每次 add() 检查 bufferSize | ≥ 100 条 |
| 定时触发 | `@Scheduled(fixedRate = 5000)` | 每 5 秒 |

### 5.6 并发控制 — ReentrantLock.tryLock()

使用 `ReentrantLock.tryLock()` 非阻塞方式：

```
flush():
    if (!flushLock.tryLock()) → 说明其他线程正在刷盘 → 跳过
    try { doFlush(); } finally { flushLock.unlock(); }
```

相比 `synchronized` 的优势：
- synchronized 会阻塞调用线程 → 高并发下 add() 线程被阻塞 → 写入延迟增大
- tryLock 非阻塞 → add() 线程立即返回 → 本次数据留在 buffer 中，下次定时/满量触发时再刷

### 5.7 优雅停机 — @PreDestroy

JVM 关闭前强制刷盘，使用 `lock()` 阻塞等待（而非 tryLock），确保刷盘一定执行：

```java
@PreDestroy void shutdown():
    flushLock.lock();
    try { doFlush(); } finally { flushLock.unlock(); }
```

同时实现 `GracefulShutdownHook.onShutdown()`，由 GracefulShutdownListener 在 Nacos 注销后调用，保证执行顺序。

### 5.8 刷盘死锁预防

`batchUpsert` 使用 `INSERT ON DUPLICATE KEY UPDATE`，多个事务交叉加行锁可能导致 DB 层死锁。预防措施：

```
flushList.sort(Comparator.comparing(CounterFlushDTO::getTargetType)
        .thenComparing(CounterFlushDTO::getTargetId)
        .thenComparing(CounterFlushDTO::getCountType));
```

按唯一索引排序后分批写入，确保所有事务以相同顺序加锁，消除死锁。

---

## 6. MQ Consumer — CounterEventConsumer

### 6.1 消费配置

```java
@RocketMQMessageListener(
    topic = "SOCIAL_TOPIC",
    selectorExpression = "LIKE||UNLIKE||FAVORITE||UNFAVORITE",
    consumerGroup = "counter-consumer-group",
    maxReconsumeTimes = 3
)
```

### 6.2 事件映射规则

| MQ Tag | 事件来源 | 目标 | 操作 |
|--------|------|------|------|
| `LIKE` | analytics | 笔记点赞数 +1（bizType=1 时） | increment(targetType=1, targetId=bizId, countType=1) |
| `UNLIKE` | analytics | 笔记点赞数 -1 | decrement(targetType=1, targetId=bizId, countType=1) |
| `FAVORITE` | analytics | 笔记收藏数 +1 | increment(targetType=1, targetId=noteId, countType=2) |
| `UNFAVORITE` | analytics | 笔记收藏数 -1 | decrement(targetType=1, targetId=noteId, countType=2) |

**bizType=2（评论）的点赞事件暂不处理。**

### 6.3 消息解析

使用 `ObjectMapper.readValue(message, Map.class)` 通用解析，避免强依赖 LikeEvent/FavoriteEvent 的具体类结构。通过 `toInt()`/`toLong()` 安全转换。

### 6.4 幂等性

计数操作本身不是幂等的（INCR/DECR 多次执行会导致计数偏差）。但 RocketMQ 至少一次语义下，重复消费概率极低。即使偶尔重复，凌晨对账修复会自动修正。

### 6.5 MQ 链路追踪

消费前调用 `MqTraceHelper.restoreTraceContext(msg)` 恢复 TraceId，消费后 `clearTraceContext()` 清理。

---

## 7. 定时对账修复

### 7.1 XXL-Job 配置

```
Job: counterReconcileJob
Cron: 0 0 3 * * ?（每天凌晨 3 点）
Executor: my-xhs-counter
路由策略: 第一个（或分片广播）
```

XXL-Job Admin 调度只派发给一个 Executor 实例，无需 Redisson 分布式锁。

### 7.2 对账算法（游标分页 + Pipeline）

```java
reconcile():
    lastId = 0, batchSize = 1000
    do:
        batch = selectBatchAfterId(lastId, batchSize)  // 游标分页，避免 OFFSET 深分页
        lastId = batch[-1].id
        
        // Pipeline 批量 GET Redis（N 次往返 → 1 次）
        redisValues = multiGet(redisKeys)
        
        for each record:
            if redisCount ≠ dbCount:
                if redisCount == 0 && dbCount > 0:
                    Redis = dbCount  // Redis 恢复（以 DB 为准）
                else:
                    DB = redisCount  // DB 修正（以 Redis 为准）
                fixedCount++
    while batch.size() == batchSize
```

### 7.3 修复策略

| 场景 | 决策 | 理由 |
|------|------|------|
| Redis ≠ DB，Redis > 0 | 以 Redis 为准 → 更新 DB | Redis 是实时更新的权威源 |
| Redis = 0，DB > 0 | 以 DB 为准 → 恢复 Redis | Redis 可能数据丢失 |
| Redis 有值，DB 无记录 | 不在此算法中 | reconcile 只扫描 DB 现有记录，不会发现 DB 无行的情况——等 Buffer 下次刷盘自然解 |

---

## 8. Gateway 交互

Counter 服务的接口对外暴露情况：

| API | 网关路由 | 鉴权 | 限流 |
|-----|:---:|:---:|:---:|
| GET `/api/counter/get` | 公开 | 无 | 1000 QPS |
| POST `/api/counter/batch-get` | 公开 | 无 | 500 QPS |
| POST `/api/counter/increment` | 不暴露 | — | 内部 |
| POST `/api/counter/decrement` | 不暴露 | — | 内部 |
| POST `/api/counter/reconcile` | 不暴露 | — | 管理 |

increment/decrement 主要通过 MQ 事件驱动（CounterEventConsumer），HTTP 接口仅作为备用/测试通道。

Gateway 的限流配置在 `my-xhs-gateway` 模块的 Nacos 配置中定义。

---

## 9. 资源配置

### 9.1 线程池

```
Tomcat: max=150 / min-spare=15 / max-connections=8192
Lettuce Redis Pool: max-active=15 / max-idle=8 / min-idle=4
Feign HTTP Client: max-connections=200 / max-per-route=50
```

相比 analytics（写密集型，Tomcat max=200），counter 设为 150——主要负载是 Buffer 攒批，对外 HTTP 请求量较小。

### 9.2 连接超时

| 组件 | 超时 | 配置 |
|------|:--:|------|
| Tomcat | 连接 30s / Keep-Alive 60s | application.yml |
| Feign | 连接 0.5s / 读取 2s | application.yml |
| RocketMQ Producer | 发送 3s | application.yml |
| Redis Lettuce | 连接等待 3s | application.yml |

### 9.3 排除的自动装配

```yaml
# 减少内存占用和启动时间
exclude:
  - SecurityAutoConfiguration
  - BatchAutoConfiguration
  - QuartzAutoConfiguration
  - WebSocketServletAutoConfiguration
  - JmxAutoConfiguration
```

---

## 10. 被下游调用

Counter 是纯消费者——消费 MQ 事件更新计数，提供查询接口给其他服务。

### 10.1 调用方（通过 MQ）

| 发送方 | Topic | Tag | 目的 |
|--------|--------|-----|------|
| analytics 服务 | SOCIAL_TOPIC | LIKE / UNLIKE | 笔记点赞计数更新 |
| analytics 服务 | SOCIAL_TOPIC | FAVORITE / UNFAVORITE | 笔记收藏计数更新 |
| content 服务（后续） | （待定） | （待定） | 评论数更新 |

### 10.2 调用方（通过 HTTP）

| 调用方 | API | 目的 |
|--------|-----|------|
| content 服务 | GET /api/counter/get 或 batch-get | 笔记详情页展示点赞/收藏/评论数 |
| home 服务（BFF） | POST /api/counter/batch-get | 首页 Feed 流展示计数 |
| user 服务 | GET /api/counter/get | 用户主页展示粉丝/关注数 |

调用方式推测为 Feign（Nacos 服务发现），对端模块的 `@FeignClient` 接口定义待确认。

---

## 11. 已知问题与改进

经过逐行验证，原有 8 个条目中有 2 个为误判（m6、m7），已删除。剩余 6 个真实问题分类如下。

### 11.1 已修复（本轮）

| # | 问题 | 修复内容 |
|:--:|------|------|
| m1 | increment/decrement/reconcile 无 `@RateLimit` | 三个端点均加 @RateLimit：increment/decrement 60s/500次（内部服务调用），reconcile 60s/2次（管理接口） |
| m2 | CounterRequest 字段无 `@NotNull` 等校验注解 | 加 @NotNull/@Min/@Max：targetType [1,2]、targetId 非空、countType [1,7] |
| m3 | MQ 重复消费无幂等保护 | 新增 Lua 原子脚本 `INCR_WITH_DEDUP_SCRIPT`：「msgId 去重检查 + SET + INCR/DECR」在同一 Redis 命令中完成，消除竞态窗口。Consumer 改用 `incrementWithDedup`/`decrementWithDedup`，重复消息静默跳过并 ACK。经 Python 4 场景验证（首次 INCR + 重复去重 + DECR + 零保护）全部通过 |

### 11.2 已知设计权衡（不修）

| # | 权衡 | 理由 |
|:--:|------|------|
| — | `scheduledFlush()` 用 `@Scheduled` 而非 XXL-Job | 5 秒高频不适合 XXL-Job 调度（调度开销 > 执行时间），且每个实例刷自己的 ConcurrentHashMap，无需分布式协调。不是问题 |

### 11.3 被删除的误判项

| # | 原始描述 | 删除原因 |
|:--:|------|------|
| m6 | multiGet 返回值 null 安全性 | 代码已有完整 null 防护：`redisValues == null → emptyList` + `i < redisValues.size()` 边界检查 + `redisValue != null ? parse : 0` 空值检查 |
| m7 | scheduledFlush 用 @Scheduled 缺少补偿 | §11.2 已分析这是正确设计，不是问题。

---

## 12. 模块文件清单

```
my-xhs-counter/src/main/java/com/myxhs/counter/
├── CounterApplication.java              # 启动类（@EnableScheduling）
├── controller/
│   └── CounterController.java           # 5 个 REST 端点
├── service/
│   └── CounterService.java              # 核心逻辑：increment/decrement/get/batch/reconcile
├── buffer/
│   └── CounterBuffer.java               # Buffer-Trigger 攒批刷盘（244 行）
├── consumer/
│   └── CounterEventConsumer.java        # MQ 消费：LIKE/UNLIKE/FAVORITE/UNFAVORITE
├── job/
│   └── CounterReconcileJob.java         # XXL-Job 定时对账（每天 3am）
├── entity/
│   └── Counter.java                     # 实体类（@TableName t_counter）
├── mapper/
│   ├── CounterMapper.java               # Mapper：batchUpsert/select/游标分页
│   └── CounterBatchQuery.java           # 批量查询参数 POJO
├── dto/
│   ├── CounterEvent.java                # MQ 事件消息体
│   ├── CounterFlushDTO.java             # Buffer 刷盘 DTO
│   ├── CounterRequest.java              # 增减/单个查询请求
│   └── CounterBatchRequest.java         # 批量查询请求
└── enums/
    ├── CountType.java                   # 计数类型枚举（7 种）
    └── TargetType.java                  # 目标类型枚举（笔记/用户）
```

---

## 关联文档

- `02-counter-test.md` — curl 测试用例与结果记录
- `03-buffer-trigger.md` — CounterBuffer 攒批设计深度分析
- `04-reconcile.md` — 对账修复算法详解
- `05-pipeline.md` — Redis Pipeline 批量查询优化
- `06-mq-consumer.md` — MQ 事件消费链路分析
- `07-data-consistency.md` — 三层一致性保障剖析
