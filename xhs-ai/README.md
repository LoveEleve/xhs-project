# xhs-ai — AI 运维诊断与知识问答 Agent

> 全新设计与实现（不继承 `my-xhs-ai*` 旧代码，旧模块仅作参考/归档）
>
> 版本：**v1.0（结项）**（M1~M4 全量交付；RV01~RV20 闭环）｜日期：2026-09-14｜结项报告：`docs/reviews/20-project-closure.md`

## 文档索引

| 文档 | 说明 | 状态 |
|------|------|------|
| `docs/00-context.md` | 背景、旧实现问题清单、术语、系统边界 | 草稿 |
| `docs/01-prd.md` | 需求与验收条件（AC） | v0.2（RV09 修订） |
| `docs/02-architecture.md` | 架构设计（AgentScope 2.0 Java） | v0.2（RV09 修订） |
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
| `docs/reviews/05-consistency-and-mcp-risks.md` | RV05：一致性修正 9 处 + MCP 工具膨胀风险 | ✅ 2026-09-12 |
| `docs/reviews/06-deep-review-business-and-jd.md` | **RV06：业务贴合度 + JD 对齐深度 Review（P0/P1 清单 + 规划修订）** | ✅ 2026-09-12 |
| `docs/reviews/07-jd-hit-matrix-v2.md` | JD 命中矩阵 v2（18 份 JD × xhs-ai v2 现状） | ✅ 2026-09-12 |
| `docs/reports/m2.0-dlq-e2e.md` | **M2.0 业务竖切① E2E 记录（DLQ 诊断→审批→重投→核验→审计）** | ✅ 2026-09-12 |
| `docs/reports/kb-eval-20260913.md` | **KB 检索评测（30 条，hit@1=100%，门禁通过；向量不启动）** | ✅ 2026-09-13 |
| `docs/reports/answer-eval-2026-09-13.md` | **答案级评测（50 用例全过；引用有效性 100%；含失败归因）** | ✅ 2026-09-13 |
| `docs/reports/cost-week-2026-09-13.md` | **成本周（N=100 轻量 + 10 诊断；单次诊断 ≈¥0.012–0.048）** | ✅ 2026-09-13 |
| `eval/kb-cases.yaml` | KB 评测集（30 条，问题→期望卡片） | ✅ 2026-09-13 |
| `eval/answer-cases.yaml` | 答案级评测集（50 条：KB30/DIAG15/SEC5） | ✅ 2026-09-13 |
| `docs/reports/m4-security-and-metering-20260913.md` | **M4 证据：红队 8 项 + Token 计量（成本口径）** | ✅ 2026-09-13 |
| `docs/reports/mttr-raw-20260913/` | MTTR 原始数据（10 案例请求/响应 + 耗时 TSV） | ✅ 2026-09-13 |
| `docs/reports/mttr-benchmark-20260913.md` | **MTTR 对照评测（10/10 案例；Agent 1.63min，降幅 92.1% 保守下界）** | ✅ 2026-09-13 |
| `docs/reports/load-test-20260913.md` | **M4 压测（C=5：100/100，P50 13.5s/P95 39.0s/P99 46.5s；C=20 超载降级记录）** | ✅ 2026-09-13 |
| `docs/reports/live-drill-20260915.md` | **AI 实测：真实流量+构造数据（DLQ/日志/指标/HITL 重投/消费积压/全链路下单）** | ✅ 2026-09-15 |
| `docs/reports/tool-selection-eval-20260915.md` | **工具选择评测 12/12；预算默认值按实测校准 20万→50万** | ✅ 2026-09-15 |
| `docs/reports/trajectory-eval-20260915.md` | **轨迹评测（部分分）+ 稳定性 0.917；审批执行崩溃恢复/回收 + 运行指标** | ✅ 2026-09-15 |
| `docs/reviews/08-m2.0-expert-review.md` | **RV08：M2.0 专家评审（harness-skills expert-reviewer 双轴）** | ✅ 2026-09-12 |
| `docs/reviews/09-tech-necessity-review.md` | **RV09：技术必要性审查（RAG 争论 → 不建朴素 RAG；每项技术的触发/止损）** | ✅ 2026-09-12 |
| `docs/reviews/10-full-dimension-review.md` | **RV10：全维度深度 Review（代码 P0×2 修复 / settlement 异步闭环 / 文档回写）** | ✅ 2026-09-12 |
| `docs/reviews/11-cart-dlq-rootcause-fix.md` | **RV11：cart 事件流水 DLQ 根因修复（CLEAR/CHECK_ALL 无 SKU + 测试漏检复盘 + 四道门禁）** | ✅ 2026-09-13 |
| `docs/reviews/12-search-dlq-rootcause-fix.md` | **RV12：search 两组历史 DLQ 排查（环境期失败）+ 韧性缺陷修复（版本冲突幂等/不可重试分类）** | ✅ 2026-09-13 |
| `docs/reviews/13-m3-knowledge-progress.md` | **RV13：M3 知识层进展（54 卡入 BM25/Top1 命中）+ 模型网关稳定性评估** | ✅ 2026-09-13 |
| `docs/reviews/14-model-gateway-and-kb-e2e.md` | **RV14：D01 模型网关（重试/熔断/降级/指标）+ 知识问答端到端打通** | ✅ 2026-09-13 |
| `docs/reviews/15-m3-knowledge-retrieval-complete.md` | **RV15：M3 知识检索闭环（code_locate + 主干完成清单）** | ✅ 2026-09-13 |
| `docs/reviews/16-full-dimension-review.md` | **RV16：全维度深度 Review（8 个 P1 修复：IDOR/熔断/降级/缓存/守卫）** | ✅ 2026-09-13 |
| `docs/reviews/17-m2x-approval-and-settlement.md` | **RV17：M2.x 审批超时 fail-closed + 跨实例决策事件 + 消费位点核验（实测）** | ✅ 2026-09-13 |
| `docs/reviews/18-rv18-deep-review.md` | **RV18：四路深审 + P0/P1 修复（重投双 ID 匹配、队列级位点、执行 CAS、诊断鉴权、事件总线落地）** | ✅ 2026-09-13 |
| `docs/reviews/19-m4-fmea-drills.md` | **RV19：M4 FMEA 演练 4 项 + Agent→ES MCP 权限挂起 P0 修复（MTTR 10/10）** | ✅ 2026-09-14 |
| `docs/reviews/20-project-closure.md` | **RV20：v1.0 结项（门禁闭环/交付物/显式边界/可选后续）** | ✅ 2026-09-14 |
| `docs/requirements/01-scenario-library.md` | 业务场景库（34 场景 + REQ 编号 + 证据形态） | ✅ v0.2 |
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
| `docs/design/12-retrieval-and-knowledge.md` | 专项：检索与知识（Agentic Retrieval，非默认 RAG） | ✅ v0.1 |

## 运行状态（2026-09-13，M1~M4 主体完成）

- 服务：`xhs-ai` 由 systemd 托管（`xhs-ai.service`，Restart=always），19020 health UP，Flyway v1，JSON 日志（含 traceId）入 ELK，`/actuator/prometheus` 可用；Nacos 注册 + 网关 `/api/ai/**` JWT 路由（SSE 31min）；审批位点诊断端点仅管理/内部令牌可用（未授权 401）。
- 鉴权：平台信任模型（内部/管理令牌/access JWT 覆盖 X-User-Id），未认证 401；入参校验与错误脱敏。
- MCP：ES/Prometheus/Grafana 三 server；Prometheus 17 个只读工具白名单（Agent 侧），web 监听随机本机端口。
- **M2.0 业务竖切①**：DLQ 诊断→审批→重投→核验→审计端到端跑通（Agent 自主编排 3 自研工具 + MCP；未审批不执行；审批后自动 effect+settlement；`ai_audit/ai_approval/ai_message` 全程落库）。证据：`docs/reports/m2.0-dlq-e2e.md`。
- M2.x 已交付（RV17）：超时 fail-closed、跨实例 pub/sub、队列级消费位点核验（RV18 修复）、诊断端点。
- 下一步：M4 剩余（FMEA 演练 4-6 项）。压测与工具预算护栏已交付。

## 技术选型（已确认）

| 项 | 选型 | 说明 |
|----|------|------|
| Agent 框架 | **AgentScope 2.0 Java**（HarnessAgent） | JDK 17；内置 workspace/memory/skill/subagent/HITL/分布式状态 |
| LLM | **siyu-all 网关**（`https://siyu.site/v1`，OpenAI 兼容） | 双通道：聊天 `deepseek-v4-pro` / Agent 工具循环 `qwen3.8-flash`（tool_calls 最稳）/ 降级 `deepseek-v4-flash` |
| Embedding | **火山方舟 Agent Plan**（`doubao-embedding-vision-large`，2048 维） | ✅ 已验证（HTTP 200/0.35s/2048 维）；**封存**：BM25 hit@1=100% 达门槛，按 RV09 止损规则不启用向量 |
| 存储 | MySQL（xhs 主库只读 + `my_xhs_ai` 业务库）/ Redis / ES | 复用现有中间件 |
| 可观测 | OpenTelemetry → Langfuse | AgentScope 内置 OTel 埋点 |
| 构建 | Maven + JUnit 5（对齐 xhs 主工程） | CI：单元测试 + 静态检查 + 契约测试 |

## 状态

- [x] 技术选型确认
- [x] 模型接入验证（siyu-all LLM ✅ / 火山方舟 Embedding ✅）
- [x] P1 深调研：数据源能力矩阵 / AgentScope 生产化深读
- [x] P1 深调研：5 个头部 Agent 项目设计参考（R03）
- [x] 源码级核验（AgentScope jar 6 项全关闭）+ 设计查漏补缺 (RV01)
- [x] P1 深调研：业务场景库（RQ01，34 场景）
- [x] P1 深调研：旧资产治理清单（R05）
- [x] 工程规范 04-engineering（E1-E7）
- [x] P4 全量测试设计（TC 矩阵 v1.0）
- [x] M1-1 依赖收敛验证（enforcer 三规则全过）
- [x] M1-2 骨架（Application/Actuator/Flyway/Logback JSON）+ 对话/SSE（会话状态/ELK/Prom 已补）
- [x] M1.5 官方 MCP 接入验证（ES/Prometheus/Grafana tools+call 实测通）
- [x] RV06 业务贴合度 + JD 对齐深度 Review（P0/P1 清单 + 规划修订 + JD 矩阵 v2）
- [x] RV08/RV09/RV10 评审闭环（专家评审/技术必要性/全维度）+ settlement 异步核验 + 审批事务化
- [x] M3 知识层增量：55 张卡片（54+infra-anchors）迁移+catalog+ES BM25+knowledge_* 工具+MCP 白名单（RV13/RV18）
- [x] D01 模型网关（ModelGateway：传输重试/熔断/降级/指标）+ Agent 知识问答 E2E（RV14；测试累计 22/22）
- [x] M3 KB EVAL：30 条（10/10/10）hit@1=100% 门禁通过；55 卡复跑答案级 30/30、引用 100%（RV18）；向量实验按 RV09 止损规则不启动
- [x] M3 code_locate v1（文件:行号 引用）+ 知识检索主干闭环（RV15）
- [x] M4 启动：红队 8 项全拦截 + Token 计量落点（Prometheus）+ 双 MCP 端口冲突修复
- [x] MTTR 对照：10 案例实测（10/10 证据完整；Agent 均值 1.63min；保守降幅 92.1%；案例 10 缺陷已于 RV19 修复复测）
- [x] 答案级评测：KB30+DIAG15+SEC5 共 50 条全过，引用有效性 100%（报告+原始 JSON 入库）
- [x] 成本周：单次诊断 18.3k in / 1.4k out tokens（≈¥0.012–0.048），较全量直塞降幅 ~74%（估算）
- [x] M2.x：审批超时 fail-closed + 跨实例 pub/sub 决策事件 + 队列级消费位点核验（RV17 交付，RV18 修正；22/22 单测）
- [x] RV18 四路深审：文档口径/SRE 运行态/跨项目一致性/代码第四轮 + P0/P1 修复闭环
- [x] M4 压测 N=100（C=5 全成，P50/P95/P99 入库）+ 工具预算护栏（软32/硬40，Gauge `ai_agent_tools_total`）
- [x] 按用户 token 预算（F13）：真实 usage 计量 + 软限切轻量 + 硬限 429；工具 schema token 指标（RV24）
- [x] 生产化：并发护栏（2/8）、保留清理 Job、7 条告警规则、门禁脚本+CI（RV25/RV26）
- [x] F7 会话重建 + 自研兜底工具（MCP 全挂仍可诊断）（RV26）
- [x] RBAC（JWT role claim）收敛管理端点 + 去 MCP 化（Agent 16 全自研工具，MCP 仅运维直连）（RV27）
- [x] M4 FMEA 演练 4 项（停 ES / Redis failover / 滚动重启 / kill MCP）→ 修复 **Agent→ES MCP 工具挂起 P0** + 交付 **MCP 进程级自愈**（RV19）
- [x] M1.6 安全与接线（鉴权/工具白名单/Nacos+网关/systemd/traceId/旧模块下线）✅ 2026-09-12
- [x] M2.0 业务竖切①：DIAG-08+OPS-01 DLQ 诊断→审批→重投→核验→审计 ✅ 2026-09-12（E2E 见 docs/reports/m2.0-dlq-e2e.md）
- [x] P2 需求工程：SLO/STRIDE/追溯矩阵（RQ02）
- [x] P2 评审（AC 可测试）→ 由 RQ01/RQ02 + RV06/RV09/RV10 覆盖（RV20 结项）
- [x] P3 架构 v0.2（折叠全部采纳项）
- [x] P3 专项设计 9/9（+ 功能驱动生态采纳）
- [x] RV05 一致性查漏补缺（9 处修正 + 工具集膨胀设计）
- [x] 多副本 HITL 跨实例恢复设计（D02 §10）
- [x] 架构设计评审（02）→ RV09 修订 + RV10 全维度评审（RV20 结项）
- [x] 测试设计评审（03）→ TC 矩阵 + 答案级评测/红队/压测/FMEA 实证（RV20 结项）
- [x] 工程骨架（M1）验收 → 运行态实证（RV20 结项）
