# my-xhs API聚合层（BFF）详细设计

> 首页Feed流需要同时调6-8个服务，没有聚合层前端要么串行调用、要么超时。
> my-xhs-home 就是 BFF（Backend For Frontend）层，负责聚合后端服务数据、裁剪字段、统一响应格式。

---

## 一、为什么需要 BFF 层

### 1.1 没有 BFF 的问题

```
前端首页加载流程（无BFF）：
┌────────┐   ┌──────┐   ┌──────┐   ┌──────┐   ┌──────┐   ┌──────┐
│ Client │──▶│ User │──▶│Note  │──▶│Social│──▶│Counter│──▶│Push  │
└────────┘   └──────┘   └──────┘   └──────┘   └──────┘   └──────┘
   │                                                                  总RT: 50+100+80+60+30 = 320ms（串行）
   │              串行调用5次，每次有网络开销
   │              每个服务返回完整数据，大量冗余字段
   └────────────── 前端需要自己拼装数据，逻辑复杂
```

### 1.2 有 BFF 后的效果

```
前端首页加载流程（有BFF）：
┌────────┐   ┌──────┐   ┌──────┐ (并行)
│ Client │──▶│ Home │──▶│ Note │────┐
└────────┘   │(BFF) │   │ User │────┤
             └──────┘   │Social│────┤
                │       │Counter│───┤
                │       │Search│────┘
                ▼       
         聚合+裁剪+缓存    总RT: max(100, 50, 80, 60, 30) = 100ms（并行）
         统一响应格式
```

### 1.3 BFF 层的核心价值

| 价值 | 说明 | 示例 |
|------|------|------|
| **并行聚合** | CompletableFuture并行调用多个服务 | 首页RT从320ms→100ms |
| **字段裁剪** | 只返回前端需要的字段 | User服务返回30字段，BFF只取5字段 |
| **格式统一** | 不同服务响应格式不一致时统一 | 统一为`Result<PageData<T>>` |
| **降级兜底** | 某个服务不可用时返回降级数据 | Counter挂了返回0计数 |
| **缓存前置** | 聚合结果缓存，减少后端调用 | 首页Feed缓存5秒 |

---

## 二、BFF 层架构设计

### 2.1 my-xhs-home 服务定位

```
my-xhs-home 是唯一的 BFF 层
├── 不对外提供独立的业务接口
├── 不写业务逻辑，只做聚合+裁剪+缓存
├── 不拥有数据库
├── 依赖所有业务服务的 Feign Client
└── 可按终端（APP/H5/小程序）扩展多个 BFF 实例
```

### 2.2 聚合接口清单

| 接口 | 聚合的服务 | 并行/串行 | 缓存策略 |
|------|-----------|----------|---------|
| **GET /api/home/feed** | Note + Social + Counter + User | 并行 | 5秒本地缓存 |
| **GET /api/home/note/{id}** | Note + Counter + Social + Product | 并行 | 30秒Redis |
| **GET /api/home/product/{id}** | Product + Inventory + Counter + Note | 并行 | 30秒Redis |
| **GET /api/home/user/{id}** | User + Counter + Social | 并行 | 60秒Redis |
| **GET /api/home/search** | Search + Counter + User | 并行 | 10秒本地 |
| **GET /api/home/cart** | Cart + Product + Inventory + Coupon | 并行 | 无缓存（实时） |
| **GET /api/home/order/{id}** | Order + Product + User + Counter | 并行 | 无缓存（实时） |

---

## 三、核心聚合实现

### 3.1 首页Feed聚合（最复杂）

```java
@Service
public class HomeFeedAggregator {

    @Autowired private NoteClient noteClient;
    @Autowired private SocialClient socialClient;
    @Autowired private CounterClient counterClient;
    @Autowired private UserClient userClient;
    @Autowired private SearchClient searchClient;
    @Autowired private PushClient pushClient;

    /**
     * 首页Feed聚合
     * 调用6个服务，CompletableFuture并行编排
     * 目标RT：P99 < 200ms
     */
    public FeedVO getFeed(Long userId, String feedType, int page, int size) {
        FeedVO feedVO = new FeedVO();

        // 第1层并行：获取笔记列表 + 社交状态 + 通知数
        CompletableFuture<List<NoteDTO>> notesFuture = CompletableFuture.supplyAsync(
            () -> getNotes(userId, feedType, page, size), executor);
        CompletableFuture<SocialStatusDTO> socialFuture = CompletableFuture.supplyAsync(
            () -> socialClient.getSocialStatus(userId), executor);
        CompletableFuture<Integer> unreadFuture = CompletableFuture.supplyAsync(
            () -> pushClient.getUnreadCount(userId), executor);

        // 等待第1层完成
        CompletableFuture.allOf(notesFuture, socialFuture, unreadFuture).join();

        List<NoteDTO> notes = notesFuture.join();
        if (CollectionUtils.isEmpty(notes)) {
            return feedVO;
        }

        // 从笔记列表提取需要的userId和noteId
        List<Long> noteIds = notes.stream().map(NoteDTO::getId).toList();
        List<Long> authorIds = notes.stream().map(NoteDTO::getAuthorId).distinct().toList();

        // 第2层并行：获取计数 + 用户信息
        CompletableFuture<Map<Long, CounterDTO>> counterFuture = CompletableFuture.supplyAsync(
            () -> counterClient.batchGetCounters("NOTE", noteIds), executor);
        CompletableFuture<Map<Long, UserDTO>> userFuture = CompletableFuture.supplyAsync(
            () -> userClient.batchGetUsers(authorIds), executor);

        // 等待第2层完成
        CompletableFuture.allOf(counterFuture, userFuture).join();

        // 聚合
        Map<Long, CounterDTO> counters = counterFuture.join();
        Map<Long, UserDTO> users = userFuture.join();

        feedVO.setNotes(notes.stream().map(note -> {
            FeedNoteVO vo = new FeedNoteVO();
            vo.setNote(note);
            vo.setAuthor(users.get(note.getAuthorId()));
            vo.setCounter(counters.get(note.getId()));
            return vo;
        }).toList());
        feedVO.setSocialStatus(socialFuture.join());
        feedVO.setUnreadCount(unreadFuture.join());

        return feedVO;
    }
}
```

### 3.2 商品详情聚合

```java
@Service
public class ProductDetailAggregator {

    /**
     * 商品详情聚合
     * 并行调4个服务，目标RT：P99 < 150ms
     */
    public ProductDetailVO getProductDetail(Long productId, Long userId) {
        // 全部并行
        CompletableFuture<ProductDTO> productFuture = ...;
        CompletableFuture<InventoryDTO> inventoryFuture = ...;
        CompletableFuture<CounterDTO> counterFuture = ...;
        CompletableFuture<List<NoteDTO>> relatedNotesFuture = ...;  // 关联笔记

        CompletableFuture.allOf(productFuture, inventoryFuture,
            counterFuture, relatedNotesFuture).join();

        // 聚合 + 裁剪
        ProductDetailVO vo = new ProductDetailVO();
        vo.setProduct(productFuture.join());
        vo.setInStock(inventoryFuture.join().getAvailable() > 0);  // 只返回是否有库存
        vo.setCounter(counterFuture.join());
        vo.setRelatedNotes(relatedNotesFuture.join());
        return vo;
    }
}
```

---

## 四、降级策略

### 4.1 按服务重要性分级

| 服务 | 重要性 | 降级策略 | 降级数据 |
|------|--------|---------|---------|
| **Note** | P0 核心 | 不降级，失败直接返回错误 | — |
| **User** | P0 核心 | 不降级，失败直接返回错误 | — |
| **Counter** | P1 重要 | 降级返回0计数 | `{likeCount:0, favoriteCount:0, commentCount:0}` |
| **Social** | P1 重要 | 降级返回未关注状态 | `{isFollowed:false, isLiked:false}` |
| **Product** | P0 核心 | 不降级，失败直接返回错误 | — |
| **Inventory** | P1 重要 | 降级返回"查看库存" | `{inStock: null, showCheckButton: true}` |
| **Push** | P2 补充 | 降级返回0未读 | `{unreadCount:0}` |
| **Search** | P1 重要 | 降级返回热门推荐 | `{results: cachedHotNotes}` |

### 4.2 降级实现

```java
// 通用降级包装器
public <T> T callWithFallback(Supplier<T> supplier, Supplier<T> fallback, String serviceName) {
    try {
        T result = supplier.get();
        return result;
    } catch (Exception e) {
        log.warn("服务调用降级: {}, 原因: {}", serviceName, e.getMessage());
        return fallback.get();
    }
}

// 使用示例
CounterDTO counter = callWithFallback(
    () -> counterClient.getCounter("NOTE", noteId),
    () -> CounterDTO.zero(),    // 降级返回0
    "counter"
);
```

### 4.3 聚合层缓存

```java
// 首页Feed缓存5秒（本地缓存，不依赖Redis）
@Cacheable(value = "home:feed", key = "#userId + ':' + #feedType + ':' + #page",
           cacheManager = "localCacheManager")  // Caffeine 5秒过期
public FeedVO getFeed(Long userId, String feedType, int page, int size) {
    // ...
}

// 商品详情缓存30秒（Redis分布式缓存）
@Cacheable(value = "home:product:detail", key = "#productId",
           cacheManager = "redisCacheManager")  // Redis 30秒TTL
public ProductDetailVO getProductDetail(Long productId, Long userId) {
    // ...
}
```

---

## 五、线程池配置

### 5.1 BFF专用线程池

```yaml
# BFF聚合调用使用独立线程池，不占用Tomcat工作线程
home:
  aggregator:
    core-pool-size: 20       # 核心线程数
    max-pool-size: 50        # 最大线程数
    queue-capacity: 200      # 队列容量
    keep-alive-seconds: 60   # 空闲线程存活时间
    thread-name-prefix: "home-aggregator-"
```

### 5.2 为什么不用默认的 ForkJoinPool

```
CompletableFuture默认使用ForkJoinPool.commonPool()
问题：
1. 线程数=CPU核数-1，8核只有7个线程，高并发下不够
2. 所有CompletableFuture共享，BFF聚合可能阻塞其他业务
3. 无法独立监控和调优

解决：BFF使用独立ThreadPoolExecutor
- 线程数可根据聚合调用量独立调整
- 监控指标独立：活跃线程数、队列长度、拒绝次数
- 不会影响其他业务线程
```

---

## 六、监控指标

### 6.1 BFF层特有指标

| 指标 | 含义 | 告警阈值 |
|------|------|---------|
| `home_aggregator_active_threads` | 聚合线程池活跃线程数 | >40 (max的80%) |
| `home_aggregator_queue_size` | 聚合线程池队列长度 | >100 |
| `home_feed_rt_p99` | 首页Feed聚合RT | >300ms |
| `home_feed_fallback_count` | 首页Feed降级次数 | >10/min |
| `home_aggregate_total_rt` | 聚合总耗时 | >500ms |

### 6.2 每个被调服务的RT追踪

```java
// 在SkyWalking中为每个聚合调用创建子Span
@TraceCrossThread  // SkyWalking跨线程追踪
CompletableFuture<NoteDTO> notesFuture = CompletableFuture.supplyAsync(() -> {
    Span span = GlobalTracer.get().createSpan("Home→Note");
    try {
        return noteClient.getNotes(userId, feedType, page, size);
    } finally {
        span.finish();
    }
}, executor);
```

---

## 七、生产决策与表达

### Q: 首页Feed加载为什么这么快？

> "首页Feed通过BFF聚合层并行调用6个服务。用CompletableFuture把串行320ms优化到并行100ms（取最慢服务的RT）。聚合层还有5秒本地缓存，重复请求直接返回缓存。Counter不可用时降级返回0计数，不阻塞主流程。"

### Q: BFF层会不会成为瓶颈？

> "BFF层不写业务逻辑、不连数据库、不连Redis（除缓存外），只做聚合和转发，RT极低。线程池独立配置（50线程），不占用Tomcat工作线程。如果真成为瓶颈，可以按终端（APP/H5/小程序）拆分成多个BFF实例独立扩容。"

### Q: 聚合层缓存和数据一致性怎么保证？

> "首页Feed缓存5秒，用户刷新最多延迟5秒看到新内容——这是可接受的。商品详情缓存30秒，通过Canal监听商品变更事件主动失效缓存。订单和购物车不缓存，保证实时性。聚合缓存失效策略：源头服务数据变更→Canal→MQ→BFF主动删缓存。"
