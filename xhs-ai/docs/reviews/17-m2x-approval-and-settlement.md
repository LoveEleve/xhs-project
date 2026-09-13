# RV17：M2.x 审批超时/跨实例 + 消费位点核验（已实现）

> 日期：2026-09-13 ｜ 对应挂账：RV08/RV10（PLAT-02 超时 fail-closed、D02 §10 跨实例、R3 消费位点核验）
> 结论：**三项全部落地并实测**；19/19 单测通过。

---

## 1. 实现内容

| # | 能力 | 实现 | 配置 |
|---|------|------|------|
| 1 | 审批超时 fail-closed | `ApprovalExpiryJob` 每 30s 扫描 pending；超时 CAS 置 `expired` + 审计 + 发布决策事件（不悬挂） | `ai.approval.timeout-minutes:10` |
| 2 | 跨实例决策事件 | `ApprovalEventBus`：Jedis Sentinel pub/sub 通道 `ai:approval:decided`；决策（once/always/reject/expired）即时广播，全部实例订阅，断线 5s 重连 | — |
| 3 | 消费位点核验 | `DlqAdminService.consumerProgress(group)`（examineConsumeStats 汇总 broker/consumer offset）；`ApprovalExecutor` 记录 `consumerOffsetBefore`；`RedeliverVerifier` 窗口结束时对比，位点推进 → `verified_consumed`，否则 `verified_no_reentry` + 人工复核建议 | — |
| 4 | 诊断端点 | `GET /api/ai/approvals/diagnostics/consumer-progress?group=`（运维/核验自查） | — |

## 2. 实测证据

| 验证 | 结果 |
|------|------|
| 超时 | 插入 requested_at=11 分钟前的 pending（id=19）→ 30s 内 `status=expired`，原因"超时未处理（fail-closed，超时阈值 10 分钟）"，`ai_audit action=approval.expired` |
| 跨实例事件 | 回复 id=20 → 日志 `已发布 {approvalId:20,status:approved}` + `收到跨实例决策`；超时 id=19 同样广播（订阅线程与调度线程分离） |
| 消费位点 | `consumer-progress?group=cart-event-sink-group` → `brokerOffset=77, consumerOffset=77, diff=0, queues=9` |
| 单测 | `ApprovalExpiryJobTest`（超时置态+审计+发布）+ 既有 18 个 = **19/19 通过** |

## 3. 边界（诚实）

- 跨实例事件当前用于**决策感知与审计一致**；Agent 侧"挂起等待审批后自动恢复对话"仍是同步审批模式（回复即执行），完整中断/恢复属后续增量；
- 消费位点核验依赖消费组有实时消费（位点推进才判定 consumed）；对无消费者的组会落入 `verified_no_reentry` 并提示人工复核，不误报成功。

## 4. 剩余

压测（N≥100 会话 P50/P95/P99）、FMEA 演练 4-6 项、工具预算护栏、M4 报告归档。
