---
iteration: ITER-009
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-18
updated: 2026-10-18
# 上游依赖：solution 未确认前，本文件不得进入 coding（见 .harness/rules/开发流程规范.md「阶段间接口」）
# 阶段间接口：REQ 编号一旦确认，禁止在不更新下游产物的情况下改写
---

# PRD: 计数与社交分析生产化改造

> 机器可读的迭代协议产物之一。本文件定义**业务需求与验收标准**，是 solution 的唯一事实来源。

## 背景与目标

**深度探索结论**（代码级证据：`CounterService` 613 / `FollowService` 823 / `LikeService` 480 / `FavoriteService` 237 / 两 Job / 5 消费者 / 10 Lua 全读）：

- **🔴 D1 颤振双向变体**：粉丝侧对账"MySQL-only→清行/Redis-only→插行"的**歧义**（无另一侧证据）↔ 关注侧对账按 Redis 权威反向操作 → W1（follow+StepB 失败）删↔插、U1（unfollow+StepB 失败）插↔删——**每 10 分钟互相打架永不收敛**；
- **🔴 D2 like 数据穿越三环**：① 清零（已止血 10-12）② **只修值不修集合**（修正值被下一次事件用旧集合 SCARD 覆盖）③ **crash 丢事件窗口**（analytics 消费者版本门 reserve-first + 异常删键——kill -9 后重投被拦死；like/favorite **无重建路径** → 永久漂移）；
- **🟡 D3-D6 机制分散**：版本门 **4+ 份拷贝**（counter 脚本常量 + LikeUnlike/FavoriteUnlike 内联 + …）；**DLQ 缺失 ×5 消费组**；三代计数（analytics 权威 / counter `2:{uid}:6/7` 镜像**绝对覆盖** / t_follow）；
- **🟡 D5 M6 集群兼容名不副实**：Lua 注释称 `{userId}` hash tag，实际键无 tag（真 Cluster CROSSSLOT）；
- **🟡 D7 工程债**：死代码 6 处、注释矛盾 ×4、cron 漂移 ×2、DDL 注释、like:set 前缀双轨、脏值守卫缺、/batch-get 零限流、单命令回填未全覆盖、懒迁移 TTL 漏；
- **🟡 D8 测试盲区**：counter 11 测 / analytics 15 测——Lua/版本门/对账/重建/消费者全无覆盖。

**目标**：单一收敛语义（证据判别 + 完成态幂等 + 可重建）+ 机制统一 + 工程收口。

## 非目标（Out of Scope）

search 域剩余线索；content 域本身；通知/拉黑边缘重设计；权限面。

## 需求（REQ）

| ID | 需求描述 | 优先级 |
|----|---------|--------|
| REQ-1 | **颤振根治（D1）**：粉丝侧对账引入**另一侧证据**（follower 的 FOLLOW_LIST 成员关系）判别——真实关系→修 Redis（补/删粉丝 ZSet）；残留→清 MySQL/修 Redis；对侧列表整体缺失时保守跳过。双向变体（W1/U1）都收敛 | P0 |
| REQ-2 | **like 三环闭环（D2）**：② 对账修正时**同时重建 counter `like:set`**（SUNIONSTORE 单命令复制 analytics 集）；③ 版本门改**完成态语义**（只读 stale 检查 + 完成后单调推进；重投可重处理——幂等前提：t_like/t_favorite 唯一索引核验/补 INSERT IGNORE 语义）；**B：analytics 权威缺失时从 `t_like`/`t_favorite` 重建**（对齐 follow 重建先例，替代"跳过等人工"） | P0 |
| REQ-3 | **版本门统一（D3）**：抽 `my-xhs-common` 共用件（完成态语义：isStale 只读 + markDone 单调推进），counter 消费者 + analytics 4 消费者收敛为一套 | P1 |
| REQ-4 | **DLQ 重放 ×5（D4）**：counter-event + like-unlike + favorite-unlike + follow + unfollow 五组 `%DLQ%` 重投源 topic（attempt≤3 防循环，超限仅告警） | P0 |
| REQ-5 | **三代计数收敛（D6）**：核验 counter 镜像键（`2:{uid}:6/7`）读取方——无读取方则**移除**（syncCountersToCounterModule + counter 侧 FOLLOW/UNFOLLOW 事件处理，评估后移除口径）；有读取方则改走事件语义（不绝对覆盖） | P1 |
| REQ-6 | **键模型（D5）**：follow/like/favorite 全部 Lua 脚本键补真 hash tag（每脚本单 slot，Cluster 兼容）；注释对齐 | P1 |
| REQ-7 | **工程收口（D7）**：死代码 6 处删除；注释矛盾 ×4 修正；cron 漂移 ×2 对齐（基准 init-xxljob.sql）；DDL 注释；like:set 前缀统一；脏值守卫（batchGet/reconcile）；`/batch-get` 限流；单命令回填全覆盖；懒迁移 TTL | P1 |
| REQ-8 | **收口**：测试补强（D8 盲区矩阵）+ **counter+analytics 纳入回归集（十模块）** + 台账/§8 回写 + 修订记录 | P0 |

## 验收标准（AC）

| ID | 归属 | 验收标准 | 来源 |
|----|------|---------|------|
| AC-1 | REQ-1 | W1/U1 两场景对账收敛（不颤振）：真实关系修 Redis、残留清/修，多轮对账后稳定一致 | 深探 D1 |
| AC-2 | REQ-2 | like 三环：对账重建 counter set（复制后两集合一致）；crash 重投可重处理（不丢不重）；权威缺失从 DB 重建 | 深探 D2 |
| AC-3 | REQ-3 | 五消费者版本门统一为共用件；行为矩阵（旧事件跳过/新事件推进/失败不推进/崩溃重投可重处理）全绿 | 深探 D3 |
| AC-4 | REQ-4 | 五组 DLQ 重投源 topic（attempt+1）；超限仅告警；重投失败不吞 | 深探 D4 |
| AC-5 | REQ-5 | 镜像键读取方核验记录；移除或语义收敛（二选一，证据落档） | 深探 D6 |
| AC-6 | REQ-6 | 每脚本全部 KEYS 同 tag（单测断言）；注释与实现一致 | 深探 D5 |
| AC-7 | REQ-7 | 死代码零残留/注释零矛盾/cron 与部署基准一致/守卫与限流落位 | 深探 D7 |
| AC-8 | REQ-8 | 十模块全绿（counter/analytics 首次纳入）+ 台账/§8/修订记录回写 | 回归基线 |

## 边界情况

- follower 的 FOLLOW_LIST 整体缺失（Redis 丢数据）→ 粉丝侧对账保守跳过（不删不建，等关注侧重建成后再判别）
- like 权威 Set 缺失且 DB 无行 → 真 0（可清零）；DB 有行 → 重建
- 大集合重建/复制超上限 → 跳过 + 告警（沿用 RELATION_REBUILD_MAX 模式）
- crash 重投的幂等前提：t_like/t_favorite 唯一索引（无则补）——防重投双插
- DLQ 重放 × 版本门完成态 → 重放事件（旧 actionTime）被 stale 检查正确跳过/未处理则重处理

## 非功能需求

| 维度 | 指标 |
|------|------|
| 性能 | 集合重建单命令（SUNIONSTORE）；上限保护 |
| 可靠性 | 颤振收敛；三环闭环；DLQ ×5；版本门崩溃安全 |
| 可观测 | 对账/重建/守卫指标；cron 注释零漂移 |

## 依赖与影响面

- 模块/服务: my-xhs-counter、my-xhs-analytics（主体）；my-xhs-common（版本门共用件）
- 消息: 新 DLQ 重放 ×5；无新业务 topic
- DDL: 无（唯一索引核验/补为预案）
- 涉及 wiki: 无（归档时回流）

## 人工决策点（已决策，2026-10-18——Owner 授权 AI 决策；经深探索）

- **决策 1（权威模型）**：关注关系事实源 = follower 的 FOLLOW_LIST（Step A 原子+失败即抛）；like 权威 = analytics Set（缺则从 DB 重建）
- **决策 2（完成态）**：版本门统一为"完成后推进"（对齐 C-008；reserve-first 的 crash 窗口废除）
- **决策 3（镜像键）**：counter `2:6/7` 无读取方 → 评估后移除（有读取方则收敛语义）
- **决策 4（DLQ 范围）**：5 组全补（counter 1 + analytics 4）
- **决策 5（记录不做）**：search 剩余线索/content 域/通知拉黑边缘
