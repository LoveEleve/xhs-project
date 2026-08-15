# M11 详细设计：HITL 审批闭环（WAITING_APPROVAL + approve 端点 + dlq.redeliver）

> 日期：2026-08-15 | 前置：M12 工具注册表（L3 元数据已就位，accessLevel/invoker=null 挂点）
> 定位：设计到可开工粒度。核心机制**复用 M5-4 checkpoint/resume**——挂起=终止+状态标记，审批=resume 注入执行，不引入新执行模型。

---

## 1. 目标与范围

- **L3 工具审批闭环**：Agent 请求 L3 工具 → WAITING_APPROVAL 挂起（事件+落库）→ 审批通过 resume 执行/拒绝终止 → 全链路审计（谁/何时/理由）
- **第一个真实 L3 执行类工具** `dlq.redeliver`：MQ 死信消息重投（命令模板写死 + 参数白名单 + 配置化管理通道）
- 明确不做：审批超时自动回收（V1 人工管理，挂起无线程占用）、多级审批、审批列表 UI

## 2. 核心机制：挂起 = 终止 + 状态标记；审批 = resume 注入执行

```
executeLoop 遇到 PolicyGuard.requiresApproval（L3 且有执行器）：
  1. 参数校验（PolicyGuard 先 validator——非法参数直接 deny，不浪费审批，Review 修正 1）
  2. 落 checkpoint（现有 recordAndStore 已做：当前步决策已存）
  3. 写 approval_json 到 ai_run（tool/args/requestedAt/status=PENDING）
  4. updateRunStatus(WAITING_APPROVAL)（心跳避免被崩溃恢复误认领——claimRunning 只认 RUNNING ✓）
  5. 事件 WAITING_APPROVAL（tool/args/reason）→ executeLoop 返回（线程释放，不阻塞）
审批通过（POST /approve {decision:"approve"}）：
  1. 原子认领：UPDATE status=WAITING_APPROVAL→RUNNING（影响 0 = 已处理/状态已变，拒绝重复审批）
  2. 写审计（approver/reason/decidedAt 进 approval_json）
  3. resume(runId, approvedTool)：重建上下文（checkpoint）→ **直接执行被审批工具**（不走模型决策）
     → 结果入 messages + 证据登记 → 继续 executeLoop（模型可继续调查/收尾）
审批拒绝（{decision:"reject", reason}）：
  1. 原子认领 → 审计
  2. updateRunStatus(CANCELLED) + finalAnswer="审批拒绝：reason"（审计字段区分，复用 CANCELLED）
```

> **Review 修正 1（P0）**：L3 工具**先参数校验再挂起**（非法 msgId/group 直接 deny，不进入审批）。
> **Review 修正 2（P0）**：**无执行器的 L3 预留工具（service.restart/order.refund）→ deny（"该动作未开放"），不挂起**——审批一个无法执行的动作是无意义 UX。
> **Review 修正 3（P1）**：approve 服务层兼容内存 miss（服务重启后审批）：run 不在内存 → store 原子认领后走 `resumeEntry` 重建（与崩溃恢复同路径）。

**为什么复用 resume 而不是线程等待**：诊断 run 挂起时释放线程（20 线程池不被死占）；审批是秒级到分钟级的人工操作，线程等待 = 资源泄漏。resume 已有 checkpoint 恢复 + 证据 registry 重建，只加"审批恢复执行"入口。

**跨轮一致性**：恢复执行被审批工具时，结果证据 ID 由本轮 registry 登记（新 ev_xxx），模型引用即校验通过——与普通工具执行同路径，无特殊证据语义。

## 3. 类型与状态

```java
// RunStatus 加 WAITING_APPROVAL（持久化 status 值；RunStore 注释同步）
// TerminationReason 加 APPROVAL_REJECTED（拒绝终态；status 仍 CANCELLED——少加枚举，审计字段区分）
// HarnessEventType 加 WAITING_APPROVAL、APPROVAL_RESULT（审批结果推送，前端刷新）

// ai_run 加列（DDL 迁移）
ALTER TABLE ai_run ADD COLUMN approval_json TEXT  -- {"tool":"dlq.redeliver","args":{...},"requestedAt":...,
                                                   --  "status":"PENDING|APPROVED|REJECTED","approver":...,"reason":...}
```

## 4. 组件改动清单

| 模块 | 改动 |
|------|------|
| app | `RunStatus.WAITING_APPROVAL`；`TerminationReason.APPROVAL_REJECTED`；`HarnessEventType` +2 |
| app | `PolicyGuard`：L3 分支改造——invoker==null → **deny**（未开放）；invoker!=null → **validator 先校验** → requiresApproval（Review 修正 1/2）|
| app | `AgentHarness.executeLoop`：requiresApproval 分支 → 挂起（不 feedback 重想）|
| app | `AgentHarness.resume`：增加"审批恢复执行"——pendingApproval 存在时先执行被审批工具再 executeLoop |
| app | `AgentHarness.requestApproval(run, decision, ctrl, listener, messages)`：落 approval_json + 状态 + 事件 |
| app | `RunStore.updateApproval(runId, json)` / `loadApproval(runId)`（ai_run.approval_json）|
| app | `RunManager`：`approve(runId, decision, reason, approver)`——原子认领 → resume / 终止 |
| app | `RunController`：`POST /api/runs/{id}/approve` |
| tools | `DlqRedeliverTool`：受控执行器（命令模板写死 + 参数白名单 + 配置化管理通道 baseUrl；未配置→ERROR）|
| tools | `AgentToolCatalog`：dlq.redeliver 的 **validator（msgId 32hex + group 白名单）** + invoker 由 binder 绑定（L3 case 补执行器）|
| app | `LogSearchAccess` 模式类比：`DlqRedeliverAccess` 接口 + 配置装配（未配置放行?——**必配**，缺配置 ERROR）|
| mcp | L3 工具不上线（filter usable 已处理）——dlq.redeliver 仅 app 侧经 Harness 调用 |
| frontend | WAITING_APPROVAL 卡片（工具/参数/通过/拒绝+理由）；`?run=` 恢复时若 WAITING_APPROVAL 显示审批 |

## 5. dlq.redeliver 执行器（命令模板写死 + 参数白名单）

```
配置：myxhs.ai.hitl.dlq-redeliver.url（HTTP 管理端点，如 http://mq-admin:8080/redeliver；未配置→工具返回 ERROR）
参数白名单：
  - msgId:     32 位 hex（正则 [0-9a-fA-F]{32}）必填
  - consumerGroup: [A-Za-z0-9_-]+ 必填
  - 其余参数拒绝
命令模板（写死，无 shell/拼接）：
  POST {url}?msgId={msgId}&group={consumerGroup}  （HttpClient，10s 超时）
执行结果（JSON）如实回填：{"status":"ok","tool":"dlq.redeliver","msgId":...,"result":...}
幂等性：重投命令本身幂等风险记录在案（审批即人为确认）；执行器不做业务幂等（如实报告服务端结果）
```

## 6. 端点契约

| 端点 | 说明 |
|------|------|
| `POST /api/runs/{runId}/approve` | body `{decision:"approve"\|"reject", reason?, approver?}`；非 WAITING_APPROVAL → 409；成功返回最新状态 |
| GET /api/runs/{id} | 状态含 WAITING_APPROVAL + approval 字段（前端渲染审批卡片）|

## 7. 时序（审批→执行）

```
模型: TOOL_CALL dlq.redeliver {msgId, consumerGroup}
→ PolicyGuard: L3 → requiresApproval
→ 挂起: checkpoint 已落 → approval_json(PENDING) → status=WAITING_APPROVAL → 事件 → 返回
运营: POST /approve {decision:"approve", approver:"ops1", reason:"确认重投"}
→ 原子认领(RUNNING) → 审计 → resume:
    checkpoint 恢复上下文 → 执行 dlq.redeliver(msgId, group) → 结果进消息 + registry 登记(ev_x)
    → executeLoop: 模型看到执行结果，继续调查或 ANSWER（证据含 ev_x）
```

## 8. 验收门禁（roadmap M11）

- [ ] 红队：无审批 L3 不可执行——模型请求 dlq.redeliver → WAITING_APPROVAL 挂起（工具未执行、无证据登记）
- [ ] 审批→执行→证据登记：approve 后 resume 执行 → 证据链含该工具结果 → 最终答案可引用
- [ ] 拒绝→终止+审计：reject → CANCELLED + approval_json 含 approver/reason/decidedAt
- [ ] 并发：重复 approve / 对非 WAITING_APPROVAL run approve → 拒绝（原子认领）
- [ ] 挂起不阻塞：WAITING_APPROVAL 期间其他 run 正常执行（线程释放验证）
- [ ] 崩溃兼容：WAITING_APPROVAL 不被崩溃恢复误认领（claimRunning 只认 RUNNING；回归）
- [ ] dlq.redeliver 参数白名单：非法 msgId/group 拒绝（PolicyGuard validator）
- [ ] 全量回归绿 + 真库 E2E（含审批全流程）

## 9. 风险与对策

| 风险 | 对策 |
|------|------|
| resume 后模型不再调用被审批工具 | resume 直接执行被审批工具（不进模型决策），结果注入上下文后模型自然继续 |
| 审批并发/竞态 | 原子认领（UPDATE 影响行数）+ 服务层单点 |
| WAITING_APPROVAL 被崩溃恢复误认领 | claimRunning 只认 RUNNING（现有语义天然排除）|
| 管理通道不可达（远端 10911 closed）| 配置化 URL + 未配置/失败 → ERROR 如实（同 log.search 白名单模式）；单测 fake 端点验证链路 |
| 审批后 SSE 断流 | 前端在 WAITING_APPROVAL 后轮询 GET 视图（审批结果落地即终态/续跑，前端刷新拉取）|
| 挂起 run 永挂 | V1 人工管理（文档注明）；挂起无线程/事件占用，内存 entry 由现有机制保留 |
| 审批后服务重启（approve 时内存 miss）| resumeEntry 重建（Review 修正 3：与崩溃恢复同路径）|

---

> 下一步：写前 review（对照克制原则与 M12 review 教训）→ 按 §4 顺序实现。
