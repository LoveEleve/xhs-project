# D09 · 功能驱动的生态采纳规划（先看功能，合适就翻译）

> 方法修正（用户指正）：不看代码看**功能**——列我们的功能清单 → GitHub 检索现成插件/MCP → 功能匹配就**直接使用或翻译移植**
> 结论：诊断工具层存在大量**现成对标实现**（含官方 MCP），可显著加速 M1/M2；同时吸收"受控只读 SQL"等成熟防护模式

## 1. 匹配总原则

| 判定 | 采纳方式 | 成本 |
|------|---------|------|
| 功能一致 + **官方/成熟** + 治理可插入（审批/审计/脱敏） | **MCP 直接使用**（白名单+pin） | 最低 |
| 功能一致 + 社区实现 | **翻译移植到 Java**（逻辑照搬，代码重写） | 低（翻译成本低） |
| 部分一致 / 领域特有（DLQ 重投、审批） | **借鉴设计 + 自研** | 中 |
| 无对标 | 自研 | 高 |

## 2. 功能匹配矩阵（我们的功能 ↔ 现成实现）

| 我们的功能 | 现成实现（GitHub） | 成熟度/许可 | 采纳方式 | 里程碑 |
|-----------|------------------|------------|---------|--------|
| **日志检索**（按服务/级别/traceId/时间窗） | `elastic/mcp-server-elasticsearch`（**官方**：search/esql/mappings/shards）；`vanovarderesyan/elastic-mcp-server`（search_logs/aggregate_logs/cluster_health/get_document）；`nizovtsevnv/kibana-mcp-server`（get_log_context） | 官方/活跃 | **MCP 直接用** + 检索参数模型翻译 | M1.5 |
| **trace 关联** | `stackbooster/otel-mcp-elastic`（跨信号 traces/metrics/logs）；`poulsbopete/mcp-server-elasticsearch`（analyze_traces） | 活跃 | MCP 直接用（ES 侧） | M1.5 |
| **指标查询**（PromQL/告警/目标） | `prometheus/prometheus-mcp`（**官方**：query/range_query/labels/alerts/rules/series + runbooks）；`grafana/mcp-grafana`（**官方**）；`bcfmtolgahan`（38 工具：Golden Signals/异常检测/容量预测） | 官方 | **MCP 直接用**（VM 兼容 Prometheus API） | M1.5 |
| **数据库只读查询** | `mir-shakir/readonly-db-mcp`（**三层写保护**：parser + `START TRANSACTION READ ONLY` + 只读账号；审计日志；PII 脱敏）；`matpb/mysql-mcp-rs`（sanitizer+自动 LIMIT+超时）；`qxduddes`（fail-closed HTTP，凭据不入模型） | 社区/较成熟 | **翻译三层防护到 Java** `db.query`（替代"一刀切禁 SQL"） | M2 |
| **DLQ 查询/重投** | `weihubeats/rocketmq-mcp`、`francisoliverlee/rocketmq-mcp`（viewMessage/examineConsumeQueue）；rocketmq-dashboard DLQ 管理（批量重发/导出） | 社区 | 借鉴 + 自研（重投必须走审批+核验） | M2 |
| **定时任务管理** | `wushiyuanmaimob/xxl-job-mcp`（20 工具含 trigger/logs）；`shaguocgl/mcp-xxl-job`（12 工具；**自动登录/Cookie 会话/读-合并-写回防字段丢失**） | 社区/生产级 | **翻译会话与读改写策略**到 Java | M2 |
| 上下文膨胀/成本 | `Dynamic Context Pruning`（工具结果剪枝）、`Tokenscope`/`Context Analysis`（token/成本分析）、`snip`（命令输出裁剪 60-90%） | 活跃 | **翻译为策略**（工具结果剪枝阈值/成本看板） | M2 |
| 密钥泄漏防护 | `VibeGuard`（secret→占位符，调用后还原）、`EnvSitter`（.env 只读指纹） | 活跃 | **翻译为脱敏管道增强 + 工作区保护规则** | M3 |
| 危险操作拦截 | `CC Safety Net`（危险 git/fs 命令）、`guardme`（deny-first 护栏）、pi `permission-gate/protected-paths` | 活跃 | 翻译为 **PolicyEngine 默认规则包** | M2 |
| 代码检索/RAG | `OpenCodeRAG`（tree-sitter 分块 + LanceDB）；Context7 MCP（官方文档） | 活跃 | 借鉴分块器；Context7 可挂 MCP | M3 |
| 记忆 | `Agent Memory`/`Honcho`/`Supermemory`/`mem0`（MCP） | 活跃 | v2 评估（AgentScope memory 为主） | v2 |
| 子代理/编排 | `Background Agents`、`oh-my-opencode`、`CrewBee`、`FlowDeck`（25 agent 四阶段） | 活跃 | v2 评估（AgentScope subagent 为主） | v2 |
| 调度/通知 | `opencode-scheduler`（systemd/launchd）、`opencode-notify` | 活跃 | 借鉴；我们已有 XXL-Job/自愈 | M3 |

## 3. 由此产生的重大设计修订

1. **MCP 从"v2 预留"→"v1.5 直接接入"**：优先接入**官方** ES/Prometheus/Grafana MCP；AgentScope 原生 MCP 客户端承载；白名单 + server-qualified 命名 + env 清洗（D05 不变）
2. **D02 工具治理修订**：数据库访问从"禁止 text2sql"升级为**受控只读查询**——吸收三层防护：
   - ① 解析层：仅 SELECT/SHOW/DESC/EXPLAIN/WITH；禁多语句/INTO OUTFILE/SLEEP/锁
   - ② 引擎层：`START TRANSACTION READ ONLY`（MySQL 报 1792 拒绝写）
   - ③ 账号层：只读 GRANT（最小授权）
   - 叠加：行数/超时限制、结果脱敏、全量审计、按用户限流
3. **DLQ/XXL-Job 采用"翻译+治理"**：功能逻辑照搬社区 MCP（会话维护/读改写），但重投/触发进审批状态机（D02），执行后核验
4. **MCP 运行时形态**：Node/Python MCP server 以**容器/子进程**运行（不依赖宿主环境），纳入 compose + 自愈脚本 + Prometheus 抓取

## 4. 移植/翻译清单（功能合适 → 翻译不难，本项目实际要写的）

| # | 移植目标 | 来源（功能对标） | 翻译到 | 验收 |
|---|---------|----------------|--------|------|
| T1 | 受控只读 SQL | readonly-db-mcp / mysql-mcp-rs | Java `db.query` + 三层防护 + 审计 | 写操作 100% 被拒（负向 E2E） |
| T2 | XXL-Job 会话与读改写 | shaguocgl/mcp-xxl-job | Java `job.*`（自动重登/Cookie/防字段丢失） | 触发/查询 E2E；并发会话失效重登 |
| T3 | 工具结果剪枝 | Dynamic Context Pruning | Java 剪枝策略（阈值+陈旧判定） | token 降幅实测；不影响结论正确性 |
| T4 | 成本/上下文分析 | Tokenscope / Context Analysis | 成本看板（已有 D03）对齐其指标口径 | 指标齐全 |
| T5 | 密钥占位符还原 | VibeGuard | 脱敏管道：出网占位、回填还原 | 单测+出网扫描 0 泄漏 |
| T6 | 危险操作规则包 | CC Safety Net / guardme / pi permission-gate | PolicyEngine 默认规则包 | 红队用例全拦截 |
| T7 | .env/工作区保护 | EnvSitter / protected-paths | 工具白名单+工作区规则 | 负向 E2E |

## 5. 供应链与许可（采纳前置）

| 来源 | 许可 | 处理 |
|------|------|------|
| Elastic / Prometheus / Grafana 官方 MCP | Apache-2.0 / AGPL?（Grafana 需核对） | 优先 MCP 直接使用；**Grafana MCP 许可需法务确认**后才接入 |
| 社区 MCP（rocketmq/xxl-job/mysql） | 多为 MIT/Apache | 翻译逻辑前记录来源与许可；代码不复制（重写） |
| opencode/pi 插件（TS） | 多为 MIT | 仅吸收设计/翻译逻辑；若直接复用小段代码须保留版权声明 |
| hermes（Python） | MIT? | 同上 |

## 6. 风险（进入 FMEA）

| 风险 | 缓解 |
|------|------|
| MCP 把库表数据暴露给模型 | 查询结果脱敏 + 行数/字段白名单 + 审计（D03） |
| 社区 MCP 质量参差 | catalog+pin+成熟期；优先官方；上线前契约测试 |
| MCP server 运行时依赖（Node/Python） | 容器化部署 + 健康检查 + 熔断（FMEA F8/F9 扩展） |
| MCP SDK 0.17 兼容 | 已在依赖矩阵登记；升级路径明确 |
| 数据出境（外部 MCP SaaS） | 一律自托管；白名单禁外网 |

## 7. 路线图修订

| 里程碑 | 新增内容 |
|--------|---------|
| M1.5 | 官方 MCP 接入验证（ES/Prometheus/Grafana）+ MCP 治理（白名单/命名/env 清洗/熔断） |
| M2 | T1/T2/T3/T4/T5/T6/T7 翻译落地 + 受控只读 SQL 替换原方案 |
| M3 | 代码 RAG 分块器借鉴；Context7；调度/通知借鉴 |
| v2 | 记忆/子代理生态评估 |
