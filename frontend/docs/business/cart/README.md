# 购物车模块（my-xhs-cart）

> 来源：`my-xhs-cart` + `my-xhs-home`（购物车聚合）源码直读。

## 一、业务边界
- **cart**：加购/改数量/删除/勾选/全选/列表/合并/清空/数量。网关前缀 `/api/cart/**`。
- **home**：购物车聚合（购物车+库存+可用券）。`/api/home/cart`。
- 底层 Redis + 购物车快照表，匿名购物车存 localStorage。

## 二、接口

### 加购
```
POST /api/cart/add   (需登录)
body: { skuId, quantity(默认1) }
```
- 品种上限 50 种 / 单品上限 99 件（超限报错）。
- Redis 原子操作（Lua）。

### 改数量
```
PUT /api/cart/quantity   body: { skuId, quantity }
```

### 删除 / 清空
```
DELETE /api/cart/{skuId}
DELETE /api/cart/clear
```

### 勾选 / 全选
```
PUT /api/cart/check       body: { skuId, checked(Boolean) }
PUT /api/cart/check-all   body: { checked(Boolean) }
```

### 列表 / 数量
```
GET /api/cart/list   → CartListVO
GET /api/cart/count  → { "count": N }
```

### 匿名购物车合并（登录时调用）
```
POST /api/cart/merge   body: { items: [{ skuId, quantity }] }
```
- 合并策略取 **max** 数量，幂等。
- 合并完成后前端应清除 localStorage 中的游客购物车。

### 购物车聚合（前端购物车页用这个）
```
GET /api/home/cart   (需登录) → CartAggVO
```
```
items[]: [{ skuId, spuId, skuName, skuImage, price, quantity, checked,
            totalAmount, availableStock, hasStock, onSale }],
checkedCount, checkedAmount, totalCount, allChecked,
availableCouponCount, availableCoupons[]
```

## 三、数据模型
```
CartItemVO(cart/list): skuId, spuId, name, price, originalPrice, quantity,
                       checked, specs, image, valid, invalidReason, addedAt
```
- `valid=false` + `invalidReason`：表示该商品已下架/信息丢失，前端应置灰并展示原因。

## 四、前端接入注意汇总
1. 购物车页建议用 `/home/cart`（带库存、是否可售、可用券数）。
2. 无库存/下架项（`hasStock=false` / `onSale=false`）要禁用勾选并提示。
3. 登录成功要合并游客购物车（`localStorage.guestCart`）并清除。
4. 加购数量受 99 上限约束；下单走 `/order/create`（见 order 模块）。
5. 勾选/数量/删除变更后需重拉聚合刷新合计。
