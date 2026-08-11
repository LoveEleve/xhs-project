# D0 技术核验（模型接入 + 框架版本 + 首批 ADR）

> 目标：回答 D1 的三个卡点——① 模型接入现状 ② 框架/版本兼容性 ③ 首批 ADR。
> 状态：2026-08-10 实测。所有版本在真正引入时须再次核验（PLAN v6 原则）。

---

## 1. 模型接入现状 —— ✅ 已打通（D1 卡点解除）

### 1.1 最终接入信息（2026-08-10 实测可用）
| 项 | 值 |
|----|-----|
| Provider | 火山方舟（Volcano Ark） |
| Base URL | `https://ark.cn-beijing.volces.com/api/v3` |
| 推理接入点 | `ep-20260810195949-k9lkt` |
| 绑定模型 | `deepseek-v4-flash-260425` |
| 认证 | ARK key（环境变量 `ARK_API_KEY`，不落库） |

> 已实测：chat/completions 返回正常（`deepseek-v4-flash-260425`，content 有效）。**D1 模型卡点解除。**

### 1.2 经验记录（避免再踩）
- `models` 列表是**平台全量目录**，≠ 账号已开通；不可用的返回 `NotFound`，存在但未开通的返回 `ModelNotOpen`。
- 本账号**不可用** seed-1-6/pro-*/deepseek-v3；**可开通/可用** seed-2-0/2-1、deepseek-v4 系列。
- **正规且最稳的用法是"推理接入点(ep-xxx)"**，直接用 ep- ID 调，绕开"模型 ID 是否开通"的纠结。

### 1.3 后续模型选型（D3/D4 再开对应接入点）
| 用途 | 候选 | 何时 |
|------|------|------|
| 主对话/Agent | `deepseek-v4-flash-260425`（已用） | D1 起 |
| 推理 | `doubao-seed-2-x-thinking` / `deepseek-r1` 系 | D4 排障 |
| RAG embedding | `doubao-embedding-*`（需另建接入点） | D3 检索 |

---

## 2. 框架/版本兼容性核验项（D1 引入前逐一执行）

> 版本以 Maven Central 实测为准，此处列核验项与命令，不承诺具体版本号（防止过时）。

| 核验项 | 关键点 | 检查命令/方法 |
|--------|--------|--------------|
| LangChain4j vs Boot 3.2.5/JDK17 | 框架层 Spring 无关；starter 兼容性 | 查 Maven Central 最新版 + release notes |
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
| 框架版本 | ⏳ 引入前执行 §2 核验项 + 兼容性报告 |
| 首批 ADR | ✅ ADR-001~004 已定稿（见 ../decisions/） |

> **下一步动作**：① 建 `my-xhs-ai` 聚合模块 ② 跑依赖树核对版本兼容 ③ 进入 D1 最小切片。
