# 02 — 模块交互拓扑

> **目标读者**：P7+ 工程师，需要理解服务间的调用关系、数据流向和瓶颈链路。
> **回答三个问题**：谁调谁？数据怎么流？最长的调用链多长？

---

## 一、调用关系全景图

```
                        ┌──────────────────────────────────────────┐
                        │               Client                     │
                        └──────────────────┬───────────────────────┘
                                           │
                                           ▼
                        ┌──────────────────────────────────────────┐
                        │           Gateway (:19000)               │
                        │  Auth → Coloring → HMAC → RateLimit      │
                        │  → ZonePreference → ApiVersion           │
                        └──────────────────┬───────────────────────┘
                                           │
              ┌────────────────────────────┼────────────────────────────┐
              │                            │                            │
              ▼                            ▼                            ▼
    ┌─────────────────┐          ┌─────────────────┐          ┌─────────────────┐
    │   user :19001   │          │  content :19002 │          │   home :19015   │
    │   注册/登录/Token│          │ 笔记/评论/审核  │          │   BFF 聚合层    │
    └────────┬────────┘          └────────┬────────┘          └────────┬────────┘
             │                            │                            │
             │                            │              ┌─────────────┼─────────────┐
             │                            │              │             │             │
             │              ┌─────────────┼──────────────┤             │             │
             │              │             │              │             │             │
             ▼              ▼             ▼              ▼             ▼             ▼
    ┌──────────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐
    │  analytics   │ │ counter  │ │ product  │ │inventory │ │  coupon  │ │  cart    │
    │  :19003      │ │ :19004   │ │ :19006   │ │ :19009   │ │ :19010   │ │ :19008   │
    └──────┬───────┘ └──────────┘ └──────────┘ └────┬─────┘ └──────────┘ └────┬─────┘
           │                                        │                        │
           │                              ┌─────────┼─────────┐              │
           │                              │         │         │              │
           ▼                              ▼         ▼         ▼              ▼
    ┌──────────────┐              ┌──────────┐ ┌──────────┐         ┌──────────┐
    │ notification │              │  order   │ │ payment  │         │  search  │
    │  :19013      │              │  :19011  │ │ :19012   │         │  :19016  │
    └──────────────┘              └────┬─────┘ └────┬─────┘         └──────────┘
                                      │             │
                                      │    Feign    │
                                      └─────────────┘
                                      双向调用（过度拆分）
```

### 调用关系矩阵

| 调用方 → 被调用方 | user | content | counter | product | order | inventory | cart | coupon | payment | analytics | notification | search | home |
|-------------------|------|---------|---------|---------|-------|-----------|------|--------|---------|-----------|-------------|--------|------|
| **gateway** | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| **home** | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | — | ✓ | ✓ | ✓ | — |
| **order** | — | — | — | — | — | ✓ | — | ✓ | ✓ | — | — | — | — |
| **payment** | — | — | — | — | ✓ | — | — | — | — | — | — | — | — |
| **cart** | — | — | — | ✓ | — | — | — | — | — | — | — | — | — |
| **analytics** | ✓ | — | — | — | — | — | — | — | — | — | — | — | — |

**图例**：✓ = Feign 调用，— = 无直接调用

### 15 个 @FeignClient 声明

| # | 模块 | Feign 接口 | 目标服务 | 文件 |
|---|------|-----------|---------|------|
| 1 | home | UserFeignClient | my-xhs-user | `home/feign/UserFeignClient.java` |
| 2 | home | ContentFeignClient | my-xhs-content | `home/feign/ContentFeignClient.java` |
| 3 | home | CounterFeignClient | my-xhs-counter | `home/feign/CounterFeignClient.java` |
| 4 | home | ProductFeignClient | my-xhs-product | `home/feign/ProductFeignClient.java` |
| 5 | home | OrderFeignClient | my-xhs-order | `home/feign/OrderFeignClient.java` |
| 6 | home | InventoryFeignClient | my-xhs-inventory | `home/feign/InventoryFeignClient.java` |
| 7 | home | CartFeignClient | my-xhs-cart | `home/feign/CartFeignClient.java` |
| 8 | home | CouponFeignClient | my-xhs-coupon | `home/feign/CouponFeignClient.java` |
| 9 | home | NotificationFeignClient | my-xhs-notification | `home/feign/NotificationFeignClient.java` |
| 10 | home | AnalyticsFeignClient | my-xhs-analytics | `home/feign/AnalyticsFeignClient.java` |
| 11 | home | SearchFeignClient | my-xhs-search | (默认配置) |
| 12 | order | InventoryFeignClient | my-xhs-inventory | `order/feign/InventoryFeignClient.java` |
| 13 | order | CouponFeignClient | my-xhs-coupon | `order/feign/CouponFeignClient.java` |
| 14 | order | PaymentFeignClient | my-xhs-payment | `order/feign/PaymentFeignClient.java` |
| 15 | payment | OrderFeignClient | my-xhs-order | `payment/feign/OrderFeignClient.java` |
| 16 | cart | ProductFeignClient | my-xhs-product | `cart/feign/ProductFeignClient.java` |
| 17 | analytics | UserFeignClient | my-xhs-user | `analytics/feign/UserFeignClient.java` |

---

## 二、RocketMQ 事件流全景

### 2.1 Topic 与 Consumer 完整清单

#### 社交领域（SOCIAL_TOPIC — Tag 过滤模式）

```
SOCIAL_TOPIC
  ├── tag=LIKE        → LikeConsumer (analytics)        点赞记录写入
  ├── tag=UNLIKE      → UnlikeConsumer (analytics)      取消点赞
  ├── tag=FAVORITE    → FavoriteConsumer (analytics)    收藏记录写入
  ├── tag=UNFAVORITE  → UnfavoriteConsumer (analytics)  取消收藏
  ├── tag=FOLLOW      → FollowConsumer (analytics)      关注记录写入
  └── tag=UNFOLLOW    → UnfollowConsumer (analytics)    取消关注
```

**为什么 6 个 Consumer 共享 1 个 Topic？**
- 社交事件本质相同：都是"用户对内容的操作"，数据结构一致
- Tag 过滤：Broker 在服务端按 Tag 过滤，Consumer 只收到自己关心的消息
- 减少 Topic 数量：降低 Broker 管理开销

#### 订单领域

```
ORDER_TRANSACTION_TOPIC
  └── OrderTransactionConsumer (inventory)   下单事务消息 → 库存预扣

ORDER_CLOSE_TOPIC
  └── OrderCloseConsumer (order)            延时关单 (delayLevel=16, 30min)

ORDER_COMPENSATION_TOPIC
  └── OrderCompensationConsumer (order)     补偿消息重试
```

#### 支付领域

```
PAY_RESULT_TOPIC
  ├── PayResultConsumer (payment)           支付结果持久化
  └── (order 服务也消费)                    更新订单支付状态

REFUND_RESULT_TOPIC
  ├── RefundResultConsumer (payment)        退款结果持久化
  └── (order 服务也消费)                    更新订单退款状态
```

#### 库存领域

```
INVENTORY_TOPIC
  └── InventoryDeductConsumer (inventory)   库存变更异步持久化 MySQL

INVENTORY_CACHE_TOPIC
  └── InventoryCacheEvictConsumer (inventory)  Canal 触发缓存失效
```

#### 内容/Feed 领域

```
FEED_TOPIC
  └── FeedPushConsumer (home)              笔记发布 → 推送到粉丝收件箱

NOTE_INDEX_TOPIC
  └── NoteIndexSyncConsumer (search)       笔记 ES 索引同步 (Canal)

PRODUCT_INDEX_TOPIC
  └── ProductIndexSyncConsumer (search)    商品 ES 索引同步 (Canal)

RECOMMEND_BEHAVIOR_TOPIC
  └── BehaviorReportConsumer (search)      推荐行为上报
```

#### 其他

```
NOTIFICATION_TOPIC
  └── NotificationEventConsumer (notification)  通知事件（评论/点赞/关注）

CART_TOPIC
  └── CartSyncConsumer (cart)              购物车异步持久化 MySQL

COUPON_CLAIM_TOPIC
  └── CouponClaimConsumer (coupon)         领券异步持久化 MySQL

CACHE_EVICT_TOPIC
  ├── CacheEvictConsumer (user)            用户缓存驱逐
  └── (product 服务也消费)                  商品缓存驱逐

DEFAULT_RETRY_TOPIC
  └── LocalMessageRetryJob (order)         本地消息重试兜底
```

### 2.2 数据流方向分析

```
┌──────────────────────────────────────────────────────────┐
│                    同步调用 (Feign)                        │
│  Client → Gateway → [Service] → Feign → [Service]        │
│  方向：北→南（请求流）                                     │
├──────────────────────────────────────────────────────────┤
│                    异步事件 (RocketMQ)                     │
│  [Service] → MQ → [Consumer]                             │
│  方向：事件生产者 → 事件消费者                              │
├──────────────────────────────────────────────────────────┤
│                    Binlog 同步 (Canal)                     │
│  MySQL Binlog → Canal → MQ → [Consumer]                   │
│  方向：数据库 → 缓存/索引                                   │
└──────────────────────────────────────────────────────────┘
```

**关键数据流**：

| 数据流 | 同步/异步 | 路径 |
|--------|----------|------|
| **下单** | 同步 + 事务消息 | order → Feign(inventory) + ORDER_TRANSACTION_TOPIC → inventory |
| **支付** | 异步 | payment → PAY_RESULT_TOPIC → order |
| **笔记发布** | 异步 | content → FEED_TOPIC → home (Feed推送) |
| **社交互动** | 异步 | analytics → SOCIAL_TOPIC → analytics (自己消费) |
| **ES 同步** | 异步 (Canal) | MySQL Binlog → Canal → NOTE_INDEX_TOPIC → search |
| **缓存驱逐** | 异步 | 业务服务 → CACHE_EVICT_TOPIC → 缓存消费者 |

---

## 三、BFF 聚合层拓扑（home 服务）

### 3.1 首页 Feed 加载流程

```
GET /api/home/feed?cursor=xxx&size=20
         │
         ▼
    HomeController
         │
         ▼
    HomeService.buildFeed()
         │
         ├──[并行]──► CounterFeignClient.getLikeCounts(noteIds)       ──┐
         ├──[并行]──► ContentFeignClient.getNoteDetails(noteIds)       ──┤
         ├──[并行]──► UserFeignClient.getUserInfos(userIds)            ──┤
         ├──[并行]──► ProductFeignClient.getProductDetails(productIds) ──┤  CompletableFuture
         ├──[并行]──► CartFeignClient.getCartItemCount()               ──┤  allOf()
         ├──[并行]──► CouponFeignClient.getAvailableCoupons()          ──┤
         ├──[并行]──► NotificationFeignClient.getUnreadCount()         ──┤
         ├──[并行]──► AnalyticsFeignClient.isFollowed(userId)          ──┤
         └──[并行]──► SearchFeignClient.getHotSearchWords()            ──┘
         │
         ▼
    FeedAssembler.assemble()  ← 聚合组装
         │
         ▼
    Result<FeedVO>
```

### 3.2 双线程池隔离

```
                    ┌──────────────────────────┐
                    │     home Thread Pool      │
                    ├──────────────────────────┤
请求到达 ──────────►│  aggregatorPool           │  ← 聚合编排线程池
                    │  core=20, max=50,         │
                    │  queue=200                │
                    │                           │
                    │  batchFeignPool           │  ← Feign 调用线程池
                    │  core=30, max=80,         │
                    │  queue=500                │
                    │                           │
                    │  拒绝策略：CallerRunsPolicy │
                    └──────────────────────────┘
```

**为什么需要双线程池？**
- `aggregatorPool`：编排 10+ 个 CompletableFuture 的 `allOf()`，CPU 密集
- `batchFeignPool`：实际执行 Feign HTTP 调用，IO 密集
- 隔离后可防止 IO 慢调用阻塞编排线程

**CallerRunsPolicy 的风险**：高峰期可能阻塞 Tomcat 线程（→ 见 `42-cache-concurrency-engineering-issues.md`）

### 3.3 依赖关系分析

home 是 BFF 层，依赖 11 个下游服务，是所有服务中依赖最多的：

| 依赖服务 | 必要性 | 降级策略 |
|---------|--------|---------|
| content | 核心 | 不可降级 |
| counter | 核心 | 不可降级 |
| user | 核心 | 默认头像/昵称 |
| product | 条件 | 商品为空 |
| inventory | 条件 | 库存未知 |
| cart | 条件 | 购物车为空 |
| coupon | 条件 | 无可用券 |
| order | 条件 | 无订单 |
| analytics | 条件 | 未关注 |
| notification | 条件 | 未读数=0 |
| search | 条件 | 热搜为空 |

---

## 四、瓶颈链路分析

### 4.1 最长调用链

```
下单全链路（同步 + 异步）：

Client → Gateway → OrderController
  → OrderService.createOrder()
    → Feign: InventoryService.preDeduct()        [网络调用 1]
    → Feign: CouponService.useCoupon()           [网络调用 2]
    → 事务消息: ORDER_TRANSACTION_TOPIC          [MQ 发送]
    → DB: INSERT t_order (ShardingSphere 路由)   [DB 写入]
    → 事务消息回查: ORDER_TRANSACTION_TOPIC      [MQ 回查]
      → InventoryDeductConsumer.deduct()         [MQ 消费]
        → DB: UPDATE t_inventory                 [DB 写入]
    → 延时消息: ORDER_CLOSE_TOPIC (30min)        [MQ 延时]
    → Feign: PaymentService.pay()                [网络调用 3]
      → PAY_RESULT_TOPIC → order                [MQ 回调]
        → DB: UPDATE t_order (status=PAID)       [DB 写入]

涉及：5 个服务、3 次 Feign、4 次 MQ、3 次 DB 写入
```

### 4.2 热点链路

| 链路 | QPS 特征 | 瓶颈点 |
|------|---------|--------|
| **首页 Feed** | 高 QPS（万级） | home 并行调用 11 个服务，任意一个慢查询拖慢整体 |
| **库存扣减** | 高并发（秒杀级） | Redis Lua 脚本执行、分桶热点、MySQL 异步持久化 |
| **笔记发布** | 中等 QPS（千级） | DFA 敏感词过滤、FEED_TOPIC 粉丝推送 |
| **搜索** | 高 QPS（万级） | ES 查询、Search After 深分页、热搜计算 |
| **IM 消息** | 高并发（百万级连接） | WebSocket 长连接管理、跨实例路由 |

### 4.3 故障传播分析

```
                    ┌──────────┐
                    │  MySQL   │ ← 单实例宕机
                    └────┬─────┘
                         │
              ┌──────────┼──────────┐
              │          │          │
              ▼          ▼          ▼
         ┌────────┐ ┌────────┐ ┌────────┐
         │content │ │counter │ │product │ ← 3 个服务不可用
         └────┬───┘ └────────┘ └────────┘
              │
              ▼
         ┌────────┐
         │  home  │ ← BFF 层受影响
         └────────┘
```

**13307 实例承载 6 个业务服务**（content/counter/product/cart/coupon/search），一个慢查询可能影响同实例所有服务。这就是为什么 inventory 独占实例（→ 见 `40-mysql-engineering-issues.md`）。

---

## 五、通信模式总结

| 模式 | 使用场景 | 占比 | 特点 |
|------|---------|------|------|
| **同步 Feign** | 实时查询、命令操作 | ~30% | 简单直接，但有级联故障风险 |
| **异步 RocketMQ** | 事件通知、最终一致性 | ~50% | 解耦、削峰、可靠投递 |
| **事务消息** | 下单（order→inventory） | ~5% | 保证本地事务和消息发送原子性 |
| **Canal Binlog** | 数据同步（MySQL→ES/Redis） | ~10% | 零侵入、准实时 |
| **WebSocket** | IM 即时通讯 | ~5% | 长连接、双向通信 |

---

> **下一篇**：`03-common-infrastructure.md` — common 模块 30+ 配置类 + AutoConfiguration 组件的注册顺序与依赖关系
