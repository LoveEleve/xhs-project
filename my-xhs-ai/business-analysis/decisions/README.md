# ADR（架构决策记录）索引

> 记录 AI 项目的关键技术决策。每项含：状态 / 决策 / 理由 / 替代 / 退出条件。
> 新决策随开发增长，按序新增。

| ADR | 主题 | 状态 |
|-----|------|:--:|
| [ADR-001](ADR-001.md) | 模型 Provider = 火山方舟(ep-...-k9lkt, deepseek-v4-flash) | ✅ 已定 |
| [ADR-002](ADR-002.md) | Agent 核心框架 = LangChain4j 主（AgentScope 验证替代） | ✅ 已定 |
| [ADR-003](ADR-003.md) | MCP = 官方 SDK + jackson2 模块 | ✅ 已定 |
| [ADR-004](ADR-004.md) | Durable Workflow = V1 本地状态机 + MySQL | ✅ 已定 |

> 命名规范：`ADR-序号-主题.md`，状态用 ✅已定 / 🟡提议 / ❌拒绝 / ↩替代。
