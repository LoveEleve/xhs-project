# MyXHS 架构分析文档

<!-- SESSION-1 SUMMARY (2026-07-08) -->
## Session-1 总结

> **供下一个会话快速理解当前状态。** 以下是本轮会话（Session 1）完成的工作摘要。

### 一、工程问题修复（Phase 1-3，共 15 项 P0+P1）

本轮围绕分布式架构工程性问题（"MySQL 主从延迟"级别），分三个阶段修复了 15 项代码层面的缺陷：

**Phase 1（5 项）**：
1. **TCC Fence 防悬挂** — 新增 `TccFenceService` 实现空回滚/悬挂/幂等三合一，DDL 加入 `t_tcc_fence` 表
2. **分布式锁升级** — `PaymentService.pay()`/`refund()` 从 SETNX 升级为 Redisson RLock + 双重检查
3. **缓存一致性** — 延迟双删从 500ms 硬编码改为 `@Value` 可配置
4. **消息可靠性** — 修复 Consumer Rebalance 幂等性 BUG（`@Idempotent` 先于 `@DistributedLock` 导致锁释放后消息被重试）
5. **Zone 切换事务保护** — `DynamicDataSource` 新增 `activeConnectionCount` 等待机制（最多 30s）

**Phase 2（5 项）**：
6. **ES 索引同步补偿** — 新增 `IncrementalIndexSyncJob`，扫描 Redis Set 中失败 docId 并 Bulk API 重建索引
7. **Feed 推送断点续传** — `FeedMessageRetryJob.compensateIncompletePush()` 扫描未完成推送消息并重发
8. **Sentinel 锁丢失保护** — `RedissonConfig` 新增 `lockWatchdogTimeout=15s` 配置 + Javadoc 说明 Sentinel 切换风险
9. **HPA 协调** — 新增 `k8s/hpa-template.yaml`，为 14 个服务 + Gateway 配置弹性伸缩策略
10. **Zone 配置监听** — `ZoneContext` 新增 property change listener 处理 Zone 切换事件

**Phase 3（5 项）**：
11. **`NoteService` 编译错误修复** — `localMsg` 变量使用前声明问题：insert → getID → setLocalMsgId → updateById
12. **`CouponService` 缺失 import** — 补充 `@Transactional` 的 import 语句
13. **`RocketMQHealthIndicator` API 兼容** — RocketMQ 5.x `getDefaultTopicRouteInfoFromNameServer(long)` 不存在，使用反射 + `NoSuchMethodException` 兜底
14. **`PaymentService` ResultCode** — `SYSTEM_BUSY` 不存在，改为 `INTERNAL_ERROR`
15. **`t_inventory` DDL 缺失 `freezing_stock`** — 补充列 + `t_tcc_fence` 表 DDL 合并到 `mysql-inventory-init.sql`

### 二、Docker-Compose 精简

- 原始 docker-compose 需要 ~28-30G 内存，目标机器仅 8C/16G
- 精简至 **~6.5G**（lite 版本）：移除 MySQL slaves ×4、Redis Sentinel ×3、RocketMQ Slave Broker、Logstash、Kibana、SkyWalking、Prometheus、Grafana
- 所有服务添加 `deploy.resources` limits，MySQL buffer-pool 缩减（512M→128M），Redis maxmemory 缩减（256M→128M）
- 3 机器部署方案已规划（machine-a-mysql.yml / machine-b-middleware.yml / machine-c-monitor.yml），但未实际部署

### 三、项目编译

- 全量 `mvn compile` 通过，无编译错误
- 所有 15 项修复的代码变更已就位

### 四、关键文件清单

| 文件 | 变更类型 |
|------|---------|
| `my-xhs-common/.../TccFenceService.java` | 新增 |
| `my-xhs-common/.../RedissonConfig.java` | 修改（lockWatchdogTimeout） |
| `my-xhs-common/.../DynamicDataSource.java` | 修改（activeConnectionCount） |
| `my-xhs-payment/.../PaymentService.java` | 修改（Redisson 锁） |
| `my-xhs-content/.../NoteService.java` | 修复（localMsg 顺序） |
| `my-xhs-content/.../FeedMessageRetryJob.java` | 修改（断点续传） |
| `my-xhs-content/.../LocalMessageMapper.java` | 修改（新增查询） |
| `my-xhs-search/.../IncrementalIndexSyncJob.java` | 新增 |
| `my-xhs-search/.../NoteIndexSyncConsumer.java` | 修改（失败记录） |
| `my-xhs-search/.../ProductIndexSyncConsumer.java` | 修改（失败记录） |
| `my-xhs-coupon/.../CouponService.java` | 修复（缺失 import） |
| `my-xhs-common/.../RocketMQHealthIndicator.java` | 修复（API 兼容） |
| `sql/mysql-inventory-init.sql` | 修改（DDL 补充） |
| `k8s/hpa-template.yaml` | 新增 |
| `docker-compose.yml` | 重写（精简至 ~6.5G） |
| `deploy/machine-a-mysql.yml` | 新增 |
| `deploy/machine-b-middleware.yml` | 新增 |
| `deploy/machine-c-monitor.yml` | 新增（未完成） |

### 五、待完成项

- 3 机器部署方案中的 machine-c-monitor.yml 未完成
- 微服务拆分建议（counter 合并、payment 合并、search+recommend 拆分）尚未实施
- README 中框架深度定制系列（41-55 号文档）尚未编写内容
- 安全运维类 P0 项（密钥明文、Nacos 集群化、Alertmanager 部署）未修复

<!-- END SESSION-1 SUMMARY -->

---

## 项目定级

**对标 P7+ ~ P8 技术专家水平**。本文档面向有经验的分布式系统工程师，每篇文档回答三个问题：
- **为什么这样设计？**（方案对比 + 选择理由）
- **边界条件怎么处理？**（异常路径 + 容错策略）
- **代价是什么？**（已知局限 + 改进方向）

## 文档体系

共 **60 篇**，分 10 大类，扁平结构不设子文件夹。

---

## 一、系统级（2 篇）

| 编号 | 标题 | 主题 |
|------|------|------|
| 01 | `01-system-overview.md` | **系统全景**：为什么是 17 个微服务（含 test + benchmark）而不是 6 个？拆分边界在哪？各模块职责矩阵、技术栈全景、部署拓扑、ShardingSphere 分库分表（order 4 库 × 4 表）、Zone 多活架构、API 多版本路由（@ApiVersion）、XXL-Job 分布式调度（15 个任务接入 7 个服务） |
| 02 | `02-module-interaction.md` | **模块交互拓扑**：17 个模块的调用关系图（含 15 个 @FeignClient 调用矩阵）、RocketMQ 17 个 Topic + 22 个 Consumer 事件流全景、SOCIAL_TOPIC tag 过滤机制（6 Consumer 共享 1 Topic）、ORDER_TRANSACTION_TOPIC 事务消息流程、BFF 聚合层拓扑（home 并行调用 11 个下游服务）、数据流方向、瓶颈链路分析、最长调用链追踪 |

---

## 二、基础设施（10 篇）← 增加 1 篇

| 编号 | 标题 | 主题 |
|------|------|------|
| 03 | `03-common-infrastructure.md` | **common 模块全景**：30+ 配置类 + AutoConfiguration 组件的注册顺序与依赖关系、为什么放 common 而不是独立模块、组件分类与职责矩阵、**核心组件**：ReadWriteRoutingDataSource（读写分离路由）、ResponseAutoWrapper（R<T> 自动包装）、ApiVersionHandlerMapping（多版本路由）、LeastConnectionsLoadBalancer（最少连接负载均衡）、FeignUnifiedConfig（R<T> 自动解包）、XxlJobConfig（分布式调度） |
| 04 | `04-aop-three-layer-defense.md` | **三层 AOP 防护**：@RateLimit → @DistributedLock → @Idempotent 的顺序设计依据、Redis 不可用时的降级放行策略、Lua 脚本实现细节、SpEL 动态 Key 解析 |
| 05 | `05-trace-context-propagation.md` | **全链路流量染色**：6 个染色标记（traceId/userId/grayTag/apiVersion/abGroup/pressureTest）的设计依据、HTTP Header → Feign → RocketMQ 三通道透传机制（TraceIdConfig 拦截器 → FeignTraceInterceptorConfig RequestInterceptor → MqTraceHelper 发送/消费）、ThreadLocal 跨线程传递（TransmittableThreadLocal + TaskDecorator）、ShadowTableInterceptor 影子表路由（压测流量自动替换表名）、23 个 Consumer 统一使用 MqTraceHelper 恢复/清理 TraceId |
| 06 | `06-cache-strategy.md` | **缓存体系**：Cache Aside 模式封装、三层一致性保障（删缓存 + 延迟双删 500ms + MQ 兜底 CACHE_EVICT_TOPIC）、Caffeine + Redis 多级缓存、Redisson 布隆过滤器、防穿透/雪崩/击穿、**Canal 缓存失效 + 回填机制**、**缓存预热（CacheWarmupRunner）**、**分布式锁防击穿（CacheHelper.getWithCacheAsideLock Singleflight 模式）** |
| 07 | `07-chaos-engineering.md` | **混沌工程**：自研故障注入框架设计（DELAY/EXCEPTION/RETURN_NULL）、通配符目标方法匹配、概率触发机制、与 ChaosBlade/Litmus 的对比、**chaos-drill.sh 7 个演练场景**（Redis 不可用/网络延迟 3s/MQ Broker 不可用/CPU 满载/磁盘 IO 高负载/MySQL 不可用/优雅停机验证）、自研边界 |
| 08 | `08-graceful-shutdown.md` | **优雅停机**：Nacos 主动注销 → 等待服务列表传播 → 执行 ShutdownHook → 关闭线程池的完整流程、与 K8s terminationGracePeriodSeconds 的配合、超时处理、**GracefulShutdownHook 接口 + Counter Buffer 刷盘案例** |
| 09 | `09-sql-guard-interceptor.md` | **SQL 熔断**：MyBatis Interceptor 慢 SQL 检测（>200ms）、连续 5 次熔断 + 30 秒冷却自动恢复、阈值选择依据、熔断后行为 |
| 10 | `10-data-generator.md` | **数据生成器**：可插拔 DataGenerator 接口设计、CommandLineRunner 启动执行、scale 参数控制数据规模、User/Note/Product 三个生成器实现、关联数据一致性保证 |
| 11 | `11-id-generator.md` | **ID 生成器**：雪花 ID（MyBatis-Plus IdWorker）+ 号段模式（SegmentIdGenerator）+ Redis 自增流水号三合一设计、各自适用场景、降级策略 |
| 12 | `12-degrade-switch-and-config.md` | **[新增]** **降级开关与动态配置**：DegradeSwitchManager 的 5 个降级开关（推荐/Feed/通知/搜索/全站热门降级）、DynamicConfigRefresher 监听 Nacos EnvironmentChangeEvent（配置变更审计 oldValue→newValue + 合法性校验 + 异步处理 + 钉钉/企微通知钩子）、Nacos @RefreshScope 动态刷新机制、Tomcat 线程池定制参数（MyXhsTomcatCustomizer）、Feign 全局关闭重试 + ErrorDecoder（FeignSafeConfig）、BOM 依赖版本管理策略 |

---

## 三、网关（2 篇）

| 编号 | 标题 | 主题 |
|------|------|------|
| 13 | `13-gateway-security.md` | **网关安全纵深防御**：JWT 双 Token（Access 30min + Refresh 7d）+ Token 黑名单（Fail-Closed）+ HMAC-SHA256 签名防篡改防重放（timestamp 5min + nonce Redis SETNX）、CORS 域名白名单、压测流量来源校验（仅允许内网段）、**HMAC 密钥与 JWT 密钥分离的设计决策**、**Gateway 与 User 服务黑名单 Key 同步问题（WebFlux vs Servlet）** |
| 14 | `14-gateway-filter-chain.md` | **7 层过滤器链**：实际执行顺序（日志→鉴权→染色→HMAC→限流→灰度→版本）的设计依据、职责分离、异常传播机制、每个 Filter 的异常降级策略、**CachingFilteringWebHandler 性能优化**（按 routeId 缓存合并后的 Filter 列表避免每次排序）、**GlobalExceptionHandler 统一异常处理**（ConnectException→503、TimeoutException→504、NotFoundException→404、其他→500，5xx 不暴露内部详情）、**业务服务层 @ApiVersion 注解双重路由策略**（Gateway Header 路由 + WebMVC HandlerMapping 路由） |

---

## 四、业务模块（13 篇）— 每篇补充了大量遗漏的设计细节

| 编号 | 标题 | 主题 |
|------|------|------|
| 15 | `15-inventory.md` | **库存服务（★★★★★）**：Redis 分桶预扣设计（userId % N 路由，默认 2 桶 + 热点 8 桶）、Lua 原子扣减脚本、三级扣减保证（L1 Redis → L2 MQ 异步 MySQL 乐观锁重试 3 次 → L3 定时对账修复）、预扣超时回收（5 分钟 Job + 惰性删除主动扫描）、**TCC Try/Confirm/Cancel 接口**（available -= qty/freezing += qty → freezing -= qty → freezing -= qty/available += qty）、**TCC Fence 防悬挂**（TccFenceService 空回滚/悬挂/幂等三合一）、**热点 SKU 动态分桶扩容（HotSkuDetector + resizeBuckets）**、**Canal 缓存失效 + 回填（reloadStockToRedis）**、**分桶完整性对账（bucketSum vs total）**、分桶数 N 的确定依据、Redis Sentinel 下 Lua 的 slot 限制、桶用完的扩容方案 |
| 16 | `16-payment.md` | **支付服务（★★★★★）**：支付状态机 + 退款状态机、**策略模式 PayChannelStrategy（Mock/Alipay/Wechat 三实现 + PayStrategyConfig Map 注册 + PayCallbackSimulator 异步回调模拟）**、**部分退款设计（多次退款 + getRefundedAmount + maxRefundable）**、**支付/退款双超时设计（30min/15 天 + Lua 原子超时判断）**、**对账 + 自动补偿机制**（每天凌晨扫描支付成功但订单仍待支付的记录，Feign 调 Order 核对 + 不一致自动 notifyPaySuccess）、**支付/退款双幂等设计（Redis SETNX + 乐观锁双重保障）**、**4 个补偿/超时检查 Job**（PaymentTimeoutCheck / PaymentNotifyCompensate / RefundTimeoutCheck / RefundNotifyCompensate）、独立数据库 my_xhs_payment、流水号生成 |
| 17 | `17-order.md` | **订单服务（★★★★★）**：RocketMQ 事务消息（半消息 + 本地事务 + 回查 LocalMessage）、订单状态机（6 状态 + canTransitTo 流转校验）、**Event Sourcing 事件溯源**（OrderEventService 事件追加 + 状态重放 + 完整事件流审计）、乐观锁防并发、延时关单（30min）+ 定时任务兜底、补偿消息机制（Feign 失败 → ORDER_COMPENSATION_TOPIC → 重试）、分库分表（ShardingSphere 4 库 × 4 表）、**订单号映射表路由**（orderNo→userId→orderId 解决分库分表后非分片键查询）、**本地消息表重试机制**（LocalMessageRetryJob 指数退避 30s/60s/120s/240s/480s 最多 5 次） |
| 18 | `18-content.md` | **内容服务（★★★★）**：DFA 敏感词过滤器（Trie 树 O(n) 匹配 + **双词库：静态文件 + Redis Set 动态运营 + Pub/Sub 多实例广播**）、**双重状态管理（AuditStatus 审核 + NoteStatus 业务 + canTransitTo 流转校验）**、**事务消息发布笔记（本地事务写 DB + LocalMessage → afterCommit 异步 Feed 推送）**、**文件存储的扩展性设计（FileStorageService 接口抽象 + LocalFileStorageService → 生产切 MinIO 只需加实现类）**、评论系统 |
| 19 | `19-home-feed.md` | **首页 Feed（★★★★）**：推拉混合模型（普通用户写扩散 Pipeline 批量 ZADD + 大 V 读时拉取）、**大 V 阈值 10 万粉丝（10 分钟缓存防跨越写入风暴）**、BFF 聚合层（9 个 Feign + 2 层并行编排 + 全局超时）、**双线程池隔离设计（aggregatorPool 20/50/200 + batchFeignPool 30/80/500 + CallerRunsPolicy + MdcAwareExecutorService）**、Redis ZSet 游标分页（score 精度处理）、**收件箱容量管理（7 天过期 + 500 条上限 + FeedCleanupJob 定时裁剪）**、**Feed 去重策略分析（ZADD 天然幂等 + 合并去重依赖 noteId）** |
| 20 | `20-search.md` | **搜索服务（★★★★）**：ES 8.x Client + **Search After 深分页（tiebreaker：relevance/time/hot + fastjson2 序列化游标）** + multi_match 权重调优（标题^3, 内容）、**热搜指数衰减算法（完整数学公式 Score = Σ(count × e^(-λ×Δt)) λ=0.1 + 60 个分钟桶滑动窗口 + 原子 RENAME 更新排行榜）**、**搜索反作弊系统（用户维度 300s 限频 + IP 维度 10 次/min + 屏蔽词检查 + Lua 原子操作）**、**热搜人工干预（置顶/屏蔽 + 标签系统："爆"/"热"/"新"）**、搜索建议（ES Completion Suggester + Redis 二级缓存）、搜索历史（Redis List + Lua 原子操作）、3 个 MQ Consumer 同步索引 |
| 21 | `21-im.md` | **即时通讯（★★★★）**：WebSocket 长连接管理（ConcurrentHashMap + 单设备踢旧 + 心跳续期路由 TTL 90s + Lua 安全注销路由）、**写扩散双写（事务内 4 操作：2 条消息 + 2 条会话更新 + 会话级序列号 Redis INCR 保证严格有序）**、一致性 Hash 路由（TreeMap + 150 虚拟节点 + FNV-1a + 实例指纹缓存）、**跨实例路由架构（Redis Pub/Sub 精准投递 vs RocketMQ 广播的对比）**、**消息可靠性三级降级（先持久化 DB → 路由投递 → 离线兜底）**、**离线消息 Sorted Set 设计（O(log N) vs List O(N) + 最多 1000 条 + ACK 确认删除 + Lua 原子存储）**、已读回执 + 未读计数 |
| 22 | `22-product.md` | **商品服务（★★★）**：SPU/SKU 管理、Caffeine + Redis 两级缓存 + 布隆过滤器防穿透、MQ 缓存驱逐、ProductStatus 枚举状态管理 |
| 23 | `23-cart.md` | **购物车服务（★★★）**：Redis 三结构协同（Hash 商品+数量 + Set 选中 + ZSet 加购时间）、Lua 原子操作、Feign 查 Product 获取详情、MQ 异步持久化 MySQL |
| 24 | `24-coupon.md` | **优惠券服务（★★★）**：Lua 原子领券（扣库存 + 限领校验一步到位，hash tag `{templateId}` 保证同 slot）、**责任链用券校验（3 节点：AmountValidator → ExpireValidator → StatusValidator + @Order 排序 + 链式执行）**、**退券流程 Redis+MySQL 双写一致性（return_coupon.lua + 乐观锁）**、MQ 同步写 DB + 失败回滚 Redis 库存、券过期 Job、券模板二级缓存（空值 60s 防穿透） |
| 25 | `25-counter.md` | **计数服务（★★★）**：CounterBuffer 攒批刷盘设计（缓冲区批量写入 DB）、Redis INCR/DECR 实时更新 + 归零保护 Lua、MQ 事件驱动、定时对账修复、为什么不用 Caffeine |
| 26 | `26-notification.md` | **通知服务（★★★）**：SSE 实时推送（SseEmitterManager + 跨实例 Redis Pub/Sub 广播）、5 分钟窗口聚合器（同类通知合并）、未读计数 + 对账 Job |
| 27 | `27-analytics.md` | **数据分析（★★★）**：Redis Set 存储关系数据（点赞/收藏/关注）、6 个 MQ Consumer 成对处理（Like/Unlike 等）+ DuplicateKeyException 幂等、Pipeline 批量查询、**社交关系对账修复（FollowCounterRepairJob：Redis ZCARD vs MySQL 计数修复）**、双层幂等（@Idempotent + SADD 业务层）、内存估算、何时迁图数据库 |

---

## 五、横切主题（5 篇）

| 编号 | 标题 | 主题 |
|------|------|------|
| 28 | `28-distributed-transaction.md` | **分布式事务方案全景**：RocketMQ 事务消息 vs **自研 TCC Fence**（TccFenceService 参考 Seata 设计，t_tcc_fence 表实现 Try-Confirm-Cancel 幂等/空回滚/防悬挂）vs SAGA vs 本地消息表的场景选择、当前方案覆盖范围（order 下单 + inventory 预扣 + coupon 核销）、未覆盖场景（支付回调异常？）、LocalMessage 补发机制（指数退避重试）、**本地消息表重试**（LocalMessageRetryJob + FeedMessageRetryJob）、**支付/退款补偿 Job**（4 个 XXL-Job 任务）、**MessageIdempotentHelper MQ 消息幂等辅助工具** |
| 29 | `29-mq-design.md` | **消息队列设计**：Topic 全景图（17 个 Topic + 22 个 Consumer 完整清单表，含生产者/消费者/幂等策略）、**SOCIAL_TOPIC tag 过滤机制**（6 个 Consumer 共享 1 个 Topic，FollowConsumer tag=follow / LikeConsumer tag=like 等）、**ORDER_TRANSACTION_TOPIC 事务消息模式**、命名规范、延时消息等级选择（delayLevel=16 = 30min）、消费幂等策略、消息积压监控、MqTraceHelper 链路透传、**MessageIdempotentHelper 消息幂等辅助工具**、**DLQ 死信队列处理（DlqMessageHandler 模板方法）** |
| 30 | `30-data-sharding.md` | **分库分表**：ShardingSphere 5.5.1 分片策略（user_id % 4 → 库，user_id / 4 % 4 → 表）、分片键选择依据、订单号映射表路由方案、非分片键查询的性能瓶颈、扩容方案 |
| 31 | `31-observability.md` | **可观测性体系**：Prometheus 4 组 21 条告警规则设计（P0-P2 分级）、4 个 Grafana Dashboard（API监控/业务指标/JVM监控/Tomcat监控）、SkyWalking 全链路追踪（OAP 9.7 + UI + ES 存储）、**SkyWalking 手动 Span 埋点**（BizSpanHelper 反射调用降级为空操作，覆盖 HTTP/Feign/MQ 自动埋点 + 业务 Span 手动创建）、**BusinessMetrics 自定义业务指标**（Micrometer Counter/Timer/Gauge，覆盖订单/库存/支付/Feed/优惠券维度）、**Logstash 日志管道**（采集 /logs/*.json → Grok 提取服务名 → ES myxhs-logs-YYYY.MM.dd）、**JSON 结构化日志（LogstashEncoder 已声明依赖但未启用，当前用 PatternLayout）**、健康检查端点（自定义 ApplicationReadinessIndicator：堆内存>90% + 死锁检测）、告警阈值选择依据、**缺失：AlertManager 通知渠道、node_exporter、中间件 exporter、慢查询告警、Feign 调用失败率告警** |
| 32 | `32-cicd-deployment.md` | **CI/CD 与部署**：GitLab CI 5 阶段流水线（compile → test → check → build → deploy）、多阶段 Docker 构建（maven:3.9 + eclipse-temurin:17-jre-alpine + 非 root 用户 + HEALTHCHECK + SkyWalking Agent 支持）、K8s 部署模板（Deployment/Service/Ingress/ConfigMap/Namespace）、Docker Compose 24 容器编排（8 MySQL + 6 Redis/Sentinel + 2 RocketMQ NameServer + 2 Broker + ES + Logstash + Kibana + Canal + Nacos + XXL-Job + Sentinel Dashboard + Prometheus + Grafana + SkyWalking OAP + UI）、**全量冒烟测试**（smoke-test.sh 覆盖 15 个服务 80+ 端点）、**运维脚本套件**（start-all.sh 5 组依赖顺序 / stop-all.sh 优雅停止 kill -15 → kill -9 / setup-firewall.sh / mysql-backup.sh / redis-backup.sh / es-backup.sh）、**混沌工程脚本**（chaos-drill.sh 7 个演练场景 + 自动生成演练报告）、**缺失：HPA/PDB/ResourceQuota/NetworkPolicy/Secret/多环境 Profile/蓝绿部署/密钥管理** |
| 33 | `33-data-storage-design.md` | **[新增]** **数据存储设计全景**：4 个 MySQL 实例 12 个数据库 27 张表的完整 ER 关系、订单分片策略（4 库 × 4 表 = 16 张分表 + 公共映射表）、Redis 数据结构全景（50+ Key 前缀覆盖 8 个数据类型 String/ZSet/Set/Hash/PubSub/BloomFilter/Lock + 未使用的 Stream/HyperLogLog/Bitmap/Geo 分析）、ES 3 个索引设计（note_index 3shard / product_index 3shard / suggest_index 1shard + Search After 深分页 + 权重调优）、Canal 3 个 Instance 同步链路（t_note→note_index / t_spu+t_sku→product_index / t_inventory→Redis 缓存失效 + MQ Consumer 双通道保障 + IndexRebuildJob 凌晨全量补偿）、**文件存储（本地磁盘 + FileStorageService 接口抽象 + 生产 MinIO/OSS 扩展预留）**、**两种 t_local_message 表的不同用途（订单事务消息回查 vs Feed 推送可靠性）**、**索引设计分析（9 个关键索引 + 3 个缺失索引建议）**、**缺失：视频上传/无 CDN/无 ES 索引别名和 ILM/无 MinIO 实现** |

---

## 六、多活架构（1 篇）← 新增

| 编号 | 标题 | 主题 |
|------|------|------|
| 37 | `37-multiactive-architecture.md` | **[新增]** **Zone 多活架构设计**：为什么需要多活（单 Zone 风险 + 方案对比）、Zone 核心抽象层（ZoneContext 单例状态管理器 + ZonePreferenceFilter 10 步决策算法 + ZoneResolver 解析器接口）、LoadBalancer Zone 优先（ServiceInstanceZoneResolver + ZonePreferenceServiceInstanceListSupplier + Reactive/Blocking 双配置）、Nacos metadata Zone 上报（15 个服务 + 环境变量 + 系统属性覆盖）、Gateway Zone 优先（设计文档 + 集成指南）、配置属性完整清单、代价与边界（生效范围/数据一致性/切换延迟/已知局限）、Zone 切换流程 |

---

## 七、框架深度定制补充（3 篇）← 新增

> 补充 Explore-7 探索发现的框架级设计模式实践，这些模式在代码中已实现但 README 未独立规划文档。

| 编号 | 标题 | 主题 |
|------|------|------|
| 37 | `37-domain-event-bus.md` | **[新增]** **领域事件总线**：DomainEvent + AbstractDomainEvent + DomainEventPublisher 自研事件接口体系、Spring ApplicationEvent 本地事件发布（RedisCommandEvent、GracefulShutdownListener、DynamicConfigRefresher）、三层事件架构（DomainEvent 本地事件 → Spring 事件总线 → RocketMQ 分布式事件）、与 Event Sourcing 的协作（OrderEventService 事件追加 + 状态重放）、代价：分布式事件无事务保障、事件顺序无保证 |
| 38 | `38-strategy-pattern.md` | **[新增]** **策略模式实践**：支付渠道策略（PayChannelStrategy 接口 + Mock/Alipay/Wechat 三实现 + PayStrategyConfig Map 注册）、推荐召回策略（RecallStrategy 接口 + Hot/ItemCF/Geo/Following/Content 五实现）、对比 if-else 工厂模式 vs 策略模式的优劣、策略动态选择与注册机制、代价：策略膨胀时管理复杂度 |
| 39 | `39-template-method.md` | **[新增]** **模板方法模式实践**：AbstractSearchService（Search After 解析/分页规范化/类型转换基类，NoteSearchService + ProductSearchService 继承）、DlqMessageHandler（DLQ 处理模板，子类实现 doHandle 方法）、AuditLogService（审计日志模板，recordAuditLog(userId, action, detail) 使用 REQUIRES_NEW 事务）、代价：基类修改影响所有子类、模板方法粒度难控制 |

---

## 八、工程问题专题（5 篇）← 新增

> 来源：Explore 8-11 工程性问题深度探索（2026-07-08）
> 核心理念：**架构文档不能只写"有什么"，更要写"踩了什么坑"。工程性问题是区分"能用"和"生产级"的关键。**

| 编号 | 标题 | 主题 |
|------|------|------|
| 40 | `40-mysql-engineering-issues.md` | **[新增]** **MySQL 工程性问题**：主从延迟与延迟双删 500ms 硬编码（为什么 500ms、主从延迟超 500ms 怎么办、MQ 兜底够不够）、分片键选择与跨分片查询的矛盾（订单号映射表单点风险 + 基因法替代方案）、分布式 ID 两套雪花算法共存风险（MyBatis-Plus IdWorker vs ShardingSphere Snowflake + 时钟回拨处理）、ShardingSphere 与 HikariCP 连接池叠加（4 分片 × 10 连接 + 2 独立池 + MySQL wait_timeout 验证）、支付服务事务边界缺失（pay() 无 @Transactional，DB 写入和 Redis 操作不在同一事务）、扩容方案缺失（% 4 硬编码，扩展到 8 库需全量迁移）、`autoReconnect=true` 废弃风险（事务上下文丢失）、读库连接池可能不足（读多写少时 10 连接瓶颈）、慢 SQL 指纹 hashCode 碰撞、Canal 延迟导致 ES 不一致的补偿方案 |
| 41 | `41-mq-distributed-engineering-issues.md` | **[新增]** **消息队列与分布式工程性问题**：ASYNC_FLUSH + ASYNC_MASTER 宕机丢消息风险（为什么不用 SYNC、本地消息表兜底够不够）、事务消息回查 UNKNOWN 无超时限制（DB 不可用时消息永久停留半消息状态）、幂等降级放行的影响范围（Redis 不可用时哪些消费者缺 DB 乐观锁兜底）、WatchDog 在 GC 暂停时无法续期（Full GC > 30s 导致锁过期被抢占）、Redis 主从切换锁丢失（Sentinel 切换期间数据未同步）、分布式锁降级放行并发安全问题（Redis 故障时 @DistributedLock 形同虚设）、XXL-Job Admin 单点（调度中心宕机所有定时任务停止）、PreDeductTimeoutJob 超时锁提前释放、补偿消息无死循环保护、@Scheduled 与 @XxlJob 混合调度的运维复杂度、MessageQueueSelector 未使用的场景分析 |
| 42 | `42-cache-concurrency-engineering-issues.md` | **[新增]** **缓存与并发工程性问题**：CacheHelper 防击穿锁等待只重试 1 次（高并发热点 Key 场景缓存击穿防护失效，建议指数退避 3-5 次）、库存扩容暂停期间直接抛异常（用户体验差，应返回可重试错误码或双 Buffer 无暂停扩容）、Feign connect-timeout=500ms 过短（生产环境网络抖动频繁超时，建议 1000-3000ms）、Jackson + fastjson2 混用序列化兼容性风险（不同库日期格式/命名策略不一致导致反序列化失败）、布隆过滤器 1% 误判率无定时重建（SPU 数量超 100 万后误判率上升，下架 SPU 无法移除）、TTL 随机偏移仅 TTL/6（分散度 16.7%，建议扩大到 20-30%）、Sentinel 无本地默认兜底限流规则（Nacos 不可用时无限流保护）、热点检测仅覆盖库存服务（SPU/笔记详情等高流量接口缺失）、读接口缺少 @RateLimit 限流、RUnpackDecoder 双次 JSON 解析性能开销、CompletableFuture CallerRunsPolicy 高峰期阻塞 Tomcat 线程、JVM 缺少 GC 日志参数 |
| 43 | `43-security-operations-engineering-issues.md` | **[新增]** **安全与运维工程性问题**：所有密钥密码明文硬编码（JWT secret/HMAC secret/MySQL/Redis/ES/XXL-Job 密码全部暴露在 Git 中 + 全部使用相同密码模式 Xhs@2026#）、Nacos standalone 单点（服务注册发现+配置中心核心依赖单点）、Elasticsearch 单节点（无数据冗余，宕机搜索不可用）、Prometheus + Grafana 单点（告警评估+可视化核心单点）、Alertmanager 未配置（21 条告警规则形同虚设）、Canal 单点（MySQL→MQ 数据同步链路单点）、Sentinel Dashboard 无认证保护、WebSocket allowedOrigins 默认 `*`、JWT secret 在 user/gateway 两个服务中默认值不同、接口级别细粒度权限控制缺失（只能校验登录态，不能校验资源归属）、AuditLogService 无子类实现（无法追溯操作审计）、SkyWalking Agent 未默认集成、MySQL 主从切换非自动 |
| 44 | `44-engineering-issues-roadmap.md` | **[新增]** **工程问题修复路线图**：按 P0/P1/P2 排列全部 78 项工程性问题，每项标注严重程度、影响范围、修复难度、预估工作量、修复方案建议。P0 紧急修复项：密钥明文（Jasypt/Vault）、CacheHelper 防击穿重试、支付事务边界、Nacos 集群化、Alertmanager 部署。P1 重要修复项：延迟双删可配置化、分布式 ID 统一、序列化库统一、Feign 超时调优、布隆过滤器重建、Sentinel 本地兜底规则。P2 优化项：扩容方案设计、慢 SQL 指纹、读接口限流、RUnpackDecoder 性能优化 |

---

## 九、收尾（3 篇）

| 编号 | 标题 | 主题 |
|------|------|------|
| 34 | `34-security-deep-dive.md` | **[新增]** **安全体系深度分析**：HMAC 签名密钥管理（当前明文存储，需 Jasypt/Vault/K8s Secrets）、Token 黑名单跨服务同步问题（Gateway WebFlux vs User Servlet 无法共用 common 模块）、**密码加密（BCrypt 已实现）vs 传输加密（MySQL useSSL=false）vs 存储加密（密码明文）**、压测流量安全控制（仅内网段可设 X-Pressure-Test）、**已实现的安全机制**：BCrypt 密码加密、防暴力破解（5 次锁定 15 分钟）、图形验证码（Redis 5 分钟 TTL）、DFA 敏感词过滤（Trie 树 + Redis Pub/Sub 多实例广播）、影子表压测隔离（ShadowTableInterceptor）、@RateLimit/@Idempotent/@DistributedLock AOP 防护、Spring Security 排除（Gateway 自定义 Filter 链替代）、**代码未实现**：@Desensitize 数据脱敏（当前仅手动 maskPhone/maskEmail）、XssFilter XSS 防护（完全未实现）、@AuditLog 操作审计（当前为模板类继承 AuditLogService，非注解驱动）、RBAC 权限管理、CSRF 防护、IP 黑名单/白名单、HTTP 安全响应头（CSP/HSTS/X-Frame-Options） |
| 35 | `35-engineering-maturity.md` | **[新增]** **工程化成熟度评估**：**Maven 插件缺失（JaCoCo 覆盖率/SpotBugs/Checkstyle/PMD/enforcer/OWASP dependency-check 均未配置）**、**代码规范缺失（无 .editorconfig/checkstyle.xml/spotbugs-exclude.xml）**、**多环境 Profile 完全缺失（K8s 有 -Dspring.profiles.active=k8s 但无对应 application-k8s.yml）**、依赖版本管理策略（BOM 导入顺序：Jackson > Spring Boot > Spring Cloud > Spring Cloud Alibaba）、**测试体系现状（7 个测试类 35 个用例，仅覆盖 common 模块 + Testcontainers 版本已管理但未使用 + 无 Spring Cloud Contract）**、API 文档缺失（无 Swagger/OpenAPI/Knife4j）、Git Commit ID 未注入构建信息、**动态降级开关管理**（DegradeSwitchManager 5 个开关 + DynamicConfigRefresher 审计校验）、**Sentinel 舱壁隔离**（不同 Feign 调用独立线程池，threadPoolMaxSize=10/maxQueueSize=20）、**Gateway 三维度限流**（按 IP/用户 ID/请求路径）、**~~InventoryService/SpuService 线程池无 @PreDestroy 关闭~~ → ✅ 已完成** |
| 36 | `36-known-issues-and-roadmap.md` | **已知不足与改进路线图**：按严重程度（P0-P3）排列的所有已知问题 + **18 项文档已规划但代码未实现的功能** + **~~P0 Bug: @EnableScheduling 缺失~~ → ✅ 已完成** + **~~P2 Bug: InventoryService/SpuService 线程池无关闭~~ → ✅ 已完成** + 每项问题的影响范围 + 改进方案建议 + 预估工作量 + 优先级排序 |

---

## 编写原则

1. **不罗列代码**：引用文件路径 + 行号即可，代码在仓库里
2. **必须回答 Why**：每个设计决策都要有方案对比和选择理由
3. **必须承认代价**：没有完美方案，诚实写出当前设计的局限和取舍
4. **必须有数据支撑**：QPS 目标、一致性时间窗口、内存估算、性能瓶颈
5. **交叉引用**：模块文档之间用 `→ 见 17-order.md#事务消息` 方式链接
6. **每篇 300-500 行**：够深入但不冗余
7. **面向技术评审**：读者是 P7+ 水平工程师，不解释基础概念（如"什么是分布式锁"）

## 阅读路径建议

- **快速了解系统**：01 → 02 → 36
- **基础设施深入**：03 → 04 → 05 → 06 → 12
- **核心业务链路**：15 → 17 → 16 → 28（下单全链路：库存→订单→支付→分布式事务）
- **内容社交链路**：18 → 25 → 19 → 20（笔记发布→计数→Feed→搜索）
- **安全与运维**：13 → 34 → 31 → 32
- **数据存储**：33（数据存储设计全景）
- **多活架构**：37（Zone 多活架构设计）
- **设计模式实践**：37-domain-event-bus → 38-strategy-pattern → 39-template-method
- **工程问题专题**：40 → 41 → 42 → 43 → 44（MySQL/MQ/缓存/安全运维/修复路线图，共 78 项工程问题）
- **框架深度定制**：45 → 59（Spring/Boot/Cloud 扩展点深度解析）
- **工程化评估**：35 → 36

---

## 本次审查发现的关键遗漏汇总

### 代码中已实现但原规划未覆盖（已全部纳入新规划）

| 遗漏设计 | 所属模块 | 纳入编号 |
|---------|---------|---------|
| 热点 SKU 动态分桶扩容（HotSkuDetector + resizeBuckets） | inventory | 15 |
| Canal 缓存失效 + 回填机制 | inventory | 06/15 |
| 三级扣减的 L2 重试策略（3 次退避） | inventory | 15 |
| 分桶完整性对账（bucketSum vs total） | inventory | 15 |
| 部分退款设计（多次退款 + maxRefundable） | payment | 16 |
| 支付/退款双超时 + Lua 原子超时判断 | payment | 16 |
| 对账 + 自动补偿机制 | payment | 16 |
| 支付/退款双幂等（Redis SETNX + 乐观锁） | payment | 16 |
| 支付策略模式（PayChannelStrategy + 3 实现） | payment | 16/38 |
| 支付/退款 4 个补偿 Job | payment | 16/28 |
| DFA 双词库（静态文件 + Redis Set 动态 + Pub/Sub 广播） | content | 18 |
| 笔记状态机流转校验（canTransitTo） | content | 18 |
| 本地消息表 + Feed 推送事务保障 | content | 18 |
| 文件存储的扩展性设计（接口抽象 + 多实现） | content | 18 |
| BFF 双线程池隔离（防嵌套 CompletableFuture 饥饿） | home | 19 |
| 大 V 阈值 10 万粉丝 + 缓存时间设计考量 | home | 19 |
| 收件箱容量管理（7 天 + 500 条 + 定时裁剪） | home | 19 |
| Feed 去重策略分析 | home | 19 |
| 热搜指数衰减算法（完整数学公式 + 滑动窗口） | search | 20 |
| 搜索反作弊系统（用户/IP 双维度 + 屏蔽词） | search | 20 |
| 热搜人工干预机制（置顶/屏蔽 + 标签系统） | search | 20 |
| Search After tiebreaker 设计 | search | 20 |
| IM 跨实例路由（Redis Pub/Sub 精准投递 vs MQ 广播） | im | 21 |
| 消息可靠性三级降级（持久化 → 路由 → 离线兜底） | im | 21 |
| 会话级序列号保证消息有序 | im | 21 |
| 离线消息 Sorted Set 设计（为何优于 List） | im | 21 |
| WebSocket 心跳 + 路由 TTL 设计 | im | 21 |
| 责任链用券校验（3 节点 + @Order 排序） | coupon | 24 |
| 退券流程 Redis+MySQL 双写一致性 | coupon | 24 |
| 社交关系对账修复（ZCARD vs MySQL） | analytics | 27 |
| 降级开关管理（5 个开关 + Nacos @RefreshScope） | common | 12 |
| Tomcat 线程池定制参数 | common | 12 |
| Feign 全局关闭重试 + ErrorDecoder | common | 12 |
| BOM 依赖版本管理策略 | common | 12 |
| 安全体系全景（已实现 vs 规划中的对比） | 横切 | 34 |
| 工程化成熟度评估（插件/规范/测试/多环境） | 横切 | 35 |

### 本轮探索（Explore 2-7）新发现的遗漏（已全部纳入新规划）

| 遗漏设计 | 所属模块 | 纳入编号 |
|---------|---------|---------|
| Event Sourcing 事件溯源（OrderEventService 事件追加 + 状态重放） | order | 17 |
| 订单号映射表路由（orderNo→userId→orderId） | order | 17/30 |
| 本地消息表重试机制（LocalMessageRetryJob 指数退避） | order/content | 17/28 |
| TCC Fence 自研机制（TccFenceService 空回滚/悬挂/幂等） | common/inventory | 15/28 |
| 领域事件总线（DomainEvent + DomainEventPublisher + 三层架构） | common | 37 |
| 策略模式实践（支付 3 策略 + 推荐 5 策略） | payment/search | 38 |
| 模板方法模式实践（AbstractSearchService/DlqMessageHandler/AuditLogService） | common/search | 39 |
| BFF 聚合层拓扑（home 并行调用 11 个下游） | home | 02/19 |
| 读写分离路由数据源（ReadWriteRoutingDataSource 三级路由） | common | 03 |
| 动态配置刷新 + 审计（DynamicConfigRefresher oldValue→newValue） | common | 12 |
| Gateway 执行顺序更正（日志→鉴权→染色→HMAC→限流→灰度→版本） | gateway | 14 |
| CachingFilteringWebHandler 性能优化 | gateway | 14 |
| GlobalExceptionHandler 统一异常处理 | gateway | 14 |
| ShadowTableInterceptor 压测影子表 | common | 05 |
| 分布式锁防击穿（CacheHelper Singleflight 模式） | common | 06 |
| 混沌工程演练脚本（chaos-drill.sh 7 个场景） | deploy | 07 |
| GracefulShutdownHook 接口 + Counter Buffer 刷盘 | common/counter | 08 |
| BusinessMetrics 自定义业务指标（Micrometer） | common | 31 |
| SkyWalking 手动 Span 埋点（BizSpanHelper） | common | 31 |
| Logstash 日志管道（Grok → ES myxhs-logs-YYYY.MM.dd） | config | 31 |
| Sentinel 舱壁隔离（threadPoolMaxSize=10/maxQueueSize=20） | common | 35 |
| Gateway 三维度限流（IP/用户/路径） | gateway | 35 |
| 全量冒烟测试（smoke-test.sh 80+ 端点） | scripts | 32 |
| 运维脚本套件（启动/停止/备份/防火墙） | scripts | 32 |
| 完整 MQ Topic/Consumer 清单（17 Topic + 22 Consumer） | 横切 | 02/29 |
| 完整定时任务清单（7 @Scheduled + 15 XXL-Job） | 横切 | 32 |
| 已实现的安全机制清单（12 项） | 横切 | 34 |
| 多版本 API 服务端路由（@ApiVersion + HandlerMapping） | common | 44/47 |
| LeastConnectionsLoadBalancer 最少连接负载均衡 | common | 46/49 |
| ResponseAutoWrapper R<T> 自动包装 | common | 38/41 |
| BCrypt 密码加密 + 防暴力破解（5 次锁定 15 分钟） | user | 34 |
| DFA 敏感词过滤（Trie 树 + Redis Pub/Sub 多实例广播） | content | 34 |
| Spring Security 排除（Gateway 自定义 Filter 链替代） | 横切 | 34 |
| Sentinel 流控和降级规则（网关级 + Nacos 持久化） | gateway | 35 |

### 文档已规划但代码未实现（全部纳入 36-known-issues）

18 项：@Desensitize / XssFilter / @AuditLog / RBAC / CSRF / 备份恢复脚本 / RedisCacheRebuildService / CacheReconciliationJob / Promtail+Loki / LogstashEncoder / HPA/PDB/ResourceQuota / 蓝绿部署 / Testcontainers / Spring Cloud Contract / IP 黑名单 / 读写分离 / AlertManager 通知渠道 / node_exporter+中间件 exporter / 多环境 Profile / 数据库密钥加密 / API 文档 / 视频上传 / CDN / MinIO 实现 / ES 索引别名和 ILM

### 本轮审查（Explore-6/7）新发现的 P0/P1 Bug（全部纳入 36-known-issues）

| 严重程度 | 问题 | 影响模块 | 影响 |
|---------|------|---------|------|
| **~~P0~~** | ~~@EnableScheduling 缺失~~ | ~~inventory, search~~ | ~~预扣超时回收（每5分钟）、热搜计算（每5分钟）、索引重建（每天凌晨4点）全部不执行~~ → ✅ **已完成** |
| **~~P2~~** | ~~InventoryService/SpuService 线程池无关闭~~ | ~~inventory, product~~ | ~~JVM 退出时队列中未完成任务可能丢失~~ → ✅ **已完成** |

---

## 架构部署评审结论（MySQL / RocketMQ / Redis / 微服务拆分）

### 一、MySQL 部署评审 — 评分 85/100

| 维度 | 当前状态 | 评价 |
|------|---------|------|
| 实例分组 | 4 实例按业务域划分（user/content/order/inventory） | 合理 |
| 订单分片 | ShardingSphere 4 库 × 4 表，订单号映射表路由 | 设计合理，当前为开发模式单实例 |
| 主从复制 | **已实现** — 4 实例各 1 主 1 从（13306→13310, 13307→13311, 13308→13312, 13309→13313） | **已完成（Stage 3）** |
| 读写分离 | **已实现** — common 模块 DataSourceConfig + @ReadOnly 注解路由 | **已完成（Stage 3）** |
| JDBC 故障转移 | **已实现** — 11 个非 ShardingSphere 服务添加 autoReconnect + failOverReadOnly（Stage 4） | **已完成（Stage 4）** |
| 连接池 | 所有服务统一 20，未按服务差异化 | 部分完成（Tomcat 线程池已差异化，连接池待优化） |
| Docker 资源限制 | **无** — 无 CPU/内存限制 | **风险** |
| IP 配置 | Zone 使用 ${MYXHS_ZONE} 环境变量 | 部分完成（数据库 IP 仍硬编码） |
| Canal | 3 个 Instance，**单点** | **风险** |

**改进历程**：
- **Stage 3**：MySQL 主从复制 + 读写分离，4 个实例各增加 1 个 Slave，@ReadOnly 注解自动路由读操作到从库
- **Stage 4**：MySQL JDBC Multi-Host 故障转移，11 个非 ShardingSphere 服务添加 autoReconnect + failOverReadOnly 参数

**改进建议**：
- **~~P0~~**: ~~增加主从复制（至少 1 主 1 从）~~ → ✅ **已完成（Stage 3）**
- **P1**: Docker 容器添加 CPU/内存限制
- **P1**: 连接池按服务差异化（home 30、order 25、其他 15-20）— 部分完成
- **P1**: IP 改为环境变量或 Docker DNS — 部分完成（Zone 已环境变量化，数据库 IP 仍硬编码）
- **P2**: Canal 增加高可用部署

### 二、RocketMQ 部署评审 — 评分 75/100（良好）

| 维度 | 当前状态 | 风险 |
|------|---------|------|
| NameServer | **双节点**（9876 + 9877） | 无 |
| Broker | **Master-Slave**（ASYNC_MASTER） | 异步复制存在少量消息丢失风险 |
| 主从复制 | 1 Slave 节点 | 异步复制，故障切换需手动或依赖 Controller |
| 刷盘策略 | ASYNC_FLUSH（异步） | 宕机可能丢失最后一批消息 |
| 自动建 Topic | **false**（已关闭） | 无 |
| 死信队列监控 | **已实现**（myxhs.mq.dlq.total Counter + P1 告警规则） | 无 |

**改进建议**：
- **P1**: 考虑 SYNC_MASTER 模式降低消息丢失风险
- **P3**: 金融级场景考虑 SYNC_FLUSH

### 三、Redis 部署评审 — 评分 85/100

| 维度 | 当前状态 | 评价 |
|------|---------|------|
| 实例数 | 3 个（16379/16380/16381） | **已实现** |
| 高可用 | **Sentinel 模式**（3 Sentinel: 26379/26380/26381） | **已完成（Stage 4）** |
| 淘汰策略 | 分用途配置（noeviction/allkeys-lru） | 部分完成（Sentinel 已启用，淘汰策略待确认） |
| 内存限制 | 各实例独立 | 已差异化 |
| 持久化 | AOF | 一致 |

**改进历程**：
- **Stage 4**：Redis Sentinel 高可用全面实施，所有 15 个服务启用 Sentinel 模式，3 个 Sentinel 节点 + 3 个 Redis 实例

**改进建议**：
- **~~P0~~**: ~~docker-compose 增加 16380/16381 两个实例，配置对应的淘汰策略~~ → ✅ **已完成**
- **~~P1~~**: ~~增加 Redis 哨兵模式（3 个 Sentinel 节点）~~ → ✅ **已完成（Stage 4）**
- **P2**: 淘汰策略确认（Business Redis noeviction 是否已正确生效）

### 四、微服务拆分评审 — 评分 72/100

**已实现改造**：Dubbo 全部移除，统一 Feign 调用；API 子模块已删除（user-api、content-api、product-api、order-api、inventory-api）；服务数从 16 减到 15。

| 服务 | 判断 | 建议 |
|------|------|------|
| gateway | 合理 | 保持 |
| user | 合理 | 保持 |
| content | 合理 | 保持 |
| analytics | 基本合理 | 可考虑将 Like/Favorite 合并到 content |
| home | 合理 | BFF 层，但依赖 10 个服务，需本地缓存 |
| product | 合理 | 保持 |
| cart | 合理 | 保持 |
| order | 基本合理 | 移除支付协调逻辑，不再直连 payment 数据库 |
| payment | **过度拆分** | **建议合并到 order**（5 个接口 + 双向 Feign 调用） |
| coupon | 合理 | 保持 |
| inventory | 合理 | 独立 MySQL 实例是正确的 |
| counter | **严重过度拆分** | **建议合并到 analytics 或 content**（5 接口、300 行代码） |
| search | **不够拆分** | **建议拆分为 search + recommend**（两个不同技术领域） |
| notification | 合理 | 保持 |
| im | 合理 | WebSocket 技术栈不同，独立合理 |
| common | 合理 | 公共模块 |

**核心发现**：

1. **counter 严重过度拆分**：5 个接口、300 行核心代码、与 content 共享 MySQL，独立成服务徒增运维成本
2. **payment 可合并到 order**：5 个接口、与 order 存在双向 Feign 调用、Mock 模式逻辑极简
3. **search + recommend 应拆分**：搜索（ES 查询优化）和推荐（召回/排序算法）是两个完全不同的技术领域
4. **order 直连 payment 数据库**：违反微服务数据隔离原则
5. **home BFF 依赖 10 个服务**：任何下游服务不可用都可能拖慢 home，虽有 FallbackFactory 降级但仍需本地缓存
6. **多个服务共享同一物理 MySQL 实例**：13306 承载 4 个业务服务 + 2 个基础设施，13307 承载 6 个业务服务，一个慢查询可能影响同实例其他服务

**推荐最终服务数：14 个**（合并 counter + payment，拆分 search + recommend）

### 五、MQ Topic 设计评审 — 评分 78/100

**16 个 Topic**，命名规范统一（全大写 + 下划线），按业务域划分清晰。

| 优点 | 改进建议 |
|------|---------|
| 命名规范统一 | 增加命名空间前缀如 `MYXHS_` |
| Topic 按业务域划分清晰 | 建立 Topic 管理文档/字典 |
| 事务消息保障下单原子性 | ORDER_COMPENSATION_TOPIC 和 ORDER_CLOSE_TOPIC 可考虑合并 |
| 本地消息表兜底 Feed 推送 | DLQ 需要增加监控和告警 |
| Tag 二级路由使用合理 | 注意 Tag 订阅关系一致性 |

### 六、综合架构评分

| 维度 | 评分 | 评级 |
|------|------|------|
| 业务架构设计 | 88/100 | **优秀** — 设计思路清晰，考虑周全 |
| MySQL 部署 | 85/100 | **良好** — 主从复制 + 读写分离 + JDBC 故障转移已实现 |
| RocketMQ 部署 | 75/100 | **良好** — 双 NameServer + Master-Slave，DLQ 监控待补充 |
| Redis 部署 | 85/100 | **良好** — Sentinel 高可用已实现 |
| 微服务拆分 | 72/100 | **良好** — 整体合理，有 2-3 处可优化 |
| MQ Topic 设计 | 78/100 | **良好** — 命名规范，改进空间小 |
| 代码工程质量 | 85/100 | **优秀** — 模式成熟，防御性编程到位 |
| **综合评分** | **85/100** | **架构设计优秀，基础设施部署完善** |

**一句话总结**：MyXHS 的业务架构设计达到了 P7+/P8 水平（三层 AOP 防护、全链路流量染色、三级库存保障、推拉混合 Feed），基础设施部署经过 Stage 3/4 改造后已基本完备（MySQL 主从复制 + 读写分离 + 资源限制、Redis Sentinel 高可用 + 淘汰策略、Zone 多活架构、RocketMQ 双 NameServer + Master-Slave + 关闭自动建 Topic），当前主要剩余微服务拆分优化和 DLQ 监控等非阻塞改进项。

### 七、优先级修复总清单

| 优先级 | 类别 | 问题 | 修复内容 | 状态 |
|--------|------|------|---------|------|
| **~~P0~~** | ~~Redis~~ | ~~3 实例设计未实施~~ | ~~docker-compose 增加 16380/16381 两个实例~~ | ✅ **已完成（Stage 4 Sentinel 模式）** |
| **~~P0~~** | ~~RocketMQ~~ | ~~单点 NameServer~~ | ~~增加第 2 个 NameServer~~ | ✅ **已完成（双 NameServer: 9876+9877）** |
| **~~P0~~** | ~~Bug~~ | ~~@EnableScheduling 缺失~~ | ~~inventory、search 启动类添加注解~~ | ✅ **已完成** |
| **~~P0~~** | ~~MySQL~~ | ~~4 实例全部单点~~ | ~~增加主从复制（1 主 1 从）~~ | ✅ **已完成（Stage 3）** |
| **~~P1~~** | ~~RocketMQ~~ | ~~单点 NameServer~~ | ~~增加第 2 个 NameServer~~ | ✅ **已完成（同上）** |
| **~~P1~~** | ~~Redis~~ | ~~无高可用~~ | ~~增加 Redis 哨兵模式~~ | ✅ **已完成（Stage 4）** |
| **~~P1~~** | ~~MySQL~~ | ~~无资源限制~~ | ~~Docker 容器添加 CPU/内存限制~~ | ✅ **已完成（8 实例均有 limits）** |
| **P1** | MySQL | 连接池统一 20 | 按服务差异化调整 | 🟡 部分完成（20/10 对大部分服务合理，暂不调整） |
| **~~P2~~** | ~~Redis~~ | ~~淘汰策略错误~~ | ~~各实例配置正确的淘汰策略~~ | ✅ **已完成（Business noeviction / Cache allkeys-lru / Default allkeys-lru）** |
| **~~P2~~** | ~~RocketMQ~~ | ~~开发模式配置~~ | ~~autoCreateTopicEnable 改为 false~~ | ✅ **已完成** |
| **P2** | 微服务 | counter 过度拆分 | 合并到 analytics 或 content | 🔴 待修复 |
| **P2** | 微服务 | payment 过度拆分 | 合并到 order | 🔴 待修复 |
| **P2** | 微服务 | order 直连 payment 数据库 | 改为 Feign 调用 | 🔴 待修复 |
| **P2** | 微服务 | search+recommend 耦合 | 拆分为独立服务 | 🔴 待修复 |
| **~~P3~~** | ~~MQ~~ | ~~DLQ 无监控~~ | ~~增加 DLQ 监控告警~~ | ✅ **已完成（myxhs.mq.dlq.total Counter + Prometheus 告警）** |
| **P3** | MQ | 无命名空间前缀 | Topic 增加 MYXHS_ 前缀 | 🔴 待修复 |

**新增完成项（Stage 4）**：

| 改造项 | 说明 | 状态 |
|--------|------|------|
| MySQL Multi-Host 故障转移 | 11 个非 ShardingSphere 服务添加 autoReconnect + failOverReadOnly | ✅ 已完成 |
| Redis Sentinel 高可用 | 所有 15 个服务启用 Sentinel 模式 | ✅ 已完成 |
| Zone 多活架构 | 支持多 Zone 部署，Dynamic DataSource Zone 路由 | ✅ 已完成 |
| Redis 命令拦截 + 事件日志 | Redis 命令审计与事件日志记录 | ✅ 已完成 |
| Dynamic DataSource Zone 路由 | 根据 Zone 自动路由到对应数据源 | ✅ 已完成 |

---

## 八、Stage 4：多活架构改造（5 项）

> 来源：mercyblitz Java 分布式架构训练营 Stage 4
> 核心理念：**从单 Zone 部署升级为多 Zone 多活架构，提升系统容灾能力和可用性**

### 改造概览

| 编号 | 改造项 | 说明 | 状态 |
|------|--------|------|------|
| S4-1 | Redis Sentinel 高可用 | 3 Sentinel（26379/26380/26381）+ 3 Redis 实例（16379/16380/16381），所有 15 个服务启用 Sentinel 模式 | ✅ 已完成 |
| S4-2 | MySQL JDBC Multi-Host 故障转移 | 11 个非 ShardingSphere 服务添加 autoReconnect + failOverReadOnly 参数 | ✅ 已完成 |
| S4-3 | Zone 多活架构 | 支持多 Zone 部署，通过 ${MYXHS_ZONE} 环境变量区分 | ✅ 已完成 |
| S4-4 | Redis 命令拦截 + 事件日志 | Redis 命令审计与事件日志记录，便于问题排查 | ✅ 已完成 |
| S4-5 | Dynamic DataSource Zone 路由 | 根据 Zone 自动路由到对应数据源 | ✅ 已完成 |

### 关键设计决策

1. **Redis Sentinel 选型**：选择 Sentinel 而非 Cluster 模式，因为当前架构使用了 Lua 脚本（`{key}` hash tag 保证同 slot）和 Pub/Sub，Sentinel 模式兼容性更好，且 3 节点 Sentinel 已满足高可用需求
2. **MySQL 故障转移策略**：非 ShardingSphere 服务通过 JDBC URL 参数（`autoReconnect=true&failOverReadOnly=false`）实现故障转移，ShardingSphere 订单服务依赖其自带的高可用机制
3. **Zone 路由**：通过 Dynamic DataSource 实现 Zone 级别的数据源路由，不同 Zone 的服务访问对应 Zone 的数据库，实现就近访问和故障隔离

### 待改进

- RocketMQ NameServer 仍为单点（Broker 已配置 Master-Slave）
- Canal 仍为单点部署
- MySQL 数据库 IP 仍硬编码（Zone 已环境变量化）

---

## 十、框架深度定制系列（13 篇）— 训练营思想落地

> 来源：mercyblitz Java 分布式架构训练营 Stage 1（12 周 24 节）
> 核心理念：**不要满足于"能用"，要在框架层做统一增强。这类优化深度绑定框架源码，不懂 Spring/Boot/Cloud 的底层机制做不出来。**

每篇文档需回答：
- **框架源码原理**：Spring/Boot/Cloud 的这个扩展点是怎么设计的？关键类和方法是什么？
- **为什么这样优化**：当前方案的局限是什么？
- **具体实现方案**：改哪些类、加哪些注解、写哪些配置？
- **代价与边界**：什么场景下生效，什么场景下有副作用？

### P1 级（6 篇）— 直接提升架构质量

| 编号 | 标题 | 主题 |
|------|------|------|
| 41 | `41-response-body-advice.md` | **POJO 隐形包装**：Spring `ResponseBodyAdvice` 源码剖析（`AbstractMessageConverterMethodProcessor.writeWithMessageConverters` 调用链）→ Controller 返回 `UserDTO`，框架自动包装为 `Result<UserDTO>`。关键点：`supports()` 方法的返回值判断、如何排除 `Result` 本身防止重复包装、与 `@ResponseStatus` 的兼容 |
| 42 | `42-feign-unified-enhancement.md` | **Feign 统一增强**：`ErrorDecoder` 源码（`SynchronousMethodHandler.executeAndDecode`）→ 下游 `Result.fail()` 自动还原为 `BizException`；`Decoder` 扩展（`SpringDecoder` 委托链）→ 自动解包 `Result<T>` 中的 data；`RequestInterceptor` 统一化 → 染色标记、认证 Token 在 common 层统一注入而非各模块重复 |
| 43 | `43-sentinel-bulkhead.md` | **Sentinel 舱壁隔离 + 热点限流**：Sentinel 的 `SphU.entry()` 调用链 → `ProcessorSlotChain` 责任链机制 → `DegradeSlot`（熔断）、`FlowSlot`（限流）的实现原理 → 为什么默认没有舱壁？如何通过 `threadPoolMaxSize` + `maxQueueSize` 为 Feign 调用配置线程池隔离？热点参数限流的 `ParamFlowItem` 实现 |
| 44 | `44-gateway-differentiated-strategy.md` | **Gateway 差异化策略**：`RouteDefinitionRouteLocator` 路由加载机制 → `Route.locate()` 中 `metadata` 的传递 → 如何为每个 Route 设置独立的超时时间（`spring.cloud.gateway.httpclient.connect-timeout` vs `response-timeout`）→ 如何按 Path 差异化限流（`KeyResolver` + `GatewayFlowRule` 的 `resource` 匹配） |
| 45 | `45-business-metrics.md` | **Micrometer 业务指标**：`MeterRegistry` 的 SPI 机制 → `MeterBinder.bindTo()` 注册流程 → 自定义 Counter（`orders.created.total`）、Timer（`inventory.prededuct.latency`）、Gauge（`inventory.hot.sku.count`）→ Tags 的设计原则（low cardinality）→ 如何在 Grafana 中用 PromQL 构建业务 Dashboard（下单成功率、库存预扣耗时 P99） |
| 46 | `46-skywalking-manual-span.md` | **SkyWalking 手动 Span 埋点**：SkyWalking 的 `TracingContext` 线程绑定机制（`ContextManager.getOrCreate()`）→ `AbstractSpan` 的生命周期 → 下单全链路手动 Span（`createOrder` → `inventory.preDeduct` → `coupon.use` → `payment.create`）→ 每个 Span 带业务 Tag（orderId、skuId、payAmount）→ 如何在 SkyWalking UI 中查看自定义 Span |

### P2 级（7 篇）— 锦上添花

| 编号 | 标题 | 主题 |
|------|------|------|
| 47 | `47-api-versioning.md` | **多版本 API 服务端实现**：Spring `RequestMappingHandlerMapping` 的 `getMappingForMethod()` 源码 → 如何扩展 `RequestCondition` 实现 `@ApiVersion` 注解 → 同一 URL 根据 `Accept-Version` Header 路由到不同处理方法 → 版本平滑升级策略（v1 标记 `@Deprecated`，v2 并行运行，v1 逐步下线） |
| 48 | `48-refreshscope-replacement.md` | **替换 @RefreshScope**：`@RefreshScope` 的源码实现（`Scope.refresh()` → `BeanLifecycleWrapper.destroy()` → 重建 Bean）→ 为什么会导致短暂请求失败？→ 替代方案：`@ConfigurationProperties` + `EnvironmentChangeEvent` 监听器 → 只更新字段值不重建 Bean → 适用场景：降级开关、阈值配置 |
| 49 | `49-custom-loadbalancer.md` | **自定义 LoadBalancer**：`ReactorServiceInstanceLoadBalancer` 接口 → `RoundRobinLoadBalancer` 的默认实现分析 → 自定义 `LeastConnectionsLoadBalancer`：从 Nacos 元数据读取实例权重和启动时间 → 预热机制（启动 < 60s 权重爬升）→ 最少活跃请求数策略 |
| 50 | `50-lettuce-mybatis-metrics.md` | **Lettuce + MyBatis Micrometer 集成**：Lettuce 的 `MicrometerCommandLatencyRecorder` 接入方式 → MyBatis `Interceptor` 接口的 `intercept()` 方法签名 → 如何区分 SQL 类型（SELECT/INSERT/UPDATE/DELETE）并分别记录 Timer → `SqlGuardInterceptor` 与 Micrometer 的融合（慢 SQL 既是熔断条件，也是监控指标） |
| 51 | `51-bom-api-split.md` | **BOM 独立模块 + API 拆分**：Maven 的 `<dependencyManagement>` vs `<dependencies>` 的区别 → 为什么 BOM 模块只声明版本不引入依赖？→ 各微服务拆出 `api` 子模块（Feign 接口 + DTO），实现模块只依赖 api → home BFF 不再拉入 MyBatis-Plus、RocketMQ 等不需要的依赖 |
| 52 | `52-tomcat-tuning.md` | **Tomcat 参数差异化**：Tomcat `NioEndpoint` 的 `maxConnections` vs `acceptorThreadCount` vs `maxThreads` 的区别 → `accept-count` 队列的作用 → 为什么库存服务（锁竞争大）应该用更少的线程？→ gateway（IO 密集）用更多线程 → 各服务差异化参数表 |
| 53 | `53-resultcode-message-template.md` | **ResultCode 消息模板化**：`MessageSource` 和 `MessageFormat` 的源码分析 → 为什么 `MessageFormat` 在高并发下有性能瓶颈？→ 自定义 `ResultCode.getMessage(Object... args)` 方法 → 业务代码 `throw new BizException(STOCK_NOT_ENOUGH, skuId)` 自动生成 `"库存不足: skuId=12345"` → 与 Bean Validation 消息的整合 |

### P3 级（仅文档，不实现代码）

| 编号 | 标题 | 主题 |
|------|------|------|
| 54 | `54-aop-static-proxy.md` | **AOP 静态代理（AspectJ CTW）**：JDK 动态代理 vs CGLIB vs AspectJ 编译期织入的原理对比 → `Method.invoke()` 反射开销的 Benchmark 数据 → `aspectj-maven-plugin` 配置 → 什么 QPS 阈值才需要切换？→ 当前 MyXHS 为什么不需要 |
| 55 | `55-spring-initializr-custom.md` | **Spring 脚手架定制**：`ProjectGenerationContext` 的子 ApplicationContext 机制 → `BuildCustomizer` 扩展点 → 如何生成符合 MyXHS 规范的多模块工程骨架 |

---

## 编写原则补充（针对本系列）

1. **必须贴源码路径**：引用 Spring 框架源码时，必须给出具体的类全限定名和方法签名，例如：
   > `org.springframework.web.servlet.mvc.method.annotation.AbstractMessageConverterMethodProcessor.writeWithMessageConverters(...)`
2. **必须画调用链**：从请求进入 → 框架处理 → 扩展点回调 → 返回，用 ASCII 图画出完整调用链
3. **必须给出对比**：优化前 vs 优化后的代码、性能、可维护性对比
4. **必须标注适用版本**：本文档基于 Spring Boot 3.2.x / Spring Cloud 2023.x / Spring Cloud Alibaba 2023.x
