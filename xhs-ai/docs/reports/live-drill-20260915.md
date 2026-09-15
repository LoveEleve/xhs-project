# AI 实测：真实流量 + 构造数据 + 5 项端到端（2026-09-15）

> 方式：对运行中的 15 微服务平台构造流量与异常数据，再由 xhs-ai Agent 完成真实诊断/检索/变更闭环
> 结论：**5/5 场景完成**；过程中发现并修复 2 个问题（MCP 自愈误判、ES 检索参数），余 1 项转 backlog

## 1. 流量与数据构造

| 项 | 方式 | 结果 |
|----|------|------|
| 真实流量 | `scripts/traffic-gen.sh`（登录→11 个读接口 + 点赞/收藏/发笔记/评论写接口） | 300 轮 ≈3.4k 请求，42s；200×3084，404×300（1 个未对上的 notify 路径） |
| 数据写入 | 发笔记/评论/点赞/收藏 | 新增笔记/评论/互动数据，触发搜索索引同步等 MQ 链路 |
| 异常数据 | Spring Boot PropertiesLauncher 注入器（无 mqadmin 时的替代路径）向 CART_TOPIC 投 2 条非 JSON 毒丸消息 | 重试耗尽后进入 `%DLQ%cart-sync-consumer-group` |

## 2. AI 实测结果（Agent 端到端）

| # | 场景 | 耗时 | 结果 |
|---|------|------:|------|
| 1 | DLQ 清单与积压 | 33s | ✅ 列 2 组共 12 条：cart-event-sink-group 10（历史）、cart-sync-consumer-group 2（本次注入） |
| 2 | 死信根因分析 | 98s | ✅ 准确判定毒丸消息（非 JSON 反序列化失败），明确"重投仍会失败、不能靠重投恢复" |
| 3 | ES 日志检索（ERROR Top 服务） | 107s | ✅ 41 条 ERROR 全来自 xhs-ai（重启风暴期间），附时间/logger/摘要证据 |
| 4 | Prometheus 实例健康 | 92s | ✅ `up==0` 为空；24 个 target 全 up（list_targets 交叉验证） |
| 5 | HITL 重投闭环 | 115s+121s | ✅ 审批 21：pending→approved→重投（SEND_OK，队列坐标入库）→ 核验器判定 **reentered_dlq**（毒丸二次入死信，无误报成功） |

## 3. 过程中发现与修复

| 级别 | 问题 | 根因 | 处置 |
|------|------|------|------|
| P0 | MCP 自愈误判引发**重启风暴**（NRestarts=24） | 存活探测只看直接子进程且 2 连击即处置，进程树/命令行抖动误伤 | 改为全进程扫描 + 3 连击；**默认仅告警不重启**（`self-restart-enabled=false`），需显式开启；已部署验证 NRestarts=0 |
| P1 | ES `search` 工具连续 3 次参数校验失败（缺 index/queryBody） | qwen3.8-flash 不会填嵌套 DSL schema | 系统提示补 12.1 显式示例；修复后同一问题成功（并在提问中加"只查一次"约束稳定耗时） |
| P2 | 日志类问题耗时波动（107s~240s+） | 模型多轮试错 | ✅ 当日已交付：新增 `log_top_services` / `log_search` 两个业务级工具（服务内部拼 DSL、参数扁平），复测 130s 且输出含"突发 vs 持续"定性分析（见 §5） |

## 4.1 简化版 ES 查询工具（RV21，已交付）

| 工具 | 参数 | 能力 |
|------|------|------|
| `log_top_services` | level(默认ERROR)/minutes(默认60)/topN(默认10) | 按 `APP_NAME.keyword` 聚合日志条数 TopN |
| `log_search` | service?/level?/keyword?/minutes?/size? | 时间倒序明细（时间/服务/级别/logger/消息，消息截断 300 字） |

- 设计：业务参数 → 服务端拼 DSL（`LogQueryBuilder`，参数强校验 clamp），ES DSL 不再暴露给模型；REST 调用复用 `MYXHS_ES_*` 凭据；工具只读 + 审计留痕。
- 验证：单测 3 例（聚合字段/过滤器/默认值），总计 33/33；Agent 复测 130s 正确输出（并主动区分启动突发与持续报错）。

## 4.2 简化版 Prometheus 指标工具（RV22，已交付）

| 工具 | 参数 | 能力 |
|------|------|------|
| `metric_top` | metric(error_rate/qps/latency_p95/slow_uri/heap_mb) + service? + topN? | 近 5m TopN（错误率%/QPS/P95 ms/慢接口/堆 MB） |
| `metric_trend` | metric(error_rate/qps/latency_p95) + service + minutes? | 时间序列趋势 + min/max/avg/last（判断突发/持续） |

- 设计：白名单指标目录 → `MetricQueryBuilder` 拼 PromQL（参数 clamp + 服务名白名单字符集），PromQL 不暴露给模型；REST 只读 + 审计。
- 前后对比（同题）：

| 问题 | 裸 PromQL（改前） | 业务级工具（改后） |
|------|------------------|------------------|
| 各服务 5xx Top3 | 63s | **52s** |
| P95 最慢接口 Top5 | 98s 且只查到 actuator 端点 | **50s**，`/api/order/refund-success` P95≈11.9ms |
| QPS 趋势（新增能力） | 无（模型不会 range 聚合） | **33s**，正确区分冷启动爬坡与平台期 |

- 测试：`MetricQueryBuilderTest` 6 例；累计 **39/39**；工具总数 31（11 自研 + ES 3 + Prom 17）。

## 4.3 消费积压诊断工具 + 全链路下单支付（RV23，已交付）

| 项 | 方式 | 结果 |
|----|------|------|
| 新增工具 | `consumer_lag_top(topN)`：按 `%RETRY%<group>` 自动发现消费组、汇总 broker/consumer 位点差 | 单测覆盖组解析；累计 40/40 |
| 造积压 | 停 my-xhs-counter → broker 容器内注入器发 80 条 LIKE 消息 | `mqadmin consumerProgress` 独立核验 Diff=80 |
| AI 诊断 | 问"哪个消费组积压最多/可能原因" | ✅ 113s 命中 `counter-consumer-group` lag=80（9 队列/maxQueueLag=10），并排除队列倾斜 |
| 恢复 | 重启 counter | 积压 80→0，消费无死信 |
| 全链路数据 | `scripts/order-flow.sh`：登录→地址→5 单创建→Mock 支付 | ✅ 5/5 单支付成功（orderId/orderNo/金额入库） |

> 工具总数 32（12 自研 + 20 MCP），已达软预算 32——下一项工具前须先做 token 预算或 tool_search。

## 4.4 按用户 Token 预算 + 工具 schema 预算（RV24，已交付）

| 能力 | 设计 | 实测 |
|------|------|------|
| 硬限拒绝 | 日预算 20 万，用尽返回 429 + 明确文案 | 预置 99.9 万 → `429 今日 AI 用量已用完（999999999/200000 tokens）`，指标 `hard_reject=1` |
| 软限切换 | 软限 80%（16 万）自动切轻量模型 | 预置 17 万 → 正常答复，`soft_switch=1`，日志标记 Token预算切换 |
| 真实计量 | 模型网关 usage 双通道累计，Redis TTL 2 天 | 正常问答后 `GET budget:99:YYYYMMDD`=**5577**，TTL=172799s |
| 工具 schema 预算 | 启动估算 schema tokens，Gauge + 软限 12k 告警 | `ai_agent_tool_schema_tokens=3428`（32 工具），远低于阈值 |

> 边界：预算仅覆盖 Agent 路径（`/chat` 无用户上下文不计量）；Redis 读取失败 fail-open（只影响成本控制，不阻断诊断）。

## 5. 证据

- 原始响应：`/tmp/opencode/drill-dlq-{1,2}.json`、`drill-log-{1,2,3}.json`、`drill-prom-1.json`、`drill-redeliver-1.json`、`approval-21-reply.json`（本机临时目录，关键结论已摘录本报告）
- 审批链：`ai_approval id=21`（approved→executed→verification reentered_dlq）；审计与 settlement 全量入库
- 复现：`bash xhs-ai/scripts/traffic-gen.sh 300 25`；注入器源码 `/tmp/opencode/Inject.java`（PropertiesLauncher 方案）
