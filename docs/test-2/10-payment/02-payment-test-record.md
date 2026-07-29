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