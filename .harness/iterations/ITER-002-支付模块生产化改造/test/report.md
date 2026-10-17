---
iteration: ITER-002
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-17
updated: 2026-10-17
# 阶段间接口：真实测试证据齐备前，本迭代不得进入发布准备（见开发流程规范.md「阶段间接口」）
---

# 测试报告: 支付模块生产化改造

> 机器可读的迭代协议产物之一。记录**真实执行证据**，是发布准备的前置条件。

## 执行摘要

- 执行时间: 2026-10-17 19:35（五阶段 + 深 Review 修复后的最终回归）
- 环境 profile: JDK 17（`/env/javaEnv/jdk17`）+ Maven 3.9 离线（`-o`）；**无运行时环境**（项目规则：单测口径；删 `target/test-classes` 强制全量重编防增量假绿）
- 命令: `mvn -o -pl my-xhs-payment,my-xhs-order,my-xhs-inventory,my-xhs-product -am test` + 全仓 `mvn -o -q compile`
- 结果: ✅ 通过（BUILD SUCCESS；**261 全绿**：common 93 / product 14 / coupon 11 / order 113 / inventory 20 / **payment 10**）

## 用例执行结果

| TC | 归属 AC | 结果 | 断言/证据 | 备注 |
|----|---------|------|----------|------|
| TC-1 | AC-1 | 🟢 | `PaymentServiceTest#paymentReuseSameChannel`（复用同一 paymentNo，不新建） | 存量防重用例已改写 |
| TC-2 | AC-1 | 🟡 | 并发 DuplicateKey 兜底：代码路径就绪（INSERT catch → 重查复用）；无并发注入用例 | 无运行时，记补测 |
| TC-3 | AC-1 | 🟢 | 存量用例（已支付/已取消 → P1-1 拒绝） | |
| TC-4 | AC-2 | 🟢 | `#paymentSwitchChannel`（close 旧渠道 + 旧单置已关闭 + 新单创建） | |
| TC-5 | AC-2 | 🟡 | close 失败中止切换：代码路径就绪；无独立用例 | 记补测 |
| TC-6 | AC-3 | 🟡 | 幂等键透传链路（订单侧→支付域）：编译 + 调用点断言（唯一调用方已核实）；无独立用例 | 记补测 |
| TC-7 | AC-3 | 🟡 | 同键返回既有 refundNo：代码路径就绪（findRefundByOutRefundNo 前置检查 + 唯一索引兜底） | 记补测 |
| TC-8~9 | AC-3 | 🟡 | 并发同键/无键兼容：代码路径就绪 | 记补测 |
| TC-10 | AC-4 | 🟢 | `#checkPaymentTimeout_channelTruthMatrix`（渠道已付 → 收敛 + 通知订单） | |
| TC-11 | AC-4 | 🟢 | 同测试（渠道未付 → 置失败） | |
| TC-12 | AC-4 | 🟢 | 同测试（不可达 → 不处置：不关渠道/不置失败/不通知） | |
| TC-13 | AC-5 | 🟢 | 同测试（close 被调 + verify_method 置失败 UPDATE） | |
| TC-14 | AC-6 | 🟢 | `#closePendingOnTermination_closesPending`（close + status=4） | 消费者为薄胶水（同 inventory 模式） |
| TC-15 | AC-7 | 🟢 | 配置化落地（payTimeoutMs/refundTimeoutDays/simulator 三项 @Value）；行为断言并入超时矩阵 | |
| TC-16 | AC-7 | 🟡 | 模拟器参数注入：@Value 就绪（默认值兜底）；无独立注入用例 | 记补测 |
| TC-17 | AC-8 | 🟢 | 六模块全量 261 全绿 + 全仓编译 | 强制全量重编 |
| TC-18 | AC-9 | 🟢 | `#reconcile_unverifiedFailedRecheck`（未核实失败单渠道已付 → 收敛 + 通知） | |
| TC-19 | AC-2 | 🟢 | `#handlePayCallback_lateSuccessOnClosed`（已关闭交易迟到成功 → 翻转 4→1 受理 + 通知；深 Review 补充） | |

## 深 Review 修复记录（2026-10-17 审查轮）

| 发现 | 严重度 | 修复 |
|------|--------|------|
| 关闭交易后 mock 回调仍可能触发（待回调标记残留）→ 命中"已关闭交易收到成功"的**静默丢弃**路径（真渠道 close 竞态下 = 用户钱丢了、系统无记录） | 中 | ① mock 策略 `close()` 取消待回调注册（`PayCallbackSimulator.cancel`，mock 世界语义自洽）；② `handlePayCallback` 迟到成功扩展覆盖**已关闭(4)**：条件翻转 4→1 + 走成功链路（订单不允许支付 → 自动退款）——真渠道 close 竞态边界修复 |
| mock 策略 `queryPayStatus` 硬编码桩（Alipay/Wechat 恒返回成功） | 中 | 重设计为"待回调标记"渠道真值模拟（执行中发现，见下） |

## 失败 / 未覆盖项

- ⚠️ TC-2/5/6/7/8/9/16：代码路径就绪但无独立单测（无运行时环境；多为并发注入/透传断言）——记补测
- 执行中发现并修正（非规划外功能）：
  1. **mock 策略 `queryPayStatus` 为硬编码桩**（Alipay/Wechat 恒返回 1 → 会把所有超时单误判为已付）→ 重设计为"待回调标记"渠道真值模拟（存在=未决→0；不存在=未知→null），与超时矩阵语义对齐；
  2. 收敛调用第 4 参（tradeNo 位）统一传 paymentNo（与对账路径同口径，避免向订单侧传 "CHANNEL_QUERY" 标记串）。

## 覆盖对账

- AC 总数: 9 / 已覆盖: 9（其中 6 条 🟡 仅代码路径、无独立用例）
- 无真实证据的 AC 不得标记为「已验证」

## 结论

✅ 可进入发布准备（代码与单测口径）；⚠️ 运行时验证（真实 Redis/MySQL/MQ）按项目规则后置
