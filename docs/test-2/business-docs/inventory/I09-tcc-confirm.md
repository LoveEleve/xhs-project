# I09: TCC Confirm — POST /api/inventory/tcc/confirm

## § 源码分析

- **Controller**: `InventoryController.java:177` → `@PostMapping("/tcc/confirm")`, X-Internal-Call
- **Service**: `InventoryTccService.java:67` → `confirmDeductStock()`
  - `tccFenceService.confirmFence()`: UPDATE t_tcc_fence SET status=2 WHERE xid=? AND branch_id=? AND status=1
  - `confirmFreeze()`: UPDATE t_inventory SET freezing_stock -= qty
  - UPDATE t_tcc_freeze_detail SET status=2 WHERE status=1
- **下游**: MySQL t_tcc_fence + t_inventory + t_tcc_freeze_detail

## § 业务逻辑

order确认→Feign I09→Fence状态1→2→确认扣减(freezing_stock -= qty)→更新明细status 1→2

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| Try已执行 | `t_tcc_fence status=1` | 悲观锁UPDATE影响0行 |

## § curl

```bash
curl -s -X POST http://localhost:19000/api/inventory/tcc/confirm \
  -H "X-Internal-Call: ${INTERNAL_TOKEN}" \
  -d '{"xid":"tx-001","branchId":1}'
```

## § ASCII流转图

```
Feign order → inventory:19005 I09 TCC Confirm
  → InventoryTccService.confirmDeductStock()
    → Fence: UPDATE t_tcc_fence SET status=2 WHERE xid,branchId,status=1
    → MySQL: UPDATE t_inventory SET freezing_stock -= qty
    → MySQL: UPDATE t_tcc_freeze_detail SET status=2
```

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| MySQL | `SELECT status FROM t_tcc_fence WHERE xid=?` | 2 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 一致性 | 乐观锁防重复confirm | ✅ |
| 安全 | X-Internal-Call校验 | ✅ |
