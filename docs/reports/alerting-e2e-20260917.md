# 告警通知链路打通实录（2026-09-17）

## 一、接通前状态
- Prometheus 已有 **31 条规则**（含 MySQL/Redis/ES/Canal/DLQ/节点等），但 **Alertmanager live 配置的 receiver 是 `default-noop`（空）** → 所有告警被静默丢弃；
- 仓库副本中的 webhook 指向占位地址（网关无该端点）→ 通知黑洞。

## 二、本次动作
1. 新增本地告警落地器 `alert-sink.service`（Python 单文件，:19099）→ 落盘 `/data2/logs/alerts.jsonl` + 可读日志；
2. Live Alertmanager receiver 改为 `default-webhook → http://127.0.0.1:19099/alert`（`send_resolved: true`），仓库副本同步；
3. 新增 4 条规则（`myxhs_mysql_conn_alerts`）：
   - `MysqlConnectionUsageHigh`（>80%，2m）← 当日 459/500
   - `MysqlReplicaLagHigh`（>30s，2m）← 当日 177s
   - `MysqlLongRunningQueries`（>60s，1m）← 当日 318 条挂 64 分钟
   - `MysqlAppConnectionsHigh`（>250，5m）
4. 规则总数 31 → 39，两处配置均热加载生效。

## 三、端到端验证（实测）
| 步骤 | 结果 |
|---|---|
| 注入 `SELECT SLEEP(600)` ×2 | `mysql_info_schema_processlist_seconds{command="query",state="user_sleep"}` >60 |
| Prometheus | `MysqlLongRunningQueries` **firing** |
| Alertmanager（19093） | active |
| Sink 落盘 | `22:34:25 [firing] MysqlLongRunningQueries severity=critical` ✅ |
| 存量告警同链路 | `EsClusterNotGreen`（既有 firing）亦已送达 ✅ |
| 清理后端 | `22:39:25 [resolved] MysqlLongRunningQueries` **已收到恢复通知**（受 `group_interval=5m` 影响，firing→resolved 间隔约 5 分钟） |

## 四、遗留与决策项
- **EsClusterNotGreen 长期 firing（决策项）**：单节点 ES 无法分配副本 → 常态 yellow；三种处理：
  ① 索引副本数设为 0（单节点正确姿势，告警消失）；② 告警只在 red 时触发（保留 yellow 观测）；③ 增加节点（本地不现实）。
  建议 ①（并在 Runbook 注明单节点前提）。
- resolve 通知延迟 5 分钟来自 `group_interval=5m`（可按需调小）；
- IM（企微/飞书）接入需凭据，当前落盘方案可平滑替换。
