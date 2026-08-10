# H05 — 购物车聚合 (GET /api/home/cart)

> 2026-08-08 | 阶段14-12 | home服务 | mytestuser(2085927845755985922)

## ASCII 流转图

```
curl → Gateway:19000 (JWT→HMAC→路由)
       → my-xhs-home:19015 (GET /api/home/cart)
         → CompletableFuture.supplyAsync (aggregatorPool)
           → cartAggService.getCartAgg(userId)
             ├── Feign: cart服务 → 购物车列表(items)
             ├── Feign: inventory服务 → 各SKU库存
             ├── Feign: coupon服务 → 可用优惠券
             └── 组装 CartAggVO(totalCount/checkedAmount/coupons)
```

## 业务逻辑

聚合购物车页面：购物车商品 + 选中状态/金额 + 可用优惠券列表。需要登录(X-User-Id header)。

## 七层验证

| 层 | 状态 | 验证内容 |
|------|:--:|------|
| HTTP | ✅ | 200 OK |
| 空购物车 | ✅ | items=[], totalCount=0, checkedAmount=0 (新用户预期) |
| 优惠券 | ✅ | availableCoupons=[] |

## 执行命令

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -i "http://localhost:19000/api/home/cart" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 2085927845755985922"
```

## 响应

```json
{"code":200,"data":{
  "items":[],"checkedCount":0,"checkedAmount":0,
  "totalCount":0,"allChecked":false,
  "availableCouponCount":0,"availableCoupons":[]
}}
```
