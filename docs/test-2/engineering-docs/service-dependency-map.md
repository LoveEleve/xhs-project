# my-xhs 服务依赖拓扑图

> 15 微服务 | 16 FeignClient | 16 RocketMQ Topic | 21 Consumer | 15 Gateway 路由

---

## 一、拓扑全景图

```
                              CLIENT
                                 │
                                 ▼
                         ┌───────────────┐
                         │ Gateway :19000 │  ← JWT/HMAC/限流
                         └───┬───┬───┬───┘
                             │   │   │
                ┌────────────┼───┼───┼─────────────────────┐
                │            │   │   │                     │
                ▼            ▼   │   ▼                     │
         ┌──────────┐  ┌────────┐│┌──────────┐   ┌──────────────┐
         │ user     │  │content │││ home(BFF)│   │ notification │
         │ :19001   │  │ :19002 │││ :19015   │   │ :19013       │
         └──┬───────┘  └───┬──┬─┘│└─┬──┬──┬──┘   └──┬───┬───────┘
            │              │  │  │  │  │  │          │   │
 Feign──────┼──────────────┼──┼──┼──┼──┼──┼──────────┼───┘
 (8路)      │              │  │  │  │  │  │          │
            │    ┌─────────┘  │  │  │  │  ├──────────┘
            │    │            │  │  │  │  │
            ▼    ▼            ▼  ▼  ▼  ▼  ▼
   ┌────────────────────────────────────────────────┐
   │           核心业务服务层                        │
   │  analytics :19003  counter :19004               │
   │  product   :19006  cart    :19008               │
   │  inventory :19009  coupon  :19010               │
   │  order     :19011  payment :19012               │
   │  search    :19016  im      :19014               │
   └────────┬───┬───┬───┬───┬───┬───────────────────┘
            │   │   │   │   │   │
            ▼   ▼   ▼   ▼   ▼   ▼
   ┌────────────────────────────────────────────────┐
   │              中间件层                           │
   │  MySQL(3306)  Redis(6379)  RocketMQ(9876)       │
   │  ES(19200)    Canal(11111) Nacos(18848)         │
   └────────────────────────────────────────────────┘

          ┌──── Feign 同步调用 (HTTP)
          │
          ═ ═ ═ MQ 异步事件 (RocketMQ)
```

---

## 二、Feign 调用矩阵

### 2.1 全量调用表 (16 个 @FeignClient)

| 调用方 | 被调服务 | 客户端类 | 降级 | InternalCall |
|:-------|---------|---------|:--:|:--:|
| **home** (BFF) | analytics | AnalyticsFeignClient | ✅ fallbackFactory | — |
| home | cart | CartFeignClient | ✅ | — |
| home | content | ContentFeignClient | ✅ | — |
| home | counter | CounterFeignClient | ✅ | — |
| home | coupon | CouponFeignClient | ✅ | — |
| home | inventory | InventoryFeignClient | ✅ | — |
| home | notification | NotificationFeignClient | ✅ | — |
| home | product | ProductFeignClient | ✅ | — |
| home | user | UserFeignClient | ✅ | — |
| **order** | coupon | CouponFeignClient | ✅ | ✅ |
| order | inventory | InventoryFeignClient | ✅ | ✅ |
| order | payment | PaymentFeignClient | ✅ | ✅ |
| order | product | ProductFeignClient | ✅ | ✅ |
| **cart** | product | ProductFeignClient | ✅ | ✅ |
| **payment** | order | OrderFeignClient | ✅ | ✅ |
| **search** | product | ProductFeignClient | ❌ 无降级 | — |

**关键发现**:
- search 的 ProductFeignClient 是唯一无降级的 Feign 调用 — product 不可用时 ES 索引同步直接报错
- 3 个服务有独立 `InternalCallFeignConfig` (order/cart/payment)，与全局 `FeignInternalCallInterceptor` 默认值一致 (`my-xhs-internal-token-2026`)，配置可省略但保留以显式声明内部调用

### 2.2 调用依赖图（出度）

```
home (BFF) ──9路──→ user, content, analytics, counter, product,
                    cart, inventory, coupon, notification

order     ──4路──→ product, inventory, coupon, payment

payment   ──1路──→ order   (回调)
cart      ──1路──→ product
search    ──1路──→ product
```

### 2.3 被调用依赖图（入度）

```
product   ←── home, order, cart, search  (4路)
inventory ←── home, order                (2路)
coupon    ←── home, order                (2路)
order     ←── payment                    (1路回调)
analytics ←── home                       (1路)
cart      ←── home                       (1路)
content   ←── home                       (1路)
counter   ←── home                       (1路)
notification ←── home                    (1路)
user      ←── home                       (1路)
```

### 2.4 Feign 配置共享层 (my-xhs-common)

| 配置类 | 作用 | 生效范围 |
|--------|------|---------|
| `FeignSafeConfig` | 全局关闭重试 (`Retryer.NEVER_RETRY`) + 自定义 ErrorDecoder | 所有服务 `@ComponentScan` |
| `FeignInternalCallInterceptor` | 自动注入 `X-Internal-Call` 头 | 全局 `@Component` |
| `FeignTraceInterceptorConfig` | 透传 6 个染色头 (Trace-Id/User-Id/Gray/Version/AB/Pressure) | 全局 |
| `LeastConnectionsLoadBalancer` | 最少活跃请求数 + 60s 预热 + Nacos 权重 | 替换默认轮询 |

**超时配置差异**（需统一）:
- home/order/cart/payment: `feign.client.config.default` connect=3000/read=3000~5000
- 其他服务: `spring.cloud.openfeign.client.config.default` connect=500/read=2000

---

## 三、MQ Topic 全景映射

### 3.1 Topic → 生产者 → 消费者矩阵

| Topic | 生产者 | 消费者 (ConsumerGroup) | 发送方式 |
|-------|--------|------|:--:|
| `SOCIAL_TOPIC` | LikeService/FavoriteService/FollowService/CommentService/NoteService | LikeUnlikeConsumer + FavoriteUnlikeConsumer + FollowConsumer(禁用) + UnfollowConsumer(禁用) + CounterEventConsumer + NoteDeleteConsumer | syncSend/asyncSend |
| `CACHE_EVICT_TOPIC` | CacheHelper (common) | CacheEvictConsumer (user) | syncSend |
| `CART_TOPIC` | CartService | CartSyncConsumer (cart) | asyncSend |
| `COUPON_CLAIM_TOPIC` | CouponService + CouponOutboxSenderJob | CouponClaimConsumer (coupon) | syncSend+Outbox |
| `FEED_TOPIC` | NoteService + FeedMessageRetryJob | FeedPushConsumer (home) | asyncSend+本地消息表 |
| `INVENTORY_TOPIC` | InventoryService + InventoryOutboxSenderJob + PreDeductTimeoutJob | InventoryDeductConsumer (inventory) | syncSend+Outbox/asyncSend |
| `INVENTORY_CACHE_TOPIC` | Canal inventory_instance | InventoryCacheEvictConsumer (inventory) | Binlog→MQ |
| `NOTIFICATION_TOPIC` | CommentService 等 | NotificationEventConsumer (notification) | asyncSend |
| `NOTE_INDEX_TOPIC` | Canal note_instance | NoteIndexSyncConsumer (search) | Binlog→MQ |
| `ORDER_TRANSACTION_TOPIC` | OrderService (事务消息) | OrderTransactionConsumer (inventory) | sendMessageInTransaction |
| `ORDER_CLOSE_TOPIC` | OrderService | OrderCloseConsumer (order) | syncSend 延时30min |
| `ORDER_COMPENSATION_TOPIC` | OrderService | OrderCompensationConsumer (order) | syncSend |
| `PAY_RESULT_TOPIC` | PaymentService | PayResultConsumer (payment) | syncSend |
| `PRODUCT_INDEX_TOPIC` | Canal product_instance | ProductIndexSyncConsumer (search) | Binlog→MQ |
| `RECOMMEND_BEHAVIOR_TOPIC` | RecommendService | BehaviorReportConsumer (search) | convertAndSend |
| `REFUND_RESULT_TOPIC` | PaymentService | RefundResultConsumer (payment) | syncSend |

### 3.2 服务 MQ 参与度

| 服务 | 生产 Topic 数 | 消费 Topic 数 | 说明 |
|------|:--:|:--:|------|
| search | 1 | 3 | Canal索引×2 + 行为上报 |
| analytics | 3 (social) | 4 (social) | 社交事件全链路 |
| inventory | 1 + Canal | 3 | 库存+L2异步+Canal |
| order | 3 | 2 | 事务消息+延时+补偿 |
| payment | 2 | 2 | 支付+退款 |
| home | 0 | 2 | 纯消费 (Feed+NoteDelete) |
| content | 3 | 0 | 纯生产 (Feed+Social+Notif) |
| coupon | 1 | 1 | Outbox 模式 |
| cart | 1 | 1 | Redis权威+MQ落库 |
| counter | 0 | 1 | 社交计数聚合 |
| notification | 0 | 1 | 通知消费 |
| user | 0 | 1 | 缓存双删兜底 |
| **gateway/im** | 0 | 0 | 无MQ参与 |

---

## 四、Gateway 路由表 (15 条)

| # | route id | Path 谓词 | 目标服务 | timeout(ms) | qps |
|---|----------|-----------|---------|:--:|:--:|
| 1 | user-service | `/api/user/**` | my-xhs-user | 5000 | 50 |
| 2 | content-service | `/api/content/**,/api/note/**,/api/comment/**,/api/topic/**` | my-xhs-content | 3000 | 200 |
| 3 | search-service | `/api/search/**` | my-xhs-search | 2000 | 300 |
| 4 | order-service | `/api/order/**` | my-xhs-order | 8000 | 10 |
| 5 | payment-service | `/api/payment/**` | my-xhs-payment | 10000 | 5 |
| 6 | inventory-service | `/api/inventory/**` | my-xhs-inventory | 3000 | 30 |
| 7 | analytics-service | `/api/analytics/**,/api/social/**` | my-xhs-analytics | 3000 | 100 |
| 8 | counter-service | `/api/counter/**` | my-xhs-counter | 3000 | 100 |
| 9 | product-service | `/api/product/**` | my-xhs-product | 3000 | 100 |
| 10 | cart-service | `/api/cart/**` | my-xhs-cart | 3000 | 50 |
| 11 | coupon-service | `/api/coupon/**` | my-xhs-coupon | 3000 | 30 |
| 12 | home-service | `/api/home/**` | my-xhs-home | 5000 | 50 |
| 13 | notification-service | `/api/notification/**` | my-xhs-notification | 3000 | 50 |
| 14 | im-service | `/api/im/**` | my-xhs-im | 5000 | 100 |
| 15 | recommend-service | `/api/recommend/**` | my-xhs-search | 3000 | 100 |

**全局默认**: connect-timeout=2000ms, response-timeout=10s

---

## 五、启动依赖顺序

微服务启动分 5 批次 (start-all.sh)，每批并行启动，批间 wait+2s 间隔：

```
批次 1 (核心基础):       user, content, analytics, counter
     ↓ wait + sleep 2s
批次 2 (业务服务):       product, cart, inventory(HEAVY 1G), coupon
     ↓ wait + sleep 2s
批次 3 (交易链路):       order(HEAVY 1G), payment
     ↓ wait + sleep 2s
批次 4 (辅助服务):       notification, im, home, search(HEAVY 1G)
     ↓ wait + sleep 2s
批次 5 (Gateway 最后):   gateway(LIGHT 256M)
```

**中间件必须先于微服务启动**: MySQL → Nacos/XXL-Job/Canal → RocketMQ → Redis → ES → SkyWalking/ELK

### 各服务 JVM 规格

| 类型 | 服务 | -Xms | -Xmx |
|------|------|:--:|:--:|
| LIGHT | gateway | 256m | 256m |
| BASE | user/content/analytics/counter/product/cart/coupon/payment/notification/im/home | 512m | 512m |
| HEAVY | order/inventory/search | 1024m | 1024m |

所有服务统一: `-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:MaxMetaspaceSize=256m`

---

## 六、跨服务数据流（七链全景）

### 链1: user（用户注册/登录/认证）
```
Client → Gateway(JWT验证) → user:19001
  ├── 注册: captcha(Redis GETDEL原子) → INSERT MySQL → JWT签发
  ├── 登录: SELECT MySQL → JWT签发 → Redis缓存token → Redis黑名单(登出)
  └── 缓存: 延迟双删 L1(DB更新后同步删) + L2(500ms后二次删) + L3(MQ兜底)
```

### 链2: product（商品创建/查询）
```
Client → Gateway → product:19006
  ├── SPU/SKU 创建: INSERT MySQL → Canal Binlog → RocketMQ → ES索引同步(search)
  ├── 查询: Redis布隆过滤器 → Cache Miss → MySQL → 异步写回Redis(逻辑过期)
  └── 布隆降级: Redis不可用 → 布隆降级放行 → 直查MySQL
```

### 链3: cart（购物车）
```
Client → Gateway → cart:19008
  ├── 操作: Redis SADD/HSET/ZADD → asyncSend CART_TOPIC → CartSyncConsumer → MySQL
  ├── 查询: Redis HMGET → Feign product(补全SKU信息)
  └── 对账: CartReconcileJob → Redis ↔ MySQL 差异修复
```

### 链4: coupon（领券/用券）
```
Client → Gateway → coupon:19010
  ├── 领券: 乐观锁扣库存(t_coupon_template) → Outbox(t_coupon_outbox)
  │        → syncSend COUPON_CLAIM_TOPIC → CouponClaimConsumer → INSERT t_user_coupon
  ├── 用券: Feign order → order调coupon/discount → Lua原子扣减
  └── 兜底: CouponOutboxSenderJob 5s扫描补发
```

### 链5: order（下单/关单/退款）
```
Client → Gateway → order:19011
  ├── 下单: sendMessageInTransaction ORDER_TRANSACTION_TOPIC
  │        → executeLocalTransaction(MySQL本地事务: 订单+明细+本地消息表)
  │        → checkLocalTransaction(回查) → COMMIT/ROLLBACK
  │        → OrderTransactionConsumer(inventory) 预扣库存
  │        → syncSend ORDER_CLOSE_TOPIC(delayLevel=16, 30min延时)
  ├── 支付回调: payment → Feign order(pay-success) → 更新订单状态
  ├── 关单补偿: Feign失败 → syncSend ORDER_COMPENSATION_TOPIC → OrderCompensationConsumer
  └── 本地消息补发: LocalMessageRetryJob 30s指数退避扫t_local_message
```

### 链6: content-social（笔记+社交）
```
Client → Gateway → content:19002 / analytics:19003
  ├── 发表笔记: INSERT t_note → afterCommit asyncSend FEED_TOPIC
  │            → FeedPushConsumer(home) Pipeline ZADD 到关注者inbox
  │            → Canal Binlog → NOTE_INDEX_TOPIC → ES note_index
  ├── 点赞: analytics INSERT t_like → syncSend SOCIAL_TOPIC:LIKE
  │       → LikeUnlikeConsumer(Lua版本号防乱序) + CounterEventConsumer(计数)
  ├── 收藏: 同点赞机制(favorite版本key)
  └── 笔记删除: NoteService → SOCIAL_TOPIC:NOTE_DELETE
               → NoteDeleteConsumer(清理outbox) + IncrementalIndexSyncJob(ES标记删除)
```

### 链7: notification + im（通知+即时通讯）
```
Client → Gateway → notification:19013 / im:19014
  ├── SSE推送: POST /api/notification/sse/ticket → 取ticket(30s) → EventSource /api/notification/sse 建连
  │           → CommentService asyncSend NOTIFICATION_TOPIC
  │           → NotificationEventConsumer(幂等removeMark后throw)
  │           → Redis Pub/Sub channel: myxhs:notification:sse:channel → SSE push
  ├── IM WebSocket: POST /api/im/ws/ticket → 取ticket(JWT 5min) → WS ws://host/api/im/ws 建连
  │                → 一致性哈希负载均衡 → Redis存储消息
  └── 聚合窗口: t_notification 去重 → 动态窗口(当天剩余秒)
```
