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
- fence 表本身也是写放大点，需按 xid/branch 索引 + 定期归档。

**④ 兜底**
- 11 个异常场景用例全通过（空回滚、悬挂、重复 Confirm/Cancel、并发等）；
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

## 面试官评分点
**高级开发级**：能说清三异常各自成因与 fence 应对；知道乐观锁 `AND status=1` 的作用。
**架构师加分**：fence 与资源幂等的分工、fence 表容量与归档、与 Seata/自研协调器的取舍、跨库一致性。
**危险信号**：只会背"空回滚/悬挂/幂等"名词；没有状态判定实现；Confirm 无幂等。

## 本项目真实证据
- `TccFenceService.java:74`（Try INSERT status=1）、`:85-91`（status==3 → SUSPENDED 悬挂拒绝）、`:121`（Confirm `UPDATE ... status=2 WHERE ... AND status=1`）、`:137,165`（Cancel INSERT/UPDATE status=3，空回滚防护）。
- 测试：11 个 TCC 异常场景通过（手册 §E / 测试矩阵）；资源侧幂等见 03 题预扣幂等表。

## 版本与来源
Seata TCC 空回滚/悬挂/幂等官方文档与蚂蚁 fence 表实践；本项目 TccFenceService 代码与测试记录。

## 真实性说明
状态机、SQL、SUSPENDED 判定为代码事实；"11 场景"为测试矩阵口径；Seata 未实际接入（自研实现，选型理由见 D02）。
