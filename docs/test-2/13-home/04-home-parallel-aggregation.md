# Home 2 层并行聚合 + 动态超时 — 深度技术分析

> 关联源码：`NoteAggService.java` / `FeedService.java` / `AggregatorThreadPoolConfig.java` / `HomeController.java`

---

## 业务背景

BFF 层的核心价值：把 N 个下游服务的响应聚合为一个前端友好的 VO。

```
笔记详情页需要：
  ① 笔记详情（content 服务）
  ② 点赞状态（analytics 服务）
  ③ 收藏状态（analytics 服务）
  ④ 点赞/收藏/评论计数（counter 服务）
  ⑤ 作者信息（user 服务）
  ⑥ 关注关系（analytics 服务）
  ⑦ 热门评论（content 服务）

串行调用：7 × 50ms = 350ms ❌（估算，单次调用约 50ms）
并行调用：50ms + 编排开销 = 60ms ✅（估算）
```

---

## 2 层并行结构

### 为什么分 2 层

第 1 层和第 2 层存在**依赖关系**：

```
第 1 层（3s 超时）：
  笔记详情 → 拿到 authorId
  点赞状态 / 收藏状态 / 计数

第 2 层（依赖第 1 层结果）：
  作者信息（需要 authorId）
  关注关系（需要 authorId）
  热门评论（需要 noteId，无依赖但归入第 2 层均衡负载）
```

第 2 层必须等第 1 层的笔记详情返回（拿到 authorId）才能发起。

### 代码结构（NoteAggService）

```java
// ===== 第 1 层：无依赖并行 =====
CompletableFuture<R<Map>> noteFuture = supplyAsync(getNoteDetail, aggregatorPool);
CompletableFuture<Boolean> likeFuture = supplyAsync(checkLikeStatus, aggregatorPool);
CompletableFuture<Boolean> collectFuture = supplyAsync(checkFavorite, aggregatorPool);
CompletableFuture<Map> counterFuture = supplyAsync(getCounters, aggregatorPool);

CompletableFuture.allOf(noteFuture, likeFuture, collectFuture, counterFuture)
        .get(3, TimeUnit.SECONDS);

// 提取 authorId（第 2 层的前置条件）
Long authorId = noteData.get("userId");

// ===== 第 2 层：依赖第 1 层结果 =====
CompletableFuture<Map> authorFuture = supplyAsync(getAuthor, aggregatorPool);
CompletableFuture<Map> relationFuture = supplyAsync(checkRelation, aggregatorPool);
CompletableFuture<List> commentsFuture = supplyAsync(getHotComments, aggregatorPool);

CompletableFuture.allOf(authorFuture, relationFuture, commentsFuture)
        .get(layer2Timeout, TimeUnit.MILLISECONDS);
```

---

## 动态超时

### 为什么需要动态超时

固定超时的问题：第 1 层已经花了 3.5 秒，第 2 层如果还是固定 3 秒，总耗时 6.5 秒——前端早就超时了。

动态超时：第 2 层的超时 = 全局预算 - 第 1 层已用时间。

```java
long startTime = System.nanoTime();
long globalTimeoutMs = 4000;

// 第 1 层（3s）
CompletableFuture.allOf(l1).get(3, TimeUnit.SECONDS);

// 第 2 层（动态）
long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
long layer2TimeoutMs = Math.max(500, globalTimeoutMs - elapsedMs);  // 至少 500ms
CompletableFuture.allOf(l2).get(layer2TimeoutMs, TimeUnit.MILLISECONDS);
```

**保证**：
- 总耗时 ≤ 4 秒（全局预算）
- 第 2 层至少 500ms（防止极端情况下第 2 层超时为负）

---

## 线程池隔离

### 为什么需要两个线程池

```java
@Bean("aggregatorPool")
ExecutorService aggregatorPool;   // core=20, max=50, queue=200

@Bean("batchFeignPool")
ExecutorService batchFeignPool;   // core=30, max=80, queue=500
```

**嵌套 CompletableFuture 死锁风险**：

```
❌ 单线程池：
  20 个外层任务占满线程池（等内层 Feign 结果）
  内层任务无线程可用 → 永远等不到 → 死锁

✅ 双线程池：
  外层用 aggregatorPool（20-50 线程）
  内层用 batchFeignPool（30-80 线程）
  两个池独立，内层总有线程可用
```

### 调用路径

```
Tomcat 线程（max=200）
    ↓ HomeController 返回 CompletableFuture
aggregatorPool（编排层）
    ├─ NoteAggService.getNoteDetail()
    ├─ FeedService.getFollowFeed()
    └─ 提交子任务到 batchFeignPool
batchFeignPool（Feign 调用层）
    ├─ contentFeign.getNoteDetail()
    ├─ analyticsFeign.checkLikeStatus()
    └─ ...
```

---

## MDC 透传

线程池的 TaskDecorator 包装：

```java
// AggregatorThreadPoolConfig.MdcAwareExecutorService
public void execute(Runnable command) {
    Map<String, String> ctxMap = MDC.getCopyOfContextMap();
    TraceContext snapshot = TraceContextHolder.snapshot();
    delegate.execute(() -> {
        if (snapshot != null) TraceContextHolder.set(snapshot);
        if (ctxMap != null) MDC.setContextMap(ctxMap);
        try {
            command.run();
        } finally {
            TraceContextHolder.clear();
            MDC.clear();
        }
    });
}
```

聚合接口的日志（含 traceId）跨线程保持一致。

---

## 降级策略

| 场景 | 降级行为 |
|---|---|
| 第 1 层超时 | `getNow(默认值)` 返回降级数据，继续第 2 层 |
| 第 2 层超时 | 作者/关注/评论返回 null/false/空列表 |
| Feign 异常 | try-catch + FallbackFactory 返回空数据 |
| 笔记不存在 | 返回 null → Controller 返回 404 |

```java
Map<Long, Map> notesMap = notesFuture.getNow(Collections.emptyMap());
Map<Long, Boolean> likesMap = likesFuture.getNow(Collections.emptyMap());
int unreadCount = unreadFuture.getNow(0);
```

---

## 面试 Q&A

**Q: 为什么不用虚拟线程/Project Loom？**
A: Java 21 的虚拟线程可以简化线程池管理（每个请求一个虚拟线程，阻塞不占平台线程）。但当前项目用 Java 17 + 双线程池方案，兼容性和稳定性优先。

**Q: 全局超时 4 秒怎么定的？**
A: 前端接口超时一般是 5 秒（用户体验可接受范围）。BFF 预留 1 秒给 Gateway 转发和网络开销，自身预算 4 秒。

**Q: 第 2 层超时为什么至少 500ms？**
A: 如果第 1 层花了 3.9 秒，第 2 层动态超时 = max(500, 4000-3900) = 500ms。500ms 是 Feign 调用单次往返的合理时间，保证第 2 层至少有 1 次尝试机会。

---

## 生产实验

### 聚合接口实测（2026-07-30）

| 接口 | 结果 | 耗时 |
|---|---|---|
| GET /api/home/feed?size=3 | 200，返回笔记列表 | rt=9ms（ACCESS 日志） |
| GET /api/home/note/{noteId} | 200，聚合数据完整 | 正常 |
| GET /api/home/user/{uid} | 200，用户+社交状态 | 正常 |
| GET /api/home/cart | 200，购物车+金额 | 正常 |

### 降级验证

停掉 analytics 服务后请求 Feed：

```
结果：HTTP 200（非 500）
isLiked=false（Feign 异常被 try-catch 捕获，返回降级值）
```

验证了 L1 层 Feign 异常兜底 + `getNow(默认值)` 降级路径。

### 未实测

- 第 1 层超时降级（需下游服务响应 > 3s，未模拟）
- 第 2 层动态超时（需精确计时验证）

---

## 发散

### 批量接口优化

当前 `batchGetNoteDetails()` 是逐个调用 content 服务（N 个 Feign 请求）。如果 content 提供批量接口（`POST /batch?ids=1,2,3`），N 次调用 → 1 次，聚合耗时从 O(N) 降到 O(1)。

### 响应缓存

对低频变更数据（作者信息、热门评论）做短 TTL 缓存（如 30 秒），可以减少 Feign 调用。注意缓存一致性（作者改头像后 30 秒内不一致可接受）。
