# RV02 · 5 个 Agent 项目代码级 Review（2026-09-12）

> 方法：**直接阅读实现代码**（非文档转述）。每个仓库读关键机制源码，记录实现细节 → 提炼工程手法 → 给出对 xhs-ai 的设计增量（DELTA）
> 声明：只借鉴设计，不复制代码

## 一、逐仓库代码核验

### 1) opencode — 权限引擎 & 插件生命周期（读透）

**证据**：`packages/opencode/src/permission/index.ts`（223 行全文）、`packages/plugin/src/v2/effect/{plugin,registration}.ts`

实现细节：
- 规则求值：`evaluate(permission, pattern, ...rulesets)` = `flat().findLast(rule => Wildcard.match(permission,..) && Wildcard.match(pattern,..))`，**last-match-wins**，无匹配默认 `{action:"ask"}`
- `ask()`：逐 pattern 求值；遇到 `deny` 立即 `DeniedError` 短路；全 `allow` 直接放行；否则登记 `pending` map（id → `{info, Deferred}`）并发 `Event.Asked`，随后 `Deferred.await`
- `reply()`：`reject` → `Deferred.fail`（可带 feedback 形成 CorrectedError），**并级联拒绝同 session 的其它 pending**；`always` → 写入 `approved` 规则表并**批量唤醒**所有已被新规则覆盖的 pending
- **dispose 时把全部 pending fail 成 RejectedError**（fail-closed，不悬挂）
- `registration.ts`：hook 注册返回 `Registration {dispose}`（Scope 管理）；`plugin.ts`：`define({id, effect})`，插件的注册随 Scope 销毁

→ **DELTA-1（审批服务）**：Java 版 `ApprovalService` 采用同构模型：`ConcurrentHashMap<id, PendingApproval{CompletableFuture}>`；规则 last-match-wins + 默认 ask + wildcard；reject 级联同会话；always 落"会话授权"且批量放行；**应用关闭时 fail-closed 全部拒绝**；与 `ai_approval` 持久化表对齐（重启恢复用 snapshot/restore，见 Reasonix）。

### 2) deepseek-harness — hooks 协议三件套（读透）

**证据**：`packages/hooks/hook-protocol/src/{merge,matcher,runner}.ts`

实现细节：
- **merge**：`deny(3) > ask(2) > allow(1) > none(0)` 取最高档；**只保留获胜档的 reason**（其余丢弃）；`continue:false` 首个即 sticky（`stop` + 首个 `stopReason`）；`additionalContext/systemMessages` 按 hook 顺序累积
- **matcher**：缺失/空/`*` = match-all；Claude 方言对 `^[A-Za-z0-9_|]+$` 做字面量（`|` 分隔备选），其余当非锚定正则；**运行时非法正则含为非匹配**，配置期用 `matcherDiagnostic` 拒绝
- **runner**：默认超时 10 分钟；传 `AbortSignal` 支持取消；**基础设施异常永不 throw**（转成"无 exitCode 的非阻塞 error"，回合继续）；exitCode `null`（信号死亡）映射为 undefined
- 仓库规范（AGENTS.md，同样有价值）：**注册即 effect（register 返回 disposer）**、"Model-visible ⟺ logged"、能力缝三角色、**misconfiguration fails loud**、发布状态只在提交点、**在做出决策的操作里执行决策**（不能靠 schema 省略/包装层替代强制）

→ **DELTA-2（Middleware 合并语义）**：审计/脱敏/限流/成本多中间件命中同一工具调用时，按 `deny>ask>allow` 合并，理由只取获胜档；任一 `stop` 即停（sticky）。→ **DELTA-3（工具执行器）**：`ToolRunner` 永不向 Agent 循环抛异常；超时/基础设施错误降级为受控结果 + 记录 duration；默认超时可配置。

### 3) pi — 持久化执行状态机（读透）

**证据**：`packages/agent/src/harness/runtime/drive.ts`（106 行）、`drive/deferred.ts`

实现细节：
- `driveOperation` 是显式状态机：`starting → checkpoint → assistant.ready/retry_wait → tools → deferred.suspended/effect_pending → summary.* → navigation`；每个状态由**持久化过程**函数推进
- 关键不变量：**一轮未推进状态即抛 `SessionInvariantError`**（`next === state` → throw），防 silent hang
- 取消路径独立：`cancel_requested` → `reconcileOperation`
- deferred（外部执行等待）：从会话日志读回 assistant 的 `deferred` 句柄，**校验 provider/modelId/api 与当前配置一致**，不一致抛不变量错误；`deferredPermits` 控制轮询次数

→ **DELTA-4（审批恢复与长任务）**：审批挂起/恢复必须是**显式状态机 + 持久化**；恢复前校验上下文一致性（审批时的工具/参数指纹）；**"无进展即失败"** 作为不变量写入我们的恢复流程（防挂起）。

### 4) DeepSeek-Reasonix — 审批管理器与会话授权（读透）

**证据**：`internal/control/approval.go`（读关键段）

实现细节：
- `approvalManager`：`approvals` map + `granted`（会话级授权）+ `planModeReadOnlyCommands`（按命令前缀信任）+ 每项含 `tool/subject/reason/rawInput/fresh/requireHuman/autoDrain/kind/recovery/reply chan`
- `autoDrain`：非 fresh 且非人工必审时，**策略命中即自动放行**（不弹卡）
- `grantSession(tool, subject)` 记录会话级授权，后续调用短路
- 检查点：`snapshotSessionAuthorizations()/restoreSessionAuthorizations()` — **控制器重建/重载后可恢复授权状态**（Grants/PlanModeReadOnlyCommands/WriteRoots）
- `cancel(id)` 处理超时/中断

→ **DELTA-5（授权可恢复）**：`always/会话授权` 必须可序列化并在 Agent 状态恢复时还原；审批项包含 `rawInput` 指纹与 `kind`（便于按类型批量治理与恢复）。

### 5) hermes-agent — 插件数据隔离 & 工程规范（读透）

**证据**：`plugins/plugin_storage.py`（44 行全文）、根 `AGENTS.md`/`plugins/AGENTS.md`

实现细节：
- **插件数据目录与安装目录分离**：状态写 `<hermes home>/plugin-data/<name>/`（安装目录会被 remove/update 清掉）；名称正则校验（防穿越）；SQLite WAL + `foreign_keys=ON`，允许读写并发
- 工程规范（可直接移植为 xhs-ai 开发手册）：
  - **每会话 prompt 缓存神圣**：任何改动历史/换工具集/重载记忆都会废缓存；唯一例外是压缩；变更类操作默认延迟生效（下会话）或显式 `--now`
  - **窄腰**：新能力按 Footprint Ladder（扩展已有 → CLI+技能 → 服务门控工具 → 插件 → MCP → 最后才是核心工具）
  - **行为契约测试**，禁止 change-detector（冻结枚举/模型清单）；**禁止测试里读源码**；E2E 用临时 home 真实导入
  - 依赖上界/精确 pin、SHA 固定、成熟期策略

→ **DELTA-6（缓存与变更治理）**：xhs-ai 开发规范加入"prompt 缓存神圣"与"变更延迟生效"；**DELTA-7（测试规范）**：禁止 change-detector/源码读取测试，一律行为契约 + 临时环境 E2E；**DELTA-8（数据隔离）**：Skill/工具运行数据与安装目录分离（MySQL 表前缀/独立库），名称白名单校验。

## 二、跨项目工程手法沉淀（转入 xhs-ai 开发手册）

| # | 手法 | 来源 | 落点 |
|---|------|------|------|
| 1 | 注册即 effect，返回 disposer，且有"卸载零残留"测试 | deepseek/opencode | 工具/Skill 注册器 + 测试门 |
| 2 | Model-visible ⟺ logged（模型可见必须可从日志重建） | deepseek | 会话事件日志设计 |
| 3 | 决策必须在做出决策的操作里强制（不能只靠 schema/包装） | deepseek | 权限/审批执行点 |
| 4 | 状态只在提交点发布；派生视图从单一权威源生成 | deepseek | 审批/工单状态机 |
| 5 | 无进展即失败（防静默挂起） | pi | 审批恢复/长任务 |
| 6 | fail-closed：关闭/无人应答/异常一律拒绝 | opencode/deepseek | 审批与策略 |
| 7 | 缓存友好：稳定前缀 + 动态信息放尾部 + 变更延迟生效 | hermes/reasonix | Prompt 组装规范 |
| 8 | 录播回放（keyless）+ golden 期望做回归 | deepseek/pi | 评测体系 |
| 9 | 禁止 change-detector / 源码读取测试 | hermes | 测试规范 |
| 10 | 依赖 pin + 成熟期 + CI 校验 | hermes/pi | 供应链治理 |

## 三、对现有文档的增量（待折入 P2/P3/P4）

- `02-architecture`：审批服务状态机（DELTA-1/4/5）、中间件合并语义（DELTA-2）、工具执行器契约（DELTA-3）
- `04-engineering`（新增）：缓存神圣与变更治理（DELTA-6）、测试规范（DELTA-7）、数据隔离（DELTA-8）、工程手法 1-10
- `03-test-design`：增加"卸载零残留"“状态恢复"“无进展即失败"“录播回放"四类用例
- RV01 缺口 G5（工具治理）吸收 DELTA-2/3；G14（开发流程）吸收 DELTA-6/7/8
