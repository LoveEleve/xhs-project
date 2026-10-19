---
iteration: ITER-012
status: confirmed
version: 1
created: 2026-10-19
updated: 2026-10-19
---

# 测试报告: 通知模块生产化改造

## 执行摘要

- 执行时间: 2026-10-19（三阶段：① 可靠性收敛 → ② 工程对齐 → ③ 收口）
- 环境 profile: JDK 17 + Maven 3.9 离线；无运行时（单测口径；删 target/test-classes 强制全量重编）
- 命令: 十二模块 `mvn -o ... -am test`
- 结果: ✅ **十二模块 614 全绿**（common 100 / product 35 / coupon 24 / order **132**（+8）/ inventory 33 / payment 73 / search 66 / cart 33 / counter 15 / analytics 29 / content 17 / **notification 57（从零建立）**——notification 首次纳入回归集）

## 用例执行结果（要点）

| TC | 归属 AC | 结果 | 断言/证据 |
|----|---------|------|----------|
| TC-1~4 | AC-1 | 🟢 | `NotificationDlqReplayConsumerTest` ×4：重投目的地/KEYS+原 msgId 保留/超限/失败不吞 |
| TC-5~6 | AC-2 | 🟢 | `NotificationEventConsumerTest`：originalMsgId 优先去重；`OrderTerminationConsumerTest`：KEYS→原 msgId→msgId 三级 |
| TC-7~8 | AC-2 | 🟢 | `NotificationServiceTest`：incrementUnread/getUnreadCount 抛异常 → processEvent 不抛（降级） |
| TC-9~11 | AC-3 | 🟢 | `NotificationAggregatorTest`：INSERT 失败/回填失败/PENDING 超时/撞空行/脏值 → 删键自愈（×5） |
| TC-12~14 | AC-3 | 🟢 | 窗口新建（回填 ID）/聚合更新（标题重建）/订单刷新（updateOrderLatest） |
| TC-15~16 | AC-4 | 🟢 | `OrderNotificationPublisherTest` ×5（落库/发送/成功回调按行标记/失败留库/落库失败不阻断）+ `LocalMessageRetryJobTest` ×3（映射 ×2 + 未知拒绝） |
| TC-17 | AC-5 | 🟢 | 限流注解核验（编译级）+ /sse 口径注释 |
| TC-18~19 | AC-6 | 🟢 | forceSetUnread TTL 断言；grep 死物零残留 + F1 注释回正 ×2 |
| TC-20 | AC-7 | 🟢 | 十二模块 **614 全绿** |
| TC-21~30 | 各 AC | 🟢 | 未读 Lua（双键 tag/防负/脏值/静态脚本）；对账（跨批累计/残留清零/日清 SETNX）；SSE（建连/容量帽/直推/跨实例定向/心跳清理/异常隔离）；票据（一次性/脏值）；跨实例订阅（Long→String 回归） |

## 阶段落地（提交记录）

| 阶段 | 提交 | 内容 |
|------|------|------|
| 规划 | `docs(harness): C-012/ITER-012 ...`（cd2b5a1a） | 五件套（F1~F7 + §7 裁决） |
| ①② | `feat(notification,order): C-012①② ...`（ee237420） | DLQ ×2 + 重试幂等 + 窗口自愈 + outbox 化 + 限流 + F1 回正 + 死物 + TTL/脚本 |
| ③ | 见提交链 | 测试集 57（notification）+ 8（order 增补）+ §8 回写 + 面试材料（10-SSE 定向 Channel 同步）|

## 深审查（清单先行，见 review-c012.md）

- 实施期修复：测试构建 ×3 类（MessageExt.properties 懒初始化/getProperties null、MP lambda 缓存初始化、updateOrderLatest null 断言）——均为测试构建问题，锁定后全绿；
- 审查期：F1 教训沉淀（跨模块注释"修正"必须以实际生产/消费方为准——C-009 教训入 §8.2）；
- 结论：见 review-c012（清单全闭环）。

## 收口清单

- [x] 十二模块全量回归 614 全绿（notification 57 首纳）
- [x] §8 回写（09-通知）
- [x] 面试材料同步（10-SSE跨实例：共享 Channel → 定向 Channel 口径）
- [x] change 卡 → done
- [x] 运行时验证按项目规则后置
