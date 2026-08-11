# MySQL 排障（死锁 / 慢查询 / 负载 / 主从 / 连接池 / 分片）

> 用户举例的核心排障域。my-xhs MySQL 是**分库分表 + 主从 + GTID**，排障面很广。

## 业务问题（AI 能回答）
- **死锁**：发生时间/涉及的 SQL/事务、锁等待链。
- **慢查询**：哪些 SQL 慢、耗时分布、是否走了索引、对接口的影响。
- **负载**：QPS/CPU/连接数、连接池耗尽、写放大。
- **主从延迟**：Seconds_Behind_Master、是否读到旧数据。
- **分片路由**：某订单落在哪个库/表、跨分片聚合。
- **连接池**：HikariCP 活跃/最大、超时。

## 可用的数据资产与就绪度
| 排障目标 | 数据来源 | 就绪度 |
|---------|---------|:---:|
| 死锁 | MySQL error log / `SHOW ENGINE INNODB STATUS` | ⚠️ 需开启/采集 |
| 慢查询 | `slow_query_log`(建议0.5s) → Filebeat → Kibana | ⚠️ **待补充**(见监控文档§七) |
| 连接池/延迟 | Prometheus `hikaricp_connections_active/max`、`http_server_requests` | ✅ |
| 主从延迟 | `SHOW SLAVE STATUS`(Io_Running/Sql_Running/Seconds_Behind) | ⚠️ 需轮询采集 |
| 分片路由 | t_order_no_mapping + 分片规则 | ✅ |
| SQL 耗时/慢端点 | SkyWalking JDBC 埋点(>1s标记) | ✅ |

> **已知缺口**：无 `mysql-exporter` 容器、无 slow_query_log 采集管道 —— MySQL 中间件本身指标（死锁/慢查询/锁等待）当前**未纳入观测**，是 O&M 工具的首要补全项。

## 关键诊断点
1. **死锁**：InnoDB 死锁需 error log / 事务锁等待分析；多索引更新顺序不一致、批量与单条混用易触发。
2. **慢查询**：结合 t_order 分片 EXPLAIN 看是否走分片键；联表/`IN` 过大/未走索引是典型。
3. **主从延迟**：写后读需 `@Transactional` 走主库（项目已规范）；非事务读到旧数据是已知设计（接受最终一致）。
4. **连接池耗尽**：`hikaricp_connections_active/max > 0.9` 是告警阈值；常与慢查询/死锁/长事务耦合。
5. **写放大/负载**：Redis 权威 + 异步落库的模块（cart/counter）对 MySQL 写压力较低；分片库避免跨分片扫描。

## 关联
- 死锁/慢查询常是**业务延迟的根因** → 关联 pillar-2 订单/库存诊断与 trace 诊断。
- 恢复 SOP 见 `../../docs/test-2/engineering-docs/failover-scenarios.md` §二。
