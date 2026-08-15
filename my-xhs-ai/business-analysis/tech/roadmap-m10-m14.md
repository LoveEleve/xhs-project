# 后续规划 v1：M10-M14（企业级 Agent 功能/技术深化）

> 日期：2026-08-15 | 状态：**规划完成，待决策点拍板后执行** | 取代：production-roadmap 的"M9 即完结"假设
> 定位：先盘点已有规划欠账，再定"搞什么/怎么搞/如何搞"——**不盲目写码**（de-risk-before-coding 纪律）

---

## 一、盘点：已有规划 vs 已交付（差距即后续范围）

| 规划来源 | 规划内容 | 已交付 | 欠账 |
|----------|---------|:--:|------|
| PLAN-v6 D5 | Durable Execution **与记忆** | Run/Step ✅ checkpoint ✅ 取消/恢复 ✅ | **HITL ❌ 记忆 ❌** |
| PLAN-v6 D6 | 三层评测、300+ 用例、效果指标 Dashboard、Langfuse | 单层 smoke 21 条 ⚠️ 指标有 ❌ Dashboard | 规模化 ❌ LLM-as-judge ❌ |
| architecture-design §3.3/3.4 | **Conversation（会话上下文）**、Trace | — | **Conversation 完全未做** |
| PLAN-v6 §3.4 | 四态分离：Run/Conversation/RAG/Memory | Run ✅ RAG ✅ | **Conversation ❌ Memory ❌**（只做了 2/4）|
| design-agent-harness §5 | HITL 审批门（WAITING_APPROVAL + approve 端点 + UI 按钮）| 只有 L3 恒拒绝 | **审批流完全未做** |
| M9 roadmap | 受控进程工具（mqadmin/mysqldumpslow）| log.search 只读版 ✅ | 执行类工具 ❌（需 HITL 配套）|
| PLAN-v6 L7/决策点 | 多智能体协作 + A2A | — | **决策点未拍板** |

> **结论：规划一直都在，欠的是 D5 的记忆/HITL、四态分离的 Conversation/Memory、D6 的评测规模化、以及多智能体决策点。** 不是没规划，是停在了 40%。

---

## 二、搞什么（M10-M14 功能范围）

### M10 会话与记忆（补四态分离欠账，最高优先）
- **多轮会话 Conversation**：`convId` 贯穿；消息持久化（ai_conversation）；多轮上下文注入（上轮结论带进下轮）；会话维度审计
- **记忆 Memory**：先**会话级摘要记忆**（每轮结束生成摘要，下轮注入），再**用户级长期记忆**（常查指标/偏好/历史结论，独立 ai_memory 表）
- 产品形态：从"单问题机器人"→"能追问、记得住上下文的诊断助手"

### M11 HITL 审批闭环（安全纵深补完）
- `WAITING_APPROVAL` run 状态（Harness 异步挂起，事件流推送 waiting_approval）
- `POST /api/runs/{id}/approve {decision, reason}`（审计：谁/何时/理由）
- **第一个真实 L3 执行类工具**（候选：mq 死信重投 `dlq.redeliver` 受控执行——命令模板写死 + 参数白名单，沿用 log.search 安全模型）
- UI 审批按钮（薄壳扩展）
- 红队：无审批不可执行；审批拒绝→终止且审计

### M12 工具平台化（架构欠账，多智能体地基）
- `ToolRegistry` 注册表：名称/描述/参数 schema/权限级(L1/L2/L3)/执行器——插件化新增工具零改 Harness
- 从硬编码三接口（MetricToolAccess/ObsToolAccess/LogSearchAccess）收敛为注册表驱动
- Gate：注册表化后 192 测试全绿；新工具 30 分钟接入（改配置+一个执行器类）

### M13 多智能体编排（决策点，见 §四）
- Orchestrator 分派：业务诊断 Agent / 技术排障 Agent / 日志检索 Agent（共享 Harness 引擎，不同工具集/预算/prompt）
- 先 PoC（双 Agent 分流）→ 评测对比单 Agent → 决定全量
- 若不做：产出"单 Agent 已够 + 何时才需要多 Agent"的论证（克制可讲）

### M14 评测与质量闭环（D6 欠账）
- 评测集 21 → **100+**（多轮/记忆/HITL/边界/拒答/权限/攻击 分层）
- **LLM-as-judge**：质量分进报告（幻觉率/完成率之外的主观质量维度）
- **bad case 回流**：真实 run 的差回答自动进评测集（企业级 RAG/Agent 标配）
- eval-gate 阈值从拍脑袋 → **实测校准**（先真跑一轮修 7/7 失败欠账）

### M15 模型分层 routing（暂缓，决策点 D4 冻结维持）
- 单一 flash 已够便宜；价值评估后再解锁

---

## 三、怎么搞（关键技术方案要点）

### M10 会话与记忆
```
ai_conversation(conv_id, user_id, title, summary, created_at, last_activity_at)
ai_message(conv_id, role, content, refs_json, run_id?, created_at)
ai_memory(user_id, key, value_json, updated_at)   -- 用户级长期记忆
```
- Harness 多轮入口：`run(query, convId, memoryContext)`——messages 从会话恢复 + 记忆摘要以 SystemMessage 注入
- 会话摘要：终态后调模型生成（或规则抽取结论段），成本可控
- 注入优先级：记忆摘要 < 会话历史 < 当前问题

### M11 HITL
- Harness 状态机加 `WAITING_APPROVAL`（executeLoop 遇到 L3 工具 → 挂起 + 事件 + 存 checkpoint，不阻塞线程）
- 审批通过 → 从 checkpoint 恢复执行该工具；拒绝 → 终止 CANCELLED/REJECTED（审计）
- Run Store 状态机扩展（RUNNING→WAITING_APPROVAL→RUNNING/终态）

### M12 工具注册表
```
ToolSpec { name, description, schema, accessLevel(L1/L2/L3), invoker }
ToolRegistry { register(ToolSpec), get(name), all() }
PolicyGuard 校验改为读注册表元数据（权限级 + 参数白名单规则）
```
- MCP 工具列表也从注册表生成（单一事实源）

### M13 多智能体
```
Orchestrator（现有 IntentRouter 升级：AGENT → 按领域分派）
  ├─ BusinessAgent（订单/支付/内容工具集，预算大）
  ├─ OpsAgent（观测/日志工具集）
  └─ 共享：Harness 引擎（参数化工具集+prompt+budget）
```

### M14 评测闭环
- eval/cases 目录分层：smoke/regression/multi-turn/memory/hitl/security
- judge：独立评测 prompt + 评分（0-5）+ 证据引用检查；进 EvalGate 报告

---

## 四、如何搞（执行顺序 + 门禁）

```
前置：eval-gate 跑绿（7 个失败用例修复，评测地基）
M10 会话+记忆 → M12 工具注册表(含 accessLevel) → M11 HITL(挂注册表) → M13 多智能体 → M14 评测闭环
（深度 review 修正：M12 在 M11 前——HITL 的权限级/审批挂点是注册表职责；M14 的 bad-case 回流并行积累）
```

| 里程碑 | 验收门禁（每步小步 + 192 测试兜底）|
|--------|------|
| M10 | 多轮归因用例（第二问引用第一问结论）✅；会话摘要注入生效 ✅；记忆命中 ✅；全量回归绿 |
| M11 | 红队：无审批 L3 不可执行 ✅；审批→执行→证据登记 ✅；拒绝→终止+审计 ✅ |
| M12 | 注册表驱动全量绿；MCP 工具列表=注册表导出 ✅ |
| M13 | PoC 双 Agent 分流正确性 ✅；与单 Agent 对比评测报告（决定全量/克制）|
| M14 | 100+ 用例门禁绿；judge 分数进报告；bad case 回流闭环 ✅ |

---

## 五、决策点（已拍板，2026-08-15）

| # | 决策 | 结论 |
|---|------|------|
| D-A | 多智能体（M13）| **PoC 后定**：先双 Agent（业务/排障）分流 PoC + 评测对比单 Agent，数据说话 |
| D-B | 记忆范围（M10）| **会话级先做**（多轮摘要记忆），用户级长期记忆为后续增量 |
| D-C | HITL 首工具（M11）| **mq 死信重投 `dlq.redeliver`**（命令模板写死+参数白名单，同 log.search 安全模型）|
| D-D | 模型分层（M15）| **继续冻结**（单一 flash 足够便宜，评测一致性优先）|

---

## 六、执行建议

**推荐顺序：M10 → M11 → M12 → M13 → M14**，每步小步 + 深度 review + 评测回归（项目纪律）。
M10 是地基（多轮是记忆/HITL/多 Agent 的展示载体），M14 贯穿（bad case 边做边积累）。
**本轮先定 §五 的 4 个决策点，再动工。**
