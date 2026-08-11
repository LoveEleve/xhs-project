# 推荐模块（my-xhs-recommend）

> 来源：`my-xhs-search`（recommend 接口）源码直读。

## 一、业务边界
- 推荐 Feed 流、相似笔记、行为上报。
- 网关前缀 `/api/recommend/**`。

## 二、接口

### 推荐 Feed
```
GET /api/recommend/feed?size=20   (可选需登录) → List<RecommendFeedVO>
```
```
RecommendFeedVO: noteId, score, source, category, reason
```
- 返回的是**笔记 ID + 推荐理由**，不是完整卡片。
- 前端需用这些 noteId 批量拉笔记详情（或经 home 聚合）才能展示卡片。

### 相似笔记
```
GET /api/recommend/similar/{noteId}?size=   → List<RecommendFeedVO>
```
- 同样是 noteId 列表。

### 行为上报（埋点）
```
POST /api/recommend/behavior   (需登录)
body: { targetId, targetType(1笔记/2商品?), action }
```
- `action` 示例：`impression`(曝光)、`click`(点击)。
- 用于推荐训练，前端 Feed 卡片曝光/点击时上报（现有 FeedPage 已实现）。

## 三、前端接入注意汇总
1. 推荐接口返回**笔记 ID 列表**（带 score/reason），需要二次取详情。
2. 现有 `FeedPage` 的「推荐」Tab 用 `getRecommendFeed`，但拿到的不是 `NoteCardVO`，
   `api/recommend.ts` 类型标注为 `FeedResponse` 并不准确，需按 `List<RecommendFeedVO>` 修正。
3. 上报行为 `targetType=1` 表示笔记；前端 Feed 做 impression/click 上报即可。
