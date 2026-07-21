# my-xhs 项目终极 AI Code Review Prompt（V3 — P8 深度最终版）

> 基于 CodeGraph（479 文件 / 8,970 节点 / 13,684 边）+ Sverklo（657 文件 / 7,819 chunks / 37,279 引用 / 评级 A）双引擎全量分析生成。
> 对比 V2 新增：已完成修复验证矩阵、架构改造总结（M2/M4/M8/M9）、剩余风险点清单、双引擎 MCP 工具调用指南。
> 生成时间：2026-06-04 | Node.js 24.16.0 | CodeGraph 0.9.9 | Sverklo 0.29.0

---

## 一、项目全貌快照

### 1.1 模块清单（16 个 Java 模块 + test）

| 模块 | 文件数 | 端口 | 核心职责 | 关键改动 |
|------|--------|------|---------|---------|
| my-xhs-common | 63 | — | AOP 切面/CacheHelper/ID 生成/链路/GracefulShutdownHook | ✅ M5 优雅停机 |
| my-xhs-gateway | 11 | 19000 | 7 Filter 链（Auth/HMAC/染色/限流/灰度/版本） | ✅ C3 IP 校验 / M11 溢出 |
| my-xhs-user | 26 | 19001 | 注册/登录/双Token/BCrypt/验证码/地址 | ✅ M13 改密注销 / m15/m16 |
| my-xhs-content | 26 | 19002 | 笔记 CRUD/评论树形/DFA 敏感词/状态机 | ✅ C1/C2 MQ afterCommit / M2 本地消息表 / m14/m17 |
| my-xhs-analytics | 23 | 19003 | 关注/点赞/收藏（Lua 原子+MQ 异步） | ✅ M6 Lua 拆分 / M16 favorite_atomic.lua |
| my-xhs-counter | 15 | 19004 | ConcurrentHashMap 双 Buffer → 定时 batch upsert | ✅ m11 RedisScript 静态化 / m20 Pipeline 对账 |
| my-xhs-product | 22 | 19006 | SPU/SKU/分类（三级缓存+布隆+逻辑过期+MQ 广播） | ✅ m12 线程池 |
| my-xhs-cart | 17 | 19008 | Redis 三结构+3 Lua+Pipeline+MQ 异步+对账 | ✅ M1 hashtag 统一 |
| my-xhs-inventory | 18 | 19009 | Redis 分桶预扣+MQ 异步扣 DB+超时释放+Canal 失效+对账+热检测+动态扩容 | ✅ M7/M12/M14/M15/M9 |
| my-xhs-coupon | 20 | 19010 | Lua 原子领取/责任链校验/MQ 落库/退券/过期回收 | ✅ C6 幂等 / M15 hashtag |
| my-xhs-order | 36 | 19011 | 事务消息+本地消息表+状态机+分库分表+超时关单+映射表 | — |
| my-xhs-payment | 26 | 19012 | 策略模式 Mock/Alipay/WeChat+回调+退款+补偿 | — |
| my-xhs-notification | 19 | 19013 | SSE 长连接+MQ 消费+5min 窗口聚合+未读计数+跨实例 Pub/Sub+对账 | ✅ C7 SSE / M17 Pub/Sub / M20 |
| my-xhs-im | 18 | 19014 | WebSocket+ticket 鉴权+Pub/Sub 路由+离线 ZSet+一致性 Hash LB+seqNo 保序 | ✅ M4 Pub/Sub / M8 / M19 / M25 |
| my-xhs-home | 38 | 19015 | Feed 推拉+双线程池并行 Feign+5 种详情聚合+MDC 透传 | ✅ M2 Pipeline / m22 |
| my-xhs-search | 33 | 19016 | ES 全文+Canal 同步+热搜反作弊+Completion 建议+多路召回推荐 | ✅ M18 / C8 ES 安全 |
| my-xhs-test | — | — | 测试模块 | — |

### 1.2 技术栈详情

| 类别 | 技术选型 | 版本 | 备注 |
|------|---------|------|------|
| 语言 | Java | 17 | Jakarta EE |
| 框架 | Spring Boot | 3.2.5 | — |
| 微服务 | Spring Cloud + Spring Cloud Alibaba | 2023.0.1 / 2023.0.1.0 | — |
| 注册/配置中心 | Nacos | 2.3.2 | 当前仅服务发现 |
| API 网关 | Spring Cloud Gateway (WebFlux) | — | 响应式 |
| 服务调用 | OpenFeign + HC5 | — | 含 FallbackFactory |
| 熔断限流 | Sentinel（规则持久化 Nacos） | 1.8.8 | — |
| 数据库 | MySQL 8.0（4 实例） | 8.0 | max-connections: 200~500 |
| ORM | MyBatis-Plus (Spring Boot 3) | 3.5.7 | LambdaWrapper |
| 分库分表 | ShardingSphere-JDBC | 5.5.1 | 4 库 × 4 表 = 16 分片 |
| 缓存 | Redis 7 (Lettuce + Redisson 3.27) | 7-alpine | 单节点, 256MB |
| 本地缓存 | Caffeine | 3.1.8 | 商品/分类/Feed |
| 消息队列 | RocketMQ | 5.1.4 | 异步刷盘, 48h 保留 |
| 搜索引擎 | Elasticsearch | 8.12.2 | IK 分词, 3 分片 1 副本, xpack.security 已启用 |
| Binlog 同步 | Canal | 1.1.7 | 3 个 instance |
| 分布式调度 | XXL-Job | 2.4.2 | — |
| ID 生成 | 号段模式（双 Buffer）+ 雪花(CosId 2.6.8) | — | step: 1000~5000 |
| 认证 | JWT (jjwt 0.12.3) + BCrypt | — | Access 30min + Refresh 7d |
| 监控 | Prometheus + Grafana + Micrometer | v2.48.1 / 10.2.3 | 10s 采集 |
| 链路追踪 | SkyWalking | 9.7.0 | OAP 存 ES |
| WebSocket | Spring WebSocket (Tomcat NIO) | — | 非 WebFlux |
| 工具库 | Hutool / Guava / MapStruct / Lombok / TTL(2.14.5) | — | — |

### 1.3 数据库架构

```
mysql-user:13306    → nacos_config, xxl_job, my_xhs_user, my_xhs_analytics, my_xhs_notification, my_xhs_im
mysql-content:13307 → my_xhs_content, my_xhs_counter, my_xhs_search, my_xhs_product, my_xhs_cart, my_xhs_coupon
mysql-order:13308   → my_xhs_order(映射表), my_xhs_payment, my_xhs_order_0~3(4 分片 × 4 分表)
mysql-inventory:13309 → my_xhs_inventory
```

### 1.4 架构拓扑图

```
[客户端] → [Gateway:19000 (7 Filter)] → [BFF:19015 (双线程池并行 Feign)]
    → [社交域: user/content/analytics/counter]
    → [电商域: product/cart/inventory/coupon/order/payment]
    → [基础域: search/im/notification]
    → [基础设施: MySQL×4 / Redis / RocketMQ / ES / Canal / XXL-Job / Nacos / SkyWalking / Prometheus]
```

### 1.5 Lua 脚本清单（16 个）

| 模块 | 文件 | 功能 | KEYS | 改动 |
|------|------|------|------|------|
| cart | `cart_add.lua` | 加购：检查上限+HINCRBY+截断+SADD+ZADD | 3 | M1 hashtag |
| cart | `cart_remove.lua` | 删除：HEXISTS→HDEL+SREM+ZREM | 3 | M1 |
| cart | `cart_check_all.lua` | 全选/取消：原子重建 checked Set | 2 | M1 |
| analytics | `follow_self.lua` | 关注(自身侧)：ZSCORE→ZADD+INCR | 2 | **M6 拆分** |
| analytics | `follow_target.lua` | 关注(目标侧)：ZSCORE→ZADD+INCR | 2 | **M6 拆分** |
| analytics | `unfollow_self.lua` | 取关(自身侧)：ZSCORE→ZREM+DECR(防负数) | 2 | **M6 拆分 + m23** |
| analytics | `unfollow_target.lua` | 取关(目标侧)：ZSCORE→ZREM+DECR(防负数) | 2 | **M6 拆分 + m23** |
| analytics | `favorite_atomic.lua` | 收藏：ZSCORE→ZADD | 1 | **M16 新增** |
| analytics | `like_atomic.lua` | 点赞：SADD 正向+条件 SADD 反向 | 2 | 未改 |
| analytics | `unlike_atomic.lua` | 取消点赞：SREM 正向+条件 SREM 反向 | 2 | 未改 |
| inventory | `prededuct.lua` | 分桶预扣：KEYS[3..N+2] 桶Key | 2+N | **M14 修复** |
| inventory | `release.lua` | 释放：KEYS[3] 桶Key → INCRBY | 3 | **M14 修复** |
| inventory | `confirm.lua` | 确认：HGET→HDEL | 1 | 未改 |
| coupon | `claim_coupon.lua` | 领券：库存检→限领检→DECR+INCR | 2 | M15 hashtag |
| coupon | `return_coupon.lua` | 退券：INCR 库存+DECR 领取次数 | 2 | M15 hashtag |

### 1.6 代码分析热点（Sverklo + CodeGraph 联合）

| 符号 | 引用数 | 影响文件 | 风险等级 | 验证方法 |
|------|--------|---------|---------|---------|
| `BizException` | 254 | 48 | 🔴 极高 | `codegraph impact BizException` |
| `onMessage` | 61 | 39 | 🟡 中 | `codegraph callers onMessage` |
| `Order` | 62 | 23 | 🟡 中 | `codegraph callers Order` |
| `RecallItem` | 64 | 8 | 🟢 低 | `codegraph callers RecallItem` |
| `createOrder` | 41 | 12 | 🟡 中 | `codegraph callers createOrder` |

### 1.7 MQ Topic 清单（16 个）

| Topic | 模式 | 用途 |
|-------|------|------|
| FEED_TOPIC | 普通 | 笔记发布→Feed 推送（M2 本地消息表兜底） |
| NOTIFICATION_TOPIC | 普通 | 各类通知事件 |
| SOCIAL_TOPIC | 普通+tag | 点赞/收藏异步落库 |
| CART_TOPIC | 普通 | 购物车异步持久化 |
| CACHE_EVICT_TOPIC | 普通+tag | 缓存删除 MQ 兜底 |
| INVENTORY_DEDUCT_TOPIC | 普通 | 库存异步扣 MySQL |
| INVENTORY_CACHE_TOPIC | 普通(Canal) | 库存 Binlog→缓存失效 |
| ORDER_TRANSACTION_TOPIC | 事务消息 | 订单创建 |
| ORDER_CLOSE_TOPIC | 延时(level=16=30min) | 超时关单 |
| COUPON_CLAIM_TOPIC | 普通 | 领券异步落库 |
| SPU_CACHE_EVICT_TOPIC | 广播 | SPU 变更→Caffeine 失效 |
| ~~IM_ROUTE_TOPIC~~ | ~~广播~~ | ~~已移除（M4 改为 Redis Pub/Sub）~~ |
| REFUND_RESULT_TOPIC | 普通 | 退款结果通知 |
| NOTE_INDEX_TOPIC | 普通(Canal) | 笔记 Binlog→ES 同步 |
| PRODUCT_INDEX_TOPIC | 普通(Canal) | 商品 Binlog→ES 同步 |

### 1.8 补偿任务清单（12 个）

| 任务 | 模块 | 频率 | 功能 |
|------|------|------|------|
| LocalMessageRetryJob | order | 30s | 重发本地消息表 |
| **FeedMessageRetryJob** | **content** | **30s @Scheduled** | **M2 新增：Feed 消息补发** |
| OrderCloseJob | order | 1min | 兜底关闭超时订单 |
| PaymentNotifyCompensateJob | payment | 2min | 支付成功通知补偿 |
| RefundNotifyCompensateJob | payment | 3min | 退款成功通知补偿 |
| RefundTimeoutCheckJob | payment | 1min | 退款超时标记关闭 |
| PreDeductTimeoutJob | inventory | 5min | 超时释放未确认预扣 |
| InventoryReconcileJob | inventory | 每天 3 点 | Redis↔MySQL 库存对账（含 M9 分桶完整性） |
| CartReconcileJob | cart | 每天 4 点 | Redis↔MySQL 购物车对账 |
| CounterReconcileJob | counter | 每天 3 点 | Redis↔MySQL 计数对账（含 m20 Pipeline） |
| CouponExpireJob | coupon | 每小时 | 过期优惠券状态标记 |
| FeedCleanupJob | home | 每天 3 点 | 清理 7 天前 Feed 数据 + **M2 收件箱裁剪** |

### 1.9 Gateway Filter 执行链

```
HIGHEST_PRECEDENCE + 100   → RequestLogFilter
HIGHEST_PRECEDENCE + 1000  → GatewayAuthFilter（白名单 + JWT + 黑名单 Fail-Closed）
HIGHEST_PRECEDENCE + 1200  → TrafficColoringFilter（C3 修复：X-Forwarded-For）
HIGHEST_PRECEDENCE + 1500  → HmacSignatureFilter（MessageDigest.isEqual()）
HIGHEST_PRECEDENCE + 2500  → RateLimitFilter（Sentinel + Nacos）
HIGHEST_PRECEDENCE + 3000  → GrayRouteFilter（M11 修复：& 0x7FFFFFFF）
HIGHEST_PRECEDENCE + 3100  → ApiVersionFilter
```

---

## 二、Review 指令

你是一位拥有 10 年以上经验的 Java 微服务架构师兼代码安全专家。请基于本项目全量源码和双引擎分析数据，进行**最终完整性验证 Review**。

### 2.1 Review 原则

1. 每项结论必须有**具体文件路径和行号**
2. 不做泛泛而谈——每个问题给出**可落地的修复代码**
3. 区分严重程度：**Critical / Major / Minor / Info**
4. 使用 CodeGraph **验证调用链**——`codegraph callers/callees/impact`
5. 使用 Sverklo **验证整体质量**——`sverklo audit/review/receipt`

### 2.2 验证步骤（严格按顺序执行）

```
Step 1 — 全量编译
  → mvn clean compile -T 4
  → 记录 BUILD 结果和所有 WARNING

Step 2 — 双引擎索引同步
  → codegraph sync（增量）
  → sverklo reindex（增量）

Step 3 — 全局健康检查
  → sverklo audit（获取当前评级 + 孤儿符号 + 耦合度）
  → codegraph status（确认索引完整性）

Step 4 — 高影响符号分

析（每个符号执行 codegraph impact）
  → BizException（254 引用 / 48 文件）
  → generateConversationId（M19 修复后）
  → saveMessageWithTransaction（M8 seqNo 后）
  → preDeduct（M14 + M9 后）
  → resizeBuckets（M9 新增）
  → storeOfflineMessage（M25 修复后）

Step 5 — 交叉调用链完整性
  → codegraph callers "publishNote"（M2 本地消息表流水线）
  → codegraph callers "storeOfflineMessage"（M4/M25 调用者）
  → codegraph callers "onMessage"（12 个 MQ 消费者一致性）
  → codegraph callees "handleChat"（M4/M8 数据流）

Step 6 — 变更审查
  → sverklo review --ref HEAD~20..HEAD（审查全部修改的爆炸半径 + 风险评分）

Step 7 — Key 格式一致性矩阵（手工+CodeGraph）
  → 搜索全项目 Key 前缀引用，列出不一致项

Step 8 — Lua ↔ Java 参数对齐
  → 逐个验证 16 个 Lua 脚本的 KEYS 数量 vs Java 调用方传入的 KEYS 数量

Step 9 — Token 消耗报告
  → sverklo receipt
```

---

## 三、Review 维度（12 个，完整版）

### 维度 1：架构设计与模块划分 — 评分目标 8.5/10

**1.1 微服务拆分粒度**
- Counter 模块仅 1 Service + 1 Job + 1 Consumer（15 文件），是否过度拆分？
- Home(BFF) 承载 Feed 推拉+5 种聚合+双线程池（38 文件），是否过重？
- analytics(关系数据) vs counter(聚合计数) 通过 MQ 间接交互——计数漂移风险？

**1.2 通信模式选择**
- ✅ IM 跨实例从 BROADCASTING → Redis Pub/Sub 定向路由（M4）
- ✅ Feed 推拉阈值硬编码在 FeedPushConsumer，是否应动态调整？
- ✅ 库存预扣同步 Feign（下单关键路径），Feed 推送异步 MQ（非关键）
- ⚠️ 购物车 Feign 超时 500ms/2000ms，与全局 5s 不一致

**1.3 数据库拆分策略**
- ✅ 订单分片 `user_id%4` + `(user_id/4)%4`，t_order_no_mapping 解决非分片键查询
- ⚠️ 商家视角/运营后台查询无方案（需 ES 宽表或 CQRS）
- ✅ 库存独立实例（高写争用隔离）
- ⚠️ 13306 承载 6 个库（user/analytics/notification/im/nacos/xxl_job）

**1.4 ID 策略**
- 号段模式：7 个业务各独立号段，双 Buffer+乐观锁
- 雪花 ID（CosId）：worker-id = `(ip[2]*256+ip[3])%1024`，同子网多实例可能冲突
- ShardingSphere 自带 SNOWFLAKE 策略

### 维度 2：公共模块设计（my-xhs-common，63 个类） — 评分目标 9/10

**2.1 AOP 切面体系**

| 切面 | Order | 降级策略 | 审查重点 |
|------|-------|---------|---------|
| RateLimitAspect | 10 | Redis 不可用→放行 | 滑动窗口 ZSet+Lua |
| DistributedLockAspect | 50 | Redisson 失败→放行 | RLock+Watchdog |
| IdempotentAspect | 100 | Redis 不可用→放行 | SETNX + 异常分类决策 |
| SqlGuardInterceptor | MyBatis 层 | 熔断后仍放行 | ConcurrentHashMap 指纹统计 |

**2.2 IdempotentAspect 异常分类（亮点）**
- `BizException / IllegalArgumentException` → 删除幂等标记（业务未执行）
- `TimeoutException / SocketTimeoutException` → 保留标记（业务可能已执行）
- 递归检查 cause 链

**2.3 CacheHelper 三重保障**
- Cache Aside + 延迟双删(500ms, ScheduledExecutor) + MQ 兜底(CACHE_EVICT_TOPIC, 16 次重试)
- 防穿透：空值缓存 2min（`\u0000__CACHE_NULL__\u0000`）
- 防雪崩：TTL 随机偏移 ±1/6
- 防击穿：Redisson tryLock Singleflight
- ⚠️ 500ms 覆盖主从延迟？MQ 也不可用时？ScheduledExecutor 单线程？

**2.4 ID 生成器**
- 号段模式：双 Buffer+乐观锁，70% 阈值异步预加载
- ⚠️ DB 不可用时 buffer 耗尽→抛异常无降级

**2.5 GracefulShutdownListener（M5 修复后）**
- ✅ Nacos 注销 → 等待传播 → GracefulShutdownHook 回调 → 关闭线程池
- ⚠️ Nacos 注销通过反射调用（`nacosServiceRegistry.deregister`），异常不阻塞

### 维度 3：网关安全设计（7 个 Filter） — 评分目标 8/10

| Filter | Order | 核心逻辑 | 审查重点 |
|--------|-------|---------|---------|
| RequestLogFilter | +100 | TraceId 生成+透传+出入站日志 | — |
| GatewayAuthFilter | +1000 | 白名单 AntPathMatcher + JWT 解析 + Token 黑名单 Fail-Closed | 密钥与 User 服务共享 |
| TrafficColoringFilter | +1200 | 压测/灰度/AB/版本标记（✅ C3 修复：X-Forwarded-For） | — |
| HmacSignatureFilter | +1500 | HMAC-SHA256 + MessageDigest.isEqual() + Nonce SETNX | 300s 窗口过宽 |
| RateLimitFilter | +2500 | Sentinel + 本地兜底 + Nacos 推送 | — |
| GrayRouteFilter | +3000 | ✅ M11 修复：`hashCode & 0x7FFFFFFF` 替代 `Math.abs()` | — |
| ApiVersionFilter | +3100 | 版本路由 metadata 匹配 | — |

**重点验证**：
- TrafficColoringFilter.getClientIp() 优先 X-Forwarded-For > X-Real-IP > remoteAddress（C3 修复后）
- GrayRouteFilter 第 68 行：`(hashCode & 0x7FFFFFFF) % 100`（M11 修复后）
- HMAC Redis 异常时 Fail-Open 与 Auth Fail-Closed 不一致

### 维度 4：数据一致性 — 评分目标 8.5/10

**4.1 订单事务消息流程**
```
幂等 SETNX(24h) → 用户锁 SETNX(10s) → 计算金额
  → sendMessageInTransaction(ORDER_TRANSACTION_TOPIC)
  → executeLocalTransaction(@Transactional: INSERT order+item+localMessage)
  → checkLocalTransaction: 查 localMessage
→ 延时关单消息(delayLevel=16=30min) → 记录快照 → 异步写映射表
```
- ✅ 本地消息表补偿：每 30s 扫描 + 60s 保护窗口 + 3 次死信

**4.2 Feed 发送可靠性（M2 修复后）**
```
NoteService.publishNote()
  → @Transactional: INSERT note + INSERT t_local_message
  → afterCommit: asyncSend("FEED_TOPIC")
    → onSuccess: markSent(localMsgId)
    → onException: 等 FeedMessageRetryJob 30s 后重试

FeedMessageRetryJob（@Scheduled 30s）
  → SELECT status=0 AND retry_count<3 AND created_at<NOW()-60s
  → syncSend → markSent / incrementRetry
  → retry_count+1 >= 3 → status=3(死信)
```
- ✅ incrementRetry 使用 `CASE WHEN retry_count + 1 >= #{maxRetry}`（off-by-one 已修复）

**4.3 库存三级扣减（M9 + M14 修复后）**
```
L1: prededuct.lua（KEYS[3..N+2] 桶Key → 检查暂停标记 → 热点检测触发扩容）
L2: MQ 异步扣 MySQL（乐观锁 WHERE available>=qty，M7 退避重试 3 次）
L3: 对账（每天 3 点：total 一致性 + M9 分桶完整性）
```

**4.4 IM 消息保序（M8 修复后）**
```
handleChat: seqNo = INCR("im:seq:" + conversationId)
  → saveMessageWithTransaction(seqNo)
  → push JSON 含 seqNo
  → RouteMessage 含 seqNo（跨实例 Pub/Sub）
  → ImRouteSubscriber 推送 JSON 含 seqNo
pushOfflineMessages: messages.sort(Comparator.comparing(ChatMessage::getSeqNo, nullsLast))
getMessageHistory: orderByDesc(ChatMessage::getSeqNo)
```

**4.5 缓存一致性策略对比（当前状态）**

| 模块 | 策略 | 时序 | 兜底 | 备注 |
|------|------|------|------|------|
| user/content | CacheHelper(afterCommit+延迟双删 500ms) | 事务后 ✅ | MQ(16 次重试) | 标准方案 |
| product(SPU) | 更新 DB→删 Redis→MQ 广播删 Caffeine→TTL 5min | 非 afterCommit | Caffeine TTL | 逻辑过期防击穿 |
| inventory | Canal Binlog→MQ→版本号防乱序删 Redis | 版本递增 | 下次读回填 | M9 热点监控 |
| cart | Redis 为准+MQ 异步+对账 | — | 对账 Job | Redis=权威 |
| counter | Redis INCR 实时+Buffer 攒批 MySQL | — | 对账 Job（m20 Pipeline） | Redis=权威 |
| notification | DB 为准+Redis 缓存未读数 | — | 对账 Job(DB 为准) | DB=权威 |

### 维度 5：高可用与容错 — 评分目标 8/10

**5.1 Feign 降级策略**

| 模块 | 降级方式 | 策略 |
|------|---------|------|
| 商品查询 | FallbackFactory | 返回 null/默认值（展示降级） |
| 用户查询 | FallbackFactory | 返回 null |
| 计数查询 | FallbackFactory | 返回 0 |
| 库存预扣 | FallbackFactory | 抛 RemoteException（关键路径） |
| 优惠券核销 | FallbackFactory | 抛 RemoteException |
| 关单→释放库存 | FallbackFactory | 抛 RemoteException→兜底 Job |

**5.2 MQ 可靠性**
- ✅ Feed 推送：M2 本地消息表 + FeedMessageRetryJob 30s 补偿
- ✅ 库存/优惠券：MQ 失败时回滚 Lua 操作
- ✅ 点赞：Redis 回滚补偿（unlike_atomic.lua）

**5.3 Redis 故障降级矩阵**

| 组件 | 降级策略 | 影响 |
|------|---------|------|
| 限流 | 放行 | 无限流保护 |
| 分布式锁 | 放行 | 并发风险 |
| 幂等 | 放行 | 重复请求 |
| Token 黑名单 | **拒绝**（安全优先） | 所有请求 401 |
| HMAC Nonce | 放行 | 重放风险 |
| 购物车/库存 Lua | **抛 BizException** | 核心操作不降级 |

**5.4 优雅停机（M5 修复后）**
- ✅ ContextClosedEvent → Nacos 注销 → sleep(10s) 传播 → ShutdownHook 回调 → 线程池关闭
- ✅ CounterBuffer 实现 GracefulShutdownHook 保证刷盘
- ⚠️ Nacos 注销用反射，接口变化时有失效风险

### 维度 6：性能 — 评分目标 7.5/10

**6.1 本次改动的性能提升**

| 改动 | 旧方案 | 新方案 | 提升 |
|------|--------|--------|------|
| M4 IM 路由 | BROADCASTING N 次无效消费 | Redis Pub/Sub 定向 | N 倍 CPU 节省 |
| M2 Feed 推送 | N 次 EVALSHA | 1 次 Pipeline 500 粉丝 | ~100x Redis RTT |
| m20 Counter 对账 | N 次 GET | 1 次 multiGet | ~Nx Redis RTT |
| m19 共同关注 | 内存 HashSet 交集 10000 条 | ZINTER 服务端计算 | 内存 + 网络优化 |

**6.2 线程池设计**

| 模块 | 池名 | 核心/最大/队列 | 拒绝 | 用途 |
|------|------|---------------|------|------|
| home | aggregatorPool | 20/50/200 | CallerRunsPolicy | 外层编排 |
| home | batchFeignPool | 30/80/500 | CallerRunsPolicy | 内层 Feign |
| search | recallExecutor | 10/20/100 | CallerRunsPolicy | 5 路召回并行 |
| product | SPU_ASYNC_EXECUTOR | 2/8/100 | CallerRunsPolicy | m12 异步刷新 |
| inventory | inventoryAsyncExecutor | 2/4/50 | CallerRunsPolicy | M9 异步扩容 |
| common | CacheHelper.scheduler | 1(守护线程) | — | 延迟双删 |

**6.3 已知瓶颈**

| 瓶颈 | 位置 | 当前状态 |
|------|------|---------|
| Counter @Scheduled 单线程 | CounterBuffer | ⚠️ 刷盘耗时 > 5s 会延迟 |
| Feign read-timeout 5s vs 聚合 3s | home 模块 | ✅ m22 已对-align 到 3s |
| IM Hash 环缓存 | ImConsistentHashLoadBalancer | ✅ m13 实例变化时才重建 |
| CounterService 每次 new RedisScript | CounterService | ✅ m11 已提为 static final |
| SpuService 用 ForkJoinPool | SpuService | ✅ m12 已指定有界线程池 |

### 维度 7：安全性 — 评分目标 7/10

**7.1 认证体系**
- ✅ 密码 BCrypt | ✅ 登录锁定 5 次/15min
- ✅ 验证码：SecureRandom + 排除易混淆字符 + 一次性消费
- ✅ Token 刷新：分布式锁防并发 + 双重黑名单检查 + 单设备登录
- ✅ M13 修复：改密码后 `revokeAllTokens()` 注销所有活跃 Token

**7.2 已修复的安全问题**
- ✅ C4/C5：密码硬编码（用户选择不修）
- ✅ C8：ES xpack.security=true（docker-compose.yml + search yml）
- ✅ C3：压测标记 IP 用 X-Forwarded-For 获取
- ✅ M11：GrayRouteFilter `Math.abs()` 溢出
- ✅ m16：UserInfoResponse 手机号/邮箱脱敏

**7.3 仍存在的安全关注点**
- ⚠️ docker-compose.yml 密码明文（用户决定保留）
- ⚠️ 15 个 yml 密钥明文（用户决定保留）
- ⚠️ RocketMQ `autoCreateTopicEnable=true`
- ⚠️ WebSocket `allowed-origins: "*"`
- ⚠️ 管理接口（对账/索引重建）无权限保护
- ⚠️ 全部 MySQL 使用 root 账号

### 维度 8：代码质量 — 评分目标 8.5/10

**8.1 设计模式运用**
- ✅ 策略：支付(Mock/Alipay/WeChat) + 文件存储(Local/OSS) + 召回(5 种)
- ✅ 责任链：优惠券校验(3 个 Validator 按 @Order)
- ✅ 状态机：笔记(枚举+canTransitTo) + 订单(枚举+乐观锁)
- ✅ 观察者：Redis Pub/Sub(敏感词刷新/SSE 跨实例)
- ✅ 模板方法：AbstractSearchService

**8.2 已修复的代码气味**
- ✅ CounterService.decrement() 每次 new RedisScript → static final
- ✅ SpuService 异步刷新用 ForkJoinPool → 自定义线程池
- ✅ IM Hash 环每次重建 → 指纹比对缓存
- ✅ Counter 对账逐条 GET → multiGet 批量
- ✅ 共同关注内存交集 → ZINTER 服务端

### 维度 9：可观测性 — 评分目标 7.5/10

- ✅ 结构化 JSON 日志 + traceId
- ✅ Prometheus 4 组 18 条告警
- ✅ 死信消息 Prometheus 指标
- ⚠️ Alertmanager 未配置通知渠道
- ✅ XXL-Job 执行结果可查

### 维度 10：可测试性 — 评分目标 4/10

- POM 声明 Testcontainers，实际使用待确认
- ❌ 核心算法缺独立单测（DFA/号段/一致性 Hash/分桶 Lua）
- ❌ 无 API 端到端测试
- ❌ 无 Contract Test
- ❌ 无性能基准测试

### 维度 11：运维与部署 — 评分目标 6/10

**11.1 Docker Compose**
- ✅ ES 已启用 xpack.security
- ⚠️ 全 host 模式（端口隔离而非网络隔离）
- ✅ MySQL/Redis/ES/Broker/Canal 配置了健康检查
- ⚠️ Sentinel/Nacos/XXL-Job/Grafana/SkyWalking 缺健康检查
- ⚠️ 无 mem_limit/cpus 资源限制
- ⚠️ 部分服务无日志限制
- ⚠️ Nacos/RocketMQ/Redis 均单点无 HA

**11.2 配置管理**
- ⚠️ 15 个服务硬编码 IP `21.91.124.110`
- ⚠️ 无 CI/CD 脚本
- ⚠️ SQL 版本管理缺 Flyway/Liquibase

### 维度 12：业务逻辑完备性 — 评分目标 8/10

**12.1 已修复的业务 Bug**
- ✅ C6：优惠券唯一索引 vs perUserLimit → 双重幂等（Redis SETNX + DB uk_claim_no）
- ✅ M19：ConversationId 溢出 → `min * 31 + max` 替代 `(min<<32)|max`
- ✅ M20：Notification 聚合计数 → UPDATE 后 SELECT getAggregateCount
- ✅ M25：离线消息裁剪非原子 → Lua 脚本原子操作
- ✅ M16：收藏 ZSCORE+ZADD → favorite_atomic.lua 原子操作
- ✅ M17：SSE 跨实例 Pub/Sub 未 start() → 添加 container.start()
- ✅ M18：IndexRebuildJob 锁跨线程 → 锁移入异步线程

**12.2 关单 vs 支付竞态**
```
关单: SELECT WHERE status=0 → UPDATE SET status=4 WHERE status=0(乐观锁)
支付: UPDATE SET status=1 WHERE status=0(乐观锁)
✅ 乐观锁保证只有一方成功
```

**12.3 Feed 推拉混合完整链路（M2 修复后）**
```
NoteService.publishNote() → afterCommit → asyncSend("FEED_TOPIC") → onSuccess: markSent
FeedPushConsumer:
  → checkBigV(authorId): ZCARD > 100000 → 拉模式 / else → 推模式
  → 推模式: Pipeline 批量 ZADD(500 粉丝 1 次往返) + FeedCleanupJob 异步裁剪
  → 拉模式: ZADD outbox
用户浏览: FeedService → ZSet reverseRange + Pipeline 拉大V发件箱 + 合并排序
```

---

## 四、已修复 Bug 完整矩阵（供验证对照）

### Critical 修复验证

| # | 问题 | 位置 | 当前代码状态 | 验证方法 |
|---|------|------|-------------|---------|
| C1 | NoteService MQ 事务内发送 | `NoteService.java:93-121` | afterCommit + asyncSend | `codegraph callees publishNote` |
| C2 | CommentService MQ 事务内 | `CommentService.java:133-168` | afterCommit + asyncSend + m17 | `codegraph callees createComment` |
| C3 | 压测标记 IP 校验 | `TrafficColoringFilter.java:150-170` | getClientIp() X-Forwarded-For | 读代码 |
| C6 | 优惠券幂等冲突 | `CouponClaimConsumer.java:55-97` | SETNX + claimNo + DuplicateKeyException | 读代码 |
| C7 | SSE 旧连接误删 | `SseEmitterManager.java:86-101` | remove(userId, emitter) 双参数 | 读代码 |
| C8 | ES 安全 | `docker-compose.yml` ES 配置 | xpack.security=true | 读文件 |

### Major 修复验证

| # | 问题 | 位置 | 验证 |
|---|------|------|------|
| M1 | 购物车 hashtag | `CartService.java:76-83` | `codegraph search "myxhs:cart:{"` |
| M4 | IM Pub/Sub | `ChatService.java:140` / `ImRouteSubscriber.java` | `codegraph callers convertAndSend` |
| M5 | 优雅停机 | `GracefulShutdownListener.java` | 读代码 |
| M6 | 关注 Lua 拆分 | `follow_self.lua` / `follow_target.lua` 等 | 读文件 |
| M8 | IM seqNo | `ChatService.java:102 / 117 / 320-321` | `codegraph callees handleChat` |
| M11 | 灰度溢出 | `GrayRouteFilter.java:68` | `codegraph node GrayRouteFilter` |
| M13 | 改密注销 Token | `UserService.java:286` / `TokenService.java:185` | `codegraph callers revokeAllTokens` |
| M14 | Inventory Lua KEYS | `prededuct.lua` / `InventoryService.java:183-189` | 读代码 |
| M15 | Coupon hashtag | `CouponService.java:61-68` | `codegraph search "coupon:{%d}"` |
| M17 | SSE Pub/Sub start | `SseCrossInstanceSubscriber.java:46-52` | 读代码 |
| M18 | IndexRebuildJob 锁 | `IndexRebuildJob.java:97-121` | 读代码 |

---

## 五、Lua ↔ Java KEYS 验证矩阵（16 个脚本 × 调用方）

此矩阵供 AI 逐项比对。每个 Lua 脚本标注了 KEYS 数量、每个 Java 调用方标注了传入的 KEYS 数量，两者必须一致。

| Lua 脚本 | KEYS | Java 调用方 1 | KEYS | Java 调用方 2 | KEYS | 状态 |
|----------|------|-------------|------|-------------|------|------|
| `cart_add.lua` | 3 | `CartService.addToCart()` | 3 | — | — | 验证 |
| `cart_remove.lua` | 3 | `CartService.removeFromCart()` | 3 | — | — | 验证 |
| `cart_check_all.lua` | 2 | `CartService.checkAll()` | 2 | — | — | 验证 |
| `follow_self.lua` | 2 | `FollowService.follow()` Step A | 2 | — | — | 验证 |
| `follow_target.lua` | 2 | `FollowService.follow()` Step B | 2 | — | — | 验证 |
| `unfollow_self.lua` | 2 | `FollowService.unfollow()` Step A | 2 | — | — | 验证 |
| `unfollow_target.lua` | 2 | `FollowService.unfollow()` Step B | 2 | — | — | 验证 |
| `favorite_atomic.lua` | 1 | `FavoriteService.favorite()` | 1 | — | — | 验证 |
| `like_atomic.lua` | 2 | `LikeService.like()` | 2 | — | — | 验证 |
| `unlike_atomic.lua` | 2 | `LikeService.unlike()` | 2 | — | — | 验证 |
| `prededuct.lua` | 2+N | `InventoryService.preDeduct()` | 2+N | — | — | 验证 |
| `release.lua` | 3 | `InventoryService.releaseStock()` | 3 | `InventoryService.rollbackPreDeduct()` | 3 | 验证 |
| `release.lua` | 3 | `PreDeductTimeoutJob.releasePreDeduct()` | 3 | — | — | 验证 |
| `confirm.lua` | 1 | `InventoryService.confirmDeduct()` | 1 | — | — | 验证 |
| `claim_coupon.lua` | 2 | `CouponService.claimCoupon()` | 2 | — | — | 验证 |
| `return_coupon.lua` | 2 | `CouponService.rollbackRedisStock()` | 2 | — | — | 验证 |

### Python 校验脚本

```python
# 将上表保存为 lua_verify.csv，运行以下脚本自动校验
import subprocess, csv
for row in csv.reader(open('lua_verify.csv')):
    script_name = row[0]
    expected_keys = int(row[1])
    # 用 codegraph 查调用方
    result = subprocess.run(['codegraph', 'callers', script_name.replace('.lua','')], 
                           capture_output=True, text=True, cwd='/data/workspace/my-xhs')
    print(f"{script_name}: expected={expected_keys}, callers={result.stdout.strip()}")
```

---

## 六、Key 格式一致性矩阵

| Key 组 | 正确格式 | 涉及文件 | 验证方式 |
|--------|---------|---------|---------|
| 购物车 | `myxhs:cart:{userId}:items/checked/sort` | `CartService.java` + `CartReconcileJob.java` | `grep -rn "myxhs:cart:" my-xhs-cart/` |
| 优惠券 | `coupon:{templateId}:stock/claimed` | `CouponService.java` | `grep -rn "coupon:stock\|coupon:claimed" my-xhs-coupon/` |
| 库存 total/bucket | `inventory:{skuId}:total/bucket:N` | `InventoryService.java` + `InventoryReconcileJob.java` + `PreDeductTimeoutJob.java` + `InventoryCacheEvictConsumer.java` | `grep -rn "inventory:total\|inventory:bucket" my-xhs-inventory/` |
| 库存 prededuct | `inventory:prededuct:{orderId}` | `InventoryService.java` + `PreDeductTimeoutJob.java` | `grep -rn "inventory:prededuct" my-xhs-inventory/` |

---

## 七、输出要求

最终输出 Markdown 报告，包含：

1. **全量编译结果**（BUILD SUCCESS/FAILURE + 警告数）
2. **双引擎状态**（CodeGraph 索引同步 + Sverklo 审计评级）
3. **高影响符号分析**（Top 5 符号 codegraph impact 结果）
4. **交叉调用链一致性**（所有关键方法的 callers/callees 对比）
5. **Lua 脚本验证**（16 个脚本 × 调用方 KEYS 对比，输出不一致清单）
6. **Key 格式一致性**（4 组 Key 跨文件 grep 对比）
7. **Sverklo 风险评估**（`sverklo review --ref HEAD~20..HEAD` 输出 + 风险评分）
8. **Token 消耗报告**（`sverklo receipt`）
9. **剩余风险清单**（按 Critical/Major/Minor/Info 分级）
10. **综合评分**（每个维度 1-10 分 + 加权总分）

---

*文档生成时间：2026-06-04*
*数据源：CodeGraph 0.9.9（479 文件 / 8,970 节点 / 13,684 边）+ Sverklo 0.29.0（657 文件 / 7,819 chunks / 37,279 引用 / 健康度 A）*
*Node.js 24.16.0（nvm LTS，自带 FTS5）*
