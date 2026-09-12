# RV03 · 插件生态映射与二次查漏补缺（2026-09-12）

> 方法：系统扫描 5 个仓库的插件目录/catalog，把"xhs-ai 计划功能"与"现成插件/外部服务"做映射，判定复用方式（直接使用 / 代码参考 / 概念借鉴），并做第二轮缺口检查

## 一、插件生态全景（扫描结果）

| 仓库 | 插件形态 | 值得注意的存量插件 |
|------|---------|------------------|
| hermes-agent | 内置插件 17 类 + **plugin-catalog 10 条 YAML + CI 校验** | **observability/langfuse**、kanban、memory（honcho/mem0/supermemory 等）、web（tavily/exa/brave/searxng/firecrawl/perplexity…）、browser（browserbase/browser-use/firecrawl）、cron_providers/chronos、context_engine、security-guidance、platforms |
| deepseek-harness | "一切皆插件"，~60 包 | **mcp-client**、**lsp/lsp-stdio/tool-lsp**、**session-query 家族**、**guard（repeat-tool-reminder + timeout-policy）**、**feedback 家族**、compaction（basic + tool-result-pruner）、plan-mode、subagent、workflow、schedule、code-runtime、sandbox、hooks 桥 |
| opencode | V1 hooks + V2 Effect 插件 + TUI feature-plugins + **MCP** | plugin API（tool/permission/auth/workspace adapter hooks）、MCP local/remote |
| pi | `.pi/extensions`（TS/jiti）+ chord facets | 扩展拦截（tool_call/tool_result 改写）、TUI 组件、prompt-url-widget |
| Reasonix | **插件包（manifest+sidecar）+ EXTENSION_PROTOCOL v2** | pluginpkg 包管理、sidecar 出进程扩展协议（NDJSON JSON-RPC）、主题插件、hooks 设置 |

## 二、功能映射：我们要实现的 ↔ 现成插件

### A. 可直接使用（外部服务/标准协议，不必重写）

| 我们的功能 | 现成资产 | 使用方式 |
|-----------|---------|---------|
| 代码导航（定义/引用/实现/hover） | **LSP 协议 + jdtls**（Java LSP server）；deepseek 的 lsp 包给出四操作归一化设计 | 引入 `lsp4j` 客户端 + 启动 jdtls；工具 schema 稳定，换 server 不影响模型请求 |
| 外部工具扩展（filesystem/git/db/fetch…） | **MCP 生态**（AgentScope 内置 MCP SDK） | MCP server 白名单 + server-qualified 工具命名 + env 清洗；只桥接 Tools |
| LLM 调用追踪 | **Langfuse**（AgentScope `OtelTracingMiddleware` + OTLP） | OTLP 端点指向 Langfuse；采纳 hermes 插件的 capture/redaction 约定 |
| 外网检索（可选） | hermes web 插件族（tavily/exa/brave/searxng…） | v2 评估；v1 不需要 |
| 长期记忆增强（可选） | hermes memory provider ABC（mem0/hindsight…） | v2 评估（AgentScope 自带 memory 为主） |

### B. 代码参考（照其设计实现，不引入代码）

| 我们的功能 | 参考实现 | 提取的设计 |
|-----------|---------|-----------|
| 可观测 + 数据治理（G1） | `hermes/plugins/observability/langfuse`（1028 行） | **三级捕获模式** metadata/sanitized/full（默认 sanitized：先脱敏再截断）；capture_mode 写入每条 trace；failsafe 包装（遥测永不阻塞回合）；**TraceState 上限 256 + LRU 驱逐**防泄漏；key 前缀校验（pk-lf-/sk-lf-）；usage 字段映射含 cache/reasoning tokens；错误与关停覆盖 |
| 循环卫生（新） | deepseek `guard`（repeat-tool-reminder + timeout-policy） | 重复工具调用提醒（改变策略或结束）+ 工具超时策略（声明超时；超时返回明确错误而非挂起） |
| 会话/审计查询（G9） | deepseek `session-query` 家族 | 统一查询服务（精确读/过滤/关系追溯/全文检索）+ **模型可用的查询工具** + 导出 ZIP；搜索与模型可见历史一致 |
| 反馈回流（G8） | deepseek `feedback` 家族 | 会话备注 + 逐消息评分；**反馈永不注入模型**；反馈门控发布 |
| 任务队列/工单（DLQ/HITL） | hermes `kanban`（dispatcher + dashboard API + systemd 单实例） | 单 dispatcher + 原子 claim + failure_limit 自动 block + request_changes 人工审批形态 |
| 插件包治理（G12/G14） | Reasonix `pluginpkg` + `EXTENSION_PROTOCOL v2` | manifest 能力声明不得超集、sidecar 出进程协议（帧上限 8MiB、30s 启动预算、最多 4 并发）、generation 热更 |
| 企业策略钩子 | deepseek `hooks` 桥（Claude Code/Codex 兼容） | 事件协议 + matcher（方言化）+ merge（deny>ask>allow）+ runner 永不 throw |
| MCP 治理（新） | deepseek `mcp-client` | server-qualified 稳定命名、每 server 一条配置、环境清洗、仅 Tools 桥接 |
| 插件目录隔离（D8） | hermes `plugin_storage` | 数据目录与安装目录分离 + 名称白名单 + SQLite WAL |
| 插件目录/供应链（ADOPT-8） | hermes `plugin-catalog` + CI | YAML catalog + SHA pin + 变更条目在 pin commit 上跑 validate |

### C. 概念借鉴（只借思想）

| 来源 | 概念 | 用途 |
|------|------|------|
| pi | chord facets（host 进程独立 bundle、provide/use 服务图、逆序销毁） | 多能力组合的卸载顺序设计 |
| deepseek | 一切皆插件 + profile/bundle/patch 分层 | 我们的"环境/客户 profile + 工具清单"分层 |
| opencode V1 hooks | tool/permission/auth/workspace 四类 hook | 中间件事件点对照表 |
| reasonix | 审批 autoDrain / 会话授权 snapshot-restore | 审批策略与恢复（已入 DELTA-5） |

## 三、二轮查漏补缺（新增缺口 G15-G20）

| # | 缺口（新） | 严重度 | 补法 |
|---|-----------|--------|------|
| G15 | 可观测数据治理未落地（capture/redaction/failsafe/状态上限） | P0（并入 G1） | 按 hermes langfuse 插件设计《观测与内容捕获规范》：三模式 + 脱敏正则 + failsafe + 上限驱逐 |
| G16 | 循环卫生缺失（重复调用/无超时） | P1 | 引入 Guard：repeat-tool-reminder + 每工具 timeout 声明与执行 |
| G17 | 代码导航技术路线未定（原先仅 JGit 静态） | P1 | 升级为 **LSP（jdtls）+ JGit 双轨**：LSP 负责定义/引用/实现/hover，JGit 负责 blame/历史 |
| G18 | MCP 集成与治理未设计 | P1 | MCP server 白名单 + server-qualified 命名 + env 清洗 + 工具 schema 审查 + 生命周期（与审批/审计打通） |
| G19 | 会话/审计查询形态未定义 | P1 | 采用统一查询服务 + 模型查询工具 + 导出；存储用 MySQL FTS/ES（替代 SQLite FTS5） |
| G20 | Bad case 反馈闭环缺失 | P2 | 反馈模块（会话备注 + 消息评分，永不注入模型）+ 定期沉淀入评测集 |

## 四、对 RV01 缺口的修订

- **G1（数据合规）**：从"待设计"升级为"有成熟参照"（hermes capture modes + 脱敏；且 Langfuse 可自托管避免外发）
- **G5（工具治理）**：吸收 deepseek guard 的 timeout-policy（工具声明超时 + 明确超时错误）
- **G9（审计查询）**：采用 session-query 形态（统一服务 + 模型工具 + 导出）
- **G8（评测）**：反馈回流（G20）作为 ground truth 来源之一
- **新增技术路线决策**：代码导航 LSP 化（G17）；MCP 作为外部工具扩展标准（G18）

## 五、可执行结论

1. **不重写**：LSP 服务器（jdtls）、Langfuse（OTLP）、MCP 服务器 → 直接用
2. **照设计实现**：观测捕获/脱敏、Guard、会话查询、反馈、kanban 式工单、插件治理
3. **下一步**：把 G15-G20 与 ADOPT/DELTA 一并折入 P2（需求工程）/P3（架构）——其中 G15/G17/G18/G19 需各出一页专项设计
