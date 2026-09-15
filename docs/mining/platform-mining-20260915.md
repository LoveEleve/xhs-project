# 平台工程深挖素材（2026-09-15，代码考古）

> 来源：对 my-xhs-* 全量代码只读考古；格式：`文件:行号` + 具体值。用于简历加分细节与面试深挖。

## 1. 关键参数（可量化细节）
- 库存：默认桶数 2 / 热点桶 8（`my-xhs-inventory/.../InventoryService.java:73,76`）；预扣过期 1800s；热点检测滑窗 10s、阈值 100 次、Key TTL 30s（`hot/HotSkuDetector.java:32-38`）；扩容线程池 2/4/50 CallerRuns（`:87`）
- Lua：`prededuct.lua:23` routeBucket 由 Java 传入（Lua 对 >2^53 雪花 ID 取模失真）；返回码 -1 重复/-2 未初始化/0 不足/1 成功；`reconcile_buckets.lua` 桶求和 vs total 原子比对（消除先读后盲写）；`claim_coupon.lua` `{templateId}` 同 slot
- 锁：Redisson watchdog 15s、retryAttempts=5/1000ms、连接池 16（`common/.../RedissonConfig.java:104,131`）；击穿锁 tryLock(3,10s)、抢锁失败 sleep 100ms 重读、仍 miss 降级直查不回填（`CacheHelper.java:341,371`）
- 缓存：延迟双删 500ms（注释：主从 200ms+读 100ms+余量）、删缓存重试 3×50ms、空值占位 TTL 2min、TTL 随机偏移 timeout/6、默认 TTL 30min（`CacheHelper.java:219,420,290,287,303`）；二次删失败发 CACHE_EVICT_TOPIC（`:498`）
- Feed：大V阈值 10 万、inbox 7 天/500 条、推送批 500、粉丝>5 万写发件箱、进度 cursor TTL 1h（`FeedPushConsumer.java:53-60,145,150,134`）
- IM/SSE：150 虚拟节点 + 环指纹复用（`ImConsistentHashLoadBalancer.java:43,126`）；route TTL=心跳×3=90s；离线 1000 条/7 天；会话 seq INCR；SSE 心跳 10s 续期 30s TTL、emitter 30min、ticket 30s 一次性（`SseEmitterManager.java:57,73,255`、`SseTicketService.java:34,62`）
- 订单/MQ：延时关单 delay-level=16（30min，测试 5=1min）、幂等键 24h、下单锁 10s（`OrderService.java:90,132,141`）；本地消息 5 次指数退避 30/60/120/240/480s（`LocalMessageRetryJob.java:48-51`）；支付通知补发 5min 窗口/10 次/批 100
- 线程池/连接：Tomcat 300/30、maxConnections 8192（gateway yml:7-13）；聚合器 20/50/200、batchFeign 30/80/500（隔离防饥饿）；ES 连接 5s/socket 30s、maxConnTotal 100
- 其他 TTL：购物车 50 种/单品 99/30 天；JWT access 30min/refresh 7d；计数去重 2h/计数 30d；热搜衰减 λ=0.1/窗口 60min/IP 10 次/分

## 2. 可讲成故事的机制（20 个精选）
1. **伪订单号算法统一（库存泄漏修复）**：orderNo→SHA-256 前 8 字节（原 24 字符 fold-hash 必然 long 溢出、跨单碰撞）——`OrderService.java:557` + `OrderTransactionConsumer.java:90`（T-067/P2-12）
2. **MySQL 幂等占位探测**：预扣前 INSERT 幂等表（走 master 无延迟），失败 finally 删除占位允许重试（`InventoryService.java:229-249`）
3. **ZSet 过期索引**：预扣同时 ZADD score=过期毫秒，回退任务提前 60s 扫描，替代全库 SCAN（`PreDeductTimeoutJob.java:101-115`）
4. **订单事件溯源防重**：`INSERT IGNORE` + `uk_order_event_seq`；已存在不 return，而是乐观锁收敛目标状态（`OrderEventService.java:72-78`）
5. **补偿分派**：RELEASE_STOCK/RETURN_COUPON/CLOSE_ORDER 专用补偿方法（不依赖订单状态），重试≥2 次写 pending Set 每分钟重放（`OrderCompensationConsumer.java:98-120`）
6. **安全解锁 Lua**：GET==ARGV 才 DEL，防锁过期误删他人锁（`OrderService.java:101`）
7. **TCC fence CAS**：cancelFence 1→3 才解冻，迟到 Confirm 被拒（`TccTimeoutJob.java:80-99`）
8. **笔记删除 tombstone**：5min 删除标记拦乱序重投复活（`FeedPushConsumer.java:89-96`）
9. **Feed 断点续推**：每批 Pipeline 后写 cursor Hash，重试从 cursor 续推（`FeedPushConsumer.java:130,160-215`）
10. **一次 Pipeline 双写**：收件箱 + 推荐 FOLLOWING 召回源（修复召回恒空）（`FeedPushConsumer.java:194-199`）
11. **购物车序列号 CAS**：事件带实例内单调 seq，"旧 ADD 不覆盖新 DELETE/CHECK_ALL"（`CartSyncConsumer.java:103,168`）
12. **HMAC 细节**：签名串 `method|path|query|ts|nonce|bodyHash`，per-session 密钥，Redis 异常 fail-closed（`HmacSignatureFilter.java:179,197-207`）
13. **X-User-Id 防伪造**：网关 set 覆盖 + 无 JWT 直连剥离伪造头（`GatewayAuthFilter.java:127`、`GatewayAuthTrustFilter.java:38-43`）
14. **Feign 内部令牌注入坑**：Feign 子上下文 new 实例化导致 @Value 不注入 → 字段默认值读环境变量（`FeignInternalCallInterceptor.java:18-30`、T-017）
15. **ticket 两步鉴权**：SSE 30s getAndDelete / IM 5min JWT type=ws_ticket，避免 URL 泄露长期 Token
16. **SSE 双参删除**：`remove(key,value)` 防旧连接回调误删新连接（C7）
17. **一致性哈希环指纹缓存**：实例列表不变复用环（150×N 节点不重建）
18. **多级缓存逻辑过期**：L2 逻辑过期→抢锁异步刷新+返回旧值；空值 5min 物理 TTL 防 ID 扫描（`SpuService.java:489-534`）
19. **热搜四合一 Lua**：屏蔽词+IP 限频+用户幂等+分钟桶 HINCRBY 一个脚本消除 TOCTOU（`HotSearchService.java:60-86`）
20. **混沌工程热更新**：chaos 表（DELAY/EXCEPTION/RETURN_NULL、probability）+ Nacos @RefreshScope 动态注入（`ChaosProperties.java:20-35`）

## 3. 修复痕迹（T-* 精选，可支撑"21 个运行态修复"故事）
- T-073 Lua 大数取模失真 → Java 传 routeBucket
- T-124 bucket count 无 hash tag（Cluster 跨 slot）
- T-107 购物车 checked 恒 1（新增 qty+10000 标志位）
- C-05 乱序旧事件覆盖新状态 → 全链路时间戳 CAS
- T-130 模板缓存无 TTL → 5min
- T-119 Long→String 序列化致 noteCount 恒 0
- T-112 SkuVO 字段名不符致 skuName 恒 null
- P0-B IM 会话 ID `min*31+max` 碰撞 → 雪花
- T-106 Sentinel/WebFlux block handler 不兼容致限流悬挂 30s → 双注册
- T-048 网关 max-life-time 必须 < 下游 keep-alive 60s（设 45s）防 PrematureClose
- C-07 SSE 旧回调误删新连接
- M17 SSE 跨实例订阅只 afterPropertiesSet 未 start
- T-072 updateById 含分片键被 ShardingSphere 拒绝 → 按 id 广播更新
- P0-C Feed 区间参数颠倒致收件箱恒空
- 坑51 未读对账游标 LIMIT 切割跳边界 → id 游标
- PaymentReconcileJob 死代码（无调度入口）→ 补 @XxlJob

## 4. 任务与兜底链路（节选）
- @Scheduled：Counter 双 Buffer 5s、Outbox 5s/200、预扣超时 60s、TCC 超时 60s、SSE 心跳 10s、ES 增量 60s、热搜 60s…
- @XxlJob：localMessageRetry 30s、deadLetterScan 1h、orderClose 1min、orderMappingRepair 5min、paymentNotifyCompensate 2min、refundNotifyCompensate 3min、payment/refundTimeoutCheck 30s/60s、paymentReconcile 3 点、inventory/coupon/counter/cart 对账（3/2/3/4 点）、unread 5min、follow 1h、feedCleanup 3 点、recommend ItemCF 2h/特征 1h/热池 30min

## 5. 观测亮点
- 指标预注册空标签防"业务系列被吞/恒 0"（P-D42/P1，`BusinessMetrics.java:115-139`）
- URI 归一化（≥10 位混合 ID → `{id}`）防时间序列爆炸（`ApiMetricsFilter.java:12`）
- DLQ Gauge 取值只读缓存、采集线程 30s 查询（指标抓取零 I/O）
- traceId 6 个染色 Header + TaskDecorator 深拷贝 + MQ userProperty 透传 + WS 三级兜底
- SQL 熔断器：慢 SQL 200ms/连续 5 次/冷却 30s（`SqlGuardInterceptor.java:37-43`）
- 优雅停机：Nacos 注销 → sleep 10s → Counter 刷盘 → 线程池收敛（`GracefulShutdownListener.java:20-46`）
- Jackson 安全白名单曾漏 `java.math` 致 BigDecimal 反序列化失败/L2 恒穿 DB（T-046）

---
# 第二轮：盲区补全（状态机/网关/缓存/调度/告警/部署/测试资产）

## 6. 状态机与枚举（条件 UPDATE = 合法迁移）
- 订单：0待付/1已付/2已发/3完成/4取消/5退款（`OrderService.java:1077-1086`）；事件映射 `OrderEventService.java:37-46`（ORDER_CREATED→-1，修复原值 0 与初始态相同致事件永不落库 T-110）；原子迁移 `OrderMapper.java:49-53`（WHERE id+user_id+status+deleted）；时间戳列各要求状态（cancelled=4/payed=1/delivered=2/completed=3）；动作约束：取消仅 0、支付仅 0、发货仅 1、确认仅 2、退款仅 {1,3}（5 幂等）；超时扫描 status=0+deadline（`:66-67`）；支付回调竞态不抛 500 防误退款（`:752-758`）
- 内容：NoteStatus 显式迁移表 DRAFT→{AUDITING,PUBLISHED}、AUDITING→{PUBLISHED,OFFLINE}、PUBLISHED→{OFFLINE}（`NoteStatus.java:20-48`）
- 券：0未用/1已用/2过期；核销 `WHERE id AND status=0`、退券 `WHERE id AND status=1 AND used_order_id=同单`（防跨单退）、过期批处理派生表绕 LIMIT（`UserCouponMapper.java:21-59`，T-058）
- 支付/退款：0待付/1成功/2失败/3退款；回调 `WHERE order_id AND status=当前`；退款 `WHERE refund_no AND status=0`；状态 key TTL 7 天
- 本地消息/推送/补偿/TCC 状态编码（`init-all.sql:500-512,659-674,817-864`）；TCC 明细迁移 `WHERE xid AND branch_id AND sku_id AND status=from`（`InventoryMapper.java:114-115`）

## 7. 网关 8 过滤器（逐一）
| # | 过滤器(order) | 关键点 |
|---|---|---|
| 1 | BodyCache(+0) | 1MB 上限 413、multipart 跳过（T-041）、空 body defaultIfEmpty（T-130） |
| 2 | RequestLog(+100) | 32hex traceId、nanoTime 计时、sw8 同 trace（T-099）、不记 body |
| 3 | GatewayAuth(+1000) | JWT type=access、黑名单 Redis 故障 fail-closed、X-User-Id/X-User-Role set 覆盖防伪造 |
| 4 | TrafficColoring(+1200) | 6 染色头、AB=hash%3、压测标记仅 10.x 客户端、XFF 追加真实 IP（P0-7） |
| 5 | Hmac(+1500) | 默认关；±5min、nonce SET NX EX 300、签名 `method|path|query|ts|nonce|bodyHash`、per-session 密钥、缺钥 fail-closed、常量时间比较 |
| 6 | RateLimit(+2500) | Sentinel 固定 1s 窗；各路由阈值：user50/order10/payment5/inventory30/cart50/coupon30/notification50/im100/search300/ai100/content·product500/home·recommend300；Nacos 真空 30s 兜底；双 BlockHandler（T-106） |
| 7 | GrayRoute(+3000) | GRAY_PERCENT=10（userId hash%100） |
| 8 | ApiVersion(+3100) | X-Api-Version 默认 v1、未知版本降级不拒绝 |

## 8. 缓存体系
- API：`getWithCacheAside`（miss 回填；Redis 挂降级 DB 不回填）、带锁版（`tryLock(3,10s)` 双检+100ms 重读）、`deleteAfterUpdate`（3×50ms）、`delayDoubleDelete`（500ms + MQ 兜底 `CACHE_EVICT_TOPIC`）
- 空值占位 TTL 2min；防雪崩 TTL+rand(timeout/6)；布隆 100 万/1% + 空值缓存双层防穿透；L2 逻辑过期 30min/物理 120min（商品）
- 失效通道：CACHE_EVICT_TOPIC（user 组 3 次重试）；库存走 Canal → INVENTORY_CACHE_TOPIC（版本号 Lua 防乱序、SCAN 64 删三类 key）；索引 NOTE/PRODUCT_INDEX_TOPIC 各 3 分区
- 预热：热搜 Top50/热门笔记 Top100/分类树，TTL 1h

## 9. 调度与告警
- XXL-Job：admin 192.168.0.142:18080、executor 端口 order9991/payment9992/cart9993/home9994/coupon9995/inventory9996/search9997/counter9998/analytics9999/notification9990；默认 misfire DO_NOTHING/路由 FIRST/SERIAL；18 个任务（频率见上文）
- **Prometheus 28 条规则**（顶级几条）：ServiceDown(up==0,1m,P0)、HighErrorRate(5xx>1%,2m,crash)、HighJvm>0.85、HighHikari>0.9、Redis 内存/连接、ES 非 green、Canal 延迟>60s、DlqMessageDetected>0、OrderHighLatency P99>2s、PaymentHighErrorRate>2%(P0)、TrafficSurge(>3×)、MysqlDown、RocketmqDlqBacklog、节点 CPU/内存/磁盘>90%；3 条注释态未启用
- Grafana 10 看板（api/biz/jvm31/node33/mysql18/hikari10/redis12/mq11/es11/tomcat4 面板）
- SkyWalking：采样 10%、recordDataTTL 3d、metricsTTL 7d；ELK：Logstash 15044/15045 → ES，ILM **30 天删除**、1 分片 0 副本

## 10. 部署与日志
- docker compose 27 服务（全部 host 网络）：端口清单见正文；Nacos namespace my-xhs，仅 3 个 dataId（common/gateway/redis）
- JVM 三档 512m/256m(GW)/1024m(HEAVY)，G1 MaxGCPause 200ms，挂 SkyWalking agent；容器模板 MaxRAMPercentage=75
- 日志主链路：logback→TCP 15044→Logstash→ES `myxhs-logs-*`；中间件 stdout→Filebeat→15045；防火墙白名单 25 端口

## 11. 测试与演练资产
- 测试矩阵分层法 L1 业务/L2 数据/L3 生产级/L4 可观测（禁越层）；现行分支 L1 135/L2 74/L3 84/L4 47=340 行；用例库 G1~G8（15 文件）+ 时间矩阵（20 个 xxl + 14 个 @Scheduled）
- 脚本：scripts/test-07~14-*.py（8 个服务断言）、full-chain-test v1/v2/v3（v3 单 traceId 串 15 服务）
- chaos：ChaosBlade 7 场景（Redis pause/延迟、MQ pause、CPU 满载、磁盘 burn、MySQL pause、优雅停机）+ 应用级 chaos（DELAY/EXCEPTION/RETURN_NULL + probability，@RefreshScope）
- 慢下游实录：UserService DELAY 11s → 网关 504；listener 75s → 回查恰好一次；iptables 断 Redis/MQ
- wrk 归档：product1020/note1096/home764/recommend761/search683；ES 0.5→2 核后 search 175→683 RPS（3.9x）、P50 115→29ms

---
# 第三轮：文档层与复盘（设计决策/踩坑/量化）

## 12. 运行态对账复盘（99-runtime-reconciliation-report，最强故事源）
- 核心论断：「中间件进程在跑 ≠ 链路就绪」；此前全是源码级+Mockito，从未业务级端到端
- **XXL-Job 全缺失**（10 executor 在线但 0 任务，兜底从未运行）→ 全量注册 19 任务；**RocketMQ 业务 topic 全缺失**（autoCreate=false，发送全报 No route）→ 补建 18 topic
- **资损级**：RefundNotifyCompensateJob 把部分退款误判全额，释放全部库存+退券 → 只补偿 `t_payment.status=3`
- **根因级**：JacksonConfig 全局 Long→String 导致 MQ 跨模块解析断裂（预扣断链）→ 4 个消费端兼容字符串/数字 id
- **TransactionConfig 类级 `@ConditionalOnBean` 评估过早 → 所有读写分离服务 `@Transactional` 静默失效（脏数据）**；移到 @Bean 方法级后复发（home 无 DataSource 又能启动）→ jcmd 确认 15 服务全加载
- ES JavaTimeModule 缺失 + 单条脏数据阻塞整批 → 单条 try-catch 跳过；地址获取失败降级空地址 → 改为拒绝下单（ADDRESS_NOT_FOUND）
- 并行 `mvn -am package` 本地仓库竞争致部分服务内嵌旧 common → 串行 clean + 先 install common
- 量化：20 并发预扣 149→129 不超卖；10 并发券 remain 98→96（限领 2）；点赞 Set 化重复不重复；事务回查 75s 延迟后预扣恰好一次 118→117
- 混沌发现：gateway 下游超时返回 500 而非 504（ResponseStatusException cause 链未识别）→ 修复；chaos 对 Mapper 永不命中（JDK 代理 `$Proxy128`）→ `getSignature().getDeclaringType()` 修复；借此制造"Redis 成功/MySQL 失败"半成功验证 FollowCounterRepair 补插 1 条
- **Buffer 刷盘 3 次全败即丢增量、对账以 DB 为基准不覆盖"DB 无行"** → 修复=全失败回写缓冲
- **DLQ 指标恒 0**：`searchOffset(now)-maxOffset` 同队列恒等 → 改 `maxOffset-minOffset`
- 性能：ES 容器 CPU 配额 0.5 核致 P50 115ms（非查询慢）→ 扩 2 核后 search 175→683 RPS（3.9x）、P99 313→96ms；限流校准保留 ≥40% 余量
- 收官：117/117 矩阵收口 + 21 运行态修复 + 15 服务 UP

## 13. 测试方法论（可讲"怎么保证质量"）
- 四层递进不可越层：L1 业务→L2 数据→L3 生产级→L4 可观测；L1 每端点三问+ASCII 流转图；L3 五透镜（性能/可扩展/微服务/并发/安全）；L2 七层数据验证（HTTP/Redis/MySQL/MQ/ES/SW/Prom）禁"HTTP 200 即通过"
- 时间矩阵原则"测试不依赖真实时间"：xxl 手动触发/短周期等待/长窗操纵数据/TTL 直查；40 项时间机制总表，兜底优先级"投消息>操纵>API>等待>审查"
- 审查发现：xxl executor_timeout 全 0（悬挂风险）、misfire DO_NOTHING；独立复核"成熟度中等偏上"但列 1 资损+2 高危正确性+1 高危一致性

## 14. 四服务深机制（analytics/counter/notification/search）
- analytics：点赞双向 Set（正向+用户反向，评论仅正向省内存），选 Set 抗乱序；版本 key TTL 24h；收藏 ZSet（score=时间）取消用原 score 回滚；FollowCounterRepair 以 ZSet ZCARD 为权威、单向 Redis→MySQL，盲区=全丢则不触发
- counter：双 Buffer（`@Contended` 防伪共享）+ 满 100/5s 触发；先 snapshot 再换 buffer 无丢失窗口；批量按 (target,type) 排序防行锁死锁；upsert 用增量语义 `count=count+VALUES` 防多实例丢增量；量化：10000 次点赞合并 2000 key/1 次批量 SQL，**DB 写压力 -80%**；对账 Redis 为权威
- notification：聚合窗口 300s、Lua SETNX 占位再回填（原 INSERT-first 撞唯一键 DuplicateKey → SETNX-first + 唯一约束降普通索引）；未读三 Lua（SAFE_DECR/HDECR/RESET）防负数；对账每 5min batch500/LIMIT5000+sleep50ms 限速，DB 为源单向覆盖；**免打扰未实现**；口径冲突：文档 5min vs 代码"当天剩余"
- search：热搜 `Score=Σ(count×e^(-λΔt))` λ=0.1、窗口 60min、Top50、5min 重算 + tmp key RENAME 原子换榜；衰减量化 5min0.61/30min0.05/60min0.002；反作弊三合一 Lua（屏蔽词+IP 10 次/分+用户幂等 300s）；推荐 5 路召回 500→粗排 100（权重 1.0/0.9/0.8/0.6/0.5）→精排 50（来源 25%+偏好 25%+质量 30%+时效 e^(-h/24) 20%）→重排 20（Seen 7 天/同品类≤2）；ItemCF 近 7 天行为、交互≥5、cos=co/sqrt(A*B)

## 15. 运维复盘（现象→根因→修复→沉淀）
1. Canal 在跑不下发：1.1.7 与 JDK17 不兼容、decoder 静默罢工 → 切 Kona JDK 8 并对位点；沉淀"勿换回 JDK17"
2. SkyWalking 业务链路全无：真根因=gateway 插件在 optional-plugins 未移入 plugins（此前误判 SCG 盲区）→ 移 5 插件；22 span、跨进程/跨线程 refs 完整
3. ES 查不到 traceId：15 模块 logback 缺 `<includeMdcKeyName>` → 补 3 个 MDC 字段
4. Prometheus 自身 DOWN/Grafana 认证失败：self-scrape target 写错机器 + basicAuth 未存密码 → 修复后 16/16 UP
5. 两个被撤回的误判：`t_order` 缺失实为 4×4 分片正常；改 OAP 时区为 CST 会污染 time_bucket → UTC 窗口是正确实践
6. SC 2023.0.1 Feign/LB 连环坑：LoadBalancer hashCode NPE + contextId 冲突 + serviceId=default → URL override 等 6 条清单
