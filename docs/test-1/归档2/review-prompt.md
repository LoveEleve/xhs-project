# my-xhs 项目系统性 Code Review Prompt

> 本文档基于对项目全部 16 个微服务模块、347 个 Java 源文件、12 个 Lua 脚本、4 份 SQL 初始化脚本、
> docker-compose.yml、Canal/Prometheus/Grafana/RocketMQ 配置文件的**逐行深度阅读**后编写。

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
| 监控 | Prometheus + Grafana + Micrometer | v2.48.1 / 10.2.3 | 5s采集 |
| 链路追踪 | SkyWalking | 9.7.0 | OAP存ES |
| WebSocket | Spring WebSocket (Tomcat NIO) | — | 非WebFlux |
| 工具库 | Hutool / Guava / MapStruct / Lombok / TTL(2.14.5) | — | |

### 1.3 模块清单与端口

| 模块 | 端口 | 核心职责 |
|------|------|---------|
| my-xhs-common | — | 公共基础（62个类：注解/切面/缓存/配置/异常/ID/指标/链路/混沌/数据生成） |
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

| 模块 | 文件 | 功能 |
|------|------|------|
| cart | `cart_add.lua` | 加购：检查上限(50)+HINCRBY+截断(99)+默认选中+记录时间 |
| cart | `cart_remove.lua` | 删除：HDEL+SREM+ZREM三结构同删 |
| cart | `cart_check_all.lua` | 全选/取消：原子重建checked Set |
| analytics | `follow_and_count.lua` | 关注：ZSCORE幂等→ZADD双向+INCR双计数 |
| analytics | `unfollow_and_count.lua` | 取关：ZSCORE幂等→ZREM双向+DECR双计数 |
| analytics | `like_atomic.lua` | 点赞：SADD正向+条件SADD反向 |
| analytics | `unlike_atomic.lua` | 取消点赞：SREM正向+条件SREM反向 |
| inventory | `prededuct.lua` | 分桶预扣：幂等检→总量检→路由桶(userId%N)→遍历桶→DECRBY+HSET记录 |
| inventory | `release.lua` | 释放：HGET预扣量→INCRBY回退来源桶→INCRBY总量→HDEL记录 |
| inventory | `confirm.lua` | 确认：HGET→HDEL记录（Redis库存在预扣时已扣） |
| coupon | `claim_coupon.lua` | 领券：库存检→限领检→DECR库存+INCR领取次数 |
| coupon | `return_coupon.lua` | 退券：INCR库存+DECR领取次数 |

### 1.7 MQ Topic 清单（16 个）

| Topic | 模式 | 用途 |
|-------|------|------|
| FEED_TOPIC | 普通 | 笔记发布→Feed推送 |
| NOTIFICATION_TOPIC | 普通 | 各类通知事件 |
| SOCIAL_TOPIC | 普通+tag | 点赞/收藏异步落库 |
| CART_TOPIC | 普通 | 购物车异步持久化 |
| CACHE_EVICT_TOPIC | 普通+tag | 缓存删除MQ兜底 |
| INVENTORY_DEDUCT_TOPIC | 普通 | 库存异步扣MySQL |
| INVENTORY_CACHE_TOPIC | 普通(Canal) | 库存Binlog→缓存失效 |
| ORDER_TRANSACTION_TOPIC | **事务消息** | 订单创建 |
| ORDER_CLOSE_TOPIC | **延时(level=16=30min)** | 超时关单 |
| COUPON_CLAIM_TOPIC | 普通 | 领券异步落库 |
| SPU_CACHE_EVICT_TOPIC | **广播** | SPU变更→Caffeine失效 |
| IM_ROUTE_TOPIC | **广播** | IM跨实例消息路由 |
| REFUND_RESULT_TOPIC | 普通 | 退款结果通知 |
| NOTE_INDEX_TOPIC | 普通(Canal) | 笔记Binlog→ES同步 |
| PRODUCT_INDEX_TOPIC | 普通(Canal) | 商品Binlog→ES同步 |
| 内联Lua脚本(多处) | — | Feed推送裁剪/IM路由注销/通知聚合/未读计数安全递减 |

### 1.8 补偿任务清单（12 个）

| 任务 | 模块 | 频率 | 功能 |
|------|------|------|------|
| LocalMessageRetryJob | order | 30s | 重发本地消息表失败消息 |
| OrderCloseJob | order | 1min | 兜底关闭超时订单 |
| PaymentNotifyCompensateJob | payment | 2min | 支付成功通知补偿 |
| RefundNotifyCompensateJob | payment | 3min | 退款成功通知补偿 |
| RefundTimeoutCheckJob | payment | 1min | 退款超时标记关闭 |
| PreDeductTimeoutJob | inventory | 5min | 超时释放未确认预扣 |
| InventoryReconcileJob | inventory | 每天3点 | Redis↔MySQL库存对账 |
| CartReconcileJob | cart | 每天4点 | Redis↔MySQL购物车对账 |
| CounterReconcileJob | counter | 每天3点 | Redis↔MySQL计数对账 |
| CouponExpireJob | coupon | 每小时 | 过期优惠券状态标记 |
| FeedCleanupJob | home | 每天3点 | 清理7天前Feed数据 |
| UnreadReconcileJob | notification | 5min | 通知未读计数对账 |

---

## 二、Review 指令

你是一位拥有 10 年以上经验的 Java 微服务架构师和资深代码审查专家。请对本项目进行**系统性、全面、深入**的 Code Review。

**Review 原则**：
1. 不做泛泛而谈——每个问题必须指出**具体文件路径和代码位置**
2. 不止找问题——同时认可优秀的设计决策并分析其原理
3. 给出可落地的改进方案——包含具体代码示例
4. 区分严重程度——Critical / Major / Minor / Suggestion
5. 关注**第五章已发现问题清单**中的具体代码问题

---

## 三、Review 维度（共 12 个维度）

### 维度 1：架构设计与模块划分

**1.1 微服务拆分粒度**
- 16 模块中 counter 仅一个 Service+一个 Job，是否过度拆分？
- analytics(关系数据) vs counter(聚合计数) 通过 Redis INCR/DECR 间接交互——计数漂移风险？
- home(BFF) 承载 Feed推拉+5种详情聚合——是否过重？

**1.2 通信模式选择**
- Feed推拉阈值：粉丝>=10万用拉模式，是否可动态调整？
- IM跨实例 BROADCASTING：N实例每条消息产生N次消费仅1次有效，替代方案？
- 库存预扣用同步Feign（下单关键路径），Feed推送用异步MQ（非关键），选择恰当

**1.3 数据库拆分策略**
- 订单分片 `user_id%4` 分库 + `(user_id/4)%4` 分表，非分片键查询通过 t_order_no_mapping 解决
- **缺失场景**：商家视角/运营后台查询无方案（需 ES 宽表或 CQRS 查询服务）
- 库存独立实例（高写争用隔离）决策正确

**1.4 Canal 同步架构**
- 3个Instance: note→ES(搜索), product→ES(搜索), inventory→Redis(缓存失效)
- 库存缓存失效用版本号(Canal `es`)防乱序 ✓
- 同步延迟链：DB→Binlog→Canal(1s)→MQ→Consumer→写入，预计 1~5s

---

### 维度 2：公共模块设计（my-xhs-common，62 个类）

**2.1 AOP 切面体系**
- 执行顺序：`RateLimit(@Order 10) → Lock(@Order 50) → Idempotent(@Order 100) → SqlGuard(MyBatis层)`
- **降级策略**：三个切面 Redis 不可用时全部 fail-open（放行），仅 Gateway 黑名单 fail-closed（拒绝）
- SqlGuardInterceptor：慢SQL(>200ms)连续5次→熔断30秒，SQL指纹用正则替换参数为`?`

**2.2 缓存工具 CacheHelper**
- 三重保障：Cache Aside + 延迟双删(500ms,ScheduledExecutor) + MQ兜底(CACHE_EVICT_TOPIC,最多重试16次)
- 防穿透：空值缓存2min + 防雪崩：TTL随机偏移±10% + 防击穿：Redisson tryLock Singleflight
- **审查点**：500ms覆盖主从延迟？MQ也不可用时？

**2.3 ID 生成器**
- 号段模式：双Buffer+乐观锁，当前buffer用完切换next，next消耗50%时异步加载下段
- DB不可用时buffer耗尽→**抛异常无降级**
- CosId worker-id：`(ip[2]*256+ip[3])%1024`——同子网多实例可能冲突

**2.4 全链路追踪**
- TraceContext(TransmittableThreadLocal) + FeignTraceInterceptor + MqTraceHelper
- Home模块 MdcAwareExecutorService 自定义包装——与TTL关系需确认
- XXL-Job线程是否被TTL包装？（未见配置）

---

### 维度 3：网关安全设计（7 个 Filter）

**3.1 JWT 鉴权（GatewayAuthFilter, Order=1000）**
- 白名单AntPathMatcher + Bearer Token解析 + type==access校验 + Redis黑名单(Fail-Closed)
- 密钥与User服务共享——依赖配置文件相同值

**3.2 HMAC 签名（HmacSignatureFilter, Order=1500）**
- `签名 = HmacSHA256(method+path+timestamp+nonce+SHA256(body), secret)`
- 时间窗口 300s（过宽，标准推荐60~120s），Nonce去重 310s TTL
- Body缓存 DataBufferUtils.join()——大文件上传内存风险

**3.3 流量染色安全（TrafficColoringFilter, Order=1200）**
- 压测标记仅允许 `10.x.x.x` 设置——**使用 remoteAddress 获取 IP，经反向代理时拿到代理IP**
- A/B分组：`(userId.hashCode() & 0x7FFFFFFF) % 3` → A/B/C

**3.4 灰度路由（GrayRouteFilter, Order=3000）**
- 按 X-Gray-Tag 筛选实例metadata，无匹配→fallback全量
- 全局异常处理不返回stacktrace ✓

---

### 维度 4：数据一致性

**4.1 订单事务消息流程**
```
幂等SETNX → 用户锁SETNX → 计算金额 → sendMessageInTransaction
  → executeLocalTransaction(@Transactional: INSERT order+item+localMessage)
  → checkLocalTransaction: 查localMessage状态
→ 延时关单消息(delayLevel=16=30min) → 异步写映射表
```
- 本地消息表补偿：每30s扫描 `status IN(0,2) AND retry<3 AND created_at<NOW()-60s`
- 死信：重试3次后status=3，需人工介入

**4.2 库存三级扣减**
```
L1: Redis分桶Lua(prededuct.lua) — userId%N路由桶, 不足遍历其他桶
L2: MQ异步扣MySQL(乐观锁WHERE available>=qty) — 失败不重试等L3
L3: 对账(每天3点) — 以Redis为权威更新MySQL(Redis丢失时以DB恢复Redis)
```
- 超时释放(每5min): SCAN预扣Key→TTL<=0→release.lua原子回退（与confirm不冲突因HGET+HDEL原子）

**4.3 购物车一致性**
- Redis为权威 + MQ异步持久化 + 对账(每天4点以Redis为准)
- Lua保证Hash+Set+ZSet三结构原子
- **Redis Cluster slot问题**：三Key无hash tag，迁移Cluster时Lua报CROSSSLOT

**4.4 缓存一致性策略对比**
| 模块 | 策略 | 时序 | 兜底 |
|------|------|------|------|
| user/content | CacheHelper(afterCommit+延迟双删) | 事务后 ✓ | MQ |
| product(SPU) | 更新DB→删Redis→MQ广播删Caffeine | 非事务 | Caffeine 5min TTL |
| inventory | Canal Binlog→MQ→版本号防乱序删Redis | 版本递增 | 下次读回填 |
| cart | Redis为准+MQ异步+对账 | — | 对账Job |

---

### 维度 5：高可用与容错

**5.1 Feign 降级策略**
- 商品/用户/计数：返回null/默认值（展示降级）
- 库存/优惠券/订单：抛RemoteException（关键路径不允许静默降级）

**5.2 MQ 可靠性**
- 事务消息：订单创建（回查查localMessage）
- 普通消息失败：库存/优惠券有Redis回滚补偿，Feed/通知仅log
- RocketMQ不可用阻塞：订单创建、库存确认、购物车持久化

**5.3 Redis 故障降级**
- 限流/锁/幂等切面：放行 | Token黑名单：拒绝 | 缓存读：穿透DB | 缓存写：静默失败
- 购物车/库存Lua：抛BizException（核心操作不降级）
- 布隆过滤器：跳过直查缓存

**5.4 优雅停机缺陷**
- 仅 `sleep(30s)` 等待，**未先注销Nacos/未停MQ Consumer/未关WebSocket/未flush Counter Buffer**
- Counter @PreDestroy 刷盘与 ContextClosedEvent 执行顺序不确定

---

### 维度 6：性能

**6.1 数据库**
- 游标分页(评论/订单)避免深分页 ✓
- 批量IN查询替代循环 ✓
- OrderCloseJob全16片扫描（无分片键）——性能隐患

**6.2 Redis**
- 热Key：热搜ZSet(所有ZINCRBY同一Key)、大V发件箱(所有粉丝读同一Key)
- 大Key：收藏列表ZSet无TTL无裁剪、大V粉丝列表百万级
- Feed推送：1万粉丝=10000次EVALSHA(建议Pipeline优化)

**6.3 线程池**
- Home双线程池隔离(聚合20-50/Feign30-80) + CallerRunsPolicy降级
- IM synchronized(session)——单聊可接受，群聊会成瓶颈
- Counter @Scheduled(5s)单线程——刷盘耗时>5s会延迟

---

### 维度 7：安全性

**7.1 认证**
- 密码BCrypt ✓ | JWT密钥明文在yml中 ✗ | 登录锁定5次/15min ✓
- docker-compose/application.yml 硬编码 DB/Redis 密码 ✗

**7.2 SQL注入**
- 全局LambdaWrapper+#{} ✓ | 未发现${} ✓ | 排序白名单switch ✓
- `wrapper.last("LIMIT "+pageSize)` 安全但不符最佳实践

**7.3 越权**
- 笔记/评论/订单/地址均有userId校验 ✓
- IM通过conversation隔离 ✓ | X-User-Id由Gateway注入不可篡改 ✓

**7.4 IM安全**
- ticket 5min JWT(type=ws_ticket) | 未一次性消费(可重复使用但踢旧连接)
- 聊天消息**未做DFA敏感词过滤** | 长度限制2000字符 ✓

---

### 维度 8：代码质量

**8.1 分层** — Controller仅参数校验+路由 ✓ | 跨服务全Feign ✓
**8.2 异常** — 全局Handler覆盖Biz/Remote/Validation/Exception ✓ | Feed/通知MQ失败有意catch+log
**8.3 命名** — 常量大写 ✓ | DTO: XxxRequest/XxxVO ✓ | 个别不一致(SkuDTO vs SkuVO)
**8.4 复用** — CacheHelper统一(SPU除外) | MQ发送无统一封装(重复代码)
**8.5 设计模式** — 策略(支付/存储) ✓ | 责任链(优惠券) ✓ | 状态机(笔记/订单用枚举+乐观锁) ✓

---

### 维度 9：可观测性

- 结构化JSON日志+traceId ✓ | Gateway请求日志(method/path/duration) ✓
- Prometheus 4组19条告警(P0~P2) ✓ | 覆盖服务/应用/业务/中间件
- **缺失**：数据库连接耗尽告警、死信队列积压告警、独立审计日志

---

### 维度 10：可测试性

- POM声明Testcontainers但实际使用待确认
- 核心算法(DFA/号段/一致性Hash/分桶Lua)缺独立单测
- 无API端到端测试、无Contract Test

---

### 维度 11：运维与部署

- Docker Compose全host模式，健康检查覆盖 ✓
- **无mem_limit/cpus资源限制** | Nacos/RocketMQ/Redis均单点无HA
- 无CI/CD脚本 | 无代码质量门禁 | SQL版本管理缺Flyway/Liquibase
- 15个服务硬编码IP `21.91.124.110`——应配置化

---

### 维度 12：业务逻辑完备性

**12.1 社交** — 关注Lua幂等 ✓ | 取关未关注抛异常(应静默) | Counter丢失靠对账(24h窗口)
**12.2 电商** — 库存三重防超卖 ✓ | 优惠券Lua原子防超发 ✓ | 关单vs支付回调乐观锁互斥 ✓ (但关单先执行时需自动退款)
**12.3 IM** — 推送失败→存离线兜底 ✓ | 跨实例消息不保序(客户端重排序) | 仅单聊无群聊

---

## 四、输出要求

对每个维度输出：评分(1-10) + 优秀设计(2-3项) + Critical/Major/Minor问题(含文件路径) + 改进建议(含代码示例)

最后给出 **Top 20 优先改进项**（按影响面×严重程度排序）。

---

## 五、已发现问题清单

### Critical

| # | 问题 | 文件 | 影响 |
|---|------|------|------|
| C1 | MQ在事务提交前发送(Feed) | `content/service/NoteService.java` publishNote() | 事务回滚后消息已发出→Feed幽灵笔记 |
| C2 | MQ在事务提交前发送(通知) | `content/service/CommentService.java` createComment() | 通知指向不存在的评论 |
| C3 | 压测标记IP校验绕过 | `gateway/filter/TrafficColoringFilter.java` | 用remoteAddress获取IP,经反代后拿到代理IP,攻击者可设压测标记 |
| C4 | docker-compose硬编码密码 | `docker-compose.yml` | 密码提交到代码仓库 |
| C5 | yml硬编码密码 | 15个服务 `application.yml` | DB/Redis密码明文 |

### Major

| # | 问题 | 文件 | 影响 |
|---|------|------|------|
| M1 | 购物车Redis三Key无hashtag | `cart/service/CartService.java` | 迁移Cluster时Lua报CROSSSLOT |
| M2 | Feed推送失败静默 | `content/service/NoteService.java` | 笔记已发布但粉丝看不到 |
| M3 | Counter Buffer崩溃丢数据 | `counter/service/CounterService.java` | kill -9丢最多5s/100条,对账间隔24h |
| M4 | IM广播消费CPU浪费 | `im/consumer/ImRouteConsumer.java` | N实例线性增长 |
| M5 | 优雅停机不完整 | `common/shutdown/GracefulShutdownListener.java` | 未注销Nacos/停MQ/关WS |
| M6 | 点赞"noop"Key Cluster不兼容 | `analytics/service/LikeService.java` | KEYS数组跨slot |
| M7 | 库存L2失败无重试等24h对账 | `inventory/job/InventoryReconcileJob.java` | 长时间不一致 |
| M8 | IM消息跨实例不保序 | `im/consumer/ImRouteConsumer.java` | 消息乱序 |
| M9 | 分桶数硬编码无迁移方案 | `inventory/service/InventoryService.java` | 扩缩桶无自动化 |
| M10 | SSE ticket未一次性消费 | `notification/service/SseEmitterManager.java` | 5min内可重复建连 |

### Minor

| # | 问题 | 文件 |
|---|------|------|
| m1 | LikeConsumer用now()非事件时间 | `analytics/consumer/LikeConsumer.java` |
| m2 | 取关未关注抛异常非静默 | `analytics/service/FollowService.java` |
| m3 | 取消点赞注释与代码不一致 | `analytics/service/LikeService.java` |
| m4 | 评论批量子查询LIMIT分配不均 | `content/service/CommentService.java` |
| m5 | MQ发送无统一封装 | 全项目各服务 |
| m6 | 收藏ZSCORE+ZADD非原子 | `analytics/service/FavoriteService.java` |
| m7 | 热搜ZSet单Key热点 | `search/service/HotSearchService.java` |
| m8 | 收藏列表无TTL无裁剪 | `analytics/service/FavoriteService.java` |
| m9 | 关注列表无上限 | `analytics/service/FollowService.java` |
| m10 | HMAC时间窗口300s过宽 | `gateway/filter/HmacSignatureFilter.java` |

---

## 六、评审输出样例

```markdown
### 维度 4：数据一致性 — 评分 8/10

#### 优秀设计
1. **库存三级扣减**：Lua原子预扣(不超卖) + MQ异步落盘(不阻塞) + 对账兜底(最终一致)
   - 分桶(userId%4)分散热点，释放回退来源桶(skuId:bucket记录)避免漂移
2. **订单事务消息+本地消息表**：双重保障分布式事务
   - 回查查localMessage状态，补偿每30s扫描(60s保护窗口避免与COMMIT冲突)

#### Critical
- **[C1] NoteService MQ在事务提交前发送**
  - 位置：`my-xhs-content/.../NoteService.java` publishNote()
  - 修复：移到 TransactionSynchronization.afterCommit() 回调中

#### Major
- **[M1] 购物车三Key无hashtag** → 使用 `{cart:{userId}}` hash tag 统一slot
```

---

*文档生成时间：2026-06-02*
*基于项目全量源码二次深度阅读*
