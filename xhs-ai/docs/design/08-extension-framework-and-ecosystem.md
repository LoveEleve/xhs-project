# D08 · 扩展框架与生态吸收规划（P3 深化）

> 调研范围：5 个项目的**本地代码**（R03/RV02）+ **GitHub 生态**（本轮新增：插件目录/注册表/catalog/index/tap）
> 结论：先建"内部扩展框架 v1"（Java SPI），再建"出进程 Sidecar v2"，用 MCP 与 Skill 两个语言无关通道吸收生态

## 1. 生态调研结论（GitHub）

| 项目 | 生态规模 | 分发/发现机制 | 关键机制 |
|------|---------|--------------|---------|
| **opencode** | ~1680 插件（OpenDock 目录；awesome-opencode 精选） | npm `opencode-plugin` keyword 自动索引；config `plugin[]`；启动 Bun 安装在 `~/.cache` | 插件 API（V1 hooks/V2 Effect）；生态覆盖记忆/上下文剪枝/成本分析/OTel/密钥脱敏/安全网/通知 |
| **deepseek-harness** | 1716-1821 插件（dsh-plugin topic + awesome-dsh 聚合） | GitHub topic `dsh-plugin`；`dsh plugin --profile add github:owner/repo`（pnpm 前端）；profile/patch 组合 | "一切皆插件"（Cordis）；hook 扩展点丰富（oh-my-dsh 700+）；registry 属第三方（dshplugin.world） |
| **pi** | pi.dev/packages 目录（扩展/技能/prompt/主题） | npm `pi-package` keyword + `package.json` `pi` 字段；`pi install npm:x@1.2.3`/git@ref（**pin**） | jiti TS 扩展热重载；示例含 permission-gate/protected-paths/checkpoint/compaction/subagent |
| **Reasonix** | 插件包 + MCP + 兼容 Claude/Codex marketplace | `reasonix plugin install git:...`（dry-run/yes/link）；VS Code/Open VSX | **Manifest v2 + Sidecar 协议 v2**（NDJSON JSON-RPC、能力子集校验、帧/启动预算、FULL TRUST 提示）；MCP 安装即授权 + readOnly/destructive hints |
| **hermes-agent** | 官方 catalog + 社区 index（JSON）+ taps | `plugin-catalog/*.yaml`（**SHA pin + 人工评审 + 移除黑名单**）；`hermes plugins install <name>`；skills tap | 供应链治理标杆（pin 至少 2 周、CI 在 pin commit 校验、provenance sidecar、pin-to-pin update） |

**重要判断**：这些生态的插件代码（TS/Python/Go）**不能直接跑在我们的 Java 进程里**。吸收通道有四条：
1. **设计吸收**（把机制在我们的 Java Middleware/Tool 里实现）
2. **MCP**（语言无关的外部工具，直接可用）
3. **Skill**（SKILL.md 语言无关，AgentScope 原生支持）
4. **Sidecar**（v2 出进程协议，可运行任意语言扩展，需自研 Java 版宿主 SDK）

## 2. 共性机制提炼 → 我们的扩展框架设计

### 2.1 v1（M2）：Java SPI 内部扩展框架

| 能力 | 设计（吸收来源） | 验收 |
|------|----------------|------|
| 扩展点注册 | `ToolProvider` / `DataSourceProvider` / `Middleware` / `PromptContributor` 接口；`register()` 返回 **disposer**（opencode/deepseek） | 注册→卸载零残留测试 |
| 声明式清单 | `extensions.yaml`：id/type/capabilities/risk/timeout/idempotent/env（hermes capability 声明） | CI：**声明=实际注册**校验（hermes validate） |
| 组合与优先级 | 有序规则 + last-match-wins + 默认拒绝（opencode 权限模型/ D02） | 策略矩阵单测 |
| 热更 | 配置热更 fail-atomic（失败保留旧）；**变更延迟生效**（缓存友好，hermes） | IT：热更失败回退 |
| 隔离 | 每个数据源 Provider 独立 bulkhead（D02/E2.2） | 故障隔离 E2E |
| 观测 | 每个 Provider 调用一个 span + 成功率指标 | 指标断言 |

### 2.2 v2（M4+）：出进程 Sidecar（借鉴 Reasonix Extension Protocol v2）

- 协议：**NDJSON JSON-RPC over stdio**；`extension/initialize` 握手；**能力必须 ⊆ manifest 声明**，否则 `capability_not_declared`
- 预算：帧上限 8MiB；单代最多 4 个 sidecar、**共享 30s 启动预算**；generation 热更（失败保留旧 runtime）
- 安全：仅"通过插件流程安装"的扩展可启动 sidecar（项目配置不得声明）；诊断/错误输出过**凭据脱敏**；完整信任块提示
- 交付：自研 `xhs-ai-extension-sdk-java`（宿主侧）+ 协议文档；Go/Python SDK 可后置
- 禁区：**不引入 in-process full-trust 插件**（reasonix/pi 的教训，RV03 不采纳清单）

### 2.3 技能与发现（M3）：Tap / Catalog / Index 三层

| 层 | 吸收来源 | 我们的形态 |
|----|---------|-----------|
| Tap（技能仓库） | hermes `skills tap add`、AgentScope GitSkillRepository | `harness-skills` 作为第一 tap；开发/运维技能按团队分仓 |
| Catalog（审核目录） | hermes plugin-catalog | `xhs-ai/extensions-catalog/*.yaml`：SHA pin≥2 周、CI 校验、移除黑名单、provenance |
| Index（发现层） | hermes plugin-index / pi.dev / OpenDock | 内部 JSON index（可离线兜底），供控制台/CLI 查询 |
| 安装语义 | Reasonix（install=授权 + dry-run 预览） | `xhs-ai ext preview/install/enable`：预览能力与风险，安装与启用分离 |

## 3. 生态吸收短名单（具体机制 → 落地）

| 来源插件/机制 | 吸收方式 | 落到我们哪个模块 | 里程碑 |
|--------------|---------|----------------|--------|
| **Dynamic Context Pruning**（工具结果剪枝） | 设计吸收 | Compaction/`ToolResultEviction` 策略 | M2 |
| **Tokenscope / Context Analysis**（成本与上下文分析） | 设计吸收 | Observability 成本看板（已设计 D03） | M2 |
| **OTel exporter**（opencode 生态） | 设计吸收 | OTel→Langfuse（已设计） | M2 |
| **VibeGuard**（secret→占位符，调用后还原） | 设计吸收 | 脱敏管道（增强出网前双向处理） | M3 |
| **EnvSitter**（.env 防泄漏：只读指纹） | 设计吸收 | 工作区保护规则 + 红队用例 | M3 |
| **Safety Net / permission-gate / protected-paths**（危险操作拦截） | 设计吸收 | PolicyEngine 规则集（D02）+ 默认规则包 | M2 |
| **git-checkpoint / custom-compaction**（pi 示例） | 设计吸收 | 会话检查点（变更前快照）+ 压缩策略可插拔 | M3 |
| **MCP（Context7 等）** | 直接使用（白名单；M1.5 已启用） | Sidecar 协议 v2 | M4 |
| **hermes catalog/index/tap** | 设计吸收 | Catalog+Taps（§2.3） | M3 |
| **Reasonix 兼容层（Claude/Codex manifest）** | 设计吸收 | 扩展清单兼容：预留 `compat` 字段，不立即实现 | v2 |
| memory 插件（Honcho/Supermemory/mem0） | 观察（v2 评估） | AgentScope memory 为主，评测后再决定 | v2 |

## 4. 治理与安全（吸收生态的前置条件）

1. **供应链**：catalog SHA pin（≥2 周成熟期）+ 人工评审 + CI 在 pin commit 校验 + removed 黑名单 + provenance 记录；**MCP server 同样纳入**（npm 精确版本 / 镜像 digest pin）
2. **权限**：安装≠启用；启用需能力确认（tools/hooks/网络/文件/凭据）；组织策略可强制 deny
3. **隔离**：v1 仅声明式（无代码）；v2 代码扩展必须出进程 + 资源限额 + 网络白名单；**永不复刻 in-process full-trust**
4. **数据**：扩展出网内容过脱敏管道（D03）；审计记录扩展 id/来源/pin
5. **许可**：以**翻译重写**为主（功能合适即翻译，见 D09）；如需复用代码片段，保留版权声明并记录许可（MIT/Apache 可用；GPL/AGPL 隔离或避免）；Grafana MCP 许可待法务确认

## 5. 路线图与验收

| 里程碑 | 交付 | 验收 |
|--------|------|------|
| M2 | 内部扩展框架 v1（SPI+清单+CI 校验+disposer）+ 默认策略规则包 | 注册/卸载零残留；声明=注册 CI 绿；策略矩阵单测 |
| M3 | Catalog+Taps（技能与工具）+ VibeGuard/EnvSitter/Checkpoint 机制 | tap 安装/校验/回滚；红队含 .env/密钥场景 |
| M4 | Sidecar 协议 v2（Java SDK）+ 生态评估流程（MCP 已于 M1.5 启用） | 握手能力子集校验；kill sidecar 不影响主流程；季度评估报告 |
| v2 | 内部 marketplace（UI/CLI）+ 兼容层（Claude/Codex manifest 可选） | 预览/安装/启用/审计闭环 |

## 6. 对现有设计的修订（ADR 增量）

- **ADR-21**：扩展框架分两代——v1 Java SPI（声明式零信任）、v2 出进程 Sidecar（协议借鉴 Reasonix v2）；禁止 in-process full-trust
- **ADR-22**：生态吸收治理——catalog + SHA pin + 人工评审 + CI 校验 + 安装≠启用 + provenance；吸收方式优先级：设计 > MCP > Skill > Sidecar
