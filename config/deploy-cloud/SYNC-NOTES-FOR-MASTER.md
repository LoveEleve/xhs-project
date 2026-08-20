# 同步说明：本轮 master 修复 9 条差异（对方上传 zip 前务必先同步）

> 2026-08-13 | 对象：部署包 master 基线（config/my-xhs-deploy-package.zip）vs 对方最新上传 zip
> 原因：本轮发现并修复了若干 P1 级问题（监控数据丢失/行为链路失效/启动故障），对方 zip 未包含。
> **下次对方上传 zip 前，请先从本仓库拉取以下 9 条（否则会回退到旧行为）。**

## 9 条差异（逐条含原因）

| # | 文件 | 差异 | 原因 |
|:--:|---|---|---|
| 1 | `docker-compose.yml` | 主/从 mysql command 补 `--slow-query-log-file=/var/lib/mysql/slow.log` | 慢查询日志路径确定化（原默认在 datadir 内不可预期）；long-query-time 主 0.5s/从 1s 双方已一致 |
| 2 | `sql/init-all.sql` | `t_user_behavior` 从 my_xhs_analytics 段移至 my_xhs_content 段，注释改 7 值枚举（1曝光…7停留） | **P1**：search 服务数据源=content 库，表建在 analytics 库 → 写入 1146 失败、行为链路全丢、推荐计算降级跳过；注释 6 值错位（2-点赞应为 2-点击） |
| 3 | `grafana/.../api-monitor.json` | 延迟/状态码面板指标正则化 `{__name__=~"myxhs_http_request_duration_seconds_bucket\|http_server_requests_seconds_bucket"}`；慢请求面板 le="0.5" 改 histogram_quantile | gateway 不依赖 common（无 myxhs_http 指标）→ 面板对网关恒空；动态桶无 le="0.5" 边界导致慢请求面板恒空 |
| 4 | `grafana/.../biz-metrics.json` | 重建 11 面板（真实业务埋点：orders/feed/mq/coupon/inventory/dlq + 业务延迟 P99） | 原 5 面板全是"替代未埋点 xxx"补救标题 + 1 处重复 + 1 处与 api 重复 |
| 5 | `grafana/.../hikaricp-monitor.json` | **新建**连接池看板（10 面板） | Tomcat/HikariCP 看板拆分：连接池独立成板，pool 维度经 legend {{application}}/{{pool}} 展示 |
| 6 | `grafana/.../jvm-monitor.json` | 内存池明细按 `sum by (id)`；+Non-Heap 池明细；+死锁线程面板 | 原"分代明细"只按 area 合计（假分代）；死锁指标为自定义 Binder 补齐（micrometer 1.12.5 无内置） |
| 7 | `grafana/.../tomcat-monitor.json` | 拆分为纯 Tomcat 看板（线程 busy/current/config_max + HTTP QPS/5xx/P99） | 指标名修正（tomcat_threads_busy_threads/config_max_threads，非 busy/max）；pool 幽灵变量删除；HTTP 面板正则化（同 #3） |
| 8 | `deploy-cloud/rocketmq-metrics.sh` | +`rocketmq_dlq_topics`/`rocketmq_retry_topics` 指标（topicList 一次输出复用） | DLQ 死信监控原本只有总数（含普通 topic），无 DLQ 专项指标 |
| 9 | `deploy-cloud/README-METRICS.md` | mysql-deadlock-metrics.sh v2 标注 + 部署说明（install -m755） | 脚本版本与部署方式文档化 |

## 附带说明（对方 zip 中已有/已过时，无需同步）
- `DEPLOY-NOTES.md` 第 30 条"Tomcat 监控注意（micrometer 1.13+ 无 tomcat.threads）"**已过时**——mbeanregistry 已全量开启（14 服务），tomcat_threads_busy_threads/current_threads/config_max_threads 已可用，请删除或改注
- 微服务侧（不在 zip 内，云主机构建时生效）：14 服务 application.yml 的 `mbeanregistry.enabled` + `percentiles-histogram`（hikaricp/业务延迟 Timer）+ gateway `GatewayMetricsConfig`（application 标签）

## 操作建议
1. 对方：`git pull` 或按上表 9 个文件逐一替换 → 重新打包上传
2. 验证：`grep slow-query-log-file docker-compose.yml`（2 处）、`grep '1-曝光' sql/init-all.sql`、`ls grafana/provisioning/dashboards/json/ | grep hikaricp`
