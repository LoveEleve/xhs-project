# 生产化补差：审计防篡改 / 上下文压缩 / 请求幂等（RV29）· 2026-09-15

> 背景：对照"审计可抵赖、长会话上下文、客户端重试"三条生产标准，本章补齐并演练验证

## 1. 审计哈希链（防篡改）

| 项 | 实现 |
|----|------|
| 结构 | `ai_audit` 增 `prev_hash/entry_hash`；`ai_audit_chain` 单行链头（`SELECT ... FOR UPDATE` 串行化，多实例安全） |
| 哈希 | `SHA256(prev \| traceId \| actor \| action \| target \| canonical(params) \| result)`；params 递归按键排序（Java/Python 双端等价规范化），result 按列宽截断后再哈希 |
| 校验 | `scripts/audit-verify.sh` 逐行重算并检查 `prev` 链；已并入 `scripts/gate.sh` |
| 演练 | 正常 3 条链 ✅ 通过 → 篡改 1 条 result → **检出断链 exit=1** → 恢复 → 通过 |
| 边界 | 存量历史行（entry_hash 为空）不参与链；多实例依赖 InnoDB 行锁 |

## 2. 会话滚动摘要（上下文压缩）

| 项 | 实现 |
|----|------|
| 结构 | `ai_session_summary(session_id PK, summary, upto_message_id)` |
| 策略 | 保留最近 20 条不动；更早未摘要批次 ≥20 条（force 为 2）交给**轻量模型**压缩为 ≤300 字要点（输入上限 12000 字符） |
| 触发 | 定时任务（10min，单轮≤5 个活跃会话）+ ADMIN 端点 `POST /api/ai/sessions/{id}/summarize?userId=&force=` |
| 集成 | F7 状态重建时注入 `【历史摘要】` + 最近 6 条消息 |
| 演练 | 造 47 条历史（含关键事实）→ 摘要 27 条，正确保留"ZEBRA-42 / DLQ 87 条 / 待办" → **删除 Redis 状态后追问，Agent 正确答出两个值**，审计 `session.rebuild` |
| 边界 | 摘要用轻量模型（成本可控）；当前只压缩"保留窗之外"，不做对话中途的在线重写 |

## 3. 请求幂等（X-Request-Id）

| 项 | 实现 |
|----|------|
| 状态机 | Redis `xhs-ai:idem:{uid}:{rid}`：`SETNX IN_FLIGHT` → 完成后写 `DONE:{sessionId,reply}`；TTL 600s；Redis 异常 fail-open |
| 行为 | 重复提交 → 直接返回首次结果（`idempotent:true`）；处理中重复提交 → 409 |
| 演练 | 首提 47s → **重提 0s 返回同答复**；并发同 id → **一 200 一 409** |
| 边界 | 幂等键由调用方生成；只覆盖 `/api/ai/agent/chat`（流式端点为实时语义，不做缓存） |

## 4. 单测与门禁

- 单测 61/61（新增 AuditChain 3、SummarySelector 2、Idempotency 1 等）
- `scripts/gate.sh`：单测 + 审计一致性 + **哈希链校验** +（可选）评测
