# 简历事实底稿 · 逐个微服务梳理（2026-09-21）

> 用途：写简历/面试引用前的唯一事实源。左侧"可写"= 代码 + 运行报告双证据；"红线"= 写进简历会被代码反证的项。
> 方法：逐个服务读 main 代码（file:line）+ 对照 `docs/reports/*` 与 `docs/test-4/*` 运行报告；行号取自当前工作区。
> 结论先行：`resume-final.md` 有 8 处会当场被戳穿的写法（见 §4），必须改写或删除。

## 0. 规模总表

| 服务 | 角色 | main 文件/LOC | test 文件 | 最强亮点（可写） | 红线（别写） |
|---|---|---:|---:|---|---|
| my-xhs-common | 15 服务共享：AOP/缓存/DB/Feign/LB/可观测/信任边界 | 135 / 13,036 | 18 | 限流+幂等切面（40/8 处落地）、CacheHelper 三段失效、SqlGuard、读写分离、Feign NEVER_RETRY | 号段 ID 未接业务、最小连接 LB 埋点缺失、`@DistributedLock`/`@ApiVersion` 0 使用、CacheWarmup key 全错位 |
| my-xhs-gateway | WebFlux 统一入口：鉴权/签名/限流/染色/灰度/版本 | 18 / 2,082 | 0 | 8 过滤器链、JWT 身份覆盖、Sentinel Nacos+30s 真空兜底、504 语义 | 灰度/版本只打标不真路由、HMAC 默认关、路由静态非 Nacos 动态 |
| my-xhs-user | 用户域：注册登录/JWT 双 token/验证码/地址 | 27 / 2,531 | 3 | 双 token 轮换+拉黑+改密吊销闭环、登录防爆破（IP/账号双维度）、地址 20 条上限 | — |
| my-xhs-product | SPU/SKU 与分类事实源 | 22 / 1,941 | 3 | Bloom+空值+逻辑过期+单飞、4,871 RPS/P99 50ms、24ms 缓存一致 | **product 不发 MQ**，"MQ 广播缓存失效"是混用 common 的地雷 |
| my-xhs-cart | 购物车（Redis 权威） | 21 / 2,232 | 2 | 7 个 Lua 全原子、seq+CLEAR 屏障乱序治理、Redis 丢可从 MySQL 恢复 | Product Feign 异常 fail-open（幽灵 SKU）；消费者仍是 TOCTOU |
| my-xhs-inventory | 库存三级扣减 + TCC 冻结 | 26 / 3,375 | 4 | 分桶 Lua+幂等占位+ZSet 超时索引、TCC fence 11 场景、20 并发不超卖 | **TCC 未接进订单链路**；Canal 缓存失效仅 DELETE 生效；"10 万 QPS"是注释 |
| my-xhs-coupon | 券模板/领券/核销/退券 | 24 / 2,006 | 4 | 单 Lua 领券+Outbox 事务性发件+双幂等、限领自愈 | **退券 Redis 补偿链路无生产者（死代码）**；"单次领券 2ms"无报告 |
| my-xhs-order | 订单/状态机/关单/分片 | 50 / 4,854 | 5 | 事务消息+本地消息表+回查、状态机+事件溯源、分片倾斜治理、Snowflake 重号修复 | 事件溯源重放接口未接线；"无 user_id 分片扫描"是保留缺口 |
| my-xhs-payment | 支付/退款/渠道策略/对账 | 27 / 3,215 | 1 | 部分退款累计语义、资损 Bug 修复、通知补偿 5min/10 次/批 100 | **支付宝/微信是文本 Mock**；支付侧两消费者默认关且空实现 |
| my-xhs-counter | 社交计数聚合 | 14 / 1,739 | 1 | Buffer 满 100/5s 攒批 + 失败回写 + 停机强刷、版本门防乱序、对账双向注入 | "双 Buffer/@Contended"实为单 map 交换；"DB 写压力 -80%"无实测 |
| my-xhs-analytics | 点赞/收藏/关注 | 29 / 2,989 | 3 | 双 Set/ZSet 模型、跨组乱序治理、关注半成功对账修复、写接口限流+幂等 | 关注 MQ 消费者默认关（实际同步落库）；**Redis 全丢时对账会删 MySQL 关系（无保护）** |
| my-xhs-content | 笔记/评论/话题/敏感词/上传 | 34 / 3,144 | 2 | 发布 11ms/搜索可见 3-6s/DFA ~0.1ms、原子发布 | 敏感词库仅 5 个测试词；DFA 动态增删词无入口 |
| my-xhs-home | Feed 聚合 + 推荐 | 38 / 3,403 | 2 | 推拉结合+断点续推、双池隔离+分层超时、1,861 RPS/P99 49.6ms | 大 V 阈值硬编码 50000 与配置 100000 冲突 |
| my-xhs-search | ES 检索/热搜/建议/历史 | 37 / 5,674 | 4 | Canal+MQ 双通道+外部版本号+tombstone、1,448 RPS/P99 61ms | 精排是规则非 ML；suggest 权重恒 1、无增量同步 |
| my-xhs-im | 私信/WebSocket | 18 / 1,892 | 0 | 跨实例 Redis pub/sub 路由、离线补发 1000/7 天、单连接踢旧 | **一致性哈希未接线（纸面通过）**；撤回/群聊未实现；0 测试 |
| my-xhs-notification | 通知聚合 + SSE | 19 / 1,937 | 0 | 自然日聚合"等 N 人"、SSE 跨实例两 Bug 修复实测 | 免打扰未实现、无 Last-Event-ID 回放；0 测试 |

---

## 1. 交易域（product / cart / inventory / coupon）

### inventory —— 简历第一梯队
可写：
- 分桶预扣 `lua/prededuct.lua:43-93` + Java `floorMod` 修雪花 ID 双精度失真 `InventoryService.java:319-323`
- 幂等占位表（INSERT IGNORE 走 master，失败删占位允许重试）`InventoryService.java:229-249`
- 超时回收 ZSet 过期索引（提前 60s 扫描）`job/PreDeductTimeoutJob.java:101-103`；O(N)→O(logN) 叙事成立
- TCC fence 状态机 `service/InventoryTccService.java:34-113` + `job/TccTimeoutJob.java:51-104`
- 实测：20 并发 149→129 不超卖、75s 回查延迟只扣一次、11 场景 TCC
  （`docs/test-4/branch-inventory/business-analysis/99-runtime-reconciliation-report.md:88-89,127,164`）
红线：TCC 只被 `InventoryController` 暴露，order/payment 无调用 → 写"落地到交易链路"会被打穿；写"为库存提供 TCC 冻结接口 + 11 场景验证"安全。"支持 10 万 QPS"仅注释。

### order —— 内容最厚
可写：
- 事务消息 `OrderService.java:232-233` + 本地事务（3 表同事务）`OrderTransactionService.java:52-104` + 回查查本地消息表 `OrderTransactionListener.java:102-133`
- 本地消息 5 次指数退避 30/60/120/240/480s `job/LocalMessageRetryJob.java:147-166`
- 状态机条件 UPDATE + 事件表 `uk_order_event_seq` + INSERT IGNORE `OrderEventService.java:53-141`、`mapper/OrderMapper.java:49-53`
- 分片：4×4 `config/sharding-config.yaml:5-7`、绑定表 `:146-147`、映射表反查 `OrderService.java:1107-1136`、独立数据源 `MappingDataSourceConfig.java:27-59`
- 实测：8 对抗场景（取消 vs 支付自动退款）`docs/reports/order-state-race-20260920.md:6-17`；分片倾斜 131 单 2/16 → 迁移 682 行 → 16/16 `docs/reports/sharding-hash-migration-20260920.md:9-33`；worker-id 354 vs 454 `multi-instance-validation-20260920.md:24-31`；Broker pause 8s 无半成品 `dist-tx-fault-drill-20260920.md:18-27`
红线：`getEventStream/replayStatus` 无调用方（重放未接线）；取消时预扣未完成 releaseStock 为 no-op 属保留权衡。

### payment
可写：部分退款累计语义 `PaymentService.java:576-638`；资损修复（补偿任务原扫全部 status=1 退款单→误判全额释放库存+退券，改 JOIN t_payment 仅 status=3）`docs/test-4/.../my-xhs-payment/02-source-deep-analysis.md:111`；通知补偿 5min/10 次/批 100 `job/PaymentNotifyCompensateJob.java:54-63`；对账 Job 从无调度死代码到运行 `job/PaymentReconcileJob.java:13-15,34`
红线：**渠道全是文本 Mock**（`strategy/impl/AlipayPayStrategy.java:9-21`）、回调是自研模拟器且有 90% 成功率；支付侧两个 MQ 消费者默认关+空实现。

### product
可写：Bloom（100 万/1%，tryInit 幂等+异步分批 5000+锁）`SpuService.java:151-252`；空值占位 2min + 逻辑过期 30min/物理 120min + 单飞 `:470-566`；afterCommit + 1s 延迟双删 `:353-364`；实测 4,871 RPS/P99 50ms（`docs/reports/batch-release-baseline-20260917.md:13`，**无本地缓存，27,442 已作废**）、24ms 一致（`cache-consistency-and-outoforder-20260920.md:10`）、Redis 故障 DB 回退（首跳 11.2s 为已知待优化）
红线：product **不发 MQ**，`CACHE_EVICT_TOPIC` 在本服务零引用；分类树缓存无单飞/空值。

### cart
可写：7 个 Lua（`resources/lua/cart_*.lua`）+ hash tag 同 slot；实例内 seq + eventTime CAS + CLEAR 屏障 `CartService.java:54-55,706-712`、`consumer/CartSyncConsumer.java:96-113`；UPSERT 幂等 `:115-153`；Redis 丢可从 MySQL 恢复 `CartService.java:742-773`；对账防"双份全丢" `job/CartReconcileJob.java:147-158`
红线：Product Feign 异常 fail-open `:632-635`；对账锁无续租、单用户入口绕过全量锁；合并逐 SKU 串行。

### coupon
可写：单 Lua 领券 `lua/claim_coupon.lua:25-47` + Outbox+syncSend 失败回滚 Redis `CouponService.java:531-560` + 消费者 msgId+uk_claim_no 双幂等 `CouponClaimConsumer.java:57-88`；核销/退券条件 UPDATE + used_order_id 跨单保护 `UserCouponMapper.java:21-34`；限领自愈（Redis 丢读 DB 实时 remain）`:503-519`；实测 10 并发限领 2 张 `99-runtime-reconciliation-report.md:90`
红线：**`COUPON_RETURN_REDIS_REPAIR_TOPIC` 有消费者无生产者**（`CouponService.java:579-598` 无调用），退券补偿是死代码；"2ms"仅注释。

---

## 2. 内容/社交域（content / home / search / im / notification / analytics / counter）

### analytics —— 关注模型最完整
可写：双 Set 点赞 `LikeService.java:81-85,164-168` + 反向索引 `:93-99`；收藏 ZSet 原 score 回滚 `FavoriteService.java:100-127`；关注双 ZSet 权威 + A/B 两步 `FollowService.java:75-146`（B 失败不回滚靠对账）；跨组乱序统一 group + actionTime 版本门 `LikeUnlikeConsumer.java:52-81`（`cache-consistency-and-outoforder-20260920.md:24-27`）；关注半成功对账修复 `test-4/.../my-xhs-analytics/01-test-matrix.md:44`；写接口 @RateLimit（30/min）+ @Idempotent 5s
红线：关注 MQ 消费者默认关（`FollowConsumer.java:39`），实际同步落库；**Redis 全丢时对账会删 MySQL 关系行**（`FollowService.java:515-521,568-573`）；COMMENT/SHARE/VIEW/FOLLOW 无版本门。

### counter
可写：Buffer 满 100/5s 攒批 + 重试 3 次失败回写缓冲 + 停机强刷 `CounterBuffer.java:53-59,121-126,221-258`；批量 upsert 增量语义 `CounterMapper.java:29-34`；Lua 去重+计数+归零保护 `CounterService.java:69-91`；版本门 `CounterEventConsumer.java:56-85`；DB 故障自愈实测 `test-4/.../my-xhs-counter/01-test-matrix.md:34`；TTL 实测 2h/30d `:21,24`；kill 消费者 LAG 1→0 恰好一次 `dist-tx-fault-drill-20260920.md:8-16`
红线："双 Buffer/@Contended"实为单 volatile map 交换 `CounterBuffer.java:161-164`；"-80% DB 写压力"无实测。

### content
可写：原子发布（内容+消息同事务）；实测发布 11ms / 搜索可见 3-6s / DFA ~0.1ms（2 万字 ~2ms）`docs/test-2/.../01-full-chain-analysis.md:19,45,51`
红线：敏感词库仅 5 个测试词；`addDynamicWords/removeDynamicWords` 无管理入口（`DFAFilter.java:103-126`）；JPA probe 默认关。

### home
可写：Feed 推拉结合 + cursor 断点续推 `FeedPushConsumer.java:53-60,145`、`FeedService.java:90-97`；双池隔离 20/50/200 + 30/80/500 CallerRuns `AggregatorThreadPoolConfig.java:48-82`；分层超时（全局预算 4s：一层 800ms → 二层取剩余 max(500,4000-elapsed)；Feed 固定 3s allOf）；删除 tombstone + `following:latest` SCAN 清理 `NoteDeleteConsumer.java:51-98`（mining 未记录的新增量）；1,861 RPS/P99 49.6ms
红线：大 V 阈值硬编码 50000 vs 配置 100000；`FeedPushConsumer.analyticsFeignClient`、`FeedService.batchFeignPool` 死注入。

### search
可写：Canal+MQ 双通道 `ProductIndexSyncConsumer.java:142-152`；外部版本号 ExternalGte + tombstone（`test-4/.../my-xhs-search/01-test-matrix.md:21,23,29,30`）；LikeCountSync 局部更新防版本域冲突 `LikeCountSyncConsumer.java:28-69`；索引重建 17 条（断点续传 + 完成后清零 + 死文档清理；**无别名机制**，2026-09-23 复核）；1,448 RPS/P99 61ms（ES 配额 2→6 核 + IO 线程 4→16）
红线：精排是规则、"预留 ML 接口"；suggest 权重恒 1、无增量同步；`NOTE/PRODUCT_INDEX_TOPIC` 扁平格式无生产方。

### im
可写：跨实例 Redis pub/sub 路由 + 离线补发 Lua 1000 条/7 天 `ChatService.java:71-82,195-200` + 单用户单连接踢旧 4001 `ImWebSocketHandler.java:58-67`；seq Redis INCR + 离线按 seq 升序 `ChatService.java:118-119,340-346`；跨实例实测 `multi-instance-validation-20260920.md:6-10`
红线：**`ImConsistentHashLoadBalancer` 无启用接线，测试矩阵 ✅ 属纸面通过**（`docs/roadmap/production-hardening.md:77`）；撤回/群聊未实现；0 个测试文件；MQ 配置残留（已改 pub/sub）。

### notification
可写：自然日聚合 SETNX+Lua"等 N 人" `NotificationAggregator.java:51-56`；SSE 跨实例（修 2 处真 Bug：本地 isOnline 短路 + TextNode 解析失败）`multi-instance-validation-20260920.md:4,13-22`；ticket 30s 两步握手 `SseTicketService.java:34,62`；未读 INCR+HINCRBY+SAFE_DECR `UnreadCountService.java:57-68`；对账 10min
红线：免打扰未实现；无 Last-Event-ID；注释 5min 与代码自然日冲突；0 测试。

---

## 3. 入口与底座（gateway / user / common）

### gateway
可写：8 个 GlobalFilter 真实顺序（BodyCache MIN_VALUE → Log +100 → Auth +1000 → Coloring +1200 → HMAC +1500 → RateLimit +2500 → Gray +3000 → Version +3100）；JWT 身份注入覆盖伪造头 `GatewayAuthFilter.java:155-163`；Redis 故障 503 可重试（非 401）`:132-138`；Sentinel Nacos + 30s 真空兜底 + 双 BlockHandler（实测 20 连发=3×200+17×429，`sqlguard-sentinel-hardening-20260917.md:28-36`）；504 语义修复
红线：灰度/版本仅写 exchange attribute，无 LB 消费；`RateLimiterConfig` 3 个 KeyResolver 死配置；路由静态 16 条非 Nacos 动态；HMAC 默认关且链路仍同步 Redis。

### user
可写：双 token（access 30min/refresh 7d）+ 刷新时 Redisson 锁 + 二查黑名单 + 旧 token 拉黑 `TokenService.java:125-198`；改密/删号全量吊销 `:308-321`、`UserService.java:370-399`；登录防爆破 IP 20 次/15min + 账号 5 次且来源 IP≥2 才锁（防账号 DoS）；验证码 getAndDelete 原子一次性；地址 20 条上限 + 默认地址缓存；实测 user info 5,846 RPS/P99 40ms（`batch-release-baseline-20260917.md:15`）
红线：删默认地址未清缓存。

### common
可写：限流切面 ZSet+Lua 滑动窗口（40 处 @RateLimit）+ 幂等切面（8 处 @Idempotent，异常分类决定是否可重试）+ @Order 10/50/100 `aspect/RateLimitAspect.java:56-102`、`aspect/IdempotentAspect.java:51-121`；CacheHelper 三段失效（3×50ms 重试 → 500ms 延迟双删 → MQ CACHE_EVICT_TOPIC，用户域落地）+ 空值占位 + TTL 随机偏移 `cache/CacheHelper.java:101-353`；SqlGuard（200ms/连续 5 次/30s 冷却 + 可配置白名单阻断，实测第 2 次 500 + 指标）`aspect/SqlGuardInterceptor.java:58-172`（`sqlguard-sentinel-hardening-20260917.md:16-21`）；读写分离 Executor 层拦截 + 从库降主 30s 探测（12 服务启用）；Feign NEVER_RETRY + ErrorDecoder + 内部令牌 fail-closed `config/FeignSafeConfig.java:52-55`、`feign/config/FeignInternalCallInterceptor.java:22-37` + HC5 200/50 + 500ms/2s + 30 个 FallbackFactory；同 zone 优先（netem 下 +34% 吞吐 / P99 -33~-47%）`docs/reports/zone-cross-region-latency-20260918.md:17-23`（默认关、单机 netem 非真实专线）；指标预注册空标签 + URI 归一化 `metrics/BusinessMetrics.java:131-149`、`metrics/ApiMetricsFilter.java:49-71`
红线：号段 ID 仅单测未接业务；最小连接 LB 埋点缺失退化为权重；`FeignUnifiedConfig`（R 解包）基本未接线；`EtagResponseBodyAdvice` 空实现；`CacheWarmupRunner` key 全错位；`@ApiVersion` 0 使用；`AuditLogService` 无子类。

---

## 4. resume-final.md 必须改写的 8 处

| # | 现写法 | 事实 | 改法 |
|---|---|---|---|
| 1 | 「落地库存三级扣减、订单分片与事务消息、…TCC…」暗示 TCC 接入交易 | TCC 只有 controller 暴露，order 无调用 | 「为库存提供 TCC 冻结接口（fence 状态机），11 场景实测」 |
| 2 | 商品「写路径 afterCommit 删缓存并对 MQ 广播失效（CACHE_EVICT_TOPIC）兜底」 | product 不发 MQ，该 topic 在 product 零引用 | 「afterCommit + 1s 延迟双删」；MQ 广播归到 common 用户域 |
| 3 | 「IM 跨实例路由：一致性哈希（150 虚拟节点）」 | LB 无接线、测试纸面通过 | 「跨实例会话路由走 Redis pub/sub」+ 保留离线补发/踢旧 |
| 4 | 优惠券「Outbox 补偿」暗示退券补偿链路 | 退券补偿无生产者（死代码） | 只写领券 Outbox + 核销/退券状态机 |
| 5 | 支付「Mock/支付宝/微信」 | 三个渠道全是文本 Mock | 「渠道策略模式 + Mock 渠道与回调模拟器」 |
| 6 | 「52 处 FallbackFactory」 | 实测 30 文件/35 方法 | 写 30 个 |
| 7 | 号段 ID 双 Buffer / 最小连接 LB 作为落地能力 | 均未接业务 | 删除或改「公共组件（含单测）」 |
| 8 | 满篇「（2026-09-20）」「事实校准…确保简历与运行态一致」「100%/10/10」 | 自我暴露的审计过程记录 + 满分数字 | 删日期戳与自证句；分数改为 30/30、12/12 原始计数 |

## 5. 数字口径速查（可引用 / 已作废）

可引用：
- product 4,871 RPS / P99 50ms（无本地缓存）；user 5,846 / 40ms；note 4,171 / 71ms；home 1,861 / 49.6ms；search 1,448 / 61ms → `batch-release-baseline-20260917.md:13-19`
- 类加载锁修复 product 1,074→4,871（4.5x）→ 同上
- zone 同区 +34% 吞吐 / P99 -33~-47%（netem）→ `zone-cross-region-latency-20260918.md:17-23`
- Redis failover 2.3s；MySQL RTO≈32s（停主 10.2s/提升 0.087s/切换 22s）→ `mysql-failover-drill-20260917.md`
- 20 并发预扣 149→129；10 并发券 98→96；75s 回查 118→117 → `99-runtime-reconciliation-report.md:88-89,127`
- 分片 131 单 2/16 → 16/16，迁移 682 行 → `sharding-hash-migration-20260920.md:9-33`
- AI：10 案例 1.63min vs 20.6min（保守基线）；hit@1 30/30；答案 50/50（修复后）；轨迹 12/12 + 0.917；N=100 C=5 P50 13.5s/P95 39s → `xhs-ai/docs/reports/*`

已作废（禁止引用）：27,442 RPS（试装 L1 的临时值）、1020/1096/683 旧压测、counter "-80%/98%"、任何"100%""10/10"式满分表述。

---

## 6. 2026-09-23 更新（逐服务深挖 + 收官批次后，事实变化）

**原红线失效（已修，可正常写）：**
- IM「跨实例会话路由走 Redis pub/sub（+ 离线补发/踢旧）」→ 已补**跨实例踢线**（新实例接管路由后定向 Pub/Sub 通知旧实例关连接），旧连接僵尸问题消除。
- search「suggest 权重恒 1、无增量同步」→ 权重已按点赞数 log10 缩放（增量同步仍无，勿写）。
- 「账号锁定信任伪造 XFF」类风险 → 已改**可信 IP 单一来源**（网关覆盖写 X-Real-IP，下游取 XFF 末段），可写。
- 支付「零元单发不起支付」→ 已支持（全额抵扣直接记账成功），可写。
- ES 全量重建断点不清零/死文档残留/无外部版本 → 均已修（断点清零 + 死文档清理 + 外部版本冲突跳过），可写。

**红线勘误（2026-09-23 深度 review 复核）：**
- order「重放未接线」→ 已接线：`POST /api/order/internal/reconcile/{orderId}`（X-Internal-Call；dry-run 默认、repair 显式、终态差异只报告不覆盖）→ 可写"订单状态可按事件流重建校准"。
- common「CacheWarmupRunner key 全错位」→ 现实现改为预热任务委托业务服务自身 warmUp（分类树/热门池），与读路径共用常量 → key 对齐，可写"启动预热框架"（"热门池键带 {global} hash tag 使刷新 RENAME 在 Cluster 合法"为新增可写点）。

**计数口径刷新（2026-09-23 实测，提交前请再跑一次）：** `@XxlJob` 23（19 文件）、`@RateLimit` 业务 45 处、`@Idempotent` 9 处、告警规则 40 条（两份零差异）。

**仍有效红线（勿写）：** TCC 未接订单主链路（只可写"提供 TCC 冻结接口 + 11 场景"）；product 不发 MQ（缓存失效勿写 MQ 广播）；号段 ID / 最少连接 LB 未接默认；支付三渠道为文本 Mock；退券补偿 Outbox 无生产者。

**新增可写事实（本台账 §13/§16 有证据）：** 网关鉴权/HMAC 失败分原因指标 + 告警（40 条规则两份零差异）；Redis 命令超时 fail-closed → 统一 503；公共组件 fail-open 打点；连接池按标准键装配 + Lettuce 指标接线；告警规则 40 条；测试套件 276 例（1 例已知登记失败）。
