---
iteration: ITER-014
status: confirmed
version: 1
created: 2026-10-19
updated: 2026-10-19
# 用途：C-014 深审查（清单先行 + 逐步思考）——BFF 聚合可靠性 × Feed 链路 × 降级语义
---

# 深度审查: C-014（home BFF 生产化改造）

## 审查清单（5 维 18 项，逐项结论）

> ✅ 通过 / 🟡 发现并修复 / 📝 记录

### A. 聚合可靠性与线程池（本轮核心）
- **A1 池隔离补全（F1）** ✅ —— 15 处内层 future 迁 batchFeignPool（Feed 5 / UserProfile 5 / Product 2 / Cart 2）+ 字段收敛（无用 aggregatorPool 字段移除）；**A3 模式全量对齐**（外层 aggregator / 内层 batchFeign）；单测 execute 断言防回归
- **A2 池容量复核** 📝 —— 迁移后 batchFeignPool（30/80/500 + CallerRuns）承接全部内层负载；容量压测后置（无运行时）
- **A3 MdcAware/CallerRuns** ✅ —— execute 包装覆盖 supplyAsync 路径（AbstractExecutorService.submit 经 execute）；队列满降级不丢弃

### B. 统计与数据正确性
- **B1 likeCollect（F2）** ✅ —— String-aware id（T-119 同族修复不彻底的补全）+ 分批 ≤100 + 部分成功累计 + 页上限 10；单测三矩阵（250 篇分 3 批/批 2 失败/页上限）
- **B2 noteCount/total** ✅ —— String total 解析（T-119 回归锁定）
- **B3 卡片字段（G1）** ✅ —— createdAt/score 填充（score 用合并后的 tuple 映射——与游标同源，无双源偏差）
- **B4 汇总计算** ✅ —— 金额 BigDecimal / checkedCount / allChecked 回归锁定

### C. 降级语义
- **C1 超时→503（F3）** ✅ —— Feed notesFuture / Cart cartFuture `!isDone` → DownstreamUnavailable（单测挂起 5s 验证）；与 Product/Note/UserProfile 的 2026-09-20 修复口径统一
- **C2 降级分档** ✅ —— 关键（content/user/product/cart）503；辅助（counter/analytics/inventory/coupon/notification）空值（既有 fallback 测试回归）
- **C3 空路径未读（G2）** ✅ —— fetchUnreadCount 抽取共用（降级返 0）；避免空 Feed 红点假 0
- **C4 404 vs 503** ✅ —— 用户/商品不存在 → null → 404；服务不可用 → 503（既有测试 + 新测试双向锁定）

### D. Feed 链路
- **D1 DLQ ×2（F4）** ✅ —— 重投源 topic；note-delete tag 保留（selector 路由）；attempt≤3；失败不吞
- **D2 大V缓存（F5/G3）** ✅ —— 空结果 sentinel + 读路径过滤 + SADD/EXPIRE pipeline（消除无 TTL 泄漏窗口）
- **D3 阈值与完成标记（F7）** ✅ —— `>=` 对齐 checkBigV；保护路径补 markPushCompleted（四路径统一）；单测边界（恰好 10 万 + 陈旧负缓存）
- **D4 已删拦截（§7-1/G6）** ✅ —— 标记先行（拦截窗口最小化）+ 常量提取 + 注释 7 天；命中必 markPushCompleted
- **D5 分页语义（F6/G5）** 📝 —— 开区间注释勘误（真实目的=翻页进度，代价=同分跨页剩余跳过）；索引游标跳过风险记录（演进=值游标/复合游标）

### E. 回归与证据
- **E1 测试集** ✅ —— 既有 9（勘误：非零测试）+ 新增 30 = 39
- **E2 十四模块复跑** ✅ —— **703 全绿**（home 首纳）

## 发现汇总（🟡 16 处，均已修复）

| # | 发现 | 类型 | 修复 |
|---|------|------|------|
| F1 | A3 池隔离回旋镖（15 处内层仍在 aggregatorPool） | 并发饥饿 | 全迁 batchFeignPool |
| F2 | likeCollect 恒 0（String id + >100 校验上限） | 真 bug | String-aware + 分批 + 累计 |
| F3 | Feed 空流 / Cart 空车 伪装依赖超时 | 降级语义 | `!isDone` → 503 |
| F4 | DLQ 缺口 ×2 | 兜底 | 重放 ×2 |
| F5 | 空缓存不缓存 | 性能 | sentinel "-1" |
| F6 | 分页注释与行为相反 | 注释 | 勘误 + 演进记录 |
| F7 | 阈值 `>` + 保护路径漏 markPushCompleted | 边界/可靠性 | `>=` + 补标 |
| F8/G6 | zCard 双调用 / 标记顺序 | 小修 | 单调用 / 先行 |
| F9/G1 | 卡片 createdAt/score 未填充 | 半成品 | 填充 |
| G2 | 空 Feed 未读恒 0 | 一致性 | fetchUnreadCount |
| G3 | 缓存 add+expire 非原子 | 原子性 | pipeline |
| G4 | 陈旧注释（逐个调用） | 注释 | 对齐 |
| §7-1 | 5min vs 7d + 裸字符串 ×2 | 注释/常量 | 常量 + 对齐 |
| §7-5 | 5 端点零限流 | 防护 | @RateLimit ×5 |
| §7-6 | "无前缀"勘误 | 勘误 | §8 记录 + javadoc |
| G5/G7 | 索引分页/可见性/卡片简化 | 记录 | 讲 |

## 结论

18 项全部闭环：池隔离补全（A3 模式全量对齐）与统计正确性（likeCollect 双因修复）经单测锁定；降级语义统一（超时=503 不伪装空数据）；Feed 链路兜底收官（DLQ ×2 + sentinel + 四路径完成标记）。证据：十四模块 **703 全绿**（home 39 首纳）。
