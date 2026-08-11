# 支付模块（my-xhs-payment）

> 来源：`my-xhs-order`（Mock 支付，默认）/ `my-xhs-payment`（远程支付）源码直读。

## 一、业务边界
- 支付在**订单模块**统一入口发起：`POST /api/order/pay/create`。
- 两种模式：**mock**（默认，本地模拟）/ **remote**（调用 my-xhs-payment）。
- 前端通常只关心 `payType` 的选择和支付结果，无需关心底层模式。

## 二、payType 语义（mock 模式）
| payType | 渠道 | 结果 |
|:--:|------|------|
| 1 | 支付宝 | 模拟**成功** |
| 2 | 微信 | 约 30% 概率模拟**失败**（余额不足/超时） |

## 三、支付流程
```
1. POST /api/order/pay/create  body: { orderId, payType }
   → 成功： { code:200, data: paymentVO }
   → 失败： { code:400, message:"支付失败: 原因" }  ← 订单会被自动取消（释放库存+退券）
2. 失败后前端必须刷新订单列表/详情（订单状态已变为 4 已取消）。
```

### 查询支付状态
```
GET /api/order/pay/status/{orderId}   (需登录，校验订单归属)
```

## 四、远程支付模式接口（my-xhs-payment，前端一般不经由此直连）
```
POST /api/payment/pay            body: PayCreateRequest
POST /api/payment/callback/{payType}
POST /api/payment/refund
POST /api/payment/refund-callback/{payType}
GET  /api/payment/status/{orderId}
```

## 五、前端接入注意汇总
1. 支付入口统一走 `/order/pay/create`，前端不要直接调 `/payment/**`。
2. `payType=2`（微信）可能失败 → 订单自动取消，需处理失败分支。
3. 支付成功后刷新订单状态（重新 `GET /order/{orderId}` 或 `pay/status`）。
4. 退款(5)一般在管理端触发，前端只展示状态。
