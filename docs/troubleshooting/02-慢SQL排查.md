# 第2题 | 慢 SQL：慢查询日志定位 → EXPLAIN → 索引与选择性

> 难度：★★★★☆｜频率：★★★★★｜区分度：高
> 关键词：MySQL、slow_query_log、EXPLAIN、type=ALL、覆盖索引、选择性、long_query_time

## 问题
接口越来越慢，怀疑是数据库慢查询。你如何用监控和慢查询日志定位到具体 SQL，再用 EXPLAIN 判断根因，并给出不"乱加索引"的修复？

## 30 秒简洁回答
结论：**先看监控/慢查询日志，锁定慢 SQL，再 EXPLAIN 看执行计划，按"选择性"决定加不加索引**。第一步看 DB 监控（QPS/连接数/慢查询数）与 `slow_query_log`（`long_query_time` 阈值）；第二步拿到慢 SQL 后 `EXPLAIN` 看 `type` 与 `rows`——`type=ALL` 且 `rows` 大 = 全表扫描；第三步按选择性修复：高选择性的列加索引（或覆盖索引），低选择性（命中大半行）加索引收益有限，要改写法或分页/限制；修完用 EXPLAIN 前后对比 + 回监控复测。

## 展开回答（高级开发级）
**监控/慢日志是入口**：
- 项目 `slow_query_log=ON`、`long_query_time=0.5`，慢 SQL 落 `joinsen-slow.log`；DB 侧指标（连接数、QPS、慢查询计数）告警。
- 锁到慢 SQL 后，核心是**读懂 EXPLAIN 的关键列**：`type`（访问类型，从好到坏 `const/eq_ref/ref/range/index/ALL`）、`key`（实际用的索引）、`rows`（预估扫描行数）、`Extra`（`Using index`=覆盖索引、`Using filesort`=排序、`Using where`=回表过滤）。

**判断根因的三步**：
1. `type=ALL` + `rows`≈表行数 → **全表扫描**，通常是缺索引或索引失效（隐式类型转换/函数包裹/前导模糊 `%x`）；
2. `type=ref/range` 但 `rows` 仍大 → 命中行多（**低选择性**），索引救了"定位"救不了"扫描"；
3. `Using filesort` → 排序没走索引，大结果集时尤其痛。

**修复按选择性**（关键，别乱加索引）：
- **高选择性**（等值、命中几行）：加普通索引，甚至覆盖索引（`Extra=Using index`，免回表）；
- **低选择性**（范围命中大半表）：加索引收益小（从全表扫变"扫一半"），要**改写 SQL**（分页 `LIMIT`、先查 id 再查详情、走 `order by id` 延迟关联）或从架构解决（分区/归档/数仓）；
- **索引失效**：修写法（去掉列上的函数、统一类型、避免前导 `%`）。

**必须给证据**：修复前后 `EXPLAIN` 的 `type/rows` 对比 + 实际耗时对比（`SHOW PROFILES` / `EXPLAIN ANALYZE`）。

## 进一步回答（架构师层级）
1. **SQL 治理前置**：SQL 在 CI 阶段跑 EXPLAIN（无索引/全表扫/无 WHERE 更新自动阻断，项目 `SqlGuard` 就是这个思路），别让慢 SQL 进生产；
2. **慢日志 + 采样**：慢查询日志按阈值 + `performance_schema.events_statements_summary_by_digest` 看"最耗时的 SQL 模板"（不是只看单条），结合 DB 监控大盘；
3. **容量与索引代价**：索引不是免费的（写放大、存储、优化器选择），要有"加索引的收益 vs 写成本"评估；核心表索引变更走灰度/online DDL；
4. **根治而非打补丁**：反复慢的表考虑分库分表/冷热分离/读写分离/缓存（项目商品详情走 Redis 缓存，DB 是兜底）；
5. **可观测闭环**：慢 SQL 数、平均耗时、扫描行数做指标与告警，修完回大盘确认趋势回落。

## 理解与复述提示（学习使用，面试时不要直接念）
- **问题本质**：慢 SQL 是"监控/日志定位 SQL → EXPLAIN 找执行计划 → 按选择性修复"的三步，不是"慢就加索引"。
- **回答顺序**：监控/慢日志锁 SQL → EXPLAIN 看 type/rows/Extra → 判断全表扫/低选择性/索引失效 → 按选择性修复 → EXPLAIN+耗时前后对比。
- **必记关键词**：slow_query_log、long_query_time、EXPLAIN、type=ALL/ref/range、rows、覆盖索引、选择性、Using filesort。
- **必须明确的边界**：加索引≠一定快（看选择性）；全表扫不总是坏事（小表/全量聚合）；`EXPLAIN` 的 rows 是预估不是精确。
- **常见错误**：慢就无脑加索引；不看选择性；索引列被函数包裹导致失效不知道；用 `SELECT *` 导致覆盖索引失效。
- **个人信息（真实故障需补充）**：慢 SQL 原文、表行数、修复前后 EXPLAIN 与耗时、影响接口。
- **自测要求**：30 秒能说清"慢日志→EXPLAIN→选择性修复"；3 分钟能说明 type 各档与 Extra；能回答"低选择性为什么加索引没用"。

## 追问与参考回答
**追问1：为什么 type=ALL 不一定需要优化？** → 小表（几百行）全表扫反而比回表快；全量聚合（`COUNT(*)` 无 WHERE）本就要扫全表。看 `rows` 与表规模、查询频率综合判断，别为了"type 好看"硬加索引。
**追问2：索引失效的常见原因？** → 列上套函数（`WHERE DATE(created_at)=...`）、隐式类型转换（字符串列比数字）、前导模糊（`LIKE '%x'`）、OR 跨列、NOT/NULL 判断；`EXPLAIN` 里 `key=NULL` 且 `type=ALL` 就是信号。
**追问3：覆盖索引为什么能显著提速？** → `Extra=Using index` 表示查询所需列都在索引里，不用回表（二次随机 IO）；对 `SELECT 少量列 WHERE 索引列` 最有效；`SELECT *` 会破坏覆盖索引，故按需取列。

## 示例与使用说明
| 项 | 内容 |
|---|---|
| 示例序号 | 示例1 |
| 形式 | 慢 SQL 定位 + EXPLAIN 对比 + 索引修复（本项目真实表） |
| 验证要求 | 完成"EXPLAIN 前后对比 + 耗时对比 + 选择性说明" |
| 使用边界 | 基于本项目 `my_xhs_product.t_product_behavior`（21.2 万行）实测 |

```sql
-- 环境：slow_query_log=ON, long_query_time=0.5, 表 212,102 行
-- 索引现状：PRIMARY(id)、idx_user_id、idx_spu_id；event_time/sku_id/created_at 无索引

-- 【定位】EXPLAIN 发现全表扫描
EXPLAIN SELECT COUNT(*) FROM t_product_behavior WHERE event_time >= '2026-09-19';
-- type=ALL, rows=212102, Extra=Using where   ← 无索引全表扫，实测 27.37ms

-- 对比：高选择性等值（有索引）
EXPLAIN SELECT COUNT(*) FROM t_product_behavior WHERE user_id=10001;
-- type=ref, key=idx_user_id, rows=1, Extra=Using index   ← 命中 1 行，0.296ms（约 92x）

-- 【修复】加索引后
ALTER TABLE t_product_behavior ADD INDEX idx_event_time(event_time);
EXPLAIN SELECT COUNT(*) FROM t_product_behavior WHERE event_time >= '2026-09-19';
-- type=range, key=idx_event_time, rows=106051, Extra=Using where; Using index  ← 13.29ms

-- 【教训】选择性：event_time 范围命中约 50% 行（106051/212102），索引只带来 2x；
--         user_id 等值命中 1 行，索引带来 92x。加索引要看选择性，低选择性要改写法/架构
```

## 面试官评分点
**高级开发级通过标准**：能定位（慢日志→SQL）；能读执行计划（type/rows/Extra）；能权衡（按选择性决定加不加索引）。
**架构师加分项**：能构建（SQL 上线前 EXPLAIN 门禁/SqlGuard、performance_schema 采样）；能定义边界（索引写放大代价、online DDL、灰度）；能兜底（缓存/分库分表/读写分离根治）。
**危险信号**：慢就无脑加索引；不看选择性；不知道索引失效原因；`SELECT *` 破坏覆盖索引。

## 实战练习
1. 对本项目 `t_product_behavior` 分别跑 `user_id` 等值、`event_time` 范围、`sku_id` 无索引三种查询，用 EXPLAIN 对比 type/rows/Extra。
2. 构造一个低选择性查询（命中大半表），证明"加索引只有 2x，不如改写法"。
3. 打开 `performance_schema.events_statements_summary_by_digest`，找出本环境真实最慢的 SQL 模板。

## 版本与来源
- MySQL 慢查询日志（slow_query_log/long_query_time）
- EXPLAIN 输出与访问类型（MySQL 官方文档）
- performance_schema（events_statements_summary_by_digest）

## 真实性说明
技术方法可直接学习；示例与数据基于**本项目真实表 `t_product_behavior`（212,102 行）实测**（EXPLAIN type=ALL→range、耗时 27.37ms→13.29ms、user_id 等值 0.296ms），非虚构；`idx_event_time` 为本次演练实际新增（真实、安全、可 `DROP INDEX` 回退）。
