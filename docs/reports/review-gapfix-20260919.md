# Review 查漏补缺（2026-09-19）：5 项发现 + 处置

> 方法：静态审计（grep/配置对照）+ 运行时实证（直连 JWT/指标/cron）+ 构建验证（全量 clean package）。
> 本轮聚焦"遗留项与观测缺口"，均已修复/验证并全量发布（22:12 完成，15/15 健康）。

## 发现与处置

### ① jwt.secret 配置漂移（发现→统一）
- **现状**：11 个服务把 `jwt.secret` 写死为 36 位 fallback（content/order/payment/product/search/inventory/coupon/notification/home/analytics/counter），cart 完全没有 `jwt:` 段；仅 user/im 使用 `${JWT_SECRET:...}`。
- **实证**：Spring relaxed binding 使环境变量 `JWT_SECRET`（64 位）覆盖 yml 写死值——**行为无 bug**（直连 cart/order 携带有效 JWT + 伪造 `X-User-Id:1` 均 200 且身份被覆盖）。
- **风险**：单服务改动/轮换时静默漂移（跨服务 token 校验断裂）；且属硬编码密钥卫生问题（F-019 同类）。
- **处置**：11 个服务统一为 `${JWT_SECRET:MyXhs@2026#JwtSecretKey!ForTokenSign}`，cart 补齐同款；全量发布后复测 3 服务（cart/order/user）直连覆盖 200。

### ② my-xhs-test 模块破坏全量构建（修复）
- **现状**：`SendCompensation` 用原生 RocketMQ Producer，但 pom 未声明依赖 → `mvn package` 全 reactor 失败（此前一直用 `-pl '!my-xhs-test'` 绕过）。
- **处置**：补 `rocketmq-spring-boot-starter`（版本由 root pom 管理）。`mvn -DskipTests clean package` 全绿，含 my-xhs-test jar。

### ③ DlqMessageHandler 死代码（清理）
- **现状**：类无任何调用方（无消费者消费 `%DLQ%` topic），但 `MetricsAutoConfiguration` 仍保留"注入 BusinessMetrics"的事件监听并打日志，**误导"每条死信都会被检测"**；实际检测由 `DlqMetrics` 轮询完成。
- **处置**：删除 `DlqMessageHandler` + 移除接线；发布后确认无新"已注入"日志（仅历史轮转文件残留）。

### ④ deadLetterScanJob 复核（无需处置，澄清口径）
- 它是**本地消息表**死信（`t_local_message.status=3`）的补偿扫描：每小时、近 24h、重投上限 3 次；xxl 状态 enabled，近 3 次执行 `handle_code=200 死信扫描完成`。
- 与 MQ `%DLQ%` 是两套机制：前者管事务消息兜底投递，后者管消费重试耗尽；报告口径需区分。

### ⑤ XXL-Job 失败无告警（补齐）
- **处置**：新增 `scripts/xxl-job-health-check.sh`（统计近 15 分钟 `trigger_code!=200` / `handle!=200`，写 textfile 指标到 node-exporter 的 `/data/rocketmq-textfile`），`/etc/cron.d/myxhs-xxl-job-health` 每 5 分钟执行。
- **指标**：`xxl_job_trigger_failures_15m` / `xxl_job_handle_failures_15m` / `xxl_job_health_ok`（实测 node-exporter 已暴露）。
- **规则**：`XxlJobScheduleOrHandleFailures`（P2，>0 持续 5m）、`XxlJobHealthCheckDown`（P1，巡检失败 10m）；已 reload 生效。

## 今日改动边界复查（无新增风险）
- `OrderNotificationPublisher`：仅在状态机真实流转后发送（Feign/MQ 双通道后到线程不会重复发）；
- `onRefundSuccess` 并发 catch：已退款返回幂等成功（消除 500/误补偿）；
- 定时任务 trace：逐单 `startNewTrace` + finally 清理；
- `DlqMetrics`：采集失败 WARN 一次、topic 缺失按 0、counter 0 基线预注册。

## 验证矩阵
| 项 | 结果 |
|---|---|
| 全量构建（含 my-xhs-test） | BUILD SUCCESS |
| 发布 | 15/15 健康（22:12） |
| 直连 JWT 覆盖（cart/order/user） | 200 / 200 / 200 |
| 死代码 | 类已删除；无新注入日志 |
| XXL 巡检指标 | `xxl_job_*` 已暴露（0/0/1） |
| 告警规则 | 2 条已加载 |
