# 从聊天机器人到企业级诊断 Agent：my-xhs-ai 的架构演进与工程化收官

> 类型：项目总复盘（论文式）| 日期：2026-08-17 | 定位：作品集/面试/博客母稿
> 配套：T5 三档讲稿、T6 九问讲稿、T7 JD 命中矩阵（`jd-hit-matrix-v1.md`）
> 成熟度标注约定：`已实现` = 有代码 + 有测试；`真模型验证` = 真库真模型跑过；`外部未闭环` = 受上游依赖阻塞。
> 证据出处：评测报告见 `docs/reports/eval-gate-report-20260817.{json,md}`；各里程碑 review 见 `review-m10-impl.md` ~ `review-m14-impl.md`、`architecture-review-v1.md`。

---

## 摘要

本文复盘一个面向电商平台的运营/运维诊断 Agent（`my-xhs-ai`）从"能对话"到"能负责任地调查"的完整演进。项目没有把目标定义成"让大模型更会聊天"，而是定义成一个**受限、可审计、可评测、可恢复**的企业级诊断系统。核心结论：企业 Agent 的难点不在"接入模型"，而在三个确定性约束——**不编造、不越权、可评测**。本文记录架构取舍、关键机制、真实调试案例、评测结果与已知边界，以及为什么"克制"本身是这个项目最有价值的部分。

---

## 一、问题定义：企业 Agent 的核心矛盾

普通聊天机器人与简单 RAG 无法满足企业诊断场景，原因有三：

1. **数字不可追溯**：回答可能流畅，但不能证明"这个数字来自哪里"。
2. **能力不可控**：模型一旦拥有任意执行能力，越权风险不可接受。
3. **质量不可回归**：模型升级或 prompt 变化后，没有机制证明系统"没有变差"。

因此，企业 Agent 的核心矛盾是：**需要模型的推理能力，但不能要模型的任意性**。

本项目的解法不是更强地约束 prompt，而是用**确定性架构**把模型的任意性关进笼子。

---

## 二、目标与边界：做什么、不做什么

### 做什么
面向订单、支付、内容运营，以及 MQ、HTTP、数据库运维链路，做多步调查、证据归因和受控输出。

### 不做什么（刻意边界）
- 不做任意代码/SQL/PromQL 执行（CodeAct 红线）
- 不做通用聊天助手
- 不做多模型分层（成本红线：仅 flash）
- 不依赖 prompt 承诺安全，靠架构兜底

这些"不做"不是能力缺失，而是设计决策，后文 §八 有完整取舍记录。

---

## 三、架构演进：分层与演进顺序

系统按"确定性优先、Agent 兜底"演进。先给规模与里程碑锚点：

### 规模锚点（2026-08 实测）

| 模块 | main/test 行数 | 职责 |
|------|:--:|------|
| my-xhs-ai-tools | 1818 / 897 | 共享工具纯类 + ToolRegistry + dlq.redeliver |
| my-xhs-ai-app | 6444 / 4391 | 路由 + Harness + HITL + 会话 + 评测 |
| my-xhs-ai-mcp | 305 / 306 | MCP 服务（14 工具） |

- 非 eval-gate 套件 app 173 测试全绿（另有 tools/mcp 测试稳定全绿）
- 里程碑序列（commit）：前置债（eval-gate 跑绿）→ M10 会话记忆 → M12 工具注册表 → M11 HITL → M13 多智能体 → M14 评测闭环，见 HANDOFF-AI-v5 §4

关键分层如下：

### 3.1 Workflow 与 Agent 分离
固定查询（"订单量多少"）是确定性问题，直接走工具；归因问题（"为什么下降"）才进入受限 Agent。`IntentRouter` 规则强信号优先，复杂归因才进 Harness。

**为什么重要**：这决定系统是可重复、可评测的。确定性路径的数字永远可复现，模型只出现在真正需要推理的地方。

### 3.2 受限 Harness 状态机
Agent 核心不是无限 ReAct 循环，而是一个状态机，负责：结构化决策解析、工具调度、步骤/Token/成本预算、循环与无进展检测、证据登记、取消/checkpoint/恢复、SSE 事件、最终答案校验。

### 3.3 ToolRegistry + MCP
工具元数据、参数 schema、权限级别、执行器收敛到单一事实源。PolicyGuard 读注册表校验权限，MCP 从同一事实源导出工具目录。新增工具不再在 Harness 里堆硬编码分支。

### 3.4 会话与记忆（四态分离）
Run（执行态）/ Conversation（会话态）/ RAG（知识态）/ Memory（长期态）四类状态分离。跨轮只注入规则抽取的结论摘要，不复用旧工具原文——**跨轮数据必须重新查询**。

### 3.5 多智能体（PoC 后决策）
先单 Agent 验证工具/证据/评测地基，再做 BUSINESS/OPS/FULL 双 Agent PoC，共享 Harness、不同 prompt/工具集/预算，最终用对比评测数据决定是否全量，而不是因为"多 Agent 先进"就堆复杂度。

### 3.6 评测闭环
YAML 用例集 + 硬断言 + 数字一致性 + 统计阈值 + LLM-as-judge + bad case 回流，构成完整质量门禁。

---

## 四、三道防线：不编造、不越权、可评测

### 4.1 不编造：存在性校验 + 数字一致性
只靠 prompt 请求模型诚实不够。真实机制是：

- 每次真实工具调用登记 evidenceId 到本轮 `ToolResultRegistry`
- 最终答案的 `evidenceRefs` 必须全部命中，否则拒绝并要求重想
- 持续无法提供真实证据则明确失败或拒答

这是**运行时事实约束**。评测层再做数字一致性检查（答案数字 vs 证据数字），作为启发式幻觉信号。

### 4.2 不越权：deny-by-default + HITL
- 工具权限 deny-by-default，未注册不可调用
- 参数白名单校验
- L3 执行型工具必须进入 `WAITING_APPROVAL`，审批后 resume，拒绝则终止并审计

关键点：即使模型被 prompt injection 影响，也突破不了确定性权限边界。注入最多影响"模型想做什么"，不能决定"系统允许它做什么"。

### 4.3 可评测：把质量变成可重复指标
不比较完整文本，而是拆成确定性断言 + 统计阈值。完成率、通过率、幻觉率、时延、成本进报告，门禁用阈值带而非要求每次输出完全一致。

---

## 五、真实调试案例（比成功结果更有价值）

### 5.1 eval-gate 门禁跑出 0 通过率
项目早期，eval-gate 真库真模型跑出 `passed=0, passRate=0.0`（7 个用例全部未通过）。这不是"模型不行"，而是评测链路的多个问题叠加（profile 空 excludedGroups 覆盖、数字一致性误报 8 种形态、限流无重试）。修复后门禁真实跑绿。出处：`project-review-v1.md` §一。

**启示**：Agent 项目最危险的不是模型效果差，而是**验收门禁从未真跑过**。

### 5.2 枚举序号被误判为幻觉（本次修复）
真模型评测中，`b2_mq_lag` 用例被判 hallucinationSuspected。追查发现：模型答案用 `1) 2) 3)` 列举原因，`EvalAsserter` 的数字提取把序号 `1` 当业务数字抽取，而证据链全是 0，导致误报。修复数字提取逻辑（跳过 `1)` / `2、` / `3. ` 形态）+ 补单测后，`EvalGateRunTest` 7/7 通过、幻觉率 0%。

**启示**：**评测系统本身也必须被测试**。评测结果不是绝对真理，它和业务代码一样会出 bug。修复 commit：`e9ee41f`，归档报告：`docs/reports/eval-gate-report-20260817.json`。

### 5.3 RunManager 与 AiQueryController 路由不一致
深度 review 发现 `RunManager` 与 `AiQueryController` 注入的 `IntentRouter` 实例不一致（一个纯规则、一个含 LLM 分类），导致同一系统两入口行为分裂。这是日常 E2E 测"正常路径"测不出来的问题，只有对照两入口才暴露。出处：`architecture-review-v1.md` P0-1。

**启示**：深度 review 的价值在于发现"一致性"类问题，这类问题单测和 happy-path E2E 都覆盖不到。

### 5.4 dlq.redeliver 真实契约与 E2E 闭环
执行型工具 `dlq.redeliver` 对接真实 RocketMQ Dashboard 契约（csrf 会话 + batchResendDlqMessage.do）。2026-08-19 首次跑通真实 E2E：Agent → mqDlqQuery → 提取 ORIGIN_MESSAGE_ID → HITL 审批 → dlq.redeliver → **CR_SUCCESS**。此前历史 DLQ 样本（`skuId=6/999`）返回 `CR_LATER`，本次新造合法消息首次拿到 `CR_SUCCESS`。

**启示**：真实 E2E 不能跳过——契约测试通过不代表外部链路通，必须造真实数据跑一遍。

---

## 六、评测结果（真库真模型）

| 项 | 结果 |
|----|------|
| 单 Agent 对比评测 | pass 85.7%、幻觉 14.3% |
| 双 Agent 对比评测 | pass 100%、幻觉 0% |
| `EvalGateRunTest`（门禁锚点 7 条） | 7/7 通过、completion 100%、幻觉 0%（修复误报后；归档 `docs/reports/eval-gate-report-20260817`） |
| `SampledRegressionTest` | 通过 |
| 造数闭环 | 订单 8（4 分片）/支付 6/加购 18/笔记 20/浏览 60，漏斗 60:18:8 |
| 成本 / 时延 / 发散率 | **nightly 首跑已产出基础结果**：100 条合计 avgDuration ≈ 25.6s/条；发散率仍需单独 bad case 汇总（见 `docs/reports/nightly-100-report-20260817.md`） |

> 数据成熟度：核心指标为 seed 数据 + 真基础设施链路；"真实线上流量"场景仍受业务流量限制，文档已如实标注。
> nightly 状态：2026-08-17 已完成 **100 条全量首跑**（smoke 20 = 100/100/0；regression 80 = 98.8/97.5/1.3；合计 99/98/1）。

---

## 七、边界与已知问题（诚实清单）

| 边界 | 状态 |
|------|------|
| nightly 全量 100 条 | 未首跑（收官项） |
| 远端 CI | 未真实验证（凭据/MCP/超时待接入） |
| dlq.redeliver 真实 E2E | 外部未闭环（ORIGIN_MESSAGE_ID 接口 NPE） |
| 长期 Memory Store | 已实现基础版（豆包 embedding + 语义检索 + 多用户隔离），仍可继续扩展 |
| Langfuse/OTel 可观测 | 已接通（run/generation/tool/answer trace），可直接展示 Langfuse trace |
| 微调 | 未做（诊断场景 flash 已够，属学习项） |
| A2A / CodeAct / 多租户 | 刻意不引入（见 §八） |
| kill -9 恢复演示 | 未做（checkpoint/恢复代码已实现 + 测试；故障注入演示是作品集缺口） |

这些边界不是掩盖，而是**明确声明**——面试时主动讲边界，比被追问后被动承认更可信。

---

## 八、取舍记录：为什么不用 X

| 取舍 | 理由 |
|------|------|
| 自研 Harness，不用 LangGraph/CrewAI | 核心难点是精确控制 checkpoint/权限/证据/恢复，通用框架不覆盖这套 Java 业务约束；自研范围限制在"执行状态机" |
| Java，不用 Python | 复用现有 Spring Cloud 电商微服务与数据基础设施；且 Java 在 18 份 JD 中明确可投 9 份，概念可迁移，就业面并非劣势 |
| 单一已验证模型，不做模型分层 | 当前主模型为 `mimo-v2.5-pro`；核心原则不是某个模型名，而是"切模型必须重跑 eval-gate"，避免变量膨胀 |
| 薄前端 | 前端只消费 API/SSE/证据/审批，不承载推理逻辑 |
| 不用 CodeAct | 企业诊断安全优先，不允许模型获得任意执行能力 |
| 不一开始做多智能体 | 先验证地基，再 PoC 对比评测决定，不堆复杂度 |
| 不用 A2A | 同进程共享 Harness，无跨系统通信需求 |

**核心观点**：克制本身是能力。能讲清"什么时候才需要多 Agent/多模型/CodeAct"比"我用了 5 个框架"更值钱。

---

## 九、方法论沉淀

1. **de-risk-before-coding**：写码前先深度 review，明确搞什么/怎么搞/如何搞
2. **评测先行**：先把门禁跑绿，再扩功能
3. **迁移黄金规则：委托而非复制**（M12 发现 keyword 白名单漂移）
4. **状态枚举变化须审计所有终态判断点**（M11 发现会话锁/metrics/SSE 漏改）
5. **评测集本身必须被评测**（M14 抽样验证 > 静态断言）
6. **确定性兜底 > prompt 承诺**（安全与不编造都靠架构，不靠措辞）

---

## 十、与业界对照

| 对照 | 采纳/拒绝 | 说明 |
|------|----------|------|
| Anthropic《Building Effective Agents》 | 采纳 Workflow/Agent 分层、ground truth、停止条件 | 拒绝 CodeAct/个人助手模型：与"受限、可审计"铁律冲突 |
| OWASP LLM Top10 | 覆盖 LLM06 Excessive Agency（HITL）、LLM05 输出处理（存在性校验） | LLM01 注入靠确定性兜底（注入无法绕过 allowlist）；LLM07 Prompt Leakage 深化中 |
| Promptfoo | 采纳声明式用例、评测缓存思想 | 自研 JUnit 方案，本地运行无数据外泄，比 SaaS 更契合隐私约束 |
| Temporal | 采纳 Durable 六价值，自研子集 + PoC 完成 | 已用审批型长任务验证 worker kill / restart / approve / complete；主线暂不切，但对照证据已具备 |
| Langfuse | 采纳可观测理念并完成接入 | 已展示 run/generation/tool/answer trace + user/session/model/tokens/cost；当前已可面试展示 |

---

## 结语

这个项目不是把大模型接进电商系统，而是围绕企业 Agent 的核心矛盾做了一套工程化约束：需要推理但不能失控，需要灵活但不能越权，需要自然语言但数字必须有证据，需要快速迭代但质量必须可回归。

最终形成的，是一个面向真实业务的、**受限、可审计、可评测、可恢复的诊断 Agent**。

**当前状态**：研发完结、工程收尾。剩余工作不是"再加功能"，而是"补齐生产级证明 + 作品集证明"——nightly 全量评测、报告归档、案例化与讲稿化。详细收官计划见 `docs/HANDOFF-AI-v5.md` §13 与"收官总计划"（T1~T14）。
