---
iteration: ITER-008
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-18
updated: 2026-10-18
# 上游依赖：solution 未确认前，本文件不得进入 coding（见 .harness/rules/开发流程规范.md「阶段间接口」）
# 阶段间接口：REQ 编号一旦确认，禁止在不更新下游产物的情况下改写
---

# PRD: 库存模块生产化改造

> 机器可读的迭代协议产物之一。本文件定义**业务需求与验收标准**，是 solution 的唯一事实来源。

## 背景与目标

**三轮深探索结论**（代码级证据：`InventoryService` 1085 行 / 5 Lua / 4 消费者 / 5 Job / Mapper / DDL 全读）：

- **🔴 F1/F2 补发双扣族**：原始发送 eventTime=应用时钟、Job 补发用 created_at（DB 时钟）→ 版本门对补发"运气型放行"；`insertOutboxEvent` 的 ON DUPLICATE 复用行 → 应用 eventId 失配 → markSent/cancel 0 行静默失效 → RELEASE/REFUND_RESTORE 重复执行 → **available 虚高（超卖方向）**；
- **🔴 F4 幂等闸门崩溃窗口**：L1/L2/退款累计键"前置标记"语义——kill -9 后重投被自己标记拦死 → 剩余 SKU 永久不预扣（无自动检出）；
- **🔴 F12 标记窗口（04B 家族）**：confirm 部分缺失不写标记 → 迟到记录被超时任务释放 → 已付款货被放回；
- **🔴 F8 DLQ 缺失**：4 消费组重试耗尽进 DLQ 无人管（预扣 DLQ = 订单已建库存永不扣）；
- **🟡 F5/F11 键模型**：prededuct.lua 跨 3+ slot（真 Cluster CROSSSLOT）；分桶当前形态零收益（total 热 key 抵消 + 同 slot 无并发收益）；
- **🟡 F6/F7/F9/F10**：对账无 CAS、cron 注释漂移 ×3+、指标覆盖不全、锁前缀不一。

**目标**：逻辑事件幂等闭环（补发/重试/崩溃三场景）+ 集群正确的单键模型（去桶 + 每脚本单 slot）+ DLQ 兜底 + 工程收口。

## 非目标（Out of Scope）

真分片形态（桶粒度 tag/跨节点/放弃原子）；TCC 冻结；CDC 增量对账；权限面。

## 需求（REQ）

| ID | 需求描述 | 优先级 |
|----|---------|--------|
| REQ-1 | **逻辑事件幂等（F1/F2）**：消费端幂等键从 msgId 改 `{orderId}:{skuId}:{action}`（uk 保证唯一）；`markOutboxSent`/`cancelOutboxEvent` 改按 (order,sku,action) 匹配（消除 eventId 失配 0 行静默失效） | P0 |
| REQ-2 | **幂等闸门语义修复（F4）**：① L1 改**完成态标记**（处理后置标记；崩溃→重投→Lua 逐 SKU 幂等补扣）；② L2 去前置标记 → **事务内幂等**（`t_inventory_l2_event` INSERT IGNORE 与库存变更同事务）+ 版本门改"完成后推进"；③ 终止消费者同改完成态 | P0 |
| REQ-3 | **DLQ 重放（F8）**：3 核心消费组（transaction/deduct/termination）`%DLQ%` 重投源 topic；attempt≤3 防循环，超限仅告警 | P0 |
| REQ-4 | **键模型收敛（F5/F11/F12）**：① 去桶 → `inventory:{skuId}:stock` 单键；② 预扣记录收敛为 String 单键 `inventory:prededuct:{skuId}:{orderId}`，墓碑/索引同 `{skuId}` tag（**每脚本单 slot**）；③ 退款累计键改 `{skuId}` tag + **同槽原子脚本**（F4-C：累计推进+库存回补单脚本）；④ confirm/release 请求携带 `items`（order 侧为 SKU 事实源）；⑤ 超时 Job per-sku 索引 SCAN + 按 (sku,order) 释放/补确认；⑥ F12 标记条件改"任一预期 SKU 缺失即写" | P1 |
| REQ-5 | **对账 CAS（F6）**：`updateAvailableStockOnly`/`updateLockedStockOnly` 改条件更新（WHERE 快照值），消除与在线写的丢更新窗口 | P1 |
| REQ-6 | **注释漂移（F7）**：ReconcileJob cron（`0 0 * * * ?`，基准 init-xxljob.sql:112）/TimeoutJob "每 5 分钟"→实际 1 分钟/Sender "券"文案/OrderTransaction/Deduct 消费者 javadoc | P1 |
| REQ-7 | **工程收口（F9/F10）**：`recordInventoryAction` 补齐（confirm/release/refund/outbox/补偿/对账/DLQ）；锁 key 前缀统一 `myxhs:lock:job:inventory:*` | P1 |
| REQ-8 | **收口**：台账问题 1/2/3 回写"已修复"+ 4/5 补标；讲解系列修订记录（分桶移除）；库存对照文档（执行方案欠项）；八模块回归 | P0 |

## 验收标准（AC）

| ID | 归属 | 验收标准 | 来源 |
|----|------|---------|------|
| AC-1 | REQ-1 | 补发/重试（新 msgId）被逻辑事件键拦截；未处理过的事件正常处理；dup 场景 markSent/cancel 按 key 生效（0 行失效消除） | 深探 F1/F2 |
| AC-2 | REQ-2 | L1 崩溃→重投→已扣 SKU 跳过+未扣 SKU 补扣；L2 崩溃→重投→不丢不重（tx 幂等）；终止崩溃→重投→幂等释放；失败不置标记/成功后置标记 | 深探 F4 |
| AC-3 | REQ-3 | DLQ 消息重投源 topic（attempt+1 属性）；attempt≥3 仅告警；重投失败不吞 | 深探 F8 |
| AC-4 | REQ-4 | 每脚本全部 KEYS 同 tag（单测断言）；单键语义全绿（init 幂等/预扣/释放/确认/墓碑/退款原子）；items 契约贯通（order↔inventory）；confirm 部分缺失写标记 | 深探 F5/F11/F12 |
| AC-5 | REQ-5 | 对账 CAS：快照值条件更新；并发变更时 0 行不动（下轮收敛） | 台账问题 2 |
| AC-6 | REQ-6 | 注释与部署基准一致（cron/时间/文案零残留） | 深探 F7 |
| AC-7 | REQ-7 | 指标覆盖各业务事件；锁前缀统一 | 深探 F9/F10 |
| AC-8 | REQ-8 | 八模块全绿 + 台账回写 + 修订记录 + 对照文档 | 回归基线 |

## 边界情况

- 补发时原消息已处理（超时已投递/标记失败）→ 幂等拦截（不再双扣）
- 原消息未投递 → 补发正常执行（at-least-once 保持）
- L2 条件失败（0 行）→ 记录事件已完成 + 失败事件（不重投）
- 旧版本门 key/旧索引键残留 → 24h/清理说明（无真实环境）
- items 缺失（异常调用）→ 保守方向（confirm 写标记不释放；release 告警）

## 非功能需求

| 维度 | 指标 |
|------|------|
| 性能 | 预扣路径命令数下降（去 total 双写）；超时 Job SCAN 键数≈在途 SKU 数 |
| 可靠性 | 幂等闭环三场景（补发/重试/崩溃）；零"已付款货被放回"；零超卖向量 |
| 可观测 | recordInventoryAction 全事件覆盖；注释零漂移 |

## 依赖与影响面

- 模块/服务: my-xhs-inventory（主体）；my-xhs-order（2 方法载荷）；my-xhs-product（2 DTO 字段）；my-xhs-common（指标）
- 消息: 新 DLQ 重放消费者（3 组）；无新业务 topic
- DDL: `t_inventory_l2_event`（新表）；无其他
- 涉及 wiki: 无（归档时回流）

## 人工决策点（已决策，2026-10-18——Owner 授权 AI 决策；经三轮深探索 + 设计验证）

- **决策 1（去桶）**：分桶当前形态零收益（total 热 key + 同 slot）→ 移除，收敛单 key；真形态为演进方向（文档化）
- **决策 2（幂等键）**：逻辑事件键 `{orderId}:{skuId}:{action}`（稳定、无需 id 传递/LAST_INSERT_ID 技法）
- **决策 3（items 契约）**：order 侧为 SKU 事实源（拒绝"订单→SKU 索引"方案——跨 slot 无法原子，会重开 F12 类窗口）
- **决策 4（L2 机制）**：事务内幂等表（INSERT IGNORE 同事务）+ 版本门"完成后推进"；接受罕见跨事件并发竞态（条件+对账兜底）
- **决策 5（DLQ 范围）**：3 核心组（cache-evict 组排除——DELETE 事件低频、损失可重建）
- **决策 6（记录不做）**：真分片形态 / TCC / CDC 增量对账
