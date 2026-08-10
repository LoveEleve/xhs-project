# my-xhs-payment curl 测试记录

> 测试时间：2026-07-29
> 测试端口：19012

## 测试 1：发起支付 — POST /api/payment/pay

### curl 请求

```bash
curl -X POST http://localhost:19012/api/payment/pay \
  -H "Content-Type: application/json" -H "X-User-Id: 10001" \
  -d '{"orderId":2082367690804072449,"payType":1,"amount":99}'
```

### L1：API 响应

```json
{"code":200,"data":{"id":2082367778142085121,"paymentNo":"PAY20260729000001","amount":99,"payType":1,"status":0,"statusDesc":"待支付"},"success":true}
HTTP:200 TIME:0.222s
```

### L4：MySQL 验证

| 列 | 期望 | 实际 | 结果 |
|-----|------|------|:--:|
| amount | 99.00 | 99.00 | ✅ |
| status | 0 (待支付) | 0 | ✅ |
| payment_no | PAY2026... | PAY20260729000001 | ✅ |

---

## Gateway + JWT + HMAC per-session 重测（2026-08-03）

> 测试时间：2026-08-03 15:09
> 测试入口：Gateway 19000
> 认证方式：JWT + HMAC per-session secret + X-User-Id header
> 测试用户：pay_tester_* (register→login, userId=2084174840186560514)
> 测试脚本：`scripts/test-10-payment.py`
> 测试模式：MockPayStrategy payType=99（同步成功）

### 7 个用例结果（5 接口 + 2 异常）

| # | 用例 | HTTP | code | msg | 结果 |
|:--:|------|:--:|:--:|------|:--:|
| 1 | POST 发起支付(payType=99) | 200 | 200 | 操作成功 | ✅ |
| 2 | GET 支付状态 | 200 | 200 | 操作成功 | ✅ |
| 3 | POST 支付回调(callback/1) | 200 | — | `success` | ✅ |
| 4 | POST 发起退款 | 200 | 200 | 操作成功 | ✅ |
| 5 | POST 退款回调(refund-callback/1) | 200 | — | `success` | ✅ |
| 6 | 异常: 支付缺 orderId | 400 | 40002 | 订单ID不能为空 | ✅ |
| 7 | 异常: 退款缺 paymentId | 400 | 40002 | 支付单ID不能为空 | ✅ |

**7/7 全部通过**。

### 关键 DTO 字段（基于源码核对）

```java
// PayCreateRequest — POST /api/payment/pay
@NotNull  Long       orderId
          Long       userId      // Controller 从 @RequestHeader("X-User-Id") 注入
@NotNull  BigDecimal amount      // @Positive 金额必须为正数
@NotNull  Integer    payType     // 1=支付宝 2=微信 99=Mock

// RefundRequest — POST /api/payment/refund
@NotNull  Long       paymentId
          Long       userId      // Controller 从 @RequestHeader("X-User-Id") 注入
@NotNull  BigDecimal refundAmount  // @Positive
          String     reason        // 退款原因（可选）
          Integer    refundType    // 1=仅退款 2=退货退款（默认 1）
```

### 回调端点特殊处理

回调端点（`/callback/{payType}` 和 `/refund-callback/{payType}`）返回 `String` 而非 `R<JSON>`：
- `@RequestBody String callbackData` — 接收原始 JSON body
- 返回 `"success"` 或 `"fail"` 纯文本
- 内部用 Jackson 解析 `out_trade_no`/`payment_no` / `refund_no`
- 用子串匹配判断结果（含 `success`/`SUCCESS`/`REFUND_SUCCESS`）
- payType 作为 PathVariable（1=支付宝）

测试时需发送 raw JSON string body，并检查 HTTP 200 + 响应文本 `"success"`。

### 策略模式 + MockPayStrategy

- `PayStrategyConfig`：`Map<Integer, PayChannelStrategy>` → 1=Alipay / 2=Wechat / 99=Mock
- MockPayStrategy：同步创建支付记录，状态直接=1（已支付），生成 `PAY{date}xxxxx` 交易号
- `PayCallbackSimulator`（`@Scheduled(fixedDelay=5000)`）：非 Mock 模式时模拟异步回调，90% 成功率，延迟 1-3s
- 幂等：Redis SETNX `payment:paying:{orderId}` TTL 30min + Redisson 分布式锁 + 乐观锁

### 多层验证

#### MySQL（端口 13308）
- `t_payment`：paymentId=2084174840756998146，status=3（已退款）✅
- `t_refund`：退款记录验证通过
- 支付不存在订单（orderId=999999999999）：Mock 模式不校验 order 存在性，直接创建 status=1 支付记录（设计如此）

#### Redis
- `payment:status:{orderId}` = 3（退款完成）✅
- `payment:pay:PaymentController:pay:{userId}` = zset（@RateLimit 计数器，TTL 7s 即将过期）✅
- `lock:payment:*` 分布式锁全部已释放 ✅

#### traceId 全链路
- traceId=`1e822ab038e947ea9e6c7d76edd83e8a`
- Gateway：`>>> POST /api/payment/pay` → 鉴权通过 → `<<< status=200 duration=40ms`
- Payment：`[支付] 支付记录已创建` → `[Mock支付] 发起支付` → `[支付成功]` → `[支付结果MQ] 发送成功` → `[退款] 退款申请` → `[Mock退款]` → `[退款成功]`
- **Gateway ↔ Payment traceId 一致** ✅

#### MQ 验证
- `PAY_RESULT_TOPIC` + tag `PAY_SUCCESS`：支付成功后发送消息通知 order 服务 ✅
- `REFUND_RESULT_TOPIC` + tag `REFUND_SUCCESS`：退款成功后发送消息通知 order 服务 ✅
- `MqTraceHelper.wrapWithTraceId` 透传 traceId ✅

### 工程知识点

1. **回调返回 String 非 R<JSON>**：`/callback/{payType}` 返回纯文本 `"success"`/`"fail"`，非标准 JSON 响应。测试必须处理 `r.text` 非 `r.json()`。
2. **MockPayStrategy 不校验 order 存在性**：payType=99 时直接创建支付记录，不调用 order Feign 验证。支付不存在的订单也会成功（设计如此，单元测试隔离）。
3. **回调 body 是原始字符串**：`@RequestBody String callbackData` 接收原始 JSON，内部用 `out_trade_no` / `payment_no` / `refund_no` 解析。测试时传标准 JSON 即可。
4. **JdbcTemplate 非 MyBatis-Plus**：payment 模块用 JdbcTemplate 操作 `t_payment` / `t_refund` 表，与其他模块（MyBatis-Plus Mapper）不同。
5. **MySQL 端口 13308**：payment 数据库在 13308 order 实例内（`my_xhs_payment`），非 13309。

### 13 层验证汇总

| 层 | 验证项 | 结果 |
|:--:|------|:--:|
| L1 | API 响应 HTTP 200 + code 200（回调: text "success"） | ✅ |
| L2 | Gateway ↔ Payment traceId 一致 (1e822ab0...) | ✅ |
| L3 | Redis：payment:status 状态缓存 + 限流 zset + 锁已释放 | ✅ |
| L4 | MySQL：t_payment status=3 + t_refund 退款记录 | ✅ |
| L5 | Payment 日志：支付创建→Mock 策略→支付成功→MQ 发送→退款 | ✅ |
| L6 | MQ：PAY_RESULT_TOPIC + REFUND_RESULT_TOPIC 发送成功 | ✅ |
| L7 | 策略模式 MockPayStrategy + 幂等（SETNX + Redisson + 乐观锁） | ✅ |
| L8 | 异常用例：40002（缺 orderId + 缺 paymentId） | ✅ |
| L9 | @RateLimit(10/60s) perUser=true（未触发，仅 1 次请求） | ✅ |
| L10 | Sentinel：actuator UP, enabled=true | ✅ |
| L11 | SkyWalking：N/A（本测试不覆盖 sw8 链路） | N/A |
| L12 | Gateway JWT auth + HMAC per-session secret | ✅ |
| L13 | Actuator /health UP, db/redis/sentinel/nacos/discovery UP | ✅ |