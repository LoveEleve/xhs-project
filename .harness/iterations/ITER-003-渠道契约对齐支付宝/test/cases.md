---
iteration: ITER-003
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-17
updated: 2026-10-17
# 阶段间接口：每条 TC 归属 prd.md 中一条 AC；上游 AC 变更时本文件必须同步
---

# 测试用例: 渠道契约对齐支付宝

> 机器可读的迭代协议产物之一。TC 编号归属 AC，供 test/report.md 与 harness-quality 的 flow→test 对账使用。

## 用例清单

| TC | 归属 AC | 前置条件 | 输入 | 期望输出 | 自动化 |
|----|---------|---------|------|---------|--------|
| TC-1 | AC-1 | 两对密钥就绪 | 参数集签名 → 验签 | 验签通过；roundtrip 正确（含空值剔除/中文/特殊字符） | 是 |
| TC-2 | AC-1 | 同上 | 篡改任一参数 / 错误公钥 | 验签失败 | 是 |
| TC-3 | AC-2 | 渠道返回各态 | queryPayStatus × 5（WAIT/SUCCESS/FINISHED/CLOSED/不可达） | 0 / 1 / 1 / 0 / null | 是 |
| TC-4 | AC-3 | 支付单 pending | 通知 TRADE_SUCCESS（验签+校验通过） | handlePayCallback(true) + 应答 "success" | 是 |
| TC-5 | AC-3 | 支付单 pending | 通知 TRADE_FINISHED | 同 TC-4（映射为成功） | 是 |
| TC-6 | AC-4 | 支付单 pending | 篡改签名 / 金额不符 / 单号不存在 / 商户不符 | 应答 "fail"；不处理（无状态变化） | 是 |
| TC-7 | AC-5 | 本地 pending | 通知 TRADE_CLOSED | 置失败（渠道确认关闭） | 是 |
| TC-8 | AC-5 | 本地已成功(1)/已退款(3) | 通知 TRADE_CLOSED | 幂等/退款收敛（不误置失败） | 是 |
| TC-9 | AC-5 | 支付单 pending | 通知 WAIT_BUYER_PAY | 忽略（日志；无状态变化） | 是 |
| TC-10 | AC-3 | 同 TC-4 | 重复通知（模拟重试同 notify_id）× 2 | 第二次幂等；应答 "success" | 是 |
| TC-11 | AC-3 | 支付单已关闭(4)（C-002 场景） | 通知 TRADE_SUCCESS（迟到） | 受理：4→1 + 通知订单（保持 C-002 行为） | 是 |
| TC-12 | AC-6 | 无台账 | createTrade | 台账建立（amount/subject/tradeNo/WAIT_BUYER_PAY） | 是 |
| TC-13 | AC-6 | 台账 WAIT | 状态迁移 ×2（并发） | WAIT→SUCCESS 仅一次（原子） | 是 |
| TC-14 | AC-7 | 台账 SUCCESS | 触发通知投递 | 参数完整（total_amount/app_id/seller_id/notify_id）+ 签名可被验签 + POST 到 notifyUrl | 是 |
| TC-15 | AC-7 | 端点应答非 "success" | 投递 × 多次 | 按节奏重试（notify_id 不变）；超上限放弃（台账记 undelivered） | 是 |
| TC-16 | AC-8 | 台账 SUCCESS | refund（同 out_request_no）× 2 | 第一次 fundChange=true；第二次返回同结果（**同号只退一次**，台账不重复扣减） | 是 |
| TC-17 | AC-8 | 注入退款异常 | refund | 抛异常/未知结果（供上层"停留退款中"） | 是 |
| TC-18 | AC-8 | 台账 SUCCESS | close | 台账置 TRADE_CLOSED；query 返回 CLOSED | 是 |
| TC-19 | AC-9 | 支付单 pending（渠道 1） | 模拟器自动付款（延迟到点） | 全链路：签名→HTTP（mock sender）→端点→验签→处理→ACK | 是 |
| TC-20 | AC-9 | 同上 | `/callback/1` 触发 | 走真实链路（同 TC-19）；`/callback/99` → 兼容直调 | 是 |
| TC-21 | AC-10 | 退款单退款中 | refund 同步返回 fundChange=true | handleRefundSuccessInternal（状态=成功） | 是 |
| TC-22 | AC-10 | 退款单退款中 | refund 同步返回 fundChange=false | markRefundFailed（保持现语义） | 是 |
| TC-23 | AC-10 | 退款单退款中 | refund 抛异常（未知） | 停留退款中（恢复任务同 out 键重试路径不变） | 是 |
| TC-24 | AC-10 | 全仓 | 编译 + grep 死引用核验 | refund-callback/handleRefundCallback/registerRefundCallback 无残留引用；微信类已删 | 是 |
| TC-25 | AC-11 | pending（渠道 A） | 换渠道 B（query A=已付） | 受理：收敛成功（不新建） | 是 |
| TC-26 | AC-11 | pending（渠道 A） | 换渠道 B（query A=未付） | close(A) + 旧单 status=4 + 新单创建 | 是 |
| TC-27 | AC-11 | pending（渠道 A） | 换渠道 B（query A=不可达） | 不切换（报错"渠道暂不可达"） | 是 |
| TC-28 | AC-11 | pending（渠道 A） | 换渠道 B（close 失败 → 再查=已付） | 受理收敛（不新建） | 是 |
| TC-29 | AC-12 | 网关配置 | 核验两条白名单 | `auth.white-list` + `auth.hmac-white-list` 含 `/api/payment/notify/**` | 手工 |
| TC-30 | AC-12 | 脚本/部署包 | 核验 | test-10/race 已删 refund-callback 步骤；deploy zip 含 V2__pending_unique.sql | 手工 |
| TC-31 | AC-13 | 全仓 | 全量重编测试 | 六模块基线全绿（261+）+ 新增用例通过 | 是 |

## 边界与降级用例

| TC | 归属 AC | 场景 | 期望 |
|----|---------|------|------|
| TC-32 | AC-3 | 通知处理抛异常（模拟处理失败） | 应答 "fail"（渠道将重试）；状态不推进 |
| TC-33 | AC-7 | 投递锁被占（多实例并发投递） | 仅一个实例投递（锁语义） |
| TC-34 | AC-10 | 恢复任务重试退款（同 out 键） | 契约层幂等复用（不重复出金） |
| TC-35 | AC-11 | 换渠道 query 已付但本地收敛失败（极端） | 不新建（宁可报错）；保留 closed+迟到成功兜底路径 |
