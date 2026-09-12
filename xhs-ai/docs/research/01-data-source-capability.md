# R01 · 数据源能力矩阵（Phase 1 实测）

> 日期：2026-09-12｜方法：对 xhs 环境逐项实测（能调通/字段/限制），作为诊断工具设计依据

## 1. 日志（ES）— 诊断主力

- 端点：`http://192.168.0.142:19200`（Basic: elastic）
- 索引：`myxhs-logs-YYYY.MM.DD`（按天滚动；09.12 已 1.3w+ 条，09.11 7.6k 条）
- **已验证字段**：`@timestamp` / `APP_NAME` / `level` / `message` / `stack_trace` / `traceId` / `userId` / `logger_name` / `thread_name` / `log_source` / `host`
- 能力：按 `APP_NAME + traceId + level + 时间窗` 精确检索；`stack_trace` 支持异常根因提取
- 限制：单索引滚动（跨天需索引模式 `myxhs-logs-*`）；正文全文检索性能待压测
- 设计影响：`log.search` 工具首选数据源；traceId 诊断第一跳（traceId → 命中服务 + 日志时间线）

## 2. 指标（VictoriaMetrics ✅ / Prometheus ?）

- VictoriaMetrics：`http://127.0.0.1:8428/api/v1/query` **实测可用**（`count(up)=23`）
- Prometheus 容器存在（`my-xhs-prometheus`），9090 返回异常（待复核端口/为 vmagent 可能）
- 服务自身 `actuator/prometheus` 全部可访问（bench 时已用）
- 能力：PromQL 查询（QPS/延迟/错误率/JVM/线程池/连接池/DLQ 积压 `rocketmq_dlq_backlog`）
- 限制：需确认数据保留窗口与 label 规范（`application`/`consumer_group` 已知）
- 设计影响：`metric.query` 基于 PromQL；DLQ 积压可直接用 `rocketmq_dlq_backlog`（避免重复实现监控）

## 3. 链路追踪（SkyWalking OAP，待深化）

- 容器：`my-xhs-skywalking-oap`（12800 端口 `/` 404 = 正常，需 GraphQL 端点）
- 已知：SkyWalking Agent 已接入（业务 traceId 通过 Correlation 打到 span tag）
- 待办：验证 GraphQL 查询（traceId → span 树/服务拓扑）；若不可用则降级为「ES 日志 traceId 时间线 + 代码知识」
- 设计影响：`trace.query` 工具 v1 以 ES+代码为主、SW 为增强；避免强依赖

## 4. 消息（RocketMQ 5.1.4）

- NameServer `192.168.0.142:9876`；Broker `11911`；Dashboard `18081`（`/rocketmq-dashboard` 上下文 + CSRF）
- 已验证：DLQ topic 查询/积压指标/重投概念；Dashboard POST 需 CSRF（403），**不适合作为服务端集成**
- 设计影响：`dlq.*` 工具用 **RocketMQ Admin SDK（`rocketmq-tools`）直连 NameServer**，不仅查积压/详情，重投走审批；Dashboard 仅人工兜底
- 待办：Maven 依赖验证 + 只读/重投权限最小化

## 5. 调度（XXL-Job）

- Admin `18080/xxl-job-admin`（登录 + `/jobinfo/trigger` 已验证可用）
- 能力：任务列表/触发/日志查询
- 设计影响：`job.status`（只读）/ `job.trigger`（approve 才允许）
- 待办：登录态维护策略（服务端短期 cookie vs 复用 token 机制）

## 6. 业务库（MySQL）

- 分片：`my_xhs_order_0..3`（16 表分片）、各业务库齐全；`my_xhs_ai` 待建
- 设计影响：只读账号按库最小授权（order/payment/content/analytics/inventory）；AI 自身库独立账号
- 待办：建库建号脚本（Flyway 管理 AI 库表）

## 7. 代码与知识资产

- 代码仓：`/data/workspace/xhs-project`（本地 Git；JGit 可做 blame/最近提交）
- 旧项目资产：`my-xhs-ai` 共 278 文件（business-analysis 137 / knowledge 69 / docs 59）
  - 故障卡（failure yaml）仅 1 张 → **知识资产以正文为主、结构化不足**，需治理后入知识卡（BM25；D12）
- 设计影响：知识入库管线必须含"来源标注 + 分层（架构/业务/代码）+ 去重 + 时效"；代码知识用 JGit 动态生成而非静态文档

## 8. 模型与 Embedding（已验证）

- LLM：siyu-all `https://siyu.site/v1`（OpenAI 兼容），`deepseek-v4-pro`（推理模型，含 reasoning_content）/ `deepseek-v4-flash`
- Embedding：火山方舟 `doubao-embedding-vision-large`（2048 维，单条 0.35s / 批量 0.23s）
- 设计影响：模型网关抽象（provider 可切换/降级）；向量维度已验证，mapping 在评测触发启用向量后再定

## 9. 能力缺口（需要补齐的探测）

| # | 缺口 | 下一步 |
|---|------|--------|
| 1 | SkyWalking GraphQL 可用性 | 实测 `/graphql` 查询 traceId |
| 2 | RocketMQ Admin SDK 在 xhs-ai 的可用性 | Maven 拉取 `rocketmq-tools:5.1.4` + 连通性验证 |
| 3 | Prometheus 与 VictoriaMetrics 的关系 | 复核 9090 服务身份与数据源配置 |
| 4 | ES 检索性能基线（日志大索引；向量为实验项） | 方案定后做专项压测 |
| 5 | 审计/审批在 AgentScope 的挂起与恢复边界 | 对照 AgentScope 2.0 文档 + 源码验证 |
