# 对账覆盖矩阵 + 数据生命周期审计（2026-09-20）

> 方向 ③：把"有对账"升级为"覆盖矩阵 + 缺口清单"，并盘点 ES/日志/releases 的真实保留行为。

## 一、对账覆盖矩阵

| 状态对 | 权威源 | 检查/修复 | 覆盖 |
|---|---|---|---|
| 计数（Redis↔DB） | Redis 为准 | `CounterReconcileJob`（每小时） | ✅ |
| 库存（Redis↔MySQL、分桶） | Redis 为准 | `InventoryReconcileJob`（每小时） | ✅ |
| 购物车（Redis↔MySQL） | 双写+对账 | `CartReconcileJob`（每小时） | ✅ |
| 优惠券模板/用户券 | DB | `CouponReconcileJob`（每小时） | ✅ |
| 支付/订单终态 | 支付单+映射表 | `PaymentReconcileJob`（每日 3 点） | ✅ |
| 未读数（DB↔Redis） | DB | `UnreadReconcileJob`（每 10 分钟） | ✅ |
| 本地消息表（outbox） | DB | `LocalMessageRetryJob` + `deadLetterScanJob`（每小时，≤3 次重投） | ✅ |
| 订单预扣残留 | Redis 预扣记录 | `PreDeductTimeoutJob`（过期清理） | ✅ |
| 订单↔映射表（分片反查） | DB | **`scripts/sharding-distribution-check.sh`**（审计脚本，非定时） | ⚠️ 有工具无调度 |
| 点赞关系（analytics 集/DB ↔ counter 计数） | 无统一权威 | **无对账 Job**（本轮已加 counter 版本门降低分叉概率，但跨域漂移无收敛器） | ❌ 缺口 |
| 优惠券 Outbox 投递 | DB | 仅消费端重试驱动 | ⚠️ 无对账 |
| IM 未读/消息序号 | DB/Redis | 无 | ❌ 缺口 |
| 订单事件/快照表增长 | — | 无归档/保留策略（随订单永久增长） | ❌ 缺口 |

**建议**：① 把 sharding 审计脚本挂 cron/XXL-Job（低风险）；② like↔counter 增加"按 analytics 集重算计数"的对账（或统一权威源）；③ IM 未读按 notification 模式补对账；④ order_event/snapshot 增加按月归档。

## 二、库存漂移根因（结案）

- 涉事预扣记录：`order_id ∈ {1789748684709/710(09-19 00:24), 1789818879154/5(19:54)}`，**均为毫秒时间戳形态的合成 ID**；映射表 `t_order_no_mapping` 对这 4 个 ID **0 命中** → 不是业务订单。
- 结论：漂移由**演练直接调用库存内部接口**（时间戳当 orderId）造成 DB/Redis 局部不一致，对账（Redis 为准）已修正；**业务链路无此问题**（E2E 后库存对账 0 修复）。
- 处置建议：演练脚本收尾时手工触发一次 `inventoryReconcileJob`（现成能力）。

## 三、数据生命周期盘点与处置

| 项 | 发现 | 处置 |
|---|---|---|
| ES ILM | `apply-ilm.sh`（30d 删除 + 模板）**从未执行**：所有 `myxhs-logs-*` 索引 `managed=False` | 已执行：创建 policy/template 并把**存量索引全部挂上**（`managed=True`） |
| ES 索引堆积 | 09-11/09-12 老索引仍在（合计 ≈1.8GB）；原因：**cron 进程今天 10:23 才启动**，"每日 4:00 清理"从未获得执行机会 | 已手动执行 `log-cleanup.sh`：删除 09-11/09-12 两个索引 + 45 个过期日志文件；cron 现已正常（xxl-health 13:05/13:10 为证），后续 4:00 自动清理 |
| 保留双口径 | 本地文件 **3 天**（`/data2/logs`）；ES 索引 **7 天**（脚本）；ILM **30 天**（兜底） | 三机制并存：脚本 7 天先到期，ILM 30d 仅兜底（与题库 53 口径一致，补充"ILM 本次已启用"） |
| releases | 41 个版本目录、6.9GB（约 3/服务，符合 release 脚本保留策略） | 暂不处置；若要进一步省盘可改保留 current+1 |
| 磁盘 | 宿主 overlay **79%（11GB 余量）**；/data2/logs 4.3G、/data2/releases 6.9G | 已释放 ~1.8GB（ES）；持续观察 |

## 四、结论
- 对账体系核心域全覆盖；**新增 4 项缺口登记**（like↔counter / 券 outbox / IM 未读 / 事件表生命周期）。
- 库存漂移结案：演练合成 ID 所致，业务链路清白。
- 生命周期真正的问题是"**机制在、执行没发生**"（ILM 未 apply + cron 未启动窗口）；两项均已落地，脚本 7 天清理恢复自动运行。
