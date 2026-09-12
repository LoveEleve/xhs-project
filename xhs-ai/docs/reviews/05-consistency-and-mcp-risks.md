# RV05 · 查漏补缺（D08/D09/ADR-23 后一致性 + 新风险）

> 方法：对最近三轮变更（扩展框架 D08 / 功能采纳 D09 / ADR-23 MCP 一等公民）与既有 17 份文档做交叉一致性检查 + 新风险识别
> 结论：发现 **9 处一致性缺口**（已修正）+ **1 个重大新风险（工具集膨胀/上下文预算）**（已补设计）

## 一、一致性缺口（已修正）

| # | 缺口 | 表现 | 修正 |
|---|------|------|------|
| C1 | 04-engineering 仍写"禁止 text2sql" | 与 ADR-23"受控只读查询"冲突 | 改为三层防护受控只读（AST+READ ONLY 事务+只读账号+行数/超时/脱敏/审计） |
| C2 | D05 仍写"v1 不接入外部 MCP" | 与 ADR-23 v1.5 直接接入冲突 | 更新为 v1.5 接官方 ES/Prom/Grafana；社区 MCP 走翻译/T1/T2 |
| C3 | D08 许可策略"只吸收设计不复制" | 与 D09"功能合适即翻译"冲突 | 改为翻译重写为主；小段复用需版权声明+许可记录（GPL 隔离）；MCP 纳入 pin |
| C4 | D06 FMEA 缺 MCP 故障 | MCP 成一等公民后无失败模式 | 新增 F16 MCP 挂/超时/schema 漂移、F17 工具集膨胀、F18 受控 SQL 绕过 |
| C5 | 03-test-design MCP 用例标注"预留" | 与 v1.5 启用不符；缺 SQL 负向 | TC-CT-MCP-01 更新；新增 TC-IT-DB-01 / TC-E2E-MCP-01 / TC-PERF-TOOL-01 |
| C6 | 04-engineering 无 MCP 运行形态 | Node/Python server 部署空白 | 新增 E5.9/E5.10（容器化/本地绑定/pin/健康/工具治理） |
| C7 | D07 容量无 MCP footprint | 资源与成本模型不全 | 新增 MCP server 内存/磁盘预算 + 工具 schema token 成本提示 |
| C8 | PRD 范围无 v1.5 | 里程碑与需求不同步 | v1 包含新增 v1.5 条目（官方 MCP + 受控只读） |
| C9 | RV04 E6.2 历史条目未标注修订 | 阅读时误导 | 加删除线 + 指向 ADR-23 |

## 二、重大新风险与设计补充

### R1 工具集膨胀 → 上下文/成本/选择混乱（P0）

- **问题**：MCP 工具数量可达 **60+**（Prometheus ~20、Grafana 多、ES ~5、XXL-Job 12-20、RocketMQ、MySQL），而工具 schema **每次请求全量下发**（hermes 明示核心工具全发）→ 输入 token 暴涨、模型误选工具、成本上升
- **补充设计（D02 §11）**：
  1. **场景化 Toolset**：诊断 / 知识 / 运维三套，按意图路由装载
  2. **渐进加载**：先暴露 `tool_search`，命中后动态激活（借鉴 pi `kimi-deferred-tools`、opencode 技能懒加载）
  3. **白名单**：`tools.json` allow（保留内置工具）；MCP 工具 server-qualified 且显式 allow
  4. **schema 预算**：每工具集上限（如 ≤8k tokens），超限裁剪说明并告警
- **验证**：TC-PERF-TOOL-01 统计各工具集 schema token；FMEA F17

### R2 MCP 供应链 pin（已并入 D08 §4）

MCP server 纳入 catalog：npm **精确版本**或容器镜像 **digest pin**；升级走评审；removed 黑名单同样适用。

### R3 MCP 结果不可信（并入威胁模型）

MCP 返回内容（日志/DB 行）与外部内容同等对待：数据块隔离、禁解释为指令、结果脱敏（D03/G2 已覆盖，补测 TC-RED-01/02 的 MCP 变体）。

## 三、仍开放的项（不阻塞，登记）

| # | 项 | 处置 |
|---|---|------|
| O1 | Rerank 精排（当前仅 hybrid） | v2 评估（bge-reranker 等） |
| O2 | 审批离线提醒（webhook/IM） | v2（SSE 已覆盖在线；离线靠下次上线提示） |
| O3 | Agent 主动发现技能/工具（marketplace 内搜索） | v2（v1 用静态 catalog + tool_search） |
| O4 | Grafana MCP 许可法务确认 | 接入前置（D09 §5） |
