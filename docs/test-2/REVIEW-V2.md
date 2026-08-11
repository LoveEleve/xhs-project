# my-xhs 深度代码审查 2.0（REVIEW-V2）

> 2026-08-10 | 逐模块逐文件核对真实源码，按 6 维度（业务/工程/分布式/微服务/安全/运维）深审。
> 范围：除 user 外全部模块。每条含 严重度 + 文件:行号 + 问题 + 修复建议。
> 说明：大量缺陷已在 Task2 中修复（#46-52），此处为**代码级新发现**。**高危需优先处理**。

---

## 一、跨模块高危（多模块复发）

| # | 问题 | 位置 | 影响 |
|:--:|------|------|------|
| X1 | **X-User-Id 信任边界**：gateway 已用 set() 覆盖防伪造，但各服务直连端口(19015等)可伪造 X-User-Id 越权 | 各 controller | 绕过网关=身份伪造 |
| X2 | **内部 token 硬编码默认值** `my-xhs-internal-token-2026` 入库 | common/各 yml | 内部接口可伪造 |
| X3 | **Redis/MySQL 明文密码入库** | 各 application.yml | 凭据泄露 |
| X4 | **写后读走从库**（读写分离强制 query→slave） | product/inventory | 写后读旧值/脏缓存 |

---

## 二、product（子代理深审）

| 严重 | 位置 | 问题 |
|:--:|------|------|
| 高 | SpuService:272 | 布隆过滤器 Redis 故障时 null，afterCommit NPE |
| 高 | ReadWriteRouting | 写后读走从库→新商品最长2min判"不存在"+空值缓存 |
| 中 | SpuService:478-514 | 防击穿锁失效：未获锁线程仍直查DB回填 |
| 中 | SpuService:612 | 下架商品详情仍可访问 |
| 中 | ProductController:59 | 幂等key过弱(名称+分类) |
| 中 | SkuService:141-157 | resolveSpuImage N+1 逐SKU查DB |
| 中 | SpuService:339-342 | 延迟双删固定1s sleep 占共享线程池+CallerRuns阻塞请求线程 |
| 中 | 各写路径 | 仅 updateSpu 延迟双删，其余单删 |
| 中 | application.yml:138 | 内部token硬编码默认值 |
| 中 | 异步任务 | SPU_ASYNC_EXECUTOR 丢 TraceId(MDC未传播) |
| 低 | SpuService:151 | 布隆 count() O(n) 拖慢启动 |

## 三、cart + inventory（子代理深审）

| 严重 | 位置 | 问题 |
|:--:|------|------|
| 高 | InventoryCompensationJob:75-78 | release.lua 缺 indexKey/orderId 参数，单SKU订单补偿必失败→死信 |
| 中 | CartSyncConsumer + DATETIME秒截断 | 纳秒时间戳被MySQL DATETIME截断→乱序保护失效 |
| 中 | PreDeductTimeoutJob:207-234 | 超时释放RELEASE无outbox，MQ丢→locked_stock幽灵锁 |
| 中 | InventoryReconcileJob:100-108 | L3只修available不修locked_stock |
| 中 | InventoryController TCC | Confirm/Cancel对Fence拒绝静默返回成功→订单已支付却无库存 |
| 中 | OrderTransactionConsumer:86-99 | pseudoOrderId折叠哈希碰撞→串单超卖(低概率) |
| 中 | InventoryCacheEvictConsumer:175 | UPDATE事件跳过→Canal缓存失效名存实亡 |
| 中 | 库存桶初始化 | init/reinit 非原子，失败半初始化 |

## 四、coupon（子代理深审）

| 严重 | 位置 | 问题 |
|:--:|------|------|
| 高 | UserCouponMapper:46-51 | batchExpire 多表UPDATE+INNER JOIN+LIMIT，MySQL不支持→每小时任务必挂 |
| 高 | CouponService:516-545 | syncSend超时删outbox+回滚Redis，但broker已收到→Redis虚高可超发 |
| 高 | CouponController:103,113 | X-User-Id无校验可伪造领券/查他人券 |
| 中 | useCoupon非幂等 | 订单Feign重试→券已核销二次调用失败 |
| 中 | CouponService:429 | available未过滤validStart未生效 |
| 中 | CouponService:246-247 | Lua -3重试后-2/-1错误归因为SOLD_OUT |
| 低 | CouponService:337 | 满减券可0元购(减免==orderAmount) |
| 中 | DLQ无告警 | maxReconsumeTimes后进DLQ无补偿 |

## 五、order + payment（子代理深审）

| 严重 | 位置 | 问题 |
|:--:|------|------|
| 高 | OrderController:126/PaymentService | 已取消/关单订单可支付→钱扣了订单不变→钱货两空无退款 |
| 高 | OrderService:632-647 | 支付-取消竞态无退款补偿 |
| 高 | OrderCompensationConsumer:113-116 | RELEASE_STOCK/RETURN_COUPON一律调closeTimeoutOrder，已取消(status≠0)被跳过→库存/券永久泄漏 |
| 高 | 事务消息+本地消息表双投递 | 同topic双发→必然重复(靠pseudoOrderId幂等兜底) |
| 高 | inventory removeMark崩溃窗口 | 处理中崩溃→标记残留→整条跳过→超卖 |
| 中 | OrderService:122 | 幂等键未按userId隔离→跨用户误伤 |
| 中 | OrderService:132 | 锁失败删幂等键→幂等破坏 |
| 中 | OrderService:889 | saveOrderNoMapping异步非事务→回调反查失败 |
| 中 | PaymentController:128 | /payment/status 无鉴权可枚举 |
| 中 | PaymentService:592 | Redis KEYS命令阻塞单线程 |
| 中 | OrderEventMapper无分片键 | 广播扫描16分片 |

## 六、content + analytics（子代理深审）

| 严重 | 位置 | 问题 |
|:--:|------|------|
| 高 | CommentService:233+counter | 级联删评论计数只减1(忽略count字段)→计数偏大 |
| 中 | NoteService:334 | VIEW计数无去重可刷 |
| 中 | LikeService:109-124 | rollbackLikeLua死代码+like/unlike回滚不对称 |
| 中/高 | FollowService:108-111 | 粉丝侧失败吞异常仍发counter事件→双源不一致 |
| 中 | FollowService:82,99 | 关注数双份存储(本地+counter)可漂移 |
| 中 | LikeService:109 | like MQ失败不回滚Redis |
| 中 | 无Sentinel | 仅@RateLimit且Redis故障时放行无告警 |

## 七、counter + home（子代理深审）

| 严重 | 位置 | 问题 |
|:--:|------|------|
| 高 | FeedService:82-83 | **H01-feed恒空根因确认**：reverseRangeByScoreWithScores(minScore,0)参数颠倒，收件箱恒空 |
| 高 | ContentFeignFallbackFactory | content宕机降级返回空Map→NoteAggService误判404 |
| 中 | CounterService:311 | 计数key无TTL→Redis只增不减 |
| 中 | CounterService:542 | 对账方向错：合法0被DB旧值还原→虚增 |
| 中 | CounterEventConsumer去重 | Buffer失败被去重拦截→Redis+1但DB未刷 |
| 中 | NoteDeleteConsumer:49 | 删笔记不清粉丝inbox |
| 中 | FeedService N+1 | 逐noteId并行Feign扇出 |
| 高 | HomeController:53,72 | 直接信任X-User-Id(独立端口可伪造) |

## 八、search + notification + im（子代理深审）

| 严重 | 位置 | 问题 |
|:--:|------|------|
| 高 | IncrementalIndexSyncJob:227-228 | 商品补偿 `FROM t_spu` **漏加 my_xhs_product. 跨库前缀**(同类漏改)→补偿必失败 |
| 高 | NoteSearchService:139 | 搜索硬过滤status=2，但写入端默认status=1→发布状态语义不一致 |
| 高 | 版本号空间混用 | 补偿用currentTimeMillis vs Canal用es→ExternalGte永久拒绝Canal更新 |
| 高 | ImController:46-62 | IM WS ticket JWT密钥硬编码默认值+/api/im/ws白名单放行→可伪造 |
| 高 | ImWebSocketHandler | 同实例重连旧连接关闭回调误删新路由 |
| 中 | IndexRebuildJob:332 | 重建计数/价格硬编码0→热度排序失效 |
| 中 | RecommendService:386 | 精排偏好桩+key用noteId非userId |
| 中 | 反作弊IP | 用X-Forwarded-For可伪造绕过热搜限频 |
| 中 | NotificationAggregator:63 | 模板缓存永不过期 |
| 中 | IncrementalIndexSyncJob | 无分布式锁+fixedRate与注释不符 |

## 九、gateway（子代理深审）

| 严重 | 位置 | 问题 |
|:--:|------|------|
| 高 | HmacSignatureFilter:169 | HMAC取per-user secret未try/catch，Redis故障→非白名单全500(与注释"降级放行"矛盾) |
| 高 | HmacSignatureFilter:183 | **HMAC不签body/query**→防篡改失效(改金额/数量不匹配) |
| 高 | admin端点豁免HMAC+X-Admin-Call未校验 | 合法JWT普通用户可调管理写接口 |
| 中 | GatewayAuthFilter:217 | 黑名单Redis故障Fail-Closed拒401 vs HMAC Fail-Open 矛盾 |
| 中 | 路由metadata超时不生效 | 全局2s/10s，order 8s形同虚设 |
| 中 | CachingFilteringWebHandler死代码 | 未注册为Bean |
| 中 | Sentinel单机无用户级 | 多实例QPS放大 |
| 中 | 白名单路径不清X-User-Id | 未鉴权路径可自造X-User-Id透传 |
| 中 | 改密在HMAC白名单 | /api/user/me/password无签名防重放 |
| 低 | 无熔断/重试 | 下游故障直接透传 |

---

## 十、优先修复清单（按危害）

### P0（资金/安全/必挂）
1. **OrderCompensationConsumer 库存泄漏**（五）
2. **已取消订单可支付/支付-取消竞态无退款**（五）
3. **InventoryCompensationJob release.lua 参数缺失**（三）
4. **UserCouponMapper.batchExpire 语法错**（四）
5. **IncrementalIndexSyncJob 漏跨库前缀**（八）
6. **HMAC 不签 body / Redis 故障 500**（九）
7. **X-User-Id 伪造 / 内部token硬编码 / 明文密码**（跨模块）
8. **事务消息+本地消息表双投递**（五）

### P1（数据一致性/功能）
9. **H01-feed 恒空（参数颠倒）**（七）
10. **content 降级误报404**（七）
11. **comment 级联计数少减**（六）
12. **coupon syncSend 超时超发**（四）
13. **写后读走从库**（二/跨）
14. **布隆过滤器 NPE**（二）
15. **counter 对账方向错误/无TTL**（七）

---

> 此 2.0 梳理为**发现清单**。P0 项建议逐条实施修复（已有多项在前文 #39-52 修复，此为新发现）。修复需按"改码→重打包→重启对应服务→验证"流程逐一闭环，并更新 pitfalls.md。

---

## 十一、运维 Review（日志 / 全链路 / 监控，2026-08-10）

> 结论：主体完善（HTTP/Feign/MQ/异步/池全部覆盖），但有 4 个运维缺口。**仅记录，暂不修复。**

### ✅ 已完善
- 监控端点：15 服务统一 health/info/prometheus/metrics/loggers
- 日志：15 服务 logback-spring.xml 含 MDC traceId/spanId/userId + Logstash
- 全链路：HTTP TraceIdConfig、FeignTraceInterceptorConfig、MqTraceHelper（21 消费者全 restore）、common AsyncConfig TaskDecorator、home/search MdcAwareExecutorService、SkyWalking agent

### ❌ 运维缺口（含修复方法，暂不实施）

| # | 缺口 | 影响 | 修复方法 |
|:--:|------|------|------|
| **O1** | **MDC userId 从未写入**（TraceIdConfig:84 只 `MDC.put("traceId")`，全 common 无 `MDC.put("userId")`） | Logstash 采集的 `userId` 字段恒空，日志无法按用户关联 | 在鉴权/HTTP 层写入 userId 到 MDC（如 TraceIdConfig 或新增 Filter，从 X-User-Id 取）；logback 已声明 userId 字段，只需填充 |
| **O2** | **product SPU_ASYNC_EXECUTOR 裸 ThreadPoolExecutor**(SpuService:75-80)，不走 common AsyncConfig | 缓存刷新/延迟双删/布隆加载异步日志丢 traceId | 改 MdcAwareExecutorService（同 home/search）或加 TaskDecorator 拷贝 MDC |
| **O3** | cart CartController:149 裸单线程池(minor) | 该操作日志丢 traceId | 同 O2，改用 MdcAwareExecutorService |
| **O4** | 自定义业务监控指标极少（仅 DLQ、本地消息死信） | 库存漂移/超发/对账无指标告警 | 为 claim/预扣/下单/支付等核心链路加 Micrometer Counter+Timer 指标，Prometheus 可查 |

> O1-O4 列入后续修复批次（运维专项），当前仅记录。
