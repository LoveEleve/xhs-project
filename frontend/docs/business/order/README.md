# 订单模块（my-xhs-order）

> 来源：`my-xhs-order` 源码直读。

## 一、业务边界
- 创建订单、订单详情/列表、取消、确认收货、发货、支付发起/状态、超时关单、退款。
- 网关前缀 `/api/order/**`。
- 依赖：inventory（库存预扣/释放）、coupon（用券/退券）、payment（远程支付模式）。

## 二、订单状态机（核心，前端必须对照）
```
0=待付款 → 1=已付款 → 2=已发货 → 3=已完成
              ↘ 4=已取消        ↘ 5=已退款
```
- `statusDesc`：后端返回中文描述，直接展示即可。
- 前端 `ORDER_STATUS_MAP` 与之一致。

## 三、接口

### 创建订单
```
POST /api/order/create   (需登录)
body: { skuItems:[{skuId,quantity}], couponId?, addressId, remark? }
→ 200 { "data": OrderVO }   ← 返回完整订单（含 orderId）
```
- 实时校验库存（不足 → `STOCK_NOT_ENOUGH`）。
- **金额以后端计算为准**（真实 SKU 价 + 真实券折扣），前端展示仅为预估。
- 下单走 RocketMQ 事务消息，30 分钟后未支付自动关单。
- 幂等防重复下单。

> ⚠️ 前端 `api/order.ts` 的 `createOrder` 已从 `{orderId}` 修正为返回 `OrderVO`。

### 订单详情
```
GET /api/order/{orderId}   (需登录) → OrderVO
```
```
orderId, orderNo, totalAmount, payAmount, discountAmount, status, statusDesc,
remark, addressSnapshot, createdAt, paidAt, items[]
OrderItemVO: skuId, skuName, skuImage, price, quantity, totalAmount
```

### 订单列表（**不分页**）
```
GET /api/order/list?status=   (需登录) → List<OrderVO>   ← 直接是数组
```
- `status` 可选：0/1/2/3/4/5；不传 = 全部。
> ⚠️ 前端 `api/order.ts` 已从分页对象修正为 `OrderVO[]`。

### 取消
```
POST /api/order/cancel?orderId=   (需登录)
```
- 仅待付款可取消；取消后释放库存、退优惠券。

### 确认收货
```
POST /api/order/confirm?orderId=   (需登录)
```
- 已发货(2)→已完成(3)。

### 发货（管理端）
```
POST /api/order/deliver   (需登录，实际管理端用) body: { orderId, logisticsCompany, trackingNo }
```

### 支付发起
```
POST /api/order/pay/create   (需登录)
body: { orderId, payType }
```
- 见 payment 模块文档；`payType`：1=支付宝(mock 成功)，2=微信(可能 mock 失败)。

### 支付状态
```
GET /api/order/pay/status/{orderId}   (需登录)
```

## 四、前端接入注意汇总
1. 订单列表**不是分页对象**，是数组（已修 `api/order.ts`）。
2. 创建订单返回完整 `OrderVO`（已修），跳详情页用 `data.orderId`。
3. 只有待付款(0)可支付/取消；已发货(2)可确认收货。
4. 支付可能失败（mock 微信 30% 失败）→ 会**自动取消订单**，前端需刷新并提示。
5. 优惠金额前端只做估算，实付以 `payAmount` 为准。
6. 下单需先有收货地址（用 `/user/address/list` 选择）。
