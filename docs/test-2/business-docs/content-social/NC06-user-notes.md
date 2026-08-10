# NC06: 用户笔记列表 — GET /api/note/user/{userId}

## § 源码分析

- **Controller**: `NoteController.java:99` → `@GetMapping("/user/{userId}")`, 参数 `@PathVariable Long userId` + `pageNum/1 pageSize/10`，公开接口
- **Service**: `NoteService.getUserNotes(userId, pageNum, pageSize)`
  - `SELECT * FROM t_note WHERE user_id=? AND status=1 AND deleted=0 ORDER BY create_time DESC LIMIT ?,?`
  - 返回 `PageResult<NoteItemVO>` (MyBatis-Plus Page)
- **下游**: MySQL t_note

## § 业务逻辑

公开接口查看任何用户的已发布笔记 → 分页倒序 → 返回 NoteItemVO列表

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 无 | 公开接口 | 未登录也可访问 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl /api/note/user/{userId}?pageNum=1&pageSize=10` | 200, {records:[], total, page} |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | 索引 user_id+status+create_time | ✅ |

## § curl

```bash
curl -s "http://localhost:19000/api/note/user/$USER_ID?pageNum=1&pageSize=5" | python3 -m json.tool
```

## § ASCII流转图

```
GET /api/note/user/{userId}?pageNum=1&pageSize=10
  → NoteController.getUserNotes(userId) → 公开
  → SELECT * FROM t_note WHERE user_id=? AND status=1 AND deleted=0 ORDER BY create_time DESC LIMIT ?,?
  → PageResult<NoteItemVO>
```
