---
iteration: ITER-009
status: confirmed
version: 1
created: 2026-10-18
updated: 2026-10-18
# 用途：C-009 深审查（实施期查漏补缺）——跨模块影响面与语义保全核验
---

# 深度审查: C-009（计数与社交分析生产化改造）

## 审查范围

C-009 全部落地改动：FollowService（颤振/键族/重建）、LikeService（tag/死反索引/死方法）、FavoriteService（重建）、CounterService（三环/键格式/守卫/回填/死代码）、CounterEventConsumer（完成态门/镜像移除）、5 消费者、DLQ 重放 ×2 类、2 Job、RedisScriptConfig、RedisKeyConstants、DDL、search 跨模块读取点。

## 审查清单（逐项结论）

> ✅ 通过 / 🟡 发现并修复 / 📝 记录

### A. 跨模块影响面（键格式变更的波及）
- **🟡 A1 search 跨模块读 like set**：键格式补 tag 后，search 的两处读取点（`LikeCountSyncConsumer:58` 点赞数同步 + `RecommendComputeJob:361` 推荐特征）仍用旧格式 → **静默失效**（SCARD=0）→ 已修正 ×2 + 测试断言（**实施期抓到的真问题**）
- **A2 home 读取**：经 Feign API（响应格式 "targetType:targetId" 独立于 Redis 键）✓ 零影响
- **A3 其他模块**：全仓 grep 无其他 like/follow/counter 键构造点 ✓

### B. 语义保全
- **B1 颤振双向收敛**：W1/U1/U2/不可判四场景单测 ✓（证据判别=Step A 事实源；不可判保守跳过）
- **B2 like 三环**：重建（t_like）→ 修正 → 集合复制（SUNIONSTORE 同 tag 单 slot）链路单测 ✓
- **B3 完成态版本门**：5 消费者统一共用件；失败不推进/完成后推进/旧事件跳过 矩阵 ✓
- **B4 收藏重建**：懒触发（空结果 + DB 有行）；真 0 不重建 ✓
- **B5 镜像键移除**：无读取方核验（home 仅 1/4 类型、前端零直调、gateway 无直调记录）✓；事件生产保留（禁用消费者路径）

### C. 键模型一致性
- **C1 脚本单 slot**：follow_self/target（两键 {userId}）+ like/favorite（单键，天然安全）逐脚本核验 ✓
- **C2 对账多键操作**：SUNIONSTORE（analytics 集 {targetId} + counter 集 {targetId}）同 tag ✓；getCommonFollowing 去 ZINTERSTORE 跨用户（本地交集）✓
- **C3 解析点**：reconcile 的 parts[3] 去花括号 ✓；scan 模式兼容新格式 ✓

### D. 工程债核验
- **D1 死代码 ×6 删除**：increment/decrement+DECREMENT_SCRIPT / CounterEvent / FollowEvent / likeAtomic+unlikeAtomic（脚本+bean）/ COUNTER_BUFFER ✓ 零残留
- **D2 注释**：LikeService ×2（反索引彻底移除 + 不回滚语义修正）/ Lua ×4 / cron ×2 / DDL target_type ✓
- **D3 韧性**：batchGet/reconcile 脏值守卫 + 单命令回填 ×2 + 懒迁移 TTL + /batch-get 限流 ✓

### E. 证据
- **E1 十模块 508 全绿**（counter 15 / analytics 28 首次纳入）+ 全仓编译
- **E2 实施期发现**：A1（跨模块键格式静默失效）——键族变更的波及扫描价值

## 发现汇总（🟡 3 处，均已修复）

| # | 发现 | 类型 | 修复 |
|---|------|------|------|
| R1 | search 跨模块读 like set 旧键格式（同步/推荐静默失效） | **真问题（跨模块）** | 读取点 ×2 + 测试断言对齐 |
| R2 | unfollow_self/target Lua 注释旧键名（follow 侧已改） | 注释遗漏 | 修正 |
| R3 | sendFollowCounterEvent 注释仍称"更新 counter 计数"（处理已移除） | 注释漂移 | 修正（生产保留口径说明） |

## 结论

跨模块影响面 + 语义保全 + 键一致性 + 工程债全部闭环；实施期抓到 1 个跨模块真问题（R1）已修复。证据：十模块 508 全绿 + 全仓编译。
