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


---

# 第二轮深审查（查漏补缺，2026-10-18）

## 审查清单（聚焦"键格式大改的波及面"与"完成态改造的崩溃语义"，5 维 16 项）

> ✅ 通过 / 🟡 发现并修复 / 📝 记录

### A. 完成态改造的崩溃语义再推演
- **A1 崩溃后重投走查（3 消费者）** ✅ —— LikeUnlike/FavoriteUnlike：DB 已写+崩溃 → 重投 → 未推进 → 重处理 → DuplicateKey 幂等 ✓ → markDone；CounterEvent：set-op 后崩溃 → 重投 → 未推进 → 重处理 → **dedup 键（同 msgId 同 {targetId} 键）命中跳过** → markDone（**dedup-skip 路径也推进** ✓ 正确：事件确已应用）
- **A2 版本门 TTL × dedup TTL 交互** ✅ —— >2h 重投（dedup 过期）：set-based/DuplicateKey/DELETE 幂等兜底 ✓
- **A3 markDone 调用正确性** ✅ —— 操作抛异常 → 不推进（重投可重处理）；dedup-skip/条件失败 → 推进（事件已处理）

### B. 键格式波及面
- **B1 旧格式残留（🟡 R5）** —— 代码/测试零逻辑残留；**RedisKeyConstants 孤儿 javadoc**（COUNTER_BUFFER 删除后遗留"计数 Buffer"注释行）+ 注释格式未随键更新 ×4（COUNTER/DEDUP/LIKE_SET/FAVORITE）→ 已修
- **B2 常量注释对齐** ✅（同 R5 修复）
- **B3 部署/种子** ✅ —— 无 Redis 种子（无运行时）

### C. 语义保全
- **C1 getCommonFollowing 边界** 📝 —— 本地交集 vs 原 ZINTERSTORE：双方列表 >MAX_COMMON_FOLLOW_FETCH(5000) 时结果可能少（原服务端全量交集后截取；新读双方各截 MAX）——同 cap 语义，超大列表边界记录
- **C2 重建顺序/score** ✅ —— created_at→score（follow/favorite 一致）；懒触发"真 0 不重建" ✓

### D. 跨模块
- **D1 search 修复覆盖** ✅ —— `LikeCountSyncConsumer`/`RecommendComputeJob`（第一轮）+ **`NoteIndexSyncConsumer.getCounterValue`（🟡 R4 本轮新发现）**——索引同步读 counter 计数的键格式未随改 → **计数静默归零** → 已修 + 注释
- **D2 全仓读取方再扫** ✅ —— `RedisKeyConstants.COUNTER` 外部零使用；`"myxhs:counter:"` 字面量仅版本门/限流前缀（单键/无关）✓

### E/F. 工程与证据
- **E1 死代码删除完整性** ✅ —— RedisScriptConfig 注释头无 like_atomic 残留；COUNTER_BUFFER 无引用
- **E2 限流注解** ✅ —— /batch-get @RateLimit 落位（编译+测试通过）
- **F1 陈旧报告** ✅ —— 十模块 surefire 全新鲜
- **F2 十模块复跑** ✅ —— **508 全绿**（修复后）

## 第二轮发现（🟡 2 处，均已修复）

| # | 发现 | 类型 | 修复 |
|---|------|------|------|
| **R4** | search `NoteIndexSyncConsumer` 读 counter 计数用旧键格式（无 {targetId} tag）→ ES 索引计数静默归零 | **真问题（跨模块·第 2 处波及）** | 键格式对齐 + 注释（C-009 D5） |
| **R5** | RedisKeyConstants 孤儿 javadoc（删除常量遗留）+ 注释未随键格式更新 ×4 | 注释残留 | 全部修正 |

## 第二轮结论

16 项全部闭环：**跨模块键格式波及共 3 处（第一轮 2 处 + 本轮 R4），全部修复**——键族改造的"波及面扫描"成为两轮审查的最大价值点；崩溃语义三消费者闭环；语义边界 1 项记录。证据：十模块 **508 全绿**（复跑）+ 全仓编译。

**两轮审查累计**：R1（search like:note ×2）/R2-R3（注释遗漏）/R4（search counter ×1）/R5（常量注释）——全部修复。


---

# 第三轮深审查（修复的修复 × 新代码细节，2026-10-18）

## 审查清单（5 维 14 项）

> ✅ 通过 / 🟡 发现并修复 / 📝 记录

### G. 前几轮修复的交互再核
- **G1 颤振修复 × 键族 tag** ✅ —— FollowService 全部键构造（含证据查询 followerListContains/重建 rebuildList/对账三方法）走 tagged helper，旧构造零残留（grep 仅 helper 定义命中）
- **G2 like 三环 × 键族 tag** ✅ —— SUNIONSTORE 两侧逐字符对齐：analytics `myxhs:like:note:{id}` / counter `myxhs:like:set:{id}:{type}`——同 tag 单 slot；懒迁移/reconcile 读取点同格式
- **G3 完成态 × DLQ 重放 × dedup 三方交互** ✅（+📝）—— 重放（新 msgId）：版本未推进 → 重处理；dedup 新键 → 放行；uk/DELETE 幂等 ✓。📝 **like 的 Set→DB 方向无修复**：DLQ 超限丢弃场景（确定性失败，罕见）→ Set 有成员/DB 缺行 → 永久分歧（现状无修复路径；B 方案只做 DB→Set 方向——演进记录）

### H. 新代码细节正确性
- **H1 favorite 懒重建触发（🟡 R6）** —— 任意空页触发（翻页越界也白耗 DB 查询/重建尝试）→ **仅首页（start==0）触发** + 测试
- **H2 SUNIONSTORE 空源竞态** 📝 —— SCARD 读取后源集瞬时消失 → union 空 → dest（counter 集）被删——自愈（下一轮对账从 t_like 重建 + 懒迁移）——记录
- **H3 重建 bizType 映射** ✅ —— counter targetType 3（评论）→ t_like biz_type 2（评论）；1→1 ✓（DDL 核对）
- **H4 MyBatis 下划线映射** ✅ —— `map-underscore-to-camel-case: true`（analytics yml:103）——新查询实体回填安全

### I. 全仓一致性
- **I1 LikeService 剩余键构造** ✅ —— 仅 buildLikeKey（tagged）×1 处；无其他 LIKE_SET 拼接
- **I2 文档一致性** ✅ —— 07/08 §8 与实现口径一致
- **I3 测试断言** ✅ —— 全绿（无旧格式断言残留）

## 第三轮发现（🟡 1 处真问题 + 2 项记录）

| # | 发现 | 类型 | 修复 |
|---|------|------|------|
| **R6** | favorite 懒重建对**任意空页**触发（翻页越界 → 白耗 DB 查询 + 重建尝试） | 真问题（性能/逻辑边界） | 触发条件加 `start == 0`（仅首页）+ 越界页测试 |
| 📝 | like 的 Set→DB 方向无修复（DLQ 超限丢弃 → 永久分歧） | 边界记录 | 演进方向（B 方案反向） |
| 📝 | SUNIONSTORE 空源竞态（dest 被删） | 边界记录 | 自愈（下轮对账/懒迁移） |

## 第三轮结论

14 项全部闭环：交互再核（G1-G3）通过——前几轮修复之间无互相破坏（键族 tag 与颤振/三环修复协同一致）；新代码细节抓到 1 处边界问题（R6）已修；2 项边界记录。证据：十模块 **509 全绿**（复跑）+ 全仓编译。

**三轮审查累计**：R1/R4（跨模块键格式波及 ×3 全扫出）/R2-R3/R5（注释遗漏）/R6（懒重建触发边界）——全部修复。
