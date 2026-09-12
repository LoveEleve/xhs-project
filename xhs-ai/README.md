# xhs-ai — AI 运维诊断与知识问答 Agent

> 全新设计与实现（不继承 `my-xhs-ai*` 旧代码，旧模块仅作参考/归档）
>
> 版本：v0.1（设计阶段）｜日期：2026-09-12

## 文档索引

| 文档 | 说明 | 状态 |
|------|------|------|
| `docs/00-context.md` | 背景、旧实现问题清单、术语、系统边界 | 草稿 |
| `docs/01-prd.md` | 需求与验收条件（AC） | v0.1 评审中 |
| `docs/02-architecture.md` | 架构设计（AgentScope 2.0 Java） | v0.1 评审中 |
| `docs/03-test-design.md` | 测试设计（全量 TC 矩阵/fixture/压测/红队） | ✅ v1.0 |
| `docs/04-engineering.md` | 工程规范（E1-E7 可执行版） | ✅ v0.1 |
| `docs/engineering/dependency-matrix.md` | 依赖矩阵与冲突处置（M1-1） | ✅ 2026-09-12 |
| `docs/resume-and-metrics.md` | 简历条目 + 指标口径与取证（面试防翻车） | ✅ v0.1 |
| `docs/research/01-data-source-capability.md` | 数据源能力矩阵（实测） | ✅ v0.1 |
| `docs/research/02-agentscope-production-deepdive.md` | AgentScope 2.0 生产化深读 | ✅ v0.1 |
| `docs/research/03-agent-project-references.md` | 5 个头部 Agent 项目设计参考（pi/deepseek-harness/opencode/Reasonix/hermes） | ✅ v0.1 |
| `docs/reviews/01-design-gap-review.md` | 查漏补缺 Review（R02 关闭 + 14 项缺口） | ✅ 2026-09-12 |
| `docs/reviews/02-code-level-agent-references.md` | 5 个 Agent 项目**代码级** Review（8 项设计增量 DELTA） | ✅ 2026-09-12 |
| `docs/reviews/03-plugin-ecosystem-mapping.md` | 插件生态映射 + 二轮缺口（G15-G20） | ✅ 2026-09-12 |
| `docs/reviews/04-engineering-gap-review.md` | **工程缺口 Review（30+ 项，P0 12 项）** | ✅ 2026-09-12 |
| `docs/requirements/01-scenario-library.md` | 业务场景库（28 场景 + REQ 编号 + 证据形态） | ✅ v0.1 |
| `docs/requirements/02-nfr-slo-threatmodel.md` | 量化 SLO + STRIDE 威胁模型 + REQ↔AC↔TC | ✅ v0.1 |
| `docs/requirements/03-legacy-asset-governance.md` | R05 旧资产治理清单与入库白名单 | ✅ v0.1 |
| `docs/design/01-model-gateway.md` | 专项：模型网关（路由/熔断/降级/预算/缓存友好） | ✅ v0.1 |
| `docs/design/02-tool-governance-approval.md` | 专项：工具治理与审批（策略/状态机/执行契约/三段式） | ✅ v0.1 |
| `docs/design/03-observability-compliance.md` | 专项：观测与合规（双平面/捕获模式/脱敏/成本） | ✅ v0.1 |
| `docs/design/04-code-navigation-lsp.md` | 专项：代码导航（LSP jdtls + JGit 双轨） | ✅ v0.1 |
| `docs/design/05-mcp-integration.md` | 专项：MCP 集成（白名单/隔离/治理） | ✅ v0.1 |
| `docs/design/06-failure-modes-fmea.md` | 专项：FMEA 失败模式（检测/降级/恢复/演练） | ✅ v0.1 |
| `docs/design/07-capacity-cost-model.md` | 专项：容量与成本模型（量化+阈值+局限） | ✅ v0.1 |
| `docs/design/08-extension-framework-and-ecosystem.md` | 专项：扩展框架 v1/v2 + 生态吸收规划 | ✅ v0.1 |
| `docs/design/09-feature-driven-ecosystem-adoption.md` | 专项：**功能驱动**的生态采纳（MCP 对标+翻译清单） | ✅ v0.1 |

## 技术选型（已确认）

| 项 | 选型 | 说明 |
|----|------|------|
| Agent 框架 | **AgentScope 2.0 Java**（HarnessAgent） | JDK 17；内置 workspace/memory/skill/subagent/HITL/分布式状态 |
| LLM | **siyu-all 网关**（`https://siyu.site/v1`，OpenAI 兼容） | `deepseek-v4-pro` 主模型 / `deepseek-v4-flash` 轻量任务 |
| Embedding | **火山方舟 Agent Plan**（`doubao-embedding-vision-large`，2048 维） | ✅ 已验证（HTTP 200/0.35s/2048 维） |
| 存储 | MySQL（xhs 主库只读 + `my_xhs_ai` 业务库）/ Redis / ES | 复用现有中间件 |
| 可观测 | OpenTelemetry → Langfuse | AgentScope 内置 OTel 埋点 |
| 构建 | Maven + JUnit 5（对齐 xhs 主工程） | CI：单元测试 + 静态检查 + 契约测试 |

## 状态

- [x] 技术选型确认
- [x] 模型接入验证（siyu-all LLM ✅ / 火山方舟 Embedding ✅）
- [x] P1 深调研：数据源能力矩阵 / AgentScope 生产化深读
- [x] P1 深调研：5 个头部 Agent 项目设计参考（R03）
- [x] 源码级核验（AgentScope jar 6 项全关闭）+ 设计查漏补缺 (RV01)
- [x] P1 深调研：业务场景库（RQ01，28 场景）
- [x] P1 深调研：旧资产治理清单（R05）
- [x] 工程规范 04-engineering（E1-E7）
- [x] P4 全量测试设计（TC 矩阵 v1.0）
- [x] M1-1 依赖收敛验证（enforcer 三规则全过）
- [ ] M1-2 骨架（Application/Actuator/Flyway/Logback JSON）→ 对话/SSE → 状态 → ELK/Prom
- [x] P2 需求工程：SLO/STRIDE/追溯矩阵（RQ02）
- [ ] P2 评审（AC 可测试）
- [x] P3 架构 v0.2（折叠全部采纳项）
- [x] P3 专项设计 9/9（+ 功能驱动生态采纳）
- [ ] M1.5 官方 MCP 接入验证（ES/Prometheus/Grafana）
- [x] 多副本 HITL 跨实例恢复设计（D02 §10）
- [ ] 架构设计评审（02）
- [ ] 测试设计评审（03）
- [ ] 测试设计评审
- [ ] 工程骨架（M1）
