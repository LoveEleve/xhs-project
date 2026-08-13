# D0 技术核验（模型接入 + 框架版本 + 首批 ADR）

> 目标：回答 D1 的三个卡点——① 模型接入现状 ② 框架/版本兼容性 ③ 首批 ADR。
> 状态：2026-08-10 实测。所有版本在真正引入时须再次核验（PLAN v6 原则）。

---

## 1. 模型接入现状 —— ✅ 已打通（D1 卡点解除）

> ⚠️ **2026-08-10 更新：Provider 已切换 火山方舟 → TeamoRouter**（用户指定，用免费档试跑）。
> 两者均为 OpenAI 兼容端点，LangChain4j `OpenAiChatModel` 仅改 baseUrl/model/key 即可切换。

### 1.1 当前生效接入信息（TeamoRouter，2026-08-10 实测）
| 项 | 值 |
|----|-----|
| Provider | TeamoRouter（多模型网关） |
| Base URL（OpenAI 兼容）| `https://api.teamorouter.com/v1` |
| 认证 | `Authorization: Bearer`，key 走环境变量 `TEAMO_API_KEY`（不落库） |
| 当前模型 | **`deepseek-v4-flash`（付费档，2026-08-10 充值后切换）** |
| 历史 | ~~`deepseek-v4-flash-free`~~（免费档，工具调用可靠性不稳——曾出现虚构工具调用，见 `08-execution/d2/D2-status-blocker.md` §三第四轮）|

> 已实测：chat / 结构化输出 / 工具循环 / 流式 **4 项全过**（`labs/d1-skeleton/`，探针见 `D1-skeleton-verification.md`）。

### 1.2 历史接入（火山方舟，已切换，保留备查）
| 项 | 值 |
|----|-----|
| Base URL | `https://ark.cn-beijing.volces.com/api/v3` |
| 推理接入点 | `ep-20260810195949-k9lkt`（绑定 `deepseek-v4-flash-260425`） |
| 认证 | `ARK_API_KEY` 环境变量（不落库） |

> 切换验证：`my-xhs-ai-app` 用 TeamoRouter 启动成功，`/api/ai/chat` 返回真实模型回复；免费档足够 D1 开发期使用。

### 1.3 经验记录（避免再踩）
- `models` 列表是**平台全量目录**，≠ 账号已开通；不可用的返回 `NotFound`，存在但未开通的返回 `ModelNotOpen`。
- 本账号**不可用** seed-1-6/pro-*/deepseek-v3；**可开通/可用** seed-2-0/2-1、deepseek-v4 系列。
- **正规且最稳的用法是"推理接入点(ep-xxx)"**，直接用 ep- ID 调，绕开"模型 ID 是否开通"的纠结。
- TeamoRouter 免费档 `deepseek-v4-flash-free`：DeepSeek 模型走 **OpenAI 兼容格式**（`/v1/chat/completions`），Claude 才需 Anthropic 原生格式（`/v1/messages`）。

---

## 2. 框架/版本兼容性核验项（D1 引入前逐一执行）

> ✅ **2026-08-10 已用 `labs/d1-skeleton/` 实证 LangChain4j 1.0.0**（报告见 `D1-skeleton-verification.md`）：
> - LangChain4j 1.0.0 + JDK17 **独立工程可编译可运行**；4 项能力（chat/结构化/工具循环/流式）**全部实证通过**。
> - ⚠️ 1.0 API 大改：`ChatModel`/`ChatRequest`/`StreamingChatResponseHandler`；`@Tool` 在 core、`AiServices` 在聚合模块。
> - ⏳ 待验：Spring Boot 3.2.5 starter 集成 + MCP jackson2 依赖树（建 app/mcp 模块时做）。

> 以下为其余核验项与命令（不承诺具体版本号，防止过时）：

| 核验项 | 关键点 | 检查命令/方法 |
|--------|--------|--------------|
| ~~LangChain4j vs Boot 3.2.5/JDK17~~ | 已实证独立运行；Boot 集成待验 | 建 my-xhs-ai-app 时跑 `mvn dependency:tree` + release notes |
| AgentScope Java 2.0 | 需 JDK17+；模型模块化 | 查 io.agentscope 最新版；注意 2.0.1 是否支持 Boot 3.2 |
| MCP Java SDK | 必须用 **jackson2** 模块(mcp-json-jackson2) | 查 io.modelcontextprotocol 版本 |
| Jackson 2.16.1 冲突 | 项目 Jackson 2，避免引 Jackson 3 | 依赖树检查 `mvn dependency:tree` |
| RocketMQ/Redis/ES 版本 | AI 服务复用现有中间件 | 用项目 BOM 对齐 |
| Langfuse 自托管 | Docker 部署 | 查最新镜像 tag |

> 落地方式：建立 `my-xhs-ai` 聚合模块后，`mvn dependency:tree` + 官方兼容性报告逐项核对，产出**兼容性报告**作为 ADR 附录。

---

## 3. 首批 ADR（已提取到 `../decisions/`）
> ADR 独立成册（会随开发增长）：见 `../decisions/`。

| ADR | 内容 | 状态 |
|-----|------|:--:|
| [ADR-001](../decisions/ADR-001.md) | 模型 Provider=火山方舟 | ✅ 已定 |
| [ADR-002](../decisions/ADR-002.md) | Agent 框架=LangChain4j 主 | ✅ 已定 |
| [ADR-003](../decisions/ADR-003.md) | MCP=官方 SDK jackson2 | ✅ 已定 |
| [ADR-004](../decisions/ADR-004.md) | Durable=V1 本地状态机 | ✅ 已定 |

---

## 4. D0 技术核验小结
| 项 | 状态 |
|----|------|
| 模型接入 | ✅ **已打通**（火山方舟 ep-20260810195949-k9lkt，实测可用） |
| 模型能力（chat/结构化/工具调用/流式）| ✅ **已实证**（`labs/d1-skeleton/` 4 探针全过，见 `D1-skeleton-verification.md`）|
| 框架版本 | ✅ LangChain4j 1.0.0 + JDK17 独立跑通；⏳ Boot 3.2.5 集成 + MCP jackson2 待验 |
| 首批 ADR | ✅ ADR-001~004 已定稿（见 ../decisions/） |

> **下一步动作**：① 用 Spring Boot 3.2.5 建 `my-xhs-ai-app`（A1 补齐）② 建 MCP 模块验依赖树 ③ 真实指标工具接数。
