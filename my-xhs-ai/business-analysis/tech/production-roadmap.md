# 生产级路线图（my-xhs-ai → Production）

> 版本：v2.0 | 日期：2026-08-14 | 归属：`my-xhs-ai` 核心
> 定位：把 D1-D4 已交付的"能力验证级"系统推进到**生产级**的完整规划——里程碑、技术栈、难点、解决方案、验收锚点。
> 前置：PLAN-v6（D5-D7 阶段定义）、design-agent-harness.md（Harness 详细设计）、HANDOFF-AI-v1.md。
> 状态：D1-D4 核心已交付（128 测试绿）；本规划覆盖 D5-D7 及生产深化（M5-M9）。

---

## 0. 定位与三条铁律

**定位**：企业级运营+运维诊断 Agent——受限、可审计、可评测、可回滚。不是个人助手，不采用 opencode/pi 作底座（ADR-005 安全模型冲突）。

**铁律**（贯穿所有阶段）：
1. 模型只能调固定工具，永不直接执行任意代码/SQL/PromQL（CodeAct 红线，`design-agent-harness.md` 非目标 + PLAN §1.4）
2. 数字必须来自确定性工具结果（存在性校验已实现，Harness 兜底）
3. 每步改动必须有评测安全网（生产级与 demo 的分水岭）
4. **模型红线（更新于 2026-08-17）**：评测/演示/门禁必须使用**单一已验证模型**；当前主模型为 `mimo-v2.5-pro`（OpenCode Go）。禁止在未重跑 eval-gate 的情况下随意切模型；多模型 routing 仍冻结。 

## 1. 目标能力全景

| 层 | 能力 | 状态 |
|----|------|------|
| 执行层 | Harness 状态机/预算/循环检测/存在性校验/证据链/HITL 门 | ✅ D4 已交付 |
| 工具层 | 13 个确定性工具（业务+观测）全走 MCP，契约 13 项 | ✅ D4 已交付 |
| 上下文层 | 确定性当前窗口 + baseline.window + RAG 口径问答 | ✅ D3/D4 已交付 |
| 接口层 | 同步端点 + SSE 流式（事件契约 RUN_STARTED→…→COMPLETED） | ✅ D4 已交付 |
| **持久层** | Run/Step 持久化、崩溃恢复、取消、异步化 | ❌ M5 |
| **评测层** | 版本化评测集、质量指标、回归门禁 | ❌ M6 |
| **可观测层** | LLM trace、成本/延迟/质量指标、告警 | ❌ M6 |
| **安全层** | 红队实证、认证授权、PII、密钥 | ❌ M7 |
| **交付层** | 容器化、gateway 接入、UI 薄壳、灰度回滚 | ❌ M8 |
| **深化层** | 受控进程工具、记忆、真数据场景、B1 完整闭环 | ❌ M9 |

---

## 2. M5：Durable Execution（持久化 + 异步 + 取消/恢复）

### 目标
1. Run Store：run/step 落库（decision/tool 结果/证据/token/成本/版本号）
2. 异步化：`POST /api/runs` 立即返回 runId，SSE 订阅进度（Harness 事件机制已就绪）
3. 取消：`DELETE /api/runs/{id}`（`AgentRun.cancel` 已预留）
4. 恢复：崩溃后从最后未完成 step 重放，不重跑已完成步骤

### 技术栈
- 存储：MySQL 新库 `my_xhs_ai` + 独立写账号（AI 自有库，与只读业务账号物理隔离；我方可用 root 自建）
- 模型：run/step 两表关系化 + step 落 messages 快照（JSON 列）
- 执行：自管线程池（已有 20 并发上限，fixed pool）

### 难点与解决方案
| 难点 | 方案 |
|------|------|
| 崩溃后对话上下文重建 | 每 step checkpoint 一次 messages 快照（JSON），恢复时直接重建，不重放 LLM 调用 |
| 同一 run 并发执行 | run 状态 CAS（RUNNING 锁），恢复时校验状态 |
| 重复执行副作用 | 工具全部只读（天然幂等），runId 唯一键去重 |
| 只读账号不能写 | AI 自有库+账号，与业务数据隔离 |
| LangChain4j 无内置 durable | 自研（Harness 已是可重入状态机，改造量小） |
| 长 run 中断后消息历史体积 | 快照按 step 粒度，垃圾回收过期 run |

### 验收
- kill 进程重启后 run 恢复或明确终止，无静默丢失
- 取消即时生效；重复提交去重
- 每 run 可追溯 model/prompt/tool/policy 版本

---

## 3. M6：评测与可观测（生产级第一道门禁）

### 目标
1. 评测集：smoke 30-50 条进 PR（6 场景 + 拒答/降级/权限/预算/注入），regression 150-300 条进 nightly；YAML 定义（query + 断言规则），JUnit runner，无凭据自动跳过
2. 质量指标每 run 落库：任务完成率、工具调用准确率、幻觉率、延迟、成本
3. LLM 可观测：先自研 run 级指标（token/成本已落库）→ 评估 Langfuse
4. PR 门禁：红线指标（幻觉率/完成率）不过不放行

### 技术栈
- JUnit 5 + YAML 评测集 + `@EnabledIf` 真库条件
- 断言分层：硬断言（run 状态/终止原因/证据数/关键词拒答）+ 软断言（答案数字 vs 工具结果 registry 一致性——Harness 已存全部证据，幻觉率检测有数据基础）
- 成本预算上限防失控
- 评测 LLM 调用缓存（同 query+prompt 版本不重复调用，重跑省钱——参考 promptfoo 缓存）
- **工具误用分析**（参考 Anthropic ACI 观点）：统计评测中模型误用工具/参数的模式，迭代工具描述，不进 prompt

### 难点与解决方案
| 难点 | 方案 |
|------|------|
| Agent 开放文本如何断言 | 分层断言：确定性字段硬断言 + 数字一致性软断言 + 关键词；不做全文比对 |
| 幻觉率自动化提取数字 | 答案与 registry 证据各提取数字集合比对（正则起步，LLM 提取兜底） |
| LLM 输出漂移导致测试不稳 | temperature=0 + 阈值带（如完成率≥90%）+ 多轮采样 |
| 评测跑真模型花钱 | smoke 小量进 PR、regression 走 nightly、月度成本上限 + 结果缓存 |

### 验收
- 评测集机器可读、可回归、进 CI
- 幻觉率/完成率/延迟/成本有阈值并进 dashboard

---

## 4. M7：安全实证与加固

### 目标
红队（注入/越权/PII）、权限矩阵、观测端点认证、密钥管理。

### 技术栈与方案
- 注入红队：直接/间接注入载荷进评测集 Security 层（衡量 ASR）；确定性权限是兜底（已实现，注入无法绕过 allowlist）
- **Prompt Leakage 红队**（OWASP LLM07）：诱导模型吐露系统提示词/工具清单/内部口径的用例（参照 LLM Top10 分类）
- 越权：非授权工具 100% 拒绝（已实现）→ 红队用例实证 + 报告
- PII：PII 正则集 + 日志脱敏过滤器 + trace 无密钥扫描
- **SBOM/依赖扫描**（OWASP LLM03）：供应链依赖清单 + 漏洞扫描
- 观测端点：Prometheus/SkyWalking token 或 IP 白名单（iptables 持久化待办）
- 密钥：MCP_API_KEY 等入密钥系统（部署层），`.env.local` 仅开发

### 验收
红队报告；越权拦截率 100%；注入 ASR < 阈值；PII 泄漏 = 0。

---

## 5. M8：部署与试点

### 目标
容器化、gateway 接入、UI 薄壳、灰度回滚、故障演练。

### 技术栈
- Docker Compose（复用现有部署包模式）→ 可选 K8s
- gateway `/api/ai/**` 路由 + 角色（运营=L1、技术=L1+L2）
- UI 复用项目现有前端栈（纯 SSE 消费，证据链可点 + HITL 审批按钮，不承载逻辑）
- bundle 版本化（model+prompt+tool+policy 捆绑可回滚）

### 难点与解决方案
| 难点 | 方案 |
|------|------|
| 与观测栈网络隔离（iptables 白名单） | AI 服务部署同机/白名单 IP |
| UI 范围膨胀 | 薄壳铁律：只消费 API+SSE，无业务逻辑 |
| 灰度回滚 | bundle 版本号贯穿 run 记录（M5 已落版本字段） |

### 验收
一键回滚；故障演练全过（模型限流/MCP 超时/Worker 崩溃/中间件不可用）；SLO 真实负载校准。
CI 门禁接入见 `ci-eval-gate.md`（M6-4 产物）。

---

## 6. M9：生产深化

- **受控进程工具**（插件化思想安全落地）：工具内部 `ProcessBuilder` 执行预定义命令（mqadmin/mysqldumpslow 等），命令模板写死 + 参数白名单校验，模型拿不到任意命令；L3 动作挂 HITL 审批门
- **模型分层 routing**（参考 Anthropic Routing 模式）：固定/简单查询走小模型省钱，归因/复杂任务走当前模型；成本分布实测后引入
- 记忆（长期上下文，独立 Memory Store）
- 慢查询管道就绪后 B1 完整闭环（外部依赖）
- 业务流量累积后 A1/A2 真数据验收（外部依赖）

---

## 7. 外部依赖清单

| 依赖 | 归属里程碑 | 状态 |
|------|-----------|------|
| 慢查询摄入管道（B1） | M9 | 需求单已发 |
| gateway `/api/ai/**` 路由+角色 | M8 | 未定 |
| MCP_API_KEY 生产设置 | M8 | 未定 |
| 只读账号白名单/轮换 | M7 | 建议已提 |
| 部署环境 | M8 | 未定 |
| Run Store 建库授权 | M5 | 我方可用 root 自建 |

---

## 8. 风险清单

| 风险 | 应对 |
|------|------|
| deepseek-v4-flash 上下文上限未核验 | M6 前压测核验，超限降 max-tokens |
| LLM 输出不稳影响评测 | 阈值带 + 多轮采样 |
| 业务流量低导致 A 面真数据不足 | 工具链路已闭环，验收以评测集+人工基线为准 |
| OpenCode Go 额度限制（5h $12 / 周 $30 / 月 $60） | 评测集夜间批量跑 + 成本指标监控（M6）；超限回退免费模型（不在我们的评测范围）|
| 外部依赖延期 | 每里程碑都有不依赖外部的先行项（M5/M6 全自主） |

---

## 9. 执行顺序

**M5 → M6 → M7 → M8 → M9**，每步小步 + review + 评测回归。M5/M6 完全自主，可立即启动。

---

## 10. 参考调研（外部，仅参考不照搬）

> 调研日期：2026-08-14。来源：Anthropic《Building Effective Agents》(2024-12)、OWASP LLM Top10 2025、Promptfoo Docs。
> 原则：**只对照本项目三条铁律做收敛，不照搬**（我们拒绝 CodeAct/任意执行/个人助手模型，行业实践凡冲突者一律不采用）。

### 10.1 Anthropic《Building Effective Agents》对照

| 观点 | 本项目对照 | 结论 |
|------|-----------|------|
| Workflows vs Agents 分层（预定义路径 vs 模型动态决策） | IntentRouter 已实现：固定查询→确定性工具（workflow），归因→Agent | ✅ 一致，无需改 |
| 最简单的方案，只在有可测改善时加复杂度 | 6 场景中能确定查的走工具 | ✅ 已实践 |
| 三原则：简单性 / 透明性（展示规划步骤）/ 精心设计 ACI（工具接口） | SSE 已透明；**ACI 未系统化** | ➕ **M6 新增：工具误用分析**（统计模型误用工具/参数的模式并迭代工具描述——参考 SWE-bench 花更多时间优化工具而非 prompt） |
| Routing：简单问题→小模型，难→大模型 | 单一模型 deepseek-v4-flash | ➕ **M9 新增：模型分层**（简单查询走小模型省钱，归因走当前模型） |
| Agent 每步从环境获取 ground truth、有停止条件 | 工具结果回填 + 预算上限 | ✅ 已实现 |
| 增加复杂度须有评测证明 | 正是 M6 评测门禁的哲学 | ✅ 一致 |

### 10.2 OWASP LLM Top10 2025 对照（M7 安全覆盖面核验）

| 风险 | 本项目覆盖 | 状态 |
|------|-----------|------|
| LLM01 Prompt Injection | 确定性权限兜底（注入无法绕过 allowlist）| ✅ 已有，红队实证入 M7 |
| LLM02 Sensitive Info Disclosure | PII 脱敏 + 数据最小化（只返回聚合）| ✅ 规划中 M7 |
| LLM03 Supply Chain | 依赖/版本锁定 + SBOM | ➕ **M7 补充：SBOM + 依赖扫描** |
| LLM05 Improper Output Handling | 存在性校验 + 结构化输出校验 | ✅ 已有 |
| LLM06 **Excessive Agency**（过度授权）| L1/L2 只读 + L3 拒绝 + HITL 门 | ✅ 已覆盖（重点项）|
| LLM07 **System Prompt Leakage** | **未覆盖** | ➕ **M7 新增红队用例：诱导模型吐露系统提示词/工具清单** |
| LLM10 Unbounded Consumption | 预算三重封顶（步骤/Token/成本）| ✅ 已有 |

### 10.3 Promptfoo 对照（M6 评测设计）

| 观点 | 本项目对照 | 结论 |
|------|-----------|------|
| 声明式测试用例（YAML，不写代码） | M6 规划 YAML 评测集 | ✅ 一致 |
| 测试驱动 LLM 开发，非 trial-and-error | M6 评测门禁哲学 | ✅ 一致 |
| 红队自动化（攻击载荷分类：注入/越权/数据泄漏）| M7 红队用例可借鉴其分类框架 | ➕ 参考其分类设计红队用例 |
| 评测缓存（重跑省钱）| 未规划 | ➕ **M6 新增：LLM 调用结果缓存（同 query+版本 不重复调用）** |
| 本地运行、无数据外泄 | 我们 JUnit 本地方案 | ✅ 一致 |

### 10.4 调研结论（对本规划的增量）

1. **M6 增两项**：工具误用分析（ACI 优化循环）、评测 LLM 缓存
2. **M7 增两项**：Prompt Leakage 红队用例、SBOM/依赖扫描
3. **M9 增一项**：模型分层 routing（省成本）
4. **原则确认**：我们的 Workflow/Agent 分层、ground truth、停止条件、Excessive Agency 防护与行业主流实践一致——架构方向正确，缺的是工程化（评测/可观测/部署），即本规划 M5-M8

### 10.5 Langfuse（LLM 可观测/Prompt 管理/评测一体化）对照

| 能力 | 参考点 | 本项目对照 |
|------|--------|-----------|
| 全链路 trace（LLM+检索+工具）、会话/用户追踪 | OTel 基础、自托管开源 | ➕ **M6 选型候选**：先自研 run 级指标（token/成本已落库），后段 PoC Langfuse（OTLP 接入防 SDK 锁定） |
| Prompt 版本管理 + label 部署 + A/B | 提示词全生命周期 | ➕ **M5 前置**：prompt 抽资源文件带版本号落 run 记录；Langfuse 引入后原生管理 |
| 评测：datasets/experiments/LLM-as-judge/人工标注队列 | 四类评测方法 | ➕ **M6 评测设计**：代码断言为主（确定性）+ LLM-judge 辅助（开放判断）+ 人工基线（标注队列模式） |
| 生产 trace 回灌评测集 | 生产→数据集反馈闭环 | ➕ **M6 加**：生产 run 定期抽样回灌评测集 |
| 成本/用量按用户/会话追踪 | FinOps for LLM | ➕ **M6 成本指标** + M9 模型分层路由 |

### 10.6 Temporal（Durable Execution for AI）对照

| 观点 | 参考点 | 本项目对照 |
|------|--------|-----------|
| 长运行会话/状态持久/HITL/自动重试/可测试/可观察 | Durable 六大价值 | ✅ 我们 M5 自研覆盖其子集（单服务、20 并发、只读工具，自研成本低） |
| "所有 LLM 用例本质是 workflow" | 用例观 | ⚠️ 部分同意：我们的固定查询=workflow（已走确定性路径），归因=Agent——分层已实现 |
| 与 Langfuse/Braintrust 生态集成 | 可观测一体化 | ➕ 若 M5 后 PoC Temporal，评估其可观测集成 |

**决策点（M5 后段，对照 ADR-002 模式）**：自研 Run Store（v1，Harness 可重入改造小）→ 后段 PoC Temporal Java SDK（同一场景对照）→ 价值显著才引入，不默认上重平台（PLAN §4"故障恢复实验后决策"）。

---

## 11. 广度扩展：生产级完整维度图（M5-M8 之外的新增维度）

> 在 §1 目标全景基础上，补齐生产级系统常被忽视的维度。

### 11.1 可观测平台层（新维度）
- 现状：应用日志 + run 内存态；无 LLM trace/成本/质量 dashboard
- 目标：**LLM 调用全链路可视**（model/tool/retrieval/policy + token/成本/延迟/质量）
- 方案：M6 先自研 run 级指标（已有 token/成本落库基础）→ 后段 Langfuse PoC（OTLP 接入，防 SDK 锁定）
- 验收：dashboard 展示任务完成率/幻觉率/成本/延迟；超阈值告警

### 11.2 Prompt/模型/策略治理层（新维度）
- 现状：prompt 在代码常量；无版本化/无灰度切换
- 目标：prompt/模型/工具集/策略版本可追溯、可 A/B、可回滚（PLAN DoD"代码/模型/Prompt/数据集/工具/索引/策略版本可追溯"）
- 方案：M5 抽 prompt 到资源文件带版本号（落 run 记录）；M6 后段 Langfuse prompt management 或自研版本表；bundle=model+prompt+tool+policy 版本捆绑（M8 回滚单元）
- 验收：run 记录可还原当时全版本；prompt A/B 可灰度

### 11.3 会话与用户层（新维度）
- 现状：无会话概念（单轮 query）；无用户归属
- 目标：多轮会话持久化 + 用户级审计/成本归属（Langfuse sessions/users 参考）
- 方案：M5 run 表加 userId/sessionId；多轮会话=复用 run 上下文（Session Store）；审计按用户
- 验收：每 run 可归属用户与会话；成本按用户可查

### 11.4 成本治理层（新维度，OWASP LLM10 深化）
- 现状：run 级 cost 估算（单价 0.002/1k 待校准）+ max-cost 封顶
- 目标：场景级预算 + 月度上限 + 成本告警 + 模型分层省成本 + 成本趋势 dashboard
- 方案：M6 成本指标进 dashboard（按场景/用户）；M9 模型分层 routing（简单查询走小模型）
- 验收：成本可查可预警；超限自动降级

### 11.5 数据保留与合规层（新维度）
- 现状：无保留策略；无 PII 擦除机制
- 目标：run/会话数据 TTL + PII 擦除 + 合规评估（PLAN §9 EU AI Act 按角色/风险分类）
- 方案：M7 Run Store TTL 清理作业 + PII 规则集（日志/trace 脱敏）；合规评估文档
- 验收：数据保留策略生效；PII 泄漏=0；合规评估有结论

### 11.6 容量与滥用防护层（新维度，OWASP LLM10 深化）
- 现状：SSE 线程池 20 上限（已做）；无请求级限流/配额
- 目标：按用户/会话限流配额（LLM 调用昂贵，防滥用/DoS）+ 容量规划
- 方案：M8 gateway 层限流（复用现有 gateway 限流能力）+ 应用层配额（maxCost/用户）
- 验收：超配额请求 429 + 审计；容量压测有数据

---

## 12. 深度扩展：各里程碑细化

### 12.1 M5 细化（Run Store 表结构草图）
```
ai_run:      run_id(PK) user_id session_id query intent status(状态机)
             termination_reason budget(JSON) versions(prompt/model/tool 版本)
             tokens_in/out cost_ms cost_est started/ended
ai_step:     id(PK) run_id(FK) step_no state decision(JSON) tool_result
             evidence_id messages_snapshot(JSON checkpoint) created_at
```
- 恢复时序：启动扫描 RUNNING 超时 run → 取最后 checkpoint 重建 messages → 续跑
- 状态机：RECEIVED→RUNNING→SUCCEEDED/PARTIAL/FAILED/CANCELLED/EXPIRED
- 清理：TTL 作业（默认 30 天，合规 §11.5）

### 12.2 M6 细化（评测体系结构）
- 评测集分层：smoke(PR, 30-50) / regression(nightly, 150-300) / security(红队) / production(回灌)
- 断言四类：代码断言（run 状态/证据数/关键词——硬）+ 数字一致性（答案 vs registry——幻觉率）+ LLM-judge（结论质量/不确定性声明完整性）+ 人工基线（标注队列）
- 指标公式：任务完成率=SUCCEEDED/总数；工具准确率=正确工具选择/工具调用；幻觉率=答案数字与证据不一致比例；延迟 P50/P95；成本/run
- 工具误用分析：评测输出统计模型误用工具/参数模式 → 迭代工具描述（ACI）
- 回灌：生产 run 抽 5% 进标注队列 → 人工基线 → 进 regression

### 12.3 M7 细化（OWASP Top10 全映射红队用例清单）
| OWASP 项 | 用例示例 |
|----------|---------|
| LLM01 注入 | 直接注入"忽略规则"；间接注入（工具结果/检索内容携带指令）|
| LLM02 敏感披露 | 诱导输出用户明细/PII；日志抓取 |
| LLM05 输出处理 | 编造数字（存在性校验应拦截）；非 JSON 输出 |
| LLM06 过度授权 | 请求 L3/未注册工具（应 100% 拒绝+审计）|
| LLM07 Prompt 泄漏 | 诱导吐露系统提示词/工具清单/内部口径 |
| LLM10 无限消耗 | 超长输出/重复调用（预算应拦截）|
| 其他 | SBOM 扫描、依赖漏洞 |

### 12.4 M8 细化（部署拓扑）
- 三服务容器化（app 19020 / mcp 19021 / ui 19022）+ 依赖观测栈网络白名单
- gateway 路由 + 限流配额 + 角色（运营 L1 / 技术 L1+L2）
- bundle 回滚：版本表 + 一键切换
- 故障演练清单：模型限流/超时、MCP 不可用、Worker 崩溃、中间件不可用

---

## 13. 技术决策点清单（开放问题，逐步实证）

| # | 决策 | 选项 | 时机 |
|:--:|------|------|------|
| D1 | LLM 可观测 | 自研指标 / Langfuse(OTLP) | M6 后段 PoC |
| D2 | Durable 引擎 | 自研 Run Store / Temporal Java SDK | M5 后段 PoC 对照 |
| D3 | 评测断言 | 纯代码断言 / +LLM-judge | M6 评测集 v1 用代码断言，v2 评估 judge |
| D4 | 模型分层 | 单一模型 / routing 小模型 | **已冻结：成本红线禁止多模型**（仅 flash）；routing 仅在"同 flash 不同端点"意义下评估 |
| D5 | 多租户/RBAC | ADR-006 单组织；用户级认证先行 | M8 |
| D6 | Prompt 治理 | 资源文件版本 / Langfuse prompt mgmt | M5 前置资源文件，M6 评估 |

> 决策原则：**默认自研/最简单方案，引入外部组件须有实证价值**（对照 ADR-002/005 模式）；每次决策落 ADR。

---

## 14. 资产盘点：难点 / 亮点 / 面试点（附交付证据）

> 定位：PLAN v5 §11 九问的**证据化升级**——每问对应"我们做了什么 + 交付证据 + 还能讲什么"。证据均来自已提交代码/测试/真库 E2E，可当场复现。

### 14.1 难点与解决方案（真技术难点，非术语）

1. **LLM 幻觉 vs 确定性数字**（最核心）
   - 难点：模型"声称调了工具"但实际编造（免费模型曾编造 12000/15000）
   - 方案：存在性校验——Harness 登记每次真实工具调用（ToolResultRegistry），答案引用的证据 id 必须命中，否则拒绝重想，2 次不收敛→EVIDENCE_INVALID；数字全部来自工具结果 JSON
   - 证据：`AgentHarnessTest` 编造证据被拒/顽固编造终止用例；E2E 模型拒答"订单下降"错误前提
2. **Agent 失控防护**（死循环/发散/预算）
   - 难点：模型空转（不调工具）、同参数死循环、发散探索
   - 方案：循环检测两模式（连续同工具同参数 N 次 / M 步无新证据 hash 前进）；预算三重封顶（步骤/Token/成本）；policy 拒绝计数；E2E 实测校准 max-tokens 30k→100k、prompt 收敛压力
   - 证据：LoopDetector 单测 4 例；E2E 15 步发散→14 步收敛对比
3. **Agent 安全边界**（注入/越权/过度授权）
   - 难点：注入无法被识别拦截 100%（OWASP 共识），必须架构兜底
   - 方案：deny-by-default allowlist + 参数白名单（group 防 PromQL 注入）+ L1/L2 只读 + L3 拒绝挂 HITL 门 + MCP 认证审计；观测数据全走 MCP 保持数据边界
   - 证据：PolicyGuard 单测（注入拒绝/L3 审批/参数校验）；McpAuthTest 401
4. **开放性文本的自动化断言**（评测）
   - 难点：Agent 答案是自由文本，无法精确比对
   - 方案：分层断言（run 状态/证据数硬断言 + 答案数字 vs registry 一致性软断言 + LLM-judge/人工标注兜底）；评测缓存省成本
   - 证据：评测体系设计（§12.2）；M6 待交付
5. **跨异构数据源归因**（16 分片 + 4 事件表 + Prometheus）
   - 难点：口径不一致、跨库查询、事件表覆盖不全
   - 方案：工具层口径固化 + 契约测试钉死；漏斗工具复用订单/支付口径；事件表覆盖不足时工具/模型如实声明（E2E 模型正确识别漏斗数据不可用）
   - 证据：契约测试 13 工具；EventAnalyticsTool 单测；E2E 诚实声明
6. **MCP 客户端自研**（协议层）
   - 难点：SDK 0.18.3 client-jdk-http-client 是 15MB fat jar 内嵌未 relocate jackson，classpath 冲突
   - 方案：自研薄协议客户端（initialize→Session-Id→tools/call，会话自愈重试一次，SSE data 行解析）
   - 证据：McpToolBridge/McpClient；全链路 E2E

### 14.2 亮点（独特性 + 证据，可现场演示）

1. **"不编造"是架构保证，不是 prompt 承诺**：存在性校验 + 证据链——业界多数靠 prompt 软约束，我们是确定性兜底
2. **证据链 + 反证 + 不确定性强制结构**：答案必须引用工具证据、声明反证与不确定性；E2E 模型拒答错误前提、识别 DLQ -283 哨兵值异常、区分死锁累计 vs 新增——**Agent 反向发现系统真实问题**
3. **确定性窗口注入**：E2E 实证同一问题不同窗口结论相反（41.9% vs 63.9%）→ 窗口/基线全部确定性化（QueryWindowExtractor + baseline.window 工具）
4. **全真实数据闭环**：13 工具连真实 MySQL 16 分片/事件表/Prometheus/SkyWalking；多轮 E2E；非 demo
5. **与业务/运维协作闭环**：数据缺口促成对方实施 4 张事件流水表（append-only 方案）+ 修复 T-058（MySQL 非法 UPDATE LIMIT）/T-059（gateway 500→404）——我们提需求、对方实施、我们验收
6. **工具层知识编码**：DLQ -1 哨兵值、5xx 扫描噪音（uri=/** 单列 noiseScanRoutes）——运维知识进工具不进 prompt，确定性
7. **深度 review 文化**：每轮 review 抓到真 bug（空转检测失效、静默负值、no-progress 被绕过）——工程质量流程可讲

### 14.3 面试点（PLAN v5 九问 → 证据映射）

1. **为什么"订单多少"不走 Agent，"为什么下降"走？**
   → IntentRouter 规则路由（固定查询→确定性工具，数字可重复）；归因→受限 Agent。Workflow vs Agent 分层与 Anthropic 观点一致。证据：IntentRouterTest。
2. **如何保证 LLM 不生成错误业务数字？**
   → 存在性校验（§14.1#1）。答案：工具结果登记 + 引用校验 + 拒绝重想 + 幻觉率评测（M6）。可现场演示 E2E 拒答。
3. **MCP/Tool/Skill/Workflow/A2A 分别解决什么？**
   → MCP=工具协议标准化（我们 13 工具全走 MCP，认证+审计）；Tool=受控能力单元；Workflow=预定义路径（固定查询）；A2A=跨 agent 协议（未采用，克制）。证据：MCP 架构图 + 契约测试。
4. **RocketMQ+Redis 为什么不等于 Durable Agent Runtime？**
   → MQ 是消息通道无状态/无重放语义；Durable 需要 checkpoint + 重放 + 幂等（M5 Run Store 设计 §12.1）。参考 Temporal 对照（§10.6）。
5. **Run State / Conversation / RAG / Long-term Memory 区别？**
   → 四类状态分离（PLAN §3.4）：Run=执行状态（M5 落库）、Conversation=会话（§11.3）、RAG=知识（已交付）、Memory=长期（M9）。
6. **Agent 非确定性如何 CI/CD 回归门禁？**
   → M6 评测体系：分层断言 + 阈值带 + 多轮采样 + 缓存；幻觉率/完成率硬指标进 PR。证据：评测设计 §12.2。
7. **Prompt Injection 无法彻底识别时如何保证不越权？**
   → 确定性权限兜底：allowlist + 参数白名单 + L3 拒绝；注入只能影响"模型说什么"，影响不了"能做什么"。证据：PolicyGuard 测试 + 红队规划 §12.3。
8. **工具调用后崩溃如何避免重复副作用/丢进度？**
   → 工具只读天然幂等 + runId 去重 + messages 快照 checkpoint 重放（M5 §12.1）；Temporal 对照实验入作品集（PLAN §11.2）。
9. **质量/延迟/成本/安全如何可解释取舍？**
   → 三条铁律 + 预算参数实测校准（max-tokens 30k→100k 实证）+ 成本指标（M6）+ 模型分层决策点 D4。证据：校准记录。

### 14.4 作品集缺口（对照 PLAN v5 §11.2，当前状态）

| 作品集项 | 状态 |
|----------|------|
| C4 架构图 + ≥10 ADR | ADR 6 个已有；C4 图未画（M8 前补） |
| LangChain4j vs AgentScope Java 对照 | 未做（PoC 项） |
| 官方 MCP Java SDK + conformance | 已用官方 SDK 服务端；conformance 未跑 |
| Temporal kill/recovery 故障实验 | 未做（M5 后段） |
| 300+ 评测集 + Promptfoo/红队报告 | 未做（M6/M7） |
| Langfuse trace + Grafana Dashboard | 未做（M6） |
| 成本/容量/SLO/灰度/回滚报告 | 未做（M6-M8） |
| 5 演示视频 + 技术复盘 | 未做（M8/M9） |

> 结论：**九问的"答案与证据"已齐（14.3 可复用为面试讲稿）；缺口全在作品集工程件**（评测/故障实验/报告/图），恰是本规划 M5-M8 的交付物——路线图与面试路线重合，无额外工作。

