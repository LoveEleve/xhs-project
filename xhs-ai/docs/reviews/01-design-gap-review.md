# RV01 · 设计查漏补缺 Review（2026-09-12）

> 方法：① 对 AgentScope 2.0.1 全部 jar 做 javap/清单核验（代码级）；② 对 5 个参考仓库关键机制做代码抽查；③ 对现有 7 份设计/研究文档做一致性 review
> 结论：**R02 待验证 6 项全部关闭；发现 14 个设计缺口（P0×5 / P1×5 / P2×4），均已给出补法**

## 一、R02 待验证项关闭（代码级证据）

| # | 原待验证 | 结果 | 证据 |
|---|---------|------|------|
| 1 | OpenAI 扩展自定义 endpoint | ✅ `baseUrl / apiKey / endpointPath / httpTransport / proxy / formatter / stream` | `javap OpenAIChatModel$Builder` |
| 2 | PermissionEngine 三态 + 挂起/恢复 | ✅ `checkPermission -> Mono<PermissionDecision>`；`allow/deny/ask/passthrough`；`getUpdatedInput()` 可改写入参；恢复事件齐全 | `PermissionEngine` / `PermissionDecision` / `RequireUserConfirmEvent` / `UserConfirmResultEvent` / `ConfirmResult` / `RequireExternalExecutionEvent` / `ExternalExecutionResultEvent` |
| 3 | 30 种 AgentEvent（SSE 契约） | ✅ 枚举已拿到（AGENT_*/MODEL_CALL_*/TEXT|THINKING|DATA_BLOCK_*/TOOL_CALL_*/TOOL_RESULT_*/EXCEED_MAX_ITERS/REQUIRE_USER_CONFIRM/REQUIRE_EXTERNAL_EXECUTION/USER_CONFIRM_RESULT/EXTERNAL_EXECUTION_RESULT/REQUEST_STOP/SUBAGENT_EXPOSED/ALL_TOOLS_DENIED/CUSTOM） | `javap AgentEventType` |
| 4 | Redis Sentinel 适配 | ✅ `jedisClient(UnifiedJedis)` / `lettuceClient` / `lettuceClusterClient` / **`redissonClient`**（Redisson 原生支持 Sentinel） | `javap RedisAgentStateStore$Builder` |
| 5 | Skill 格式兼容 | ✅ `AgentSkill(name, description, metadata, skillContent, resources)`；harness 侧有 `SkillCatalog/SkillRegistry/curator(AllowListFilter/EnvironmentFilter/LocalApprovalGate/SkillUsageRecord)`；frontmatter→metadata 需 M1 实测 | `AgentSkill` / harness jar 清单 |
| 6 | AgentScope 状态表 DDL | ✅ `MysqlAgentStateStore / MysqlDistributedStore / JdbcStore(Mysql/PG/SQLite 方言)`；jar 内无 .sql → **DDL 由框架自管**，M1 运行时验证表结构 | mysql 扩展 jar 清单 |

> 附带发现：`PermissionMode` 有 `DEFAULT/ACCEPT_EDITS/EXPLORE/BYPASS/DONT_ASK` 五档，可作为审批策略基线；`PermissionRule(resource, action, behavior, …)` 支持细粒度规则。

## 二、R03 机制代码抽查确认

| 项目 | 抽查点 | 结果 |
|------|--------|------|
| opencode | `permission/index.ts` 有 `pending: Map<ID, PendingEntry>` + ask/allow/deny 流程；插件 `define({effect})` 返回 Scope 可处置 | ✅ 机制真实存在 |
| hermes | `agent/micro_compaction.py`：cursor/defrag/rolling summary/边界必须闭合 turn | ✅ 且印证"改历史破坏缓存"警告 |
| pi | `harness/runtime/` 有 intent 元数据、drive 到 settlement/durable wait | ✅ 三段提交真实 |
| reasonix | `internal/control/approval.go`：approvalManager + 决定类型/超时/持久化 + resolveAfter | ✅ 审批可按类型扩展 |
| deepseek | `packages/hooks/hook-protocol` 有 matcher/merge/runner/invariant 全套测试 | ✅ hooks 协议工程化程度高 |

## 三、设计缺口清单

### P0（进入编码前必须补齐）

| # | 缺口 | 风险 | 补法（去处） |
|---|------|------|------------|
| G1 | **敏感数据外发合规**：日志含 userId/订单/支付信息，直接送第三方 LLM 网关（siyu.site） | 合规/数据泄漏 | 新增《数据安全与脱敏设计》：字段级脱敏（手机号/地址/token）、数据分级（公开/内部/敏感）、出网白名单、可切换私有化模型、审计留存策略（04 + 专项 ADR） |
| G2 | **Prompt Injection（日志是被攻击者可控的不可信数据）** | 指令劫持/工具滥用 | 输入隔离规范：日志/消息一律作为"数据块"包裹、禁止解释为指令；工具白名单 + 参数 schema 强校验；输出引用校验；红队用例入评测（P2 威胁模型 + 03） |
| G3 | **模型网关设计缺失**（现只写"provider 可切换"） | 不稳定/成本失控 | 专项设计：超时/重试/熔断/限流/降级（pro→flash→拒绝）、prompt cache 友好策略、token 预算与会话配额、成本计量以网关侧为准（02 ADR-8 + 04） |
| G4 | **交互契约未定义**：SSE 业务事件与审批交互协议 | 前后端无法并行/返工 | 用已确认的 30 种 AgentEvent 定义 SSE 事件契约（含 ConfirmResult 回传）；审批 once/always/reject + fail-closed；给出最小前端交互草案（P4 接口设计） |
| G5 | **工具治理细则**：幂等键、超时、结果上限、限流、缓存、熔断 | 误操作/雪崩/数据源被打挂 | 《工具治理规范》：所有工具声明 risk/timeout/幂等语义/结果上限；变更类 intent→effect→settlement；ES/Prom 查询限流与缓存（02 + P4） |

### P1（设计包评审前补齐）

| # | 缺口 | 补法 |
|---|------|------|
| G6 | SLO/容量/成本未量化（并发、上下文上限、P95、token 预算） | P2 需求工程产出量化 NFR 表 + 容量/成本模型 |
| G7 | 知识工程未细化（分块/混合检索融合/rerank/引用校验/失效更新） | P4 专项设计：分层分块、RRF/加权融合、引用必校验真实存在 |
| G8 | 评测方法与 baseline 未落地（ground truth 来源/录播/无解语料） | 03 升级：录播回放 + golden + 红队/无解用例 + 门槛 |
| G9 | Agent 自身可观测与审计查询（bad case 回流） | 04：双平面 OTel + 审计查询 API + bad case 回流看板 |
| G10 | 旧知识资产治理（278 文件） | R04：分类分级 + 来源标注 + 时效校验 + 入库白名单 |

### P2（实施期补齐）

| # | 缺口 | 补法 |
|---|------|------|
| G11 | 多环境部署/密钥管理/备份恢复/DR | 04 工程规范 + compose/K8s 清单 |
| G12 | Prompt/Skill/Tool 版本化流程（变更审计/灰度） | catalog + 版本字段 + CI 校验（ADOPT-8） |
| G13 | 第三方条款与 License 合规（模型网关/embedding 数据条款） | 合规页：数据保留/训练使用声明/替代方案 |
| G14 | 协作流程未落到本仓（harness 6 阶段、分支策略、评审门禁） | **对 xhs-ai 执行 apply-harness**，生成 `.harness/`（owner/rules/skills/changes）+ CI 门禁 |

## 四、文档修订清单

| 文档 | 修订 |
|------|------|
| `02-architecture.md` | 新增 ADR-8 模型网关（含预算与降级）；补"数据脱敏与不可信输入"章节；SSE 事件契约引用 AgentEventType |
| `01-prd.md` | NFR 表拆成"目标/预算/测量方法"；新增"数据合规"非功能需求；FR-5 补 fail-closed 与 once/always/reject |
| `03-test-design.md` | 补红队/注入/无解用例；录播回放 fixture 策略；评测门槛与 baseline |
| 新增 | 《G1 数据安全与脱敏设计》《G3 模型网关设计》《G5 工具治理规范》（P4 阶段产出） |

## 五、下一步

1. **R04**：旧资产治理 + 场景库（REQ 编号 + 期望证据形态）
2. **P2 需求工程**：量化 NFR/SLO + STRIDE 威胁模型 + REQ/AC/TC 编号（把 G1/G2/G6 纳入）
3. **P3**：把 ADOPT-1~9 与 G3/G5 折进架构设计（含模型网关、工具治理两个专项）
4. **G14**：对 `xhs-ai` 执行 apply-harness（用我们自己的方法论约束这个项目）
