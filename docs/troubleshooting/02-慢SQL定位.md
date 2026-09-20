# 第2题 | 慢 SQL：慢日志 → EXPLAIN → 索引（收益取决于选择性）

> 难度：★★★★★｜频率：★★★★★｜区分度：高
> 关键词：slow_query_log、EXPLAIN、type=ALL/ref/range、覆盖索引、selectivity、profile

## 问题
监控发现某接口 P99 上涨，慢查询日志出现一条 SQL。你如何从慢日志定位到这条 SQL，用 EXPLAIN 判断根因，再决定怎么优化（而不是盲目加索引）？

## 30 秒简洁回答
结论：**先看监控定位慢 SQL，再 EXPLAIN 看执行计划，最后按"选择性"决定优化**。第一步从慢查询日志（`slow_query_log`）或监控拿到慢 SQL 与耗时；第二步 `EXPLAIN` 看 `type`（ALL=全表扫 / ref=走索引 / range=范围扫）、`key`（命中的索引）、`rows`（扫描行数）、`Extra`（Using filesort/temporary 等）；第三步按根因修——无索引才加索引、低选择性列加索引收益有限、`Using filesort` 要改 ORDER BY 或加复合索引；修完 `EXPLAIN` + 实测耗时对比验证。

## 展开回答（高级开发级）
**第 0 步 · 监控定位**：慢查询日志（`long_query_time` 阈值 + 日志文件）是第一现场；监控侧 DB 慢查询指标/接口 P99 上涨佐证。拿到"哪条 SQL、耗时多少、出现频率"。

**第 1 步 · EXPLAIN 读执行计划**（关键是四个字段）：
- `type`：`ALL`（全表扫，最差）→ `range`（范围）→ `ref`（非唯一索引等值）→ `const/eq_ref`（主键/唯一）；
- `key`：实际命中的索引（`NULL` = 没走索引）；
- `rows`：估算扫描行数（越接近"结果行数"越好）；
- `Extra`：`Using index`（覆盖索引，只读索引不回表）、`Using where`（回表过滤）、`Using filesort`（额外排序，慢）、`Using temporary`（临时表，慢）。

**第 2 步 · 按根因修，不盲目加索引**：
- **没索引** → 加索引（但要选对列：WHERE/JOIN/ORDER BY 的高选择性列）；
- **有索引但没走** → 检查索引列是否被函数/隐式类型转换/前缀 `LIKE '%x'` 破坏（导致索引失效）；
- **低选择性** → 加索引收益有限（如 status 只有 3 个值），不如改查询/分区/缓存；
- **Using filesort/temporary** → ORDER BY 与 WHERE 不一致，加**复合索引**按 (WHERE 列, ORDER BY 列) 顺序；
- **回表多** → 覆盖索引（把 SELECT 的列放进索引），省回表 IO。

**第 3 步 · 验证**：`EXPLAIN` 对比 + `SET profiling=1` 实测耗时 + 慢日志确认不再出现。

## 进一步回答（架构师层级）
1. **慢 SQL 治理是体系不是救火**：慢查询日志 + 阈值分级 + 告警（超过 N ms/秒级增长），`pt-query-digest`/慢日志分析出 TopN，按"影响面×频率"排优先级；
2. **索引有代价**：每个索引占空间、拖慢写（INSERT/UPDATE 维护 B+Tree）、内存占用，不能见慢就加；要有"写多读少 vs 读多写少"的权衡；
3. **根治靠数据量与 SQL**：数据量级决定了该分页（LIMIT 深翻页 → 游标/keyset）、该分片（见 13 题）、该归档（冷热分离）、该改模型（大表拆分）；索引只是手段之一；
4. **可观测**：DB 慢查询指标接入 Prometheus/大盘，与接口 P99 联动，能从"接口慢"反查"哪条 SQL"；
5. **变更规范**：加索引属 DDL，大表要 `ALGORITHM=INPLACE, LOCK=NONE`（避免锁表），低峰执行 + 回滚预案。

## 理解与复述提示（学习使用，面试时不要直接念）
- **问题本质**：慢 SQL 是"慢日志 → EXPLAIN → 按选择性/根因优化"的定位问题，不是"加个索引"。
- **回答顺序**：监控/慢日志拿 SQL → EXPLAIN 看 type/key/rows/Extra → 按根因修（没索引/索引失效/低选择性/filesort/回表）→ 验证。
- **必记关键词**：type=ALL/ref/range、Using filesort/temporary/index、覆盖索引、选择性、复合索引、索引失效场景。
- **必须明确的边界**：索引有代价（空间/写放大/内存）；低选择性加索引收益有限；EXPLAIN 的 rows 是估算。
- **常见错误**：见慢就加索引不看选择性；`LIKE '%x'`/函数包索引列导致索引失效还硬加；深翻页只调 LIMIT 不换游标；大表加索引用默认 DDL 锁表。
- **个人信息（真实故障需补充）**：表行数、慢 SQL 与耗时、EXPLAIN 前后 type/rows、最终方案（加索引/改 SQL/改模型）、优化前后耗时。
- **自测要求**：30 秒能说清"慢日志→EXPLAIN→按根因修"；3 分钟能说明 type 各值的含义与索引失效场景；能回答"低选择性列要不要加索引"。

## 追问与参考回答
**追问1：有索引但 EXPLAIN 还是 type=ALL，为什么？** → 索引失效：① WHERE 列被函数包（`DATE(created_at)=...`）；② 隐式类型转换（字符串列比较数字）；③ 前缀模糊 `LIKE '%x'`；④ OR 跨列；⑤ 数据分布让优化器认为全扫更划算（回表代价高）。先 `EXPLAIN` 看 `key=NULL` + 检查 SQL，再决定是否改写或加覆盖索引。
**追问2：低选择性列（如 status 只有 3 个值）要不要加索引？** → 通常不加：选择性低 = 索引区分度差，走索引可能比全扫还慢（回表多）。除非配合其他列做**复合索引**（status+created_at），或该查询是"取少量已过滤行"（如 status=1 且时间倒序 LIMIT）。
**追问3：加索引后为什么写变慢？** → 每个二级索引都要维护一份 B+Tree，INSERT/UPDATE/DELETE 要同步更新所有索引 → 写放大；索引占空间与 buffer pool。读多写少的表加索引划算，写多读少的表要克制，用"读写比"权衡。

## 示例与使用说明
| 项 | 内容 |
|---|---|
| 示例序号 | 示例1 |
| 形式 | 诊断命令 + 本项目真实输出 |
| 验证要求 | 完成 EXPLAIN 对比 + 实测耗时对比 + 修复 |
| 使用边界 | 基于本项目真实库 `my_xhs_product.t_product_behavior`（212,102 行） |

```bash
# 表结构与索引（真实）：event_time/behavior_type/created_at/sku_id 均无索引
# PRIMARY(id), idx_user_id(user_id), idx_spu_id(spu_id)

# 第0步 慢日志是否开启（真实：ON, 阈值 0.5s）
SHOW VARIABLES LIKE 'slow_query_log';   -- ON
SHOW VARIABLES LIKE 'long_query_time';  -- 0.5

# 第1步 EXPLAIN：无索引的 event_time 范围查询 → 全表扫
EXPLAIN SELECT COUNT(*) FROM t_product_behavior WHERE event_time >= '2026-09-19'\G
#   type=ALL  key=NULL  rows=212102  Extra=Using where   ← 全表扫描
# 实测耗时 0.027s（21 万行全扫；数据量到千万级会到秒级）

# 对比：有索引的 user_id 等值查询
EXPLAIN SELECT COUNT(*) FROM t_product_behavior WHERE user_id=1001\G
#   type=ref  key=idx_user_id  rows=1  Extra=Using index  ← 命中索引+覆盖索引
# 实测耗时 0.000296s（92 倍差距）

# 第2步 修复：加索引
ALTER TABLE t_product_behavior ADD INDEX idx_event_time(event_time);
EXPLAIN SELECT COUNT(*) FROM t_product_behavior WHERE event_time >= '2026-09-19'\G
#   type=range  key=idx_event_time  rows=106051  ← 索引范围扫，扫描行数减半
# 实测耗时 0.013s（约 2 倍；因为 event_time 范围覆盖近半数据，选择性低，收益有限）
# 教学点：索引收益取决于【选择性】——等值命中 1 行收益 92 倍，范围覆盖半表收益仅 2 倍
```

## 面试官评分点
**高级开发级通过标准**：能分层（慢日志→EXPLAIN→修复）；能权衡（选择性、索引代价、读多写少）；能验证（EXPLAIN+profile 耗时对比）。
**架构师加分项**：能构建（慢 SQL 治理体系：日志+阈值+告警+TopN 优先级）；能定义边界（索引有写放大与空间代价、大表 DDL 用 INPLACE/LOCK=NONE、深翻页换游标）；能兜底（低选择性换复合索引/改模型/分片/归档）。
**危险信号**：见慢就加索引不看选择性；看不出 type=ALL 与索引失效；大表加索引锁表；深翻页只调 LIMIT。

## 实战练习
1. 在本项目 `t_product_behavior`（21 万行）上：EXPLAIN 一个无索引列的查询，记录 type/rows/耗时；加索引后再 EXPLAIN，对比选择性带来的收益差异。
2. 构造一个 `ORDER BY` 与 WHERE 列不一致的查询，看 `Using filesort`，再建 (WHERE列, ORDER BY列) 复合索引消除 filesort。
3. 用 `LIKE '%x'` / `DATE(created_at)` 包索引列，观察 EXPLAIN 从 ref 退回 ALL，理解索引失效。

## 版本与来源
- MySQL 8（EXPLAIN type/rows/Extra 语义、slow_query_log、SHOW PROFILES）
- MySQL 官方文档 EXPLAIN：https://dev.mysql.com/doc/refman/8.0/en/explain.html

## 真实性说明
技术方法可直接学习；示例命令与输出基于**本项目真实库与表**（`t_product_behavior` 212,102 行、EXPLAIN type=ALL/ref/range、耗时 27ms/0.296ms/13ms 均为实测）；`idx_event_time` 索引为本演练新增（可 `DROP INDEX idx_event_time` 回滚），非虚构。
