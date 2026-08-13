# 源码研究计划（AI 中间件 / Agent 框架）

> 版本：v0.1 | 日期：2026-08-10（GitHub 实测数据）
> 定位：规划对 Agent 生态的**源码研究**——用操作纪律"需求→自主设计→参考→对比"方法，服务我们的技术决策（D1/D2/D4/D5），不漫无目的读源码。
> 原则：① 只研"够当期决策用"的核心 ② 每框架有明确研究目标（解决我们哪个决策）③ 产出对照笔记/ADR ④ 源码 clone 到**工作区外**（`/data/tmp/opencode/`），不污染 git 仓库。

---

## 一、GitHub 实测清单（2026-08-10 核对）

| 仓库 | ⭐ | 语言 | 最近活跃 | 我们的角色 |
|------|:--:|:--:|:--:|------|
| `langchain4j/langchain4j` | 12.9k | Java | ✅ 2026-08-12 | **主线框架**（D1 建立其上）|
| `agentscope-ai/agentscope-java` | 5.0k | Java | ✅ 2026-08-12 | **验证替代**（D4 对比 PoC；深学计划见 `../../docs/phase-05-agent-architecture/README.md` 5-B）|
| `modelcontextprotocol/java-sdk` | 3.7k | Java | ✅ 2026-08-07 | **MCP 官方 SDK**（D2）|
| `langgraph4j/langgraph4j` | 1.9k | Java | ✅ 2026-08-12 | **Durable/工作流参考**（D5）|
| `temporalio/sdk-java` | 427 | Java | ✅ 2026-08-13 | D5 Durable 候选（触发才 PoC）|
| `alibaba/spring-ai-alibaba` | 10.6k | Java | ✅ 2026-08-10 | ⭐**新发现**：阿里 Java Agent 框架（关注/对照）|
| `spring-projects/spring-ai` | 挑战者 | Java | — | 因需 Boot 3.4 未选（懂为何即可）|
| **`earendil-works/pi`** | 86.7k | TS | ✅ | **开发用编码 Agent（对标 Claude Code，见 Phase-16）+ Agent Harness/事件/权限/TUI 深参考**（`../../09-reference/pi-reference.md` 已提取架构）|

> **pi agent 定位（本版补入）**：不止当"参考图"——pi 是与 Claude Code/Cursor 同类的**编码 Agent**，用于我们开发期（生成 MCP 骨架、Agent 代码、测试），属原规划 **Phase-16 AI 编程**（`../../docs/phase-16-ai-coding/README.md`）；同时深读其 `pi-agent-core`（工具调用+状态管理）与 `pi-telemetry`（厂商中立契约）作为 Harness 参考。
> **AgentScope 深学定位**：原规划 Phase-05 5-B 已有 6 篇源码深读计划（HarnessAgent/ReActAgent、三层记忆 Flush→Consolidation→Compaction、Skill 生命周期、权限三态+HITL、Plan Mode、28 事件、中间件 5 阶段）——直接复用该计划，不重复造。

> **新发现**：`spring-ai-alibaba` 是阿里新出的 Java Agentic 框架（10.6k⭐，活跃），与 AgentScope 同门——**列入 tech-selection 关注项**，D4 对比 PoC 时可加为观察对象。

---

## 二、研究顺序与目标（绑定期决策）

| 序 | 框架 | 研究目标（解决哪个决策）| 何时必须 | 阅读范围 |
|:--:|------|----------------------|:--:|------|
| 1 | **LangChain4j** | D1 基于它写代码：懂 Agent/AiServices/工具契约/RAG API 怎么组织 | **D1 前** | 核心模块（chat/agent/memory/tool/rag）+ 示例 |
| 2 | **MCP Java SDK** | D2 建 `my-xhs-ai-mcp`：懂 server 端工具注册/schema/传输 | **D2 前** | server 端 + 工具定义 + conformance |
| 3 | **AgentScope Java** | D4 对比 PoC：懂它的 Harness（权限/HITL/会话/沙箱）| **D4 前** | workflow/harness + 权限/HITL |
| 4 | **LangGraph4j** | D5 Durable：懂它的状态机/checkpoint/中断恢复 | **D5 前** | 状态/checkpoint/持久化 |
| 5 | **Temporal Java** | D5 候选：懂 workflow/replay/activity 模型 | 触发条件成立时 | 核心概念 + 示例 |
| 6 | **pi / spring-ai-alibaba** | 参考/对照：模块划分、UI、Agent 框架差异 | 参考 | 架构级 + 对照 |

---

## 三、每框架研究模板（产出格式）

```
**{框架}**
- 需求：解决我们哪个决策/问题
- 自主设计：我若实现会怎么做（先于看源码）
- 参考实现：框架怎么做（关键类/机制，file 或 package 级）
- 对比取舍：我的方案 vs 框架差异 + 为什么
- 结论：采用/借鉴/忽略 + 依据
```

> 产出：`09-reference/source-study/{framework}.md` 对照笔记 + 触发 ADR 更新。

---

## 四、待确认（动手前，按纪律盘问）

1. **clone 位置**：`/data/tmp/opencode/`（工作区外，推荐）——是否同意？
2. **clone 深度**：浅克隆（--depth 1）省空间，需要历史/分支时再补——是否同意？
3. **第一个研谁**：推荐 **LangChain4j**（D1 前必须，主线）——是否同意？
4. **每次推进量**：按纪律"一次一个框架、小步、交你 review"——是否同意？

---

## 五、关联
- 概念：`../02-plan/concepts-glossary.md`（LangChain4j/AgentScope/Spring AI/vLLM）
- 选型：`tech/tech-selection.md`（LangChain4j 主、AgentScope 验证替代）
- 参考：`09-reference/pi-reference.md`（pi 架构提取）
