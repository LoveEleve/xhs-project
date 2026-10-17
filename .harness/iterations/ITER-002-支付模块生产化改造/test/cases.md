---
iteration: ITER-002
status: draft            # draft / confirmed
version: 1
created: 2026-10-16
updated: 2026-10-16
# 阶段间接口：每条 TC 归属 prd.md 中一条 AC；上游 AC 变更时本文件必须同步
---

# 测试用例: 支付模块生产化改造

> 机器可读的迭代协议产物之一。TC 编号归属 AC，供 test/report.md 与 harness-quality 的 flow→test 对账使用。

## 用例清单

| TC | 归属 AC | 前置条件 | 输入 | 期望输出 | 自动化 |
|----|---------|---------|------|---------|--------|
| TC-1 | AC-1 | 订单存在 pending 支付单（渠道 A） | 再次发起（渠道 A） | 复用同一 paymentNo；不新建；REUSE 事件（**重写存量 `paymentDuplicatePrevention` 用例**） | 是 |
| TC-2 | AC-1 | 无 pending | 并发发起 ×2（同渠道） | 仅 1 条 pending（唯一索引兜底，DuplicateKey→复用） | 是 |
| TC-3 | AC-1 | 订单已支付 | 发起支付 | 维持现行为（P1-1 拒绝） | 是 |
| TC-4 | AC-2 | pending（渠道 A） | 发起（渠道 B） | close(A) 被调 + 旧单 status=4 + 新单创建 | 是 |
| TC-5 | AC-2 | 同上 | close 失败（渠道异常） | 降级：记日志 + 旧单仍关闭 + 新单创建 | 是 |
| TC-6 | AC-3 | 无退款 | refundByOrder(outRefundNo=X) | 新退款单 + out_refund_no=X | 是 |
| TC-7 | AC-3 | 已有 X 的退款单 | 同键重发 | 返回既有 refundNo；不新增；不重复出金 | 是 |
| TC-8 | AC-3 | 同键并发 ×2 | 并发请求 | 唯一索引兜底：1 成功 1 复用 | 是 |
| TC-9 | AC-3 | 无幂等键调用 | refundByOrder(无键) | 兼容现行为 + 日志 | 是 |
| TC-10 | AC-4 | 超时 pending（渠道查询=成功） | 执行超时任务 | 不置失败；本地收敛成功 + 通知订单 | 是 |
| TC-11 | AC-4 | 超时 pending（渠道查询=未支付） | 执行超时任务 | 置失败 | 是 |
| TC-12 | AC-4 | 超时 pending（渠道不可达） | 执行超时任务 | 不处置（状态不变；下一轮重扫） | 是 |
| TC-13 | AC-5 | 同 TC-11 | 执行超时任务 | close 渠道被调 + verify_method=1 落库 | 是 |
| TC-14 | AC-6 | 待付款订单（有 pending 支付单） | 终结事件消费（`OrderTerminationConsumer`） | pending 支付单 status=4 + 渠道 close 被调；后续发起支付被拒 | 是 |
| TC-15 | AC-7 | 配置 pay.timeout.ms=小值 | 超时扫描 | 行为随配置（小值即超时） | 是 |
| TC-16 | AC-7 | 模拟器成功率/延迟配置 | 发起支付 | 参数生效（注入断言） | 是 |
| TC-17 | AC-8 | 全仓 | 全量重编测试 | 全模块基线全绿 + 新增用例通过 | 是 |
| TC-18 | AC-9 | status=2 且 verify_method=2（渠道已付） | 执行对账核查分支 | 收敛成功 + 通知订单 | 是 |
| TC-19 | AC-9 | status=2 且 verify_method=2（渠道确认未付） | 执行对账核查分支 | 补记 verify_method=1（不误收敛） | 是 |

## 边界与降级用例

| TC | 归属 AC | 场景 | 期望 |
|----|---------|------|------|
| TC-20 | AC-1 | 复用请求遇到 pending 已被回调置成功 | 幂等返回（不误复用） |
| TC-21 | AC-2 | 换渠道时旧单已被支付（竞态） | 拒绝换渠道（旧单已成功，按现行为） |
| TC-22 | AC-4 | 超时任务重跑（幂等） | 已处置单不重复处置（乐观锁） |
| TC-23 | AC-3 | 补偿/恢复任务重试退款 | 沿用同键 → 幂等复用 |
