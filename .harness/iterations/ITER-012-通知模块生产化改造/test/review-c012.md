---
iteration: ITER-012
status: confirmed
version: 1
created: 2026-10-19
updated: 2026-10-19
# 用途：C-012 深审查（清单先行 + 逐步思考）——通知可靠性链 × 跨模块波及面
---

# 深度审查: C-012（通知模块生产化改造）

## 审查清单（5 维 18 项，逐项结论）

> ✅ 通过 / 🟡 发现并修复 / 📝 记录

### A. 可靠性链正确性（本轮核心）
- **A1 DLQ 重放 ×2** ✅ —— 重投源 topic 保留 tag；**KEYS + 原 msgId 属性保留**（消息级幂等场景）；attempt≤3；失败不吞（家族口径）
- **A2 幂等键升级** ✅ —— NotificationEventConsumer 优先原 msgId 属性；OrderTerminationConsumer 三级解析（KEYS→原 msgId→msgId）；**首处理与重放解析一致**（同一 resolveDedupId 函数）
- **A3 重试幂等** ✅ —— post-commit（未读增量/读计数）降级不抛；聚合步骤照旧抛（事务回滚干净）→ 消除双计窗口；**对账兜底路径复核**（10min 以 DB 为准收敛 ✓）
- **A4 窗口锁自愈** ✅ —— 五路径删键（INSERT 失败/回填失败/PENDING 超时/撞空行/脏值）；**回填 vs 删键决策复核**（PENDING 不可判定 → 删键）；自愈语义（最坏 2 条独立行不丢事件）✓
- **A5 outbox 化** ✅ —— 落库（operationType 独立——避免与 ORDER_CREATED/TERMINATED 互相误标）→ 提交后发送（afterCommit 防御）→ **markSuccess 按行 id**（同订单多状态共享 transactionId，不可粗标——与 04B 的 markSuccessByTransactionIdAndType 的差异已注释说明）；补发映射 ✓；落库失败不阻断主链路 ✓

### B. 跨模块波及面（echo 比对）
- **B1 F1 回旋镖（🟡）** —— C-009 "注释矛盾 ×4" 中 DDL target_type 一处**过度纠正**（对齐到 counter 域语义）→ 三份 SQL 副本矛盾 → 回正 ×2；**教训**：跨模块注释修正必须以该字段实际生产/消费方为准
- **B2 生产方分级** ✅ —— 社交（like/comment/follow）best-effort（C-010 口径已有）；订单终结（04B Outbox）；订单状态（C-012 Outbox）——三级口径成文（§8.3）
- **B3 消费端一致性** ✅ —— 重放属性名（originalMsgId/dlqReplayCount）与生产端（重投）单一来源（常量在 NotificationDlqReplayConsumer）——无魔法字符串分散
- **B4 DLQ 监控覆盖** ✅ —— DlqMetrics 已改动态发现本服务消费者组（@RocketMQMessageListener 注解扫描）→ 新增 replay 组自动覆盖，无手工清单

### C. 语义保全
- **C1 SSE 链路** ✅ —— 未改动（仅限流与注释）；10-SSE 面试材料同步（共享 Channel → 定向 Channel 口径——原文档落后于代码）
- **C2 未读语义** ✅ —— TTL 补全不改读写语义；对账/防负/脏值路径回归测试锁定
- **C3 死物边界** ✅ —— 代码删（方法/字段）、表留注（迁移链成本）——边界与 C-009（死代码删）区分说明

### D/E. 工程与证据
- **D1 测试集从零** ✅ —— 57 用例（10 类）；测试构建修正 ×3 类（properties 懒初始化/MP lambda 缓存/ null 断言）锁定
- **E1 十二模块复跑** ✅ —— **614 全绿**（notification 57 首纳）
- **E2 文档同步** ✅ —— §8 回写 + 10-SSE 材料 + §5 限流口径

## 发现汇总（🟡 7 处，均已修复）

| # | 发现 | 类型 | 修复 |
|---|------|------|------|
| F1 | C-009 过度纠正 target_type 注释（回旋镖） | 跨模块注释错误 | init-all ×2 回正 + 教训沉淀 |
| F2 | DLQ 缺口 ×2 组 | 兜底缺口 | 重放 ×2（KEYS/原 msgId 保留） |
| F3 | 重试非幂等窗口（聚合双计） | 部分失败窗口 | post-commit 降级 + 原 msgId 幂等 |
| F4 | 窗口锁不自愈（PENDING 残留） | 状态机自愈 | 五路径删键重选 |
| F5 | 限流缺口 + 文档漂移 | 防护/文档 | 3 端点补限流 + 口径修正 |
| F6 | 订单状态通知可丢 | 可靠性不对称 | outbox 化（at-least-once） |
| F7 | SSE 心跳串行（记录） | 性能观察 | 讲（规模上限内可接受） |

## 结论

18 项全部闭环：可靠性链（DLQ/幂等/自愈/outbox）经单测锁定；跨模块波及面（F1 回旋镖/B2 分级/B4 监控覆盖）全核；测试集从零建立 57 用例。证据：十二模块 **614 全绿**。
