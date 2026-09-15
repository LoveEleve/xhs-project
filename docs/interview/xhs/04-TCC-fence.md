# 第04题 | TCC 三异常与 Fence 状态机

> 难度：★★★★☆｜频率：★★★★☆｜区分度：高
> 关键词：空回滚、悬挂、幂等、fence 表、乐观锁、状态机 1→2/3

## 问题
问题：TCC 的空回滚、悬挂、幂等三个经典问题怎么解？为什么不用 Seata 内置的？

## 面试可讲版（五段式）

**① 业界背景**
TCC（Try-Confirm-Cancel）绕不开三大异常：**空回滚**（Try 未到，Cancel 先到）、**悬挂**（Cancel 先到，滞后 Try 再到）、**幂等**（Confirm/Cancel 重复到达）。业界主流解法是 **fence 表**（Seata/蚂蚁最佳实践）：用一张带状态的记录表 + 乐观锁，把"事务分支是否执行过、执行到哪一步"变成可判定的状态机，而不是依赖时序假设。

**② 项目选择**
公共组件 `TccFenceService`（`my-xhs-common/tcc`）：`t_tcc_fence` 状态机 **1=Try 成功 / 2=Confirm 成功 / 3=Cancel**。Try 先 `INSERT ... status=1`；Cancel 时若记录不存在则 `INSERT ... status=3`（**空回滚防护**）；Try 发现已有 status=3 则直接拒绝（**悬挂检测**，返回 SUSPENDED）；Confirm/Cancel 用 `UPDATE ... AND status=1` 乐观锁保证只生效一次（**幂等**）。

**③ 坑**
- 早期只靠业务侧判重，Cancel 先到会把"还没发生的 Try"当成已执行，回滚了不该回滚的资源；
- 并发 Confirm/Cancel 无乐观锁会双写状态（后来统一 `WHERE status=1`）；
- **T-074 表结构漂移**：运行库 `t_tcc_freeze_detail` 是 `id` 单主键、DDL 定义是联合主键 → mapper UPSERT 全部 1364 失败 → **TCC Try 完全不可用**；修复为 ALTER 对齐联合 PK（表空）后三态全过——"文档/DDL/运行库三方漂移"是分布式组件的经典坑；
- **旧超时任务三缺陷**（`TccTimeoutJob` 头注释）：按 SKU 聚合 `freezing_stock` 一刀切 → 误回退别单刚冻的；`updated_at` 被任何冻结刷新 → 热 SKU 超时永远不可达；回退不写 fence → 迟到 Confirm 把已回退库存再 confirm → **超卖**。新实现按 xid 逐条取消；
- fence 表本身也是写放大点，需按 xid/branch 索引 + 定期归档。

**④ 兜底**
- **11 场景直接调用全部通过**（`/tcc/try /confirm /cancel`：幂等、空回滚、悬挂拒绝、超量拒绝、fence 状态机 1→2/3）——证据：test-4 运行态对账报告（第六轮深水区验证，2026-09-11）；补充证据：`FINAL-HANDOFF §6` 五场景、`test-3 G5-02-06/07`（三态/超时解冻）；
- 超时未确认由 `TccTimeoutJob`（60s 一轮）扫 status=1 明细 → `cancelFence(1→3)` → 解冻，Redisson 锁保证多实例只有一个 Job 执行；
- 状态机之外的资源操作仍要求幂等（Redis Lua + 预扣幂等表），fence 是"判定层"不是唯一防线；
- 定时对账收敛 fence 与业务数据的偏差；AI 侧审批执行复用了同一思想（状态字段 + CAS + 回收 Job）。

**⑤ 话术**
> "TCC 三异常本质是时序问题，我们用 fence 状态机把时序变成可判定状态；Confirm/Cancel 走 `WHERE status=1` 乐观锁，等价于给资源端加了单调状态校验，不依赖时钟假设。"

## 追问与参考回答
**追问1：fence 与 Seata AT 的 undo log 区别？** undo log 是 AT 模式的自动回滚镜像，对业务侵入小但锁粒度粗；fence 是 TCC 的显式状态判定，控制细、需要业务感知，适合我们的预扣/券这类自定义资源。
**追问2：悬挂检测为什么是 status==3？** Cancel 建了 3 就意味着"事务已决定回滚"，之后任何 Try 都不应该再执行——直接拒绝并告警。
**追问3：fence 表写失败怎么办？** 本地事务内与业务操作同库，失败即整体回滚；跨库场景靠消息重试 + 对账兜底。
**追问4：为什么状态只有 1/2/3？** 保持最小状态集，避免状态爆炸；更细的过程态由业务表承担。
**追问5：Confirm 和 Cancel 谁先到？** 正常由事务协调器保证 Confirm 先；但网络乱序无法排除，所以两方向都要幂等。
**追问6：超时取消为什么按 xid 而不是按 SKU 聚合？** 旧实现按 SKU 聚合 `freezing_stock` + `updated_at`：会误回退别单刚冻的、热 SKU 被刷新后永不超时、回退不写 fence 导致迟到 Confirm 超卖。新实现按每条冻结明细的 `created_at` 独立判定，取消前先 `cancelFence(1→3)` 再解冻——先改判定态，后动库存，顺序不能反。

## 面试官评分点
**高级开发级**：能说清三异常各自成因与 fence 应对；知道乐观锁 `AND status=1` 的作用。
**架构师加分**：fence 与资源幂等的分工、fence 表容量与归档、与 Seata/自研协调器的取舍、跨库一致性。
**危险信号**：只会背"空回滚/悬挂/幂等"名词；没有状态判定实现；Confirm 无幂等。

## 本项目真实证据
- `TccFenceService.java:74`（Try INSERT status=1）、`:85-91`（status==3 → SUSPENDED 悬挂拒绝）、`:121`（Confirm `UPDATE ... status=2 WHERE ... AND status=1`）、`:137,165`（Cancel INSERT/UPDATE status=3，空回滚防护）。
- 测试：**11 场景直接调用实测** `docs/test-4/branch-inventory/business-analysis/99-runtime-reconciliation-report.md:164`（幂等/空回滚/悬挂拒绝/超量拒绝/fence 状态机）；补充 `docs/FINAL-HANDOFF.md:227`（五场景）、`docs/test-3/execution/G5-RERUN-20260816.md:44-45`（G5-02-06/07）；T-074 表结构漂移 `docs/test-3/review/ISSUES.md:417`；超时 Job `TccTimeoutJob.java:47-57`（60s/10min/Redisson 锁）；资源侧幂等见 03 题预扣幂等表。

## 发散追问地图（横向）
- 分布式事务全景：2PC/3PC、TCC、SAGA、本地消息表、事务消息、最大努力通知的适用场景对比。
- Seata 模式：AT/TCC/SAGA/XA 的侵入性、锁粒度、补偿方式；为什么自研 fence。
- TCC 变体：空 Try、异步 Confirm、补偿重试与人工介入；反悬挂的通用做法。
- SAGA：前向恢复 vs 后向恢复、补偿的幂等与顺序。
- 事务消息 vs TCC：资金场景与非资金场景的选择依据。
- 三异常通用解法：幂等键、状态机、超时扫描；fence 表的容量与归档。
- 一致性级别与业务取舍：强一致/最终一致/人工对账。

## 版本与来源
Seata TCC 空回滚/悬挂/幂等官方文档与蚂蚁 fence 表实践；本项目 TccFenceService 代码与测试记录。

## 真实性说明
状态机、SQL、SUSPENDED 判定为代码事实；"11 场景"口径来自 test-4 运行态对账报告（第六轮深水区验证：`/tcc/try|confirm|cancel` 直接调用）；Seata 未实际接入（自研实现，选型理由见 D02）。
