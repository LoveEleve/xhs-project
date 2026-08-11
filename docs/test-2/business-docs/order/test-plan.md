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

| 顺序 | 端点 | 依赖 | 产出 | 异常 |
|:--:|------|------|------|:--:|
| 1 | D01-order-create | SKU+库存+地址+Token | execution/order/D01-order-create.md | ⚠️ 缺库存/缺Token/重复下单 |
| 2 | D02-order-detail | D01 | execution/order/D02-order-detail.md | — |
| 3 | D03-order-list | D01 | execution/order/D03-order-list.md | — |
| 4 | D08-pay-create | D01 | execution/order/D08-pay-create.md | ⚠️ Mock 10%失败 |
| 5 | D09-pay-status | D08 | execution/order/D09-pay-status.md | — |
| 6 | D10-pay-success | D08成功 | execution/order/D10-pay-success.md | Internal |
| 7 | D05-order-cancel | D01(pending) | execution/order/D05-order-cancel.md | ⚠️ 已支付无法取消 |
| 8 | D04-order-by-no | D01 | execution/order/D04-order-by-no.md | — |

---

## 三、关键异常

| 场景 | 验证 | 预期 |
|------|------|------|
| Mock 支付10%失败 | D08 连续10次 | ~1次失败→订单自动取消 |
| 延时关单 | 创建订单不支付等2分钟 | status=4 已取消 |
| 重复下单 | POST D01 相同参数两次 | 第二次幂等返回相同orderId |
| refund 越权 | POST M03 用他人 paymentId | 403 "无权操作该支付单" |

---
## 测试要点补充（2026-08-10，实测修正）

- **D01-order-create**：走**事务消息异步落库**，返回200后需等落库再查详情；body{skuItems,addressId,bizIdentifier}；需 JWT+HMAC。
- **D05-order-cancel**：`orderId` 是 **query 参数**；已支付订单取消返回 30009。
- **内部端点（X-Internal-Call 直连 19011）**：
  - POST /api/order/pay-success?orderId=&tradeNo= （query）
  - POST /api/order/pay-fail?orderId=
  - POST /api/order/refund-success / refund-fail
  - GET /api/order/pay-amount?orderId=
- **D08-pay-create** body{orderId,payType}；D09-pay-status GET。
- 已验证：订单 addressSnapshot=真实地址、skuImage=图片（#40/#41 已修）。

---
## L0-L4 逐端点核对清单

### D01-order-create
- [ ] L0: token+HMAC + SKU + 地址 + 库存桶 + 幂等键
- [ ] L1正常: 200 返回orderId; 异常: 缺库存→STOCK_NOT_ENOUGH, 重复→幂等拦截
- [ ] L2: 分片库定位订单(`SHOW DATABASES LIKE 'my_xhs_order%'`→`my_xhs_order_{n}.t_order_{m}`)+明细+本地消息表; Redis库存预扣; 地址快照=真实、skuImage非空
- [ ] L3: 分布式(TCC预扣/事务消息/Outbox) / 幂等 / 一致性

### D08-pay-create / D10-pay-success
- [ ] L1: pay/create → 200 支付单; pay-success(内部,X-Internal-Call,query orderId+tradeNo) → 200
- [ ] L2: 支付单status流转; order status 0→1
- [ ] L3: 事务消息 / 幂等

### D05-order-cancel
- [ ] L1: POST `?orderId=` → 200; 已支付→30009
- [ ] L2: order status→4; 退库存/退券
- [ ] L3: 幂等 / 补偿
