# my-xhs 全项目深度 Review（fresh，独立于旧文档）

> 2026-08-11 | 逐模块手把手复核（15 模块），聚焦：业务逻辑 / 工程 / 分布式 / 微服务 / 日志 / 监控 / 全链路。
> 每模块详细笔记见 review-<module>.md（/data/tmp/opencode/）。

---

## 一、结论速览

- **整体工程成熟度中等偏上**：Lua 原子化、Outbox、对账、幂等、事件溯源、分桶、版本化索引等分布式范式普遍使用到位。
- **但存在 1 个资损级缺陷 + 2 个高危正确性缺陷 + 1 个高危一致性缺陷**，需优先处理。
- 监控/全链路基础框架完整，但存在 O1(userId 缺失) / O2(异步丢 traceId) 两处横切缺口。

---

## 二、P0 / 高危（资损 / 数据串台 / 超卖）

### P0-A [资损·高] 优惠券下单从不核销 → 可无限复用（Coupon）
- `OrderService` 只调 `getCouponDiscount`（算折扣）与 `returnCoupon`，**从不调用 `useCoupon`**；
  `CouponFeignClient.useCoupon` 与 `CouponController /api/coupon/use` 均为**死代码**。
- 后果：下单用券后 `user_coupon.status` 仍为 0、`used_order_id` 为空 → 同一张券可反复下单每单都减额 → 大额资损。
- `returnCoupon` 依赖 `WHERE status=1 AND used_order_id=?`，因从未 markUsed 而恒为 no-op。
- **修复**：下单创建订单时（事务消息本地事务内）调用 `useCoupon` 核销并绑定 orderId。

### P0-B [高·正确性] IM 会话 ID 哈希碰撞 → 聊天串台（IM）
- `generateConversationId = min*31 + max`（ChatService.java:446）非单射，已计算验证存在大量碰撞
  （如 (1,34)→65 与 (2,3)→65）。conversationId 同时作 DB 持久化键与历史查询键 →
  不同用户对共享同一会话历史/未读数。
- **修复**：改用不碰撞编码（如 `min<<20|max` 并校验 max<2^20）或落库会话表分配唯一 ID。

### P0-C [高·正确性] Feed 收件箱读取参数颠倒 → 普通用户推流恒空（Home）
- `reverseRangeByScoreWithScores(inboxKey, minScore, 0, ...)`（FeedService.java:81-83）：max=0 使区间为空，
  score 为正数时间戳 → 收件箱恒空，普通用户 Feed 只靠大V发件箱。
- **修复**：`(inboxKey, 0, lastScore, 0, size)`。

### P0-D [高·一致性] 本地消息表补发 = 每单必然重复投递（Order）
- 事务消息 Commit 后，`t_local_message(status=0)` 状态不被联动置成功；`LocalMessageRetryJob` 每 30s 补发 →
  每单 ORDER_TRANSACTION 投递 2 次。靠下游幂等兜底不超卖，但**每单重复消费**是确定浪费。
- **修复**：事务提交后 markSuccess；或事务消息/本地表二选一。

---

## 三、P1 / 高危（微服务 / 资金 / 一致性）

### P1-1 [高·资金] 已取消订单可支付、竞态无退款（Order + Payment）
- `onPaymentSuccess` 捕获乐观锁冲突（取消/关单先赢）仅 return false（OrderService.java:642-647），**不发退款**。
- Payment `pay()` 不校验订单存在/待付款 → 已取消订单仍可创建支付单。
- **修复**：支付成功但订单非待付款 → 触发幂等退款；payment 下单前回查订单状态与金额。

### P1-2 [高·资金/一致性] 补偿消费者忽略 action，统一走 closeTimeoutOrder → 已支付订单库存泄漏（Order）
- `OrderCompensationConsumer` 对 RELEASE_STOCK/RETURN_COUPON 一律调 `closeTimeoutOrder`，
  其只处理 status=0 → 已支付订单的释放库存补偿被跳过 → 库存永久泄漏。
- **修复**：按 action 分发到 releaseInventory/returnCouponIfUsed，并对非待付款订单仍执行释放。

### P1-3 [高·微服务] 服务端口信任 X-User-Id + X-Internal-Call 无统一强制（系统性）
- 下游 controller 直接信任 `@RequestHeader("X-User-Id")`；X-Internal-Call 仅 Feign 客户端添加，
  服务端逐端点手工 `token.equals(v)`（非恒定时间，易漏配）。
- 整体安全依赖"服务端口防火墙封闭"这一前置假设；一旦 19001+ 可达即可伪造身份/越权。
- **修复**：统一 InternalCall 过滤器 + 端口级网络安全兜底 + 恒定时间比较。

### P1-4 [高·一致性] ES 补偿版本域与 Canal 版本域不一致（Search）
- Canal 消费端用 `es`（小整数），补偿 job 用 `currentTimeMillis()`（~1.7e12）作 ExternalGte 版本。
- 补偿后该文档 version 巨大 → 后续 Canal 增量更新被 ES 拒绝 → 索引停在补偿快照直到全量重建。
- **修复**：统一版本域（补偿沿用 binlog/es 版本）。

### P1-5 [高·一致性] 搜索增量补偿跨库查询缺库名前缀（Search）
- `queryNotesByIds`/`queryProductsByIds` 裸查 `t_note`/`t_spu`，未带物理库前缀（笔记/商品分属不同库）。

---

## 四、P2 / 中危（性能 / 可扩展 / 正确性）

| # | 模块 | 问题 |
|---|------|------|
| 1 | Product | `batchGetSkuDetails` 对每个 SKU 单独 `selectById` 查 SPU 取图 → N+1；应 collect distinct spuId 一次 IN |
| 2 | Product | `getSpuDetail` 不过滤下架状态；布隆过滤器只 add 不 remove → 下架/删除商品仍可查 |
| 3 | Home | Feed 逐条并行调 content/user 详情（20+ 次下游/页）无批量接口无缓存 → QPS 放大 |
| 4 | Payment | `checkPaymentTimeout` 用 `keys()` 全量扫描（阻塞 Redis）；成功支付写永久无 TTL status key（内存无界） |
| 5 | Payment | 真实第三方回调端点要求 X-Internal-Call（第三方无 token 会被拒）+ 无渠道签名验签（mock 实现，上生产需改造） |
| 6 | Counter | 计数 Redis key 永久无 TTL → 内存无界 |
| 7 | Cart | "Redis 丢失后以 MySQL 恢复"注释与实际不符：读路径只读 Redis，对账单向 Redis→MySQL，MySQL 备份不可读恢复 |
| 8 | Cart | 对账以 MySQL userId 集为枚举源，纯 Redis 新用户永不补录 |
| 9 | User | 登录锁定可被用于账号 DoS（5 次错密码锁 15 分钟，无 IP 维度） |
| 10 | Gateway | 压测标记 IP 校验可被伪造（用客户端可控 X-Forwarded-For 而非 remoteAddress）；HMAC 不签名 body（P0-6a）|
| 11 | Gateway | per-user HMAC secret 读取无 try/catch → Redis 故障 500；引号剥离 hack 脆弱 |
| 12 | Order | pseudoOrderId=fold-hash(orderNo) 作库存幂等键，跨单碰撞风险 + orderNo 在 Redis 故障时降级随机可能撞号 |
| 13 | Content | NOTE_LIST_USER 缓存只删不填（读路径无回填）→ 死缓存键 |
| 14 | Notification | 聚合窗口实为"当天剩余"，与注释"5min"不符；模板 `{title}`/`{content}` 占位符语义错乱 |
| 15 | User | login/register `@Transactional` 包裹 BCrypt 计算 + Redis 操作，长占 DB 连接 |

---

## 五、日志 / 监控 / 全链路（横切）

- **基础框架完整**：6 维 TraceContext 经 HTTP + MQ + Feign 透传；ApiMetricsFilter URI 归一化 + 百分位；
  BusinessMetrics/DlqMetrics/MyBatisMetrics 埋点齐全。
- **O1 [中] MDC 未写 userId**（TraceIdConfig.java:84 只写 traceId）→ 按用户排查全链路日志困难。
- **O2 [中] 异步线程池 MDC 支持不一致**：home 用 MdcAwareExecutorService（标准模板）；
  product/inventory 裸 `new Thread`、order/cart 用 commonPool 的 `CompletableFuture.runAsync` → 异步链路丢 traceId。
- **Gateway(WebFlux) 无等效 HTTP 指标**（ApiMetricsFilter 仅 Servlet 生效）。

---

## 六、做得好的（可作范本）
- Inventory：分桶 + Lua 原子预扣 + 三级一致性 + Outbox + 动态扩容 + msgId 幂等/失败 removeMark 重试。
- Counter/Analytics：Lua 原子去重 + Set 化 like 计数（抗乱序）+ 归零保护 + 双向对账。
- Cart：Lua 三结构原子化 + 事件时间戳乱序保护 + 对账防"双份全丢"。
- Order：事务消息 + 事件溯源 + 乐观锁状态机 + 幂等下单。
- Home：双层 MdcAware 线程池隔离 + 超时降级。

---

## 六·附、与既往文档对齐 + 真实性确认（2026-08-11）

> 本 fresh review 独立复跑，多数问题与 REVIEW-V2 / FIX-PLAN-V2-FULL 一致；并新发现 2 个**此前未记录**的高危项。
> 关键项均已实测复核（见下）。

| 本次编号 | 既往文档 | 是否已知 | 确认方式 |
|:--:|------|:--:|------|
| P0-A 券核销从不调用(useCoupon死代码) | **无**（此前只评"useCoupon非幂等"，假设其被调用）| **新** | 全仓 grep：`useCoupon` 仅定义，无任何调用方 |
| P0-B IM 会话ID碰撞(min*31+max) | **无** | **新** | 实测：ID 1..3000 顺序下 **440 万对**不同用户对同 conversationId |
| P0-C Feed 收件箱参数颠倒 | REVIEW-V2 七·H01 | 已知 | Redis `reverseRangeByScoreWithScores(key,min,max,...)` 实参 (minScore,0)→空区间 |
| P0-D 本地消息表每单重复投递 | P0-8 | 已知 | 代码：事务提交后本地消息未标 status=1，RetryJob 30s 必重发 |
| P1-1 已取消可支付/竞态无退款 | P0-2 | 已知 | 代码 onPaymentSuccess 捕获乐观锁冲突仅 return false |
| P1-2 补偿忽略 action→库存泄漏 | P0-1 | 已知 | 代码一律走 closeTimeoutOrder，status!=0 跳过 |
| P1-3 X-User-Id/Internal-Call 信任边界 | X1/X2、P0-7 | 已知 | — |
| P1-4 ES 版本域混用 | REVIEW-V2 八 | 已知 | 补偿用 currentTimeMillis vs Canal 用 es |
| P1-5 补偿漏跨库前缀 | P0-5 | 已知 | — |
| HMAC 不签 body / Redis 故障 500 | P0-6a / P0-6b | 已知 | — |
| O1 MDC userId 缺失 / O2 异步丢 traceId | 运维 O1/O2 | 已知 | — |

**结论**：整体对齐（独立复跑互相印证）；**新增 2 个高危项 P0-A(资损)、P0-B(串台)**，未在既往清单中，
建议纳入修复优先级前列（尤以 P0-A 券核销为资损最高）。

---

## 七、建议修复顺序

1. **P0-A 券核销**（资损）→ **P0-B IM 会话碰撞**（串台）→ **P0-C Feed 收件箱**（核心功能失效）
2. **P1-1 支付退款竞态**、**P1-2 补偿分发**（资金/库存）
3. **P1-3 端口信任模型**（安全）、**P1-4/5 ES 版本/跨库**（一致性）
4. P2 清单逐项 + **O1/O2 日志横切补齐**

### ✅ 修复进度
| 项 | 状态 |
|:--:|:--:|
| P0-A 券核销 | ✅ 已修复+验证（2026-08-11，order 已重启） |
| P0-B IM 会话ID碰撞 | ✅ 已修复+验证（2026-08-11，im 已重启） |
| P0-C Feed 收件箱 | ✅ 已修复+验证（2026-08-11，home 已重启） |
| P1-2 补偿忽略 action | ✅ 已修复+验证（2026-08-11，order 已重启） |
| P1-1 支付退款竞态 | ✅ 已修复+验证（2026-08-11，order+payment 已重启） |
| P1-4 ES 版本域混用 | ✅ 已修复+验证（2026-08-11，search 已重启；含补偿 Map.of/bulk 既有缺陷） |
| P1-5 补偿漏跨库前缀 | ✅ 已修复+验证（2026-08-11，search 已重启） |
| P1-3 端口信任模型 | ✅ 已修复+验证（2026-08-11，全服务重打包重启；GatewayAuthTrustFilter + 各服务 jwt.secret） |
| O1 MDC userId | ✅ 已修复+验证（2026-08-11，TraceIdConfig/MqTraceHelper 写 userId 到 MDC） |
| O2 异步线程池 traceId | ✅ 已修复+验证（2026-08-11，MdcAwareExecutorService 应用于 product/inventory/order/cart） |
| P2-1 product SKU N+1 | ✅ 已修复+验证（2026-08-11，product 已重启） |
| P2-2 product 下架详情可见 | ✅ 已修复+验证（2026-08-11，product 已重启） |
| P2-9 登录锁定账号DoS | ✅ 已修复+验证（2026-08-11，user 已重启；IP 维度 + 多IP才锁账号） |
| P2-13 NOTE_LIST_USER 死缓存键 | ✅ 已修复+验证（2026-08-11，content 已重启） |
| P2-14 Notification 聚合窗口注释 | ✅ 已修复+验证（2026-08-11，notification 已重启；注释勘误：占位符非bug） |
| **生产配置 Review** | ✅ 已出报告（2026-08-12）：`review-production-config.md` — Nacos无鉴权/从库宕机/告警指标名失效/VM空转/Kibana不可用 等 P-D1~P-D12 |
| **SkyWalking 全链路深审** | ✅（2026-08-12）：P-T1 线程池/ForkJoin 插件未启用→异步链路断链、P-T2 agent9.6vsOAP9.7、P-T3 telemetry未接入、P-T4 全采样、P-T5 gRPC杂散请求；修复方案已入 FIX-PLAN-PRODUCTION-CONFIG |
| **生产配置二轮深挖** | ✅（2026-08-12）：修正 M-1 iptables实为收紧(P-D1暴露面下调)、M-2 P-D8撤销；新增 P-D13无Alertmanager/告警无出口、P-D14 ES日志无ILM无限增长、P-D15 mysql-slave内存97.5%濒危、P-D16 Kibana随机密钥、P-D17 残留topic与镜像、P-D18 404序列多 |
| **生产配置三轮深挖** | ✅（2026-08-12）：P-D20 xxl-job调度严重错配（19任务仅~8可调度，order关单/本地消息/库存对账/券过期/购物车对账/feed清理/推荐计算全失效）、P-D19 MySQL无备份、P-D21 Redis HA名义化(单sentinel) |
| **生产配置四轮深挖** | ✅（2026-08-12）：P-D22 t_inventory_compensation schema漂移→库存补偿机制全失效、P-D23 8/9全服务Redisson日志风暴9GB/天(已修复,跨机部署sentinel下发127.0.0.1)、P-D24 /logs无清理14GB、P-D25 脏表、时区混用 |
| **生产配置五轮深挖** | ✅（2026-08-12）：P-D26 从库relay-log未固化(主机名变更断复制)、P-D27 Kibana monitoring指向不可解析地址、从库8-9曾尝试CHANGE SOURCE但当前仍不在运行(P-D2强化)、Nacos鉴权三度确认关闭 |
| **生产配置六轮深挖** | ✅（2026-08-12）：P-D28 微服务机iptables空+actuator loggers可远程改日志级别(实测204)、P-D29 MySQL慢查询11次、Nacos明文JWT/HMAC secret |
| **业务链路×配置影响矩阵** | ✅（2026-08-12）：结合业务分析——order无@Scheduled兜底(关单失效最痛)/库存补偿SQL全错/支付搜索通知链路健全/推荐购物车feed降级；业务优先级重排 |
| P1-2 补偿忽略 action | ✅ 已修复+验证（2026-08-11，order 已重启） |
