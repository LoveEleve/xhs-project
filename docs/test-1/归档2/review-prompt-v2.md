# my-xhs 项目系统性 Code Review Prompt（V2 — 深度完善版）

> 本文档基于对项目全部 **17 个模块（含 test）、347 个 Java 源文件、12 个 Lua 脚本、4 份 SQL 初始化脚本、
> docker-compose.yml、Canal/Prometheus/Grafana/RocketMQ 配置文件**的**逐行二次深度阅读**后编写。
> 相比 V1 版本，本版本新增了：精确到代码行号的问题定位、更多新发现的 Bug、线程安全分析、
> Lua 脚本逻辑验证、跨模块交互问题、以及完整的技术决策评价。

---

## 一、项目概述

### 1.1 项目定位

my-xhs 是一个**小红书（社交电商）全栈微服务项目**，覆盖社交（笔记/评论/关注/点赞/收藏/IM/Feed流/通知）和电商（商品/购物车/库存/优惠券/订单/支付）完整业务链路。

### 1.2 技术栈全景

| 类别 | 技术选型 | 版本 | 备注 |
|------|---------|------|------|
| 语言 | Java | 17 | |
| 框架 | Spring Boot | 3.2.5 | Jakarta EE |
| 微服务 | Spring Cloud + Spring Cloud Alibaba | 2023.0.1 / 2023.0.1.0 | |
| 注册/配置中心 | Nacos | 2.3.2 | 当前仅用服务发现 |
| API 网关 | Spring Cloud Gateway (WebFlux) | — | 响应式 |
| 服务调用 | OpenFeign + HC5 连接池 | — | 含 FallbackFactory |
| 熔断限流 | Sentinel（规则持久化 Nacos） | 1.8.8 | |
| 数据库 | MySQL 8.0（4实例，业务隔离） | 8.0 | max-connections: 200~500 |
| ORM | MyBatis-Plus (Spring Boot 3) | 3.5.7 | LambdaWrapper |
| 分库分表 | ShardingSphere-JDBC | 5.5.1 | 4库×4表=16分片 |
| 缓存 | Redis 7 (Lettuce + Redisson 3.27) | 7-alpine | 单节点, 256MB |
| 本地缓存 | Caffeine | 3.1.8 | 商品/分类/Feed |
| 消息队列 | RocketMQ | 5.1.4 | 异步刷盘, 48h保留 |
| 搜索引擎 | Elasticsearch | 8.12.2 | IK分词, 3分片1副本 |
| Binlog同步 | Canal | 1.1.7 | 3个instance |
| 分布式调度 | XXL-Job | 2.4.2 | |
| ID 生成 | 号段模式（双Buffer）+ 雪花(CosId 2.6.8) | — | step: 1000~5000 |
| 认证 | JWT (jjwt 0.12.3) + BCrypt | — | Access 30min + Refresh 7d |
| 监控 | Prometheus + Grafana + Micrometer | v2.48.1 / 10.2.3 | 10s采集 |
| 链路追踪 | SkyWalking | 9.7.0 | OAP存ES |
| WebSocket | Spring WebSocket (Tomcat NIO) | — | 非WebFlux |
| 工具库 | Hutool / Guava / MapStruct / Lombok / TTL(2.14.5) | — | |

### 1.3 模块清单与端口

| 模块 | 端口 | 核心职责 |
|------|------|---------|
| my-xhs-common | — | 公共基础（64个类：注解/切面/缓存/配置/异常/ID/指标/链路/混沌/数据生成） |
| my-xhs-gateway | 19000 | API网关（7个Filter链：日志→鉴权→染色→HMAC→限流→灰度→版本） |
| my-xhs-user | 19001 | 用户（注册/登录/双Token/验证码/地址/信息） |
| my-xhs-content | 19002 | 内容（笔记CRUD/评论树形/DFA敏感词/状态机/本地文件存储） |
| my-xhs-analytics | 19003 | 互动（关注/点赞/收藏，Lua原子+MQ异步落库+Pipeline批查） |
| my-xhs-counter | 19004 | 计数（ConcurrentHashMap双Buffer聚合→定时batch upsert） |
| my-xhs-product | 19006 | 商品（SPU/SKU/分类，三级缓存+布隆过滤器+逻辑过期+MQ广播Caffeine失效） |
| my-xhs-cart | 19008 | 购物车（Redis三结构+3个Lua+Pipeline+MQ异步持久化+对账） |
| my-xhs-inventory | 19009 | 库存（Redis分桶预扣3个Lua+MQ异步扣DB+超时释放+Canal缓存失效+对账） |
| my-xhs-coupon | 19010 | 优惠券（Lua原子领取/责任链校验/MQ落库/退券/过期回收） |
| my-xhs-order | 19011 | 订单（事务消息+本地消息表+状态机+分库分表+超时关单+映射表） |
| my-xhs-payment | 19012 | 支付（策略模式Mock/支付宝/微信+回调模拟器+退款+3个补偿任务） |
| my-xhs-notification | 19013 | 通知（SSE长连接+MQ消费+5分钟窗口聚合+未读计数+跨实例Pub/Sub+对账） |
| my-xhs-im | 19014 | IM（WebSocket+ticket两步鉴权+Redis路由+MQ广播+离线ZSet+写扩散+一致性Hash LB） |
| my-xhs-home | 19015 | BFF聚合（Feed推拉结合+双线程池并行Feign+5种详情聚合+MDC透传） |
| my-xhs-search | 19016 | 搜索（ES全文+Canal同步+热搜滑动窗口反作弊+Completion建议+多路召回推荐） |

### 1.4 数据库架构

```
mysql-user:13306 → nacos_config, xxl_job, my_xhs_user, my_xhs_analytics, my_xhs_notification, my_xhs_im
mysql-content:13307 → my_xhs_content, my_xhs_counter, my_xhs_search, my_xhs_product, my_xhs_cart, my_xhs_coupon
mysql-order:13308 → my_xhs_order(映射表), my_xhs_payment, my_xhs_order_0~3(分片库各含4分表)
mysql-inventory:13309 → my_xhs_inventory
```

### 1.5 架构分层图

```
[客户端] → [Gateway:19000(7层Filter)] → [BFF:19015(双线程池并行Feign)]
    → [社交域: user/content/analytics/counter]
    → [电商域: product/cart/inventory/coupon/order/payment]
    → [基础域: search/im/notification]
    → [基础设施: MySQL×4/Redis/RocketMQ/ES/Canal/XXL-Job/Nacos/SkyWalking/Prometheus]
```

### 1.6 Lua 脚本清单（12 个）

| 模块 | 文件 | 功能 | KEYS数 |
|------|------|------|--------|
| cart | `cart_add.lua` | 加购：检查上限(50)+HINCRBY+截断(99)+默认选中+记录时间 | 3 |
| cart | `cart_remove.lua` | 删除：HEXISTS检查→HDEL+SREM+ZREM三结构同删 | 3 |
| cart | `cart_check_all.lua` | 全选/取消：原子重建checked Set（先DEL再SADD所有） | 2 |
| analytics | `follow_and_count.lua` | 关注：ZSCORE幂等→ZADD双向+INCR双计数 | 4 |
| analytics | `unfollow_and_count.lua` | 取关：ZSCORE幂等→ZREM双向+DECR双计数 | 4 |
| analytics | `like_atomic.lua` | 点赞：SADD正向+条件SADD反向 | 2 |
| analytics | `unlike_atomic.lua` | 取消点赞：SREM正向+条件SREM反向 | 2 |
| inventory | `prededuct.lua` | 分桶预扣：幂等检→总量检→路由桶(userId%N)→遍历桶→DECRBY+HSET记录 | 2 |
| inventory | `release.lua` | 释放：HGET预扣量→INCRBY回退来源桶→INCRBY总量→HDEL记录 | 2 |
| inventory | `confirm.lua` | 确认：HGET→HDEL记录（Redis库存在预扣时已扣） | 1 |
| coupon | `claim_coupon.lua` | 领券：库存检→限领检→DECR库存+INCR领取次数 | 2 |
| coupon | `return_coupon.lua` | 退券：INCR库存+DECR领取次数 | 2 |

### 1.7 MQ Topic 清单（16 个）

| Topic | 模式 | 用途 |
|-------|------|------|
| FEED_TOPIC | 普通 | 笔记发布→Feed推送 |
| NOTIFICATION_TOPIC | 普通 | 各类通知事件 |
| SOCIAL_TOPIC | 普通+tag(LIKE/UNLIKE/FAVORITE/UNFAVORITE) | 点赞/收藏异步落库 |
| CART_TOPIC | 普通 | 购物车异步持久化 |
| CACHE_EVICT_TOPIC | 普通+tag(user-service) | 缓存删除MQ兜底 |
| INVENTORY_DEDUCT_TOPIC | 普通 | 库存异步扣MySQL |
| INVENTORY_CACHE_TOPIC | 普通(Canal) | 库存Binlog→缓存失效 |
| ORDER_TRANSACTION_TOPIC | **事务消息** | 订单创建 |
| ORDER_CLOSE_TOPIC | **延时(level=16=30min)** | 超时关单 |
| COUPON_CLAIM_TOPIC | 普通 | 领券异步落库 |
| SPU_CACHE_EVICT_TOPIC(product-cache-evict) | **广播** | SPU变更→Caffeine失效 |
| IM_ROUTE_TOPIC | **广播** | IM跨实例消息路由 |
| REFUND_RESULT_TOPIC | 普通 | 退款结果通知 |
| NOTE_INDEX_TOPIC | 普通(Canal) | 笔记Binlog→ES同步 |
| PRODUCT_INDEX_TOPIC | 普通(Canal) | 商品Binlog→ES同步 |
| 内联Lua脚本(多处) | — | Feed推送裁剪/IM路由注销/通知聚合/未读计数安全递减 |

### 1.8 补偿任务清单（12 个）

| 任务 | 模块 | 频率 | 功能 |
|------|------|------|------|
| LocalMessageRetryJob | order | 30s | 重发本地消息表失败消息(3次死信) |
| OrderCloseJob | order | 1min | 兜底关闭超时订单(游标分页100条) |
| PaymentNotifyCompensateJob | payment | 2min | 支付成功通知补偿 |
| RefundNotifyCompensateJob | payment | 3min | 退款成功通知补偿 |
| RefundTimeoutCheckJob | payment | 1min | 退款超时标记关闭 |
| PreDeductTimeoutJob | inventory | 5min(@Scheduled) | 超时释放未确认预扣(SCAN+release.lua) |
| InventoryReconcileJob | inventory | 每天3点 | Redis↔MySQL库存对账(Redis为准) |
| CartReconcileJob | cart | 每天4点 | Redis↔MySQL购物车对账(Redis为准) |
| CounterReconcileJob | counter | 每天3点 | Redis↔MySQL计数对账(Redis为准) |
| CouponExpireJob | coupon | 每小时 | 过期优惠券状态标记(分批1000) |
| FeedCleanupJob | home | 每天3点 | 清理7天前Feed数据 |
| UnreadReconcileJob | notification | 5min | 通知未读计数对账(DB为准) |

### 1.9 Gateway Filter 执行链

```
HIGHEST_PRECEDENCE + 100   → RequestLogFilter（TraceId生成/透传 + 出入站日志）
HIGHEST_PRECEDENCE + 1000  → GatewayAuthFilter（白名单 + JWT解析 + 黑名单 + 注入X-User-Id）
HIGHEST_PRECEDENCE + 1200  → TrafficColoringFilter（压测/灰度/AB/版本标记注入）
HIGHEST_PRECEDENCE + 1500  → HmacSignatureFilter（时间戳+Nonce+HMAC-SHA256验签）
HIGHEST_PRECEDENCE + 2500  → RateLimitFilter（Sentinel + 本地兜底规则 + Nacos动态推送）
HIGHEST_PRECEDENCE + 3000  → GrayRouteFilter（灰度实例筛选 10%流量）
HIGHEST_PRECEDENCE + 3100  → ApiVersionFilter（版本路由 metadata 匹配）
```

### 1.10 Sentinel 网关限流规则（本地兜底）

| 服务 | QPS阈值 |
|------|---------|
| order-service | 500 |
| payment-service | 300 |
| inventory-service | 500 |
| coupon-service | 200 |
| user-service | 200 |
| search-service | 1000 |
| home-service | 1000 |
| product-service | 800 |
| counter-service | 1000 |
| content-service | 500 |
| analytics-service | 500 |
| notification-service | 300 |
| im-service | 500 |
| cart-service | 500 |

### 1.11 Prometheus 告警规则（18条/4组）

| 组 | 规则 | 阈值 |
|----|------|------|
| 应用级 | 5xx错误率 | >1% 持续2min |
| 应用级 | P99延迟 | >3s 持续5min |
| 应用级 | JVM堆 | >85% 持续3min |
| 应用级 | GC暂停 | >1s 持续1min |
| 应用级 | HikariCP等待连接 | >5 持续2min |
| 应用级 | 服务宕机 | up==0 持续1min |
| 业务级 | 下单失败率 | >5% 持续3min |
| 业务级 | 支付成功率 | <95% 持续5min |
| 业务级 | MQ积压 | >10000条 持续5min |
| 业务级 | 登录失败率 | >20% 持续5min |
| 中间件 | Redis内存 | >90% 持续3min |
| 中间件 | Redis连接数 | >1000 持续2min |
| 中间件 | ES集群状态 | red 持续1min |
| 中间件 | ES JVM | >90% 持续5min |
| 中间件 | Canal同步延迟 | >5s 持续3min |
| 中间件 | RocketMQ磁盘 | >85% 持续5min |
| 核心链路 | 订单/支付/库存/搜索 4黄金信号 | 各有阈值 |
| 核心链路 | 流量突增 | >2倍均值 持续3min |

---

## 二、Review 指令

你是一位拥有 10 年以上经验的 Java 微服务架构师和资深代码审查专家。请对本项目进行**系统性、全面、深入**的 Code Review。

**Review 原则**：
1. 不做泛泛而谈——每个问题必须指出**具体文件路径和代码位置**
2. 不止找问题——同时认可优秀的设计决策并分析其原理
3. 给出可落地的改进方案——包含具体代码示例
4. 区分严重程度——Critical / Major / Minor / Suggestion
5. 关注**第五章已发现问题清单**中的具体代码问题
6. 验证跨模块交互是否存在一致性/时序/降级问题

---

## 三、Review 维度（共 12 个维度）

### 维度 1：架构设计与模块划分

**1.1 微服务拆分粒度**
- 16 模块中 counter 仅一个 Service+一个 Job+一个 Consumer，是否过度拆分？
- analytics(关系数据) vs counter(聚合计数) 通过 MQ(SOCIAL_TOPIC) 间接交互——计数漂移风险？
- home(BFF) 承载 Feed推拉+5种详情聚合+双线程池——是否过重？

**1.2 通信模式选择**
- Feed推拉阈值：粉丝>=10万用拉模式，硬编码在 `FeedPushConsumer`，是否可动态调整？
- IM跨实例 BROADCASTING：N实例每条消息产生N次消费仅1次有效，替代方案？
- 库存预扣用同步Feign（下单关键路径），Feed推送用异步MQ（非关键），选择恰当
- 购物车 Feign 超时 500ms/2000ms，订单关单 Feign 到库存/优惠券超时未单独配置（共用全局 2000ms）

**1.3 数据库拆分策略**
- 订单分片 `user_id%4` 分库 + `(user_id/4)%4` 分表，非分片键查询通过 t_order_no_mapping 解决
- **缺失场景**：商家视角/运营后台查询无方案（需 ES 宽表或 CQRS 查询服务）
- 库存独立实例（高写争用隔离）决策正确
- 13306 承载 6 个库（user/analytics/notification/im/nacos/xxl_job），是否需要拆分？

**1.4 Canal 同步架构**
- 3个Instance: note→ES(搜索), product→ES(搜索), inventory→Redis(缓存失效)
- 库存缓存失效用版本号(Canal `es` 字段)防乱序 ✓
- 同步延迟链：DB→Binlog→Canal(1s)→MQ→Consumer→写入，预计 1~5s
- Canal instance.properties 中 `filter.regex` 精确到表级别，避免多余消费

**1.5 ID 策略分析**
- 号段模式：7个业务各独立号段（step=1000~5000），双Buffer+乐观锁
- 雪花ID（CosId）：worker-id = `(ip[2]*256+ip[3])%1024`，同子网多实例可能冲突
- ShardingSphere 分片表自带 SNOWFLAKE 策略，worker-id 也用 IP 计算

---

### 维度 2：公共模块设计（my-xhs-common，64 个类）

**2.1 AOP 切面体系（执行顺序及降级策略）**

| 切面 | Order | 降级策略 | 锁/限流Key |
|------|-------|---------|------------|
| RateLimitAspect | 10 | Redis不可用→放行 | 滑动窗口ZSet+Lua |
| DistributedLockAspect | 50 | Redisson连接失败→放行，tryLock超时→放行 | Redisson RLock+Watchdog |
| IdempotentAspect | 100 | Redis不可用→放行 | SETNX + 异常分类决定是否删除 |
| SqlGuardInterceptor | MyBatis层 | 熔断后仍放行(log不阻止) | ConcurrentHashMap指纹统计 |

**2.2 IdempotentAspect 异常分类策略（亮点）**
- `BizException / IllegalArgumentException / IllegalStateException` → 删除幂等标记（业务未执行）
- `TimeoutException / SocketTimeoutException` → 不删除标记（业务可能已执行）
- 其他异常 → 保守策略不删除
- 递归检查 cause 链

**2.3 缓存工具 CacheHelper**
- 三重保障：Cache Aside + 延迟双删(500ms,ScheduledExecutor) + MQ兜底(CACHE_EVICT_TOPIC,最多重试16次)
- 防穿透：空值缓存2min(标记`\u0000__CACHE_NULL__\u0000`) + 防雪崩：TTL随机偏移±1/6 + 防击穿：Redisson tryLock Singleflight
- Singleflight 获取锁失败：sleep(100ms)→重读缓存→仍未命中→降级直查DB
- **审查点**：500ms覆盖主从延迟？MQ也不可用时？ScheduledExecutor单线程?

**2.4 ID 生成器**
- 号段模式：双Buffer+乐观锁，当前buffer用完切换next，next消耗50%时异步加载下段
- DB不可用时buffer耗尽→**抛异常无降级**
- 支持三种策略：`nextId()`雪花, `nextSegmentId(bizType)`号段, `nextIdStr()`字符串格式

**2.5 全链路追踪**
- TraceContext(TransmittableThreadLocal) + FeignTraceInterceptor + MqTraceHelper
- Home模块 MdcAwareExecutorService 自定义包装：execute()前捕获MDC→Runnable包装→执行后清理
- XXL-Job线程是否被TTL包装？（未见配置）
- MQ 消费者端通过 MqTraceHelper 从 Message Header 恢复 TraceId

**2.6 RedisOperator 封装**
- 统一连接异常处理：`LettuceConnectionException` / `RedisConnectionException` → 抛 `RedisUnavailableException`
- 其他异常：记日志返回默认值（静默失败）
- 上层切面通过 `catch(RedisUnavailableException)` 判断是否降级

---

### 维度 3：网关安全设计（7 个 Filter）

**3.1 JWT 鉴权（GatewayAuthFilter, Order=1000）**
- 白名单 AntPathMatcher（16个路径模式）+ Bearer Token解析 + type==access校验 + Redis黑名单(Fail-Closed)
- 密钥与 User 服务共享——依赖配置文件相同值（WebFlux不能引用Servlet模块依赖）
- TOKEN_BLACKLIST_PREFIX 硬编码同步风险（必须与 my-xhs-common 保持一致）

**3.2 HMAC 签名（HmacSignatureFilter, Order=1500）**
- `签名 = HmacSHA256(method + path + timestamp + nonce, secret)` → Base64
- 使用 `MessageDigest.isEqual()` 时间恒定比较（防时序攻击）✓
- 时间窗口 300s（过宽，标准推荐60~120s），Nonce去重 Redis SET NX EX 300
- Redis异常时降级为放行（Fail-Open）——与Auth的Fail-Closed策略不一致
- 不校验 Body 内容（仅校验 Header），body 篡改无法检测

**3.3 流量染色安全（TrafficColoringFilter, Order=1200）**
- 压测标记仅允许 `10.x.x.x` 设置——IP检查使用 `remoteAddress`
- A/B分组：`(userId.hashCode() & 0x7FFFFFFF) % 3` → A/B/C（位运算避免溢出）✓
- `X-Gray-Tag` / `X-Api-Version` 客户端可指定，否则默认值

**3.4 灰度路由（GrayRouteFilter, Order=3000）**
- 使用 `Math.abs(userId.hashCode())` ——Integer.MIN_VALUE时仍为负数（Bug）
- 对比：TrafficColoringFilter 已正确使用 `& 0x7FFFFFFF`
- 无匹配→fallback全量，全局异常处理不返回stacktrace ✓

**3.5 Sentinel 限流（RateLimitFilter, Order=2500）**
- 双源规则：Nacos动态推送优先 + 本地兜底规则（30秒等待窗口）
- 429 响应：`{"code":429,"message":"请求过于频繁，请稍后再试","data":null}`

**3.6 全局异常处理（GlobalExceptionHandler）**
- `@Order(-1)` 最高优先级 ErrorWebExceptionHandler
- ConnectException→503, TimeoutException→504, NotFoundException→404, 其他→500
- 5xx 不暴露堆栈 ✓，5xx 记 ERROR 日志 ✓

---

### 维度 4：数据一致性

**4.1 订单事务消息流程**
```
幂等SETNX(24h) → 用户锁SETNX(10s) → 计算金额(Mock:99元/SKU-10元优惠)
  → sendMessageInTransaction(ORDER_TRANSACTION_TOPIC)
  → executeLocalTransaction(@Transactional: INSERT order+item+localMessage)
  → checkLocalTransaction: 查localMessage(有=COMMIT/无=ROLLBACK/异常=UNKNOWN)
→ 延时关单消息(delayLevel=16=30min) → 记录快照 → 异步写映射表(公共库)
```
- 本地消息表补偿：每30s扫描 `status IN(0,2) AND retry<3 AND created_at<NOW()-60s`
- 死信：重试3次后status=3，需人工介入，Prometheus 指标 `deadLetterCount` 上报

**4.2 库存三级扣减（精确验证）**
```
L1: Redis分桶Lua(prededuct.lua) — userId%N路由桶, 不足遍历其他桶
    返回: 1=成功, 0=库存不足, -1=重复预扣, -2=未初始化
L2: MQ异步扣MySQL(乐观锁WHERE available>=qty) — 失败不重试等L3
L3: 对账(每天3点) — 以Redis为权威更新MySQL(Redis丢失时以DB恢复Redis)
```
- 超时释放(每5min): SCAN `inventory:prededuct:*` → TTL<=0 → release.lua原子回退
- release.lua 安全：HGET+HDEL原子，与confirm不冲突
- **新发现Bug**：PreDeductTimeoutJob.releasePreDeduct() 遍历Hash entries时包含`:bucket`辅助字段

**4.3 购物车一致性**
- Redis为权威 + MQ异步持久化(asyncSend) + 对账(每天4点以Redis为准)
- Lua保证Hash+Set+ZSet三结构原子
- 对账三种修复：Redis有DB无→INSERT / 不一致→UPDATE / Redis无DB有→DELETE
- **Redis Cluster slot问题**：三Key `cart:items:{userId}` / `cart:checked:{userId}` / `cart:sort:{userId}` 无显式hash tag

**4.4 缓存一致性策略对比**
| 模块 | 策略 | 时序 | 兜底 | 备注 |
|------|------|------|------|------|
| user/content | CacheHelper(afterCommit+延迟双删500ms) | 事务后 ✓ | MQ(16次重试) | 标准方案 |
| product(SPU) | 更新DB→删Redis→MQ广播删Caffeine→TTL 5min | 非afterCommit | Caffeine TTL | 逻辑过期防击穿 |
| inventory | Canal Binlog→MQ→版本号防乱序删Redis | 版本递增 | 下次读回填 | 异步链1~5s |
| cart | Redis为准+MQ异步+对账 | — | 对账Job | Redis=权威 |
| counter | Redis INCR实时+Buffer攒批MySQL | — | 对账Job | Redis=权威 |
| notification | DB为准+Redis缓存未读数 | — | 对账Job(DB为准) | DB=权威 |

**4.5 MQ 发送时序问题汇总**
| 模块 | 场景 | 发送时机 | 问题 |
|------|------|---------|------|
| content | 笔记发布→Feed | @Transactional内、afterCommit注册后 | 事务未提交时MQ已发 |
| content | 评论→通知 | 同上 | 同上 |
| analytics | 点赞→落库 | 非事务（Redis操作后同步send） | 无事务问题 ✓ |
| order | 订单创建 | 事务消息（半消息→本地事务→COMMIT） | 正确 ✓ |
| inventory | 预扣→MySQL | 同步Feign返回后发MQ | 正确 ✓ |

---

### 维度 5：高可用与容错

**5.1 Feign 降级策略**
| 模块 | 降级方式 | 策略 |
|------|---------|------|
| 商品查询 | FallbackFactory | 返回null/默认值（展示降级） |
| 用户查询 | FallbackFactory | 返回null（展示降级） |
| 计数查询 | FallbackFactory | 返回0（展示降级） |
| 库存预扣 | FallbackFactory | 抛RemoteException（关键路径不允许静默降级） |
| 优惠券核销 | FallbackFactory | 抛RemoteException（关键路径不允许静默降级） |
| 关单→释放库存 | FallbackFactory | 抛RemoteException→关单失败→延时重试/兜底Job |

**5.2 MQ 可靠性**
- 事务消息：订单创建（回查查localMessage）
- 普通消息失败：
  - 库存/优惠券 → 有Redis回滚补偿（MQ失败时回退Lua操作）
  - 点赞 → 有Redis回滚补偿（unlike_atomic.lua回滚）
  - 收藏 → 无回滚（MQ异步发送，失败仅log）
  - Feed/通知 → 仅log
- RocketMQ不可用阻塞：订单创建（事务消息）、库存确认、购物车持久化

**5.3 Redis 故障降级矩阵**
| 组件 | 降级策略 | 影响 |
|------|---------|------|
| 限流(RateLimitAspect) | 放行 | 无限流保护 |
| 分布式锁(DistributedLockAspect) | 放行 | 并发风险 |
| 幂等(IdempotentAspect) | 放行 | 重复请求 |
| Token黑名单(GatewayAuthFilter) | **拒绝** | 安全优先 |
| HMAC Nonce去重 | 放行 | 重放风险 |
| 缓存读 | 穿透DB | DB压力 |
| 缓存写 | 静默失败 | 下次读回填 |
| 购物车/库存Lua | **抛BizException** | 核心操作不降级 |
| 布隆过滤器 | 跳过直查缓存 | 少量穿透 |

**5.4 优雅停机分析**
- 实现：`GracefulShutdownListener` 监听 `ContextClosedEvent`，`sleep(30s)` 等待
- **缺失操作**：
  1. 未先通过 Nacos API 注销实例（30s内新请求仍可路由过来）
  2. 未停止 MQ Consumer（可能正在处理消息被打断）
  3. 未关闭 WebSocket 连接（IM模块）
  4. 未 flush Counter Buffer（最多丢5s/100条数据）
  5. Counter @PreDestroy 刷盘与 ContextClosedEvent 执行顺序不确定

---

### 维度 6：性能

**6.1 数据库**
- 游标分页(评论/订单/对账)避免深分页 ✓
- 批量IN查询替代循环 ✓ (SKU批量、评论子评论批量)
- OrderCloseJob 全16分片SCAN（无分片键条件）——全表扫描性能隐患
- 优惠券过期Job分批UPDATE LIMIT 1000 + sleep 100ms 让出锁 ✓
- Counter对账逐条查Redis→应改Pipeline批量

**6.2 Redis**
- 热Key：热搜ZSet(所有ZINCRBY同一Key)、大V发件箱(所有粉丝读同一Key)
- 大Key：收藏列表ZSet无TTL无裁剪、大V粉丝列表百万级、离线消息ZSet(MAX 1000)
- Feed推送：`FeedPushConsumer` Lua脚本逐个粉丝 EVALSHA(建议Pipeline优化)
- 共同关注：内存中两个5000元素Set求交集（建议ZINTERSTORE服务端计算）
- Product逻辑过期异步刷新：使用默认ForkJoinPool（无界线程创建风险）

**6.3 线程池设计**
| 模块 | 池名 | 核心/最大/队列 | 拒绝策略 | 用途 |
|------|------|---------------|---------|------|
| home | aggregatorPool | 20/50/200 | CallerRunsPolicy | 外层编排 |
| home | batchFeignPool | 30/80/500 | CallerRunsPolicy | 内层Feign调用 |
| search | recallExecutor | 10/20/100 | CallerRunsPolicy | 5路召回并行 |
| common | CacheHelper.scheduler | 1(守护线程) | — | 延迟双删 |

**潜在问题**：
- IM `synchronized(session)` ——单聊可接受，群聊会成瓶颈
- Counter `@Scheduled(5s)` 单线程——刷盘耗时>5s会延迟
- Feign read-timeout=5s vs 聚合CompletableFuture超时3s → 超时后Feign线程仍阻塞2s

**6.4 批量优化已做**
- 评论子评论：批量IN查询 + 内存分组（避免N+1） ✓
- 点赞批量查询：Pipeline SISMEMBER ✓
- Feed大V发件箱：Pipeline批量查 ✓
- SKU批量：`WHERE id IN(...)` 一次查询 ✓

---

### 维度 7：安全性

**7.1 认证体系**
- 密码BCrypt ✓ | JWT密钥明文在yml中 ✗ | 登录锁定5次/15min ✓
- 验证码：SecureRandom+排除易混淆字符+一次性消费(校验后删除) ✓
- Token刷新：分布式锁防并发+双重黑名单检查+Redis存最新Token(单设备登录) ✓
- **修改密码后未注销现有Token** → 旧Token最长30min仍有效

**7.2 密码/密钥硬编码**
- docker-compose.yml: MySQL/Redis/Grafana/XXL-Job 密码明文
- 15个服务 application.yml: DB/Redis密码、JWT Secret、HMAC Secret 明文
- ES 关闭了 xpack.security（任何人可访问 19200 端口）
- RocketMQ Broker `autoCreateTopicEnable=true`（生产环境应关闭）

**7.3 SQL注入**
- 全局LambdaWrapper+#{} ✓ | 未发现${} ✓ | 排序白名单switch ✓
- `wrapper.last("LIMIT "+pageSize)` ——pageSize 为 int 类型已通过 Math.min 限制，安全但不符最佳实践
- Search模块 `RecommendComputeJob.enrichEngagementCounts()` 直接拼接ID字符串（虽来自内部DB查询）

**7.4 越权防护**
- 笔记/评论/订单/地址均有userId校验 ✓
- IM通过conversation隔离 ✓ | X-User-Id由Gateway注入不可篡改 ✓
- **但**：Gateway 是否 strip 了外部传入的 X-User-Id？（需确认 AuthFilter 是否在注入前先删除旧值）
- 管理接口（对账修复/索引重建）无权限保护 ✗

**7.5 IM安全**
- ticket 5min JWT(type=ws_ticket) ✓ | 未一次性消费(可重复使用但踢旧连接)
- 聊天消息**未做DFA敏感词过滤** | 长度限制2000字符 ✓
- ConversationId生成：`(min<<32)|max` 当userId>2^31时long溢出导致碰撞

**7.6 输入校验**
- RegisterRequest: username 4-32位正则 ✓, password 6-64位 ✓
- NotePublishRequest: 标题@NotBlank ✓, **content无@Size限制** ✗
- UpdateUserRequest: gender无@Min/@Max范围校验 ✗
- AddressCreateRequest: 手机号正则 ✓, 各字段长度限制 ✓
- UserInfoResponse: 返回完整手机号/邮箱未脱敏（地址模块做了脱敏，用户模块未做）

---

### 维度 8：代码质量

**8.1 分层** — Controller仅参数校验+路由 ✓ | 跨服务全Feign ✓ | 无跨层调用 ✓
**8.2 异常** — 全局Handler覆盖Biz/Remote/Validation/Exception ✓ | Feed/通知MQ失败有意catch+log ✓
**8.3 命名** — 常量大写 ✓ | DTO: XxxRequest/XxxVO ✓ | 个别不一致(SkuDTO vs SkuVO)
**8.4 复用** — CacheHelper统一(SPU除外，用三级缓存) | MQ发送无统一封装(重复代码)
**8.5 设计模式**
- 策略模式：支付(Mock/Alipay/WeChat) ✓ | 文件存储(Local/OSS) ✓ | 召回(5种策略) ✓
- 责任链：优惠券校验(3个Validator按@Order) ✓
- 状态机：笔记(枚举+canTransitTo) ✓ | 订单(枚举+乐观锁) ✓
- 观察者：Redis Pub/Sub(敏感词刷新/SSE跨实例) ✓
- 模板方法：AbstractSearchService(抽取公共ES操作) ✓

**8.6 新发现的代码气味**
- CounterService.decrement() 每次 new DefaultRedisScript<>() → 应提为静态常量
- SpuService 异步刷新缓存用 ForkJoinPool(默认) → 应指定有界线程池
- IM ConsistentHashLoadBalancer 每次请求重建 Hash 环 → 应缓存实例快照
- Notification聚合器 `incrementAggregateCount` 返回值语义误用（返回的是affected rows不是count值）

---

### 维度 9：可观测性

- 结构化JSON日志+traceId ✓ | Gateway请求日志(method/path/duration/traceId) ✓
- Prometheus 4组18条告警(P0~P2) ✓ | 覆盖服务/应用/业务/中间件
- XXL-Job 执行结果可查 ✓ | 死信消息有 Prometheus 指标 ✓
- **缺失**：
  - Alertmanager 未配置通知渠道（告警规则存在但无法发送）
  - 数据库连接耗尽告警
  - 死信队列积压告警
  - 独立审计日志（操作记录）
  - 多Redis端口(16380/16381)实际未使用/监控

---

### 维度 10：可测试性

- POM声明Testcontainers但实际使用待确认
- 核心算法(DFA/号段/一致性Hash/分桶Lua)缺独立单测
- 无API端到端测试、无Contract Test
- 无性能基准测试（Counter Buffer吞吐/Feed推送延迟）
- my-xhs-test 模块存在但功能待确认

---

### 维度 11：运维与部署

**11.1 Docker Compose**
- 全host模式（端口号隔离而非网络隔离）
- 健康检查：MySQL/Redis/ES/Broker/Canal ✓ | Sentinel/Nacos/XXL-Job/Grafana/SkyWalking ✗
- **无 mem_limit/cpus 资源限制** → 内存泄漏可拖垮宿主机
- **部分服务无日志限制** → 磁盘撑满风险
- Nacos/RocketMQ/Redis 均单点无HA

**11.2 配置管理**
- 15个服务硬编码IP `21.91.124.110` → 应配置化（环境变量/Nacos配置）
- 无CI/CD脚本 | 无代码质量门禁 | SQL版本管理缺Flyway/Liquibase
- Nacos Config 已引入依赖但仅用于服务发现，未启用配置管理

**11.3 Canal 运维**
- Canal 3个instance共享一个server进程，instance故障隔离度低
- 无Canal position checkpoint 备份策略

---

### 维度 12：业务逻辑完备性

**12.1 社交**
- 关注Lua幂等 ✓ | 取关未关注返回0(Lua幂等) ✓
- Counter丢失靠对账(24h窗口，Redis为准修复MySQL，DB为准恢复Redis)
- 收藏 ZSCORE+ZADD 非Lua原子 → 并发可能重复MQ

**12.2 电商**
- 库存三重防超卖 ✓ | 优惠券Lua原子防超发 ✓ | 关单vs支付回调乐观锁互斥 ✓
- 关单时Feign释放库存/退优惠券，若Feign失败→关单失败→兜底Job重试
- 订单金额计算目前为Mock(固定99元)，生产需对接商品服务实时价格
- **唯一索引冲突**：优惠券 `uk_user_coupon(user_id, coupon_id)` 与 perUserLimit>1 逻辑冲突

**12.3 IM**
- 推送失败→存离线兜底(ZSet, MAX 1000条, 7天过期) ✓
- 跨实例消息不保序（客户端依赖 msgId/timestamp 重排序）
- 仅单聊无群聊
- ACK确认：ZREM O(log N) > LREM O(N) ✓

**12.4 搜索**
- 热搜反作弊：IP限频(10次/分钟) + 用户限频(同词300秒1次) + 指数衰减 ✓
- 推荐5路并行召回+粗排+精排+重排(已读过滤+品类打散) ✓
- Canal同步ExternalGte版本控制防乱序 ✓

**12.5 通知**
- SSE永不超时(0L)+心跳保活 ✓ | 自己给自己通知跳过 ✓
- 5分钟聚合窗口（避免大V单条动态产生百万通知）✓
- **SseEmitter 关闭旧连接回调会误删新连接**（Bug）

---

## 四、输出要求

对每个维度输出：评分(1-10) + 优秀设计(2-3项，解释为什么优秀) + Critical/Major/Minor问题(含文件路径和行号) + 改进建议(含代码示例)

最后给出 **Top 25 优先改进项**（按影响面×严重程度×修复成本排序）。

---

## 五、已发现问题清单（V2 — 扩充版）

### Critical（数据正确性/安全/资金损失风险）

| # | 问题 | 文件 | 影响 | 新/旧 |
|---|------|------|------|-------|
| C1 | MQ在事务提交前发送(Feed) | `content/service/NoteService.java` publishNote() | 事务回滚后消息已发出→Feed幽灵笔记 | V1 |
| C2 | MQ在事务提交前发送(通知) | `content/service/CommentService.java` createComment() | 通知指向不存在的评论 | V1 |
| C3 | 压测标记IP校验用remoteAddress | `gateway/filter/TrafficColoringFilter.java` | 经反代后拿到代理IP,攻击者可设压测标记 | V1 |
| C4 | docker-compose硬编码全部密码 | `docker-compose.yml` | MySQL/Redis/Grafana/Canal/XXL-Job密码提交代码仓库 | V1 |
| C5 | 15个yml硬编码密码+JWT Secret | 15个服务 `application.yml` | DB/Redis密码/JWT密钥明文，泄露=全Token可伪造 | V1 |
| C6 | 优惠券唯一索引vs perUserLimit冲突 | `coupon/consumer/CouponClaimConsumer.java` | uk_user_coupon限制1条记录,但perUserLimit允许多次→Redis已扣库存但DB拒绝INSERT | **NEW** |
| C7 | Notification SSE旧连接回调删新连接 | `notification/service/SseEmitterManager.java` createConnection() | put新emitter后complete旧emitter,onCompletion回调remove(userId)删掉新emitter | **NEW** |
| C8 | ES未启用安全(xpack.security=false) | `docker-compose.yml` ES配置 | 19200端口任何人可读写索引，可删除/篡改搜索数据 | **NEW** |

### Major（功能正确性/可用性/一致性）

| # | 问题 | 文件 | 影响 | 新/旧 |
|---|------|------|------|-------|
| M1 | 购物车Redis三Key无hashtag | `cart/service/CartService.java` | 迁移Cluster时Lua报CROSSSLOT | V1 |
| M2 | Feed推送失败静默(try/catch仅log) | `content/service/NoteService.java` | 笔记已发布但粉丝看不到 | V1 |
| M3 | Counter Buffer崩溃丢数据 | `counter/service/CounterBuffer.java` | kill -9丢最多5s/100条,对账间隔24h | V1 |
| M4 | IM广播消费CPU浪费 | `im/consumer/ImRouteConsumer.java` | N实例线性增长无效消费 | V1 |
| M5 | 优雅停机不完整 | `common/shutdown/GracefulShutdownListener.java` | 未注销Nacos/停MQ/关WS/flush Buffer | V1 |
| M6 | 关注Lua 4 KEYS跨slot | `analytics/service/FollowService.java` + lua | Cluster下CROSSSLOT | V1 |
| M7 | 库存L2失败无重试等24h对账 | `inventory/consumer/InventoryDeductConsumer.java` | MySQL乐观锁失败→长时间不一致 | V1 |
| M8 | IM消息跨实例不保序 | `im/consumer/ImRouteConsumer.java` | 消息乱序需客户端重排 | V1 |
| M9 | 分桶数硬编码(default=2,hot=8) | `inventory/service/InventoryService.java` | 扩缩桶无自动化迁移方案 | V1 |
| M10 | SSE ticket未一次性消费 | `notification/service/SseEmitterManager.java` | 5min内可重复建连(但踢旧连接) | V1 |
| M11 | GrayRouteFilter Math.abs()溢出 | `gateway/filter/GrayRouteFilter.java` L67 | Integer.MIN_VALUE时灰度判断异常 | **NEW** |
| M12 | PreDeductTimeoutJob遍历:bucket辅助字段 | `inventory/job/PreDeductTimeoutJob.java` releasePreDeduct() | 将`:bucket`field当skuId处理→回退到错误Key | **NEW** |
| M13 | 修改密码后未注销现有Token | `user/service/UserService.java` changePassword() | 旧Token(30min access+7d refresh)仍有效 | **NEW** |
| M14 | Inventory prededuct.lua CROSSSLOT | `inventory/resources/lua/prededuct.lua` | KEYS[1]=total:{skuId}, KEYS[2]=prededuct:{orderId} slot不同 | **NEW** |
| M15 | Coupon claim_coupon.lua CROSSSLOT | `coupon/resources/lua/claim_coupon.lua` | KEYS[1]=stock:{templateId}, KEYS[2]=claimed:{templateId}:{userId} | **NEW** |
| M16 | 收藏ZSCORE+ZADD非原子 | `analytics/service/FavoriteService.java` L57-65 | 并发重复MQ导致多次INSERT(唯一索引兜底不丢数据但浪费) | V1 |
| M17 | Notification Redis Pub/Sub未start() | `notification/service/SseCrossInstanceSubscriber.java` init() | MessageListenerContainer 只调了afterPropertiesSet()没调start() | **NEW** |
| M18 | Search IndexRebuildJob 锁在异步线程释放 | `search/job/IndexRebuildJob.java` manualRebuild() | 异步线程非锁持有者,isHeldByCurrentThread()=false,锁2h后才自动过期 | **NEW** |
| M19 | IM ConversationId长溢出 | `im/service/ChatService.java` generateConversationId() | userId>2^31时(min<<32)|max溢出→不同对话碰撞 | **NEW** |
| M20 | Notification incrementAggregateCount返回值误用 | `notification/service/NotificationAggregator.java` L134 | 返回affected rows(0/1)而非新count值,聚合标题显示错误 | **NEW** |

### Minor（代码质量/最佳实践/潜在风险）

| # | 问题 | 文件 | 新/旧 |
|---|------|------|-------|
| m1 | LikeConsumer用now()非事件时间 | `analytics/consumer/LikeConsumer.java` | V1 |
| m2 | 取关未关注时Lua返回0(Service层未区分已处理) | `analytics/service/FollowService.java` | V1更正 |
| m3 | 取消点赞注释与代码不一致 | `analytics/service/LikeService.java` | V1 |
| m4 | 评论批量子查询LIMIT分配不均 | `content/service/CommentService.java` | V1 |
| m5 | MQ发送无统一封装(重复代码) | 全项目各服务 | V1 |
| m6 | 热搜ZSet单Key热点 | `search/service/HotSearchService.java` | V1 |
| m7 | 收藏列表无TTL无裁剪 | `analytics/service/FavoriteService.java` | V1 |
| m8 | 关注列表无上限(粉丝ZSet百万级) | `analytics/service/FollowService.java` | V1 |
| m9 | HMAC时间窗口300s过宽 | `gateway/filter/HmacSignatureFilter.java` | V1 |
| m10 | HMAC不校验Body内容 | `gateway/filter/HmacSignatureFilter.java` | **NEW** |
| m11 | CounterService.decrement()每次new RedisScript | `counter/service/CounterService.java` | **NEW** |
| m12 | SpuService异步刷新用ForkJoinPool(无界) | `product/service/SpuService.java` | **NEW** |
| m13 | IM Hash环每次请求重建(无缓存) | `im/loadbalancer/ImConsistentHashLoadBalancer.java` | **NEW** |
| m14 | NotePublishRequest content字段无@Size限制 | `content/dto/request/NotePublishRequest.java` | **NEW** |
| m15 | UpdateUserRequest gender无@Min/@Max校验 | `user/dto/request/UpdateUserRequest.java` | **NEW** |
| m16 | UserInfoResponse手机号/邮箱未脱敏 | `user/dto/response/UserInfoResponse.java` | **NEW** |
| m17 | 评论通知未排除自己评论自己 | `content/service/CommentService.java` L147 | **NEW** |
| m18 | 管理接口(对账/索引重建)无权限保护 | counter/search Controller | **NEW** |
| m19 | 共同关注内存交集(各5000条) | `analytics/service/FollowService.java` L293 | **NEW** |
| m20 | Counter对账逐条查Redis应改Pipeline | `counter/service/CounterService.java` reconcile() | **NEW** |
| m21 | uploadImage接收userId未使用 | `content/controller/NoteController.java` | **NEW** |
| m22 | Feign超时5s vs 聚合超时3s不一致 | `home/service/NoteAggService.java` | **NEW** |
| m23 | 关注计数DECR可能变负数 | `analytics/resources/lua/unfollow_and_count.lua` L27 | **NEW** |
| m24 | Search NoteFeatures.UNKNOWN 静态可变实例 | `search/job/RecommendComputeJob.java` | **NEW** |
| m25 | 离线消息ZCARD+removeRange非原子 | `im/service/ChatService.java` storeOfflineMessage() | **NEW** |

---

## 六、交叉模块数据流完整性分析

### 6.1 下单全链路时序
```
Client → Gateway(Auth+HMAC+RateLimit)
  → Order.createOrder()
    → Redis 幂等SETNX(24h)
    → Redis 用户锁SETNX(10s)
    → Feign→Inventory.preDeduct() [同步, 2s超时]
      → prededuct.lua(Redis分桶扣减)
    → Feign→Coupon.useCoupon() [同步, 2s超时]
      → 责任链校验 → DB标记已使用
    → RocketMQ事务半消息(ORDER_TRANSACTION_TOPIC)
      → executeLocalTransaction: INSERT order+item+localMessage
      → 成功→COMMIT / 失败→ROLLBACK
    → 延时消息(ORDER_CLOSE_TOPIC, 30min)
    → 异步→写映射表(公共库)
  → Response(orderNo)

// 消费端
OrderCreatedConsumer:
  → 通知库存确认(INVENTORY_DEDUCT_TOPIC: CONFIRM)
  → 通知计数服务
```

### 6.2 关单vs支付竞态分析
```
30min到期:
  OrderCloseJob/OrderCloseConsumer
    → SELECT WHERE status=0
    → UPDATE SET status=4 WHERE status=0 (乐观锁)
    → 成功 → Feign释放库存+退优惠券

支付回调:
  PaymentNotifyController
    → UPDATE SET status=1 WHERE status=0 (乐观锁)
    → 成功 → 通知发货

互斥保证：两者同时到达时，乐观锁保证只有一方UPDATE成功
极端Case：关单先成功→支付回调失败→需自动发起退款（当前代码已处理）✓
```

### 6.3 Feed 推拉混合完整链路
```
笔记发布:
  NoteService.publishNote() → MQ(FEED_TOPIC)【⚠️事务内发送】
  
FeedPushConsumer:
  → 查作者粉丝数(Redis ZCARD)
  → 粉丝<10万: 推模式
    → ZRANGEBYSCORE获取粉丝列表(分批500)
    → 逐个粉丝: Lua(ZADD收件箱 + ZREMRANGEBYRANK裁剪500条 + EXPIRE 7天)
  → 粉丝>=10万: 拉模式
    → 写入作者发件箱(feed:outbox:{authorId})

用户浏览首页:
  FeedService.getFollowFeed()
    → 拉收件箱(ZSet reverseRangeByScore)
    → Pipeline拉关注的大V发件箱(批量)
    → 合并+排序+游标分页
```

---

## 七、评审输出样例

```markdown
### 维度 4：数据一致性 — 评分 8/10

#### 优秀设计
1. **库存三级扣减**：Lua原子预扣(不超卖) + MQ异步落盘(不阻塞) + 对账兜底(最终一致)
   - 分桶(userId%N)分散热点，释放回退来源桶(HSET记录源桶号)避免漂移
   - 超时释放Lua的HGET+HDEL原子保证与用户confirm不双重执行
2. **订单事务消息+本地消息表**：双重保障分布式事务
   - 回查查localMessage状态，补偿每30s扫描(60s保护窗口避免与COMMIT冲突)
   - 死信3次后标记+Prometheus指标上报(可触发告警→人工介入)
3. **Counter Buffer攒批合并**：高频INCR场景下10000QPS→可能只2000条SQL
   - 双Buffer交换(新Buffer立即接收写入) + 排序防死锁 + 分批500条

#### Critical
- **[C1] NoteService MQ在事务提交前发送**
  - 位置：`my-xhs-content/.../NoteService.java` publishNote() L103
  - 修复：移到 TransactionSynchronization.afterCommit() 回调中
- **[C6] 优惠券唯一索引与perUserLimit冲突**
  - 位置：`my-xhs-coupon/.../CouponClaimConsumer.java` L57
  - 修复：唯一索引改为 `uk_user_coupon(user_id, template_id, coupon_no)` 或移除唯一索引改用幂等key

#### Major
- **[M12] PreDeductTimeoutJob遍历:bucket辅助字段**
  - 位置：`my-xhs-inventory/.../PreDeductTimeoutJob.java` releasePreDeduct()
  - 修复：遍历entries时过滤 `field.contains(":bucket")`
- **[M1] 购物车三Key无hashtag**
  - 修复：改为 `myxhs:cart:{userId}:items` / `myxhs:cart:{userId}:checked` / `myxhs:cart:{userId}:sort`
    （注意：Redis hash tag 是 `{userId}` 这个大括号内的部分决定slot）
```

---

*文档生成时间：2026-06-02*
*基于项目全量源码三次深度阅读*
*V2 相比 V1 新增：8 Critical + 10 Major + 16 Minor 问题，完善了 6 个交叉模块分析*
