---
iteration: ITER-003
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-17
updated: 2026-10-17
# 阶段间接口：真实测试证据齐备前，本迭代不得进入发布准备（见开发流程规范.md「阶段间接口」）
---

# 测试报告: 渠道契约对齐支付宝

> 机器可读的迭代协议产物之一。记录**真实执行证据**，是发布准备的前置条件。

## 执行摘要

- 执行时间: 2026-10-17（五阶段 + 缺口用例补齐后的最终回归）
- 环境 profile: JDK 17（`/env/javaEnv/jdk17`）+ Maven 3.9 离线（`-o`）；**无运行时环境**（项目规则：单测口径；删 `target/test-classes` 强制全量重编防增量假绿）
- 命令: `mvn -o -pl my-xhs-common,my-xhs-product,my-xhs-coupon,my-xhs-order,my-xhs-inventory,my-xhs-payment -am test` + 全仓 `mvn -o -q compile`
- 结果: ✅ 通过（BUILD SUCCESS；**324 全绿**：common 93 / product 14 / coupon 11 / order 113 / inventory 20 / **payment 73**——较 261 基线净增 63，全部为 payment 新增/扩展，零回归）

## 用例执行结果

| TC | 归属 AC | 结果 | 断言/证据 | 备注 |
|----|---------|------|----------|------|
| TC-1 | AC-1 | 🟢 | `AlipaySignatureUtilTest#signAndVerifyRoundTrip` + `#buildSignContentRules` + `#chineseAndSpecialCharsRoundTrip` | 真实 RSA2（本地密钥对） |
| TC-2 | AC-1 | 🟢 | `#tamperedParamFails` + `#wrongPublicKeyFails` + `#missingOrMalformedSignFails` | |
| TC-3 | AC-2 | 🟢 | `AlipayPayStrategyTest#queryPayStatusMapping`（5 分支：WAIT/SUCCESS/FINISHED/CLOSED/不可达）+ `#queryPayStatusNullStatus` | 四态→三值映射表 |
| TC-4 | AC-3 | 🟢 | `AlipayNotifyControllerTest#tradeSuccessProcessed`（真实验签链路：支付宝私钥签→公钥验→处理→"success"） | |
| TC-5 | AC-3 | 🟢 | `#tradeFinishedMappedToSuccess` | FINISHED→成功（差异注释） |
| TC-6 | AC-4 | 🟢 | `#signatureFailureRejected` / `#amountMismatchRejected` / `#unknownPaymentRejected` / `#merchantMismatchRejected` / `#unknownTradeStatusRejected`（全部 "fail" + 不处理） | 二次校验三失败分支 |
| TC-7 | AC-5 | 🟢 | `#tradeClosedDispatchesFail`（TRADE_CLOSED → handlePayCallback(false) + "success"） | |
| TC-8 | AC-5 | 🟢 | `PaymentServiceTest#handlePayCallback_closedOnSucceededNoOp`（已成功单收到关闭通知 → 幂等 no-op） | 本轮补齐 |
| TC-9 | AC-5 | 🟢 | `AlipayNotifyControllerTest#waitBuyerPayIgnored` | |
| TC-10 | AC-3 | 🟢 | `#duplicateNotifyIdempotent`（重复通知 ×2 → 均 "success"；CAS 幂等） | |
| TC-11 | AC-3 | 🟢 | `PaymentServiceTest#handlePayCallback_lateSuccessOnClosed`（存量：已关闭 4→1 受理） | |
| TC-12 | AC-6 | 🟢 | `SimulatedAlipayServerTest#createTradeBuildsLedger` + `#createTradeIdempotent` | 台账建立/幂等 |
| TC-13 | AC-6 | 🟡 | 状态迁移原子性：交易锁（setIfAbsent+TTL）+ 单转换路径就绪；无并发注入用例 | 记补测 |
| TC-14 | AC-7 | 🟢 | `#triggerPayDeliversSignedNotify`（参数完整含 total_amount/app_id/seller_id/notify_id + **通知签名可被支付宝公钥验签**） | HTTP 由 sender mock 捕获 |
| TC-15 | AC-7 | 🟢 | `#notifyRetryScheduledOnNoAck`（未 ACK → attempts+1 + 5s 重试）+ `#retryScanRedeliversAndGivesUp`（到期重投 notify_id 不变；超上限放弃） | |
| TC-16 | AC-8 | 🟢 | `#refundSuccessAndFullClose` + `#refundIdempotentByOutRequestNo`（同号只退一次） | |
| TC-17 | AC-8 | 🟢 | `#refundFaultInjection`（异常 + 不落幂等键） | |
| TC-18 | AC-8 | 🟢 | `#closeTradeRules`（WAIT→CLOSED，商户主动关单**不发通知**；已支付→拒绝关闭） | 二轮 Review 修正 |
| TC-19 | AC-9 | 🟢 | `#simulateUserPaymentSuccess`（自动付款→投递）；"服务器签名→端点公钥验签"两段真实链路拼接核验（HTTP 跳为 mock） | |
| TC-20 | AC-9 | 🟢 | `PaymentControllerTest#alipayTriggerGoesRealChain`（/callback/1 → triggerPay，不再直调）+ `#mockChannelDirectCompat`（/callback/99 兼容） | 本轮补齐 |
| TC-21 | AC-10 | 🟡 | 退款成功分支：统一同步分支代码路径就绪；失败分支已测（TC-22），成功分支无独立用例 | 记补测 |
| TC-22 | AC-10 | 🟢 | `PaymentServiceTest#refundSyncFailBranch`（fundChange=false → 退款失败链路） | 本轮补齐 |
| TC-23 | AC-10 | 🟡 | 退款异常分支：`@Transactional` 回滚 + 渠道抛异常路径就绪；无独立用例 | 记补测 |
| TC-24 | AC-10 | 🟢 | 死链清理核验：全仓 grep `PayCallbackSimulator/WechatPayStrategy/handleRefundCallback/registerRefundCallback/refund-callback` **零残留** + 全 reactor 编译通过 | |
| TC-25 | AC-11 | 🟢 | `PaymentServiceTest#paymentSwitchChannel_queryPaidAccepts`（先查=已付 → 受理收敛，不新建不重发） | |
| TC-26 | AC-11 | 🟢 | `#paymentSwitchChannel`（先查=未付 → close + 旧单置 4 + 新建新单） | |
| TC-27 | AC-11 | 🟢 | `#paymentSwitchChannel_queryUnreachableRejects`（不可达 → 报错不切换） | |
| TC-28 | AC-11 | 🟢 | `#paymentSwitchChannel_closeFailRecheckPaid`（close 失败 → 复查已付 → 受理收敛） | |
| TC-29 | AC-12 | 🟢 | 网关两条白名单含 `/api/payment/notify/**`（JWT `white-list` + HMAC `hmac-white-list`；配置核验） | 管道类最小处理 |
| TC-30 | AC-12 | 🟢 | 脚本更新（test-10 删退款回调步骤 / race S3-S5 适配同步退款；py_compile 通过）+ deploy zip 补 `V2__pending_unique.sql` | |
| TC-31 | AC-13 | 🟢 | 六模块全量 324 全绿 + 全仓编译 | 强制全量重编 |
| TC-32 | AC-3 | 🟢 | `AlipayNotifyControllerTest#processingExceptionReturnsFail`（处理异常 → "fail" 触发渠道重试） | |
| TC-33 | AC-8 | 🟡 | 投递锁（多实例并发投递）：tryLock 路径就绪；无并发注入用例 | 记补测 |
| TC-34 | AC-10 | 🟡 | 恢复任务同 out 键重试：渠道侧同号幂等已测（TC-16）；恢复链路整体无独立用例 | 记补测 |
| TC-35 | AC-11 | 🟡 | 极端路径（受理 CAS 未命中仍不新建）：代码路径就绪；无独立用例 | 记补测 |

## 深 Review / 执行中发现与修正

| 发现 | 严重度 | 处理 |
|------|--------|------|
| `forEachRemaining` 为 Iterator 默认方法（Mockito mock 不执行）→ 台账扫描在测试中恒返回空 | 低（测试真实性） | 仓储扫描改 `hasNext/next` 循环（与真实 Cursor 契约一致，测试覆盖扫描逻辑） |
| `RefundTradeResult` 缺无参构造 → Jackson 反序列化（退款幂等结果回读）失败 | 中（潜在运行时） | 补 `@NoArgsConstructor`（同补 `AlipayTradeQueryResult` 对称防护） |
| 换渠道测试原设计（旧单与请求同渠道）落入复用分支 → 未触达先查后决 | 低（测试用例） | 重写为 99→1 跨渠道矩阵（4 用例） |
| `refund()` 入口返回 `R<Void>`（`refundByOrder` 才返回 R<String>）→ 测试断言口径修正 | 低 | 断言调整为 isSuccess + 失败链路 UPDATE 验证 |

## 深 Review 第二轮（代码逻辑复审，2026-10-17）

| 发现 | 严重度 | 处理 |
|------|--------|------|
| **商户关单通知竞态**：close（换渠道/终结/超时）触发的 TRADE_CLOSED 通知若被扫描线程抢先投递，会把"待支付"旧单置为支付失败并联动**订单取消**；随后流程"置已关闭(4)"UPDATE 落空（换渠道失败 + 订单误取消） | 中高 | 商户主动关单**不投递业务通知**（发起方已知悉，通知无增量信息；用户侧放弃/失败由 `triggerPay(false)` 模拟）——消除竞态 |
| **全退通知 nextRetryAt 未落库**：`scheduleDelivery` 在 `repository.save` 之后调用 → 全退关闭通知永不投递（死路径） | 中 | 调整顺序（transition + schedule 在 save 之前）；测试补断言（nextRetryAt > 0 且已落库） |
| **退款幂等结果落库顺序**：台账 save 先于幂等 save → 崩溃窗口内同键重试会重复累计退款额（或全退后误报"交易已关闭"失败） | 中 | 幂等结果**先落**（"同号只退一次"优先于台账记账；计数漂移仅影响超额防护，可容忍） |
| 通知投递链路染色断裂：sender 不带 X-Trace-Id；扫描线程不建新 trace（旧模拟器有 startNewTrace） | 低 | sender 透传 X-Trace-Id（端点 `TraceIdConfig` 拦截器接收）+ 两个扫描循环 startNewTrace/clear |

## 规划微调记录（执行 vs 规划）

| 项 | 规划 | 实际 | 理由 |
|----|------|------|------|
| 配置键 | `payment.alipay.mock.*` | `pay.alipay.mock.*` | 与既有 `pay:`/`refund:` 配置节一致（同文件风格） |
| T1.4/T1.5 实施顺序 | 契约层阶段含客户端实现 + 映射表 | 客户端实现随阶段③（模拟服务器就绪后）、映射表随阶段④（策略改造） | 保证每阶段独立可编译 |
| 退款渠道幂等键 | outRequestNo | 优先业务键 `outRefundNo`（重试稳定），缺省 `refundNo` | 对齐官方"同号只退一次"语义：结果未知的重试同键不重复出金 |

## 覆盖对账

- AC 总数: 13 / 已覆盖: 13（其中 6 条 🟡 仅代码路径、无独立用例——集中在并发注入与恢复链路）
- 无真实证据的 AC 不得标记为「已验证」

## 结论

✅ 可进入发布准备（代码与单测口径）；⚠️ 运行时验证（真实 Redis/MySQL/MQ/HTTP 自循环）按项目规则后置
