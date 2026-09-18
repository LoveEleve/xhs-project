# 第43题 | 组件深度拷打：MySQL / InnoDB

> 难度：★★★★★｜频率：★★★★★｜区分度：高
> 关键词：InnoDB 索引/B+树、MySQL/InnoDB 三层锁、MVCC、redo/undo/binlog、两阶段提交、复制与 RTO/RPO

## 问题
InnoDB 的索引和锁原理？一条 update 的完整链路？主从复制怎么保证一致？故障切换 RTO/RPO 多少？

## 面试可讲版（五段式）

**① 原理层（索引/MVCC/锁）**
- **B+ 树索引**：聚簇索引（主键叶子存整行）+ 二级索引（叶子存主键，回表）；**最左前缀**、覆盖索引避免回表；页 16KB，三层可存约 2000 万行；
- **MVCC**：行隐藏列 `trx_id/roll_pointer` + undo 版本链 + ReadView——RR 级别下普通读是**快照读**（不加锁），当前读（`SELECT ... FOR UPDATE`/UPDATE）读最新并加锁；
- **锁**：全局/表/行（记录锁+间隙锁+临键锁），意向锁做表行兼容；RR 下范围条件会加 gap lock 防幻读；**死锁**：InnoDB 自动检测并回滚代价小的事务；
- **一条 UPDATE 的链路**：Buffer Pool 改页（脏页）→ 写 redo log（prepare）→ 写 binlog → redo commit（**两阶段提交**）→ 后台刷脏；崩溃恢复按 redo/binlog 一致性判断。

**② 本项目配置与用法（运行态实测）**
- MySQL **8.0.46**，隔离级别 **RR**，buffer pool **1GB**，max_connections 500；
- `innodb_flush_log_at_trx_commit=2` + `sync_binlog=0`——**性能换持久性**：OS 崩溃可能丢约 1 秒事务（业务可容忍；强一致靠对账——**主动说明的取舍**）；
- **GTID 复制**（`gtid_mode=ON / Auto_Position=1`），异步、无 semi-sync——RPO 不保证（见 ④）；
- 分片：订单 4×4（ShardingSphere，29 题）；支付/映射表独立数据源（30 题）；
- 读写分离 + `ReadWriteRoutingDataSource`（21 题）；连接池 master 20/slave 10 + `initializationFailTimeout(-1)`；
- SqlGuard 慢 SQL 熔断（23 题）。

**③ 慢 SQL 与连接（真实事故复盘）**
- **comment 性能事故（三因素叠加）**：① 相关子查询模拟 ROW_NUMBER → 改**窗口函数** + **复合索引**（`V3__comment_perf_index.sql`）；② **僵尸连接风暴**——压测中发布重启 content，旧实例 kill 后 TCP CLOSE-WAIT，MySQL 侧 **318 条挂起查询（t_comment SHARED_READ MDL，挂 64 分钟）**，连接 **459/500** 逼近上限、P99 500ms；`KILL` 清理后 **459→106**、服务立刻恢复；③ 不盲目加池——先审计连接持有者；
- **教训**：压测期间不要发布重启；连接数/长查询要有告警（事后补了 4 条规则，38 题）。

**④ 复制与故障转移（演练实测）**
- **演练数据**：停主 **10.2s**（优雅停）→ 提升从库 **0.087s** → 应用改配置切换 **22s** → **content 视角 RTO ≈32s（不含故障发现）**；本次 **RPO=0**（幸运：停前 2s 已同步）；
- **暴露的 5 个短板**（诚实清单）：① **无自动检测/切换**（谁发现主挂了没人管，真实 RTO=检测+32s，需 MHA/Orchestrator/ProxySQL/MGR）；② 逐服务改配置（应走配置中心/DNS 收敛）；③ **RPO 不保证**（异步复制，严格要 semi-sync 或受控 switchover）；④ errant GTID 风险（非受控提升可能多出匿名事务）；⑤ 短命告警被 group_wait 吞（已用 keep_firing_for 修）；
- **为什么不用 MGR**：单机环境无多节点仲裁价值；生产三节点 MGR/Orchestrator 是正解（列为路线）。

**⑤ 拷打追问**
1. **"RR 和 RC 区别？"** RR 快照读一致性更强但 gap lock 多（并发写差）；RC 无 gap lock 但有幻读（业务能容忍时 RC 更常用）；本项目 RR（默认），范围更新场景注意 gap lock 死锁。
2. **"为什么 update 要两阶段提交？"** redo 和 binlog 是两个日志系统：prepare→binlog→commit 保证崩溃恢复时两者一致（避免主从/备份数据分叉）。
3. **"flush_log=2 丢了怎么办？"** 丢的是 OS 崩溃窗口（约 1s）；业务兜底=对账（18 题）+ 幂等；强一致场景可调 1（性能代价）——明确取舍而非疏忽。
4. **"主从延迟怎么处理？"** 监控 Seconds_Behind_Source；写后读强制主库（21 题）；大事务拆分（批量 DML 分批，防 177s 延迟重演）。
5. **"死锁怎么排查？"** `SHOW ENGINE INNODB STATUS` 的 LATEST DETECTED DEADLOCK；业务侧固定加锁顺序、缩小事务、重试。
6. **"分库分表后跨分片事务？"** 避免强事务：本地事务+最终一致（事务消息/对账）；订单分片键 user_id 让单用户事务不跨库（29 题）。

**⑥ 话术**
> "InnoDB 是聚簇索引加二级索引回表、MVCC 快照读、行级锁加临键锁；update 走 redo/binlog 两阶段提交。我们 8.0.46、RR、buffer pool 1G，flush_log=2 加 sync_binlog=0 是性能换持久性，极端会丢约 1 秒，靠对账兜底——这个取舍我会主动讲。最常见的事故是 comment：慢 SQL 加僵尸连接风暴，318 条挂起查询把连接顶到 459/500，清理后 459 到 106 立刻恢复。故障转移演练过：停主 10.2 秒、提升 0.087 秒、应用切换 22 秒，RTO 约 32 秒不含发现时间；暴露的最大短板是没有自动检测切换，这是下一步。"

## 发散追问地图（横向）
- 索引：B+ 树/哈希/全文、联合索引顺序、索引下推、统计信息。
- 事务：隔离级别、MVCC、next-key lock、两阶段提交、组提交。
- 日志：redo/undo/binlog、WAL、日志格式（ROW/STATEMENT）。
- 复制：异步/半同步/组复制、GTID、多源复制、延迟复制。
- 高可用与运维：Orchestrator/MHA/ProxySQL、在线 DDL（gh-ost/pt-osc）、备份恢复（PITR）。

## 面试官评分点
**高级开发级**：能讲索引/事务/复制基础；知道自己配置的取舍。
**架构师加分**：flush_log/sync_binlog 的取舍表达；僵尸连接事故的完整根因链；RTO 分解与"无自动检测"的诚实短板；errant GTID/半同步这类深水区。
**危险信号**：RR/RC 说不清；主从不一致没预案；RTO 只报提升时间；把 flush_log=2 说成完全不丢。

## 本项目真实证据
- 运行态：8.0.46/RR/buffer 1G/flush_log=2/sync_binlog=0/max_conn 500/GTID ON+Auto_Position=1；
- `docs/reports/mysql-failover-drill-20260917.md`（10.2s/0.087s/22s/RTO≈32s/5 短板清单）；
- `docs/reports/comment-sql-tuning-20260917.md`（ROW_NUMBER+复合索引/318 僵尸/459→106）；Hikari 配置（21 题）。

## 版本与来源
MySQL 官方文档（InnoDB/复制）；本项目演练与调优报告。

## 真实性说明
配置/RTO/事故数字均为运行态与仓库报告事实；"RPO=0"标注为本次演练特例；短板主动披露。
