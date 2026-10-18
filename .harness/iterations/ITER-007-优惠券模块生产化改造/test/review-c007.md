---
iteration: ITER-007
status: confirmed
version: 1
created: 2026-10-18
updated: 2026-10-18
# 用途：C-007 第三轮深度代码审查（查漏补缺）——针对已落地实现，19 项
---

# 深度审查: C-007（优惠券模块生产化改造）· 第三轮（查漏补缺）

## 审查范围

C-007 全部落地改动：`CouponService`（Design B 发送链 / dispatchClaimResult / useCoupon 包裹 / returnCoupon / N+1 重构）、`CouponClaimConsumer`（守卫+指标）、`CouponDlqReplayConsumer`（新）、`CouponOutboxMapper`（删方法）、三个 Job、`RedisKeyConstants`、SQL 注释 ×2、测试 ×3 文件。

## 审查清单（19 项，逐项校验结论）

> 结论标记：✅ 通过 / 🟡 发现并修复 / 📝 记录（边界）

### A. 部分成功/部分失败（核心）

- **A1 outbox 幂等重试有效性（uk 前提）** ✅ —— `sql/init-all.sql:520` 存在 `UNIQUE INDEX uk_claim_no (claim_no)`："写入超时≠失败"的重试语义成立（重试插同 claimNo 必触发 DuplicateKey）。首次即 DuplicateKey 的分支为防御性（UUID 无碰撞）。即使出现"首写已提交但响应丢失 → 重试"的理论路径，也不会双投递（`markOutboxSent` 按 claim_no 全匹配 + 消费端幂等）。
- **A2 markSent 失败 → 补发重复投递拦截（双保险）** ✅ —— limit=1 时守卫先拦（countIssued≥1 → skip）；limit>1 时守卫放行 → insert 撞 uk_claim_no → DuplicateKey → return。两路径均不重复发券（consumer 分支核验 + 测试矩阵）。
- **A3 补发 Job 与 Design B 交互** ✅ —— Sender 无 cancel/上限逻辑：非 SEND_OK/异常 → `scheduleRetry`（指数退避 30→480s + 抖动，记录不丢弃，"券事件必须最终送达"），与"任何结果保留 Outbox"完全一致。**🟡 R3-1：类级 javadoc 旧叙事（"解决 syncSend 超时回滚导致的双花"）与 Design B 矛盾 → 已修正**。
- **A4 DLQ 重放失败链** ✅ —— 重投失败：不吞异常 → 本组重试 → 本组 DLQ（`%DLQ%coupon-dlq-replay-*-group`，无自动消费方 → 转人工/HITL；指标 dlq_replay_fail 可见）。超限（attempt≥3）：仅告警 + ack 丢弃 → 对账修 claimed 后用户可再领（自愈）。📝 本组 DLQ 无自动消费方——设计选择（防"DLQ→重放→再 DLQ"无限循环），人工兜底。
- **A5 "insert 成功 + decrement 失败"歧义** ✅（自愈）—— 重试时 DuplicateKey 短路跳过 decrement（无法区分"ack 丢失已扣"与"扣减未执行"；跳过是唯一不会双扣的选择）。remain 漂移由对账自愈：`doReconcile` 以 Redis stock 为准回写 MySQL remain（`updateRemainCountOnly`）。📝 记录歧义设计。

### B. 分布式/一致性

- **B1 DLQ topic 名匹配** ✅ —— `%DLQ%coupon-claim-consumer-group` / `%DLQ%coupon-order-termination-consumer-group` 与两消费者实际 `consumerGroup` 逐字核验一致。📝 运行时依赖 broker 自动建 topic（部署口径 autoCreateTopicEnable；无运行时验证按项目规则后置）。
- **B2 重放 × 重领 × 守卫交叉** ✅ —— 重放消息经完整消费链：msgId 新（原始失败已 removeMark）→ 守卫（countIssued 判定）→ insert（uk 兜底）。三重闸门判定源一致（DB 已发券数），无绕行路径。
- **B3 守卫与幂等标记交互** ✅ —— skip 路径 `return`（不 removeMark）→ 标记保留 = 终态决策（countIssued 单调不减，决策稳定）。finally 清 trace ✓。
- **B4 对账收敛方向** ✅ —— claimed：以 DB countIssued 为准（含已用/已过期）修正；stock：以 Redis 为准回写 remain；key 缺失：反向从 MySQL 补全。方向自洽（实时扣减权威 + 已发券事实权威）。**🟡 R3-2：类级 javadoc 三重过时（"每天凌晨 2 点" / "退券时 Redis 操作失败" / "MQ 回滚失败"）→ 已修正**。
- **B5 双败回滚完备性** ✅ —— 唯一回滚路径（可证无消息在途）；回滚自身失败 → 日志"需人工介入" + 对账收敛（Redis 偏小 → 固化漏损，安全方向）。📝 残余：连续两次未知窗口（概率极低、对账可见）。

### C. 数据正确性

- **C1 getAvailableCoupons 语义等价** ✅ —— 原"offline 集合排除" vs 新"status==1 保留"：孤儿/下线/未生效/已过期四类过滤逐类等价核验 + 单测锁定（`getAvailableCouponsSemanticsLocked`）。N+1 → 2 查询（selectList + selectBatchIds 单次复用）。
- **C2 countIssued 口径** ✅ —— 全状态计数（含已用/已过期；无逻辑删除列）——与限领语义（领过即占名额）及对账注释一致。
- **C3 outbox 状态流转** ✅ —— 0 待发/1 已发/2 遗留；清理 `deleteSentBefore` 覆盖 (1,2)；卡单告警统计 status=0 超 5min。**🟡 R3-4：DDL 注释（`sql/init-all.sql:515` + deploy 副本 `:514`）仍描述 status=2 旧产生语义 → 已修正（标注遗留值）**。

### D. 工程/可观测

- **D1 指标命名一致性** ✅ —— 代码 vs 测试断言 vs report.md 三处逐字核对（claim_ok/sold_out/limit/fail、use_ok/fail、return_ok/skip、claim_persist_ok/fail/duplicate、claim_limit_guard_skip、dlq_replay_ok/fail/over_limit、outbox_stuck）。
- **D2 cron 注释真相 + 转义** 🟡 —— 方法级已对齐（前两轮）；**本轮补 R3-3：ExpireJob 类级"每小时"、ReconcileJob 类级"每天凌晨 2 点"残留 → 已修正**。`0 *&#47;5` javadoc 转义核验正确（避免 `*/` 提前终止注释——②轮已踩坑）。
- **D3 死代码/冗余** 🟡 —— `markOutboxCancelled` 零残留（仅删除说明注释）；**本轮补 R3-5：CouponDlqReplayConsumer 两个内嵌类冗余 @Slf4j（无日志使用）→ 已删**。
- **D4 日志分级与 trace** ✅ —— 发送异常/非OK/markSent 失败=WARN（可恢复）；双败=ERROR；守卫拦截=WARN；DLQ 超限=ERROR、重投成功=WARN；两 DLQ 监听器 restore+clear trace（finally）✓。📝 守卫新增 2 次读/delivery（selectById + countIssued）——异步路径可接受。

### E. 验证证据

- **E1 全仓编译** ✅ —— `mvn -o -q compile`（全部模块，非仅 8 模块）零错误。
- **E2 陈旧报告假绿** ✅ —— 已识别并清理（首轮汇总混入 2 个 9 月陈旧 surefire 报告）；本轮按"仅本次运行报告"统计。
- **E3 断言与实现一致性抽查** ✅ —— 关键断言逐条对照实现（守卫指标名、回滚断言 never syncSend、DLQ attempt 属性值、markSent 分支）；无"断言宽松掩盖缺陷"项。

## 第三轮发现汇总（🟡 6 处，均已修复）

| # | 发现 | 类型 | 修复 |
|---|------|------|------|
| R3-1 | CouponOutboxSenderJob 类级 javadoc 旧叙事（超时回滚双花） | 注释漂移 | 重写为 Design B 职责描述 |
| R3-2 | CouponReconcileJob 类级 javadoc 三重过时（cron/退券/MQ 回滚） | 注释漂移 | 重写 + 修正原因清单（DuplicateKey 短路/回滚失败/Redis 重启） |
| R3-3 | CouponExpireJob 类级 javadoc"每小时" | 注释漂移 | 修正为每 5 分钟（引用方法 javadoc 基准） |
| R3-4 | outbox DDL status 注释旧产生语义（init-all ×2 副本） | DDL 注释漂移 | 标注"遗留值：C-007 后不再产生" |
| R3-5 | CouponDlqReplayConsumer 内嵌类冗余 @Slf4j ×2 | 冗余 | 删除 |
| R3-6 | ReconcileJob handleSuccess/log 文案"个模板"（实含 claimed 键修复） | 文案准确度 | 改"项（含模板库存 + 限领计数）" |

## 结论

19 项全部闭环：**零逻辑缺陷**（前两轮修复的 Design B / 守卫 / DLQ 重放实现经第三轮深挖确认自洽——A1 uk 前提、A2 双保险、A5 对账自愈、B4 收敛方向均有代码级证据）；发现 6 处**注释/文案漂移**（C-007 改造的"尾巴"）已全部修复。证据：全仓编译零错误 + 八模块 445 全绿（审查后复核）。
