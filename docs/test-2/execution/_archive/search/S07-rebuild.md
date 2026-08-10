# S07 — 重建搜索索引

`POST /api/search/index/rebuild` | X-Admin-Call required

## ASCII 流转图

```
[curl] → Gateway:19000 → search:19016
  → SearchController.rebuildIndex(X-User-Id, X-Admin-Call)
  → IndexRebuildJob.manualRebuild() → CompletableFuture.runAsync
     ├ doRebuild(): "SELECT 1 FROM t_note" 校验数据源
     ├ note_index 重建: t_note → ES BulkRequest → note_index
     ├ suggest_index 重建: t_note.title → ES BulkRequest → suggest_index (Completion Suggester)
     └ product_index 重建: t_spu → ES BulkRequest → product_index
```

## 业务逻辑

admin 手动触发搜索索引全量重建（异步）。从 MySQL 读取已发布的笔记和商品，批量写入对应 ES 索引。每步独立 try-catch 保证单步失败不阻断其他索引。

## curl

```bash
curl -s -X POST "http://localhost:19000/api/search/index/rebuild" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 10001" \
  -H "X-Admin-Call: my-xhs-admin-token-2026"
# → {"code":200,"message":"操作成功"}（异步执行，立即返回）
```

## 七层验证

| 层 | 实际 | 状态 |
|------|------|:--:|
| HTTP | 200, "操作成功" | ✅ |
| ES note_index | 34 docs | ✅ |
| ES suggest_index | 38 docs（修复后） | ✅ |
| ES product_index | t_spu 不在 my_xhs_content 库→跳过（不影响 note/suggest） | ⚠️ |
| 日志 | "建议关键词已索引38条" | ✅ |
| Prometheus | search actuator 指标 | ✅ |
| SkyWalking | Gateway X-Trace-Id | ✅ |

## 踩坑
- suggest_index 修复详见 `pitfalls.md` §S03修复链（6 层根因，2.5h）
