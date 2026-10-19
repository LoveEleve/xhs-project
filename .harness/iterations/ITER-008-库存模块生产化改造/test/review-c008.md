---
iteration: ITER-008
status: confirmed
version: 1
created: 2026-10-18
updated: 2026-10-18
# 用途：C-008 深审查（实施期查漏补缺）——键模型重构的语义保全与一致性核验
---

# 深度审查: C-008（库存模块生产化改造）

## 审查范围

C-008 全部落地改动：4 消费者（Deduct/OrderTransaction/OrderTermination/DlqReplay 新）、5 Lua（4 重写 + refund_restore 新 + reconcile_buckets 删）、InventoryService（全量重写）、5 Job、Mapper、DTO ×6、order 侧 2 方法、product 侧 1 DTO、DDL、yml、测试 ×7 文件。

## 审查清单（逐项校验结论）

> 结论标记：✅ 通过 / 🟡 发现并修复 / 📝 记录（边界）

### A. 部分失败/幂等（三场景矩阵）

- **补发（新 msgId）** ✅ —— L2 逻辑事件幂等（inbox PK=(order,sku,action)）拦截；单测 `duplicateApplicationSkipped`
- **重投（同 msgId）** ✅ —— 完成态标记（isProcessed 只读拦截）/ inbox 兜底；单测覆盖
- **崩溃（kill -9）** ✅ —— L1 完成后置标记（重投 → Lua 逐 SKU 幂等补扣）；L2 事务回滚无记录（重投重新应用）；退款回补同槽原子脚本（delta=0 幂等）；终止释放幂等（墓碑+无记录即返回）
- **F12 标记窗口** ✅ —— 任一预期 SKU 缺失即写标记；单测 `confirmDeduct_partialMissing_writesConfirmedMarker`
- **items 缺失防御** ✅ —— confirm 写标记（保守不释放）/ release 仅告警；单测 `releaseStock_missingItems_conservativeSkip`

### B. 键模型一致性（集群正确性）

- **全模块键格式一致** ✅ —— grep 核验：Service/TimeoutJob/CompensationJob/ReconcileJob/CacheEvict 五处键构造逐一对齐（`inventory:{skuId}:stock` / `:prededuct:{orderId}` / `:terminated:{orderId}` / `:prededuct:index` / `:refund:{orderId}`）
- **每脚本单 slot** ✅ —— 单测 `preDeductAllKeysSameSlot`（KEYS 全部 `inventory:{skuId}` 前缀）+ 各脚本 KEYS 注释与 Java 传参逐一对过
- **索引键一致性（🟡 真 bug 抓到）** —— R1：TimeoutJob 索引键曾用 `inventory:prededuct:index:{skuId}`（tag 在尾），与 Service 写入 `inventory:{skuId}:prededuct:index` **不一致** → 扫描永远匹配不到（超时释放整体失效）→ 测试 `expiredRecord_withoutConfirmedMarker_releases` 抓到 → 统一 tag-first

### C. 语义保全（历史修复的等价性）

- **F-037（来源桶释放）** ✅ —— 随分桶消亡（单键无"扣错桶"）；补偿测试断言按 (sku,order) 回退
- **N10（扩容暂停窗口）** ✅ —— 随 resize 消亡（无 pause/resize 路径）；无测试残留
- **T-073（雪花取模精度）** ✅ —— 随路由消亡（无桶路由）
- **T-124（bucket:count tag）** ✅ —— 键整体收敛（bucket:count 删除）
- **04B 墓碑** ✅ —— per-(sku,order) 原子「检查+总是写墓碑」语义保留（mark_terminated.lua 重写）；`preDeduct_terminatedTombstone_skip` + `releaseStock_noRecord_writesTombstone` 锁定
- **04B 确认标记** ✅ —— 保留 + F12 强化（任一缺失）
- **N7（不盲目回填）** ✅ —— getStock 保留（未命中读 MySQL 不回填）

### D. 跨模块契约

- **items 贯通** ✅ —— order `doConfirmInventoryDeduct`/`doReleaseInventory`（Map 载荷）→ inventory DTO；终止载荷 items 解析（兼容字符串形态 Long）
- **product 字段清理** ✅ —— InitRequest.bucketCount 删（原本就未 set）
- **兼容性** 📝 —— 单部署同发（无灰度需求）；旧格式消息（无 items）走防御路径

### E. 测试与编译证据

- **八模块 457 全绿**（common 93 / product 35 / coupon 24 / order 124 / **inventory 32**（基线 20，+12）/ cart 33 / search 43 / payment 73）+ 全仓编译零错误
- **实施期抓到的真实缺陷**：R1（索引键不一致，见 B）——测试先行策略生效

## 发现汇总（🟡 5 处，均已修复）

| # | 发现 | 类型 | 修复 |
|---|------|------|------|
| R1 | TimeoutJob 索引键格式与 Service 不一致（扫描失效） | **真 bug** | 统一 tag-first（测试抓到） |
| R2 | reconcileLockedStock 仍按 Hash 扫描（旧模型） | 重构遗漏 | 单键化重写（String GET + skuId 解析） |
| R3 | HotSkuDetector.java 未删（计划删除项遗漏） | 清理遗漏 | 删除 |
| R4 | 注释残留旧格式 ×4（PseudoOrderIdUtil/TimeoutJob×2/ReconcileJob/CacheEvict） | 注释漂移 | 修正 |
| R5 | ReconcileJob 缺 businessMetrics 字段（指标接线编译错误） | 接线遗漏 | 补字段 |

## 结论

幂等三场景 + 键模型一致性 + 语义保全 + 跨模块契约全部闭环；实施期抓到 1 个真 bug（R1）+ 4 处遗漏（R2-R5）已全部修复。证据：八模块 457 全绿 + 全仓编译。


---

# 第二轮深审查（查漏补缺，2026-10-18）

## 审查清单（5 维 18 项，逐项结论）

> 结论标记：✅ 通过 / 🟡 发现并修复 / 📝 记录（边界）

### A. 幂等/一致性语义再推演（4 场景走查）

- **A1 DLQ 重放 × 版本门 × inbox** ✅ —— 失败消息进 DLQ 时版本未推进（"完成后推进"语义）→ 重放（旧 eventTime）不被误杀；版本已被更新事件推进 → 重放被 stale 拦截（正确）；>24h 版本 key 过期 → inbox（7 天）兜底
- **A2 完成态标记并发投递** ✅ —— 双投递同处理（无标记）→ 业务操作天然幂等（Lua 逐 SKU / 墓碑）→ 双 markProcessed 无副作用
- **A3 outbox ON DUPLICATE 重置已 sent 行** ✅ —— 同 action 再入（cancel 失败重试等）→ 行重置 pending → Job 重发 → **消费端幂等三重拦截**（inbox 同 eventTime / 完成态 / Lua）；markSent 按业务键（F2）保证标记命中
- **A4 F12 标记与 confirm 重试/补偿交叉** ✅ —— confirm 重试对已确认订单（记录已删）→ anyMissing 写标记（无害——无遗留记录）；超时任务仅对遗留记录生效

### B. 键模型/集群正确性

- **B1 旧格式残留全仓扫描** 🟡 —— 代码/测试零残留；OrderService 注释 1 处旧格式（`inventory:prededuct:{id}`）→ 已修正
- **B2 脚本 × 调用方矩阵** ✅ —— preDeduct(4K/4A)/confirm(2K/1A)/markTerminated(2K/1A)/release(3K/1A)×4 调用方/refund(2K/2A) 逐一对齐
- **B3 非脚本多键操作** 📝 —— 仅 SCAN 模式（reconcile/超时 Job），Cluster 单节点扫描边界已注释（既有口径）

### C. 语义保全（历史修复等价性）

- **C1 T-066 自愈触发等价** ✅ —— canal DELETE 删 stock 键（CacheEvict 已改）→ doPreDeduct stock 缺失检查 → rebuild（与旧 bucketCount-null 触发等价）
- **C2 getStock initialized 语义变化** ✅ —— product 侧不消费 getStock/initialized（仅 init 调用）——零影响
- **C3 超时"补确认失败不释放"** ✅ —— catch 后 return true（本轮不释放，下轮重试）保留

### D. 跨模块契约边界

- **D1 order items 空列表防御** ✅ —— inventory 侧 confirm 写标记（保守不释放）/ release 仅告警；单测锁定
- **D2 product init/reinit 契约** ✅ —— InitRequest 去 bucketCount 后字段对齐；reinit 单参

### E. 证据

- **E1 陈旧报告假绿排查** ✅ —— 8 个回归模块 surefire 均为本次运行；非回归模块（analytics/content 等）陈旧报告不影响
- **E2 断言一致性抽查** ✅ —— 幂等键/指标名/CAS 参数逐条对照实现

## 第二轮发现（🟡 1 处真问题，已修复）

| # | 发现 | 类型 | 修复 |
|---|------|------|------|
| **R6** | **inbox 幂等键 `(order,sku,action)` 对 REFUND_RESTORE 太粗**：分次退款（先退 1 件再退 2 件）产生**同 (order,sku) 的第二个合法事件**（delta 增量），被 inbox 误杀 → MySQL 少补（Redis/MySQL 分歧直到对账） | **真问题（语义误杀）** | PK 加入 `event_time`：重发（同 eventTime）去重 ✓；分次退款（不同 eventTime）各自应用 ✓；回归测试 `multiRefundEventsAppliedIndependently`（+1 测） |

## 第二轮结论

18 项全部闭环：**抓到 1 个语义误杀问题（R6）并修复**（幂等键粒度：事件级而非动作级——分次退款是唯一"同 (order,sku) 多事件"合法场景，已推演穷尽）；其余 17 项通过或记录。证据：八模块 **458 全绿**（inventory 33，+1）+ 全仓编译。


---

# 第三轮深审查（修复交互回归，2026-10-18）

## 审查清单（聚焦"前两轮修复之间是否互相破坏"，5 项）

> 结论标记：✅ 通过 / 🟡 发现并修复 / 📝 记录（边界）

- **F1 补发链再推演（🟡 真问题 R7）** —— 原始 payload `eventTime=T0`（应用时钟，ms）；Job 补发用 `created_at`（DB NOW()，秒级截断）——**两者不等** → 第二轮 R6 把 eventTime 加进 inbox 主键后，补发被 inbox 视为"新事件"（MISS）→ **F1 双扣洞回归**（仅剩版本门"跨秒运气"兜底）
- **F2 重发 vs 新事件判定** ✅ —— 单事件型（PRE_DEDUCT/CONFIRM/RELEASE）：同 (order,sku) 唯一事件；REFUND_RESTORE：多事件型（分次退款）。R7 修复后：重发=同 eventTime（精确复用）→ 去重；分次退款=不同 eventTime → 各自应用 ✓
- **F3 双保险** ✅ —— R7 后补发 eventTime 与原始完全一致：版本门 stale-skip（version >= T0）+ inbox 命中双保险；>24h 版本 key 过期由 inbox（7 天）兜底
- **G1 负/零 quantity 链路** ✅ —— `PreDeductRequest` `@Min(1)`；order 事务消费端 `quantity <= 0 → throw`；refundRestore 负数 target → 脚本 `target <= cur` 跳过（安全方向）
- **G2 DTO 校验完整性** ✅ —— orderId/skuId/quantity `@NotNull`（preDeduct 另含 `@Min(1)`）

## 第三轮发现（🟡 1 处真问题，已修复）

| # | 发现 | 类型 | 修复 |
|---|------|------|------|
| **R7** | **R6 修复的交互回归**：eventTime 进 inbox PK 后，Job 补发（eventTime=created_at≠原始 T0）被 inbox 视为新事件 → 补发重复应用窗口回归（F1 洞） | **真问题（交互回归）** | **`t_inventory_outbox` 增 `event_time` 列**（原始 T0 随行存储；迁移文件 `sql/migration/inventory/V1__outbox_event_time.sql` + init-all ×2）；Job 补发**精确复用**（NULL 存量行回退 created_at）；`InventoryOutboxSenderJobTest` 加断言锁定（payload eventTime == 行内原始值） |

## 第三轮结论

5 项全部闭环：**抓到 1 个交互回归（R7：前两轮修复互相破坏——R6 的 eventTime 键 + F1 的补发时钟错位）并根治**（outbox 存原始 eventTime，补发精确复用——版本门与 inbox 双保险恢复确定性）。证据：八模块 **458 全绿**（inventory 33）+ 全仓编译。

**三轮审查累计**：R1（索引键不一致，实施期）/R2-R5（重构遗漏）/R6（inbox 粒度误杀分次退款）/R7（R6 与 F1 的交互回归）——全部修复并测试锁定。
