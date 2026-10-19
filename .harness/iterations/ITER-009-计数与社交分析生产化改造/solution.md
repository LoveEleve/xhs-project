---
iteration: ITER-009
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-18
updated: 2026-10-18
# 阶段间接口：本文件仅可引用已 confirmed 的 prd.md 中的 REQ/AC；本文件 confirmed 前不得进入 coding
---

# 技术方案: 计数与社交分析生产化改造

> 机器可读的迭代协议产物之一。把 prd.md 的 REQ 转化为**实现任务与观测点**。
> 纪律：具体方法逻辑以代码为准，本文件只说明「调哪个接口/方法 + 它在整条链路中的位置」。

## 方案概述

```
权威模型（目标）：
  关注关系：follower 的 FOLLOW_LIST（Step A：原子 + 失败即抛）为事实源
            —— 粉丝侧对账以它为"另一侧证据"判别 真实/残留
  点赞：analytics Set 为权威（缺失 → 从 t_like 重建，不再跳过等人工）
        counter like:set = 展示副本（对账时 SUNIONSTORE 复制收敛）
  幂等：版本门统一"完成后推进"（只读 stale + 完成后单调推进；崩溃重投可重处理）
  兜底：DLQ 重放 ×5 组（counter + analytics 全消费组）
```

四阶段：

| 阶段 | 内容 | 独立价值 |
|---|---|---|
| ① 一致性根治 | D1 颤振（另一侧证据）+ D2 三环（②集合复制 ③完成态 B重建） | 收敛不打架 + 穿越闭环 |
| ② 机制统一 | D3 版本门共用件 + D4 DLQ ×5 + D6 三代计数收敛 | 一套语义 + 兜底齐 |
| ③ 键模型/工程 | D5 真 tag + D7 死代码/注释/cron/守卫/限流 | 集群正确 + 水准对齐 |
| ④ 收口 | 测试补强 + 十模块回归 + 台账/§8/修订记录 | 闭环 |

## REQ → 任务映射

### 阶段① 一致性根治（REQ-1/2 / AC-1/2）

| REQ/AC | 实现任务 | 涉及文件/模块 | 观测点 |
|--------|---------|--------------|--------|
| AC-1 | T1.1 `repairFollowerRelationships` 引入另一侧证据：MySQL-only 行 → 查 follower 的 FOLLOW_LIST 含 target？真实→ZADD 回粉丝 ZSet（score=created_at）+计数；残留→删 MySQL；Redis-only 成员 → 同证据：真实→INSERT MySQL（现状）；残留→**ZREM 粉丝 ZSet**+计数（不插行）；对侧列表整体缺失→保守跳过 | `analytics/service/FollowService.java` | 单测：W1/U1 两场景多轮收敛 |
| AC-2 | T1.2 counter 对账集合复制：`reconcileLikeFromAnalytics` 修正时 `SUNIONSTORE counterSet analyticsSet` + EXPIRE（替代只修值） | `counter/service/CounterService.java` | 单测：复制后两集合一致 |
| AC-2 | T1.3 版本门完成态：analytics 4 消费者 + counter 消费者改"只读 stale 检查 + 完成后单调推进"（失败不推进→重投重处理）；幂等前提核验：t_like/t_favorite 唯一索引（无则 INSERT IGNORE 语义） | 5 消费者 + mapper | 单测：崩溃重投不丢不重 |
| AC-2 | T1.4 权威重建：like——`reconcileLikeFromAnalytics` 守卫命中（Set 缺失/空）时**从 t_like 重建 analytics Set**（有 DB 行）再比对；DB 无行→真 0；favorite——analytics `myxhs:favorite:{userId}` 重建方法（对齐 follow rebuild 先例）+ 触发点 | `counter`/`analytics` service + mapper | 单测：缺失→重建→一致 |

### 阶段② 机制统一（REQ-3/4/5 / AC-3/4/5）

| REQ/AC | 实现任务 | 涉及文件/模块 | 观测点 |
|--------|---------|--------------|--------|
| AC-3 | T2.1 `my-xhs-common` 新增 `VersionGateHelper`（`isStale(key, eventTime)` 只读 + `markDone(key, eventTime, ttl)` 单调推进 Lua）；5 消费者替换为共用件 | `common/mq/VersionGateHelper.java`（新） | 单测：矩阵 |
| AC-4 | T2.2 `CounterDlqReplayConsumer` + `AnalyticsDlqReplayConsumer`（或统一一类）：5 组 `%DLQ%`（counter-event / like-unlike / favorite-unlike / follow / unfollow）→ 重投源 topic（保留 tag/KEYS）；attempt≤3 | `counter`/`analytics` consumer | 单测：重投/超限 |
| AC-5 | T2.3 镜像键核验：全仓 grep `2:{uid}:6/7`（及动态拼接）读取方——无→移除 `syncCountersToCounterModule` + counter FOLLOW/UNFOLLOW 事件处理（口径=评估后移除）；有→改事件语义 | `analytics/FollowService`、`counter` consumer | 核验记录 + 单测 |

### 阶段③ 键模型/工程（REQ-6/7 / AC-6/7）

| REQ/AC | 实现任务 | 涉及文件/模块 | 观测点 |
|--------|---------|--------------|--------|
| AC-6 | T3.1 全部 Lua 键补真 hash tag（每脚本单 slot）：follow_self/target、unfollow_self/target、like_forward_atomic、unlike_forward_atomic、favorite/unfavorite_atomic——逐脚本 KEYS 分析（用户域/目标域取 tag）；`RedisKeyConstants` 与注释同步 | `analytics` lua + constants | 单测：KEYS 同 tag 断言 |
| AC-7 | T3.2 死代码 6 处删除（increment/decrement+DECREMENT_SCRIPT、CounterEvent、COUNTER_BUFFER、likeAtomic/unlikeAtomic 脚本+bean、FollowEvent） | counter/analytics/common | grep 零残留 |
| AC-7 | T3.3 注释矛盾 ×4 + cron 漂移 ×2 + DDL 注释 + like:set 前缀统一 | 多文件 | grep 核验 |
| AC-7 | T3.4 韧性/防护：batchGet/reconcile 脏值守卫；`/batch-get` 限流；单命令回填全覆盖；懒迁移 TTL | counter | 单测 + 核验 |

### 阶段④ 收口（REQ-8 / AC-8）

| REQ/AC | 实现任务 | 涉及文件/模块 | 观测点 |
|--------|---------|--------------|--------|
| AC-8 | T4.1 测试补强：对账/重建/版本门/DLQ 矩阵（Lua 语义用键断言 + 逻辑单测）；**counter+analytics 纳入回归（十模块）** | `src/test` | 测试报告 |
| AC-8 | T4.2 台账（如需）/07+08 §8 回写 + 修订记录 | docs | 文档 |

## 接口与数据变更

- **REST**：`/batch-get` 补 `@RateLimit`；无其他
- **消息**：DLQ 重放 ×5；无新业务 topic
- **数据模型**：无 DDL（t_like/t_favorite 唯一索引核验；缺失为预案）
- **Redis**：键补 hash tag（tag 变化注意旧键清理说明）

## 关键设计决策

| 决策 | 结论 | 理由 / 权衡 |
|------|------|------------|
| 关注关系事实源 | follower 的 FOLLOW_LIST | Step A 原子+失败即抛 → 单向可判；粉丝侧歧义由它消解 |
| 版本门 | 完成态（对齐 C-008） | reserve-first 的 crash 丢事件窗口废除；重投可重处理 |
| like 权威重建 | t_like → analytics Set | 对齐 follow 重建先例；替代"跳过等人工" |
| counter set 收敛 | SUNIONSTORE 单命令复制 | O(1) 命令级、原子；避免 SMEMBERS→SADD 逐成员 |
| 镜像键 | 评估后移除（无读取方） | 三代计数收敛；有读取方则改事件语义 |
| DLQ | 5 组全补 | 与 C-007/8 对齐；防"消费失败后无人管" |

## 风险与降级

| 风险 | 影响 | 缓解 / 降级 |
|------|------|------------|
| 颤振修复的判别错误 | 误删真实关系 | 证据=Step A 事实源；对侧列表整体缺失保守跳过；单测双场景 |
| 完成态改造回归 | 消费者行为变化 | 行为矩阵单测（旧/新/失败/崩溃四态） |
| 大集合重建 | 对账耗时 | 上限保护 + 单命令复制 |
| 镜像键移除误判 | 隐藏读取方断裂 | 全仓 grep 核验（含动态拼接）后才删 |
| tag 变更 | 旧键残留 | 部署说明清理（无真实环境） |
