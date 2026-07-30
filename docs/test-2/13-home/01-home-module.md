# 13-home 首页 BFF — 架构文档

> 端口：19015 | 数据库：无（纯聚合层） | 更新时间：2026-07-30

---

## 1. 模块定位

home BFF 是 my-xhs 平台的前端聚合层（Backend For Frontend），负责编排下游 9 个微服务的数据组装为前端友好的聚合视图。

核心能力：
- **推拉混合 Feed 流**：普通用户推模式 + 大V 拉模式，Redis ZSet 收件箱/发件箱
- **2 层并行聚合**：CompletableFuture 编排，动态超时 + `getNow()` 优雅降级
- **9 个 Feign FallbackFactory**：每个下游服务独立降级，区分"服务不可用"和"数据不存在"
- **MQ 异步写扩散**：笔记发布事件 → RocketMQ FEED_TOPIC → 推送到粉丝收件箱

---

## 2. 架构图

```
┌──────────────────────────────────────────────────────────────────────────┐
│                              客户端                                       │
│              GET /api/home/{feed|note|product|user|cart}                  │
│                                  │                                       │
│                         Gateway (19000)                                  │
│                                  │                                       │
│                    ┌─────────────▼──────────────┐                        │
│                    │     HomeController          │                        │
│                    │  CompletableFuture.supplyAsync(aggregatorPool)      │
│                    └─────────────┬──────────────┘                        │
│                                  │                                       │
│              ┌───────────────────┼───────────────────┐                   │
│              ▼                   ▼                    ▼                   │
│     ┌────────────────┐ ┌────────────────┐ ┌──────────────────┐          │
│     │   FeedService  │ │  NoteAggService│ │ ProductAggService│          │
│     │ 推拉混合Feed流  │ │ 笔记详情聚合    │ │ 商品详情聚合      │          │
│     └───────┬────────┘ └───────┬────────┘ └────────┬─────────┘          │
│             │                  │                    │                    │
│     ┌───────▼────────┐ ┌──────▼───────┐  ┌─────────▼─────────┐          │
│     │CartAggService  │ │UserProfile   │  │   2层并行编排       │         │
│     │购物车聚合       │ │AggService    │  │ L1: 3s + L2: 2s   │         │
│     └───────┬────────┘ └──────┬───────┘  └─────────┬─────────┘          │
│             │                 │                     │                    │
│             └──────────┬──────┘                     │                    │
│                        │                            │                    │
│              ┌─────────▼────────────────────────────▼─────────┐          │
│              │             9x Feign Clients (batchFeignPool)   │         │
│              │  user / content / product / cart / inventory    │         │
│              │  coupon / counter / analytics / notification    │         │
│              │  全部使用 FallbackFactory 降级                   │         │
│              └────┬────┬────┬────┬────┬────┬────┬────┬────┬───┘          │
│                   │    │    │    │    │    │    │    │    │              │
│    my-xhs-user   content  product  cart  inventory  coupon  counter     │
│    19001         19002    19006    19008 19009      19010   19004        │
│    analytics(19003)  notification(19013)                                 │
│                                                                          │
│ ┌──────────────────────────────────────────────────────────────────┐     │
│ │             Redis (Business 16381)                                │    │
│ │  myxhs:feed:inbox:{uid}  (ZSet) — 收件箱 (7天TTL)                 │    │
│ │  myxhs:feed:outbox:{uid} (ZSet) — 发件箱 (7天TTL)                 │    │
│ │  myxhs:user:bigv:{uid}   (String) — 大V标记 (10min TTL)          │    │
│ └──────────────────────────────────────────────────────────────────┘     │
│                                                                          │
│ ┌──────────────────────────────────────────────────────────────────┐     │
│ │  RocketMQ FEED_TOPIC                 XXL-Job FeedCleanupJob       │    │
│ │  FeedPushConsumer (写扩散)           每天3点SCAN清理过期收件箱      │    │
│ └──────────────────────────────────────────────────────────────────┘     │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 3. 源码清单

| 文件 | 职责 |
|---|---|
| `HomeApplication.java` | 启动类，@EnableAsync + @EnableFeignClients + @EnableScheduling |
| `controller/HomeController.java` | BFF 主控制器，5 个聚合接口，CompletableFuture 异步返回 |
| `controller/FeedTestController.java` | 测试接口（@Profile("dev")，绕过 MQ 直接推收件箱/发件箱） |
| `service/FeedService.java` | 推拉混合 Feed 流（读取收件箱+大V发件箱+2 层聚合） |
| `service/NoteAggService.java` | 笔记详情聚合 |
| `service/ProductAggService.java` | 商品详情聚合 |
| `service/CartAggService.java` | 购物车聚合 |
| `service/UserProfileAggService.java` | 用户主页聚合 |
| `consumer/FeedPushConsumer.java` | RocketMQ 消费者：笔记发布事件 → 写扩散推送 |
| `job/FeedCleanupJob.java` | XXL-Job 定时清理过期收件箱 |
| `config/AggregatorThreadPoolConfig.java` | 双层线程池（aggregatorPool + batchFeignPool）+ MDC 透传 |
| `feign/*FeignClient.java` | 9 个 Feign 接口 |
| `feign/fallback/*FallbackFactory.java` | 9 个 FallbackFactory |
| `dto/FeedVO.java` | Feed 流 VO |
| `dto/NoteCardVO.java` | Feed 卡片 VO |
| `dto/NoteDetailAggVO.java` | 笔记详情聚合 VO |
| `dto/ProductDetailAggVO.java` | 商品详情聚合 VO |
| `dto/CartAggVO.java` | 购物车聚合 VO |
| `dto/UserProfileAggVO.java` | 用户主页聚合 VO |
| `dto/NotePublishEvent.java` | 笔记发布事件 MQ DTO |

---

## 4. 数据模型

### 4.1 Redis Key 设计

| Key 模式 | 类型 | TTL | 说明 |
|---|---|---|---|
| `myxhs:feed:inbox:{userId}` | ZSet (score=publishTime) | 7 天 | 用户收件箱（推模式写入） |
| `myxhs:feed:outbox:{authorId}` | ZSet (score=publishTime) | 7 天 | 大V发件箱（拉模式读取） |
| `myxhs:user:bigv:{authorId}` | String ("1"/"0") | 10 分钟 | 大V标记缓存 |
| `myxhs:feed:push:progress:{localMsgId}` | String (cursor) | 1 小时 | 推送进度游标（断点续推） |
| `myxhs:feed:push:progress:{localMsgId}` | Hash (total/status) | 1 小时 | 推送元数据 |
| `myxhs:follow:list:{userId}` | ZSet | 永久 | 用户关注列表 |

### 4.2 无数据库

无 DataSource。自动排除了 `DataSourceAutoConfiguration`。

---

## 5. API 端点

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|---|
| GET | `/api/home/feed` | X-User-Id | 关注 Feed 流（推拉混合，游标分页） |
| GET | `/api/home/note/{noteId}` | X-User-Id 可选 | 笔记详情聚合 |
| GET | `/api/home/product/{spuId}` | 公开 | 商品详情聚合 |
| GET | `/api/home/user/{targetUserId}` | X-User-Id 可选 | 用户主页聚合 |
| GET | `/api/home/cart` | X-User-Id | 购物车聚合 |
| POST | `/api/home/test/push-inbox` | dev | 手动推收件箱（@Profile("dev")，仅开发环境） |
| POST | `/api/home/test/push-outbox` | dev | 手动推发件箱（@Profile("dev")，仅开发环境） |

所有接口返回 `CompletableFuture<R<VO>>`，释放 Tomcat 线程到 aggregatorPool 执行。

---

## 6. 核心流程

### 6.1 Feed 流读取（推拉混合）

```
GET /api/home/feed?lastScore={cursor}&size=20
                    │
           ┌────────▼────────┐
           │  Step 1: 获取ID │
           │                 │
           │ ① 收件箱:       │
           │ ZREVRANGEBYSCORE│
           │ myxhs:feed:inbox│
           │ :{uid}          │
           │ 0 ~ cursor-0.001│
           │                 │
           │ ② 大V发件箱:     │
           │ Pipeline 读取   │
           │ 关注的大V的发件箱 │
           │                 │
           │ ③ 合并+排序     │
           │ 按score降序     │
           └────────┬────────┘
                    │
           ┌────────▼────────┐
           │  Step 2: 聚合   │
           │                 │
           │ L1 (3s timeout):│
           │  ├─ batchGetNote│
           │  │  Details(n)  │
           │  ├─ batchCheck  │
           │  │  LikeStatus  │
           │  └─ getUnread   │
           │     Count()     │
           │                 │
           │ L2 (2s timeout):│
           │  ├─ batchGetUser│
           │  │  Infos(n)    │
           │  └─ batchGet    │
           │     Counters()  │
           └────────┬────────┘
                    │
           ┌────────▼────────┐
           │ Step 3: 组装    │
           │ NoteCardVO[] +  │
           │ nextCursor      │
           └─────────────────┘
```

### 6.2 Feed 推送（写扩散）

```
内容服务发笔记 → RocketMQ FEED_TOPIC
                    │
           ┌────────▼────────┐
           │ FeedPushConsumer│
           │                 │
           │ 判断是否大V      │
           │ 查 Redis bigv:  │
           │ {authorId}      │
           │ 10min TTL缓存   │
           └────────┬────────┘
                    │
         ┌──────────┴──────────┐
         ▼                     ▼
   ┌──────────┐      ┌──────────────────┐
   │ 是 大V    │      │ 否 — 普通用户     │
   │ 拉模式    │      │ 推模式            │
   │ ZADD     │      │ 遍历粉丝 ZSet     │
   │ outbox   │      │ (batch 500)       │
   │ 设 TTL   │      │ Pipeline ZADD     │
   └──────────┘      │ 到每个粉丝收件箱   │
                      │ 记录进度到 Redis  │
                      │ 断点续推          │
                      └──────────────────┘
```

### 6.3 2 层并行聚合模式（以 NoteAggService 为例）

```java
// 全局 4s 超时（request 级别）
long startTime = System.nanoTime();
long globalTimeoutMs = 4000;

// L1: 无依赖的并行调用（3s 超时）
CompletableFuture.allOf(f1, f2, f3).get(3, TimeUnit.SECONDS);
Map<...> r1 = f1.getNow(defaultValue);  // 降级

// L2: 依赖 L1 结果（动态超时，至少 500ms）
long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
long l2Timeout = Math.max(500, globalTimeoutMs - elapsed);
CompletableFuture.allOf(f4, f5).get(l2Timeout, TimeUnit.MILLISECONDS);
```

FeedService 使用固定超时（L1=3s, L2=2s），无全局 4s 兜底。

### 6.4 大V 判断

```
checkBigV(authorId):
  ├─ Redis GET myxhs:user:bigv:{authorId} → "1" → 是大V
  ├─ Redis GET → null → ZCARD 粉丝数 → >=100000 → 是大V
  └─ 缓存结果到 Redis，TTL=10min
```

---

## 7. 线程池设计

```
Tomcat 请求线程 (max=200)
       │
       ▼
aggregatorPool (core=20, max=50, queue=200)
  ├─ 编排层：FeedService / NoteAggService 等
  └─ 提交子任务到 batchFeignPool
       │
       ▼
batchFeignPool (core=30, max=80, queue=500)
  └─ Feign 调用（并行 batch 调用）
```

**为什么双层隔离？**：嵌套 `CompletableFuture` 场景下，内外共用线程池可能死锁（外层占满等待内层，内层无线程可用）。

**MDC 透传**：`MdcAwareExecutorService` 包装器，在 `execute()` 时捕获当前线程 MDC，任务执行时恢复。

---

## 8. 降级策略

| 服务 | 降级行为 |
|---|---|
| content | 空笔记详情 + 空评论列表 |
| user | 空用户信息 |
| analytics | 空社交状态（false/空） |
| counter | 空计数（0） |
| notification | 未读数=0 |
| product/inventory/cart/coupon | 空数据 |

所有超时字段通过 `future.getNow(defaultValue)` 安全读取。

---

## 9. 依赖关系

### Feign 下游（9 个）

| Feign | 目标 | 端口 |
|---|---|---|
| UserFeignClient | my-xhs-user | 19001 |
| ContentFeignClient | my-xhs-content | 19002 |
| AnalyticsFeignClient | my-xhs-analytics | 19003 |
| CounterFeignClient | my-xhs-counter | 19004 |
| ProductFeignClient | my-xhs-product | 19006 |
| CartFeignClient | my-xhs-cart | 19008 |
| InventoryFeignClient | my-xhs-inventory | 19009 |
| CouponFeignClient | my-xhs-coupon | 19010 |
| NotificationFeignClient | my-xhs-notification | 19013 |

### 中间件
- **Redis**：默认 `StringRedisTemplate` 通过 Sentinel（26379/26380/26381）路由到 master 16379。缓存 Redis 端口 16380（allkeys-lru），业务 Redis 端口 16381（noeviction），通过 `@Qualifier("cache")` 区分注入。
- **RocketMQ FEED_TOPIC**：消费笔记发布事件
- **Nacos**：服务注册与配置
- **XXL-Job**：Feed 清理调度

---

## 10. 关键设计决策

| 决策 | 选择 | 原因 |
|---|---|---|
| Feed 模型 | 推拉混合 | 普通用户推（低延迟），大V 拉（防写扩散） |
| 大V 阈值 | 100,000 粉丝 | 对标微博/Instagram 标准，超过此值推模式写扩散压力过大 |
| 线程池隔离 | aggregator + batchFeign | 防嵌套饥饿 |
| 超时策略 | 全局 4s + 动态 L2 | L2 至少 500ms |
| 降级 | FallbackFactory + getNow | 不断路 |
| 大V 缓存 TTL | 10 分钟 | 缩短不一致窗口 |
| 推送进度 | Redis cursor 断点续推 | 崩溃后恢复 |

---

## 11. 配置要点

| 配置项 | 值 | 说明 |
|---|---|---|
| 端口 | 19015 | |
| Tomcat 线程 | max=200, min-spare=20 | 异步释放，不阻塞 |
| aggregatorPool | core=20, max=50, queue=200 | 编排线程池 |
| batchFeignPool | core=30, max=80, queue=500 | Feign 线程池 |
| Feign 超时 | connect=3s, read=3s | |
| 全局聚合超时 | 4 秒 | |
| RocketMQ 消费组 | home-consumer-group | FEED_TOPIC |
| XXL-Job | appname=my-xhs-home, port=9994 | FeedCleanupJob |

---

## 12. 测试场景建议

1. Feed 流读取（收件箱有数据时返回 NoteCardVO 列表）
2. Feed 流翻页（传入 nextCursor）
3. 笔记详情聚合（2 层降级验证）
4. 商品详情聚合
5. 用户主页聚合
6. 购物车聚合
7. 下游服务不可用时降级验证（停掉一个 Feign 服务）
8. Feed 推送测试（通过 RocketMQ 手动发 NotePublishEvent）
