---
iteration: ITER-011
status: confirmed
version: 1
created: 2026-10-19
updated: 2026-10-19
# 用途：C-011 深审查（清单先行 + 逐步思考）——补偿收敛 × 别名切换 × 墓碑统一 × 兜底
---

# 深度审查: C-011（搜索模块生产化改造）

## 审查清单（5 维 20 项，逐项结论）

> ✅ 通过 / 🟡 发现并修复 / 📝 记录

### A. 补偿收敛与版本域（R1/R3/R5）
- **A1 conflict 豁免矩阵** ✅ —— bulk 逐项：成功/conflict/真失败三类分流；全冲突批次 → 失败集空（收敛）；与 IndexRebuildJob 豁免口径对齐（同族一致性）
- **A2 确定性失败** ✅ —— 商品文档构建失败丢弃 + `doc_build_failed` 指标（重试必然再失败 → 不进入失败集）；**边界**：数据补全后由 Canal 事件/日重建恢复（讲）
- **A3 整批异常保守回填** ✅ —— bulk 抛异常 → 全量回填（宁可多试不丢）；与 A2 不矛盾（批量级≠项级）
- **A4 flat 版本域** ✅ —— ts 域优先（binlogTimestamp→eventTimestamp→ts→now）+ ExternalGte；structured 路径既有 ts 域（P1-4）复核 ✓；**javadoc 勘误**（原"用 es 而非 ts"与代码矛盾——审查期修复）
- **A5 与补偿链路一致性** ✅ —— Canal(ts 毫秒) / flat(ts 毫秒) / 增量补偿(updated_at 毫秒) / rebuild(updated_at 毫秒) 四路同域——**版本域矩阵闭合**

### B. 别名切换（旗舰）
- **B1 resolve 三分支** ✅ —— 别名存在/legacy 原地/自动建账 v1（🟡 **审查期修复**：原用 getAlias 探测，缺失时抛 404 → fresh env 建账分支不可达；改 existsAlias）
- **B2 原子切换** ✅ —— 单次 updateAliases remove+add（actions 断言 hasSize(2)）；非两次调用（无中间态）
- **B3 调用序** ✅（走查）—— doRebuild：resolveTarget → bulk(write) → prune(write) → **switch** → replay(alias) → delete(old)；**切换前任何失败 → 别名不动、旧实体不删**（失败安全）；切换后重放失败 → 别名已新、旧实体保留（可人工回滚）
- **B4 窗口重放** ✅ —— updated_at ≥ rebuildStart + id 游标分页 + ExternalGte（重放与 Canal 并发写幂等）
- **B5 legacy 兼容与续传** ✅ —— legacy → inPlace（不建实体不写续传键）；中断 → status 续传复用（不重复建实体）；完成清 target 键
- **B6 新部署建账（T2.3）** ✅ —— IndexInitializer 对 note/product 走 resolve（新环境直接 v1+别名；存量原地）

### C. 墓碑统一（R4）
- **C1 note 物理删** ✅ —— 双路径（canal wrapper / flat）都走版本化墓碑（status=-1 + ExternalGte + deletedAt）；迟到旧 UPDATE 被拒（防复活）
- **C2 product 墓碑** ✅ —— 既有墓碑补 deletedAt（与 note 对齐）
- **C3 超龄清理** ✅ —— delete_by_query（status=-1 && deletedAt<now-7d）；**边界**：逻辑删墓碑无 deletedAt → 不受影响（由 DB 驱动 pruneByDeletedFlag 清理）——两套清理不重叠不遗漏
- **C4 清理时机** ✅ —— 切前对新实体执行（清理成本不进线上切换路径）

### D. 兜底与工程（R2/R6/R7/R8）
- **D1 DLQ ×5** ✅ —— 5 组监听（组名逐一核对消费端一致）；tag 保留（SOCIAL_TOPIC 按 selectorExpression 路由，丢 tag = 丢路由）；attempt≤3 防循环；重投失败抛出不吞
- **D2 ThreadLocal 对称** ✅ —— Product finally remove（Note 既有）；线程池复用场景残留 = 跨消息串号
- **D3 限流** ✅ —— 8 端点 @RateLimit（perUser + IP 回退）；依赖 Redis（模块既有）
- **D4 注释漂移** ✅ —— 审查期再发现 ×3（HotSearchService 5min ×2 / note javadoc）→ 全清（grep 核验零残留）；实施期 ×3（增量 5min×2 / 推荐权重 / 攒批）

### E. 回归与证据
- **E1 测试新增 20** ✅ —— 收敛矩阵 5 + 别名管理 8 + 重建决策 3 + DLQ 5（含超限/失败传播）
- **E2 十一模块复跑** ✅ —— **546 全绿**（search 63）

## 发现汇总（🟡 3 处，均已修复）

| # | 发现 | 类型 | 修复 |
|---|------|------|------|
| **R1** | IndexAliasManager.resolve 用 getAlias 探测——别名缺失时 ES 抛 404 → fresh env 自动建账分支不可达（首个全新环境重建必炸） | 真缺陷（测试设计阶段发现） | 改 existsAlias 探测 + getAlias 仅存在分支调用 |
| **R2** | note structured 路径 javadoc 与代码矛盾（"用 es 而非 ts"——实际 P1-4 已 ts 域） | 注释漂移（R6 同族） | javadoc 重写（ts 域 + ExternalGte 口径） |
| **R3** | HotSearchService "每 5 分钟" ×2（实际默认 1min 可配） | 注释漂移（R6 同族） | 对齐（保留"生产建议 5min"为建议语义） |

## 结论

20 项全部闭环：补偿收敛矩阵（conflict 豁免/确定性失败/整批保守）与四路版本域一致性经单测锁定；别名切换（resolve/原子切换/窗口重放/legacy/续传）全矩阵覆盖，**调用序走查确认失败安全**；墓碑统一（防复活 + 防泄漏双闭环）；DLQ ×5 与限流补齐。审查期抓获 resolve 探测缺陷 1 处 + 注释漂移 2 处。证据：十一模块 **546 全绿**。
