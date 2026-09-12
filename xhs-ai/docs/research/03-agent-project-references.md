# R03 · 五个头部 Agent 项目设计参考（Phase 1）

> 对象：pi / deepseek-harness / opencode / DeepSeek-Reasonix / hermes-agent（浅克隆于 `/data/workspace/references/`）
> 目的：**只提炼设计点、工程问题解法、插件机制**，不抄代码；标注可采纳项与明确不采纳项

## 1. 项目定位速览

| 项目 | 定位 | 最值得看 |
|------|------|---------|
| pi | 可自扩展本地编码 harness（ai/agent/coding-agent/tui + chord 插件运行时） | 工具持久化三段提交、session tree、扩展拦截 |
| deepseek-harness | "一切皆插件" harness（Cordis），内核组合出 CLI/Web/Desktop/SDK/ACP | capability seams、生命周期 waterfall、会话事件日志、评测回放 |
| opencode | 单实例多项目 AI 编码 Agent，v2 小核心+热重载插件 | Catalog transform 热更、Permission ask 挂起、durable inbox、Context Epoch |
| DeepSeek-Reasonix | 单 Go 二进制本地 Agent（TUI/桌面/serve/ACP） | core 精简、会话归属栅栏、扩展热更 generation、账单/预算三事实 |
| hermes-agent | 同一内核跨 CLI/网关/TUI/桌面，能力边缘扩展 | 双轨 hook/middleware、micro-compaction、content-free OTLP、插件 catalog 治理 |

## 2. 跨项目共性设计模式（强信号）

| # | 模式 | 出处 | xhs-ai 落点 |
|---|------|------|------------|
| P1 | **窄腰核心 + 能力边缘化**：核心小且稳定，日志/指标/trace/DLQ 等一律插件或 seam | deepseek(hooks/capability-seams)、opencode(v2)、hermes、pi | AgentScope HarnessAgent 为核心；诊断能力全部做"工具 Provider"，不改核心 |
| P2 | **Capability Seam 三角色**：Definition / Provider / Consumer 分包，换实现不动工具 schema | deepseek `capability-seams.md` | `tools-api`（契约） / `tools-impl-*`（ES/Prom/RMQ/Job） / `agent`（消费）三层包 |
| P3 | **双轨扩展**：observer hook 只读 fail-open；middleware 可改写、fail-open、链式 | hermes `middleware/README.md` | AgentScope `MiddlewareBase`（onAgent/onReasoning/onActing/onModelCall）承载：审计/脱敏/限流/成本 |
| P4 | **插件生命周期可重放**：register 返回 disposer；卸载靠 finalizer 反注册；热更 fail-atomic（失败保留旧 runtime） | deepseek、opencode `plugin.ts`、reasonix `EXTENSIONS.md` | 工具/Skill 注册必须可反注册；配置热更（数据源地址/黑白名单）失败回退 |
| P5 | **审批是一等公民**：pending/reply(once/always/reject)；无回答方 **fail-closed**；审批与决定入日志 | opencode、deepseek、pi、hermes | `ai_approval` 持久化 + 框架 PermissionEngine；审计事件与诊断回放共用 |
| P6 | **会话归属与栅栏**：durable inbox 先准入后调度；steer 打断/queue 排队；generation 防串台 | opencode、reasonix `APP_SESSION_OWNERSHIP.md` | 每 `(userId,sessionId)` 串行；审批恢复用 steer；配置/技能热更 bump generation |
| P7 | **上下文工程**：稳定 key 合成 + Context Epoch（缓存基线）+ 分级压缩（摘要/工具结果卸载/溢出恢复） | opencode `CONTEXT.md`、pi `compaction.md`、hermes `micro-compaction.md` | AgentScope Compaction + ToolResultEviction；**缓存友好**（稳定前缀，动态信息放尾部） |
| P8 | **工具持久化/幂等**：intent→effect→settlement 三段提交、outcome_ready 防重放；陈旧结果拒绝 | pi `tool-durability.md`、opencode `tools.md` | 变更类工具（dlq.redeliver/job.trigger）加幂等键 + 执行后核验 + 陈旧拒绝 |
| P9 | **观测双平面**：业务 trace（可含内容）与运维 OTLP（content-free：只有 ID/耗时/状态/成本） | hermes `observability/README.md` | OTel 两个 exporter/两套属性约定；token/成本入指标 |
| P10 | **评测工程**：录制回放（keyless）+ golden 期望；faux provider 离线回归；诚实性评测（无解语料） | deepseek `testing.md`、pi evals、reasonix `benchmarks/README.md` | 评测集升级：录播诊断 session + 无解用例 + 边界计量（不采信模型自报） |
| P11 | **权限策略引擎**：有序规则 last-match-wins、默认 ask、插件不得改策略；能力声明不得超 manifest | opencode `provider-policy.md`、reasonix `EXTENSIONS.md` | 策略引擎独立于工具实现；技能/插件声明能力白名单 + CI 校验 |
| P12 | **供应链与治理**：catalog + SHA pin + 成熟期 + CI 在 pin commit 上校验 | hermes `plugin-catalog-ci.yml` | Skill/工具清单进 catalog，CI 校验声明与实际注册一致 |

## 3. 工程问题 → 解法（精选，直接可用）

| 痛点 | 成熟解法（出处） | xhs-ai 采纳 |
|------|----------------|------------|
| 插件阻塞启动 | 内置/配置先出基线，后台激活 + debounce reload（opencode） | M1 启动不阻塞：数据源不可达时降级 + 后台重连 |
| 卸载/热更残留 | 可重放 transform + finalizer 全回滚（opencode/deepseek） | 工具注册器写反注册测试（启动→注册→卸载→零残留） |
| 流式失败不可回放 | assistant 消息内嵌 timed stream + attempt 记录（deepseek） | 诊断会话支持"从失败点重放"（事件日志驱动） |
| hook 异常拖垮循环 | 每步降级为受控结果，never throw into loop（deepseek） | 审计/脱敏/限流 middleware 全部 fail-open + 预算 |
| 审批无人应答 | approval/request fail-closed unavailable（deepseek） | 审批超时策略：默认拒绝 + 提示（配置化） |
| 输出超限 | 统一限界 + 大结果落文件占位（opencode） | 日志/知识检索结果一律截断+落盘引用 |
| 上下文改历史破坏缓存 | 摘要滚动 + defrag + 原子归档（hermes）；stable prefix（reasonix） | 压缩策略做 A/B 评测后才启用；system/tool schema 字节稳定 |
| 多源配置漂移 | Catalog transform 按域 reload（opencode） | 数据源/工具/技能三类配置独立热更 |
| 模型自报指标不可信 | 边界代理计量 + 故障注入 + 诚实性评测（reasonix） | 成本/延迟以网关侧计量为准；诊断准确率用 golden 集测 |
| 成本失控 | 账单三事实 + run/task 预算（reasonix）；usage ledger（pi） | 会话 token/成本预算 + flash/pro 路由 + 超预算降级 |

## 4. 插件/扩展机制对比

| 维度 | pi | deepseek | opencode | reasonix | hermes | 对 xhs-ai 的结论 |
|------|----|----------|----------|----------|--------|-----------------|
| 形态 | TS 扩展（jiti）| 进程内插件+package | npm/目录插件+bundle | 声明式插件包+sidecar | Python 插件+catalog | Skill(声明式)+Tool Provider（Java Bean） |
| 注册 | `pi.on` 拦截/注册 | `ctx.effect/on` + disposer | `define({effect})` + Scope | manifest 能力声明 | hooks 全集 + middleware | 工具/技能注册必须返回 disposer |
| 热更 | `/reload` | run/stop + HMR 安全 | rebuild + debounce | generation + fail-atomic | reload/update | 配置热更 fail-atomic（保留旧） |
| 版本 | engines 无 | generation 迁移链 | engines semver 校验 | apiVersion 精确匹配 | SHA pin + 成熟期 | 版本化+CI 校验；不做巨型兼容层 |
| 权限 | 无 | approval waterfall | 规则引擎+默认 ask | full-trust（差评） | guard fail-closed | 策略引擎独立；**禁 full-trust** |
| 隔离 | 靠容器 | sandbox 可选 | 无沙箱（自述） | 无沙箱（自述） | 无超时（自述） | v1 无代码执行；回调一律有 deadline |

## 5. 对现有设计文档的修订建议（采纳清单 ADOPT）

| 编号 | 修订 | 落到哪 |
|------|------|--------|
| ADOPT-1 | 工具按 **Definition/Provider/Consumer** 三层包拆分（`tools-api`/`tools-impl-*`/`agent`） | 02-architecture §3 |
| ADOPT-2 | 新增 **策略引擎**：有序规则 last-match-wins + 默认 ask；策略与工具实现解耦、技能不可改策略 | 02-architecture ADR-5、04-engineering |
| ADOPT-3 | 审批协议：once/always/reject + 无应答 fail-closed + 事件化审计（与回放共用） | 02 §2.2、03 测试 |
| ADOPT-4 | 变更类工具 **intent→effect→settlement + 幂等键 + 执行后核验**（DLQ 重投/Job 触发） | 02 §2.2、P4 详细设计 |
| ADOPT-5 | **Context Epoch + 稳定前缀**（缓存友好）+ 压缩策略先评测后启用 | 02、03（评测门禁） |
| ADOPT-6 | 观测 **双平面**：业务 trace 与 content-free 运维 OTLP；网关侧计量成本 | 02 §1、04 |
| ADOPT-7 | 评测升级：**录播回放 + golden 期望 + 无解语料 + 边界计量** | 03-test-design §6 |
| ADOPT-8 | Skill/Tool **catalog + 版本化 + CI 校验（声明=实际注册）**；热更 fail-atomic | 04-engineering、CI |
| ADOPT-9 | 每会话 **token/成本预算** + pro/flash 路由 + 超预算降级 | 02 ADR、01 NFR |

## 6. 明确不采纳（风险清单）

| 不采纳 | 原因（出处） |
|--------|------------|
| full-trust 代码插件 | reasonix 自述可覆盖 host deny 且无沙箱；企业多租户不可接受 |
| 无审批/无策略的无沙箱执行 | pi/opencode 自述为设计取舍，不适合生产运维动作 |
| 巨型兼容层（2600+ 名称） | hermes 自身建议企业直接 API 版本化 |
| 每轮改写历史的 micro-compaction | hermes 自述破坏 prompt cache，默认关闭；须评测后启用 |
| 照搬 Effect/Scope 或 Typert RPC 语义 | 与 AgentScope Java 运行时差异大；只借抽象 |
| 模型自改运行时（self-modification） | 权限与供应链风险；Java 生产禁 |

## 7. 参考仓库

浅克隆于 `/data/workspace/references/{pi,deepseek-harness,opencode,DeepSeek-Reasonix,hermes-agent}`（只读参考，勿提交到本仓库）。
