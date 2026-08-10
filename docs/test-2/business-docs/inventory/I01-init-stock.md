# I01: 初始化库存 — POST /api/inventory/init

## § 源码分析
- **Controller**: `InventoryController.java:57` → `@PostMapping("/init")`, X-Admin-Call
- **Service**: `InventoryService.java:141` → `initStock()`
  - 根据stock数量动态确定桶数(>10000→10桶,>1000→5桶,≤1000→1桶)
  - Redis分桶写入: `inventory:{skuId}:bucket:{0..N-1}` + `inventory:{skuId}:total`
  - MySQL: INSERT/UPDATE t_inventory(available_stock)
- **下游**: Redis桶分片 + MySQL t_inventory

## § 业务逻辑
管理员提交(skuId, stock)→根据数量计算桶数→Redis写入桶分片+total→MySQL记录基础库存

## § 前置检查
| 条件 | 验证 | 不满足后果 |
|------|------|------|
| X-Admin-Call | Header admin-token | 403 |
| skuId有效 | product已创建SKU | 无需 |

## § 数据验证 L2
| 层 | 验证命令 | 预期 |
|------|------|------|
| Redis | `r.get('inventory:{skuId}:total')` | =stock |
| Redis | `r.get('inventory:{skuId}:bucket:0')` | 分桶值 |
| MySQL | `SELECT available_stock FROM t_inventory WHERE sku_id=?` | =stock |

## § 生产级检查 L3
| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 可扩展 | 动态桶数防热点 | ✅ |

## § curl
```bash
ADMIN_TOKEN="my-xhs-admin-token-2026"
curl -s -X POST http://localhost:19000/api/inventory/init \
  -H "X-Admin-Call: $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"skuId":123,"stock":10000}'
```

## § ASCII流转图
```
Admin → inventory:19005 I01 initStock
  → 计算桶数: stock>10000→10桶
  → Redis: SET inventory:{skuId}:total = stock
  → Redis: SET inventory:{skuId}:bucket:{0..9} 分片写入
  → MySQL: INSERT/UPDATE t_inventory
```
