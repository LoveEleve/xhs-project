# my-xhs 前端业务文档

> 本目录是**前端开发专用的业务参考**——所有内容均由我直接阅读后端各微服务源码产出（非搬运 test-2 文档），
> 目的是让前端在不熟悉后端代码的情况下，也能写出**业务正确、不频繁报错**的页面。

---

## ⭐ 先读这个
**[FRONTEND-PLAYBOOK.md](FRONTEND-PLAYBOOK.md)** — 交互流程级业务逻辑（登录/Feed/发布/评论/社交/下单/支付/优惠券/搜索/通知/IM 的
乐观更新、状态机边界、失败分支、降级占位字段）。写任何页面都先对照它。

---

## 模块清单（与后端微服务一一对应）

| 目录 | 对应服务 | 前端页面 | 状态 |
|------|---------|---------|:--:|
| [user](user/README.md) | my-xhs-user | 登录/注册/我的/地址/拉黑/改密 | ✅ |
| [content-social](content-social/README.md) | my-xhs-content, my-xhs-social(analytics) | Feed/笔记详情/发布/评论/点赞/收藏/关注 | ✅ |
| [product](product/README.md) | my-xhs-product | 商品列表/详情 | ✅ |
| [cart](cart/README.md) | my-xhs-cart | 购物车 | ✅ |
| [order](order/README.md) | my-xhs-order | 订单创建/列表/详情 | ✅ |
| [payment](payment/README.md) | my-xhs-payment | 支付（嵌入订单页） | ✅ |
| [coupon](coupon/README.md) | my-xhs-coupon | 我的券/领券中心 | ✅ |
| [inventory](inventory/README.md) | my-xhs-inventory | 库存（嵌商品/购物车） | ✅ |
| [home](home/README.md) | my-xhs-home | BFF 聚合层 | ✅ |
| [search](search/README.md) | my-xhs-search | 搜索页 | ✅ |
| [recommend](recommend/README.md) | my-xhs-recommend | 推荐 Feed | ✅ |
| [notification](notification/README.md) | my-xhs-notification | 通知页（SSE） | ✅ |
| [im](im/README.md) | my-xhs-im | 会话/聊天（WS） | ✅ |
| [counter](counter/README.md) | my-xhs-counter | 计数（嵌入详情） | ✅ |
| [analytics](analytics/README.md) | my-xhs-analytics | 社交动作底层 | ✅ |
| [gateway](gateway/README.md) | my-xhs-gateway | 鉴权/路由/幂等 | ✅ |

---

## 前端开发必读（贯穿全项目的关键约定）

### 1. 通用响应格式
所有接口统一包装：
```json
{ "code": 200, "message": "success", "data": {...} }
```
- `code === 200` 表示成功；非 200 时 `message` 为可直接展示给用户的提示。
- 分页响应：`{ "data": { "records": [...], "total": 100, "size": 20, "current": 1 } }`

### 2. 鉴权（务必按此实现）
- 登录后得到 `data.accessToken`，前端存 `localStorage.token`。
- 所有请求头带 `Authorization: Bearer {token}`，Gateway 校验后向下游注入 `X-User-Id`。
- `401` → token 失效，前端跳登录页。
- **单设备登录**：同账号后登录会踢掉旧设备（旧 token 进黑名单）。
- `hmacSecret`：per-session 签名密钥（前端一般无需处理，由 Gateway 完成）。

### 3. 三个最容易踩的坑（读源码确认）
1. **分页参数名不一致**：`/product/spu/list` 用 `pageNum/pageSize`，其他多数用 `page/size`。
2. **订单列表不分页**：`/order/list` 直接返回 `data: OrderVO[]`（数组），不是分页对象。
3. **「我的」接口与公开接口返回字段不同**：`/user/me` 含 phone/email，`/{userId}/info` 只含非敏感字段。

### 4. 前端 API 层现存缺陷（写页面时对照修复）
见各模块 README 末尾「前端接入注意」小节，均已核对后端源码。

---

## 阅读顺序建议

1. 本 README（全局约定）
2. gateway/README.md（理解鉴权与路由如何工作）
3. home/README.md（理解 BFF 聚合层，很多页面数据其实来自 home 模块）
4. 按你要做的页面，读对应模块 README

## 如何核对字段

每个模块 README 都包含「数据模型」小节，字段名与后端 VO 完全一致（我直接读的 DTO/entity 源码）。
前端 `src/types/index.ts` 中的类型应与这些模型对齐；若有出入，以本目录文档为准。

---

## ⚠️ P0：完善前端前必须先修的硬伤（均读后端源码确认）

### 1. ✅ 【已修复】HMAC 请求签名缺失（见 gateway 模块）
后端网关 `HmacSignatureFilter`（`@Component GlobalFilter`）**无条件生效**，所有非 HMAC 白名单的
**写请求**（点赞/收藏/关注/加购/下单/发布/领券…）必须带 `X-Timestamp`、`X-Nonce`、`X-Signature` 头，
否则返回 `403 签名校验失败`。
- **已实现**：`src/utils/hmac.ts`（签名算法）+ `src/api/client.ts`（请求拦截器自动签名）+ `authStore` 保存/清除 `hmacSecret`。
- 签名 = `Base64(HMAC-SHA256(METHOD + path + timestamp + nonce, hmacSecret))`（已用后端公式核对一致）。
- 策略：凡有 `hmacSecret`（已登录）的请求都签名——非白名单路径必需，白名单路径网关跳过校验、多余无害。

### 2. 前端 API 层字段/类型与后端不符（写页面时逐个修正）
> ✅ = 已修复；未标 = 待修
| 文件 | 问题 | 后端实际 |
|------|------|---------|
| ✅ `api/social.ts` favorite/unfavorite/status | 原发 `{bizId,bizType}` | 后端只要 `{noteId}` |
| ✅ `api/social.ts` getFavoriteList | 原类型 unknown | 返回 `{total,list:[noteId]}` |
| ✅ `api/cart.ts` + `cartStore` | getCartCount 返回 `{count:N}` | 已改为 `.data.count` |
| ✅ `api/note.ts` getComments/getUserNotes/getMyNotes | 原 `page/size` | 后端用 `pageNum/pageSize` |
| ✅ `api/note.ts` publishNote/saveDraft | 原返回 `{id}` | 返回 `{noteId}` |
| ✅ `api/auth.ts` refreshToken | 原无参 | 需 `?refreshToken=` |
| ✅ `types/index.ts` AddressRequest | 原 `name/phone/detail` | `receiverName/receiverPhone/detailAddress` |
| ✅ `api/recommend.ts` getRecommendFeed/getSimilarNotes | 原当 NoteCard 数组 | 返回 `List<RecommendFeedVO>`（noteId+score） |
| `api/social.ts` getFollowing/getFollowers/getRelation | 类型 unknown | 返回 `{total,list}` / `{isFollowing,isFollowBack,isMutual}`（写关注页时修） |
| `api/coupon.ts` getUserCouponList | 类型 `PageData` | 返回 `List<UserCouponVO>` 数组（写券页时修） |
| `api/search.ts` searchNotes/searchProducts | 类型 `PageData<NoteCardVO>` | 返回 `SearchResultVO<NoteSearchVO/ProductSearchVO>`（写搜索页时修） |
| `api/im.ts` getImMessages | 类型 `ImMessageVO[]` | 返回 `{records,total,page}` 分页 Map（写IM页时修） |

### 3. 返回结构差异（页面数据来源别搞错）
- 商品详情/笔记详情/用户主页/购物车：**优先用 `/api/home/**` 聚合**（数据最全）。
- 搜索/推荐返回的是「ID/专用 VO + searchAfter 游标」，不是标准分页/卡片，需适配。
- 收藏列表/关注/粉丝返回 `{total, list}`，list 元素是 ID 或 FollowVO，需二次取详情。

### 4. 状态机（前端对照展示/按钮）
- 订单：`0待付款→1已付款→2已发货→3已完成`，`4已取消`，`5已退款`。
- 笔记：`0草稿/1审核中/2已发布/3已下架`；`noteType: 0图文/1视频`。
- 用户券：`0未使用/1已使用/2已过期`；券类型 `1满减/2折扣/3无门槛`。
- 用户 `gender: 0未知/1男/2女`；`status: 1正常/0禁用`。
