# P08: 批量查询SKU — GET /api/product/sku/batch?skuIds=

## § 源码分析

- **Controller**: `ProductController.java:167` → `@GetMapping("/sku/batch")`, 参数 `@RequestParam List<Long> skuIds` + `@RequestHeader(value="X-Internal-Call",required=false)`
- **Service**: `SkuService.java:94` → `batchGetSkuDetails()`
  - skuIds为空 → `return List.of()` (空列表)
  - 构建 `WHERE id IN(...) AND status=1` 查询(只查上架SKU)
  - 一次查询替代N次循环单查
  - `stream().map(toSkuVO).collect(Collectors.toList())`
- **安全**: Controller层 `X-Internal-Call` header校验 → `isInternalCall(internalCall)` → 非法返回403 "仅限内部服务调用"
- **防滥用**: Controller层 `skuIds.size() > 100` → 返回 `PARAM_INVALID`

## § 业务逻辑

订单/购物车等服务通过X-Internal-Call内部token调用批量查SKU → Controller校验X-Internal-Call header → skuIds 1-100范围校验 → SkuService构建 `WHERE id IN(...) AND status=1` → MySQL一次查询 → 下架SKU不返回(status≠1自动过滤) → 返回SkuVO列表

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| Internal Token | `X-Internal-Call: {internal.token配置值}` | 403 仅限内部服务调用 |
| skuIds范围 | 1-100个ID | PARAM_INVALID |
| SKU至少1条数据 | `mysql -P 3306 -e "SELECT id FROM my_xhs_product.t_sku WHERE status=1 LIMIT 1"` | 空列表(非错误) |
| my_xhs_product DB可连 | `mysql -P 3306 -e "SELECT 1 FROM my_xhs_product.t_sku LIMIT 1"` | 500 |

## § ASCII流转图

```
curl GET /api/product/sku/batch?skuIds=1,2,3 (X-Internal-Call)
  → Gateway → my-xhs-product:19006 ProductController.batchGetSkuDetails()
    → X-Internal-Call校验 → 非内部调用? → 403
    → skuIds=null/empty/size>100? → PARAM_INVALID
    → MySQL: SELECT * FROM t_sku WHERE id IN(1,2,3) AND status=1
    → stream map toSkuVO (stock字段从VO剔除)
    → 返回 List<SkuVO>
```

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP(内部) | `curl -s "http://localhost:19000/api/product/sku/batch?skuIds=1,2,3" -H "X-Internal-Call: my-xhs-internal-token-2026"` | 200, `data[].id` 数组 |
| HTTP(无token) | `curl -s "http://localhost:19000/api/product/sku/batch?skuIds=1"` | 403 |
| HTTP(超出100) | `curl -s "http://localhost:19000/api/product/sku/batch?skuIds=$(for i in $(seq 1 101); do echo -n $i; [ $i -lt 101 ] && echo -n ','; done)" -H "X-Internal-Call: ..."` | PARAM_INVALID |
| MySQL | `mysql -P 3306 -e "SELECT id,name FROM my_xhs_product.t_sku WHERE id IN(1,2,3) AND status=1"` | 1+N行 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | WHERE id IN(...) 更少N+1 | ✅ |
| 安全 | X-Internal-Call内部令牌 | ✅ |
| 稳定性 | 限制100条防打挂DB | ✅ |
| 微服务 | 直查MySQL无跨服务调用 | ✅ |
| 冗余 | 下架SKU自动过滤(status=1) | ✅ |

## § curl

```bash
# 内部调用批量查SKU
curl -s "http://localhost:19000/api/product/sku/batch?skuIds=1,2,3" \
  -H "X-Internal-Call: my-xhs-internal-token-2026" | python3 -m json.tool
# 预期: { "code": 200, "data": [ { "id": 1, "spuId": 1, "name": "...", "price": 29900 }, ... ] }

# 无token验证——应返回403
curl -s -i "http://localhost:19000/api/product/sku/batch?skuIds=1" | head -1
# 预期: HTTP/1.1 403
```
