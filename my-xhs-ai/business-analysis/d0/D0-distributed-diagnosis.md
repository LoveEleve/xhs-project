# D0：分布式 / 工程排障能力与缺口全量清单

> 版本：v0.1 | 日期：2026-08-10
> 定位：把 B 面从"3 个场景"扩展为**完整的分布式/工程排障能力地图**——逐项确认"能查什么 / 数据从哪来 / 就绪度 / 缺什么 / 要补什么"。
> 依据：基于 my-xhs 真实代码/配置核对（非照抄）。新增 **B4 主从复制延迟**；并把 Java 死锁 vs MySQL 死锁、选举、分布式锁、Canal、RocketMQ 等全部纳入。
> 阅读：`../02-plan/PLAN-v6.md` §2（工具 vs Agent 判断标准）+ `d0/D0-scenarios.md` + `../03-pillars/pillar-3-ops/*`。

> ⚠️ **编号消歧**：**场景 B 编号**（B1 死锁 / B2 积压 / B3 5xx / **B4 复制延迟**）与 **观测缺口 B 编号**（B1 mysql-exporter … **B4 DLQ** … B6-B10）是**两套独立编号**，勿混。文中"B4"视上下文：谈场景=复制延迟，谈缺口=DLQ 消费者。

---

## 1. 判断标准（先定走工具还是 Agent）
- **能确定查的**（固定指标/状态）→ 确定性工具。
- **需多步交叉归因的** → Agent。
- **数据没采集的** → 先补观测（缺口），否则"能查"是空话。

---

## 2. 问题矩阵（按域）

### 2.1 MySQL 域
| 问题 | 能否排查 | 数据来源 | 就绪度 | 缺口/要补 |
|------|:--:|---------|:--:|------|
| **主从复制延迟（B4·新增）** | ✅ | `SHOW SLAVE STATUS`：Seconds_Behind_Master / Io_Running / Sql_Running | ⚠️ | **补 mysql-exporter 复制延迟指标**（B6）|
| MySQL 死锁 | ✅ | InnoDB error log / `SHOW ENGINE INNODB STATUS` / performance_schema | ⚠️ | 开 innodb_status_output + **采 error log 管道**（B7）|
| 慢查询 | ✅ | slow_query_log(≥0.5s)→Filebeat→ES | ⚠️ | 已列 B2 |
| 连接池耗尽 | ✅ | Prometheus `hikaricp_connections_active/max` | ✅ | — |
| 分片路由 | ✅ | t_order_no_mapping + 分片规则 | ✅ | — |
| 锁等待 | ✅ | performance_schema.data_lock_waits | ⚠️ | 需采集（B7 附带）|

> 复制事实：user/content/order/inventory 都有主从（`init-replication-*.sql`，GTID，master 13306→slave）；复制延迟导致**读旧数据**（已接受最终一致，写后读走主库）。

### 2.2 JVM / Java 域
| 问题 | 能否排查 | 数据来源 | 就绪度 | 缺口/要补 |
|------|:--:|---------|:--:|------|
| **Java 死锁** | ✅（但需现场转储）| JVM **Thread Dump**（jstack / Arthas）/ 死锁检测 | ⚠️ | **补线程转储自动采集/按需抓取**（B10）|
| OOM | ✅ | 堆转储 + jvm_memory_used | ⚠️ | 需配置转储 |
| GC 暂停 | ✅ | Prometheus `jvm_gc_pause_seconds` | ✅ | — |
| 线程池耗尽 | ✅ | `tomcat_threads_current` | ✅ | — |

> Java 死锁实据点：`SegmentIdGenerator`（synchronized，ID 发号，高并发竞争点）、`DynamicDataSource`（切库）——是 Java 死锁/竞争的热点。

### 2.3 RocketMQ 域
| 问题 | 能否排查 | 数据来源 | 就绪度 | 缺口/要补 |
|------|:--:|---------|:--:|------|
| 消费积压 | ✅ | `rocketmq_consumer_offset` | ✅ | — |
| DLQ 死信 | ⚠️ | `%DLQ%` topic / 消费日志 | ⚠️ | 已列 B4（建消费者）|
| 事务消息/outbox 积压 | ✅ | t_local_message / t_*_outbox | ✅ | — |
| **主从/选举（broker 切换）** | ⚠️ | broker 状态指标 / 切换事件 | ⚠️ | **补 RocketMQ exporter 指标**（B8）|

> 事实：`broker.conf`(broker-a, brokerId=0, **ASYNC_MASTER**) + `broker-slave.conf`(brokerId=1, **SLAVE**)；broker-a 故障即触发主从切换（选举），诊断需 broker 指标 + 切换日志。

### 2.4 分布式协作域
| 问题 | 能否排查 | 数据来源 | 就绪度 | 缺口/要补 |
|------|:--:|---------|:--:|------|
| **分布式锁 / 幂等** | ⚠️ | Redisson 锁竞争/超时日志 + Redis 指标 | ⚠️ | 需 Redis exporter（B3）+ 锁日志观测 |
| **Job 领导选举（唯一执行）** | ⚠️ | `@DistributedLock` 获取日志 + 任务执行记录 | ⚠️ | 需锁获取日志，判断重复/漏执行 |
| **Canal binlog→MQ** | ⚠️ | Canal 延迟/断连指标 + MQ 产出 | ⚠️ | **补 Canal 监控指标**（B9）|

> 事实：`@DistributedLock(key=...)`（`my-xhs-common`）用于定时任务唯一执行（LocalMessageRetryJob 等）；Canal 有 inventory/note/product 三个实例（`config/canal/conf/*`），读 MySQL binlog 推 MQ——若 Canal 断连，下游同步/索引/缓存失效。

### 2.5 缓存 / 存储 / 服务
| 问题 | 能否排查 | 数据来源 | 就绪度 | 缺口/要补 |
|------|:--:|---------|:--:|------|
| Redis 内存/命中/淘汰 | ⚠️ | redis-exporter | ⚠️ | 已列 B3 |
| ES 健康/延迟 | ✅ | es-ops（已有分析）| ✅/⚠️ | 视采集 |
| 5xx / 慢端点 / 跨服务链路 | ✅ | Prometheus + SkyWalking | ✅ | — |

---

## 3. Java 死锁 vs MySQL 死锁（专项区分）
| 维度 | Java 死锁 | MySQL 死锁 |
|------|----------|-----------|
| 现象 | 线程互相持锁等锁，CPU 可能不高但请求卡死 | 事务互相等锁，InnoDB 检测后**回滚一个事务**报 ERROR 1213 |
| 表象 | 接口超时/线程池耗尽/无响应 | 报错 `Deadlock found`，事务回滚，业务失败 |
| 定位 | **Thread Dump** 找 `BLOCKED/DEADLOCK` 线程、锁拥有者 | `SHOW ENGINE INNODB STATUS` 的 LATEST DETECTED DEADLOCK |
| 数据缺口 | 需线程转储（B10）| 需 error log 采集（B7）|
| 关联 | 常因锁顺序不一致/长事务持锁 | 多索引更新顺序、批量+单条混用、gap lock |

> Agent 排障时须**先分辨是哪一种死锁**（Java vs MySQL），走不同证据链——这是排障 Agent 的关键分支。

---

## 4. "选举"（专项）
- **不存在"单一业务选举"，my-xhs 的"选举"分散在各中间件**：
  1. **RocketMQ broker 主从**：broker-a 故障 → slave 升主（ASYNC_MASTER→SLAVE 切换），诊断需 broker 指标（B8）。
  2. **Job 唯一执行**：多个实例抢 `@DistributedLock` 只有一个执行（近似 leader election），诊断需锁获取日志。
  3. **Nacos 注册中心**：心跳 15s 超时 → 摘除 → 重启注册（服务级可用性，非选主）。
- **结论**：不单列为场景，作为各域的"相邻项/提及"纳入（对应 B8/B9 + 锁日志）。
- 若真要深做"选举排障"，需补对应中间件监控指标，当前数据薄。

---

## 5. 新增观测缺口（D0-audit B1-B5 之外，B6-B10）
| # | 缺口 | 影响 | 方案 | 归属 | 优先级 |
|:--:|------|------|------|------|:--:|
| B6 | **主从复制延迟指标**（mysql-exporter 附带 replication lag）| B4 无法查 | mysql-exporter 采 `Seconds_Behind_Master` | mysql | 🔴 |
| B7 | **MySQL error log / innodb status 采集** | Java/MySQL 死锁无法查 | error log 管道 + 开启 innodb_status_output | mysql | 🔴 |
| B8 | **RocketMQ broker 指标（主从/切换）** | 主从选举/切换无法查 | RocketMQ exporter/Dashboard 指标接入 Prometheus | mq | 🟡 |
| B9 | **Canal 监控指标** | binlog→MQ 断连/延迟无法查 | Canal server metrics + 告警 | canal | 🟡 |
| B10 | **JVM 线程转储** | Java 死锁无法定位 | 按需 jstack/Arthas + 死锁检测 | 各服务 | 🟡 |

---

## 6. 新增 B4 场景（加入 `D0-scenarios.md`）
### B4. MySQL 主从复制延迟归因
- **问题**：主从复制延迟高 / 读旧数据，Agent 需归因（大事务/从库慢查询/网络/relay log）。
- **数据依赖**：`SHOW SLAVE STATUS`（Seconds_Behind_Master/Io_Running/Sql_Running）+ slow_query + 大事务。
- **就绪度**：❌ 需补 B6（复制延迟指标）+ B2（慢查询）。
- **验收**：能报出延迟量 + 归因方向（或明确"未采集无法归因"）；数字来自确定性工具；带证据链。

---

## 7. 结论
- **能查的（✅）**：连接池、分片、GC/线程池、5xx/链路、MQ 积压、outbox/事务消息。
- **能查但缺观测（⚠️→要补 B6-B10）**：主从延迟、死锁（Java+MySQL）、RocketMQ 主从、Canal、分布式锁。
- **Agent 关键分支**：死锁要**先分 Java vs MySQL**；复制延迟/选举属确定性查询（工具）+ 多步归因（Agent）。
- **下一步**：把 B6-B10 并入 D0-audit 缺口清单 + B4 入场景规格；对应服务 Owner 补齐后才可验收。
