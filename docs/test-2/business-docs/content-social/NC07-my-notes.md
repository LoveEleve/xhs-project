# NC07: 我的笔记 — GET /api/note/my

## § 源码分析

- **Controller**: `NoteController.java:110` → `@GetMapping("/my")`, 参数 `X-User-Id` + optional `status` + `pageNum/1 pageSize/10`
- **Service**: `NoteService.getMyNotes(userId, status, pageNum, pageSize)`
  - `SELECT * FROM t_note WHERE user_id=? AND deleted=0` + (status!=null `AND status=?`)
  - ORDER BY update_time DESC → 草稿也在列表中
  - 返回 `PageResult<NoteItemVO>`
- **下游**: MySQL t_note

## § 业务逻辑

需登录 → 查用户全部笔记(含草稿status=0) → 可选status过滤 → 按更新时间倒序

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `X-User-Id` | 401 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl /api/note/my?status=0` | 200, 草稿列表 |
| MySQL | `SELECT COUNT(*) FROM t_note WHERE user_id=? AND status=0` | = records.length |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | user_id过滤 | ✅ |

## § curl

```bash
curl -s "http://localhost:19000/api/note/my?pageNum=1&pageSize=10" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool | head -10
```

## § ASCII流转图

```
GET /api/note/my?status=0&pageNum=1
  → NoteController.getMyNotes(X-User-Id, status, page, size)
  → SELECT * FROM t_note WHERE user_id=? AND status=? ORDER BY update_time DESC
  → PageResult<NoteItemVO> (含草稿)
```
