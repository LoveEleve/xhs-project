# H02: 笔记聚合 — GET /api/home/note/{noteId}

## § 源码分析
- **Controller**: `HomeController.java:69` → `@GetMapping("/note/{noteId}")`, X-User-Id 可选(登录返回互动状态, 未登录仅公开信息)
- **Service**: `NoteAggService.getNoteDetail()` → 5路Feign并行:
  - content: getNoteDetail + pageComments(评论分页)
  - user: getUserPublicInfo(作者头像/昵称)
  - analytics: checkLikeStatus + checkFavStatus + checkFollowRelation
  - counter: batchGetCounts(点赞数/收藏数/评论数/分享数)
  - notification: (仅登录用户) 未读通知数
- **聚合**: CompletableFuture.allOf 5路并行, 30s超时, failback返回部分数据

## § 业务逻辑
GET noteId → 并行Feign取5路数据(笔记内容+作者信息+互动状态+计数+通知) → 结果聚合成NoteDetailVO → 返回

## § 前置检查
| 条件 | 验证 | 不满足后果 |
|------|------|------|
| noteId有效 | MySQL content.t_note存在该记录 | 404, {message:"笔记不存在"} |
| home服务启动 | Nacos注册 + Feign调用可达 | 503 |

## § 数据验证 L2
| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl "http://localhost:19000/api/home/note/2085989641275572226"` | 200, noteDetailVO含content/author/counts |
| content | MySQL `SELECT * FROM t_note WHERE id=?` | 各字段返回 |
| counter | Redis `HGETALL myxhs:counter:1:2085989641275572226` | 各计数key存在 |

## § 生产级检查 L3
| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | 5路并行Feign(30s total, 8s/call) | ✅ |
| 弹性 | CompletableFuture.allOf + failback | ✅ |
| N+1 | 聚合层1次请求查询5个服务 | ✅ |

## § curl
```bash
NOTE_ID=2085989641275572226
curl -s "http://localhost:19000/api/home/note/${NOTE_ID}" | python3 -m json.tool
```

## § ASCII流转图
```
GET /home/note/{noteId}
  → HomeController:19015 → NoteAggService.getNoteDetail()
    → CompletableFuture.allOf(5路并行):
      ├─ content: /content/note/{noteId} → NotePO
      ├─ content: /content/comment/page/{noteId} → Page<CommentVO>
      ├─ user: /user/{authorId} → UserPublicVO
      ├─ analytics: batchStatus(like/fav/follow) → Map
      ├─ counter: /counter/batch-get → counts
      └─ notification: (optional) → unreadCount
    → 聚合{noteDetail, author, counts, interactions, comments}
    → 返回 NoteDetailVO
```
