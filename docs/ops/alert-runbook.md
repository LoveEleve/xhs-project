# 告警 Runbook（2026-09-17）

> 通知链路：Prometheus（19090）→ Alertmanager（19093）→ webhook sink（127.0.0.1:19099）→ `/data2/logs/alerts.jsonl`（`alert-sink.service`）。
> 如需接企业微信/飞书：在 Alertmanager 增加 receiver 并调整 route（当前默认落盘）。

## 一、MySQL 连接与复制（2026-09-17 僵尸连接事故后新增 4 条）

### MysqlConnectionUsageHigh（连接使用率 >80%）
- **含义**：`threads_connected/max_connections > 0.8` 持续 2 分钟（当日事故峰值 459/500）。
- **排查**：`SELECT user,host,command,COUNT(*) FROM information_schema.processlist GROUP BY 1,2,3;`
  重点看：`command='Sleep'` 且 `host` 为应用网段的数量（池正常为 5/服务）；`command='Execute'` 的僵留查询。
- **常见根因**：① 僵尸连接（旧实例被杀后 TCP CLOSE-WAIT，MySQL 侧未清理）；② 连接池泄漏/未归还；③ 业务长事务。
- **处置**：定位僵留连接 → `KILL <id>`；确认应用实例数是否正确（无重复实例）；必要时滚动重启持有者。
- **预防**：不要在压测中频繁发布重启；连接数/长查询告警常开。

### MysqlLongRunningQueries（存在 >60s 活跃查询）
- **含义**：`mysql_info_schema_processlist_seconds{command=~"query|execute"} > 60`（当日 318 条挂 64 分钟）。
- **处置**：`SELECT id,time,state,LEFT(info,100) FROM information_schema.processlist WHERE command<>'Sleep' ORDER BY time DESC;`
  → 确认非业务刚需后 `KILL`；若是 DDL 等锁，先处理阻塞者。
- **预防**：SQL 走索引/避免全表扫描；批量任务分批提交。

### MysqlReplicaLagHigh（从库延迟 >30s）
- **含义**：从库 `Seconds_Behind_Master > 30`（当日大 DELETE 导致 177s）。
- **排查**：从库 `SHOW SLAVE STATUS`；大事务来源（批量 DML、DDL）；从库负载/磁盘。
- **处置**：等待追平；必要时分批重放；评估 `slave_parallel_workers`。
- **预防**：批量 DML 分批（如 LIMIT 500/批）；DDL 避开高峰。

### MysqlAppConnectionsHigh（应用侧连接 >250）
- **排查**：按服务核对 Hikari 池 `active/idle` 与实例数；检查是否有旧实例未退出（重复进程）。
- **处置**：清理重复实例/僵尸连接；确认无连接泄漏后，再评估池参数（**不要先加池**，先看 DB 连接总量余量）。

## 二、存量告警速查（摘）
| 告警 | 第一动作 |
|---|---|
| MysqlDown | 查 mysqld_exporter 与 MySQL 进程/端口 |
| RedisHighMemoryUsage/Connections | `redis-cli info memory/clients`；大 key/连接泄漏 |
| EsClusterNotGreen | `GET _cluster/health?level=indices`；单节点常见 replicas 未分配（yellow）→ 评估副本数 |
| RocketmqDlqBacklog / DlqMessageDetected | AI 侧 `dlq_list` 诊断 → HITL 审批重投（见 xhs-ai 文档） |
| CanalHighDelay | canal 实例状态、binlog 位点、JDBC 连接 |
| SearchHighLatency | ES CPU 配额/慢查询（当日案例：2 核打满 → 6 核，508→1,448 RPS） |

## 三、验证与维护
- 端到端验证：注入 `SELECT SLEEP(600)` → firing（22:34:25）→ resolved（22:39:25）均落盘（2026-09-17 实测）。
- `EsClusterNotGreen`：单节点集群 yellow 属预期，建议将索引副本数设为 0 或调整告警仅 red 触发（待决策）。
- 规则位置：`deploy/docker/my-xhs-deploy-zip/config/prometheus/alert_rules/myxhs_rules.yml`（live）与 `config/prometheus/alert_rules/myxhs_rules.yml`（仓库副本）。
- 热加载：`curl -X POST http://localhost:19090/-/reload`（Prometheus）、`http://localhost:19093/-/reload`（Alertmanager）。
