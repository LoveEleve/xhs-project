# 首页聚合模块（my-xhs-home，BFF 层）

> 来源：`my-xhs-home` 源码直读。

## 一、业务边界
home 是 **BFF（Backend For Frontend）聚合层**，把多个微服务数据并行聚合，返回前端「一站式」VO。
**很多前端页面的数据其实来自这里，而不是各业务微服务。**

## 二、接口

### 关注 Feed 流（游标分页）
```
GET /api/home/feed?lastScore&size=20   (需登录) → FeedVO
```
```
FeedVO: notes[]: NoteCardVO, nextCursor(String), hasMore, unreadCount
NoteCardVO: noteId, title, coverUrl, noteType, authorId, authorNickname,
            authorAvatar, likeCount, collectCount, commentCount,
            isLiked, isCollected, isFollowed, createdAt, score
```
- 首次请求不传 `lastScore`（或 0）。
- **翻页用上一页返回的 `nextCursor`**（字符串，是 score 的格式化值）作为下一次的 `lastScore`（需转 Double）。
- `isLiked/isCollected/isFollowed` 已随卡片返回，无需另查。
> ⚠️ 前端 `FeedPage` 目前用「最后一篇的 `score`」作游标；后端其实返回了 `nextCursor`，
> 建议改用 `data.nextCursor`（解析成 Double）更稳妥。

### 笔记详情聚合
```
GET /api/home/note/{noteId}? (可选需登录) → NoteDetailAggVO
```
```
noteId, title, content, images[], videoUrl, coverUrl, noteType, tags[],
createdAt, authorId, authorNickname, authorAvatar,
likeCount, collectCount, commentCount, isLiked, isCollected, isFollowed,
hotComments[]
```
- **前端笔记详情页应优先用此接口**（笔记+作者+计数+社交状态+热门评论一次拿全）。
- 未登录时社交状态全为 false。

### 商品详情聚合
```
GET /api/home/product/{spuId}   (公开) → ProductDetailAggVO
```
```
spuId, name, description, images[], categoryId, categoryName, status,
skuList[]: [{ skuId, skuName, price, image, specValues, availableStock, hasStock }],
collectCount, viewCount, relatedNotes[]: NoteCardVO[]
```
（详见 product 模块。）

### 用户主页聚合
```
GET /api/home/user/{targetUserId}? (可选需登录) → UserProfileAggVO
```
```
userId, nickname, avatar, bio, followingCount, followerCount,
likeAndCollectCount, noteCount, isFollowing, isFollowBack, isMutual, notes[]: NoteCardVO
```
- **前端用户主页页应优先用此接口**（含关注关系 + 用户笔记列表）。

### 购物车聚合
```
GET /api/home/cart   (需登录) → CartAggVO
```
```
items[]: [{ skuId, spuId, skuName, skuImage, price, quantity, checked,
            totalAmount, availableStock, hasStock, onSale }],
checkedCount, checkedAmount, totalCount, allChecked,
availableCouponCount, availableCoupons[]
```
（详见 cart 模块。）

## 三、前端接入注意汇总
1. **优先用 home 聚合**：笔记详情、商品详情、用户主页、购物车、Feed 都从这里取，数据最全。
2. Feed 翻页用 `nextCursor`。
3. 聚合接口内部有降级策略：某下游挂了字段返回默认值，前端要做好空值兜底。
4. 用户主页的计数（关注/粉丝/获赞收藏）与「笔记列表」都在 `UserProfileAggVO` 一次返回。
