# MySQL 故障转移演练报告（2026-09-17）

> 目标：在当前主从（异步 GTID 复制）架构下，实测**主库故障的 RTO/RPO** 与应用切换代价，并暴露短板。
> 配置：主 192.168.0.142:3306（server_id=1）/ 从 3307（server_id=2，read_only）；GTID Auto_Position；复制账号 root。

## 一、演练步骤与实测时间
| 步骤 | 动作 | 耗时/结果 |
|---|---|---|
| 1 | 基线写入评论标记 → 从库可见 | ✓（写入后 2s 内同步） |
| 2 | `docker stop my-xhs-mysql`（优雅停主） | **10.2s** |
| 3 | RPO 检查：停主前写入的标记在从库存在 | ✓ **RPO=0（本次）** |
| 4 | 提升从库：`STOP SLAVE; RESET SLAVE ALL; read_only=0` | **0.087s** |
| 5 | 新主直写验证（INSERT/DELETE） | ✓ |
| 6 | 应用切换（content 代表）：master URL→3307 重启 | **22s**（至首次写成功） |
| 7 | 读路径（原本就走 3307） | 全程可用 |
| — | **content 视角总 RTO** | **≈32s**（不含故障发现时间） |

## 二、恢复过程（已验证）
1. 清理演练标记（含切换后写在新主的 errant 数据）；
2. 重启旧主库（3306，read_only=0）；
3. 3307 重新挂回 3306：`CHANGE REPLICATION SOURCE TO ... AUTO_POSITION=1; START REPLICA;` → **IO/SQL Running=Yes，延迟 0**；
4. content 切回主库（3306）→ 写/读验证通过；服务健康抽查 4/4=200。

## 三、暴露的问题（本报告核心价值）
1. **无自动故障检测/切换**：提升本身秒级，但"谁发现主挂了"没人管——真实 RTO = 检测 + 32s。需要 MHA/Orchestrator/ProxySQL/MGR 之一；
2. **应用切换是逐服务改配置+重启（~20s/服务）**：无统一写入口（VIP/DNS/ProxySQL），生产要避免"改 15 个服务配置"；
3. **RPO=0 不保证**：异步复制下本 drill 是"幸运"（stop 前 2s 已同步）；严格 RPO 需半同步（semi-sync）或受控 switchover；
4. **Errant GTID**：演练在新主直写的数据在挂回从库后成为 errant transaction（生产中应做"计划切换":先停写、等同步、再提升）；
5. **告警被吞（已修复）**：`MysqlDown` 在演练中 firing ~75s 后恢复，因 `group_wait=30s` + 短命恢复，**通知被 Alertmanager 丢弃**；已修：`MysqlDown.keep_firing_for: 2m` + `group_wait: 10s` + 新增 **Watchdog 心跳**（实测 23:11:09 送达）。

## 四、结论与建议选型
- 当前架构可"手工救火"（总 ~32s + 检测时间），但**不具备生产级 HA**；
- 推荐路线（按成本）：① **semi-sync + Orchestrator/MHA**（自动检测+切换+选主）；② **ProxySQL**（统一写入口，应用零改动，结合脚本/健康检查）；③ 升级 **MGR**（多主/自动选主，改造成本最高）；
- 短期可做：把 master URL 收敛到单一入口（域名/VIP 或 ProxySQL），消灭"逐服务改配置"。
