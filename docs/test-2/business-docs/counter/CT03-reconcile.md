# CT03: 对账 — POST /api/counter/reconcile

## § 源码分析
- **Controller**: `CounterController.java:66` → `@PostMapping("/reconcile")`, X-Admin-Call
- **Service**: `CounterService.java:512` → `reconcile()`
  - 游标扫描 MySQL `t_counter` 全表
  - 逐条对比 Redis `myxhs:counter:{targetType}:{targetId}:{countType}`
  - like特殊修正: `reconcileLikeFromAnalytics()` → SCARD `myxhs:like:set:{type}:{id}` 精确修正
  - 不一致 → 以MySQL为准修复Redis
- **下游**: MySQL t_counter + Redis counter + Redis like Set

## § 业务逻辑
管理员触发 → 游标分页扫描t_counter → 逐条Redis GET对比 → SCARD like Set精确修正 → 不一致MySQL盖写Redis

## § 前置检查
| 条件 | 验证 | 不满足后果 |
|------|------|------|
| X-Admin-Call | Header admin-token | 403 |

## § 数据验证 L2
| 层 | 验证命令 | 预期 |
|------|------|------|
| MySQL | `SELECT COUNT(*) FROM t_counter` | 总记录数 |
| Redis | 对比各counter key | 一致 |

## § 生产级检查 L3
| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 一致性 | like用SCARD Set精确 | ✅ |
| 性能 | 游标分页避免全量锁 | ✅ |

## § curl
```bash
curl -s -X POST http://localhost:19000/api/counter/reconcile \
  -H "X-Admin-Call: my-xhs-admin-token-2026"
```

## § ASCII流转图
```
Admin → counter:19004 → CounterService.reconcile()
  → 游标: SELECT * FROM t_counter LIMIT batch
  → Redis GET myxhs:counter:{type}:{id}:{type}
  → 不一致 → like: SCARD修正 / 其他: MySQL覆盖Redis
  → 继续游标下一批
```
