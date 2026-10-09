# 生产化接入清单（逐服务扫描产出，2026-09-23）

> 用途：记录逐服务扫描中发现的"当前为 Mock/未接线/单档，接真实生产前必做"的事项。
> 规则：**只记录，不写进简历**（简历保持已发生事实口径）；实现与否按优先级另行排期。
> 关联：面试防御手册对应主题的"诚实边界/上生产我会补的三件事"章节。

## payment（资金链路）

| # | 事项 | 现状证据 | 生产影响 | 方案（能上生产的最小集） |
|---|---|---|---|---|
| 1 | **渠道回调验签** | 控制器无 verify，仅 `X-Internal-Call` 内部可达 | 伪造回调即可改支付状态 | `ChannelSignVerifier` SPI：Alipay RSA2 / WeChat V3 + 平台证书轮换 + fail-closed + 原始报文 Hash 留存 |
| 2 | **超时前置渠道查询 + 关单** | `queryPayStatus` 定义但 0 调用；无 close API | "本地失败渠道已收钱"仅靠迟到成功事后兜底 | 超时任务置失败前先查询；真实渠道补 `close()`（防"已关单却付款成功"） |
| 3 | **回调端点公网化配套** | 现仅内部可达 | 无对账举证与纠纷依据 | 渠道 IP 白名单 + 独立限流桶 + 原始报文留存 90 天 + 幂等键（paymentNo+渠道状态） |
| 4 | **金额单位映射** | Mock 用 `BigDecimal` 元 | 真实渠道（分）直接错 100 倍 | 渠道适配层做元/分转换 + 单测（接入清单第一条） |
| 5 | **手续费分级** | 结算域单档计提 | 渠道×品类多费率错算 | 费率表（渠道+品类+账期）+ 生效时间版本 |

## order（订单状态机与事件溯源）

| # | 事项 | 现状证据 | 生产影响 | 方案（最小集） |
|---|---|---|---|---|
| 1 | ~~显式状态机转移表~~ | — | — | ✅ **已完成**：`ALLOWED_FROM` 白名单集中校验（OrderEventService.appendEvent） |
| 2 | **事件表按月归档** | `t_order_event_*` 只增不清理 | 长期单表过亿，回放/审计查询劣化 | 冷热分表 + `order_id` 路由回放；归档任务按月切 |
| 3 | ~~校准自动化~~ | — | — | ✅ **已完成**：`orderEventCalibrationJob` 每日干跑 + `orders.calibration.total` 指标 + XXL 注册 |

## order（第二轮补充）

| # | 事项 | 现状证据 | 生产影响 | 方案（最小集） |
|---|---|---|---|---|
| 4 | **下单幂等改"回放"语义** | `setIfAbsent(key,"1")`，重复请求直接拒绝 | 客户端超时重试拿到"重复提交"错误，体验差且易误判 | SETNX 存 `PENDING` → 事务提交后写 orderId；重复请求：PENDING→"处理中"、orderId→按原单返回（回放） |
| 5 | 本地消息重试加排序索引 | `(status, created_at)` 不覆盖 `next_retry_time` 排序 | 稳态无影响（表近空）；积压时 filesort | 上量后再加 `(status, next_retry_time)`（16 分片 × 4 库需脚本化 ALTER） |
| 5b | ~~本地消息表无清理~~ | `status=1` 成功行永久保留 | 一单一行 ×16 分片线性增长 | ✅ **已完成**：`deleteSentBefore`（7 天，走 idx_status_created）+ 每日 SETNX 标记 + 有界排空 |
| 6 | ~~支付结果消费的"false"语义细分~~ | — | — | ✅ **已完成**：`orders.pay_result.total{reason}`（race/transient_event_failed/transient_status_lost/not_found） |
| 7 | 下单幂等"回放"语义 | `setIfAbsent(key,"1")` 重复即拒 | 客户端重试拿到"重复提交"错误 | 拍板**不改**（现语义可辩护、更简单）；若改：SETNX 存 PENDING → 提交后写 orderId，重复请求回放原单 |

## coupon（优惠券）

| # | 事项 | 现状证据 | 生产影响 | 方案（最小集）/ 触发条件 |
|---|---|---|---|---|
| 1 | 热点券 stock **单键**（未分桶） | `myxhs:coupon:{templateId}:stock` 单 String | 秒杀级单券流量会把单键打到 Redis 单核上限 | 与库存域同款：N 桶 + 邻桶借用（不足再触发异步补桶）。**触发**：单券峰值 > 1w QPS |
| 2 | claimed 键**无 TTL** | `claimed:{userId}` 永久 | key 空间按 用户×模板 增长（有上界，非按请求泄漏） | 评估后保留（TTL 会让限领失效，对账虽可重建但有窗口）；**触发**：单模板领取用户 > 100w 或内存告警 |
| 3 | claimed 对账 **per-key 查询**（N+1） | SCAN 后逐键 `countIssued` | 日切任务偏重 | 按模板聚合 + 批量 IN。**触发**：claimed 键 > 10w 或对账耗时 > 10min |

## cart（购物车）

| # | 事项 | 现状证据 | 生产影响 | 方案/触发条件 |
|---|---|---|---|---|
| 1 | ~~TTL 刷新非原子~~ | — | — | ✅ **已完成**：同 slot 单 Lua 原子刷新 |
| 2 | 角标口径 HLEN 含失效 | `getCartCount` | 角标数与列表不一致（UX，非资损） | 若产品要求精确：维护 valid 计数（写路径同步）；**默认不动** |
| 3 | 事件流水无 msg_id 唯一键 | `t_cart_event` | Sink 重复消费会产生重复流水行（ORDERLY+3 次重试内概率低） | 加 `uk_msg_id`。**触发**：流水重复排查出现 |

## search（搜索/索引）

| # | 事项 | 现状证据 | 生产影响 | 方案/触发条件 |
|---|---|---|---|---|
| 1 | ES 单节点 `replicas=0` | IndexInitializer 默认 0（可配） | 节点故障即无检索（无副本可提升） | 生产 ≥1 副本（配置项已具备）。**触发**：部署多节点 ES 时立即设 |
| 2 | 重建期 `refresh_interval` 未调优 | 用默认 1s，bulk 写期间段合并开销大 | 重建拖慢写入并挤占检索资源 | 重建前设 30s/-1、完成后恢复（**必须带恢复保护**，失败会永久拖慢可见性）。**触发**：重建耗时超窗口或写入告警 |
| 3 | suggest 无增量同步 | 仅全量重建写 suggest_index | 新笔记建议词最长滞后到次日重建 | 增量消费里同步 completion 文档。**触发**：产品要求建议实时性 |
| 3b | ~~热搜快照无保留策略~~ | 分钟级写 top-N（≈7 万行/天） | 千万级表 | ✅ **已完成**：30 天保留 + 间隔可配（`search.hot.recompute-interval-ms`） |
| 3c | mapping 升级静默不生效 | `IndexInitializer` 仅 create-if-not-exists | 加字段后检索不到（以为生效） | 需要时 putMapping（加字段安全）或 reindex（改类型）。**触发**：映射变更时 |
| 4 | GEO 召回无数据源 | `t_item_feature.geo_hash` 无写入方 | 该路召回恒空（策略仍被调用） | 接入位置数据（笔记发帖定位）或从召回链摘除。**触发**：明确位置业务 |
| 5 | ~~行为上报无去重~~ | — | — | ✅ **已完成**：INSERT IGNORE + 复用 eventId（重投幂等、降级零重复） |

## notification（通知）

| # | 事项 | 现状证据 | 生产影响 | 方案/触发条件 |
|---|---|---|---|---|
| 1 | `Last-Event-ID` 断线回放未实现 | SSE 无 event id 语义 | 断线窗口内的实时提醒丢失（列表仍可查） | 事件带 id + 客户端重连携带 + 服务端补发。**触发**：产品要求推送级可靠 |
| 2 | 归零可吞并发 +1 | `markAllRead` 先 DB 后 Redis set 0 | 未读偶发少 1（对账 10 分钟收敛） | Redis 归零与前次 INCR 用同一 Lua 版本判定。**默认不动**（窗口小） |
| 3 | ~~通知表保留期~~ | — | — | ✅ **已完成**：180 天 + `idx_created_at`（迁移 notification/V1） |

## content（内容）

| # | 事项 | 现状证据 | 生产影响 | 方案/触发条件 |
|---|---|---|---|---|
| 1 | `auditStatus` 审核 | — | — | ✅ **最小闭环已完成**：管理端审核端点（通过/驳回 + 缓存失效）；任务队列/通知回调仍为可选增强 |
| 2 | 计数事件即发即忘无补偿 | SHARE/VIEW/UNCOMMENT 直接 asyncSend | 丢事件只靠 counter 对账收敛 | 复用 Outbox（发布已在用）。**触发**：计数漂移排查频繁 |
| 3 | t_note 组合索引 | 单列 idx_status/idx_created_at | 大表 "status+audit+created" 列表会有 filesort | 加 `(status, audit_status, created_at)`。**触发**：公开列表慢查询 |

## 数据的保留与归档（全库横扫结论）

| 表 | 现状 | 决策 | 触发条件 |
|---|---|---|---|
| `t_order_event` | 只增不删（真相源） | **冷归档**（按月切冷表 + 查询路由） | 单表 > 1 亿行或回放/审计查询 > 1s |
| `t_payment_event` | 只增不删（资金审计） | 保留；必要时冷归档 | 单表 > 1 亿行 |
| `t_chat_message` | 只增不删（业务数据） | 保留（产品级保留期决策） | 存储告警或产品要求 |
| `t_notification` | 只增不删（业务数据） | 可设 180~365 天保留 | 存储告警 |
| `t_inventory_outbox` / `t_inventory_compensation`（原文"t_compensation_message"为幻影表名，已修正） | 只增不删 | ✅ **已完成**：outbox 7 天 / 补偿 90 天（日切 + 有界排空） | — |

## 待扫服务
- [ ] coupon / cart（超发防线 / 7 Lua / CLEAR 屏障）
- [ ] search（ES 同步与索引治理）
- [ ] content / home（DFA / Feed / 聚合）
- [ ] inventory（三级扣减细节复核）
- [ ] im / notification（长连接与通知聚合）
- [ ] gateway / user / analytics / counter / common
