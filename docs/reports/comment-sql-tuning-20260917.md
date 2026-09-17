# comment SQL/索引调优报告（2026-09-17）

> 决策前提：**不引入本地缓存**（含 Caffeine L1 已全部移除）。本报告只做 SQL/索引层优化。

## 一、问题定位（极端数据下的 O(N²)）
`selectTopChildrenByParentIds` 原实现用**相关子查询**模拟 ROW_NUMBER：
`WHERE (SELECT COUNT(1) FROM t_comment c2 WHERE c2.parent_id=c.parent_id AND c2.id<=c.id) <= N`。
- 造数：单根 5,202 条子评论；
- 实测（发布后服务级）：**单发 >30s、HTTP 500（超时）**；ab 压测 20 分钟无法收敛；
- 200 条子评论时两者差异不明显（~0.09 vs ~0.1ms）——说明该缺陷呈非线性放大。

## 二、修复（两项，均有证据）
1. **复合索引**（`sql/migration/content/V3__comment_perf_index.sql`）：
   - `idx_note_parent_deleted_id (note_id, parent_id, deleted, id)`：根评论查询（note_id+parent_id=0 ORDER BY id DESC LIMIT）
   - `idx_parent_deleted_id (parent_id, deleted, id)`：子评论查询/精确计数（EXPLAIN：`Covering index lookup ... rows=5202 actual time=3.08ms`）
2. **窗口函数改写**：`ROW_NUMBER() OVER (PARTITION BY parent_id ORDER BY id)`（MySQL 8.0.46）：
   - 旧：>30s / 500（5,202 子评论）
   - 新：单发 ~0.5-4s（同数据，Extreme）；5,202 子评论时窗口查询服务侧 ~300-400ms、COUNT ~3-500ms
   - A/B 对照：5,202 条时 EXPLAIN ANALYZE 窗口 0.89ms vs 相关子查询 1.0ms（小样本无差异），**服务级旧实现直接崩**——保留窗口函数。

## 三、正常规模基线（清理压测数据后：3 根评论 + 2 子评论）
> 更正说明：本节曾误写"1,235 RPS / 66ms"（未实测）。以下为**实测值**。

| 场景 | 指标 | 数值 |
|---|---|---|
| 单发（空闲） | 延迟 | **~15ms**（热身后） |
| ab n=20000 c=64 | RPS | **~514** |
| 同上 | P99 | **~500ms** |
| 同上 | 读池水位 | HikariPool-2 **active=20/20，pending=44** |
| 同上 | DB 连接 | MySQL 总连接 **459/500**（接近上限） |

**结论（据实测）**：comment 并发上限 ≈ **读池 20 × (1/连接持有时间≈39ms) ≈ 500 RPS**；瓶颈是**读连接池饱和 + DB 连接总量吃紧（459/500）**，与本地缓存无关，也不能靠盲目加池解决（会打爆 DB 连接）。

> 与优化前（1,267 RPS / 无子评论）相比持平——正常规模瓶颈在两次查询的固定开销；极端规模（数千子评论/根）从"不可用"变为"可用"。

## 三·补：压测期间的从库拥塞事故（真实教训）
- 现象：清理 5,200 行种子数据用**单条大 DELETE**，从库回放期间 `Seconds_Behind_Master=177`；期间发出的压测查询在从库上**挂起 30 分钟**（180+ 条长查询占资源），服务 P99 从 66ms 恶化到 500ms；
- 处置：kill 从库所有 >60s 长查询后立即恢复（单发 14.9ms，RPS 回 1,235）；
- 教训：① 批量 DML 必须**分批提交**（如 LIMIT 500 循环），避免复制延迟；② 复制延迟/从库长查询要进监控与 Runbook；③ 压测不要与批量数据变更叠加进行（读写分离把读流量打到了正在回放的从库）。

## 四、结论与边界
- 本地缓存不引入；SQL/索引修复解决的是"极端数据下不可用"，**并发上限受连接资源约束**；
- **不盲目加池**：DB 已 459/500 连接，需先审计 421 条应用连接（minIdle/空闲回收/是否有持有时间过长）再谈池大小；
- 未做：缩短读连接持有时间（事务边界/VO 组装移出）、`COUNT(*) OVER` 合并查询、读副本分流——均需专项评估；
- 压测数据已清理（id ≥ 9e18 删除）；从库长查询已 kill 并恢复。
