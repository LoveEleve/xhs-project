# 深度规划 v2：探索式演进（业界对照版）

> 日期：2026-08-15 | 前置：current-state-v1.md（现状基线）、roadmap-m10-m14.md（范围）、design-m10-conversation.md（M10 设计）
> 方法：**先对照业界权威实践（Anthropic Building Effective Agents），再定我们的演进路径**——不是拍脑袋列功能，而是每个里程碑都能指出对应业界模式与理由。

---

## 一、业界模式对照（Anthropic Building Effective Agents，2024-12）

Anthropic 的核心框架：**Workflows（预定义路径）vs Agents（模型自主决策）**，五种模式 + 三原则。

| 业界模式 | 定义 | 我们的现状 | 差距/行动 |
|---------|------|:--:|------|
| **Routing**（路由分类→专精下游）| 分类输入→分流到专精任务 | ✅ 三阶路由（L0/L1/L2/L3）| 已是最贴合模式；Anthropic 提到路由**兼做成本优化**（简单→小模型）→ 模型分层（M15 解锁点）|
| **Agent**（增强 LLM 循环）| 模型基于环境反馈（工具结果）自主决策循环 | ✅ AgentHarness 全状态机 | 符合"ground truth from environment"原则；三原则的 ACI 深度待加强（工具文档/测试）|
| **Prompt chaining**（链式门禁）| 固定步骤链 + 程序化 gate | 🟡 存在性校验=gate 的雏形 | M14 evaluator 化后可组合 |
| **Orchestrator-workers**（动态拆解）| 中央 LLM 动态拆任务→工人→汇总 | ❌ 未做 | **M13 多智能体的正确形态**——不是"多个 Agent 互相聊天"，是 orchestrator 拆解+汇总 |
| **Evaluator-optimizer**（生成-评估循环）| 一个 LLM 生成，另一个评估反馈 | 🟡 存在性校验/门禁是规则评估 | **M14 LLM-as-judge = evaluator 模式落地** |
| **Parallelization**（并行/投票）| 独立子任务并行 / 同任务多次投票 | ❌ 未做 | 评测集自动化（并行评估各维度）可借鉴 |
| 三原则①**Simplicity** | 只有复杂度带来可验证收益才加 | ✅ 单 Agent 克制/决策点机制 | 继续 |
| 三原则②**Transparency** | 显式展示 agent 的规划步骤 | 🟡 SSE 事件流有 THINK/TOOL 展示 | M10 会话后补"上轮结论承接"可见性 |
| 三原则③**ACI**（Agent-Computer Interface）| 工具定义/文档/测试投入 = HCI 同级 | 🟡 工具 schema 有，example/edge case 文档缺 | **M12 工具注册表 = ACI 中心化载体**：schema+示例+边界+测试 |

> 结论：我们的技术路线（Routing + Agent + 门禁）与 Anthropic 推荐的主流模式高度一致；缺的是 **Orchestrator-workers（M13）、Evaluator（M14）、ACI 深度（M12）**——恰好是 M10-M14 的范围，方向得到业界印证。

---

## 二、目标架构演进路径（现状 → 企业级 Agent 平台）

```
现状（2026-08-15）                     M10-M12 后                       M13-M14 后（目标）
┌─────────────┐                    ┌─────────────┐                  ┌──────────────────┐
│ 单轮问题     │                    │ 多轮会话     │                  │ Orchestrator     │
│ → 路由 →    │                    │ → 会话+记忆  │                  │ （任务理解/拆解） │
│ → Harness   │                    │ → Harness   │                  │  ├─ 业务诊断 Agent│
│ → 单 Agent  │                    │ → 工具注册表 │                  │  ├─ 排障 Agent    │
└─────────────┘                    │ → HITL 审批  │                  │  └─ 日志 Agent    │
                                   └─────────────┘                  │      （共享引擎） │
                                                                    │ → Evaluator 评估  │
                                                                    │ → bad case 回流   │
                                                                    └──────────────────┘
关键不变式：Harness 引擎（状态机/预算/证据链/校验）跨 Agent 复用——多 Agent ≠ 重写，是参数化
```

## 三、每个里程碑的深度设计决策（业界对照 + 理由）

### M10 会话与记忆（对应：Agent 三原则②Transparency + context engineering）
- **决策**：多轮上下文注入 = 恢复历史消息（最近 20 轮）+ 会话摘要 SystemMessage（注入优先级：摘要<历史<当前）
- **业界理由**：Anthropic"agent 从与人类交互开始，明确任务后独立执行，可回到人类获取判断"——多轮是 Agent 的天然形态；上下文工程（context engineering）是业界 2025 年后重点
- **风险**：历史膨胀 → 摘要压缩；跨轮引用编造 → 上轮结论为模型可见文本（不强制校验，文档说明）
- **验证方式**：多轮归因评测用例（Q2 引用 Q1 结论）+ 摘要注入日志可查

### M11 HITL 审批（对应：Agent"pause for human feedback at checkpoints"）
- **决策**：WAITING_APPROVAL 状态 + approve 端点 + dlq.redeliver 受控执行（命令模板写死+参数白名单+审批门）
- **业界理由**：Anthropic 明确"agents can pause for human feedback at checkpoints or when encountering blockers"——HITL 是业界认可的 Agent 标准能力而非可选
- **风险**：挂起 run 的资源占用/超时；审批并发；dlq.redeliver 的真实副作用（幂等性）
- **验证**：红队（无审批不可执行）、审批→执行→证据登记、拒绝→终止+审计

### M12 工具注册表（对应：ACI 原则）
- **决策**：ToolRegistry（name/description/schema/accessLevel/invoker）+ PolicyGuard 读注册表元数据 + MCP 列表从注册表导出
- **业界理由**：Anthropic"carefully craft your ACI through thorough tool documentation and testing"——工具是 Agent 与环境的接口，注册表是 ACI 治理的载体
- **风险**：重构回归（3 硬编码接口→注册表）；schema 与 LLM 调用的一致性
- **验证**：注册表驱动后 192 测试绿；新增工具 30 分钟接入（演示案例）

### M13 多智能体（对应：Orchestrator-workers）
- **决策**：先 PoC **Routing 分流版**（业务诊断 Agent/排障 Agent/日志 Agent 按意图分流，共享 Harness 引擎）→ 评测对比单 Agent → 数据决定是否升级 Orchestrator-workers
- **业界理由**：Anthropic 区分 parallelization（预定义并行）vs orchestrator-workers（动态拆解）——**我们应先做最简单的 routing 分流**，只有评测证明需要才上 orchestrator（Simplicity 原则）
- **关键设计**：多 Agent ≠ 多套代码——Harness 参数化（工具集/预算/prompt/权限级）；Orchestrator 只是另一层决策（如果要做）
- **验证**：双 Agent 分流正确性（业务问题→业务 Agent、排障问题→排障 Agent）+ 对比评测报告

### M14 评测闭环（对应：Evaluator-optimizer + Parallelization）
- **决策**：评测集 20→100+（9 层→12 层：+multi-turn/+memory/+hitl）；LLM-as-judge（质量分 0-5 + 证据引用检查）；bad case 回流（真实 run 差回答自动入库评测集）
- **业界理由**：evaluator-optimizer 模式 = 生成+评估反馈循环；parallelization 建议"automating evals where each LLM call evaluates a different aspect"——judge 并行评估多维度
- **风险**：judge 本身不可靠（校准：与规则断言交叉验证）；回流 case 的质量
- **验证**：judge 分数与规则断言一致性（抽样人工复核）；100+ 用例门禁绿

## 四、探索项（决策点，不急于定）

| 探索 | 状态 | 触发条件 |
|------|:--:|------|
| Langfuse（LLM 可观测，Transparency 强化）| ❌ | M14 评测闭环后（有真实评测数据才值得可视化）|
| Temporal（Durable Workflow）| ❌ | 触发条件：HITL 挂起/恢复出现状态一致性问题时 |
| 模型分层 routing（Routing 的成本维度）| ❌ 冻结 | 成本数据积累后（M15，先保持单模型评测一致性）|
| Evaluator 上生产（生成-评估循环用于 Agent 自校正）| ❌ | M14 judge 稳定后评估 |

## 五、执行纪律与验收

- **每步小步**：M10 → M11 → M12 → M13 → M14，每步深度 review + 192 测试回归 + 真实 E2E
- **每个里程碑验收门禁**：见 roadmap-m10-m14.md §四（已定义）
- **评测贯穿**：M14 的 bad case 回流机制从 M10 起边做边积累
- **不盲目开工**：每个里程碑先出详细设计（如 design-m10-conversation.md）→ 评审 → 动工

## 六、与面试叙事的关系

- 业界对照本身就是面试弹药："我们的路由=Anthropic Routing 模式、HITL=checkpoint pause、多 Agent=orchestrator-workers 的克制演进、评测=Evaluator 模式"
- 从"我做了个 Agent"→"**我按 Anthropic 模式构建了一个 Agent 平台，并知道每一步为什么**"——这是大厂 Agent 平台岗的核心叙事
