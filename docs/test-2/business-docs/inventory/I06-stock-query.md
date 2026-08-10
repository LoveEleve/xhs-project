# I06: 查询库存 — GET /api/inventory/stock/{skuId}

## § 源码分析
- **Controller**: `InventoryController.java:124` → `@GetMapping("/stock/{skuId}")`, 公开接口
- **Service**: `InventoryService.java:461` → `getStock()`
  - CacheAside: Redis GET inventory:{skuId}:total
  - 命中→返回; 未命中→MySQL SELECT available_stock FROM t_inventory → 回写Redis → 返回
- **下游**: Redis total + MySQL t_inventory

## § 业务逻辑
公开查询库存 → Redis读total → 命中直接返回 → 未命中查MySQL回填Redis

## § curl

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 服务运行 | `curl Nacos .../my-xhs-inventory` | Gateway 503 |
```bash
curl -s http://localhost:19000/api/inventory/stock/123 | python3 -m json.tool
```

## § ASCII流转图
```
GET /api/inventory/stock/{skuId}
  → CacheAside: Redis GET inventory:{skuId}:total
    → 命中 → 直接返回
    → 未命中 → MySQL SELECT available_stock → SET Redis → 返回
```

## § 数据验证 L2
| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | curl -s | 200 |

## § 生产级检查 L3
| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 可靠性 | Outbox/Retry兜底 | ✅ |
