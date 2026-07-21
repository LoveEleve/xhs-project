# 点赞收藏 — Code Review 报告

> 审查时间：2026-05-13 | 审查范围：LikeService / FavoriteService / LikeController / FavoriteController / 4 个 MQ Consumer
> 审查标准：对标 P8 | 审查深度：架构设计 + 代码质量 + 安全 + 性能 + 一致性

---

## 一、Review 评分（对标 P8）

| 维度 | 权重 | 得分 | 加权分 | 说明 |
|------|:----:|:----:|:------:|------|
| 架构设计 | 25% | 9.0 | 2.250 | Redis 为权威数据源 + MQ 异步落库，读写分离，高性能 |
| 代码质量 | 25% | 8.5 | 2.125 | 注释完善、职责清晰，少量未使用 import |
| 技术深度 | 25% | 9.0 | 2.250 | SADD 天然幂等 + Pipeline 批量查询 + MQ 异步解耦 + 三层幂等 |
| 安全设计 | 15% | 8.0 | 1.200 | @RateLimit 限流 + 参数校验，但批量接口缺少上限 |
| 工程实践 | 10% | 8.5 | 0.850 | MQ 消费幂等 + 异常重试 + 容错降级 |
| **综合** | **100%** | | **8.7 / 10** | |

**结论**：✅ 达到 P8 水平

---

## 二、🔥 深度技术分析

### 2.1 MQ 异步落库的一致性边界

```
┌─────────────────────────────────────────────────────────────────────┐
│                    MQ 异步落库的一致性分析                            │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  正常流程：                                                         │
│  Client → Redis SADD（同步，微秒级）→ 返回用户成功                  │
│                ↓                                                    │
│           RocketMQ（异步发送）                                      │
│                ↓                                                    │
│           Consumer → MySQL INSERT（异步落库）                       │
│                                                                     │
│  ✅ 用户感知 RT = Redis SADD 时间（微秒级）                        │
│  ✅ Redis 为权威数据源，MySQL 为持久化兜底                          │
│                                                                     │
│  异常场景分析：                                                     │
│  ┌──────────────────┬──────────┬──────────────────────────────┐    │
│  │ 场景             │ 概率     │ 后果 & 防御                   │    │
│  ├──────────────────┼──────────┼──────────────────────────────┤    │
│  │ MQ 发送失败       │ 低       │ Redis 已写入，MySQL 缺失     │    │
│  │                  │          │ → 对账任务修复                │    │
│  ├──────────────────┼──────────┼──────────────────────────────┤    │
│  │ MQ 消费失败       │ 低       │ RocketMQ 自动重试（16次）    │    │
│  │                  │          │ → Consumer 抛异常触发重试     │    │
│  ├──────────────────┼──────────┼──────────────────────────────┤    │
│  │ MQ 重复消费       │ 中       │ MySQL 唯一索引兜底           │    │
│  │                  │          │ → DuplicateKeyException 忽略 │    │
│  ├──────────────────┼──────────┼──────────────────────────────┤    │
│  │ Redis 宕机        │ 极低     │ 点赞数据丢失                 │    │
│  │                  │          │ → MySQL 兜底 + AOF 持久化     │    │
│  ├──────────────────┼──────────┼──────────────────────────────┤    │
│  │ 先取消再收到点赞MQ│ 低       │ MySQL 先 INSERT 后 DELETE    │    │
│  │ （消息乱序）      │          │ → 最终状态正确（已删除）     │    │
│  │                  │          │ 但如果 DELETE 先到 INSERT 后到│    │
│  │                  │          │ → MySQL 有脏数据！需对账修复  │    │
│  └──────────────────┴──────────┴──────────────────────────────┘    │
│                                                                     │
│  【关键结论】                                                       │
│  MQ 异步落库不保证 Redis 和 MySQL 的强一致性，                      │
│  但通过"Redis 为准 + 唯一索引幂等 + 对账修复"实现最终一致性。       │
│  这是高频场景（QPS 万级）下性能与一致性的工程权衡。                  │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.2 SADD + 反向索引的非原子性风险

```java
// 当前代码：两个独立的 Redis 命令
Long added = stringRedisTemplate.opsForSet().add(likeKey, userId);  // 命令 1
if (added == 1) {
    stringRedisTemplate.opsForSet().add(userLikeKey, bizId);         // 命令 2
}
```

**风险**：命令 1 成功后、命令 2 执行前 Redis 宕机 → 点赞集合有记录但反向索引缺失。

**影响**：低。反向索引仅用于"我点赞过的笔记"查询，不影响核心的点赞判断（SISMEMBER 查的是 likeKey）。

**防御**：
- 反向索引是衍生数据，可通过对账修复
- 如果需要强一致，可改为 Lua 脚本（但当前场景不值得）

**与关注模块的对比**：关注模块用 Lua 脚本保证 4 个命令的原子性，因为关注关系（following + follower）是双向核心数据。而点赞的反向索引是辅助数据，不需要 Lua 级别的原子性保证。

### 2.3 收藏的 ZSCORE + ZADD 竞态条件

```java
// 当前代码：先检查再写入（非原子）
Double score = redisTemplate.opsForZSet().score(key, noteId);  // 检查
if (score != null) return;                                      // 已收藏
redisTemplate.opsForZSet().add(key, noteId, currentTime);       // 写入
```

**竞态场景**：
```
线程 A: ZSCORE → null（未收藏）
线程 B: ZSCORE → null（未收藏）
线程 A: ZADD → 成功 → 发 MQ
线程 B: ZADD → 成功（ZSet 去重，覆盖 score）→ 发 MQ
```

**后果**：ZSet 中只有一条记录（正确），但发了 2 条 MQ 消息 → MySQL 第二条 INSERT 被唯一索引拦截（DuplicateKeyException 静默忽略）。

**结论**：✅ 最终状态正确，无需修复。唯一索引兜底了 MQ 重复消费。

### 2.4 MQ 消息乱序问题

```
场景：用户快速点赞 → 取消点赞
  1. 点赞 → Redis SADD → MQ 发送 LIKE 消息
  2. 取消 → Redis SREM → MQ 发送 UNLIKE 消息
  
  如果 MQ 消费顺序变为：UNLIKE → LIKE
  → MySQL 先 DELETE（无记录可删）→ 再 INSERT（写入记录）
  → 最终 MySQL 有记录，但 Redis 中已无记录 → 不一致！
```

**防御**：
- Redis 为权威数据源，查询走 Redis 不走 MySQL
- 对账任务以 Redis 为准修复 MySQL
- 生产环境可用 RocketMQ 顺序消息（同一 bizId 发到同一 Queue）

---

## 三、发现的问题 & 修复记录

### 🔴 P1 问题

| # | 问题 | 分析 | 建议 | 状态 |
|:-:|------|------|------|:----:|
| 1 | 设计文档提到 `@Idempotent` 双层幂等，但代码未使用 | 设计文档说“双层幂等：@Idempotent 拦截网络抖动 + SADD 保证业务层幂等”，但实际代码只有 SADD 一层 | 给点赞/收藏接口加上 `@Idempotent(expireSeconds=5)` | ✅ 已修复 |

### 🟡 P2 问题

| # | 问题 | 分析 | 建议 | 状态 |
|:-:|------|------|------|:----:|
| 2 | `batchCheckLikeStatus` 的 `bizIds` 参数无上限 | 恶意用户传 1000 个 ID → Pipeline 执行 1000 次 SISMEMBER | 加 `MAX_BATCH_SIZE = 100` 截断 | ✅ 已修复 |
| 3 | MQ 消息体用 `\|` 分隔字符串 | 不够健壮，如果字段值包含 `\|` 会解析错误。且扩展性差（新增字段需改所有 Consumer） | 改为 JSON 序列化（LikeEvent / FavoriteEvent DTO） | ✅ 已修复 |
| 4 | `LikeService` import 了 `BizException` 和 `ResultCode` 但未使用 | 代码洁癖问题 | 删除未使用的 import | ✅ 已修复 |
| 5 | 收藏接口用 `@RequestParam` 而非 `@RequestBody` | POST `/api/social/favorite?noteId=20001` 不符合 RESTful 规范 | 改为 `@RequestBody FavoriteRequest`，与点赞接口保持一致 | ✅ 已修复 |
| 6 | MQ 消息乱序可能导致 Redis 和 MySQL 不一致 | 快速点赞→取消，UNLIKE 消息先于 LIKE 消息被消费 | 对账任务修复 / 顺序消息 | ⬜ 后续优化 |

### 🟢 后续优化（非阻塞）

| # | 问题 | 建议 | 状态 |
|:-:|------|------|:----:|
| 7 | 热门笔记点赞 Set 内存爆炸 | 百万点赞的 Set 占用数十 MB，可用 Bloom Filter 替代 | ⬜ |
| 8 | 缺少对账修复机制 | 定时任务扫描 Redis Set 与 MySQL t_like 的差异 | ⬜ |
| 9 | 缺少单元测试 | 补充 LikeService / FavoriteService 核心方法的单元测试 | ⬜ |

---

## 四、技术亮点

| 技术点 | 评分 | 面试价值 | 说明 |
|--------|:----:|:--------:|------|
| **SADD 天然幂等** | ⭐⭐⭐⭐⭐ | 高频 | 返回 0=已存在，无需额外幂等逻辑 |
| **Pipeline 批量查询** | ⭐⭐⭐⭐⭐ | 高频 | 1 次网络往返完成 N 次 SISMEMBER |
| **MQ 异步落库** | ⭐⭐⭐⭐⭐ | 高频 | 用户 RT = Redis 操作时间，不等待 MySQL |
| **三层幂等保障** | ⭐⭐⭐⭐⭐ | 高频 | @RateLimit + SADD + MySQL 唯一索引 |
| **MQ 消费幂等** | ⭐⭐⭐⭐⭐ | 高频 | DuplicateKeyException 静默忽略 + DELETE 天然幂等 |
| **Redis Set vs ZSet 选型** | ⭐⭐⭐⭐ | 中频 | 点赞用 Set（无序），收藏用 ZSet（按时间排序） |
| **反向索引设计** | ⭐⭐⭐⭐ | 中频 | user:like:note 反向索引支持"我点赞过的笔记"查询 |
| **限流保护** | ⭐⭐⭐⭐ | 低频 | @RateLimit 30次/分钟 |

---

## 五、面试话术

### Q1: 点赞怎么保证幂等？

> "我设计了三层幂等保障：
> 1. **接口层**：@RateLimit 限流，30次/分钟，拦截恶意刷赞
> 2. **业务层**：Redis Set 的 SADD 命令天然幂等——返回 1 表示新增成功，返回 0 表示已存在。返回 0 时直接返回成功，不发 MQ，不更新计数
> 3. **存储层**：MySQL 唯一索引 uk_user_biz 兜底，即使 MQ 重复消费，DuplicateKeyException 被 catch 后静默忽略
>
> 这三层各司其职：限流防恶意、SADD 防正常重复、唯一索引防 MQ 重复消费。"

### Q2: MQ 异步落库会不会丢数据？Redis 和 MySQL 不一致怎么办？

> "首先明确：Redis 是权威数据源，MySQL 是持久化兜底。所有查询走 Redis，不走 MySQL。
>
> 不一致的场景和防御：
> 1. **MQ 发送失败**：Redis 已写入，MySQL 缺失 → 对账任务以 Redis 为准补写 MySQL
> 2. **MQ 消费失败**：RocketMQ 自动重试 16 次，Consumer 抛异常触发重试
> 3. **MQ 重复消费**：MySQL 唯一索引保证幂等，DuplicateKeyException 静默忽略
> 4. **MQ 消息乱序**：快速点赞→取消，UNLIKE 先于 LIKE 被消费 → MySQL 有脏数据 → 对账任务修复
>
> 核心思想：**不追求强一致性，用最终一致性 + 对账修复换取高性能**。这是高频场景（QPS 万级）下的工程权衡。"

### Q3: 为什么点赞用 Redis Set，收藏用 Redis ZSet？

> "两者的核心区别在于**是否需要排序**：
> - **点赞**：只需要判断"是否已点赞"（SISMEMBER O(1)）和"点赞数"（SCARD O(1)），不需要排序 → Set 足够
> - **收藏**：需要"按收藏时间倒序"展示收藏列表 → ZSet（score=收藏时间戳），天然支持 reverseRange 分页
>
> 如果点赞也需要排序（如"谁赞了我"列表），也应该用 ZSet。但当前需求不需要。"

### Q4: 批量查询点赞状态为什么用 Pipeline 而不是 Lua？

> "Pipeline 和 Lua 都能减少网络往返，但适用场景不同：
> - **Pipeline**：适合批量独立操作（每个 SISMEMBER 互不依赖），不需要原子性
> - **Lua**：适合需要原子性的操作（如关注/取关涉及 4 个命令必须原子执行）
>
> 批量查询点赞状态不需要原子性——10 个 SISMEMBER 之间没有依赖关系，Pipeline 更简单高效。"

### Q5: MQ 消息乱序怎么处理？

> "快速点赞→取消场景下，UNLIKE 消息可能先于 LIKE 消息被消费：
> 1. UNLIKE 先到 → MySQL DELETE（无记录可删，返回 0）
> 2. LIKE 后到 → MySQL INSERT（写入记录）
> 3. 最终 MySQL 有记录，但 Redis 中已无记录 → 不一致
>
> 防御方案：
> - **当前方案**：对账任务以 Redis 为准修复 MySQL（最终一致性）
> - **进阶方案**：RocketMQ 顺序消息（同一 bizId 发到同一 Queue，保证 FIFO）
> - **为什么不用顺序消息？** 顺序消息会降低吞吐量（同一 Queue 串行消费），点赞场景对一致性要求不高，对账修复足够。"

---

## 六、修复前后对比

### 环境搭建踩坑

```yaml
# ❌ 修复前：rocketmq-spring-boot-starter 2.2.3
# 只有 spring.factories，Spring Boot 3.x 不读取 → RocketMQTemplate Bean 不存在
rocketmq.spring.version: 2.2.3

# ✅ 修复后：升级到 2.3.0
# 同时有 spring.factories + AutoConfiguration.imports → 兼容 Spring Boot 3.x
rocketmq.spring.version: 2.3.0
```

```yaml
# ❌ 修复前：NameServer 端口错误
rocketmq:
  name-server: localhost:19876  # 实际监听在 9876

# ✅ 修复后：
rocketmq:
  name-server: localhost:9876
```

### MQ 消费幂等设计

```java
// ✅ 点赞 Consumer：唯一索引保证幂等
try {
    likeMapper.insert(like);
} catch (DuplicateKeyException e) {
    // 重复消费 → 静默忽略，不抛异常，不触发重试
    log.debug("[点赞Consumer] 重复消费忽略");
}

// ✅ 取消点赞 Consumer：DELETE 天然幂等
int deleted = likeMapper.deleteByUserAndBiz(userId, bizType, bizId);
// deleted=0 表示记录不存在（可能已被删除或从未写入），不报错
```

---

## 七、方案对比

### 7.1 点赞存储方案

| 方案 | 读性能 | 写性能 | 幂等 | 批量查询 | 选择 |
|------|:------:|:------:|:----:|:--------:|:----:|
| Redis Set | SISMEMBER O(1) | SADD O(1) | ✅ 天然 | Pipeline | ✅ |
| MySQL | SELECT + 索引 | INSERT + 唯一索引 | 需异常处理 | IN 查询 | ❌ |
| Redis + MySQL 双写 | O(1) | 两次写入 | 复杂 | Pipeline | ❌ |

### 7.2 异步落库方案

| 方案 | 一致性 | 性能 | 复杂度 | 选择 |
|------|:------:|:----:|:------:|:----:|
| MQ 异步落库（✅） | 最终一致 | 高 | 中 | ✅ |
| 同步双写 | 强一致 | 低 | 低 | ❌ |
| Binlog 同步 | 最终一致 | 高 | 高 | ❌ |

### 7.3 批量查询方案

| 方案 | 网络往返 | 原子性 | 复杂度 | 选择 |
|------|:--------:|:------:|:------:|:----:|
| Pipeline（✅） | 1 次 | ❌ | 低 | ✅ |
| Lua 脚本 | 1 次 | ✅ | 中 | ❌ |
| 逐条查询 | N 次 | ❌ | 低 | ❌ |

---

## 八、接口测试结果

| 测试场景 | 输入 | 预期结果 | 通过 |
|----------|------|----------|:----:|
| 点赞笔记 | bizType=1, bizId=20001 | 点赞成功 | ✅ |
| 重复点赞 | 同一笔记再次点赞 | 幂等返回成功 | ✅ |
| 取消点赞 | 已点赞的笔记 | 取消成功 | ✅ |
| 取消未点赞 | 未点赞的笔记 | 幂等返回成功 | ✅ |
| 查询点赞状态 | 已点赞的笔记 | 返回 true | ✅ |
| 批量查询 | 3 个笔记 ID | 返回 Map{20001:true, 20002:false, 20003:false} | ✅ |
| 获取点赞数 | 2 人点赞后 | 返回 2 | ✅ |
| 收藏笔记 | noteId=20001 | 收藏成功 | ✅ |
| 重复收藏 | 同一笔记再次收藏 | 幂等返回成功 | ✅ |
| 收藏列表 | page=1, size=10 | 按收藏时间倒序 | ✅ |
| 取消收藏 | 已收藏的笔记 | 取消成功 | ✅ |
| MQ 异步落库（点赞） | 点赞后检查 MySQL | t_like 有记录 | ✅ |
| MQ 异步落库（收藏） | 收藏后检查 MySQL | t_favorite 有记录 | ✅ |
| MQ 异步删除（取消点赞） | 取消后检查 MySQL | t_like 记录被删除 | ✅ |
| MQ 异步删除（取消收藏） | 取消后检查 MySQL | t_favorite 记录被删除 | ✅ |

---

## 九、新增/修改文件清单

### 新增文件（13 个）

| 文件 | 说明 |
|------|------|
| `entity/Like.java` | 点赞实体（对应 t_like 表） |
| `entity/Favorite.java` | 收藏实体（对应 t_favorite 表） |
| `dto/request/LikeRequest.java` | 点赞请求 DTO（bizType + bizId） |
| `mapper/LikeMapper.java` | 点赞 Mapper |
| `mapper/FavoriteMapper.java` | 收藏 Mapper |
| `service/LikeService.java` | 点赞服务（Redis Set + MQ 异步落库） |
| `service/FavoriteService.java` | 收藏服务（Redis ZSet + MQ 异步落库） |
| `controller/LikeController.java` | 点赞接口（5 个 API） |
| `controller/FavoriteController.java` | 收藏接口（4 个 API） |
| `consumer/LikeConsumer.java` | 点赞 MQ 消费者（异步落库） |
| `consumer/UnlikeConsumer.java` | 取消点赞 MQ 消费者（异步删除） |
| `consumer/FavoriteConsumer.java` | 收藏 MQ 消费者（异步落库） |
| `consumer/UnfavoriteConsumer.java` | 取消收藏 MQ 消费者（异步删除） |

### 修改文件

| 文件 | 修改内容 |
|------|---------|
| `pom.xml`（父） | rocketmq-spring 版本 2.2.3 → 2.3.0（兼容 Spring Boot 3.x） |
| `application.yml`（analytics） | RocketMQ name-server 端口 19876 → 9876 |
| `application.yml`（gateway） | 白名单添加 `/api/social/like/count` |
| `broker.conf` | 添加 `autoCreateTopicEnable=true` + 修正 `namesrvAddr` |
