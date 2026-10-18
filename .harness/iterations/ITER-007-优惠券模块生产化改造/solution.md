---
iteration: ITER-007
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-17
updated: 2026-10-17
# 阶段间接口：本文件仅可引用已 confirmed 的 prd.md 中的 REQ/AC；本文件 confirmed 前不得进入 coding
---

# 技术方案: 优惠券模块生产化改造

> 机器可读的迭代协议产物之一。把 prd.md 的 REQ 转化为**实现任务与观测点**。
> 纪律：具体方法逻辑以代码为准，本文件只说明「调哪个接口/方法 + 它在整条链路中的位置」。

## 方案概述

```
领券链路（重构后）：
  Lua 闸门（stock/claimed 原子）→ 恒为最终事实（放行后不回滚*）
  → outbox 落（同 claimNo 幂等重试 ×1；双败才回滚 + 报错——唯一回滚路径）
  → syncSend（任何结果）→ SEND_OK：markSent 尽力；异常/非OK：保留 outbox
  → 领券返回成功（券必达：补发 Job 5s 节奏 + DLQ 重放 ≤3）
  * 残余：双次写入失败（连续两次未知窗口，记录）

投递侧（消费者）：
  msgId 幂等 → 【限领守卫：DB count ≥ perUserLimit → 跳过+告警】→ insert(uk兜底) → decrementRemain
  DLQ → 重放消费者（attempt≤3）→ 重投源 topic
```

四阶段：

| 阶段 | 内容 | 独立价值 |
|---|---|---|
| ① 领券可靠性重构 | Design B + 写入重试 + 测试重写/新增 + 死方法清理 | 零超发向量 |
| ② 一致性与兜底 | 限领守卫 + DLQ 重放 + N+1 消除 + javadoc 清理 | 语义闭环 |
| ③ 可观测与工程 | 指标族 + cron 注释 + 死常量 + 重复代码 | 与改造水准对齐 |
| ④ 收口 | §7 回写 + 八模块回归 | 闭环 |

## REQ → 任务映射

### 阶段① 领券可靠性重构（REQ-1/4 / AC-1~3/6）

| REQ/AC | 实现任务 | 涉及文件/模块 | 观测点 |
|--------|---------|--------------|--------|
| AC-1/2/3 | T1.1 `sendClaimEventSync` 重构：① outbox insert（同 claimNo 重试 ×1：DuplicateKey→按成功；仍失败→return null）② syncSend try/catch（SEND_OK→markSent try/catch 仅 warn；异常/非OK→warn）③ 返回 claimNo（恒成功） | `service/CouponService.java` | **单测：三分支矩阵** |
| AC-2 | T1.2 `claimCoupon`：null 返回（仅双败）→ `rollbackRedisStock` + BizException（保持）；成功日志语义更新 | 同上 | 单测 |
| AC-6 | T1.3 `markOutboxCancelled` 删除（mapper 方法 + 调用点两处，引用面已核） | `mapper/CouponOutboxMapper.java` | 全仓零残留 |
| AC-6 | T1.4 claimCoupon 重复 switch 提取（-3 重试块与主块共用私有方法 `doClaimAndSend(userId, templateId, perUserLimit, stockKey, claimedKey)`） | `service/CouponService.java` | 代码核验 |
| AC-1 | T1.5 测试重写：`claimCouponKeepsOutboxWhenMqSendFails` → 断言"不抛错 + outbox 保留 + never cancel + 返回成功"；新增"双次写入失败 → 回滚 + 抛错" | `CouponServiceTest` | 断言 |

### 阶段② 一致性与兜底（REQ-2/3/5 / AC-4/5/7）

| REQ/AC | 实现任务 | 涉及文件/模块 | 观测点 |
|--------|---------|--------------|--------|
| AC-4 | T2.1 限领守卫：`CouponClaimConsumer` insert 前 `count(userId, templateId) ≥ template.perUserLimit → 跳过 + WARN + 指标`（模板查询用既有 templateMapper） | `consumer/CouponClaimConsumer.java` | 单测：超限跳过/正常放行 |
| AC-5 | T2.2 `CouponDlqReplayConsumer`（新）：两个 `%DLQ%` topic 各一个监听器（或一个类两注解不可——两个类，或统一抽象）：读消息 → 重投源 topic（`rocketMQTemplate.syncSend`）→ 消息属性 `dlqReplayCount` +1（>3 → 仅告警 + 指标）；trace 还原 | `consumer/CouponDlqReplayConsumer.java`（新，两监听器内部类或两个类） | 单测：重投/超限告警 |
| AC-7 | T2.3 `getAvailableCoupons` 重构：单次 `selectBatchIds(distinct)` → Map 复用（下线过滤 + VO 构建 + 孤儿过滤）；`batchToVO` 增加预构建 Map 重载 | `service/CouponService.java` | 单测：T-121 语义锁定（既有用例 + 补下线/孤儿） |
| AC-6 | T2.4 `returnCoupon` javadoc 重写（RV30：只恢复用户券状态；附"为什么"）+ 移除 `evictTemplateCache` | `service/CouponService.java` | 代码核验 |

### 阶段③ 可观测与工程（REQ-6/7 / AC-8）

| REQ/AC | 实现任务 | 涉及文件/模块 | 观测点 |
|--------|---------|--------------|--------|
| AC-8 | T3.1 指标（`BusinessMetrics.recordCouponAction`）：claim（ok/sold_out/limit/fail）、use（ok/fail）、return（ok/skip）、consumer（persist_ok/persist_fail/limit_guard_skip）、dlq_replay（ok/over_limit） | `CouponService`、两个消费者、DLQ 重放 | 代码核验/单测 |
| AC-8 | T3.2 cron 注释对齐（引用 deploy zip `init-xxljob.sql:118/123`）：expire → "每 5 分钟 `0 */5 * * * ?`"；reconcile → "每小时 `0 0 * * * ?`" | `CouponExpireJob`、`CouponReconcileJob` | grep 零残留 |
| AC-8 | T3.3 死常量删除：`RedisKeyConstants.COUPON_STOCK/COUPON_USER` | `my-xhs-common` | 编译 |

### 阶段④ 收口（REQ-8 / AC-9）

| REQ/AC | 实现任务 | 涉及文件/模块 | 观测点 |
|--------|---------|--------------|--------|
| AC-9 | T4.1 八模块全量回归 + 全仓编译 | 全仓 | 测试报告 |
| AC-9 | T4.2 对照 §7 回写（10 线索裁决含 **lead5 翻案** + 7 新发现 + 两轮 review 34 项 + 业务语义论证） | `docs/学习/模块对照/03-优惠券.md` | 文档 |

## 接口与数据变更

- **对外接口**：无新增；claim 失败语义收窄（仅业务结果 + 双次写入失败）
- **消息**：新 DLQ 重放消费者（两个 `%DLQ%` topic）；无新业务 topic
- **数据模型**：无 DDL
- **common**：死常量删除

## 关键设计决策

| 决策 | 结论 | 理由 / 权衡 |
|------|------|------------|
| 领券语义 | Design B：闸门放行后恒成功 + outbox 必达 | 关闭超发向量；内部故障不进用户视野（产品语义）；项目哲学（超时≠失败） |
| 回滚面 | 仅"双次写入失败" | 每次回滚都基于"确定无消息在途"；不确定性一律向安全方向（保留） |
| 写入重试 | 同 claimNo 幂等重试 ×1 | DuplicateKey 免费实现"写入超时≠失败"的验证 |
| DLQ 重放 | attempt≤3 自动 + HITL 补充 | 核心线首个自动消费-DLQ 重放；防循环 |
| 限领守卫 | 投递时 DB 计数守限领 | G35：重放×重领交叉防护；正常路径零影响 |
| 记录不做 | 应然缺口/重型方案/手动退券端点 | 克制，不为技术而技术 |

## 风险与降级

| 风险 | 影响 | 缓解 / 降级 |
|------|------|------------|
| 双次写入失败残余 | 极端下超发窗口（连续两次未知） | 概率极低；对账可见；记录 |
| 补发 Job 长期不可用 | 券延迟到账 | outbox-stuck 告警（>5min ERROR）+ DLQ 重放 + 对账 |
| 限领守卫误杀 | 用户拿不到券 | 仅闸门/DB 分歧时触发（对账修复后的一致态）；告警可见 |
| DLQ 重放循环 | 消息风暴 | attempt≤3 硬上限 |
| getAvailableCoupons 重构回归 | 可用券列表语义漂移 | T-121 用例锁定（validStart/End/下线/孤儿） |

## 人工决策点（已决策，2026-10-17——Owner 授权 AI 决策；经两轮 review + 语义对照）

- 决策 1~5 见 prd.md §人工决策点（Design B / 写入重试 / DLQ 重放 / 限领守卫 / 记录不做）
