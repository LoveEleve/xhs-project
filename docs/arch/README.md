# MyXHS 架构分析文档

## 项目定级

**对标 P7+ ~ P8 技术专家水平**。本文档面向有经验的分布式系统工程师，每篇文档回答三个问题：
- **为什么这样设计？**（方案对比 + 选择理由）
- **边界条件怎么处理？**（异常路径 + 容错策略）
- **代价是什么？**（已知局限 + 改进方向）

## 文档体系

共 **36 篇**，分 6 大类，扁平结构不设子文件夹。

---

## 一、系统级（2 篇）

| 编号 | 标题 | 主题 |
|------|------|------|
| 01 | `01-system-overview.md` | **系统全景**：为什么是 16 个微服务而不是 6 个？拆分边界在哪？各模块职责矩阵、技术栈全景、部署拓扑 |
| 02 | `02-module-interaction.md` | **模块交互拓扑**：16 个模块的调用关系图、RocketMQ 事件流全景、数据流方向、瓶颈链路分析、最长调用链追踪 |

---

## 二、基础设施（10 篇）← 增加 1 篇

| 编号 | 标题 | 主题 |
|------|------|------|
| 03 | `03-common-infrastructure.md` | **common 模块全景**：13 个配置类 + 20+ AutoConfiguration 组件的注册顺序与依赖关系、为什么放 common 而不是独立模块、组件分类与职责矩阵 |
| 04 | `04-aop-three-layer-defense.md` | **三层 AOP 防护**：@RateLimit → @DistributedLock → @Idempotent 的顺序设计依据、Redis 不可用时的降级放行策略、Lua 脚本实现细节、SpEL 动态 Key 解析 |
| 05 | `05-trace-context-propagation.md` | **全链路流量染色**：6 个染色标记（traceId/userId/grayTag/apiVersion/abGroup/pressureTest）的设计依据、HTTP Header → Feign → RocketMQ 三通道透传机制、ThreadLocal 跨线程传递（TransmittableThreadLocal + TaskDecorator）、影子表路由 |
| 06 | `06-cache-strategy.md` | **缓存体系**：Cache Aside 模式封装、三层一致性保障（删缓存 + 延迟双删 500ms + MQ 兜底 CACHE_EVICT_TOPIC）、Caffeine + Redis 多级缓存、Redisson 布隆过滤器、防穿透/雪崩/击穿、**Canal 缓存失效 + 回填机制**、**缓存预热（CacheWarmupRunner）** |
| 07 | `07-chaos-engineering.md` | **混沌工程**：自研故障注入框架设计（DELAY/EXCEPTION/RETURN_NULL）、通配符目标方法匹配、概率触发机制、与 ChaosBlade/Litmus 的对比、自研边界 |
| 08 | `08-graceful-shutdown.md` | **优雅停机**：Nacos 主动注销 → 等待服务列表传播 → 执行 ShutdownHook → 关闭线程池的完整流程、与 K8s terminationGracePeriodSeconds 的配合、超时处理 |
| 09 | `09-sql-guard-interceptor.md` | **SQL 熔断**：MyBatis Interceptor 慢 SQL 检测（>200ms）、连续 5 次熔断 + 30 秒冷却自动恢复、阈值选择依据、熔断后行为 |
| 10 | `10-data-generator.md` | **数据生成器**：可插拔 DataGenerator 接口设计、CommandLineRunner 启动执行、scale 参数控制数据规模、User/Note/Product 三个生成器实现、关联数据一致性保证 |
| 11 | `11-id-generator.md` | **ID 生成器**：雪花 ID（MyBatis-Plus IdWorker）+ 号段模式（SegmentIdGenerator）+ Redis 自增流水号三合一设计、各自适用场景、降级策略 |
| 12 | `12-degrade-switch-and-config.md` | **[新增]** **降级开关与动态配置**：DegradeSwitchManager 的 5 个降级开关（CacheDegrade/MqDegrade/FeignDegrade/DbDegrade/SearchDegrade）、Nacos @RefreshScope 动态刷新机制、Tomcat 线程池定制参数（MyXhsTomcatCustomizer）、Feign 全局关闭重试 + ErrorDecoder（FeignSafeConfig）、BOM 依赖版本管理策略 |

---

## 三、网关（2 篇）

| 编号 | 标题 | 主题 |
|------|------|------|
| 13 | `13-gateway-security.md` | **网关安全纵深防御**：JWT 双 Token（Access 30min + Refresh 7d）+ Token 黑名单（Fail-Closed）+ HMAC-SHA256 签名防篡改防重放（timestamp 5min + nonce Redis SETNX）、CORS 域名白名单、压测流量来源校验（仅允许内网段）、**HMAC 密钥与 JWT 密钥分离的设计决策**、**Gateway 与 User 服务黑名单 Key 同步问题（WebFlux vs Servlet）** |
| 14 | `14-gateway-filter-chain.md` | **7 层过滤器链**：HMAC 签名 → 流量染色 → 限流 → 灰度路由 → JWT 认证 → API 版本 → 请求日志的顺序设计、职责分离、异常传播机制、每个 Filter 的异常降级策略 |

---

## 四、业务模块（13 篇）— 每篇补充了大量遗漏的设计细节

| 编号 | 标题 | 主题 |
|------|------|------|
| 15 | `15-inventory.md` | **库存服务（★★★★★）**：Redis 分桶预扣设计（userId % N 路由，默认 2 桶 + 热点 8 桶）、Lua 原子扣减脚本、三级扣减保证（L1 Redis → L2 MQ 异步 MySQL 乐观锁重试 3 次 → L3 定时对账修复）、预扣超时回收（5 分钟 Job + 惰性删除主动扫描）、**热点 SKU 动态分桶扩容（HotSkuDetector + resizeBuckets）**、**Canal 缓存失效 + 回填（reloadStockToRedis）**、**分桶完整性对账（bucketSum vs total）**、分桶数 N 的确定依据、Redis Cluster 下 Lua 的 slot 限制、桶用完的扩容方案 |
| 16 | `16-payment.md` | **支付服务（★★★★★）**：支付状态机 + 退款状态机、策略模式 PayChannelStrategy（3 种实现：Mock/Alipay/Wechat + PayCallbackSimulator）、**部分退款设计（多次退款 + getRefundedAmount + maxRefundable）**、**支付/退款双超时设计（30min/15 天 + Lua 原子超时判断）**、**对账 + 自动补偿机制（Feign 调 Order 核对 + 不一致自动 notifyPaySuccess）**、**支付/退款双幂等设计（Redis SETNX + 乐观锁双重保障）**、独立数据库 my_xhs_payment、流水号生成 |
| 17 | `17-order.md` | **订单服务（★★★★★）**：RocketMQ 事务消息（半消息 + 本地事务 + 回查 LocalMessage）、订单状态机（6 状态 + canTransitTo 流转校验）、乐观锁防并发、延时关单（30min）+ 定时任务兜底、补偿消息机制（Feign 失败 → ORDER_COMPENSATION_TOPIC → 重试）、分库分表（ShardingSphere 4 库 × 4 表）、订单号映射表路由 |
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
| 28 | `28-distributed-transaction.md` | **分布式事务方案全景**：RocketMQ 事务消息 vs TCC vs SAGA vs 本地消息表的场景选择、当前方案覆盖范围（order 下单 + inventory 预扣 + coupon 核销）、未覆盖场景（支付回调异常？）、LocalMessage 补发机制、FeedMessageRetryJob 本地消息补发 |
| 29 | `29-mq-design.md` | **消息队列设计**：Topic 全景图（ORDER_TRANSACTION_TOPIC / ORDER_CLOSE_TOPIC / ORDER_COMPENSATION_TOPIC / CACHE_EVICT_TOPIC / PAY_RESULT_TOPIC / REFUND_RESULT_TOPIC / SOCIAL_TOPIC 等）、命名规范、为什么不使用 TAG 区分消息类型、延时消息等级选择（delayLevel=16 = 30min）、消费幂等策略、消息积压监控、MqTraceHelper 链路透传 |
| 30 | `30-data-sharding.md` | **分库分表**：ShardingSphere 5.5.1 分片策略（user_id % 4 → 库，user_id / 4 % 4 → 表）、分片键选择依据、订单号映射表路由方案、非分片键查询的性能瓶颈、扩容方案 |
| 31 | `31-observability.md` | **可观测性体系**：Prometheus 4 组 21 条告警规则设计（P0-P2 分级）、3 个 Grafana Dashboard（JVM/API/业务指标）、SkyWalking 全链路追踪、**JSON 结构化日志（LogstashEncoder 已声明依赖但未启用，当前用 PatternLayout）**、健康检查端点（自定义 ApplicationReadinessIndicator：堆内存>90% + 死锁检测）、告警阈值选择依据、**缺失：AlertManager 通知渠道、node_exporter、中间件 exporter、慢查询告警、Feign 调用失败率告警** |
| 32 | `32-cicd-deployment.md` | **CI/CD 与部署**：GitLab CI 5 阶段流水线（compile → test → check → build → deploy）、多阶段 Docker 构建（maven:3.9 + eclipse-temurin:17-jre-alpine + 非 root 用户 + HEALTHCHECK）、K8s 部署模板（Deployment/Service/Ingress/ConfigMap）、Docker Compose 19 容器编排、**运维脚本（smoke-test.sh / start-all.sh / stop-all.sh / setup-firewall.sh）**、**缺失：HPA/PDB/ResourceQuota/NetworkPolicy/Secret/多环境 Profile/蓝绿部署/密钥管理** |
| 33 | `33-data-storage-design.md` | **[新增]** **数据存储设计全景**：4 个 MySQL 实例 12 个数据库 27 张表的完整 ER 关系、订单分片策略（4 库 × 4 表 = 16 张分表 + 公共映射表）、Redis 数据结构全景（50+ Key 前缀覆盖 8 个数据类型 String/ZSet/Set/Hash/PubSub/BloomFilter/Lock + 未使用的 Stream/HyperLogLog/Bitmap/Geo 分析）、ES 3 个索引设计（note_index 3shard / product_index 3shard / suggest_index 1shard + Search After 深分页 + 权重调优）、Canal 3 个 Instance 同步链路（t_note→note_index / t_spu+t_sku→product_index / t_inventory→Redis 缓存失效 + MQ Consumer 双通道保障 + IndexRebuildJob 凌晨全量补偿）、**文件存储（本地磁盘 + FileStorageService 接口抽象 + 生产 MinIO/OSS 扩展预留）**、**两种 t_local_message 表的不同用途（订单事务消息回查 vs Feed 推送可靠性）**、**索引设计分析（9 个关键索引 + 3 个缺失索引建议）**、**缺失：视频上传/无 CDN/无 ES 索引别名和 ILM/无 MinIO 实现** |

---

## 六、收尾（3 篇）← 增加 2 篇

| 编号 | 标题 | 主题 |
|------|------|------|
| 34 | `34-security-deep-dive.md` | **[新增]** **安全体系深度分析**：HMAC 签名密钥管理（当前明文存储，需 Jasypt/Vault/K8s Secrets）、Token 黑名单跨服务同步问题（Gateway WebFlux vs User Servlet 无法共用 common 模块）、**密码加密（BCrypt 已实现）vs 传输加密（MySQL useSSL=false）vs 存储加密（密码明文）**、压测流量安全控制（仅内网段可设 X-Pressure-Test）、**文档已规划但代码未实现**：@Desensitize 数据脱敏、XssFilter XSS 防护、@AuditLog 操作审计、RBAC 权限管理、CSRF 防护、IP 黑名单/白名单 |
| 35 | `35-engineering-maturity.md` | **[新增]** **工程化成熟度评估**：**Maven 插件缺失（JaCoCo 覆盖率/SpotBugs/Checkstyle/PMD/enforcer/OWASP dependency-check 均未配置）**、**代码规范缺失（无 .editorconfig/checkstyle.xml/spotbugs-exclude.xml）**、**多环境 Profile 完全缺失（K8s 有 -Dspring.profiles.active=k8s 但无对应 application-k8s.yml）**、依赖版本管理策略（BOM 导入顺序：Jackson > Spring Boot > Spring Cloud > Spring Cloud Alibaba）、**测试体系现状（仅 3 个测试类 + Testcontainers 版本已管理但未使用 + 无 Spring Cloud Contract）**、API 文档缺失（无 Swagger/OpenAPI/Knife4j）、Git Commit ID 未注入构建信息、**InventoryService/SpuService 线程池无 @PreDestroy 关闭（P2 风险）** |
| 36 | `36-known-issues-and-roadmap.md` | **已知不足与改进路线图**：按严重程度（P0-P3）排列的所有已知问题 + **18 项文档已规划但代码未实现的功能** + **P0 Bug: @EnableScheduling 缺失导致 inventory 预扣超时回收/search 热搜计算/索引重建定时任务不执行** + **P2 Bug: InventoryService/SpuService 线程池无关闭逻辑** + 每项问题的影响范围 + 改进方案建议 + 预估工作量 + 优先级排序 |

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
| 安全体系全景（已实现 vs 规划中的对比） | 横切 | 33 |
| 工程化成熟度评估（插件/规范/测试/多环境） | 横切 | 34 |

### 文档已规划但代码未实现（全部纳入 36-known-issues）

18 项：@Desensitize / XssFilter / @AuditLog / RBAC / CSRF / 备份恢复脚本 / RedisCacheRebuildService / CacheReconciliationJob / Promtail+Loki / LogstashEncoder / HPA/PDB/ResourceQuota / 蓝绿部署 / Testcontainers / Spring Cloud Contract / IP 黑名单 / 读写分离 / AlertManager 通知渠道 / node_exporter+中间件 exporter / 多环境 Profile / 数据库密钥加密 / API 文档 / 视频上传 / CDN / MinIO 实现 / ES 索引别名和 ILM

### 本轮审查（Explore-6/7）新发现的 P0/P1 Bug（全部纳入 36-known-issues）

| 严重程度 | 问题 | 影响模块 | 影响 |
|---------|------|---------|------|
| **P0** | @EnableScheduling 缺失 | inventory, search | 预扣超时回收（每5分钟）、热搜计算（每5分钟）、索引重建（每天凌晨4点）**全部不执行** |
| **P2** | InventoryService/SpuService 线程池无关闭 | inventory, product | JVM 退出时队列中未完成任务可能丢失 |

---

## 架构部署评审结论（MySQL / RocketMQ / Redis / 微服务拆分）

### 一、MySQL 部署评审 — 评分 75/100

| 维度 | 当前状态 | 评价 |
|------|---------|------|
| 实例分组 | 4 实例按业务域划分（user/content/order/inventory） | 合理 |
| 订单分片 | ShardingSphere 4 库 × 4 表，订单号映射表路由 | 设计合理，当前为开发模式单实例 |
| 主从复制 | **无** — 4 个实例全部单点 | **高风险** |
| 连接池 | 所有服务统一 20，未按服务差异化 | 需调整 |
| Docker 资源限制 | **无** — 无 CPU/内存限制 | **风险** |
| IP 配置 | **硬编码** 127.0.0.1 / 21.91.124.110 | 需改为环境变量 |
| Canal | 3 个 Instance，**单点** | **风险** |

**核心问题**：4 个 MySQL 实例全部单点，无主从复制。任何一个实例宕机都会导致其承载的多个服务不可用。

**改进建议**：
- **P0**: 增加主从复制（至少 1 主 1 从）
- **P1**: Docker 容器添加 CPU/内存限制
- **P1**: 连接池按服务差异化（home 30、order 25、其他 15-20）
- **P1**: IP 改为环境变量或 Docker DNS
- **P2**: Canal 增加高可用部署

### 二、RocketMQ 部署评审 — 评分 45/100（高风险）

| 维度 | 当前状态 | 风险 |
|------|---------|------|
| NameServer | **单节点** | **NameServer 宕机 = 整个 MQ 系统不可用** |
| Broker | **单节点**（ASYNC_MASTER） | **Broker 宕机 = 所有消息丢失** |
| 主从复制 | **无** Slave 节点 | 无故障切换 |
| 刷盘策略 | ASYNC_FLUSH（异步） | 宕机可能丢失最后一批消息 |
| 自动建 Topic | **true**（开发模式） | 生产环境需关闭 |
| 死信队列监控 | **无** | DLQ 消息无告警 |

**核心问题**：RocketMQ 是**单节点单点部署**，Broker 宕机影响面极大：
- 下单链路断裂（事务消息半消息全部丢失）
- 库存异步扣减中断
- 支付/退款通知中断
- Canal 数据同步中断
- Feed 推送中断

**改进建议**：
- **P0**: 增加至少 1 个 Broker Slave，配置 SYNC_MASTER 模式
- **P1**: 增加第 2 个 NameServer
- **P2**: autoCreateTopicEnable 改为 false
- **P2**: 增加 DLQ 监控告警
- **P3**: 金融级场景考虑 SYNC_FLUSH

### 三、Redis 部署评审 — 评分 50/100（架构设计合理但未实施）

| 维度 | 代码设计 | 实际部署 | 差距 |
|------|---------|---------|------|
| 实例数 | 3 个（16379/16380/16381） | **1 个**（16379） | **严重不一致** |
| 淘汰策略 | 分用途配置（noeviction/allkeys-lru） | **全部 allkeys-lru** | **Business Redis noeviction 设计失效** |
| 内存限制 | 各实例独立 | 256MB 共享 | 严重不足 |
| 持久化 | AOF | AOF | 一致 |
| 高可用 | 无 | 无 | 单点 |

**核心问题**：docker-compose.yml 与 application.yml 配置严重不一致。代码层面设计了 3 实例分离（Business 16381 noeviction / Cache 16380 allkeys-lru / 主 16379），但部署层面只有 1 个实例 16379。这导致：
- Business Redis noeviction 设计完全失效，业务数据可能被 allkeys-lru 淘汰
- 256MB 内存承载 3 个实例的数据量，严重不足
- 单实例宕机 = 所有 Redis 功能瘫痪（缓存、分布式锁、幂等、限流、IM 路由等）

**改进建议**：
- **P0**: docker-compose 增加 16380/16381 两个实例，配置对应的淘汰策略
- **P1**: 增加 Redis 哨兵模式（3 个 Sentinel 节点）
- **P2**: 内存限制：Business 建议 512MB+，Cache 建议 256MB+，主 256MB

### 四、微服务拆分评审 — 评分 72/100

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
| MySQL 部署 | 75/100 | **中等** — 实例分组合理，但单点风险高 |
| RocketMQ 部署 | 45/100 | **严重** — 单点部署，核心链路风险极高 |
| Redis 部署 | 50/100 | **严重** — 设计合理但未实施，配置不一致 |
| 微服务拆分 | 72/100 | **良好** — 整体合理，有 2-3 处可优化 |
| MQ Topic 设计 | 78/100 | **良好** — 命名规范，改进空间小 |
| 代码工程质量 | 85/100 | **优秀** — 模式成熟，防御性编程到位 |
| **综合评分** | **70/100** | **架构设计思路好，基础设施部署严重不足** |

**一句话总结**：MyXHS 的业务架构设计达到了 P7+/P8 水平（三层 AOP 防护、全链路流量染色、三级库存保障、推拉混合 Feed），但基础设施部署停留在原型/Demo 阶段（MySQL/RocketMQ/Redis 全部单点），需要补齐高可用部署才能支撑生产环境。

### 七、优先级修复总清单

| 优先级 | 类别 | 问题 | 修复内容 |
|--------|------|------|---------|
| **P0** | Redis | 3 实例设计未实施 | docker-compose 增加 16380/16381 两个实例 |
| **P0** | RocketMQ | 单点 Broker | 增加 Broker Slave，配置 SYNC_MASTER |
| **P0** | Bug | @EnableScheduling 缺失 | inventory、search 启动类添加注解 |
| **P0** | MySQL | 4 实例全部单点 | 增加主从复制（1 主 1 从） |
| **P1** | RocketMQ | 单点 NameServer | 增加第 2 个 NameServer |
| **P1** | Redis | 无高可用 | 增加 Redis 哨兵模式 |
| **P1** | MySQL | 无资源限制 | Docker 容器添加 CPU/内存限制 |
| **P1** | MySQL | 连接池统一 20 | 按服务差异化调整 |
| **P2** | Redis | 淘汰策略错误 | 各实例配置正确的淘汰策略和内存限制 |
| **P2** | RocketMQ | 开发模式配置 | autoCreateTopicEnable 改为 false |
| **P2** | 微服务 | counter 过度拆分 | 合并到 analytics 或 content |
| **P2** | 微服务 | payment 过度拆分 | 合并到 order |
| **P2** | 微服务 | order 直连 payment 数据库 | 改为 Feign 调用 |
| **P2** | 微服务 | search+recommend 耦合 | 拆分为独立服务 |
| **P3** | MQ | DLQ 无监控 | 增加 DLQ 监控告警 |
| **P3** | MQ | 无命名空间前缀 | Topic 增加 MYXHS_ 前缀 |

---

## 七、框架深度定制系列（13 篇）— 训练营思想落地

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
| 37 | `37-response-body-advice.md` | **POJO 隐形包装**：Spring `ResponseBodyAdvice` 源码剖析（`AbstractMessageConverterMethodProcessor.writeWithMessageConverters` 调用链）→ Controller 返回 `UserDTO`，框架自动包装为 `Result<UserDTO>`。关键点：`supports()` 方法的返回值判断、如何排除 `Result` 本身防止重复包装、与 `@ResponseStatus` 的兼容 |
| 38 | `38-feign-unified-enhancement.md` | **Feign 统一增强**：`ErrorDecoder` 源码（`SynchronousMethodHandler.executeAndDecode`）→ 下游 `Result.fail()` 自动还原为 `BizException`；`Decoder` 扩展（`SpringDecoder` 委托链）→ 自动解包 `Result<T>` 中的 data；`RequestInterceptor` 统一化 → 染色标记、认证 Token 在 common 层统一注入而非各模块重复 |
| 39 | `39-sentinel-bulkhead.md` | **Sentinel 舱壁隔离 + 热点限流**：Sentinel 的 `SphU.entry()` 调用链 → `ProcessorSlotChain` 责任链机制 → `DegradeSlot`（熔断）、`FlowSlot`（限流）的实现原理 → 为什么默认没有舱壁？如何通过 `threadPoolMaxSize` + `maxQueueSize` 为 Feign 调用配置线程池隔离？热点参数限流的 `ParamFlowItem` 实现 |
| 40 | `40-gateway-differentiated-strategy.md` | **Gateway 差异化策略**：`RouteDefinitionRouteLocator` 路由加载机制 → `Route.locate()` 中 `metadata` 的传递 → 如何为每个 Route 设置独立的超时时间（`spring.cloud.gateway.httpclient.connect-timeout` vs `response-timeout`）→ 如何按 Path 差异化限流（`KeyResolver` + `GatewayFlowRule` 的 `resource` 匹配） |
| 41 | `41-business-metrics.md` | **Micrometer 业务指标**：`MeterRegistry` 的 SPI 机制 → `MeterBinder.bindTo()` 注册流程 → 自定义 Counter（`orders.created.total`）、Timer（`inventory.prededuct.latency`）、Gauge（`inventory.hot.sku.count`）→ Tags 的设计原则（low cardinality）→ 如何在 Grafana 中用 PromQL 构建业务 Dashboard（下单成功率、库存预扣耗时 P99） |
| 42 | `42-skywalking-manual-span.md` | **SkyWalking 手动 Span 埋点**：SkyWalking 的 `TracingContext` 线程绑定机制（`ContextManager.getOrCreate()`）→ `AbstractSpan` 的生命周期 → 下单全链路手动 Span（`createOrder` → `inventory.preDeduct` → `coupon.use` → `payment.create`）→ 每个 Span 带业务 Tag（orderId、skuId、payAmount）→ 如何在 SkyWalking UI 中查看自定义 Span |

### P2 级（7 篇）— 锦上添花

| 编号 | 标题 | 主题 |
|------|------|------|
| 43 | `43-api-versioning.md` | **多版本 API 服务端实现**：Spring `RequestMappingHandlerMapping` 的 `getMappingForMethod()` 源码 → 如何扩展 `RequestCondition` 实现 `@ApiVersion` 注解 → 同一 URL 根据 `Accept-Version` Header 路由到不同处理方法 → 版本平滑升级策略（v1 标记 `@Deprecated`，v2 并行运行，v1 逐步下线） |
| 44 | `44-refreshscope-replacement.md` | **替换 @RefreshScope**：`@RefreshScope` 的源码实现（`Scope.refresh()` → `BeanLifecycleWrapper.destroy()` → 重建 Bean）→ 为什么会导致短暂请求失败？→ 替代方案：`@ConfigurationProperties` + `EnvironmentChangeEvent` 监听器 → 只更新字段值不重建 Bean → 适用场景：降级开关、阈值配置 |
| 45 | `45-custom-loadbalancer.md` | **自定义 LoadBalancer**：`ReactorServiceInstanceLoadBalancer` 接口 → `RoundRobinLoadBalancer` 的默认实现分析 → 自定义 `LeastConnectionsLoadBalancer`：从 Nacos 元数据读取实例权重和启动时间 → 预热机制（启动 < 60s 权重爬升）→ 最少活跃请求数策略 |
| 46 | `46-lettuce-mybatis-metrics.md` | **Lettuce + MyBatis Micrometer 集成**：Lettuce 的 `MicrometerCommandLatencyRecorder` 接入方式 → MyBatis `Interceptor` 接口的 `intercept()` 方法签名 → 如何区分 SQL 类型（SELECT/INSERT/UPDATE/DELETE）并分别记录 Timer → `SqlGuardInterceptor` 与 Micrometer 的融合（慢 SQL 既是熔断条件，也是监控指标） |
| 47 | `47-bom-api-split.md` | **BOM 独立模块 + API 拆分**：Maven 的 `<dependencyManagement>` vs `<dependencies>` 的区别 → 为什么 BOM 模块只声明版本不引入依赖？→ 各微服务拆出 `api` 子模块（Feign 接口 + DTO），实现模块只依赖 api → home BFF 不再拉入 MyBatis-Plus、RocketMQ 等不需要的依赖 |
| 48 | `48-tomcat-tuning.md` | **Tomcat 参数差异化**：Tomcat `NioEndpoint` 的 `maxConnections` vs `acceptorThreadCount` vs `maxThreads` 的区别 → `accept-count` 队列的作用 → 为什么库存服务（锁竞争大）应该用更少的线程？→ gateway（IO 密集）用更多线程 → 各服务差异化参数表 |
| 49 | `49-resultcode-message-template.md` | **ResultCode 消息模板化**：`MessageSource` 和 `MessageFormat` 的源码分析 → 为什么 `MessageFormat` 在高并发下有性能瓶颈？→ 自定义 `ResultCode.getMessage(Object... args)` 方法 → 业务代码 `throw new BizException(STOCK_NOT_ENOUGH, skuId)` 自动生成 `"库存不足: skuId=12345"` → 与 Bean Validation 消息的整合 |

### P3 级（仅文档，不实现代码）

| 编号 | 标题 | 主题 |
|------|------|------|
| 50 | `50-aop-static-proxy.md` | **AOP 静态代理（AspectJ CTW）**：JDK 动态代理 vs CGLIB vs AspectJ 编译期织入的原理对比 → `Method.invoke()` 反射开销的 Benchmark 数据 → `aspectj-maven-plugin` 配置 → 什么 QPS 阈值才需要切换？→ 当前 MyXHS 为什么不需要 |
| 51 | `51-spring-initializr-custom.md` | **Spring 脚手架定制**：`ProjectGenerationContext` 的子 ApplicationContext 机制 → `BuildCustomizer` 扩展点 → 如何生成符合 MyXHS 规范的多模块工程骨架 |

---

## 编写原则补充（针对本系列）

1. **必须贴源码路径**：引用 Spring 框架源码时，必须给出具体的类全限定名和方法签名，例如：
   > `org.springframework.web.servlet.mvc.method.annotation.AbstractMessageConverterMethodProcessor.writeWithMessageConverters(...)`
2. **必须画调用链**：从请求进入 → 框架处理 → 扩展点回调 → 返回，用 ASCII 图画出完整调用链
3. **必须给出对比**：优化前 vs 优化后的代码、性能、可维护性对比
4. **必须标注适用版本**：本文档基于 Spring Boot 3.2.x / Spring Cloud 2023.x / Spring Cloud Alibaba 2023.x
