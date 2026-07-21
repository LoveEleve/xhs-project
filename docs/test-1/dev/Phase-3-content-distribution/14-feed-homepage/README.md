# Feed流首页

> 所属服务：my-xhs-home (9015) | 开发阶段：Phase-3 | 状态：⏳ 待开发

---

## 🎯 一、需求分析

### 1.1 业务场景

Feed 流是社交平台的核心体验。my-xhs-home 是 BFF（Backend For Frontend）聚合层，不拥有数据库，通过 CompletableFuture 并行调用 6 个下游服务聚合数据。Feed 流采用推拉混合模式：普通用户发笔记走推模式（写扩散到粉丝收件箱），大V（粉丝 > 10 万）走拉模式（粉丝读取时实时拉取发件箱），兼顾写性能和读性能。

### 1.2 功能边界

| 功能项 | 是否实现 | 说明 |
|--------|---------|------|
| 关注 Feed 流 | ✅ | 推拉混合模式，关注的人的笔记 |
| 发现流 | ✅ | 推荐笔记（调用推荐服务） |
| 笔记详情聚合 | ✅ | 笔记 + 计数 + 社交状态 + 关联商品 |
| 商品详情聚合 | ✅ | 商品 + 库存 + 计数 + 关联笔记 |
| 用户主页聚合 | ✅ | 用户信息 + 计数 + 社交状态 |
| 推模式（写扩散） | ✅ | 普通用户发笔记 → 推送到粉丝收件箱 |
| 拉模式（读扩散） | ✅ | 大V发笔记 → 粉丝读取时实时拉取 |
| 大V阈值自动判定 | ✅ | 粉丝数 > 10 万切换为拉模式 |
| 游标分页 | ✅ | 基于时间戳游标，避免 offset 深分页 |
| 降级兜底 | ✅ | Counter→0, Social→未关注, Notification→0未读 |
| 本地缓存 | ✅ | Caffeine 5 秒缓存 Feed 结果 |

### 1.3 数据量预估

| 指标 | 预估值 | 计算依据 |
|------|--------|----------|
| Feed 流 QPS | 20000 | 首页是最高频页面 |
| 平均关注数 | 200 人/用户 | 社交平台平均值 |
| 大V占比 | 0.1% | 粉丝 > 10 万 |
| 聚合服务 RT | < 200ms | 并行调用 6 个服务 |

---

## 🏗️ 二、架构设计

### 2.1 整体架构图

```
Client → Gateway → my-xhs-home(9015) [BFF聚合层]
                        │
                        ├── Feign → my-xhs-content (笔记列表/详情)
                        ├── Feign → my-xhs-user (用户信息/批量)
                        ├── Feign → my-xhs-analytics (社交状态/关注/点赞)
                        ├── Feign → my-xhs-counter (批量计数)
                        ├── Feign → my-xhs-notification (未读数)
                        ├── Feign → my-xhs-search (推荐Feed)
                        ├── Feign → my-xhs-product (商品详情)
                        └── Feign → my-xhs-inventory (库存状态)
                        
                        ├── Redis: 收件箱/发件箱 ZSet
                        └── Caffeine: 本地缓存 5s
```

### 2.2 推拉混合 Feed 流流程

```
笔记发布流程（推模式 - 普通用户）：
┌────────┐   ┌─────────┐   ┌─────────┐   ┌──────────┐
│ 作者发布 │──▶│ Content │──▶│ RocketMQ│──▶│ Analytics│
│ 笔记    │   │ 入库    │   │ PUBLISH │   │ 推送粉丝 │
└────────┘   └─────────┘   └─────────┘   └────┬─────┘
                                                │
                    ZADD feed:inbox:{followerId} noteId timestamp
                    （遍历粉丝列表，写入每个粉丝的收件箱）

笔记发布流程（拉模式 - 大V）：
┌────────┐   ┌─────────┐   ┌─────────────────────────────┐
│ 大V发布 │──▶│ Content │──▶│ ZADD feed:outbox:{bigVId}   │
│ 笔记    │   │ 入库    │   │ （不推送，粉丝读取时拉取）    │
└────────┘   └─────────┘   └─────────────────────────────┘

读取 Feed 流：
┌────────┐   ┌──────────┐   ┌──────────────┐   ┌──────────┐
│ 用户刷新│──▶│ 拉取收件箱│──▶│ 拉取关注大V的 │──▶│ 合并+排序 │
│ 首页   │   │ ZRANGE   │   │ 发件箱ZRANGE  │   │ +分页    │
└────────┘   └──────────┘   └──────────────┘   └──────────┘
```

### 2.3 BFF 并行聚合流程

```
CompletableFuture 2层并行编排：

第1层（并行）：
├── getNotes(userId, feedType, cursor, size) → 笔记列表
├── getSocialStatus(userId, noteIds)         → 社交状态
└── getUnreadCount(userId)                   → 未读通知数

第2层（依赖第1层结果，并行）：
├── batchGetUsers(userIds)                   → 作者信息
└── batchGetCounters(bizType, noteIds)       → 计数数据

合并 → FeedVO 返回

总 RT = max(第1层RT) + max(第2层RT) ≈ 50ms + 30ms = 80ms
```

---

## 🗄️ 三、数据库设计

> my-xhs-home 为 BFF 聚合服务，**不拥有数据库**。Feed 数据来自 Redis 收件箱/发件箱 + 各服务 Feign API。

---

## 🔑 四、缓存设计

### 4.1 Redis Key 规范

| Key Pattern | 类型 | 过期时间 | 说明 |
|-------------|------|----------|------|
| `feed:inbox:{userId}` | ZSet | 7d | 用户收件箱（推模式写入），score=发布时间戳 |
| `feed:outbox:{userId}` | ZSet | 7d | 用户发件箱（拉模式读取大V），score=发布时间戳 |
| `home:feed:{userId}:{feedType}:{page}` | String | 5s | Feed 聚合缓存（Caffeine 本地缓存） |
| `home:note:detail:{noteId}` | String | 30s | 笔记详情聚合缓存 |
| `user:bigv:{userId}` | String | 1h | 大V标记（粉丝数 > 10 万） |

### 4.2 Caffeine 本地缓存配置

```java
@Bean
public Cache<String, FeedVO> feedCache() {
    return Caffeine.newBuilder()
        .maximumSize(10000)              // 最多缓存 1 万个 Feed 页
        .expireAfterWrite(5, TimeUnit.SECONDS) // 5 秒过期
        .recordStats()
        .build();
}
```

---

## 📡 五、接口设计

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| GET | `/api/home/feed` | 关注 Feed 流（关注的人的笔记） | ✅ |
| GET | `/api/home/discover` | 发现流（推荐笔记） | ✅ |
| GET | `/api/home/note/{id}` | 笔记详情聚合 | ❌ |
| GET | `/api/home/product/{id}` | 商品详情聚合 | ❌ |
| GET | `/api/home/user/{id}` | 用户主页聚合 | ❌ |

### 5.1 关注 Feed 流请求/响应

```json
// GET /api/home/feed?lastScore=1716000000000&size=20
{
  "code": 200,
  "data": {
    "notes": [
      {
        "noteId": 123456,
        "title": "今日穿搭分享",
        "coverImage": "https://...",
        "author": { "userId": 789, "nickname": "小红", "avatar": "..." },
        "counters": { "likeCount": 1234, "collectCount": 567, "commentCount": 89 },
        "social": { "isFollowed": true, "isLiked": false },
        "createdAt": "2024-05-18T10:30:00"
      }
    ],
    "nextCursor": "1716000000001",
    "hasMore": true,
    "unreadCount": 5
  }
}
```

---

## 💻 六、核心代码实现

### 6.1 推拉混合 Feed 读取

```java
/**
 * 推拉混合 Feed 流读取
 * 1. 从收件箱拉取推模式的笔记（普通用户推送的）
 * 2. 从关注的大V发件箱拉取拉模式的笔记
 * 3. 合并排序 + 游标分页
 */
public List<Long> getFeedNoteIds(Long userId, Double lastScore, int size) {
    String inboxKey = "feed:inbox:" + userId;

    // 1. 从收件箱拉取（推模式笔记）
    Set<ZSetOperations.TypedTuple<String>> inboxNotes = redisTemplate.opsForZSet()
        .reverseRangeByScoreWithScores(inboxKey, 0, lastScore, 0, size);

    // 2. 获取用户关注的大V列表
    List<Long> bigVIds = getBigVFollowings(userId);

    // 3. 从大V发件箱拉取（拉模式笔记）
    List<ZSetOperations.TypedTuple<String>> bigVNotes = new ArrayList<>();
    for (Long bigVId : bigVIds) {
        String outboxKey = "feed:outbox:" + bigVId;
        Set<ZSetOperations.TypedTuple<String>> notes = redisTemplate.opsForZSet()
            .reverseRangeByScoreWithScores(outboxKey, 0, lastScore, 0, size / bigVIds.size());
        bigVNotes.addAll(notes);
    }

    // 4. 合并 + 按时间戳降序排序 + 取 Top size
    return Stream.concat(inboxNotes.stream(), bigVNotes.stream())
        .sorted(Comparator.comparingDouble(t -> -t.getScore()))
        .limit(size)
        .map(t -> Long.valueOf(t.getValue()))
        .collect(Collectors.toList());
}
```

### 6.2 BFF 并行聚合

```java
/**
 * CompletableFuture 并行聚合 6 个服务
 * 独立线程池，不共享 ForkJoinPool.commonPool()
 */
public FeedVO aggregateFeed(Long userId, List<Long> noteIds) {
    // 第1层并行
    CompletableFuture<List<NoteDTO>> notesFuture = CompletableFuture
        .supplyAsync(() -> noteFeignClient.batchGetNotes(noteIds), aggregatorPool);
    CompletableFuture<Map<Long, SocialStatusDTO>> socialFuture = CompletableFuture
        .supplyAsync(() -> socialFeignClient.getSocialStatus(userId, noteIds), aggregatorPool);
    CompletableFuture<Integer> unreadFuture = CompletableFuture
        .supplyAsync(() -> notificationFeignClient.getUnreadCount(userId), aggregatorPool);

    // 等待第1层完成
    CompletableFuture.allOf(notesFuture, socialFuture, unreadFuture).join();

    List<NoteDTO> notes = notesFuture.join();
    List<Long> userIds = notes.stream().map(NoteDTO::getUserId).distinct().collect(Collectors.toList());

    // 第2层并行（依赖第1层结果）
    CompletableFuture<Map<Long, UserDTO>> usersFuture = CompletableFuture
        .supplyAsync(() -> userFeignClient.batchGetUsers(userIds), aggregatorPool);
    CompletableFuture<Map<Long, CounterDTO>> countersFuture = CompletableFuture
        .supplyAsync(() -> counterFeignClient.batchGetCounters("NOTE", noteIds), aggregatorPool);

    CompletableFuture.allOf(usersFuture, countersFuture).join();

    // 组装 FeedVO
    return buildFeedVO(notes, socialFuture.join(), unreadFuture.join(),
                       usersFuture.join(), countersFuture.join());
}
```

### 6.3 降级兜底（FallbackFactory）

```java
/**
 * 通用降级工厂：任何下游服务不可用时返回默认值
 * Counter → 0计数, Social → 未关注, Notification → 0未读
 */
@Component
public class CounterFeignFallback implements CounterFeignClient {
    @Override
    public Map<Long, CounterDTO> batchGetCounters(String bizType, List<Long> bizIds) {
        // 降级：所有计数返回 0
        return bizIds.stream().collect(Collectors.toMap(
            id -> id, id -> CounterDTO.zero()));
    }
}
```

---

## ⚖️ 七、方案对比

### 7.1 Feed 流模型：推模式 vs 拉模式 vs 推拉混合

| 维度 | 推模式（写扩散） | 拉模式（读扩散） | 推拉混合（✅ 选定） |
|------|----------------|----------------|-------------------|
| 写性能 | 差（大V百万粉丝写百万次） | 好（只写发件箱） | 好（大V走拉模式） |
| 读性能 | 好（直接读收件箱） | 差（合并多个发件箱） | 好（收件箱 + 少量大V发件箱） |
| 存储 | 大（每个粉丝一份） | 小（只存发件箱） | 中 |
| 实时性 | 高（推送即可见） | 高（实时拉取） | 高 |
| 复杂度 | 低 | 中 | 高 |

**选择理由**：纯推模式大V发笔记写扩散百万次不可接受；纯拉模式关注 200 人需合并 200 个发件箱太慢。推拉混合：普通用户推（99.9%），大V拉（0.1%），兼顾性能。

### 7.2 分页方案：游标分页 vs offset 分页

| 维度 | 游标分页（✅ 选定） | offset 分页 |
|------|-------------------|------------|
| 深分页性能 | O(logN) | O(N) 越深越慢 |
| 数据一致性 | 不会漏/重复 | 新数据插入导致重复 |
| 实现复杂度 | 中（需维护游标） | 低 |

---

## 🐛 八、踩坑记录

### 8.1 ForkJoinPool.commonPool() 阻塞

- **现象**：CompletableFuture 默认使用 commonPool，下游服务超时时阻塞其他业务
- **解决**：创建独立 ThreadPoolExecutor（核心 20/最大 50/队列 200），所有聚合任务用独立线程池
- **教训**：BFF 聚合层必须用独立线程池，不能共享 commonPool

### 8.2 大V判定缓存不一致

- **现象**：用户粉丝数突破 10 万后，仍走推模式导致写扩散
- **解决**：`user:bigv:{userId}` 缓存 1 小时 + 粉丝数变更时主动刷新
- **教训**：大V阈值判定需要实时性，不能只依赖定时任务

### 8.3 收件箱 ZSet 无限膨胀

- **现象**：活跃用户收件箱 ZSet 达到数万条，内存暴涨
- **解决**：收件箱只保留最近 7 天数据，ZREMRANGEBYSCORE 定时清理
- **教训**：ZSet 必须设置容量上限或 TTL

---

## 📊 九、测试验证

| 测试场景 | 输入 | 预期结果 | 通过 |
|----------|------|----------|------|
| 关注 Feed 流 | 已关注用户 | 返回关注人的笔记列表 | ⬜ |
| 大V笔记拉取 | 关注了大V | 从大V发件箱实时拉取 | ⬜ |
| 游标分页 | lastScore + size | 不重复不遗漏 | ⬜ |
| 降级兜底 | Counter 服务不可用 | 计数返回 0，不影响主流程 | ⬜ |
| 并行聚合 | 正常请求 | RT < 200ms | ⬜ |
| 本地缓存 | 5 秒内重复请求 | 直接返回缓存 | ⬜ |

---

## 🎤 十、面试考察点

### Q1: Feed 流的推模式和拉模式分别是什么？各有什么问题？

> 1. "推模式（写扩散）：用户发笔记时，推送到所有粉丝的收件箱。优点：读极快（直接读收件箱）。缺点：大V百万粉丝写百万次，写放大严重"
> 2. "拉模式（读扩散）：用户读 Feed 时，实时拉取关注人的发件箱合并。优点：写极快。缺点：关注 200 人需合并 200 个发件箱，读放大"
> 3. "我们用推拉混合：普通用户推模式（99.9%），大V（粉丝 > 10 万）拉模式（0.1%），兼顾读写性能"

### Q2: Feed 流分页为什么不能用 offset？

> 1. "Feed 流是实时更新的，新笔记不断插入"
> 2. "offset 分页：第 1 页看完后新插入 1 条，第 2 页会重复看到第 1 页最后一条"
> 3. "游标分页：基于时间戳游标（lastScore），新数据不影响已翻页的结果"
> 4. "ZSet ZREVRANGEBYSCORE 天然支持游标分页，性能 O(logN)"

### Q3: BFF 聚合层怎么保证性能？

> 1. "CompletableFuture 2 层并行：第 1 层并行调 3 个服务，第 2 层并行调 2 个服务"
> 2. "总 RT = max(第 1 层) + max(第 2 层) ≈ 80ms，而非串行 5×50ms=250ms"
> 3. "独立线程池（核心 20/最大 50），不共享 ForkJoinPool"
> 4. "降级兜底：任何服务不可用返回默认值，不影响主流程"

---

## 📚 参考资料

| 资料 | 相关章节 | 参考内容 |
|------|----------|----------|
| 📄 phase-3/README.md | §3.14 | Feed流首页完整设计（推拉混合/BFF聚合/降级） |
| 📄 02-module-detailed-design.md | §5 | Feed流推拉模型/大V处理 |
| 📖 《亿级流量系统架构设计与实战》 | 第4章 | 微信朋友圈Feed流方案 |
