---
iteration: ITER-007
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-17
updated: 2026-10-17
# 上游依赖：solution 未确认前，本文件不得进入 coding（见 .harness/rules/开发流程规范.md「阶段间接口」）
# 阶段间接口：REQ 编号一旦确认，禁止在不更新下游产物的情况下改写
---

# PRD: 优惠券模块生产化改造

> 机器可读的迭代协议产物之一。本文件定义**业务需求与验收标准**，是 solution 的唯一事实来源。

## 背景与目标

**深度探索 + 两轮 review + 业务语义对照结论**（证据：对照 `03-优惠券` §7 逐条核实 + 代码走查 34 项）：

- **🔴 真洞（部分失败→超发）**：领券发送链——① SEND_OK 后 markOutboxSent 失败 → 作废 outbox + 回滚 Redis，**但消息已发出** → 消费者发券 → 超发；② syncSend 超时/非 SEND_OK（消息可能已写入 broker）→ 同样作废+回滚 → 超发；
- **语义缺口 G35**：DLQ 重放 × 对账修正后重领交叉 → 用户可持有超 perUserLimit 的券；
- 工程：`returnCoupon` javadoc 与实现矛盾（旧语义残留）；getAvailableCoupons N+1；无消费-DLQ 重放；指标稀疏；cron 注释漂移 ×2；死常量；
- **lead5 翻案**：对照文档称"SQL 库段错位"——实际 init-all.sql:525-526 明确 `USE my_xhs_content`，**不是问题**（phase-1 误判）。

**目标**：领券链路重构为 **Design B（名额锁定即成功 + 券必达·至少一次）**——产品语义对齐真实平台；消除全部超发向量。

## 非目标（Out of Scope）

- 叠加/互斥/发券/兑换码/定向券（应然缺口——讲不做）；重型一致方案（版本号 CAS——克制）；权限类

## 需求（REQ）

| ID | 需求描述 | 优先级 |
|----|---------|--------|
| REQ-1 | **领券发送链 Design B 重构**：outbox 先落（**同 claimNo 幂等重试一次**——"写入超时≠失败"，DuplicateKey=实际已写按成功）；syncSend 任何结果均**保留 outbox**（SEND_OK→markSent 尽力；异常/非 OK→warn）；**仅"双次写入失败"才回滚 Redis + 报错**；领券在闸门放行后**恒返回成功**（补发任务必达） | P0 |
| REQ-2 | **投递时限领守卫（G35）**：消费者 insert 前查 `count(user, template) ≥ perUserLimit` → 跳过 + 告警（防 DLQ 重放×重领交叉的超限发放；stock 漏损为安全方向） | P0 |
| REQ-3 | **DLQ 重放消费者**：消费 `%DLQ%coupon-claim-consumer-group` + `%DLQ%coupon-order-termination-consumer-group` → 重投源 topic；attempt 计数上限 3（超限仅告警，防循环） | P0 |
| REQ-4 | **死代码清理**：`markOutboxCancelled` 删除（Design B 后零调用）；`returnCoupon` javadoc 修正（RV30 语义）+ 无意义 `evictTemplateCache` 移除；claimCoupon 重复 switch 提取 | P1 |
| REQ-5 | **getAvailableCoupons N+1 消除**：单次批量模板查询复用（下线过滤 + VO 构建共用）；**必须保持 T-121 语义**（validStart/End/下线/孤儿券过滤） | P1 |
| REQ-6 | **可观测**：`BusinessMetrics.recordCouponAction` 指标族（claim 各结果 / use / return / 消费者持久化 / DLQ 重放；reconcile 修复项）；cron 注释 ×2 对齐部署（引用 init-xxljob.sql 行号） | P1 |
| REQ-7 | **死常量删除**：common `RedisKeyConstants.COUPON_STOCK/COUPON_USER`（零引用且格式与真实键不符） | P2 |
| REQ-8 | **收口**：对照 §7 回写（10 线索裁决含 lead5 翻案 + 新发现 + 两轮 review + 语义论证）；八模块回归 | P0 |

## 验收标准（AC）

| ID | 归属 | 验收标准 | 来源 |
|----|------|---------|------|
| AC-1 | REQ-1 | 发送异常/非 SEND_OK → **outbox 保留 + 领券成功**（不抛错、不回滚、不作废）；重复投递由 uk_claim_no 幂等兜底 | review G-H1/H2 |
| AC-2 | REQ-1 | outbox 写入失败 → 同 claimNo 重试一次：DuplicateKey=成功路径；仍失败 → 回滚 Redis + 报错（唯一回滚路径） | review G3 |
| AC-3 | REQ-1 | SEND_OK → markSent 尽力（失败仅 warn，行保留补发 → 幂等兜底） | review G-H1 |
| AC-4 | REQ-2 | 消费者：DB count ≥ perUserLimit → 跳过 + 告警（不 insert、不 decrement）；正常路径永不触发（闸门已保证） | 语义对照 G35 |
| AC-5 | REQ-3 | DLQ 消息重投源 topic；attempt>3 → 仅告警；重投成功后正常消费 | review G25 |
| AC-6 | REQ-4 | `markOutboxCancelled` 零残留；returnCoupon javadoc=RV30 语义；claimCoupon 无重复 switch | 探索 N3/N6 |
| AC-7 | REQ-5 | getAvailableCoupons 单次批量查询；T-121 语义保持（用例锁定） | 对照 §7-9 |
| AC-8 | REQ-6 | recordCouponAction 各业务事件落位；cron 注释与 init-xxljob.sql 一致 | 对照 §7-2 |
| AC-9 | REQ-8 | 八模块全量回归全绿 + 新增用例通过；§7 回写 | 回归基线 |

## 边界情况

- outbox 双次写入失败残余（连续两次未知窗口）→ 记录（极低概率；对账可见）
- DLQ 重放后仍失败（数据损坏）→ 告警；对账修 claimed → 用户可再领（业务自愈）
- removeMark 失败 + 重试被幂等跳过 → 丢券（**安全方向** under-issue）；对账修 claimed → 可再领——记录
- 对账 Redis 丢失补种偏高（in-flight 未落账）→ mild 窗口，既有行为——记录
- 旧部署快照 cron 与当前基准差异（08-11 快照每分钟 vs deploy zip 5min/hourly）→ 按 deploy zip 修正——记录

## 非功能需求

| 维度 | 指标 |
|------|------|
| 性能 | getAvailableCoupons 消除 N+1；claim 无新增往返（重试仅失败路径） |
| 可靠性 | 至少一次语义（outbox+补发+DLQ 重放）；**零超发向量**；漏损安全方向 |
| 可观测 | recordCouponAction 覆盖全部业务事件；告警路径（守卫/DLQ 超限/双败） |

## 依赖与影响面

- 模块/服务: my-xhs-coupon（主体）；my-xhs-common（死常量）
- 消息: 新 DLQ 重放消费者（两个 %DLQ% topic）；无新业务 topic；无 DDL
- 涉及 wiki: 无（归档时回流）

## 人工决策点（已决策，2026-10-17——Owner 授权 AI 决策；经两轮 review + 语义对照）

- **决策 1（Design B）** = 至少一次：闸门放行后恒成功、outbox 必达；仅"双次写入失败"回滚。理由：关闭全部超发向量 + 对齐产品语义（内部故障不进用户视野）+ 项目哲学（超时≠失败）
- **决策 2（写入重试）** = 同 claimNo 幂等重试一次（DuplicateKey=已写入）
- **决策 3（DLQ 重放）** = attempt≤3 自动重放（核心线首个自动消费-DLQ 重放；AI 线 HITL 工具为人工补充路径）
- **决策 4（限领守卫）** = 投递时以 DB 计数守 perUserLimit（G35）
- **决策 5（记录不做）** = 应然缺口 / 重型一致方案 / 手动退券端点（复用 HITL 工具）
