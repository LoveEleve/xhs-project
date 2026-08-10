# H03: GET /api/home/product/{spuId}
## § 源码分析
- Controller: HomeController.java:91, 公开
- Service: `ProductAggService.getProductDetail()` — 4路Feign: product(spuDetail+skus), inventory(stock), counter(batchGetCounts targetType=4)
## § 业务逻辑
并行Feign取商品详情+SKU列表+库存+计数→聚合返回
## § curl
```bash
curl -s "http://localhost:19000/api/home/product/1"
```
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
