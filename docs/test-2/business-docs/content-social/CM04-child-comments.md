# CM04: 子评论(楼中楼) — GET /api/comment/children/{parentId}

## § 源码分析

- **Controller**: `CommentController.java:92` → `@GetMapping("/children/{parentId}")`, 参数 `@PathVariable parentId`
- **Service**: `CommentService.getChildComments(parentId)`
  - `SELECT * FROM t_comment WHERE parent_id=? AND is_deleted=0 ORDER BY created_at ASC LIMIT 10`
  - 二级评论不嵌套(无孙评论) → 只查parent_id的子评论
  - 上限10条/楼中楼
- **下游**: MySQL my_xhs.t_comment

## § 业务逻辑

点击一级评论→展开楼中楼→最多显示10条子评论→按时间正序→不嵌套(只有两层)

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| parentId有效 | 一级评论存在 | 返回空列表 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl /api/comment/children/456` | 200, {children:[...]} |
| MySQL | `SELECT COUNT(*) FROM t_comment WHERE parent_id=? AND is_deleted=0` | = children数组长度 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | parent_id索引+LIMIT 10 | ✅ |
| 设计 | 二级不嵌套防爆炸 | ✅ |

## § curl

```bash
curl -s "http://localhost:19012/api/comment/children/456"
```

## § ASCII流转图

```
GET /api/comment/children/{parentId}
  → CommentController.getChildren(parentId)
  → SELECT * FROM t_comment
    WHERE parent_id=? AND is_deleted=0
    ORDER BY created_at ASC LIMIT 10
  → 返回 {children: [{id, userId, content, createdAt}, ...]}
```
