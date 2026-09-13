# RV11：cart 事件流水 DLQ 根因修复（含测试漏检复盘）

> 日期：2026-09-13 ｜ 触发：用户追问"DLQ 积压 8 条是业务代码问题还是脏数据？"
> 结论：**业务代码缺陷（三方契约不一致）**，非脏数据。已修复、重放 9 条、清理 DLQ，并沉淀四道测试门禁。

---

## 1. 根因（三方契约不一致）

| 角色 | 行为 | 证据 |
|------|------|------|
| 生产者（合法语义） | 购物车级动作 `CLEAR`/`CHECK_ALL` 无具体 SKU，传 `skuId=null` | `CartService.java:600` `sendCartSyncEvent(userId, null, 0, 0, "CLEAR")` |
| 表结构（不接受） | `t_cart_event.sku_id BIGINT NOT NULL` 无默认值 | `init-all.sql:451` |
| 消费者（漏处理） | 脏消息防御只查 `action/userId/timestamp`；insert 时 `sku_id` 为 NULL → `DataIntegrityViolationException` → 重试 4 次 → DLQ | `CartEventSinkConsumer.java:67,84`；异常原文 `Field 'sku_id' doesn't have a default value` |

补充发现：
- **CHECK_ALL 同源**：DLQ 8 条中 2 条实为 `CHECK_ALL`（同样无 skuId），首次修复仅特判 CLEAR，二次修复改为"无 SKU 动作集合"。
- **幂等兜底缺失**：消费者注释声称 `uk_msg_id` 唯一索引兜底，实际建表无该索引（只靠 Redis 一层）。

## 2. 修复

| # | 改动 | 文件 |
|---|------|------|
| 1 | 无 SKU 动作集合（`CLEAR`/`CHECK_ALL`）→ `sku_id=0` 哨兵；其余动作缺 skuId 按脏消息跳过 | `CartEventSinkConsumer.java` |
| 2 | 补唯一索引 `uk_msg_id` + 修正动作词汇表注释 | `sql/migration/cart/V1__cart_event_uk_msg_id.sql`（新增）、`init-all.sql` |
| 3 | 核验器健壮性：DLQ topic 不存在（已清理且无新失败）= 未再入 DLQ（正常路径，不再报错挂起） | `xhs-ai DlqAdminService.findByOriginMsgId` |

## 3. 重放与验证（治理流程：审批→执行→异步核验）

- 9 条重放（DLQ 8 条中 7 条原始事件 + 2 条 CHECK_ALL 重放，跳过 1 条重复副本）全部经 `dlq_redeliver` 审批（审批单 2~10）→ `reply=once` → `SEND_OK`。
- 落库核对：`t_cart_event` 新增 **CLEAR 5 + CHECK_ALL 2 = 7**，与原始事件数一致（`CHECK_ALL` 首轮被旧逻辑跳过属预期）。
- DLQ 清账：`%DLQ%cart-event-sink-group` 删除，全量 DLQ 仅剩 product-index/recommend 两组历史测试数据（另行评估）。
- 异步核验：10 分钟窗口按 `DLQ_ORIGIN_MESSAGE_ID=重投新 msgId` 匹配 → 均未再入 DLQ（`verified_no_reentry`）。

## 4. 为什么 117 项测试没测出来（复盘）

| # | 原因 | 证据 |
|---|------|------|
| 1 | **覆盖角度错位**：CLEAR 只按"同步消费者+屏障"测（CA-L2-04），事件流水消费者（CA-L2-03）从未覆盖 CLEAR/CHECK_ALL | `test-docs/my-xhs-cart/01-test-matrix.md:23,24` |
| 2 | **断言粒度错位**：事件流水只断言"有数据"，不是"逐动作发布数=落库数"（CLEAR 实际 0 行） | 同文件；`t_cart_event` 动作分布 |
| 3 | **DLQ 门禁只覆盖两个手工选组**：DLQ 专项验证 counter/inventory-order（修复 #21 后指标可见），cart 组无告警无断言 | `99-runtime-reconciliation-report.md:125,201-206` |
| 4 | **旁路链路静默失败**：事件流水失败不影响主链路断言；测试自己发出的 CLEAR 就在 09-08 进了 DLQ（首次失败时间与测试同日） | DLQ Born Timestamp 09-08 19:29 |
| 5 | **跨层契约无机械校验**：生产者语义/表约束/消费者假设/注释声明四处漂移，全靠人发现 | 本次 uk_msg_id 注释缺失即是例证 |

## 5. 沉淀门禁（防复发）

| 门禁 | 内容 | 落点 |
|------|------|------|
| DLQ 零积压 | 每轮回归结束断言全量 `%DLQ%*` backlog=0，非零即失败 | xhs-ai 03-test-design（TC-EVAL-OPS-01）；harness `rocketmq-toolkit` |
| 动作词汇表契约 | 每个消费者组按动作全集发消息（ADD/UPDATE/DELETE/CHECK/CHECK_ALL/CLEAR），断言落库/状态 | TC-CT-EVENT-01 |
| 发布=落库对账 | 业务事件发布计数 vs 事件表行数（逐动作/逐 topic） | 对账脚本（nightly） |
| Schema 契约校验 | 代码声明（唯一索引/非空/枚举/哨兵约定）与实际 DDL 比对 | TC-CT-SCHEMA-01；harness `rocketmq-toolkit` 检查清单 |
| DLQ 指标自动发现 | 指标按 topic 自动发现，不硬编码组名（修复 #21 的完善） | 平台监控 |

> 教训：**测试断言要打在"副作用"上（DLQ/事件表/审计），而不是只打在"主流程返回值"上**；旁路链路必须有独立的完整性门禁。
