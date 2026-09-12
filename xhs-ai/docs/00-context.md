# 00 · 背景与边界

## 1. 背景

xhs 微服务工程（15 个服务 + 6 类中间件）已完成全量测试闭环（117/117）与 21 项运行态修复。当前痛点是：
- 故障排查依赖人工：日志/指标/trace/DLQ 分散在多个系统（Kibana、Prometheus、SkyWalking、RocketMQ Dashboard、XXL-Job）
- 系统知识散落在文档与代码中，新人与 AI 难以快速定位「谁负责什么、链路怎么走、为什么这样设计」
- 旧 AI 项目（`my-xhs-ai*`）验证过方向，但工程化程度不足，无法作为企业级底座继续演进

## 2. 旧实现（my-xhs-ai*）问题清单（重做原因）

| # | 问题 | 影响 |
|---|------|------|
| 1 | 文档/演示驱动：`demo-*.sh`、`FINAL-SUMMARY`、`HANDOFF` 等占主体，缺少可回归的测试与 CI | 无法证明"每次改动不退化" |
| 2 | 依赖丢失：`.env.local` 遗失、外部云库（21.130.247.89）不可达 | 不可复现运行 |
| 3 | 架构未收口：诊断、知识、Memory、Temporal PoC、MCP 多主线并行，边界模糊 | 维护成本高 |
| 4 | 自研 Harness（HITL/工具循环/状态机）与框架能力重复 | 重复造轮子、质量不可控 |
| 5 | 缺少企业级治理：多租户隔离、权限三态、审计、限流、降级 | 不敢对生产开放 |
| 6 | 评测缺失：知识问答仅 9 条用例，无持续评测机制 | 质量不可度量 |

> 结论：不继承旧代码；旧仓库中**知识资产**（业务分析、故障卡、代码卡片）作为输入迁移复用。

## 3. 系统边界

- **被诊断系统**：`xhs-project` 15 服务（gateway/user/content/product/cart/inventory/coupon/order/payment/notification/im/home/search/analytics/counter）+ 中间件（MySQL 分片、Redis Sentinel、RocketMQ、ES、Nacos、XXL-Job、Prometheus/Grafana、SkyWalking）
- **xhs-ai 自身**：独立服务（端口 19020），通过 HTTP/MCP 访问被诊断系统，不侵入业务代码
- **只读优先**：诊断查询只读；变更类动作（如 DLQ 重投）必须走 HITL 审批 + 审计

## 4. 术语表

| 术语 | 含义 |
|------|------|
| Harness | AgentScope 的工程化层：workspace/memory/skill/subagent/sandbox/Plan Mode |
| HITL | Human-in-the-loop，工具执行三态：allow / approve / deny |
| AC | 验收条件，每条可测试 |
| 诊断三联 | traceId → 最后命中服务 → 主类/方法 → 最近提交/blame |
| 知识三层 | architecture / business / code-structure |
