# 搜索模块（my-xhs-search）

> 来源：`my-xhs-search` 源码直读。

## 一、业务边界
- 笔记搜索、商品搜索、搜索建议、搜索历史、热搜榜/历史热搜。
- 网关前缀 `/api/search/**`。
- 底层 ES，笔记/商品搜索用 **Search After 深分页 + 高亮**。

## 二、核心响应结构（与普通分页不同！）
```
SearchResultVO<T>: { items:[T], total, searchAfter, hasMore, took }
```
- **翻页用 `searchAfter` 游标**，不是页码。下一页请求把上一页的 `searchAfter` 带回（如 `sort`/`searchAfter` 参数）。
- 前端不能按普通 `page/size` 分页。

> ⚠️ 前端 `api/search.ts` 的 `searchNotes/searchProducts` 目前类型为 `PageData<NoteCardVO>`，**错误**。
> 实际返回 `SearchResultVO`，且元素是 `NoteSearchVO/ProductSearchVO`（字段不同，见下）。

## 三、接口

### 笔记搜索
```
GET /api/search/note?keyword&page&size&sort   (可选需登录) → SearchResultVO<NoteSearchVO>
```
```
NoteSearchVO: noteId, userId, title, content, coverImage, likeCount,
              collectCount, commentCount, createdAt,
              highlightTitle, highlightContent(高亮含<em>标签)
```
- 搜索自动记录到热搜窗口（反作弊）。
- `highlightTitle/Content` 含高亮标签，前端可直接渲染。

### 商品搜索
```
GET /api/search/product?keyword&categoryId&minPrice&maxPrice&sort   → SearchResultVO<ProductSearchVO>
```
```
ProductSearchVO: spuId, skuId, name, categoryId, categoryName, brandName,
                 price, image, sales, createdAt, highlightName
```

### 搜索建议
```
GET /api/search/suggest?prefix   → List<String>
```

### 搜索历史（需登录）
```
GET    /api/search/history          → List<String>
DELETE /api/search/history          (清空)
DELETE /api/search/history/{keyword} (删单条)
```

### 热搜
```
GET    /api/search/hot              → List<HotSearchVO>
POST   /api/search/hot/record       body:{keyword}
GET    /api/search/hot/snapshot?date=YYYY-MM-DD  → List<HotSearchVO>
```
```
HotSearchVO: rank, keyword, score, pinned, tag
```
> ⚠️ 前端 `getHotSearch` 类型为 `{keyword,score}[]`，实际是 `HotSearchVO[]`（含 rank/pinned/tag）。

## 四、前端接入注意汇总
1. 搜索结果是 `SearchResultVO`（`items + searchAfter + hasMore`），**不是** PageData，且翻页用 `searchAfter`。
2. 笔记搜索字段是 `coverImage/highlightTitle`，不是 `coverUrl/title`——不能直接复用 `NoteCard`，需适配。
3. 商品搜索字段是 `spuId/skuId/image/highlightName`，可跳商品详情。
4. 热搜返回 `HotSearchVO`（rank/pinned/tag），热门词可点击跳搜索。
5. 搜索历史需登录；`recordSearchKeyword` 已由后端在搜索时自动记录，前端**无需**手动上报（但仍保留该能力）。
