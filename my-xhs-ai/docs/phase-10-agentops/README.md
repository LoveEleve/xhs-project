# Phase 10: AgentOps + 可观测性

## 前置依赖

- **Phase 9 (评测)**：评测是 AgentOps 五层架构第三层

## 为什么第十

评测告诉你"Agent 质量怎样"（Phase 9），AgentOps 告诉你"Agent 在线上正发生什么"。本 Phase 建立完整的可观测 + 生产监控体系：LLM 调用链追踪 + 成本 Dashboard + 告警。

## 与 my-xhs 的关联

| AgentOps 需求 | my-xhs 已有 | 集成方式 |
|-------------|-----------|---------|
| LLM 调用链追踪 | SkyWalking | LLM call→Tool call→最终输出 全链路 |
| 指标采集 | Prometheus | TTFT/TPOT/Tool成功率/Token消耗 |
| Dashboard | Grafana | 成本 Dashboard+告警 |
| 网关 | LiteLLM (新增) | DeepSeek + 备选模型统一管理 |

## 学什么

| 模块 | 内容 |
|------|------|
| AgentOps 五层 | 网关→追踪→评测→护栏→监控 |
| LiteLLM | 100+模型+负载均衡+故障转移+成本追踪+语义缓存 |
| Langfuse | Agent Tracing+Prompt 管理+版本对比+A/B测试 |
| 指标 | TTFT/TPOT/Tool成功率/完成率/Token消耗 |

## 生产工程问题

| 问题 | 本 Phase 如何解决 | 验证方式 |
|------|-----------------|---------|
| **成本爆炸** | Token 配额+步数硬限制+单次成本上限→LiteLLM 实时追踪 | 单次调用成本 < $0.10，超出自动阻断 |
| **向量检索退化** | ES 查询延迟监控（P95）+自动触发索引重建 CronJob | P95 延迟 < 100ms，超阈值自动告警 |
| **漂移检测** | 生产 Scorecard 指标与 baseline 对比→偏离 > 10% 自动告警 | Grafana 告警规则生效 |

## 文档清单（6 篇）

| # | 文档 | 内容要点 |
|---|------|---------|
| 01 | agentops-architecture.md | AgentOps 五层架构详解：每层职责+工具选型+ my-xhs 集成点 |
| 02 | litellm-deep-dive.md | LiteLLM 源码级分析：100+模型统一接入+负载均衡+故障转移+成本追踪 |
| 03 | langfuse-deep-dive.md | Langfuse Agent 全链路 Tracing+Prompt 版本管理+A/B 测试+成本分摊 |
| 04 | agent-metrics.md | 全部指标定义：TTFT/TPOT/Tool成功率/完成率/Token消耗/P95延迟 |
| 05 | cost-dashboard.md | Grafana Dashboard：成本按用户/会话/任务维度+预算预警 |
| 06 | alerting.md | Prometheus AlertManager 规则：延迟>15s/错误率>5%/成本>$0.10 四类告警 |

## 代码结构

```
src/main/java/com/myxhs/ai/ops/
├── MetricsCollector.java        # 指标采集（TTFT/TPOT/Tool成功率/Token消耗）
├── TraceIdPropagator.java       # TraceId 透传（Agent→MCP Server→LLM API）
├── CostTracker.java              # 成本追踪（按用户/会话/任务维度分摊）
├── AlertManager.java             # 告警管理（Prometheus AlertManager 集成）
└── DashboardExporter.java        # Grafana Dashboard JSON 模板生成
```

## 验证

1. Agent 全链路可追踪 (LLM→Tool→输出)
2. 成本 Dashboard：按用户/会话/任务
3. 告警：延迟>15s/错误>5%/成本>$0.10 触发
4. LiteLLM 多模型故障转移正常

## 对后续的影响

- **Phase 11 (安全)**：AgentOps 第四层是护栏
- **Phase 13 (治理)**：监控数据是治理基础
