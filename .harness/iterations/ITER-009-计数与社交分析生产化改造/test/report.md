---
iteration: ITER-009
status: confirmed
version: 1
created: 2026-10-18
updated: 2026-10-18
---

# 测试报告: 计数与社交分析生产化改造

## 执行摘要

- 执行时间: 2026-10-18（三阶段：① 一致性根治 → ② 机制统一 → ③ 键模型/工程；收口并入）
- 环境 profile: JDK 17 + Maven 3.9 离线；无运行时（单测口径；删 target/test-classes 强制全量重编）
- 命令: `mvn -o -pl my-xhs-common,my-xhs-product,my-xhs-coupon,my-xhs-order,my-xhs-inventory,my-xhs-payment,my-xhs-search,my-xhs-cart,my-xhs-counter,my-xhs-analytics -am test` + 全仓 `mvn -o -q compile`
- 结果: ✅ **十模块 508 全绿**（common 100 / product 35 / coupon 24 / order 124 / inventory 33 / payment 73 / search 43 / cart 33 / **counter 15** / **analytics 28**——counter/analytics 首次纳入回归集）

## 用例执行结果（要点）

| TC | 归属 AC | 结果 | 断言/证据 |
|----|---------|------|----------|
| TC-1~4 | AC-1 | 🟢 | 颤振四场景：`FollowServiceTest`（W1 ZADD 补员不删行 / U1 ZREM 清残留不插行 / U2 删行 / 不可判保守跳过） |
| TC-5~7 | AC-2 | 🟢 | `CounterServiceTest`（重建+复制+修正 / 复制不重建 / 真0跳过） |
| TC-8~12 | AC-2/3 | 🟢 | `LikeUnlikeConsumerTest` + `CounterEventConsumerTest`（旧事件跳过/新事件应用+推进/失败不推进）+ `VersionGateHelperTest`（7 态矩阵） |
| TC-13~14 | AC-4 | 🟢 | `CounterDlqReplayConsumerTest` + `AnalyticsDlqReplayConsumerTest`（重投+tag/超限/失败不吞） |
| TC-15 | AC-5 | 🟢 | 镜像键核验记录（无读取方→移除）+ 回归全绿 |
| TC-16 | AC-6 | 🟢 | 键 tag 单测/断言（follow 键族 + like 键族 + 测试键串对齐） |
| TC-17~20 | AC-7 | 🟢 | 死代码零残留 grep / cron 与基准 / 脏值守卫单测（batchGet 路径）/ 限流注解 |
| TC-21 | AC-8 | 🟢 | 十模块 508 全绿 |
| TC-24 | AC-2 | 🟢 | 懒迁移 TTL 补（代码核验） |

## 阶段落地（提交记录）

| 阶段 | 提交 | 内容 |
|------|------|------|
| 规划 | `0ac79871` | ITER-009 五件套 |
| ① | `faf629d2` | 颤振双向根治 + like 三环闭环 + 收藏重建（+13 测） |
| ② | `cf368ccf` | DLQ ×5 + 镜像键移除 + 通用打点（+6 测） |
| ③ | 见后续提交 | 键族真 tag + 死代码 ×6 + 注释 ×4 + cron ×2 + 守卫/限流/回填/TTL |

## 深审查（两轮）

- 第一轮（实施期）：跨模块影响面扫描抓到 **R1（search 读旧键格式静默失效）**——键族变更波及的真实问题，已修复；R2/R3 注释遗漏已修；
- 第二轮（查漏补缺）：**R4（search NoteIndexSyncConsumer 读 counter 计数旧键格式——第 2 处跨模块波及）** 已修；R5（常量注释残留）已修；崩溃语义三消费者闭环（含 dedup-skip 路径推进正确性）；getCommonFollowing 截断边界记录。十模块 508 复跑全绿。

## 收口清单

- [x] 十模块全量回归 508 全绿 + 全仓编译
- [x] 深审查（review-c009.md：3 处发现全部修复）
- [x] §8 回写（07-计数 + 08-社交分析）
- [x] change 卡 → done
- [x] 运行时验证（真实 MQ 重放/Cluster slot）按项目规则后置
