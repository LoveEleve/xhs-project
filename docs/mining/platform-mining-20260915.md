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
