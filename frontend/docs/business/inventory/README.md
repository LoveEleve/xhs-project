# 库存模块（my-xhs-inventory）

> 来源：`my-xhs-inventory` 源码直读。

## 一、业务边界
- 库存预扣/确认/释放（TCC）、库存查询。
- **前端不直接调用**；库存通过 home 聚合/订单流程间接体现。

## 二、接口（多为内部/订单调用）
```
GET  /api/inventory/stock/{skuId}   → StockVO
POST /api/inventory/preDeduct       (下单预扣, 内部)
POST /api/inventory/confirm         (支付成功确认扣减, 内部)
POST /api/inventory/release         (取消/失败释放, 内部)
POST /api/inventory/tcc/try|confirm|cancel
```

## 三、前端接入注意汇总
1. 前端拿库存：商品详情用 `/home/product/{spuId}` 的 `skuList[].availableStock/hasStock`；
   购物车用 `/home/cart` 的 `availableStock/hasStock`。
2. 前端**不要**调 `/api/inventory/**`（需内部/管理员权限）。
3. 无库存/库存不足的 SKU 前端要禁用选择/加购，并提示。
