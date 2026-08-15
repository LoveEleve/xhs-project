# 深度 Review：M11 实现（HITL 审批闭环）

> 日期：2026-08-15 | 对象：M11 HITL（design-m11-hitl.md）| 视角：状态机正确性/审批并发/审计完整性/场景适配
> 结论：**1 个 P0（实现中自发现并修复）+ 无残留 P1**——215 测试全绿 + 真库冒烟

---

## P0-1：审批挂起视图缺工具名（pendingApproval 只有 args）

**问题链**：挂起时 `AgentRun.pendingApproval` 只存 args（msgId/consumerGroup），视图（GET /api/runs/{id}）展示 WAITING_APPROVAL 但**没有工具名**——前端审批卡片无法显示"审批哪个工具"。

**修复**：`AgentRun.flagWaitingApproval(tool, args)` 增加 pendingTool 字段，视图输出 pendingTool + pendingApproval。RunControllerTest 的挂起断言（`contains("dlq.redeliver")`）捕获。

## 实现中自发现并修复（非 review 阶段）

1. **H2 测试建表缺 approval_json 列** → 3 处测试建表 SQL 补列（RunControllerTest/AgentHarnessTest/HitlApprovalTest）
2. **loadApproval 对 null 列 NPE**：`rs.getString` 返回 null → `findFirst` 对 null 元素炸 `Optional.of(null)` → 先 filter 再 findFirst
3. **"重投死信"被意图路由当问候直答**：mock 分类失败→默认引导；测试 query 改用 L0 强信号（MQ 词）
4. **SYSTEM_PROMPT 未告知模型 dlq.redeliver 存在**（设计 P2-c 的后果）：真库 E2E 模型直接 DECLINE（"仅只读工具"）→ prompt 补 dlq.redeliver 说明（L3 需审批语义）→ 模型改为"先调查再决策"（查 mqDlqBacklog 后判断无死信不请求，行为正确）

## 注意点（记录在案）

1. **真库挂起-审批 E2E 不可控**：模型是否请求 dlq.redeliver 取决于数据（无真实死信时合理不请求）——完整链路由 Harness/Controller 层 fake 单测锁定（确定性），真库冒烟验证 app/端点/行为。
2. **dlq.redeliver 管理通道未配置**（远端 MQ 不可达）：工具返回 ERROR 如实（同 log.search 白名单模式）；生产配置 `myxhs.ai.hitl.dlq-redeliver.url` 即接真实执行。
3. **审批拒绝复用 CANCELLED**（TerminationReason.APPROVAL_REJECTED 区分）：少加枚举，审计在 approval_json。
4. **挂起无超时**（V1 人工管理）：无线程/事件占用，内存 entry 由现有机制保留；重启后 approve 走 resumeEntry 重建（Review 修正 3）。

## 方法论复盘

- **写前 review 3 处修正全部兑现**：L3 先校验再挂起（非法参数不浪费审批）/无执行器 L3 直接 deny（不挂起无法执行的审批）/approve 兼容内存 miss
- **"挂起=终止+状态标记，审批=resume 注入执行"**复用 M5-4 checkpoint 机制——零新执行模型，恢复路径天然防重复执行（EXECUTED 标记）
- **测试先于 prompt 发现真问题**：单测锁定链路后，真库 E2E 暴露"模型不知道工具存在"——提示 SYSTEM_PROMPT 与注册表的一致性维护（M14 可评估 prompt 从注册表生成）

## 结论

- M11 完成：审批闭环（挂起/审批/resume 执行/拒绝终止/审计/并发 409）+ dlq.redeliver 受控执行 + 前端审批卡片
- 验收门禁对照：红队（无审批不执行）✓ / 审批→执行→证据登记 ✓ / 拒绝→终止+审计 ✓ / 并发 ✓ / 崩溃兼容（claimRunning 只认 RUNNING）✓ / 参数白名单 ✓
- 215 测试全绿（app 154 + tools 50 + mcp 11）
- 下一步：M13 多智能体 PoC / M14 评测闭环（bad-case 回流并行积累）
