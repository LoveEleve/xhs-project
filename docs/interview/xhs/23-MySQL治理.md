# 第23题 | MySQL 慢 SQL / 死锁 / 连接池与 SqlGuard

> 难度：★★★☆☆｜频率：★★★★☆｜区分度：中
> 关键词：慢 SQL 观测→校准→分级强制、SqlGuard 熔断、白名单阻断、死锁检测、HikariCP

## 问题
MySQL 慢 SQL 怎么治理？死锁怎么排查？SqlGuard 是什么？

## 面试可讲版（五段式）

**① 业界背景**
慢 SQL 治理的三个层次：**观测**（慢日志/performance_schema）→ **定位**（执行计划/索引/锁等待）→ **强制**（拦截/熔断）。生产上"一上来就阻断"会误伤，正确路径是**先观测、再校准、分级强制**；连接池是另一个常见问题源（连接泄漏、池耗尽）。

**② 项目选择**
- **SqlGuard 生产化演进**（重点讲）：
  - v1 只观测：>200ms 记慢日志 + 指标；
  - **v2 分级**：慢 SQL 连续 5 次触发判定 → `SqlGuardBlockedException`（bypass 需显式）；**阻断默认关闭**（`block-on-open=false`），开启后按**白名单模式**（`block-patterns`）与**豁免名单**（`exempt-patterns`）放行合法慢查询；
  - 冷却恢复：熔断后有冷却期，避免永久熔断；
  - 3 个 Prometheus 指标：`slow_total` / `open_total` / `blocked_total`；
  - 6 个单测 + 真机验证：白名单开启后第 2 次请求即 500 阻断（带业务错误码）；
- **连接池**：HikariCP master 20/slave 10；连接泄漏排查（僵尸连接 459→106 事故：压测期间发布 + 池未回收，清理后 comment 才恢复）；
- **索引事故**：comment 列表 ROW_NUMBER + 复合索引（详情页 SQL 调优），P99 从 191ms→70ms 级。

**③ 坑**
- **"慢"≠"坏"**：批量任务/对账 SQL 天然慢，直接阻断会打挂对账——所以豁免名单 + 默认关闭是必须的；
- **判定窗口**：单次慢可能是偶发（锁等待），连续 N 次才是问题；阈值与次数要按业务校准；
- **阻断的爆炸半径**：拦截器在 common，开启后全服务生效——必须灰度（先单服务白名单）再全量；
- **僵尸连接**：压测+发布叠加，连接归还失败（连接在途 + 池 shut down），连接数只涨不落——排查看 Hikari 指标不只看 DB PROCESSLIST。

**④ 兜底**
- 阻断默认关闭：先观测数据校准阈值（避免误伤）；
- 豁免名单保业务；冷却期自动恢复；
- 异常统一走 `GlobalExceptionHandler`（SQL 阻断返回明确错误，不裸 500 堆栈）；
- 连接池：`initializationFailTimeout(-1)`（从库不阻塞启动）、最大池 + 泄漏检测（Hikari leakDetectionThreshold 可开）。

**⑤ 话术**
> "慢 SQL 我是按'观测→校准→分级强制'做的：SqlGuard 一开始只记录 200ms 慢查询和指标；后来加阻断，但默认关闭、白名单+豁免名单、冷却恢复，而且开启前用观测数据校准过阈值——直接阻断会误伤对账这类天然慢的 SQL。真机验证过白名单开启后第二次请求就 500。连接池那块踩过僵尸连接的坑：压测期间发布导致连接不回收，看 Hikari 指标才发现。"

## 追问与参考回答
**追问1：为什么默认关闭？** 没校准的阻断是事故；观测数据先跑一段时间，确认阈值/白名单后再开，且按服务灰度。
**追问2：死锁怎么排查？** `SHOW ENGINE INNODB STATUS` 看 LATEST DETECTED DEADLOCK；业务上固定加锁顺序（按 ID 排序）、缩短事务、必要时重试。
**追问3：连接池怎么定大小？** 按 QPS×平均耗时估并发，乘安全系数；不是越大越好（DB 连接是资源，过多反而慢）。
**追问4：慢 SQL 和索引什么关系？** 先用 EXPLAIN 看 type/key/rows；覆盖索引/最左前缀/避免函数列；本项目 comment 用 ROW_NUMBER 改写+复合索引。
**追问5：怎么防止慢 SQL 拖垮整个服务？** 应用层熔断（SqlGuard）+ DB 层 max_execution_time + 连接池隔离（读写分池）。

## 发散追问地图（横向）
- 慢 SQL：执行计划、索引设计（覆盖/最左/区分度）、分区/归档。
- 锁：行锁/间隙锁/意向锁、死锁检测与重试、乐观锁 vs 悲观锁。
- 连接池：HikariCP 参数、泄漏检测、池耗尽应急。
- 保护机制：SQL 防火墙（ProxySQL）、max_execution_time、熔断降级。
- 观测：慢日志、performance_schema、Prometheus + Grafana 面板。

## 面试官评分点
**高级开发级**：能讲索引/执行计划/连接池基础；知道慢 SQL 排查路径。
**架构师加分**：分级治理（观测→校准→强制）；默认关闭与灰度开启的工程判断；豁免名单的业务视角；连接池与僵尸连接的排查证据。
**危险信号**：上来就全量阻断；慢 SQL 只答"加索引"；连接池越大越好论。

## 本项目真实证据
- `SqlGuardInterceptor:47` 三指标注释；v2 白名单/豁免/冷却/默认关闭（`docs/reports/sqlguard-sentinel-hardening-20260917.md`：真机验证白名单第 2 次请求 500 阻断、6 单测）；
- 连接池：master 20/slave 10（ReadWriteRoutingDataSourceConfig）、`setInitializationFailTimeout(-1)`；
- 僵尸连接：`docs/reports/comment-sql-tuning-20260917.md`（459→106）；MySQL 慢查询治理口径在 capacity/a2 报告。

## 版本与来源
本项目 SqlGuard 代码与加固报告；MySQL 官方（EXPLAIN/InnoDB 死锁）；HikariCP 文档。

## 真实性说明
SqlGuard 行为（默认关闭/白名单/冷却/指标）为代码与真机验证事实；僵尸连接数字来自事故报告；未夸大为"全量阻断"。
