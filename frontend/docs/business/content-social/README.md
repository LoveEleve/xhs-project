# 内容 & 社交模块（my-xhs-content + my-xhs-analytics）

> 来源：`my-xhs-content`（笔记/评论）、`my-xhs-analytics`（点赞/收藏/关注）源码直读。

## 一、业务边界
- **content**：笔记发布/草稿/编辑/删除/详情/列表、图片上传、评论发表/删除/列表。网关前缀 `/api/note/**`、`/api/comment/**`。
- **analytics（社交）**：点赞/取消、收藏/取消、关注/取关、关系查询。网关前缀 `/api/social/**`。
- **counter**（另一服务）：点赞/收藏/评论等计数，通过 detail 聚合接口返回，前端不直接调。

## 二、笔记模块

### 发布
```
POST /api/note/publish   (需登录)
body: { title(必填≤128), content(≤20000), images(≤9), videoUrl?, coverUrl?,
        topicIds?, tags?, noteType(0图文/1视频) }
→ 200 { "data": { "noteId": 123 } }
```
- 限流：1 分钟最多 5 篇。
- 发布前自动 DFA 敏感词检测。

### 草稿
```
POST /api/note/draft    (需登录)
```
- 草稿允许不完整（标题可为空），**不触发敏感词检测、不推送 Feed**。

### 编辑
```
PUT /api/note/{id}      (需登录)
```
- 仅草稿/已发布可编辑；已发布再编辑会**重新做敏感词检测**。

### 删除
```
DELETE /api/note/{id}   (需登录)
```
- 逻辑删除，并**级联删除该笔记所有评论**。

### 详情（公开）
```
GET /api/note/detail/{id}   → NoteDetailVO
```
```
id, userId, title, content, images[], videoUrl, coverUrl, topicIds[], tags[],
status, statusDesc, noteType, createdAt, updatedAt
```

### 列表
```
GET /api/note/user/{userId}?pageNum&pageSize   (公开，仅已发布) → PageResult<NoteItemVO>
GET /api/note/my?status&pageNum&pageSize       (需登录，含草稿) → PageResult<NoteItemVO>
```
```
NoteItemVO: id, userId, title, coverUrl, firstImage, noteType, status, createdAt
```
> ⚠️ **参数名**：这里同样是 `pageNum/pageSize`，不是 `page/size`。前端 `api/note.ts` 的 `getUserNotes/getMyNotes` 目前用 `page/size`，需修正。

### 草稿发布
```
POST /api/note/{id}/publish   (需登录)
```
- 仅草稿可发布，其他状态报错。

### 图片上传
```
POST /api/note/upload/image  (需登录, multipart form, field=file)
→ { "data": { "url": "..." } }
```
- 支持 JPEG/PNG/GIF/WebP，最大 5MB，限流 1 分钟 20 次。
- 前端先上传拿 url，再把 url 塞进 `images` 发布。

### 分享
```
POST /api/note/{id}/share  (需登录) → 递增分享计数
```

### 关键枚举
- **noteType**：`0=图文, 1=视频`（前端 NoteCard 中 `noteType===1` 显示视频图标 ✓）。
- **status**：`0=草稿, 1=审核中, 2=已发布, 3=已下架`。
- 状态机：草稿→(审核中/已发布)→…→已下架（只允许合法迁移）。

## 三、评论模块

### 发表
```
POST /api/comment   (需登录)
body: { noteId(必填), content(必填≤500), parentId?, replyToId? }
→ { "data": { "commentId": 123 } }
```
- 1 分钟最多 10 条；DFA 敏感词检测。

### 删除
```
DELETE /api/comment/{id}   (需登录)
```
- **评论作者或笔记作者**可删；删一级评论会级联删子评论。

### 列表
```
GET /api/comment/list/{noteId}?lastId&pageSize   (游标分页, 公开) → List<CommentVO>
GET /api/comment/page/{noteId}?pageNum&pageSize  (传统分页, 公开) → PageResult<CommentVO>
GET /api/comment/children/{parentId}?lastId&pageSize  (楼中楼) → List<CommentVO>
GET /api/comment/count/{noteId} → { "count": N }
```
```
CommentVO: id, noteId, userId, parentId, replyToId, content, likeCount,
           createdAt, children[], childCount
```
- 一级评论列表接口已预加载前 3 条子评论（`children` 非空）。
- 前端 `CommentList` 用的是 `/comment/page/{noteId}`（传统分页）+ `/comment/children/{parentId}`，均存在 ✓。

## 四、点赞 / 收藏 / 关注（社交）

### 业务类型 bizType
- **`1=笔记, 2=评论`**（点赞/收藏共用此约定，收藏实际只作用于笔记）。

### 点赞
```
POST   /api/social/like      body: { bizType, bizId }   (需登录)
DELETE /api/social/like      body: { bizType, bizId }   (需登录)
GET    /api/social/like/status?bizType&bizId  → Boolean
GET    /api/social/like/batch-status?bizType&bizIds=1,2,3  → Map<Long,Boolean>
GET    /api/social/like/count?bizType&bizId  → Long
```
- 点赞幂等（SADD）；1 分钟最多 30 次。

### 收藏
```
POST   /api/social/favorite  body: { noteId }   (需登录)   ← 注意只有 noteId
DELETE /api/social/favorite  body: { noteId }   (需登录)
GET    /api/social/favorite/status?noteId  → Boolean
GET    /api/social/favorite/list?page&size → { total, list: [noteId...] }   ← 返回笔记ID数组
```

> ⚠️ **前端 API 层缺陷（必须修）**：`api/social.ts` 的 `favorite/unfavorite` 发送 `{bizId, bizType}`，
> 后端只接受 `{noteId}`。请改为 `{noteId}`。
> 另外 `getFavoriteList` 返回的是 `{total, list: number[]}`（笔记ID），前端 `FavoritesPage`
> 需用这些 ID 逐个/批量拉笔记详情（或用 home 聚合）才能展示，不能直接当卡片数据用。

### 关注
```
POST   /api/social/follow/{targetUserId}    (需登录)
DELETE /api/social/follow/{targetUserId}    (需登录)
GET    /api/social/following/{userId}?page&size  (公开) → { total, list: FollowVO[] }
GET    /api/social/follower/{userId}?page&size   (公开) → { total, list: FollowVO[] }
GET    /api/social/common/{targetUserId}         (需登录) → List<Long>
GET    /api/social/relation/{targetUserId}       (需登录) → { isFollowing, isFollowBack, isMutual }
```
```
FollowVO: userId, nickname, avatar, followedAt, isFollowBack
```
- 关注/取关各限流 1 分钟 20 次。
- 不能关注自己（前端可前置判断）。

> ⚠️ **前端 API 层缺陷**：`getFollowing/getFollowers` 的返回类型是 `unknown`，实际为 `{total,list}`；
> `getRelation` 返回 `{isFollowing,isFollowBack,isMutual}`。写关注/粉丝/用户主页页面前应补类型。

## 五、前端接入注意汇总
1. `noteType`: 0 图文 / 1 视频。
2. 笔记 status：0 草稿 / 2 已发布（列表接口已过滤，草稿只在 `/note/my` 出现）。
3. 收藏接口体是 `{noteId}`，不是 `{bizId,bizType}`。
4. 收藏列表 / 关注列表 / 粉丝列表返回的是「ID 或 FollowVO」+ total，不是标准分页对象，需自行处理。
5. 笔记列表分页参数用 `pageNum/pageSize`。
6. 评论发表/删除后需刷新计数（重新拉 detail 或计数接口）。
