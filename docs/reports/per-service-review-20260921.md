# 逐服务五维度评审台账（2026-09-21）

> 方法：4 组并行评审（每组 3-5 个服务），维度 = 业务逻辑 / 工程 / 方案合理性 / 分布式 / 微服务。每条含 `文件:行号` 证据与级别。
> 本轮已修 16 项（见 §0）；其余登记如下（按服务分组，级别 高/中/低）。**未修不等于不重要**——按优先级排期。
> 相关：域设计见 `aftersale-settlement-20260921.md`；故障推演见 `fault-drill-matrix-20260921.md`；生产语义见 `fault-semantics-review-20260921.md`。

## 0. 本轮已修（16）
| # | 服务 | 问题 | 级别 |
|---|---|---|---|
| 1 | inventory | 退款回补补偿行存 delta（重试被当累计目标 → 静默少补） | 高 |
| 2 | order | 事务消息状态异常但本地事务已提交 → 报失败致用户重试重复下单 | 高 |
| 3 | coupon | 领券发送失败"回滚 Redis + Outbox 补发"并存 → 超发（新增 status=2 作废） | 高 |
| 4 | payment | `@Transactional` 挂到查询方法上（refund/refundByOrder 无事务）+ 支付库缺专用事务管理器 | 中 |
| 5 | counter | 评论版本门按 noteId（不同评论互相误判丢计数）→ 改按 commentId，级联跳过 | 中 |
| 6 | home | 热门评论读错字段（`list` vs `records`）→ 恒空 | 高 |
| 7 | home | `getNow()` 先于异常判定 → 503 降级失效（商品）+ 超时被当"用户不存在"（用户） | 高 |
| 8 | im | 重连踢旧时旧连接关闭事件误删新连接路由 | 高 |
| 9 | user | 地址脱敏导致订单快照手机号落库为 `138****1234` → 内部端点返回明文 | 高 |
| 10 | gateway | 请求体在鉴权前无上限聚合（未鉴权可打爆堆）→ Content-Length 预检 + 带上限 join + 顺序挪到鉴权后 | 高 |
| 11 | common | `ResultCode.getMessage` 参数含 `$`/`\` 抛异常（业务错误变 500）→ quoteReplacement | 中 |
| 12 | common | Redis 拦截器包装 StringRedisTemplate → 开启开关后 14 服务启动失败 | 中 |
| 13 | content | 评论事件补 commentId（配套 5） | 中 |
| 14 | inventory | 补偿表清理/文档同步 | 低 |
| 15 | coupon | Outbox 清理含已作废行 + DDL 注释 | 低 |
| 16 | gateway | 死配置（server.tomcat / spring.mvc）删除 + 文档更正 | 中 |

## 1. my-xhs-order
| 维度 | 问题 | 级别 |
|---|---|---|
| 业务/并发 | `skuItems` 未按 skuId 聚合，两行同 SKU（60+60，库存 100）校验都过、预扣只扣 60 → **超卖**（`OrderService.java:175-187`） | 中 |
| 业务/契约 | `payAmount` 可为 0（券全额抵扣），而 payment 侧 `amount` 是 `@Positive` → **0 元单永远发不起支付**，只能等 30min 关单（`OrderService.java:193-196` + payment `PayCreateRequest.java:89-91`） | 中 |
| 业务/越权 | 发货只校验 `X-User-Id`，**买家可自行置"已发货"再确认收货**（`OrderController.java:113-120`） | 中 |
| 分布式/幂等 | 幂等键不带 userId → 不同用户同 bizIdentifier 互斥，可被恶意抢占（`OrderService.java:75,131-136`） | 中 |
| 工程/性能 | `getUserOrders` 无分页无 LIMIT（`OrderService.java:470-480`） | 低 |
| 工程/可观测 | 映射补录异常 `log.debug` 吞掉（`OrderMappingRepairJob.java:77-79`） | 低 |
| 工程/并发 | 补偿去重 `hasKey`+`set` 非原子（`OrderCompensationConsumer.java:91-95`） | 低 |

## 2. my-xhs-payment
| 维度 | 问题 | 级别 |
|---|---|---|
| 分布式/资金 | 超时 Job 置 status=2（失败），迟到成功回调静默 return，`reconcile()` 只扫 status=1 → **用户已付、系统记失败、无自动退款/对账通道**（`PaymentService.java:307-310,773-775,909-911`） | 中 |
| 方案/契约 | `pay()` 从不校验金额，而 order 侧 `/pay-amount` 注释明确"供支付服务校验"（`PaymentService.java:181-192`） | 中 |
| 业务/回调 | 回调解析失败一律按失败处理（格式不符的成功回调会被标记失败并触发订单取消）；退款回调解析失败伪造 `REFUND_<ts>` 且回 success（`PaymentController.java:224-250,172-174`） | 中 |
| 工程/死代码 | `payingKey` 只写不读（`PaymentService.java:155,195,251...`） | 低 |
| 工程/可观测 | 事件线程池 DiscardPolicy 无拒绝计数（`PaymentService.java:82-89`） | 低 |
| 分布式/补偿 | 通知补偿对非 503 错误码视为"无需补偿"（订单瞬时 500 会漏补偿）（`PaymentNotifyCompensateJob.java:159-168`） | 低 |

## 3. my-xhs-inventory
| 维度 | 问题 | 级别 |
|---|---|---|
| 分布式/乱序 | `checkEventVersion` 在 DB 操作**前**推进版本键 → DB 异常重投被判旧事件永久跳过（locked 虚高，对账不修 locked）（`InventoryDeductConsumer.java:187-200`） | 中高 |
| 业务/对账 | `locked_stock/freezing_stock` 从不对账；`reinitStock` 把 locked 计入 total → 可售虚增（`InventoryReconcileJob.java:98-108`、`InventoryService.java:920`） | 中 |
| 并发/扩容 | `refundRestore` 不查 `inventory:paused` → 扩容窗口内回补可能落旧桶被覆盖（`InventoryService.java:744-772`） | 中 |
| 业务/超时 | 预扣超时以 `now+60s` 为 cutoff **提前 60s 释放**；窗口内支付成功 confirm 找不到记录 → 未占用库存且 MySQL locked 残留（`PreDeductTimeoutJob.java:66,101`） | 中 |
| 工程/性能 | `getStock` 命中 Redis 后仍每次查 MySQL 取 locked（缓存形同虚设）（`InventoryService.java:534-553`） | 中低 |
| 工程/阻塞 | 管理端点 `/internal/reconcile` 同步全量对账（无锁/无分页）（`InventoryController.java:148-155`） | 中低 |
| 工程/性能 | 热点检测在 preDeduct 热路径 +4 次 Redis RTT、阈值硬编码（`HotSkuDetector.java:46-64`） | 低 |
| 工程/表增长 | `t_inventory_prededuct_idem` 永久保留无清理（`InventoryService.java:245-249`） | 低 |
| 业务/边界 | `releaseStock` 桶号 `parseInt` 无保护（脏值 500）（`InventoryService.java:485-486`） | 低 |
| 业务/错误码 | "库存未初始化"用 `PARAM_INVALID`（应新增语义码）（`InventoryService.java:281-287`） | 低 |

## 4. my-xhs-coupon
| 维度 | 问题 | 级别 |
|---|---|---|
| 分布式/补偿 | `rollbackRedisStock` 失败仅日志无补偿；对账又以 Redis 为准把 remain 调成已扣 → **未发出的券永久吞掉库存**（`CouponService.java:569-577` + `CouponReconcileJob.java:106-112`） | 中 |
| 分布式/对账 | 只对账 stock，不对账每用户 `claimed`（Redis 清库后可突破限领）（`claim_coupon.lua:37-46`） | 中低 |
| 并发/一致性 | `updateTemplateStatus` 与对账 Job 用 `updateById` 全字段写，覆盖并发 `decrementRemainCount`（`CouponService.java:136-141`） | 中低 |
| 业务/口径 | `getAvailableCoupons` 不过滤模板 status（下线模板的券仍现于"可用"）（`CouponService.java:422-438`） | 中低 |
| 业务/边界 | `validEnd` 为 null 时 `isAfter` NPE（`CouponService.java:208`） | 低 |
| 业务/校验 | 建模板不校验 `discountValue` 与 `minAmount` 组合（满减券可 0 元购）（`CouponService.java:88-104`） | 低 |

## 5. my-xhs-product
| 维度 | 问题 | 级别 |
|---|---|---|
| 方案/合理性 | 冷 Key 未抢到锁的线程 sleep 50ms 后**直接打 DB**（防惊群承诺不成立）（`SpuService.java:539,577-595`） | 中 |
| 工程/资源 | `SPU_ASYNC_EXECUTOR` CallerRunsPolicy 承载 `sleep(1000)` 延迟双删 → 队列满时请求线程被拖 1s（`SpuService.java:83,397-400`） | 中低 |
| 微服务/契约 | 建 SKU 只落冗余 stock，不初始化 inventory → 下单"库存未初始化"失败（`SkuService.java:64`） | 中低 |
| 工程/N+1 | 同批 spuIds 两次 `selectBatchIds`（`SkuService.java:117-119,129`） | 低 |
| 工程/死代码 | `toSkuVO` 两个重载无调用（`SkuService.java:194-200`） | 低 |
| 业务/边界 | `originalPrice` 未校验 ≥ price（`SkuCreateRequest.java:28-29`） | 低 |
| 业务/状态机 | SPU 上/下架无迁移校验（可重复上架、无 SKU 上架、下架不看在途）（`SpuService.java:411-444`） | 低 |

## 6. my-xhs-cart
| 维度 | 问题 | 级别 |
|---|---|---|
| 分布式/内存态 | `CLEAR_BARRIERS` 为进程内 map：只增不删（内存泄漏）+ 重启/rebalance 后屏障丢失 → 旧 ADD 可在 MySQL 复活已 CLEAR 条目（`CartSyncConsumer.java:45,99-103`） | 中 |
| 微服务/性能 | 合并购物车逐条串行 Feign（最多 50 次 × 5s）→ 最坏数百秒（`CartService.java:518-527`） | 中 |
| 业务/微服务 | 加购只看 SKU status 不看 `spuStatus` → SPU 下架的 SKU 仍可加购（`CartService.java:624-635`） | 中低 |
| 工程/资源 | `cart_add.lua` 新建三 Key 时无 TTL（刷新失败即永不过期）（`cart_add.lua` + `CartService.java:133-157`） | 低 |
| 业务/口径 | `/cart/count` 用 `HLEN` 含失效条目（角标与列表不一致）（`CartService.java:570-574`） | 低 |
| 并发 | 单用户对账端点无锁 + `watermarkSkipped` 实例字段多线程写（`CartController.java:182-192`） | 低 |

## 7. my-xhs-counter / my-xhs-analytics
| 服务 | 问题 | 级别 |
|---|---|---|
| counter | `reconcile()` 绝对值覆盖 DB 与 Buffer flush 竞争 → 重复计数（`CounterService.java:562-569`） | 低 |
| counter | 关注回滚条件过窄（"归零保护"与去重共用 false，不重试不分叉）（`CounterEventConsumer.java:395-404`） | 低 |
| counter | `Long.parseLong` 直接解析 Redis 值（脏值 500）（`CounterService.java:416,473`） | 低 |
| analytics | 分页 `size=0` → `ZREVRANGE 0 -1` **返回全量**（公开接口）（`FollowService.java:223-232`、`FavoriteService.java:151-159`） | 中 |
| analytics | 每小时 Job 直接**绝对覆写** counter 服务的 Redis 键（绕过增量/去重；无 TTL）（`FollowService.java:486-491`） | 中 |
| analytics | favorite ZSet 无 TTL 续期、无收藏对账任务（`favorite_atomic.lua:10`） | 低 |
| analytics | 关注 Step B 失败仅日志（依赖每小时 Job）（`FollowService.java:122-132`） | 低 |
| analytics | 消费者异常时删版本 key → 重试期间更旧事件可穿过版本门（`LikeUnlikeConsumer.java:90-93`） | 低 |

## 8. my-xhs-content / my-xhs-home
| 服务 | 问题 | 级别 |
|---|---|---|
| content | `compensateIncompletePush` 无次数上限 60s 重投 + 消费端早退不写 `push_status=2` + 删除标记仅 5min → **已删笔记可被写回 Feed**（`FeedMessageRetryJob.java:138-179`、`home/FeedPushConsumer.java:92-96`、`home/NoteDeleteConsumer.java:59-60`） | 高 |
| content | 列表只过滤 `status` 不过滤 `auditStatus`（PENDING/REJECTED 异常数据外泄）（`NoteService.java:374-377`） | 中 |
| content | UNCOMMENT/SHARE/VIEW 全 `asyncSend` 即发即忘无补偿（计数/Feed 永久漂移）（`NoteService.java:279-288,519-543`） | 中 |
| content | 重试任务锁 TTL 55s 在回调前释放 → 30s 轮次会重复投递（`FeedMessageRetryJob.java:64-110`） | 中 |
| content | `createComment` 事务内重查被回复评论，并发删除 NPE（`CommentService.java:153-155`） | 中 |
| content | `batch-detail` 公开无条数上限（万级 id 放大查询）（`NoteController.java:99-102`） | 中 |
| content | DFA 建 Trie 不做预处理（大写/空格/全角动态词永不命中）（`DFAFilter.java:194-209`） | 中 |
| content | 删除笔记未失效 `COMMENT_COUNT` 缓存（`NoteService.java:279-288`） | 低 |
| home | **大 V 读路径不回源**（key 仅发布时写、TTL 10min；大V只写 outbox → 停发 10min 后粉丝看不到其笔记）（`FeedService.java:371-387`） | 高 |
| home | `likeAndCollectCount` 1000 条上限 + `total` 判断失效（String）→ 统计静默偏小（`UserProfileAggService.java:129-170`） | 中 |
| home | `aggregatorPool` 同池嵌套（外层占满 → 每请求 3s 降级）（`HomeController.java:58-64` + `FeedService.java:148-216`） | 中 |
| home | 断点续推用 follower ZSet **下标**（取关致左移 → 永久漏推）（`FeedPushConsumer.java:143-219`） | 中 |
| home | DLQ 后 `push_status` 停 1 → 补偿 60s 死循环；大V路径不写 `following:latest`（`FeedPushConsumer.java:101-106,114-117`） | 中 |

## 9. my-xhs-search / my-xhs-im / my-xhs-notification
| 服务 | 问题 | 级别 |
|---|---|---|
| search | `recallExecutor` 同池嵌套（外层占满 → 召回 2s 超时、推荐降级）（`RecommendController.java:50-53` + `RecommendService.java:226-246`） | 高 |
| search | 全量重建断点完成后不清零 → 次日"全量"退化为增量（`IndexRebuildJob.java:156-158,249-251`） | 中 |
| search | 重建/补偿只 upsert 不清理死文档；重建不带 external version（跨版本域覆盖）（`IndexRebuildJob.java:257-291`） | 中 |
| search | `quality_score` 永不刷新（ON DUPLICATE KEY 不含该列）（`RecommendComputeJob.java:194-209`） | 中 |
| search | `sort=sales` 不可用（`sales` 从不写入 → 静默退化）（`ProductIndexDocumentBuilder.java:30-40`） | 中 |
| search | t_sku 批量 binlog 只取 `data[0].spu_id`（其余 SKU 父 SPU 不更新）（`ProductIndexSyncConsumer.java:143-151`） | 中 |
| search | `ip="unknown"` 匿名共用维度 → 热搜严重少计（`HotSearchService.java:152-156`） | 中 |
| search | `currentNoteId` ThreadLocal 不 remove（串注 + 漏补偿）（`NoteIndexSyncConsumer.java:67,95-124`） | 中 |
| im | 跨实例 Pub/Sub 即发即忘：目标实例宕机 → **消息永久丢失**（发送者已收 ACK）（`ChatService.java:139-170`） | 高 |
| im | `conversationId` 并发分配竞态（A→B/B→A 各生成一个 ID → 一半历史不可见）（`ChatService.java:466-492`） | 中 |
| im | `upsertConversation` 读改写无乐观锁（丢未读 + lastMessage 回退）（`MessagePersistService.java:73-102`） | 中 |
| im | 分页 `size` 未防负 → MP 不拼 LIMIT 全表返回（`ImController.java:84,114`） | 中 |
| im | "单用户单连接"仅单实例生效（跨实例无踢线）（`ImWebSocketHandler.java:37-73`） | 中 |
| notification | 跨实例 SSE 即发即忘（route 30s 内仍指向宕机实例，"已推送"判定失真）（`SseEmitterManager.java:136-152,232-246`） | 中 |
| notification | `serverId` 取 `System.getProperty("server.port")`（恒为默认 19013，无法区分实例）（`SseEmitterManager.java:331-342`） | 中 |
| notification | 分页 `size` 未防负（全表）（`NotificationController.java:79`） | 中 |
| notification | `UnreadReconcileJob` 分批处理却对跨批用户写"部分计数" → 对账自身制造错值（`UnreadReconcileJob.java:71-101`） | 中 |
| notification | heartbeat 无异常隔离（Redis 抖动 → 死连接留 30min）（`SseEmitterManager.java:255-291`） | 中 |

## 10. my-xhs-gateway / my-xhs-user / my-xhs-common
| 服务 | 问题 | 级别 |
|---|---|---|
| gateway | IP 判定信任伪造 `X-Forwarded-For`（伪造 `10.x` 即得压测标记）（`TrafficColoringFilter.java:159-168`） | 中 |
| gateway | 只追加 XFF、从不写 `X-Real-IP`，下游 `RateLimitAspect` 却优先读它（单一可信来源缺失）（`TrafficColoringFilter.java:110-117`） | 中 |
| gateway | Zone LB 读 JVM `-D` 系统属性，但无任何脚本设置 → 只设 `MYXHS_ZONE` 时静默失效（`GatewayZoneLoadBalancerConfiguration.java:27`） | 中 |
| gateway | `HmacSignatureFilter.hmacSecretKey` 死字段（构造快照后从未使用）（`HmacSignatureFilter.java:67,99,190`） | 低 |
| gateway | 鉴权/HMAC 失败无指标；白名单无 method 维度；`determineHttpStatus` resolve 可能 null（NPE） | 低 |
| user | **登录防爆破信任伪造 XFF**：单攻击者用 5 个假 IP 即可锁任意账号（账号 DoS），也可伪造受害者 IP 触发封禁（`AuthController.java:53-55` + `UserService.java:414-453`） | 高 |
| user | 地址写方法在 `@Transactional` 内取/放 Redisson 锁（锁先于提交释放 → 并发可产生两个默认地址/超 20 条）；`updateAddress` 在 DB 更新前写默认缓存（回滚后脏 30min）（`UserAddressService.java:57-213`） | 中 |
| user | `changePassword` 无事务且吊销异常被吞（改密"成功"但旧 token 未吊销）；`logout` 同样吞（`UserService.java:370-399`、`TokenService.java:295-303`） | 中 |
| user | `updateUserInfo` 在事务内 `delayDoubleDelete`（并发读可回填旧值；common 已有 `TransactionHook.afterCommit` 却 0 使用）（`UserService.java:352-360`） | 中 |
| user | `/api/user/batch/info` 无界 `Set` 逐条查询（放大）（`UserController.java:97-100`） | 低 |
| user | 用户名不存在不累计失败计数（枚举不受限速）；验证码获取无限流；禁用不吊销 token | 低 |
| common | **3 个 HealthIndicator 从未注册**（无 @Component/无 beans/不在 imports → 永远不 DOWN，注释宣称已覆盖）（`health/*.java`） | 中 |
| common | `CacheHelper` 最外层 catch 把 `dbFallback` 业务异常当 Redisson 异常 → 降级后**二次查 DB** 且吞异常（`CacheHelper.java:224-235`） | 中 |
| common | `SentinelBulkheadConfig` 用无效键 `System.setProperty`（舱壁隔离实际未配置，承诺的 Feign 独立线程池不存在）（`SentinelBulkheadConfig.java:34-40`） | 中 |
| common | 幂等/限流切面 Redis 不可用时一律 fail-open 且无指标（限流失效、重复提交不拦，运维不可见）（`IdempotentAspect.java:59-66`） | 中 |
| common | `UserContext` 用 TTL 且 0 引用；`AsyncConfig` 不传播 → 一旦使用会跨请求串号（`UserContext.java:21-23` + `AsyncConfig.java:64-90`） | 中 |
| common | `RedisOperator` 部分方法吞异常与类注释承诺矛盾（调用方无法感知 Redis 故障）（`RedisOperator.java:209-315`） | 中 |
| common | `LettuceMetricsConfig` 的 ClientResources 未被工厂引用（`lettuce.command.latency` 恒空）；`RedisConfig` 忽略标准键 + `2s` 解析成 2ms；`GracefulShutdownListener` 固定 sleep 10s + 反射注销；`FeignSafeConfig` 错误流未关；`GlobalExceptionHandler` 业务错误返 200 信封；`BusinessMetrics` 用 JDK 内部 `@Contended` | 低 |
| common | 死代码：`IdempotentMessageAspect`+`@IdempotentMessage`、`TransactionHook`、`DegradeSwitchManager`、`DynamicConfigRefresher`、`DomainEventPublisher`、`BizSpanHelper`（0 引用） | 低 |

## 11. 建议的修复顺序（按收益/风险）
1. **高**：content Feed 死循环与复活（§8-1）、home 大V不回源（§8-9）、search 线程池嵌套（§9-1）、im 跨实例丢消息（§9-9）、user 防爆破伪造 IP（§10-7，需与 gateway XFF 一起做）
2. **中高**：inventory 版本门先推进（§3-1）、order 同 SKU 超卖与 0 元单（§1-1/1-2）、payment 超时失败资金语义（§2-1）
3. **中**：home/search/im/notification 的分页下限、home 断点游标、notification serverId、common HealthIndicator/幂等指标、analytics 分页与绝对覆写
4. **低**：死代码清理与可观测补全

## 12. 修复进度（2026-09-21 第二轮"全修"）
### 本轮新增已修（25 项，全部编译通过）
| 服务 | 修复 |
|---|---|
| content | Feed 补偿链闭环三修：① 消费端"已删早退/大V路径"补 `markPushCompleted`（此前漏标记 → 补偿 Job 每 60s 死循环重投）② 补偿重投上限 10 次 + 超限置 `push_status=3` 终态 ③ 已删 tombstone TTL 5min→7 天（覆盖 MQ 重试窗口，防已删笔记写回 Feed） |
| content | 公开列表补 `auditStatus=APPROVED` 过滤（防未审/驳回数据外泄）；评论事件补 `commentId` |
| home | 大 V 读路径 miss 回源 `ZCARD` 并回写标记（此前大V停发 10 分钟后粉丝看不到其新笔记） |
| search | 召回内层独立线程池 `recallInnerExecutor`（此前同池嵌套 → 外层占满致召回 2s 超时、推荐降级） |
| im | 投递改 at-least-once：先落离线持久副本再推/Pub-Sub（此前目标实例宕机 → 消息永久丢失） |
| order | ① 发货限管理端（`X-Admin-Call`，买家不可自发货）② 幂等键带 userId（防跨用户抢占）③ `/list` 加 limit（默认100/最大200）④ 拒绝同 SKU 多行（此前两行同 SKU 校验都过、预扣只扣一行 → 超卖） |
| coupon | ① "可用券"过滤下线模板 ② `validEnd` 空值 NPE ③ 模板状态/对账改单字段更新（防覆盖并发扣减） |
| inventory | ① 版本门改为"只读比较 + DB 成功后再推进"（此前 DB 异常重投被永久跳过、locked 虚高）② 退款回补前检查扩容暂停窗口（且在推进累计键之前，防 delta=0 静默漏补）③ 预扣桶号解析容错（脏值不再 500） |
| analytics / im / notification | 分页下限 clamp（size<=0 此前会退化为全量返回） |
| cart | 加购校验同时要求 SPU 在架（此前 SPU 下架仍可加购） |
| product | 冷 key 未获锁改"有界等待 3×100ms 重查缓存"再降级 DB（此前只等一轮就回源，防惊群不成立） |
| common | ① 三个 HealthIndicator 从未注册 → 新增 `HealthIndicatorAutoConfiguration` 按依赖条件注册（Cache Redis/RocketMQ/堆与死锁）② `CacheHelper` 业务/DB 异常不再被当 Redisson 异常降级（此前吞异常 + 二次查 DB） |

### 仍未修（继续按 §11 顺序推进）
§1-2（order 0 元单）、§2-1（payment 超时失败资金语义）、§3-2/3-4/3-5（locked 对账 / 预扣提前释放 / getStock 查 DB）、§3-6..3-10、§4-2/4-3（coupon claimed 对账）、§5（product 库存初始化/N+1/死代码/状态机）、§6-1/6-2（cart 屏障 Redis 化/批量 Feign/对账锁/角标口径/Lua TTL）、§7（counter 竞争与解析）、§8（content 其余/ home 其余：断点游标、DLQ 终态、线程池嵌套、限速）、§9（search 重建/死文档/ThreadLocal/排序/ip、im conversationId 竞态与踢线、notification SSE 可靠性/serverId/对账错值/心跳隔离）、§10（gateway XFF 与 X-Real-IP、user 防爆破与地址锁、common 其余：SentinelBulkhead/fail-open 指标/RedisOperator/Lettuce/死代码）

### 本轮补充落地（IM 客户端契约 + 终态告警）
| 项 | 内容 |
|---|---|
| IM 客户端契约（前端 `frontend/src/pages/im/ImChatPage.tsx`） | 补齐三件套：① 处理 `OFFLINE` 批次（此前前端**完全没处理**，离线消息既不展示也不 ACK）② 收到 CHAT/OFFLINE 逐条回 `ACK(msgId)`（此前**从未 ACK**，离线副本只能等 7 天 TTL，每次重连重复补发）③ 按 `msgId` 去重（含历史种子集合）；发送侧处理 `ACK/NACK`（乐观消息临时 id 与服务端 msgId 按序配对、NACK 回滚乐观消息） |
| 服务端契约文档 | `ImWebSocketHandler` 类注释写明 at-least-once + 客户端义务（ACK/去重）+ OFFLINE 批次上限 |
| Feed 推送终态指标 | `feed.push.terminal.total{reason}`（BusinessMetrics 预注册）+ content 补偿 Job 置终态时上报 |
| 告警规则 | 新增 2 条业务告警（`FeedPushCompensationTerminal` P2、`AftersaleRefundFailed` P2，含 Runbook）；并修复根 config 与部署包规则漂移（根 config 缺 2 条 XXL 规则 → 现两份均 37 条、零差异） |

## 13. 逐服务深挖（第三轮，一轮一服务）
### my-xhs-inventory（2026-09-21 深挖）
| 发现 | 证据 | 级别 | 处置 |
|---|---|---|---|
| **locked_stock 从不对账**：预扣记录丢失/TTL 过期/确认事件失败都会留下"幽灵锁"；而 `reinitStock` 把 locked 计入总库存 → **可售虚增（可超卖）** | `InventoryReconcileJob`（只修 available）、`InventoryService.reinitStock` 计入 locked | 中高 | **已修**：新增 `reconcileLockedStock`（每轮 SCAN 在途预扣 Hash 按 SKU 汇总，与 MySQL locked 比对修正；跳过 `:index` ZSet 键；方向保守=缺失视为 0）+ `InventoryMapper.updateLockedStockOnly` |
| 版本门"检查即推进"导致 DB 异常重投被当成旧事件永久跳过 | `InventoryDeductConsumer.checkEventVersion` | 中高 | 已修（第二轮）：原子预留 + 失败条件回滚（本轮复核确认语义正确、无顺序回退） |
| locked 负数风险（我原以为存在） | `confirmDeduct/releaseStock` SQL 均带 `AND locked_stock >= qty` 守卫 | — | **核实不成立**（守卫已防负数） |
| prededuct/confirm/release Lua 复核 | 幂等（Hash 字段=skuId）、Cluster 同 slot、跨桶扫描、total 与桶原子一致、索引 ZSet member=orderId 与 TTL 一致 | — | 未发现新问题 |

### my-xhs-coupon（2026-09-21 深挖）
| 发现 | 证据 | 级别 | 处置 |
|---|---|---|---|
| **限领计数（claimed）从不对账**：计数无 TTL，Redis 清库/丢 key 后限领直接失效（可突破 perUserLimit） | `claim_coupon.lua`（claimedKey 无 TTL）、`CouponReconcileJob`（只对账 stock） | 中 | **已修**：新增 `reconcileClaimedCounters`（SCAN `myxhs:coupon:*:claimed:*`，以 `t_user_coupon` 实际发券数为准修正；在途领取±1 的窗口已在注释说明） |
| **卡住的 Outbox 不可见**：用户已被扣限领/库存但券未发出的异常无告警 | `t_coupon_outbox.status=0` 超时无检查 | 中 | **已修**：`checkStuckOutbox`（>5 分钟未发送 → ERROR 日志 + `coupon.action.total{action=outbox_stuck}` 指标）+ `CouponOutboxMapper.countStuck` |
| 领券→发券链路复核 | Lua 原子扣库存/限领；Outbox 事务发件 + 双幂等（msgId + uk_claim_no）；发送失败路径已改为"回滚 Redis + 作废 Outbox"（第二轮） | — | 未发现新问题 |
| 订单↔券一致性复核 | 下单时**占用券**（useCoupon 乐观锁 WHERE status=0 + 绑定 orderId），核销失败则取消订单；折扣不一致有告警 | `OrderService.java:283-320` | 未发现新问题（设计正确：先占券后有折扣） |

### my-xhs-order（2026-09-21 深挖）
| 发现 | 证据 | 级别 | 处置 |
|---|---|---|---|
| **事件序号并发撞唯一键被静默吞掉**：`nextSeq = lastEvent.seq + 1`（普通 SELECT）+ `INSERT IGNORE`（`uk_order_event_seq`）→ 并发流转（支付 vs 取消）中第二条事件被忽略，但**状态 UPDATE 照常提交** → 状态与事件流永久分叉（回放校准基于残缺事件流） | `OrderEventService.appendEvent`（普通读+IGNORE 后继续更新状态） | 高 | **已修**：① 新增 `findLastForUpdate`（`FOR UPDATE` 锁定读）让同订单追加在 DB 层串行；② `inserted == 0` 改为 **ERROR + 抛异常回滚**（不再"吞掉事件只改状态"） |
| 事务消息回查链路复核 | 查本地消息表且**不按 status 过滤**（`markSuccessByTransactionId` 后仍能命中 → 不会误 ROLLBACK）；查询异常返回 UNKNOWN 等 Broker 复查；header 带 `userId` 精确路由分片；`t_order.uk_order_no` 兜住"生产者重试导致本地事务跑两次" | `OrderTransactionListener`、`LocalMessageMapper` | — | 未发现新问题 |
| 退款双通道复核 | 先 `confirmInventoryDeductSync` 清预扣记录，再 `refundRestore` 唯一回补通道（注释明确防"release+refundRestore 双补"） | `OrderService.onRefundSuccess` | — | 未发现新问题（此前担心的双补已正确规避） |
| 关单补偿复核 | 按 action 分派（RELEASE_STOCK/RETURN_COUPON 不依赖订单状态）——安全性由"支付成功即删预扣记录"保证：已支付订单的 release 会因记录不存在而 no-op | `OrderCompensationConsumer` | — | 未发现新问题 |
| 登记（低） | `t_order_snapshot` **只写不读**（纯归档用途，无读取路径）；`t_local_message` 无 `transaction_id` 唯一键（现由 `t_order.uk_order_no` 间接保护，可补强） | `OrderService.java:1027`、DDL | 低 | 登记 |

### my-xhs-payment（2026-09-21 深挖）
| 发现 | 证据 | 级别 | 处置 |
|---|---|---|---|
| **pay() 完全不校验金额**：order 侧 `/pay-amount` 注释明确"供支付服务校验金额是否匹配"，但支付域从不调用 → 金额被篡改/传错都按错额记账 | `PaymentService.pay()`（只有订单状态校验） | 中 | **已修**：pay() 增加 `getOrderPayAmount` 比对，不一致直接拒绝（金额不可用时也 fail-closed 拒绝） |
| **回调解析失败被当成"渠道说失败"**：`extractPayResult` 解析异常返回 false → 格式不符的**成功回调**被标记支付失败并触发订单取消 | `PaymentController.extractPayResult` | 中 | **已修**：改三态 `parsePayResult`（1/0/-1），UNKNOWN → 返回 "fail" 让渠道重试，**不改支付状态**；删除旧的二态死方法 |
| **退款回调伪造单号**：`extractRefundNo` 解析失败伪造 `REFUND_<ts>` 并回 "success" → 真实退款回调被静默丢弃 | `PaymentController.extractRefundNo` | 中 | **已修**：解析失败返回 null → 端点返回 "fail" 要求渠道重试，不处理 |
| **超时置失败后"迟到成功"被静默丢弃**：支付超时 Job 置 status=2，迟到的成功回调 `status != PENDING → return`，而 reconcile 只扫 status=1 → 用户已付、系统记失败、无退款通道 | `PaymentService.handlePayCallback` | 中高 | **已修**：`success && status==FAIL` → 条件翻转 2→1 → 走 `afterPaySuccess`（订单若已取消 → 既有"业务拒绝自动退款"闭环兜住；订单仍待付款 → 正常支付成功）。**重构**：把成功后的副作用拆成 `afterPaySuccess`（否则 `WHERE status=0` 守卫会吞掉通知与退款——自查发现） |
| 通知补偿把非 503 错误都当"无需补偿" | `PaymentNotifyCompensateJob` | 低 | **已修**：只有 `ORDER_NOT_FOUND(30008)` 这类确定性结论才清计数，其他瞬态错误继续重试 |
| 事件线程池 DiscardPolicy 静默丢弃 | `PaymentService.PAYMENT_EVENT_EXECUTOR` | 低 | **已修**：自定义拒绝处理器（ERROR 日志，排障可查"事件为何缺行"） |
| 登记（低） | `payingKey` 只写不读（真正防重用 statusKey）；跨数据源事务已在第二轮修为专用事务管理器 | — | 登记 |

## 14. 深挖后的 review（2026-09-21，对 inventory/coupon/order/payment 共 12 处改动）
### 自查抓到并修
| # | 问题（我自己引入） | 后果 | 修法 |
|---|---|---|---|
| 1 | 事件序号改 `FOR UPDATE` 锁定读后，appendEvent 的锁序变成"事件锁→订单锁"，而调用方（paySuccess/cancel）是"订单锁→事件锁" → **反向锁序可死锁** | 死锁（MySQL 会回滚一方，但属新增风险） | 改"快路径普通读（无锁）+ **冲突时**才锁定读重算 seq 并重试一次"；仍冲突 → 显式回滚 |
| 2 | 迟到成功修复的第一版：翻转 2→1 后调 `handlePaySuccessInternal`，其守卫是 `WHERE status=0` → 必然早退（通知/自动退款都不执行） | 比原 bug 更隐蔽 | 已在实现中重构出 `afterPaySuccess`（本轮 review 复核其 7 项副作用完整） |
### 兼容性核对（关键，全部通过）
- **mock 回调模拟器直接调用服务方法**（`handlePayCallback/handleRefundCallback`），不走 HTTP 端点 → 三态解析/单号解析改动**不影响 mock 链路**；`scripts/test-09/test-10` 的 payload 均带 `status:SUCCESS` / `refund_no` → 不破坏既有脚本
- order 侧 `pay/create` 传入的 `amount` 就是 `order.getPayAmount()` → 新增金额校验不会拦正常支付
- `inventory:prededuct:*` 前缀下只有 per-order Hash 与 `:index`（已跳过）→ locked 对账扫描口径正确
### 登记（低）
- locked 对账与 L2 事件的瞬态竞态：可能瞬时把 locked 算低（保守方向，下轮自愈，已注释）
- 迟到成功的自动退款失败仅日志（既有 gap）→ 建议后续补"待退款"补偿/告警
- claimed 对账是 per-key 查询（用户×模板量大时偏重；日切低峰执行，生产可改按模板聚合）

### 第二轮 review（对上述 12 处改动的再拷打）
| # | 结论 | 依据/处置 |
|---|---|---|
| 1 | **致命核对通过**：payment→order 的 `OrderFeignClient` 带 `InternalCallFeignConfig`（内部令牌）→ 新增的 `/pay-amount` 金额校验不会 403 | `PaymentService.pay()` 实测路径 |
| 2 | **致命核对通过**：通知补偿有 `MAX_RETRY_COUNT=10` 上限 → 我把"非确定性错误"改为继续重试后不会无限循环（超限 `需人工处理`） | `PaymentNotifyCompensateJob:116` |
| 3 | **自查出更优解并采纳**：seq 冲突 ⇒ 对方也在为同一订单追加 ⇒ 状态乐观锁只允许一个赢家 ⇒ 本事务状态更新必然失败 ⇒ **"冲突直接回滚"就是正确语义**；据此**删除了锁定读重试**（连同 `findLastForUpdate` 死方法）→ 彻底消除"事件锁↔订单锁"反向锁序死锁面，且常规路径零锁 | `OrderEventService.appendEvent`（重试时靠幂等检查跳过，不会无限失败） |
| 4 | 核对通过：`afterPaySuccess` 的 7 项副作用与原实现一致；mock 模拟器进程内直调、测试脚本 payload 兼容；order `pay/create` 的 amount == payAmount | 见 §14 |
| 5 | 补充提示（本轮）：两处对账 SCAN（inventory locked / coupon claimed）在 Redis Cluster 下只覆盖单节点 → 已写入注释（当前单实例+Sentinel 无影响） | 代码注释 |
| 6 | 登记（低）：事件线程池拒绝只打日志无指标；迟到成功自动退款失败仅日志 | 待后续 |

### my-xhs-cart（2026-09-21 深挖）
| 发现 | 证据 | 级别 | 处置 |
|---|---|---|---|
| **CLEAR 屏障无界**：`CLEAR_BARRIERS` 进程内 map 只增不删（活跃用户越多内存越大）；重启/重平衡后丢失 | `CartSyncConsumer` | 中 | **已修**：`putClearBarrier` 两层保护（24h 过期淘汰 + 50k 上限后整体清空并告警）。**注**：ORDERLY 顺序消费已保证同用户事件有序，屏障仅是乱序兜底，丢失安全 |
| **合并购物车逐条串行 Feign**：最多 50 次 × read-timeout 5s → 最坏数百秒阻塞合并 | `CartService.mergeAnonymousCart` | 中 | **已修**：先 `batchGetSkuInfo` 一次批量预取（≤ 购物车 50 条上限，安全），按"在架 + SPU 在架"过滤；批量失败（空结果）回退逐条校验，保持"商品服务异常降级放行"的既有语义 |
| **创建路径 Key 无 TTL**：`cart_add.lua`/`cart_merge_item.lua` 新建 items/checked/sort 时不设 TTL，靠 Java 侧 `refreshTTL` 补（异常被吞 → Key 永不过期） | 两个 Lua | 中 | **已修**：Lua 内加 `EXPIRE`（滑动 TTL，原子且不受 Java 侧刷新失败影响）；其余变更脚本（改量/删除/勾选）仍靠 Java 侧刷新，仅影响"延长"，不产生永久 Key |
| **手动对账单用户端点绕过对账锁** + `watermarkSkipped` 实例字段多线程写 | `CartController:186`、`CartReconcileJob:58` | 低 | **已修**：新增 `reconcileUserWithLock`（复用同一把锁，抢不到抛"已有对账执行中"）；计数改 `AtomicInteger` |
| 加购校验 SKU/SPU 在架 | `CartService.skuExists` | — | 已在早前轮次修复（本轮复核通过） |
| 登记（低） | `/cart/count` 用 `HLEN`（含下架/失效条目，角标与列表口径不一致）；Product 校验 fail-open 为**有意设计**（可用性优先 + valid 标记兜底） | — | 登记 |

### my-xhs-counter + my-xhs-analytics（2026-09-21 深挖）
| 服务 | 发现 | 级别 | 处置 |
|---|---|---|---|
| counter | `getCount` 回填 Redis 用 `set` + `expire` 两条命令：中间崩溃留下**永不过期的计数 key** | 低 | **已修**：改单条 `SET ... EX 30d`（原子） |
| counter | `getCount` 对 Redis 值 `Long.parseLong` 裸奔：脏值直接 500 | 低 | **已修**：脏值降级查 DB 并回填 + warn |
| counter | 去重 TTL 2h vs DLQ 重投（可 >2h 后到达）：迟到的重复事件会**重复计数**；LIKE 有 Set 权威可对账修复，其它计数类型不能 | 低 | **登记**（有意取舍，注释已说明"覆盖 MQ 最大重试窗口"；如需收紧可提到 24h，代价是去重 key 内存 12×） |
| counter | incr-with-dedup Lua 复核：去重键与计数同脚本原子、`EXPIRE` 在 `INCRBY` 之后（注释记录了首次创建前 EXPIRE 无效的回归）、归零保护返回 -1 且不删去重标记 | — | 未发现新问题 |
| analytics | **`syncCountersToCounterModule`（每小时对账 Job 调用）写计数键不带 TTL** → `SET` 清掉 counter 模块维护的 30 天续期 → key 变永久（内存泄漏面） | 中低 | **已修**：写回带 30d TTL；"绝对覆盖"口径仍为登记项 |
| analytics | `LikeService` javadoc 宣称"用户点赞反向索引 `like:user:{userId}:note`"，但**全仓无任何写入/读取** | 低 | **已修**：javadoc 明确标注已移除 + 启用前提 |
| analytics | Follow 列表组装复核：互关状态用 Pipeline 一次往返（非 N+1）；用户存在性校验 fail-closed；分页上下限已 clamp | — | 未发现新问题 |

### 测试套件验证（本轮附带）
- 跑通全套单测发现并修复了**我改动导致的 3 处测试破坏**：`CouponReconcileJob`/`CouponReconcileJobTest`（构造 + 断言更新为定向更新）、`OrderController`(+OrderEventService)/`OrderControllerTest`（构造 + 3 参重载 stub）、`InventoryCompensationJobTest`（构造 + InventoryService）
- **已验证全绿**：common 93 / user 14 / content 9 / analytics 15 / counter 9 / product 14 / coupon 13 / order 59
- **已知 1 个未修失败**：`InventoryServiceTest`（1 例，与 refundRestore 语义/暂停检查相关）——按要求不再投入测试修复，登记待办

### my-xhs-content + my-xhs-home（2026-09-21 深挖）
| 服务 | 发现 | 级别 | 处置 |
|---|---|---|---|
| content | **DFA 建 Trie 不做预处理**（去空格/全角转半角/小写），而文本检测前会做 → 含大写、空格、全角字符的动态词**永远命中不了** | 中 | **已修**：`buildTrie` 对词走同一 `preprocess`（动态词经广播 reload 重建，同样受益） |
| content | `batch-detail` 公开接口**无条数上限**（万级 id 放大 DB/缓存查询） | 中 | **已修**：上限 100，超限截断 + warn |
| content | `deleteNote` 只失效笔记缓存，**未失效 `COMMENT_COUNT`** → 笔记删除后评论数最长 5min 仍返回旧值 | 低 | **已修**：afterCommit 内一并 `delayDoubleDelete(COMMENT_COUNT + noteId)` |
| content | `createComment` 事务内重查被回复评论，**并发删除 → null → NPE（500）** | 中 | **已修**：改为先取对象再判空 |
| content | `shareNote` 只校验 status，**不校验 auditStatus**（与详情/列表口径不一致） | 低 | **已修**：补审核态校验 |
| content | 分页回包用**原 pageNum**（pageNum<=0 时响应页码与实际不符） | 低 | **已修**：回包用 clamp 后的页码 |
| home | 下游全超时/降级时 `cards` 为空但 **hasMore 照常返回 true** → 前端无限翻空页 | 低 | **已修**：空 cards → hasMore=false + nextCursor=null |
| home | `author/relation` future **无异常隔离** → 异常完成时 `getNow` 抛 CompletionException → 500（与"单路失败仅降级"语义不符） | 中 | **已修**：两个 future 体内 try/catch 降级为空 |
| home | 获赞收藏统计 `total` 用 `instanceof Number`，而全局 Long→String 序列化下 total 恒为 String → **上限保护（page<=20）外的分页判断失效** | 中 | **已修**：兼容 Number/String 解析（1000 条上限本身仍为登记项） |
| home | `NoteDeleteConsumer` SCAN `following:latest` **无限速**（key 多时长占 Redis 单线程） | 低 | **已修**：每 500 key 让出 10ms |
| 登记 | content：UNCOMMENT/SHARE/VIEW `asyncSend` 即发即忘无补偿；Feed 重试锁 TTL 在回调前释放；`selectPending` 用应用时钟比较 DB 时间（单机同 TZ 无影响）；home：获赞收藏统计 1000 条上限、`aggregatorPool` 同池嵌套、推模式断点用 ZSet 下标（取关左移会漏推）、大V路径不写 `following:latest`（拉模式已覆盖，属设计取舍） | 低/中 | 待排期 |

#### 追加复核轮（2026-09-21 晚，对上一批修复的自我 review）
| 发现 | 性质 | 处置 |
|---|---|---|
| 分页回包 clamp **补丁不完整**：上一轮只改了 CommentService，NoteService 的 `getUserNotes`/`getMyNotes` 两处回包页码仍是原值 | 漏修（自检发现） | **已补**（×2），并全项目扫描确认无其它残留 |
| 支付回调把渠道**中间态**（Alipay `WAIT_BUYER_PAY`、WeChat `NOTPAY`/`USERPAYING`）落入"明确失败"分支 → 提前终结订单支付态；真实渠道先发中间态通知、成功后发终态 | 语义漏洞（上一轮三态解析的延伸） | **已修**：中间态返回 -1（保持待支付，渠道继续重试） |
| product `SpuService.listSpus` 回包页码 | **误报** | controller 入口已 clamp（pageNum≥1/pageSize≤50），非 bug |
| `FeedService`/`CartAggService` 的 `getNow` 前无异常隔离 | **非 bug** | 故意的"硬失败"通道：`isCompletedExceptionally()` → 抛 `DownstreamUnavailableException` → 干净 503 |
| 其余聚合 future 异常隔离 | **复核通过** | ProductAggService/UserProfileAggService/RecommendService/库存 future 均有 try/catch |
| 回归验证 | — | content 9/9、home 9/9 全绿（含本轮改动） |

## 15. 状态归一（2026-09-23，对 §12"仍未修"清单逐条核代码）

> 背景：§12 清单与 §13/§14 深挖修复记录交叉，状态未刷新，容易漏/误判。本节为**唯一权威状态**。

| 清单项 | 状态 | 证据 |
|---|---|---|
| §1-2 order 0 元单 | **❌ 仍开放** | `PayCreateRequest.amount` 仍 `@Positive`；`OrderController.createPayment` 直传 `payAmount`（可为 0）→ 0 元单发不起支付 |
| §2-1 payment 超时失败资金语义 | ✅ 已修 | §13：迟到成功条件翻转 2→1 + `afterPaySuccess` |
| §3-2 locked 对账 | ✅ 已修 | §13：`reconcileLockedStock` + `updateLockedStockOnly` |
| §3-4 预扣提前 60s 释放 | **❌ 仍开放** | `PreDeductTimeoutJob:101` `cutoffMs = now + 60000` |
| §3-5 getStock 缓存命中仍查 DB | **❌ 仍开放** | `InventoryService.getStock` 命中 Redis 后仍 `selectOne` 取 locked |
| §3-6 管理端点同步全量对账 | ⚠️ 部分缓解 | 已加 `@RateLimit(2/min)` + `isAdminCall`；仍同步全量、无锁、无分页 |
| §3-7 热点检测热路径 +4 RTT | **❌ 仍开放** | `InventoryService:300` preDeduct 内 `recordAndCheck` |
| §3-8 prededuct_idem 无清理 | **❌ 仍开放** | 成功预扣**保留**占位（仅失败删）→ 每单+SKU 永久增长 |
| §3-9 "库存未初始化"错误码 | **❌ 仍开放** | 三处仍用 `PARAM_INVALID`（:283/:287/:365） |
| §3-10 releaseStock 桶号解析 | ✅ 已修 | 第二轮桶号容错 |
| §4-2 coupon claimed 对账 | ✅ 已修 | §13：`reconcileClaimedCounters` |
| §4-3 stuck outbox | ✅ 已修 | §13：`checkStuckOutbox` + 指标 |
| §5 product（库存初始化/N+1/死代码/状态机） | ✅ **已深挖完成**（7 修 + 3 登记） | 见 §13 product 深挖 |
| §6-1/6-2 cart（屏障/批量/对账锁/Lua TTL） | ✅ 已修 | §14 |
| §6 角标口径 HLEN | **❌ 仍开放** | `getCartCount` 用 `opsForHash().size`（含下架/失效条目，与列表口径不一致） |
| §7 counter 竞争与解析 | ✅ 已修 | §14：`SET ... EX` + 脏值降级 |
| §7 去重 TTL 2h vs DLQ 重投 | ❌ 登记（取舍） | 注释已说明覆盖 MQ 最大重试窗口 |
| §8 限速（NoteDelete SCAN） | ✅ 已修 | 本轮：每 500 key 让 10ms |
| §8 线程池嵌套（home） | ✅ 已修 | A3：内层统一 `batchFeignPool`（30/80/500）隔离 |
| §8 DLQ 终态（Feed 推送） | ✅ 已修 | 第二轮：重投上限 10 + `push_status=3` 终态 + 指标/告警 |
| §8 断点游标用 ZSet 下标 | **❌ 登记** | 取关左移会漏推（拉模式覆盖，低） |
| §8 content asyncSend 即发即忘无补偿 | **❌ 登记** | UNCOMMENT/SHARE/VIEW |
| §9 search（重建/死文档/quality_score/sales/ThreadLocal/ip） | **❌ 未深挖** | 待排期（本轮第 5 顺位） |
| §9 im（conversationId 竞态/踢线/丢未读/分页） | ✅ **已深挖完成**（10 修 + 6 登记） | 见 §13 im 深挖 |
| §9 notification（SSE/serverId/对账错值/心跳） | ✅ **已深挖完成**（6 修 + 5 登记） | 见 §13 notification 深挖 |
| §10 gateway（XFF/X-Real-IP/Zone LB/死字段/指标） | ✅ **已深挖完成**（7 修 + 6 登记，含 XFF 家族闭环） | 见 §13 gateway 深挖 |
| §10 user（防爆破 XFF/地址锁/改密/双删/batch 无界/枚举） | ✅ **已深挖完成**（11 修 + 5 登记） | 见 §13 user 深挖；含跨模块 XFF 闭环（gateway/common） |
| §10 common（SentinelBulkhead/fail-open/UserContext/RedisOperator/Lettuce/死代码） | ✅ **已深挖完成**（6 修 + 5 登记） | 见 §13 common 深挖 |

**结论**：真未修 **9 项**（inventory 5 + order 1 + cart 1 + 登记 2），未深挖 **7 模块**（user/im/notification/gateway/search/common/product）。其余 8 项确认已修。

### my-xhs-user（2026-09-23 深挖，五维度）
| 维度 | 发现 | 级别 | 处置 |
|---|---|---|---|
| 微服务/安全 | **登录防爆破信任伪造 XFF**：直接读 XFF 第一段做 IP 维度计数 → 攻击者伪造 5 个假 IP 即可锁任意账号（DoS）/伪造受害者 IP 触发封禁 | 高 | **已修**：优先 X-Real-IP、退化取 XFF **最后一段**（gateway 追加的真实连接 IP 在末尾，伪造段只在前面） |
| 微服务/安全 | **X-Real-IP 单一可信来源缺失**（跨模块）：gateway 只追加 XFF 从不写 X-Real-IP，而 common `RateLimitAspect` 优先读它 → 恒降级 remoteAddr（网关 IP）→ **所有匿名用户共用一个限流桶**；gateway 自身取 XFF 第一段 → 伪造 10.x 可骗压测标记 | 中高 | **已修（一次闭环）**：gateway 写入并**覆盖** X-Real-IP；gateway 自身判定只用 remoteAddr（边缘节点无上游反代，注释写明前提）；RateLimitAspect 统一口径「X-Real-IP → XFF 末段 → remoteAddr」 |
| 分布式 | **地址 4 个写方法锁在 `@Transactional` 内** → 锁先于提交释放 → 并发可产生**两个默认地址**/超 20 条上限 | 中 | **已修**：锁外移 + `TransactionTemplate`（锁覆盖到提交后） |
| 分布式 | **默认地址缓存在事务内写** → 回滚后脏缓存 30min（缓存指向非默认地址） | 中 | **已修**：缓存操作移到提交后（create/update/delete/setDefault 四处，delete 先清后写） |
| 分布式/安全 | **改密凭证吊销：顺序错误 + 异常被吞 + 重复调用**——先改密码再吊销、`invalidateUserCredentials` 吞异常，且与 `revokeAllTokens` 完全重复 → Redis 抖动时"改密成功但旧 access/refresh 最长 7 天仍可用" | 中 | **已修**：新增 `revokeAllTokensStrict`（Redis 故障上抛 → 503，fail-closed），改为**先吊销再改密**；删除重复的死方法 |
| 分布式 | **updateUserInfo 事务内延迟双删** → 提交前删缓存，并发读回填旧值（脏 30min） | 中 | **已修**：`TransactionHook.afterCommit`（common 已有工具首次投入使用） |
| 分布式 | **deleteUser 事务内吊销 + 清缓存** → 回滚时做无谓吊销；提交前清缓存可被回填（删号后信息仍可查） | 中 | **已修**：同样 afterCommit |
| 业务/安全 | **用户名不存在不累计失败计数** → 用户名枚举不受账号/IP 锁定约束 | 中 | **已修**：user-not-found 分支同样计数（IP+账号双维度） |
| 业务 | updateUserInfo 并发改同一手机号 → 唯一键冲突 500（register 有映射、update 没有） | 低 | **已修**：DuplicateKeyException → PHONE_EXISTS |
| 工程/安全 | 验证码获取无任何限流（可无限刷 Redis） | 低 | **已修**：`@RateLimit` 30 次/60s 按 IP |
| 工程 | `/batch/info` 无界 Set（公开接口放大 DB/缓存查询） | 低 | **已修**：上限 100 截断 + warn |
| 登记 | 屏蔽列表仅存 Redis（无持久化）且**未接入任何过滤链路**（全仓无消费者）→ 半成品；`logout` 清理异常被吞（Redis 故障时"注销成功"但凭证仍有效）；登录计数 increment+expire 非原子；`userExists` 不走缓存；deleteUser 不清地址数据 | 低 | 登记 |

**复核**：`t_user` 唯一键仅 username/phone（update 路径只会撞 phone → 映射正确）；`UserApplication` 扫描 `com.myxhs.common`（@RateLimit 切面生效）；user 无 Feign（叶子服务）；测试套件 user 14/14 + common 93/93 全绿（含修 AddressServiceTest 构造注入）。

### my-xhs-im（2026-09-23 深挖，五维度）
| 维度 | 发现 | 级别 | 处置 |
|---|---|---|---|
| 分布式 | **conversationId 并发分配竞态**：双向并发首条消息各分配雪花 ID → 同一对用户两个会话，历史查询只命中其一（一半历史不可见） | 中 | **已修**：用户对粒度 Redisson 锁覆盖"查关系→分配→首条落库"，后者必复用先者 ID |
| 分布式 | **upsertConversation 读改写整行覆盖**：并发消息丢未读（读改写丢更新）；并发已读回执整行 updateById 把 lastMessage 回退成旧快照 | 中 | **已修**：新增定向 SQL（`unread_count = unread_count + ?` 原子自增、只改消息列、is_deleted 复活）；handleRead/markAllRead 改定向清零（`resetUnread`） |
| 分布式 | **并发首条消息撞 `uk_user_peer`** → 消息事务整体回滚（消息丢失 + NACK） | 中 | **已修**：捕获 DuplicateKeyException 降级定向更新；UPDATE 0 行（并发插入方回滚）补插一次 |
| 微服务 | **跨实例无踢线**：用户在两实例各有一条连接，旧连接成僵尸（收不到消息还占资源） | 中 | **已修**：`registerRoute` 返回旧实例 → Pub/Sub 发 KICK(97) → 旧实例关本地连接；路由由既有 Lua 比较删除保护，不误删新路由 |
| 工程/安全 | **WS ticket 可重放**：ticket 只能放 URL（浏览器 WS API 无 Header）→ 进访问日志，5 分钟内可重放建连 | 中低 | **已修**：签发时登记 jti，握手 GETDEL **一次性消费**（subject 与登记值再校验） |
| 工程/安全 | **WS 单帧无上限**：内容截断在 JSON 解析之后，超大帧先打爆堆 | 中低 | **已修**：WS 容器 `maxTextMessageBufferSize/Binary 64KB` |
| 工程 | 离线副本中"DB 已不存在"的 msgId 永远等不到 ACK → 每次重连重复拉取（最长挂 7 天） | 低 | **已修**：推送时清理 DB 缺失副本 |
| 工程 | 2000 字符截断可能切开代理对（emoji 乱码） | 低 | **已修**：高代理位判断回退 1 |
| 工程 | 分页 page 未 clamp（size 已 clamp）→ 响应页码与实际不符 | 低 | **已修**：page 下限 clamp（两处） |
| 文档 | `t_chat_message.conversation_id` 列注释仍是旧哈希方案（P0-B 已改"雪花ID+关系表复用"）→ 误导排障 | 低 | **已修**：init-all.sql（含部署包）+ `sql/migration/im/V1__fix_conversation_id_comment.sql` |
| 登记 | `is_deleted` 无人写 1（无删会话接口，半成品）；Redis 未读与 DB 无对账且 reset 可盖并发 +1；`renewRoute` 两次 EXPIRE 非原子；`allowed-origins: *`（票据一次性已降低风险）；im `@EnableFeignClients` 无 Feign 接口；会话列表可加 (user_id, updated_at) 复合索引 | 低 | 登记 |

**复核**：KICK 通道名与订阅名一致（`myxhs:im:route:{serverId}`）；`getAndDelete` 需 Redis 6.2+（项目既有 CaptchaService 同要求）；前端/测试脚本均"一 ticket 一连接"（一次性消费兼容）；网关白名单含 `/api/im/ws`（握手不经 Bearer 鉴权）；im 无单测（编译 + 交叉验证）。

### my-xhs-notification（2026-09-23 深挖，五维度）
| 维度 | 发现 | 级别 | 处置 |
|---|---|---|---|
| 分布式 | **UnreadReconcileJob 跨批部分计数覆盖**：按通知 id 分页扫描，但**每批各自汇总并 force-set** → 用户未读跨批时，后一批的部分计数覆盖前一批的完整计数（对账自身制造错值） | 中 | **已修**：全局累计所有批次后统一比对修复 |
| 微服务 | **serverId 取 `System.getProperty("server.port")`** → 读不到 Spring 配置恒取默认 19013，同主机多实例 serverId 完全相同（路由值无法区分实例） | 中 | **已修**：`@Value("${server.port}")` 真实端口 + PID 唯一化 |
| 方案/微服务 | **跨实例 SSE 全实例广播**：所有实例订阅同一 Channel、N-1 次无效投递；且"已发布"易被当"已推送"（Pub/Sub 即发即忘，路由 30s TTL 内可能仍指向宕机实例） | 中低 | **已修**：改**定向 Channel**（`…sse:channel:{serverId}`，与 im 路由模式一致）；返回值语义注释澄清（SSE 仅实时加速，列表 API 为权威源） |
| 工程 | **心跳 Redis 续期无异常隔离**：抖动时抛异常 → 本轮"本地死连接清理"被跳过（死连接滞留到 emitter 30min 超时） | 中低 | **已修**：续期 try/catch，清理照常执行 |
| 分布式 | **未读双键 Lua 在 Cluster 下 CROSSSLOT**（`unread:{u}` 与 `unread:type:{u}` 不同 slot） | 低 | **已修**：两键加同一 hash tag `{u:userId}`；顺带把 decrementUnread 的两个脚本合并为一次原子调用（原两次调用间可被并发重置插入） |
| 工程 | 分页 page 未 clamp；合并脚本后 3 个死常量残留 | 低 | **已修** |
| 登记 | markAllRead 的 DB→Redis reset 可吞并发 +1（对账 10min 内收敛）；未读键无 TTL；`forceSetUnread` 非原子（set+del+putAll）；对账只覆盖"有未读行"的用户（Redis 有残留而 DB 无未读的用户不会被清零）；聚合窗口用应用时区与 DB `notify_date` 可能跨日不一致 | 低 | 登记 |

**本轮自查抓到自伤 bug**：键拼接全量替换误伤 helper 本体 → `totalKey` **自递归**（编译通过、运行 StackOverflowError）→ 已修并全仓扫描同类模式（无残留）。
**误报排除**：SSE 并发 send —— javap 验证 Spring 6.1.6 `ResponseBodyEmitter.send(Object, MediaType)` 本身 `synchronized`，无需额外锁。
**注**：未读键改 hash tag 后，存量 Redis 计数键失效（对账 10 分钟内按 DB 重建）；混合版本滚动发布期间跨实例 SSE 定向 Channel 会不匹配（本项目无滚动发布场景）。

### my-xhs-gateway（2026-09-23 深挖，五维度）
| 维度 | 发现 | 级别 | 处置 |
|---|---|---|---|
| 分布式/方案 | **Zone 优先的 zone 取值链断裂**：只读 JVM `-D` 系统属性，而 gateway 不依赖 common（无 ZoneEnvironmentPostProcessor）→ 任何脚本只设 `MYXHS_ZONE` 时该属性从未设置 → zone 恒为 defaultZone，特性静默失效 | 中 | **已修**：四级取值 `Environment → -D → MYXHS_ZONE → defaultZone` |
| 工程/可观测 | **鉴权/HMAC 失败无指标**（只打日志，失败率无法在 Prometheus 侧观测/告警） | 中低 | **已修**：`myxhs_gateway_auth_failures_total{reason}`（missing_header/invalid_token/wrong_type/revoked/redis_unavailable/missing_sub）+ `myxhs_gateway_hmac_failures_total{reason}`（7 类）；配套 2 条告警规则（两份同步） |
| 工程 | `determineHttpStatus` 的 `HttpStatus.resolve()` 对非标准状态码返回 null → 调用方 `status.value()` NPE | 低 | **已修**：null 兜底 500 |
| 工程 | `HmacSignatureFilter.hmacSecretKey` 死字段（改 per-session 密钥后从未使用） | 低 | **已修**：删除 |
| 工程/安全 | 透传客户端 `X-Trace-Id` 无格式校验（超长/异常字符污染日志与下游 sw8 头） | 低 | **已修**：格式白名单（≤64 安全字符），非法则重新生成 |
| 工程 | 敏感 query 脱敏正则**每请求重编译 11 次**；`getClientIp` 两个等价分支 | 低 | **已修**：正则静态预编译；去冗余分支 |
| 配置 | **告警规则两份再次漂移**（根 config 与部署包互有缺失：2 条 XXL 规则 vs 改进版 DLQ 表达式 + XXL health 组） | 中低 | **已修**：并集归一（39 条、零差异、无重名） |
| 登记 | 白名单无 method 维度（抽查证据：白名单条目均指向只读端点或自带 X-Admin-Call/X-Internal-Call 校验 → 纵深防御级）；Gray/ApiVersion 过滤器只写 exchange attribute、无 LoadBalancer 消费（文档已声明设计项）；`GRAY_PERCENT=10` 硬编码；`zone.preference.enabled` 全仓未开启（默认关闭为设计取舍，取值链已修开启即生效）；Sentinel 单机限流（集群需 Token Server）；透传客户端 traceId 属可观测性设计取舍 | 低 | 登记 |

**复核**：XFF 家族（伪造压测标记/不写 X-Real-IP）已在 user 深挖轮闭环（本模块确认落地）；过滤器链 order 核对（Log100→Auth1000→Body1100→染色1200→HMAC1500→限流2500→灰度3000→版本3100）✓；auth 白名单 24 条与 HMAC 白名单 57 条无实质缺口（`/api/coupon/template/list` 被 `/api/coupon/template/**` 覆盖）。

### my-xhs-search（2026-09-23 深挖，五维度）
| 维度 | 发现 | 级别 | 处置 |
|---|---|---|---|
| 方案/分布式 | **全量重建断点不清零**：完成后保留 `lastNoteId/lastSpuId`（且 key 的 1 天 TTL 与次日 4 点调度存在竞态：有时读到陈旧断点、有时已过期）→ 次日"全量重建"退化为增量，历史漏变更永远修不回 | 中 | **已修**：完成时清零断点（失败时保留续传语义不变） |
| 分布式 | **重建无 external version**：重建期间的陈旧读会覆盖并发增量写入的新文档（增量链路已有 Canal ts 外部版本，重建没有） | 中 | **已修**：重建写带 `updated_at` 毫秒外部版本（ExternalGte，与 ts 同域）；**版本冲突视为跳过而非失败**（自查抓到：否则"文档已更新"会被当批次失败 → 整个重建抛异常） |
| 方案 | **死文档不清理**：重建只 upsert → 源表逻辑删除/转非发布的行若错过 Canal 删除事件永久残留 ES（可命中已删内容） | 中 | **已修**：重建尾部按 `deleted=1` 扫描清理笔记/商品死文档 + 建议索引清理"已删除或非已发布"词条（幂等，失败不阻塞完成标记） |
| 业务/推荐 | **quality_score 永不刷新**：特征提取只选"无特征记录"的笔记，且 upsert 不含 quality_score → 质量分冻结在首次计算 | 中 | **已修**：选择条件增加"有新行为（行为时间 > 特征更新时间）"；upsert 刷新 quality_score + like/comment 计数（表内已有列此前恒为 0）；显式 `updated_at=NOW()` 防值未变时每轮重复选中 |
| 业务/契约 | **`sort=sales` 静默乱序**：全链路无销量数据源（t_spu 无 sales 列、订单分库分表聚合代价高），文档从不含该字段 → 按缺失字段排序 | 中 | **已修（显式降级）**：WARN + 降级 relevance；DTO 注释同步（销量口径列为设计项） |
| 分布式 | **t_sku 批量 binlog 只取 `data[0].spu_id`** → 一次事件改多 SKU/多 SPU 时其余父 SPU 不重建索引 | 中 | **已修**：遍历全部行、去重后逐个重建 |
| 业务/安全 | **热搜匿名维度用 "unknown"**：SearchController 读原始 XFF、缺失即 "unknown" → 全体匿名用户共用一个防刷/限频桶（热搜少计 + 正常用户被误限） | 中 | **已修**：新增 common `ClientIpResolver`（统一可信口径 X-Real-IP → XFF 末段 → remoteAddr）；search 三处 + user 登录复用（去重私有实现） |
| 工程 | **ThreadLocal 不 remove**（`currentNoteId`）→ 线程复用时残留上一 noteId，后续消息异常会把陈旧 id 记入补偿集合 | 中低 | **已修**：finally remove |
| 工程 | `legacyNoteDocument`/`buildProductDocument` 死方法 | 低 | **已修**：删除 |
| 登记 | GEO 召回依赖 `t_item_feature.geo_hash` 但全仓无写入方（无位置数据源）→ 该路恒空；建议索引 weight 恒 1（无热度权重）；suggest 重建部分失败仅 warn 不阻断；`sales` 真正落地需销量口径（订单聚合/计数器） | 低 | 登记 |

**验证**：common 93/93、user 14/14、search 6/6 全绿；全量编译通过。

### my-xhs-common（2026-09-23 深挖，五维度）
| 维度 | 发现 | 级别 | 处置 |
|---|---|---|---|
| 方案/配置 | **`SentinelBulkheadConfig` 伪配置**：`System.setProperty("csp.sentinel.bulkhead.*")` 并非 Sentinel 配置项（Sentinel 无开箱 Feign 线程池舱壁）→ 承诺的隔离从未生效 | 中 | **已修**：删除伪配置，注释改为诚实描述（实际隔离 = Sentinel 流控/降级规则 + Feign 关重试/超时 + 最小连接 LB） |
| 工程/可观测 | **幂等/限流切面 fail-open 无指标**：Redis 故障期间"幂等失效/限流退化"只在日志里 | 中 | **已修**：`myxhs_common_fail_open_total{component=idempotent\|rate_limit,reason}` + 1 条 Prometheus 告警 |
| 工程/配置 | **Lettuce 指标恒空**：声明了 `ClientResources`（MicrometerCommandLatencyRecorder）但手写工厂不引用；**标准池键被忽略**（yml 的 `lettuce.pool.max-active:25` 等实际用默认 8）；`spring.data.redis.timeout=2s` 被 `replaceAll` 解析成 **2ms** | 中 | **已修**：两个手写工厂均接线 `ClientResources`；按标准键装配 `GenericObjectPoolConfig`；统一 `DurationStyle` 解析（两处） |
| 分布式/工程 | **`RedisOperator` 命令超时被吞**（与类注释"连接不可用抛 RedisUnavailableException"矛盾）→ 调用方无法感知故障 | 中 | **已修**：超时归入连接失败；配套 `GlobalExceptionHandler` 新增 `RedisUnavailableException → 503`（可重试语义，避免落 500）。**行为变更已登记**：Redis 超时由"静默降级"变"fail-closed 503/缓存回退" |
| 工程 | 死代码 6 个类共 **431 行**：`IdempotentMessageAspect`+`@IdempotentMessage`、`DegradeSwitchManager`、`DomainEventPublisher`、`BizSpanHelper`、`DynamicConfigRefresher`（全仓 0 引用，全量编译验证） | 低 | **已修**：删除 |
| 工程 | `BusinessMetrics` 用 JDK 内部 `jdk.internal.vm.annotation.Contended`，而部署未开 `-XX:-RestrictContended` → 实为惰性且依赖内部 API | 低 | **已修**：移除 |
| 登记 | Feign 线程池舱壁未实现（设计项）；`UserContext`（TransmittableThreadLocal）未包 `TtlExecutors` → @Async 链路取不到上下文（且全仓 0 业务引用，属预留能力）；优雅停机 Nacos 注销走反射 + 固定等待（可配，默认 10s）；业务异常 HTTP 200 信封（前端依赖，设计取舍）；`RedisOperator` 非连接异常（序列化等）仍吞（符合类注释） | 低 | 登记 |

**验证**：全量编译通过；测试 **common 93 / user 14 / content 9 / analytics 15 / counter 9 / product 14 / coupon 13 / order 59 / cart 13 / payment 5 / search 6 / home 9 全绿**（顺带补齐早前改动未同步的 3 处断言：counter getCount TTL、cart Lua 第 6 参数、payment 金额校验 stub；inventory 1 例为已知登记失败）。

### my-xhs-product（2026-09-23 深挖，五维度）
| 维度 | 发现 | 级别 | 处置 |
|---|---|---|---|
| 方案/工程 | **延迟双删把 `sleep(1000)` 塞进通用线程池**：队列满触发 CallerRunsPolicy 时由**请求线程睡 1s**（更新接口被延迟双删拖慢） | 中低 | **已修**：专用单线程 `ScheduledExecutorService` 到点执行（不占请求线程/池内线程，@PreDestroy 关停） |
| 微服务/契约 | **建 SKU 不初始化 inventory** → 新 SKU 下单直接"库存未初始化"失败 | 中低 | **已修**：新增 `InventoryFeignClient`（+内部令牌配置 + `@EnableFeignClients`），提交后自动初始化（"已初始化"视为成功；失败 ERROR + `myxhs_product_inventory_init_fail_total`，管理端 `/api/inventory/init` 可重试）；inventory `/init` 端点接受内部令牌（原来只认 X-Admin-Call） |
| 业务/边界 | **建 SKU 要求 SPU 已上架**，与新增的"上架需有 SKU"校验互锁 → 无法"先建 SKU 再上架" | 中低 | **已修**：放宽为"SPU 存在即可" |
| 业务/状态机 | 上架无 SKU 校验（上架即"空商品"：可被搜到但无法下单） | 低 | **已修**：上架前校验至少一个上架中 SKU |
| 业务/边界 | `originalPrice` 未校验 ≥ price；`specs` 未校验合法 JSON/长度（DDL 为 JSON 语义列） | 低 | **已修**：划线价校验 + JSON 解析校验 + DTO 长度上限（1024） |
| 工程/N+1 | 同批 spuIds 两次 `selectBatchIds`（首图 + 状态） | 低 | **已修**：一次查询构建两张 Map |
| 工程/死代码 | `toSkuVO` 两个重载无调用 | 低 | **已修**：删除（`resolveSpuImage` 仍在 getSkuDetail 使用，已保留——自查曾误删并即时恢复） |
| 登记 | SPU 下架不校验在途订单（在途订单已占用库存/已定价，属设计取舍）；`SPU_ASYNC_EXECUTOR` 的布隆加载/异步刷新在队列满时仍走 CallerRuns（背景任务，影响可控）；SKU `stock` 冗余字段与 inventory 事实源并存（代码注释已说明） | 低 | 登记 |

**验证**：product 14/14 全绿（含修 SkuServiceTest 构造注入）；inventory 15 中 1 例为已知登记失败。

## 16. 收官批次（2026-09-23，登记项按优先级再清一批，共 17 项）

| # | 模块 | 项 | 处置 |
|---|---|---|---|
| 1 | payment/order | **0 元单发不起支付**（`@Positive` 拒 0 → 只能等超时关单） | **已修**：`DecimalMin(0)` 放行 + 支付域零元直接记账成功（渠道不可调，tradeNo=ZERO_AMOUNT）；同步修复 mock 同步成功/零元路径返回 VO 状态滞后 |
| 2 | inventory | **预扣提前 60s 释放**（窗口内支付 confirm 找不到记录） | **已修**：Lua 记录物理 TTL = 到期 + 10 分钟缓冲；超时任务只释放"真正到期"（不再抢在 TTL 前） |
| 3 | inventory | **预扣幂等占位永久保留**（表线性增长） | **已修**：对账任务清理 7 天前占位（`deleteStalePredeductIdem`，每轮 2000 行） |
| 4 | inventory | 管理端点与 XXL 调度可重叠全量对账 | **已修**：`doReconcile` 加 Redisson 锁（抢不到跳过本轮） |
| 5 | inventory | `getStock` 缓存命中仍每请求查 MySQL 取 locked | **已修**：locked 5s 短缓存（展示字段允许秒级滞后，变化路径不主动失效） |
| 6 | inventory | "库存未初始化"用 `PARAM_INVALID` | **已修**：新增语义码 `SKU_STOCK_NOT_INITIALIZED(30018)`（3 处） |
| 7 | im | `is_deleted` 无人写 1（无删会话接口） | **已修**：`DELETE /api/im/conversations/{peerId}` 软删（对方发言自动复活）+ 未读清零 |
| 8 | im | 会话列表缺复合索引 → filesort | **已修**：`(user_id, updated_at)` 索引（init-all×2 + 迁移 `im/V2`） |
| 9 | im | `renewRoute` 两次 EXPIRE 非原子 | **已修**：键加同 hash tag `{u:userId}` + 单脚本原子续期（Cluster 同 slot；90s TTL 格式自愈） |
| 10 | im | 空 `@EnableFeignClients`；`allowed-origins: *` | **已修**：删空注解；Origin 改 `IM_ALLOWED_ORIGINS` 可配（生产收紧指引） |
| 11 | notification | 未读键无 TTL（永久堆积） | **已修**：原子脚本内 `EXPIRE 30d`（活跃续期；过期后由 10 分钟对账按 DB 重建） |
| 12 | notification | 对账覆盖缺口："Redis 有残留、DB 无未读"的用户永不修复 | **已修**：SCAN 未读键（hash tag 前缀）逐户核对 DB，为 0 即清零（限速） |
| 13 | search | 建议索引 weight 恒 1（completion 排序无意义） | **已修**：weight 用点赞数 log10 缩放（1~101） |
| 14 | search | suggest 部分失败仅 warn → 静默残缺 | **已修**：与笔记/商品一致，部分失败抛错（断点不推进、下次重试） |
| 15 | common | `UserContext`（TransmittableThreadLocal）在 @Async 中取不到 | **已修**：`taskExecutor` 用 `TtlExecutors` 包装 |
| 16 | gateway | `GRAY_PERCENT` 硬编码 | **已修**：`myxhs.gray.percent` 可配（0=关闭自动灰度） |
| 17 | im | 跨实例未读无对账 | 登记（im 无调度基建；Redis/DB 漂移由 reset/ACK 路径主导，量级小） |

**验证**：全量编译通过；测试 **common 93 / user 14 / content 9 / analytics 15 / counter 9 / product 14 / coupon 13 / order 59 / cart 15 / payment 5 / search 6 / home 9 / im/notification/gateway 无单测** 全绿；inventory 仅剩 1 例已知登记失败（按要求不投入）。**另修 4 处因本批次改动失配的测试断言**（payment 状态断言、inventory 脚本 stub ×3）。

## 17. 收口（2026-09-23）

- **深挖覆盖**：15 服务 + common 全部完成五维度深挖（§13 七段），§15 状态归一后未深挖项为 0。
- **修复总量**：深挖轮 56 项 + 收官批次 17 项 + 复核轮修正（含 6 个自查自伤 bug）= 本台账 §0/§12/§13/§14/§16 全部可追溯。
- **工程验证**：全量编译通过；测试套件 **276 例**（surefire 报告口径），仅 `InventoryServiceTest.refundRestore_multiSkuUsesIndependentIdempotentKeys` 1 例**已知登记失败**（用户指定不投入）；SQL（init-all×2）/告警规则（40 条×2）/迁移脚本均已同步。
- **剩余登记**：均为设计项或需外部数据源/真机演练（Feign 线程池舱壁、GEO 召回数据源、销量口径、屏蔽接入过滤链路、Zone 多活与混沌演练等），见各 §13 末尾"登记"行。
- **交付物**：`docs/reports/per-service-review-20260921.md`（本文件）、`docs/reports/production-readiness-backlog-20260923.md`（接入清单）、`docs/interview-defense-handbook.md`（面试防御手册，29 主题 + 23 长文）、**`docs/interview-question-forecast.md`（面试提问预测）**、**`docs/interview-mock-transcript.md`（模拟面试逐字稿 + 90 秒故事库）**、**`docs/interview-deep-grilling.md`（第四层拷打：一致性陷阱/白板题/反事实题）**、**`docs/resume-v3.md`（简历唯一维护版**，旧版 resume-final/full/full-v2/2page* 自 2026-09-26 冻结不再维护）。

## 18. order 深度 review（2026-09-23，面试兜底 × 实现评估双视角）

**面试地图（已进防御手册 X/X′）**：状态表 vs 事件表的分工、条件 UPDATE 单一赢家、序号冲突"宁可回滚不抢锁"、T-110/T-123 两个坑、三条关单路径 + 补偿三件 + pseudoOrderId、回放校准与方向保护。

| # | 发现 | 级别 | 处置 |
|---|---|---|---|
| 1 | 补偿兜底集合**无 TTL**、坏成员每分钟全量重放刷日志 | 中 | **已修**：写入侧 EXPIRE 24h + 重放每轮上限 20 条 |
| 2 | 状态收敛失败抛 `IllegalStateException` → 并发取消/发货用户看到 **500**（应 4xx） | 中 | **已修**：改 `BizException(ORDER_STATUS_ERROR, "订单状态已变化，请刷新后重试")` |
| 3 | `cancelOrder` **不发状态通知**（发货有、取消没有，用户端无感知） | 低中 | **已修**：对称补 `publishStatusChanged("订单已取消")` |
| 4 | `*_at` 里程碑时间在事件事务外补写（崩溃窗口） | 低 | 登记：校准的"补齐里程碑时间"可自愈 |
| 5 | `checkLocalTransaction` 缺 userId 时 `order_no` 全库扫描兜底 | 低 | 登记（生产者必带 header，兜底路径保留） |
| 6 | 快路径注释仍写"锁定读重算 seq 重试"（该设计已删） | 低 | **已修**：注释同步为"冲突整体回滚让调用方重试" |

**验证**：order 59/59 全绿；全量编译通过。

### order 第二轮（生产视角深拷打，2026-09-23）

| # | 发现 | 级别 | 处置 |
|---|---|---|---|
| 1 | **映射补录 Job 生产不可用**：每轮 `while(true)` 从 id=0 扫到全表 + **逐行** `selectByOrderNo`（N+1）；订单量上来后每 5 分钟一次全表扫 + 百万级查询 | **高（生产阻塞）** | **已修**：Redis 滚动游标（扫到尾回绕，历史缺口仍持续兜底）+ 每轮上限 10 页（2000 单）+ 批量 `IN` 查已有映射 + 查询列收窄为 id/user_id/order_no |
| 2 | 超时扫描 `selectTimeoutOrders` 无分片键跨 16 片扫描 | 低（可接受） | 复核通过：`idx_status_created` 使过滤高效、结果集小、游标分页 ✓（注释"全表扫"与实现不符，未改代码） |
| 3 | 本地消息重试 `ORDER BY next_retry_time` 无匹配索引（(status,created_at) 不覆盖排序） | 低 | 登记：消息在稳态近空（存活秒级）；若上量再加 (status,next_retry_time)（需 64 张物理表 ALTER，故不轻动） |
| 4 | 下单幂等语义为"重复即拒绝"（SETNX），非"重复返回原单" | 低中 | 登记（接入清单）：推荐改为 SETNX 存 orderId（PENDING→orderId 两段写），重复请求**回放原响应** |
| 5 | 支付结果回写把"竞态"与"瞬态失败"混在 false 里（只打日志） | 低 | **已修（本轮）**：新增 `orders.pay_result.total{reason}`（race / transient_event_failed / transient_status_lost / not_found）+ 5 个分支打点 |
| 5b | 下单幂等"拒绝"vs"回放"语义 | 低中 | **设计项（本轮拍板不改）**：现语义可辩护且更简单；面试谈资：回放=SETNX 存 PENDING→orderId 两段写 |
| 5c | 本地消息排序索引 | 低 | **设计项（触发条件）**：消息积压或表过万行再做（64 张物理表 ALTER，风险>收益） |

**验证**：order 59/59 全绿（含同步 2 处测试契约）；全量编译通过。

## 19. coupon 深度 review（2026-09-23）

**面试地图（已进手册 Y/Y′）**：四层防超发、发送失败"回滚+作废成对"、-3 自愈与语义错误码、退券≠退库存（RV30）、对账双权威源。

| # | 发现 | 级别 | 处置 |
|---|---|---|---|
| 1 | **旧语义"退券 Redis 补偿链"是颗雷**：消费者在、生产者零调用；其 `repairReturnCouponRedis` 会 INCR stock/DECR claimed，与现行 RV30 语义（退券不碰 Redis）直接冲突；一旦被触发（手工投递/误接线/陈旧消息）→ 超发敞口 + 与每日对账打架 | **中高（潜在地雷）** | **已删除**：生产者方法 + repair 方法 + 嵌套 record + topic 常量 + 消费者 + 其测试 + OutboxSender 重放段（保留下单失败回滚使用的 return_coupon.lua） |
| 2 | 兜底集合无 TTL/无重放上限（随发现 1 一并消失） | — | 随链删除 |
| 3 | 热点券 stock 单键（未分桶） | 低 | 登记（接入清单）：与库存域同款分桶设计 |
| 4 | claimed 键无 TTL；claimed 对账 per-key N+1 | 低 | 登记（日切低峰可接受；量大改按模板聚合） |

**复核通过**：领券原子 Lua（同 slot hash tag）、Outbox 失败"回滚+作废"成对、双层幂等、useCoupon 乐观锁+校验链、管理端点鉴权、模板缓存 30min+状态变更失效、ExpireJob 批量 UPDATE、退券的乐观锁幂等。

**验证**：coupon 11/11 全绿（删除 1 个死测试）；全量编译通过。

### coupon 轮 review（2026-09-23，对删除动作与手册新题的复核）

| # | 发现 | 级别 | 处置 |
|---|---|---|---|
| 1 | 删除退券补偿链后 **OutboxSender 两个字段变死字段**（couponService / stringRedisTemplate） | 低 | **已清**（含 import） |
| 2 | **部署包 topic 初始化脚本**仍含已废 `COUPON_RETURN_REDIS_REPAIR_TOPIC` | 低 | **已清**（init-rocketmq-topics.sh） |
| 3 | 母版简历残留"（退券 Redis 补偿通道：消费端已实现、生产端未接线）"——该链已整链删除 | 低 | **已修**：改为"退券语义：只恢复用户券状态，不回补库存、不减限领计数" |
| 4 | 简历业务 topic 数 17 → 实测唯一 topic **15** | 低 | **已修**（母版 + 本地实测口径） |
| 5 | **发现平行简历 `resume-full-v2.md`（9-24 修改，本人迭代版）**：含同口径失实"回调验签""支付宝/微信/Mock" | 中 | **已修正 2 处事实**（与母版同口径）；其余（AI 工程效能/AI 项目）按"先不用管"未动 |
| 6 | 手册 Y 的"恢复为未使用"表述 | — | **复核通过**：`returnCoupon` SQL 确为 `SET status=0, used_order_id=NULL`，与 DDL 状态码（0/1/2）一致 |

**验证**：coupon 11/11 全绿；全量编译通过；删链接口的其它引用面（Java/配置/脚本/文档）零残留（仅存档快照 `config/production-env-config/*` 保留旧记录，未动）。

## 20. cart 深度 review（2026-09-23）

**面试地图（已进手册 Z/Z′）**：三结构 + 7 Lua、为什么 Redis 为权威、TOCTOU/T-107/清空并发三坑、顺序三层防线、对账"防双份全丢"。

| # | 发现 | 级别 | 处置 |
|---|---|---|---|
| 1 | **`t_cart_event` append-only 无保留策略**（无限增长）且无 `created_at` 索引（将来清理会全表扫） | 中 | **已修**：索引（init-all×2 + 迁移 `cart/V1`）+ 对账任务按 30 天/5000 行分批清理 |
| 2 | `refreshTTL` 3 次独立 EXPIRE（部分失败混合 TTL） | 低 | 登记（仅影响"延长"，add/merge 已在 Lua 内原子续期）；如收紧可改同 slot 单脚本 |
| 3 | 角标 `HLEN` 含失效条目（与列表口径不一致） | 低 | 登记（既有取舍） |
| 4 | 事件流水无 msg_id 唯一键（Sink 消费者 ORDERLY + 消费组内去重，重复风险低） | 低 | 登记 |

**复核通过**：7 个 Lua 的原子性与同 slot、TOCTOU 修复、T-107 标志位语义、merge take-max、CLEAR 屏障有界化、对账"itemsKey 不存在只跳过不删"守卫、表索引（uk_user_sku）。

**验证**：cart 15/15 全绿；全量编译通过。

### cart 轮 review + 跨目录一致性（2026-09-23）

| # | 发现 | 级别 | 处置 |
|---|---|---|---|
| 1 | 我的 cart 清理**吞吐不足**：任务每天 4 点一次（单批 5000）→ 追不上日增量，表仍会增长 | 中 | **已修**：改有界排空（最多 20 批 × 5000/日，触顶打 WARN） |
| 2 | **order 本地消息表同样无清理**：`status=1` 成功行一单一行、16 分片永久累积 | 中 | **已修**：Mapper 加 `deleteSentBefore`（走 `idx_status_created`）+ 重试任务内"每日一次 SETNX 标记 + 有界排空"，7 天保留 |
| 3 | 迁移目录双向漂移：根缺 `cart/V1`（部署包独有）、部署包缺 7 个（content V3 / order V2 / payment V1 / inventory V2 / im V1+V2 / cart V2）；同名文件内容级 diff | 中 | **已修**：双向同步 + **递归内容 diff 零差异**；我的 cart 迁移改名 V2 防撞版本号 |
| 4 | `init-all.sql` 两份漂移 14 行（action 注释 / uk_msg_id / 空行 / 一条历史注释） | 中 | **已修**：统一为最准版本（含 `CHECK` 词与 CLEAR 哨兵说明）→ **零差异** |
| 5 | `cart_check_all.lua` 注释笔误（`myxhs:myxhs:cart:`） | 低 | **已修** |
| 6 | CLEAR 屏障上限 50k | — | 复核通过（与手册口径一致） |

**验证**：order 59/59 + cart 15/15 全绿；全量编译通过；SQL 两份零差异（init-all + migration 递归）。

### 保留策略横扫（2026-09-23 第二轮 review）

| 表 | 判定 | 处置 |
|---|---|---|
| `t_user_behavior` | **全系统第一增长风险**（每次浏览/点赞一行、无清理）；读侧只用 7 天/24h | **已修**：90 天保留（日切闸门 + 有界排空 100 万行/日，挂热池任务，已具 `idx_created_at`） |
| `t_cart_event` | 已修（上轮） | 排空吞吐补强（10 万/日 + 触顶 WARN） |
| `t_local_message` | 已修（上轮） | 7 天 + 每日 SETNX + 有界排空 |
| `t_order_event` | 业务真相源，不可删 | 归档（冷表）保留在接入清单（触发：单表过亿） |
| `t_payment_event` | **资金审计**，不可删 | 保留；如需要按冷归档（触发：过亿） |
| `t_chat_message` | 业务数据（用户要历史） | 保留；产品级保留期决策 |
| `t_notification` | 业务数据（列表可查） | 保留；可设 180~365 天（触发：存储告警） |
| `t_compensation_message` | 运维数据（仅失败落库） | 可设 90 天（触发：过千万） |

**可见性**：三处保留清理的失败日志 warn → **error**（长期静默失败必须能在错误日志/告警看到）。

**文档同步**：`DEPLOY-NOTES` 新增"2026-09-23 升级补充"（5 个迁移 + 无新 XXL 任务说明 + 行为表保留）；`数据库表结构.md` 补 cart `created_at` 索引标记。

## 21. search 深度 review（2026-09-23）

**面试地图（已进手册 AA/AA′）**：双通道 + 外部版本 + tombstone + 重建对账四件套；search_after 与 size+1；热搜分钟桶 + 反作弊 Lua；建议/历史有界。

| # | 复核项 | 结论 |
|---|---|---|
| 1 | search_after 深分页（note/product 双路径） | ✅ 真实实现（sort 值游标 + size+1 判 hasMore） |
| 2 | ES 客户端超时（5s connect / 30s socket，可配） | ✅ |
| 3 | 搜索历史有界（trim + TTL） | ✅ |
| 4 | 热搜反作弊（单 Lua：屏蔽词 + IP/用户限频 + 写桶 + TTL） | ✅ |
| 5 | 热点池 RENAME 同 slot（{global} hash tag） | ✅ 上轮修复 |
| 6 | 行为表 90 天保留（idx_created_at 已具） | ✅ 本轮新增 |

**登记（设计项，含触发条件）**：单节点 ES `replicas=0`（生产 ≥1）；重建期 `refresh_interval` 调优（摊薄写放大，需配套恢复保护）；suggest 无增量同步（只随全量重建）；GEO 召回无 `geo_hash` 写入方（策略恒空）；推荐精排为规则非 ML；行为上报无 msgId 去重（重复上报会放大推荐计数）。

**文档卫生**：`resume-bullet-pool.md` 的"索引重建 + 别名切换"表述已过时（无别名机制）→ 同步修正。

### search 第二轮（2026-09-23，对 AA 轮再拷打）

| # | 发现 | 级别 | 处置 |
|---|---|---|---|
| 1 | **`ItemCF` 相似度键 RENAME 在 Cluster 下必失败**：键为 `recommend:itemcf:<noteId>` + `:tmp`（每笔记一个键，百万级），两键不同 slot → 每次刷新 RENAME 报错 → **相似度全空** | **高（集群化即失效）** | **已修**：新增 `itemCfKey(noteId)` 带 `{item:noteId}` hash tag（job + 两个读取方同步） |
| 2 | **热搜实时榜同款雷**：`search:hot:realtime` + `:tmp` RENAME 两键不同 slot | 中高 | **已修**：常量加 `{realtime}` hash tag（读写共用常量；1h TTL 自愈） |
| 3 | **热搜快照每 1 分钟写 top-N**（≈7 万行/天、2600 万行/年）且无保留策略 | 中 | **已修**：30 天保留（日切闸门 + 有界排空，`idx_snapshot_time` 已具）+ 重算间隔改可配（生产建议 5 分钟） |
| 4 | `IndexInitializer` 只 create-if-not-exists，**mapping 升级静默不生效** | 低 | 登记（接入清单）：mapping 变更需 putMapping 或 reindex |
| 5 | NoteSearchService 结果组装无 N+1（hit 直接取 _source） | — | 复核通过 |
| 6 | AA′ 手册题的事实核对（版本号/tombstone/断点/清理/search_after） | — | 复核通过 |

**全仓 RENAME 用法清账**：recommend:hot（首轮修 ✓）、itemcf（本轮修 ✓）、search:hot:realtime（本轮修 ✓）——现已无跨 slot RENAME。

## 22. notification 深度 review（2026-09-23）

**面试地图（已进手册 AC/AC′）**：聚合窗口 + 唯一键对齐、未读"DB 门控 Redis"、对账全局累计、SSE 三件套（ticket/定向 Channel/心跳）、实时与可靠分离。

| # | 复核项 | 结论 |
|---|---|---|
| 1 | 测试控制器暴露风险 | ✅ 双重保护（`@Profile("dev")` + X-Internal-Call） |
| 2 | 列表查询索引 | ✅ `(user_id, created_at DESC)` + `(user_id,type,is_read)` + `uk_aggregate` |
| 3 | 已读链路原子性 | ✅ DB 条件更新（WHERE is_read=0）门控 Redis 递减 |
| 4 | 未读 Lua：双键同 slot + 30d TTL + 不为负 | ✅ 本会话修复 |
| 5 | 对账：全局累计 + 残留键清理 | ✅ 本会话修复 |
| 6 | 聚合唯一键与 DB 对齐（防 Redis 丢锁重复） | ✅ |
| 7 | 订单通知定向刷新内容（状态滞后） | ✅ |

**登记（含触发条件）**：markAllRead 归零吞并发 +1（对账收敛）；聚合窗口时区边界；`Last-Event-ID` 断线回放（设计项）；通知表保留期建议 180~365 天（触发：存储告警）。

**验证**：notification 无单测；全量编译通过。

## 23. content 深度 review（2026-09-23）

**面试地图（已进手册 AD/AD′）**：发布 Outbox、DFA 两个坑（预处理同源/热更新 copy-on-write）、计数事件即发即忘的边界、评论/删除联动。

| # | 复核项 | 结论 |
|---|---|---|
| 1 | 发布原子性 | ✅ `t_note` + `t_local_message` 同事务 + FeedMessageRetryJob 补发 |
| 2 | DFA 热更新并发 | ✅ copy-on-write（volatile 发布完整 trie），读侧无撕裂 |
| 3 | 评论列表组装 | ✅ 无 N+1（senderName 仅创建时取一次） |
| 4 | 评论计数 | ✅ batchCountByParentIds 批量精确 + COMMENT_COUNT 双删 |
| 5 | 删除联动（tombstone + following:latest 限速 + DLB 终态） | ✅ 前几轮修复 |
| 6 | 审核通道 | ❌ **无审核工作流端点**（auditStatus 靠管理端/DB）→ 登记 |

**验证**：content 9/9 全绿；全量编译通过。

## 24. 售后分摊 / 结算 深度 review（2026-09-23）

**面试地图（已进手册 AE/AE′）**：最大余数法 + 不变量、退满补差、在途占额度、退款单号归属、结算差异收敛。

| # | 复核项 | 结论 |
|---|---|---|
| 1 | DiscountAllocator：分为单位整数运算 + 余数补分 + ID 平局 | ✅（含"余分未分配"的保守守卫） |
| 2 | `refundableAmount` 部分数量下整 | ✅ 永不超退 |
| 3 | **"本次退满"补差闭合** | ✅ `resolveRefundable`：已退+本次≥数量 → 整件可退 − 已占用 |
| 4 | 额度"已占用"含在途（`status IN (3,4)` = 退款中/已完成） | ✅ 防跨类型并发双退（保守方向） |
| 5 | 退款失败(6)释放额度、恢复任务核对事实（含单号归属） | ✅ |
| 6 | 结算差异：人工已处理不被覆盖 / 消失收敛 / 重现复位 / 未到账跳过 | ✅ |

**登记**：手续费单档；分摊不落库（算法变更的历史口径影响需评估）。

**验证**：order 59/59 + payment 5/5 全绿；全量编译通过。

## 25. home 聚合 深度 review（2026-09-23，面试题收官）

**面试地图（已进手册 AF/AF′）**：预算制分层超时（4s：一层 800ms + 二层剩余）、双池隔离（A3 饥饿修复）、单路降级 vs 购物车硬失败、空页 hasMore 收敛。

| # | 复核项 | 结论 |
|---|---|---|
| 1 | 分层超时是"剩余预算"式 | ✅ `max(500, global(4s) − elapsed)`（note/product）；Feed 固定 3s |
| 2 | 双池隔离与拒绝策略 | ✅ aggregatorPool 20/50/200 + batchFeignPool 30/80/500，CallerRuns 降级 |
| 3 | 单路降级/异常隔离/空页收敛 | ✅ 前几轮修复 |
| 4 | 事实表"4s→3s"表述 | **勘误**：一层 800ms（非 3s），3s 是 Feed 的 allOf |

**收官**：至此**简历全部条目均有对应面试题**（手册 29 主题 + 23 长文；仅剩"内容审核工作流"等内容侧设计项在接入清单）。剩余登记（1000 条上限/推模式 cursor/大V latest）均有触发条件。

**验证**：home 9/9 全绿；全量编译通过。

## 26. 剩余项 A 批清零（2026-09-23，"能做的全做"）

| # | 项 | 处置 |
|---|---|---|
| 1 | cart TTL 刷新非原子（3 次 EXPIRE） | **已修**：同 slot 单 Lua 原子刷新（三键带 `{userId}`） |
| 2 | 通知表无保留策略（且缺 `created_at` 索引，清理会全表扫） | **已修**：180 天保留 + 日切闸门 + 有界排空；**补 `idx_created_at`**（init-all×2 + 迁移 `notification/V1`） |
| 3 | 行为上报重复投递撞主键 → 3 次重试进 DLQ（毒丸） | **已修**：消费者 `INSERT IGNORE` 幂等跳过；生产端降级写库复用同一 eventId（歧义场景零重复） |
| 4 | 内容审核无入口（公开列表已滤 APPROVED → 新笔记永远不可见） | **已修**：管理端审核端点（X-Admin-Call，通过/驳回 + 详情缓存失效） |
| 5 | 订单状态机"隐式"（合法性散在各守卫） | **已修**：显式转移白名单（集中校验，拒绝 0→2/0→3 类非法流转；含补偿重放放宽） |
| 6 | 校准只有手动端点（无自动化） | **已修**：`orderEventCalibrationJob` 每日干跑（(status,created_at) 索引 + 时间窗 + 限页），差异只告警 + 指标 `orders.calibration.total`；XXL 注册两份同步 |
| 7 | 库存热点检测 4 次 RTT 在预扣热路径 | **已修**：降采样 1/5（可配 `inventory.hot.detect-sample-rate`，含除零防护） |
| 8 | 库存 outbox / 补偿表无保留策略 | **已修**：outbox 7 天、补偿 90 天（日切闸门 + 有界排空，复用既有索引） |

**验证**：全量编译通过；测试全绿（inventory 仅剩 1 例已知登记失败）；init-all 与 migration 目录两份零差异。

**B 类（需外部条件，不空转）**：payment 真渠道六项（无真实渠道 → 验签 SPI 是空壳）、ES replicas/refresh（需多节点）、GEO 数据源（需位置业务）。
**C 类（已拍板不做）**：幂等回放语义（用户决定）、本地消息排序索引（稳态近空 + 64 表 ALTER）。


## 27. 分布式与业务语义深度拷打（2026-09-27）

> **独立文档**：`docs/reports/distributed-semantics-grilling-20260927.md`
> 内容：7 套一致性机制全景 + 11 个域逐个拷打（订单/库存/支付/关单/购物车/营销/内容/搜索/IM通知/对账/锁与缓存）
> + P0 预扣失败收口缺失 + P1 TCC 空壳/"Redis 为 truth"前提未文档化 + 面试"能打/不能吹"清单 + 待核实 7 项。
> 与本文档关系：§0-§26 记"**改了什么**"（修复台账），grilling 文档记"**语义怎么想、还差什么**"（讨论与拷打）。
> 下一轮拷打对象（已登记）：配置热更新（Nacos 刷新旧值）、灰度发布、时钟/分布式 ID、分片扩容。


## 28. P0 库存失败收口 v1（2026-09-27，"失败事件 + 订单侧收口"）

> 背景：`distributed-semantics-grilling-20260927.md` §2.2 P0——预扣/确认失败只 log 等对账，
> 订单已提交（甚至已支付）但库存未锁 → 发货超卖风险。

**改动**
1. 公共指标：`BusinessMetrics` 增 `inventory.failure.total{reason}` / `order.inventory_failure.total{result}`；
2. inventory：预扣重试耗尽、确认失败 → 发 `INVENTORY_FAILED_TOPIC`（syncSend 3s，尽力而为+失败指标）；仅 PRE_DEDUCT/CONFIRM（资金相关），RELEASE/REFUND_RESTORE 不发（对账可修）；
3. order：新消费者 `InventoryFailureConsumer`（映射表解析 userId）→ `OrderService.handleInventoryFailure`：
   待付款=自动取消（释放其他已成功 SKU、退券、通知用户）；已付款=ERROR+指标（人工，v2 自动退款）；
   状态机新增 `ORDER_INVENTORY_FAILED_CANCELLED`（目标 4，ALLOWED_FROM={0}）；
4. 告警：`OrderInventoryFailurePaid`（P1，`increase(order_inventory_failure_total{result="manual_required_paid"}[10m])>0`）——live+部署包两份零差异；
5. 部署：`init-rocketmq-topics.sh` 增 topic（幂等重跑）；DEPLOY-NOTES 已记录；
6. Runbook：`docs/reports/inventory-failure-closure-rule-20260927.md`（三态策略/指标/值班 SOP/v1 局限）。

**验证**：`mvn -q -DskipTests -T 1C compile` 全绿；告警两份 diff 零差异。
**遗留（v2）**：已付款自动退款、发货守卫（履约前校验锁定态）、映射缺失边界。


## 29. P2 重试放大治理（2026-09-27，第三轮拷打收口）

> 背景：`distributed-semantics-grilling-20260927.md` §7"全链路重试放大总账"。

**改动**
1. **Producer 重试显式化**：11 个服务 `rocketmq.producer` 增 `retry-times-when-send-failed/-async: 2`（此前为隐式默认 2，仅补记账）；search 同步补 `send-message-timeout: 3000`；
2. **Outbox 补发退避**：coupon/inventory 增 `retry_count`/`next_retry_time`（迁移 `coupon/V1__outbox_retry.sql`、`inventory/V3__outbox_retry.sql`；`init-all`×2 + `sql/migration` 双向零差异）；Job 失败写回 30s→480s 指数退避 + ±20% 抖动，查询侧按 `next_retry_time` 过滤；
3. **统一抖动**：新增 `common/mq/RetryBackoffUtils`；本地消息表（30s→480s）、inventory 消费（50/100ms）、计数 Buffer（100ms×i）、通知聚合（50<<n）、CacheHelper（100/50ms）接入；
4. **部署**：DEPLOY-NOTES 增两条迁移说明（已有环境按序执行）。

**验证**：`mvn -q -DskipTests -T 1C compile` 全绿；迁移目录双向零差异。
**剩余（P3）**：Feed 补偿退避 + analytics 批量化、DLQ 重放 runbook。


## 30. P3 清理（2026-09-27，第三轮遗留收口）

1. **Feed 推送补偿退避**：`t_local_message` 增 `push_next_retry_time`（迁移 `content/V4__feed_push_retry.sql`；`init-all`×2 同步）；Job 重投后写回 **60s→600s 指数退避+±20% 抖动**，SQL 侧按到期过滤（消除固定 60s 反复重投与扫描饥饿）；
2. **死代码清理**：`FeedPushConsumer` 移除未使用的 `AnalyticsFeignClient` 字段（fan-out 实为 Redis ZSet 分页 + Pipeline，循环内零 Feign）；
3. **勘误**：第三轮"400 次×Feign"表述已修正（grilling §7.2/§7.3）；
4. **DLQ 重放 runbook**：`docs/reports/dlq-replay-runbook-20260927.md`（先看后动/先修后放/幂等/限速四原则 + mqadmin 定位命令 + 幂等核对表 + 季度演练）。

**验证**：`mvn -q -DskipTests -T 1C compile` 全绿；迁移目录双向零差异。


## 31. 第四轮拷打：发布中断与在途请求（2026-09-27，发现 1 真缺陷）

> 全文：`distributed-semantics-grilling-20260927.md` §8。

**发现**
1. 🔴 **P1 真缺陷**：`GracefulShutdownListener.shutdownExecutors()` 在 web 排空**之前**执行（ContextClosedEvent 先于 lifecycle stop，字节码核实）→ 发布窗口内依赖线程池的请求会 5xx；
2. 监听器注释顺序写反（实际"先摘除后停服"）；
3. 10s 传播等待/单实例窗口"15-20s"/IM 路由 90s 陈旧窗口——待演练实测或优化（见 §8.3 修复清单）。

**状态**：**P1 已修（2026-09-27）**——新增 `common/shutdown/ExecutorShutdownProcessor`（`@PreDestroy` 阶段关闭线程池），`GracefulShutdownListener` 移除提前关闭 + 注释更正（先摘流→web 排空→destroy 关池）；compile 全绿。P2/P3 待办：发布演练实测、IM 实例级路由注销、双实例发布切换、XXL misfire 盘点。


## 32. 第五轮拷打：缓存失效时序（2026-09-27）

> 全文：`distributed-semantics-grilling-20260927.md` §9。

**发现**
1. 失效可靠性三档不统一：CacheHelper（重试+MQ 兜底）｜product 自研（仅 log）｜inventory 回声保护（不删）；
2. **幽灵缓存**：order `myxhs:order:info:*` 9 处删除、全仓无写入 → 死代码；
3. **兜底单点**：`CACHE_EVICT_TOPIC` 只有 user 一个消费者组（content 兜底依赖它）；
4. inventory 消费者 javadoc 与实现漂移（旧行为描述）。

**修复（2026-09-27 已完成）**：
1. ✅ product 失效兜底：`evictSpuCache` 失败发 `CACHE_EVICT_TOPIC` + product 补 RocketMQ producer（此前无 MQ）；
2. ✅ 兜底单点：product/content 各补 `CacheEvictConsumer`（独立消费组，删除幂等）；
3. ✅ inventory javadoc 更正（回声保护 + T-066 溯源）；
4. ✅ 缓存台账建成：`docs/reports/cache-invalidation-ledger-20260927.md`；
5. ⏸️ order:info 幽灵键：**维持观察**（已有登记 T-031/T-066 同族，不清理）。

**验证**：compile 全绿。
