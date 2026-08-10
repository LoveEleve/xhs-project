# my-xhs-cart 购物车服务

> 10个端点 | CartController + CartService | Redis三结构 + MQ异步 + Feign

---

## 架构概览

```
curl → Gateway → cart(19008) → Redis(items/checked/sort 三结构)
                              → Feign ProductFeignClient(GET /api/product/sku/batch)
                              → MQ(CART_TOPIC → CartSyncConsumer → MySQL uk_user_sku UPSERT)
```

## 端点清单

| ID | 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|:--:|
| C01 | POST | `/api/cart/add` | 加入购物车 | JWT+X-User-Id |
| C02 | PUT | `/api/cart/quantity` | 修改数量 | JWT+X-User-Id |
| C03 | DELETE | `/api/cart/{skuId}` | 删除商品 | JWT+X-User-Id |
| C04 | PUT | `/api/cart/check` | 勾选/取消勾选 | JWT+X-User-Id |
| C05 | PUT | `/api/cart/check-all` | 全选/取消全选 | JWT+X-User-Id |
| C06 | GET | `/api/cart/list` | 购物车列表(含商品详情) | JWT+X-User-Id |
| C07 | POST | `/api/cart/merge` | 匿名购物车合并 | JWT+X-User-Id |
| C08 | DELETE | `/api/cart/clear` | 清空购物车 | JWT+X-User-Id |
| C09 | GET | `/api/cart/count` | 商品种类数量(角标) | JWT+X-User-Id |
| C10 | POST | `/api/cart/internal/reconcile` | 手动对账(管理接口) | X-Admin-Call |

## Key Redis

| Key | 类型 | TTL | 用途 |
|------|------|:--:|------|
| `myxhs:cart:{userId}:items` | Hash | 30天 | skuId→quantity |
| `myxhs:cart:{userId}:checked` | Set | 30天 | 已选中的skuId集合 |
| `myxhs:cart:{userId}:sort` | ZSet | 30天 | skuId→添加时间戳(排序) |

> 花括号 `{userId}` 是 hash tag，保证三结构在同一 Redis slot，Lua脚本可原子操作。

## Key MySQL

| 表 | 库 | 说明 |
|------|------|------|
| t_cart_item | my_xhs_cart | uk_user_sku(user_id,sku_id)唯一索引，MQ幂等UPSERT |
