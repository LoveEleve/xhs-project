# 参考：Pi Agent Harness（earendil-works/pi）

> 来源：https://github.com/earendil-works/pi | https://pi.dev | 86.7k star，MIT，TypeScript monorepo
> 记录：2026-08-10 | 定位：作为 my-xhs-ai **模块划分 / 可观测 / UI 层** 的架构参考
> 注意：这是**同类的 Agent 工具（如 opencode）**，不是北大 π 模型。两者不同。

---

## 一、它是什么
一句话：**Agent 工具包 = 统一 LLM API + Agent 运行时 + TUI + 编码 Agent CLI**。

| 包 | 作用 | 对应 my-xhs-ai |
|----|------|---------------|
| `pi-ai` | 统一多 Provider LLM API | Provider 抽象（模型接入） |
| `pi-agent-core` | Agent 运行时：工具调用 + 状态管理 | Agent Harness + Run/Step 状态 |
| `pi-telemetry` | **厂商中立 telemetry 契约 + 参考适配器 + 一致性测试** | OTel + trace + 契约测试 |
| `pi-coding-agent` | 交互式编码 Agent CLI | （我们是业务诊断 Agent，非编码） |
| `pi-tui` | 终端 UI（差分渲染） | UI 层参考 |

---

## 二、我们借鉴什么（3 点）

1. **模块划分干净（monorepo 分层）**：LLM 接入 / Agent 运行时 / telemetry / UI **彻底分离**。
   → 印证我们"Provider 抽象 / Harness / 工具层 / 可观测"分层的合理性。UI 是独立薄层，不侵入核心。

2. **telemetry 厂商中立 + 一致性测试**：
   - pi 专门做"**vendor-neutral 契约 + 参考适配器 + conformance tests**"。
   → 直接印证我们规划里 **OTel 统一 traceId/runId/stepId + 契约测试** 的做法（§4 Trace=OTel，D6 Langfuse）。
   - 启示：我们可参考它"**契约先行、多后端可替换**"的可观测设计，避免被单一平台锁定。

3. **TUI 差分渲染**：UI 只渲染变化部分，性能好。
   → 我们 Web UI 的流式（SSE）展示可用同样思路：**增量渲染 Agent 步骤**，而非整页刷新。

---

## 三、我们拒绝什么（关键安全差异 ⚠️）

**pi 没有内置权限系统**——它"默认以启动用户的权限运行，要更强边界就容器化/沙箱"。

这**与 my-xhs-ai 完全相反，绝不能照搬**：
- pi 面对的是**编码场景**（沙箱里跑用户自己的代码，可接受）。
- 我们面对的是**真实业务数据 + 运维观测数据**（订单/支付/库存/慢查询/死锁），用户是运营与技术，**必须 deny-by-default + L1/L2/L3 权限 + HITL 内建**，不能靠沙箱外包。
- 我们的安全护栏（注入/越权/PII/权限守卫）是**工具层内建**的，不是进程级沙箱。

> 结论：**借鉴它的模块/可观测/UI，但安全模型采用我们自己的 deny-by-default 内建方案**。

---

## 四、对 UI 层的具体启发（供 PLAN 补 UI 用）
我们最终也要一个类 opencode/pi 的交互界面（Web），展示：
- 流式输出（Agent 思考/工具调用过程实时滚动）
- 证据链 / 来源引用可点
- HITL 审批按钮（高危动作等人工确认）
- 权限/审计可见性

> UI 是 **API+SSE 的薄消费层**（D7 试点 / 或 D2 最小聊天壳），核心仍是后端。
