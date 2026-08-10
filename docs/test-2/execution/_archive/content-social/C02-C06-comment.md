# C02/C03/C05/C06 — 评论 CRUD

## C03: GET /api/comment/list/{noteId} — 评论列表

### ASCII 流转图
```
[curl] → Gateway:19000 → content:19002
  → CommentController.getCommentList(/{noteId}, ?lastId&pageSize=10)
  → CommentService.getCommentList()
     ├ MySQL:13307 my_xhs_content.t_comment SELECT (游标分页, deleted=0)
     └ 每条评论预加载前3条子评论(楼中楼)
```

### curl
```bash
curl -s "http://localhost:19000/api/comment/list/2085540601761169409" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 10001"
```

| 层 | 实际 | 状态 |
|------|------|:--:|
| HTTP | 200, 1 条评论 (C01测试评论) | ✅ |
| MySQL | t_comment count=1, deleted=0 | ✅ |
| Prometheus | content actuator 指标 | ✅ |
| SkyWalking | Gateway X-Trace-Id | ✅ |

---

## C05: GET /api/comment/count/{noteId} — 评论数

### curl
```bash
curl -s "http://localhost:19000/api/comment/count/2085540601761169409" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 10001"
```

| 层 | 实际 | 状态 |
|------|------|:--:|
| HTTP | 200, count=1 | ✅ |

### 踩坑
- 路径是 `/count/{noteId}` 不是 `?noteId=`

---

## C06: GET /api/comment/page/{noteId} — 评论分页

### curl
```bash
curl -s "http://localhost:19000/api/comment/page/2085540601761169409?pageNum=1&pageSize=3" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 10001"
```

| 层 | 实际 | 状态 |
|------|------|:--:|
| HTTP | 200, pages=1 | ✅ |

### 踩坑
- 路径是 `/page/{noteId}` 不是 `?noteId=`

---

## C02: DELETE /api/comment/{id} — 删除评论

### ASCII 流转图
```
[curl] → Gateway:19000 → content:19002
  → CommentController.deleteComment(X-User-Id=10001, /{commentId})
  → CommentService.deleteComment()
     ├ 校验: 评论作者或笔记作者
     └ MySQL UPDATE t_comment SET deleted=1 (逻辑删除)
```

### curl
```bash
curl -s -X DELETE "http://localhost:19000/api/comment/2085543359650267138" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 10001"
```

| 层 | 实际 | 状态 |
|------|------|:--:|
| HTTP | 200 | ✅ |
| MySQL | deleted=1 (逻辑删除) | ✅ |
