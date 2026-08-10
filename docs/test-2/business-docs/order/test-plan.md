# my-xhs-order + payment 测试执行计划

> 19端点 | 链5 | 依赖链1-4全链 | 参照 TEMPLATE.md

---

## 一、前置准备

```bash
TOKEN=$(cat /tmp/test_token.txt)
# 确认 order/payment 服务在线
curl -s "http://21.130.247.89:18848/nacos/v1/ns/instance/list?serviceName=my-xhs-order&namespaceId=my-xhs" | python3 -c "import json,sys;print('order:',len(json.load(sys.stdin)['hosts']))"
# 需要有SKU+库存+优惠券(可选)
SKU_ID=$(mysql -h 21.130.247.89 -P 3306 -u root -p'Xhs@2026#MySQL' -N -e "SELECT id FROM my_xhs_product.t_sku LIMIT 1" 2>/dev/null)
```

---

## 二、执行顺序

| 顺序 | 端点 | 依赖 | 异常 |
|:--:|------|------|:--:|
| 1 | D01-order-create | SKU+库存+地址+Token | ⚠️ 缺库存/缺Token/重复下单 |
| 2 | D02-order-detail | D01 | — |
| 3 | D03-order-list | D01 | — |
| 4 | D08-pay-create | D01 | ⚠️ Mock 10%失败 |
| 5 | D09-pay-status | D08 | — |
| 6 | D10-pay-success | D08成功 | Internal |
| 7 | D05-order-cancel | D01(pending) | ⚠️ 已支付无法取消 |
| 8 | D04-order-by-no | D01 | — |

---

## 三、关键异常

| 场景 | 验证 | 预期 |
|------|------|------|
| Mock 支付10%失败 | D08 连续10次 | ~1次失败→订单自动取消 |
| 延时关单 | 创建订单不支付等2分钟 | status=4 已取消 |
| 重复下单 | POST D01 相同参数两次 | 第二次幂等返回相同orderId |
| refund 越权 | POST M03 用他人 paymentId | 403 "无权操作该支付单" |
