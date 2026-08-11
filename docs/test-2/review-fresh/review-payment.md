# Payment 模块 Review

## 安全 / 金额
1. **[中] pay() 不校验订单存在/状态/金额** (PaymentService.java:136-216)
   直接信任 request.getAmount() 且不回查订单。生产流程由 order 服务（内部）以服务端计算的 payAmount 调用，
   故金额篡改被内部调用门槛挡住；但支付服务自身不做订单存在性/待付款校验 →
   已取消/不存在的订单仍可创建支付单（连到 Order P0-2：取消后仍可支付）。

2. **[高·设计] 第三方支付回调接口要求 X-Internal-Call，且无签名验签** (PaymentController.java:72-91, 138-163)
   - 真实支付宝/微信回调来自第三方服务器，不会有内部 token → 被 403 拒绝（mock 模式下才走 simulator）。
   - extractPayResult 仅 `callbackData.contains("success")`，无渠道签名验证。
   - 若将来开放给外部且仅靠 internal token（或 token 泄露），可伪造回调。属 mock 实现，上生产需改渠道验签 + 公开回调端点。

## 性能 / 可扩展
3. **[中] checkPaymentTimeout 用 `keys()` 全量扫描** (PaymentService.java:592)
   `stringRedisTemplate.keys("myxhs:payment:status:*")` 每 30s 阻塞扫描全库，O(N) 且阻塞 Redis。
   应改 SCAN 游标。数据量大时是性能隐患。

4. **[中] handlePaySuccessInternal 写永久无 TTL 的 status key** (PaymentService.java:310)
   `myxhs:payment:status:{orderId}=1` 永久不过期（注释：防30min后重复支付）。
   每笔已支付订单产生一个永久 key → Redis 内存无界增长。建议改为有 TTL 或用支付单 DB 状态。

## 正确性
5. **[低] handleRefundSuccessInternal 把整个支付单置为"已退款(3)"** (PaymentService.java:531-534)
   部分退款场景下任一退款成功即整单标记已退款，语义粗糙（无部分退款状态）。

6. **[中] 支付成功/失败：Feign 通知订单 + MQ(PAY_RESULT_TOPIC) 双通道** (PaymentService.java:320-327, 346-352)
   冗余但订单侧乐观锁幂等；PayResultConsumer 仅记日志占位。

7. **[低] 回调解析失败时伪造 paymentNo (`PAY_`+时间戳)** (PaymentController.java:150)
   掩盖解析错误，静默幂等跳过；不利于排查。

8. 退款链路做得较好：Redisson 锁 + DB 乐观锁 + 归属校验(防水平越权) + 可退金额校验 + 退款单状态机。
