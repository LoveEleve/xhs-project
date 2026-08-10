# H05: GET /api/home/cart
## § 源码分析
- Controller: HomeController.java:133, X-User-Id
- Service: `CartAggService.getCartAgg()` — 3路Feign: cart(cartList+count), coupon(availableCoupons), inventory(stock)
## § 业务逻辑
并行取购物车列表+券列表+库存→聚合返回
EOF
## § 数据验证 L2
| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | curl -s | 200 |

## § 生产级检查 L3
| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | 并行Feign | ✅ |

## § 前置检查
| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 服务运行 | Nacos | 503 |

## § ASCII流转图
```
curl → Gateway → home:19015 → Feign×N → 聚合返回
```
## § curl  
```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s http://localhost:19000/api/home/cart -H "Authorization: Bearer $TOKEN"
```
