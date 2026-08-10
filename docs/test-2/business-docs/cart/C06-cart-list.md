# C06: 购物车列表 — GET /api/cart/list

## § 源码分析

- **Controller**: `CartController.java:96` → `@GetMapping("/list")`, 参数 `X-User-Id`
- **Service**: `CartService.java:290` → `getCartList()`
  - Pipeline 批量执行: HGETALL items + SMEMBERS checked + ZREVRANGE sort (1次RTT)
  - Feign批量取SKU: `ProductFeignClient.batchGetSkuDetails(skuIds)` → GET /api/product/sku/batch
  - 下架/获取失败标记 `valid=false`
  - 计算 `checkedCount/checkedAmount` (只看valid=true)
  - 按 `addedAt` 倒序排列
- **下游**: Redis三结构 + Feign Product + MySQL(Feign间接)

## § 业务逻辑

Pipeline一次RTT取三结构 → 批量查商品信息 → 下架/失败标记valid=false → 计算选中数量/金额 → 按加入时间倒序返回 → allChecked只看有效商品

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `cat /tmp/test_token.txt` | 401 |
| Product服务在线 | `curl Nacos .../my-xhs-product` | 商品信息获取失败(valid=false) |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl /api/cart/list` | 200, items数组, checkedCount, checkedAmount |
| Redis | `r.hlen('myxhs:cart:{userId}:items')` | = items.length |
| MySQL | `SELECT COUNT(*) FROM t_cart_item WHERE user_id=?` | >=0 (异步落库) |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | Pipeline 1次RTT | ✅ |
| 微服务 | Feign降级 valid=false | ✅ |
| 可扩展 | Hash tag同slot | ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s http://localhost:19000/api/cart/list \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool | head -30
```

## § ASCII流转图

```
curl GET /api/cart/list
  → Gateway → CartController.getCartList(X-User-Id)
    → Pipeline: HGETALL items + SMEMBERS checked + ZREVRANGE sort
    → Feign ProductFeignClient.batchGetSkuDetails(skuIds)
      → 失败: valid=false (商品信息获取失败)
      → 下架(status!=1): valid=false
    → 计算 checkedCount/checkedAmount (只看valid=true)
    → 返回 CartListVO{items, checkedCount, checkedAmount}
```
