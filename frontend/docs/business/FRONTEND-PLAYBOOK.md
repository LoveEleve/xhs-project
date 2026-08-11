# 前端落地手册（交互流程级业务逻辑）

> 第二层深挖：从 API 契约下沉到**服务实现**，提炼前端真正要处理的交互流程、乐观更新、
> 状态机边界、降级字段、失败分支。写页面时对照本手册，可避免大量「能跑但业务不对」的 bug。
> 各模块基础契约见各 README；本手册只讲**流程与边界**。

---

## 1. 登录 / 鉴权（前端根基）

- 登录返回 `accessToken + refreshToken + hmacSecret`。**hmacSecret 必须保存**（写请求签名用）。
- **单设备登录**：同账号后登录会踢掉旧设备 → 旧 token 进黑名单，下次请求 401。前端收到 401 即跳登录。
- 刷新 token 的接口参数是 **query**：`POST /user/auth/refresh?refreshToken=xxx`。
  > ⚠️ 前端 `api/auth.ts` 的 `refreshToken()` 没传该参数，若要自动续期需修正。
- 改密码成功后，后端注销该用户**所有** token → 前端必须跳登录页重新登录。
- 429 = 限流（登录失败、发帖、点赞等都有频率限制），前端提示「操作过于频繁，稍后再试」。

## 2. Feed 流（全部 / 关注 / 推荐）

### 「全部 / 关注」Tab → `GET /home/feed?lastScore&size`
- 翻页用上一页的 **`nextCursor`**（字符串，转 Double 当 lastScore）。
  > ⚠️ 现有 `FeedPage` 用「最后一篇的 score」，后端其实返回 `nextCursor`，建议改用它。
- **返回的 `isCollected` 恒为 false**（后端没聚合收藏状态）、**`isFollowed` 恒为 true**（Feed 都是关注的人）。
  → 前端在 Feed 卡片上**不要**依赖这两个字段做收藏/关注状态展示。
- `unreadCount` 会随 Feed 返回（未读通知数），可用于角标。

### 「推荐」Tab → `GET /recommend/feed`
- 返回 `List<RecommendFeedVO>`（`noteId/score/source/category/reason`），**只是笔记 ID 列表**。
- 前端需用这些 noteId 二次拉取详情才能展示卡片（现有 FeedPage 直接把结果当 NoteCardVO 用 → 会渲染空白）。
- 曝光/点击上报 `POST /recommend/behavior {targetId, targetType:1, action}`。

## 3. 笔记发布 / 编辑（含敏感词）

- **发布 / 编辑已发布笔记 / 发表评论 / 发布草稿，都会做 DFA 敏感词检测**。
  命中返回业务错误，`message` 里含违规词（如 `内容包含违规词汇：xxx`）→ 前端应直接把 message 展示给用户。
- 发布流程：先 `POST /note/upload/image`（multipart, field=`file`）拿 url → 塞进 `images[]` → `POST /note/publish`。
- 发布成功 → 后端自动推 Feed（异步），前端提示「发布成功」即可，无需手动刷关注流。
- **笔记状态机**：`0草稿 / 1审核中 / 2已发布 / 3已下架`。编辑仅草稿/已发布可编辑。
- 「我的笔记」`GET /note/my?status&pageNum&pageSize` 支持按 status 筛选 → 可做 草稿/已发布 Tab。
- 列表缩略图用 `NoteItemVO.firstImage`（第一张图）。

## 4. 评论（两级楼中楼）

- **只支持两级**：一级评论 `parentId=0`；子评论 `parentId=根评论ID`。
- 回复语义（前端发送参数）：
  - 回复一级评论：`parentId=一级评论ID`（`replyToId` 可省）
  - 回复子评论：`parentId=根评论ID` + `replyToId=子评论ID`
  - 后端会自动把「回复二级评论」的 parentId 修正为根评论ID（保证只两级）。
- 列表：一级评论按 id **降序**（新的在前），每条预加载**前3条子评论** + `childCount`。
  子评论按 id **升序**（新的在后），点「查看更多」`GET /comment/children/{parentId}` 加载。
- 删除：**评论作者或笔记作者**可删；删一级评论会级联删其下所有子评论。
- 发表评论后：计数异步更新，前端**重新拉详情**刷新 `commentCount`。
- 敏感词命中报错同上。

## 5. 点赞 / 收藏 / 关注（乐观更新安全）

- **点赞/取消、收藏/取消都幂等**（重复操作直接成功不报错）→ 前端可放心做乐观更新，失败才回滚。
- 收藏请求体是 **`{noteId}`**（不是 bizId/bizType）。
- **关注**：不能关注自己；重复关注报 `ALREADY_FOLLOWED`，未关注就取关报 `NOT_FOLLOWED`。
  → 前端切换按钮前先拿当前状态（如详情页 isFollowed / 用户主页 isFollowing）。
- **关注/粉丝列表** `GET /social/following|follower/{userId}` 返回 `{total, list: FollowVO[]}`，
  且 `FollowVO` 里 **nickname/avatar 可能为空**（服务只填了 userId/followedAt/isFollowBack）→
  前端需对每个 userId 单独取用户信息（或经用户主页接口）。
- **收藏列表** `GET /social/favorite/list` 返回 `{total, list:[noteId...]}` → 需逐个取笔记详情。
- 关系查询 `GET /social/relation/{userId}` → `{isFollowing, isFollowBack, isMutual}`（用户主页判断关注按钮文案）。

## 6. 商品详情（SKU 选择 + 加购/购买）

- 用 `GET /home/product/{spuId}`：含 `skuList[].specValues`（对象）、`availableStock`、`hasStock`、`relatedNotes`。
- **SKU 选择逻辑**：从所有 sku 的 `specValues` 聚合出「规格组」（key→可选值），
  用户选满所有规格后，匹配到**唯一 skuId** 才能加购/下单；否则提示「请选择规格」。
- 无库存（`hasStock=false`）的 SKU 置灰禁用。
- 加购 `POST /cart/add {skuId, quantity}`；「立即购买」→ 直接跳 `/order/create` 并带 `{skuItems, skuInfo}`（前端要自带展示信息，因订单接口不回传图片）。

## 7. 购物车 → 下单 → 支付（核心电商闭环）

### 购物车
- 用 `GET /home/cart`（含 `availableStock/hasStock/onSale/checkedAmount/allChecked`）。
- 无库存/下架项禁用勾选并提示；合计以 `checkedAmount` 为准。
- 数量上限 99/种、品种上限 50；登录后合并游客购物车（`localStorage.guestCart` → `POST /cart/merge` → 清除）。

### 创建订单
- `POST /order/create {skuItems, couponId?, addressId, remark?}` → 返回完整 `OrderVO`。
- **地址**：传 `addressId`，但**后端当前存的 `addressSnapshot` 是硬编码 Mock**（`测试用户/13800138000/北京市朝阳区...`）
  → 订单详情页显示的是假地址，前端**不要**据此回显真实地址。
- **明细图片** `items[].skuImage` 恒为 **null**（product SKU 无图片字段）→ 前端订单展示要做空图兜底。
- **金额以后端计算为准**（真实 SKU 价 + 真实券折扣），前端预估仅供参考。
- 可能失败：库存不足（`STOCK_NOT_ENOUGH`）、券不可用/已被使用、地址未传。
- 下单走事务消息 + **30 分钟超时自动关单**（未支付会被自动取消）。

### 支付
- `POST /order/pay/create {orderId, payType}`；`payType: 1=支付宝(mock成功) / 2=微信(约30% mock失败)`。
- **微信支付可能失败** → 订单**自动取消**（释放库存+退券）→ 前端支付失败分支必须刷新订单状态。
- 支付成功/失败后都重新 `GET /order/{orderId}` 或 `pay/status` 刷新。

## 8. 优惠券

- 类型：`1满减(满minAmount减discountValue)` / `2折扣(金额×discountValue/10)` / `3无门槛(直接减)`。
- 折扣**不能超过订单金额**（防0元购）。
- 领券 `POST /coupon/claim {templateId}`；我的券 `GET /coupon/user/list?status` 返回**数组**。
- ⚠️ 后端**没有**「全部券模板列表」公开接口 → 领券中心页需自行设计数据来源（或用模板管理接口需管理员 token）。

## 9. 搜索 / 推荐（返回结构特殊）

- 搜索返回 `SearchResultVO {items, total, searchAfter, hasMore, took}` → **翻页用 searchAfter**，非页码。
- 笔记搜索字段是 `coverImage/highlightTitle/highlightContent`（高亮含 `<em>`），**不是** `coverUrl/title` → 不能直接复用 `NoteCard`。
- 商品搜索字段 `spuId/skuId/name/image/highlightName` → 可跳 `/product/:spuId`。
- 热搜 `GET /search/hot` → `HotSearchVO[]`（rank/keyword/score/pinned/tag）；点击可跳搜索。

## 10. 通知（SSE）

- `GET /notification/list?type&page&size` 标准分页；`isRead: 0未读/1已读`。
- SSE：`POST /sse/ticket` 拿 30 秒 ticket → `EventSource('/notification/sse?ticket=xxx')`。
  - 事件名：`notification` / `unread-count` / `connected`。
  - **ticket 30 秒过期**，EventSource 无法带自定义头 → 建立/重连时都要重新取 ticket。
  - `onerror` 断开后需重连。
- 未读数 `GET /notification/unread-count` → `{total, details{type:count}}`；`details` 可做分类 Tab 角标。

## 11. IM（WebSocket）

- 握手：`POST /im/ws/ticket`（5分钟）→ `new WebSocket('ws://host/api/im/ws?ticket=xxx')`。
- 发送 `{type:"CHAT", to: receiverId, content}`；**msgId 由服务端生成**，服务端回 `{type:"ACK", msgId, timestamp}`。
- 接收对方消息：`{ver:1, type:"CHAT", msgId, seqNo, from, content, msgType, timestamp}`。
- 消息类型：`CHAT/ACK/READ/TYPING/PING/PONG/NACK`；`msgType: 0普通 / 98输入中 / 99已读回执`。
- 心跳：定时发 `{type:"PING"}` 收 `{type:"PONG"}`；**90s 无 PING 路由过期**（视为离线）。
- 进入会话：`POST /im/read/{peerId}` 清未读；发 `{type:"READ", peerId}` 通知对方。
- 会话/消息列表：`GET /im/conversations|messages/{peerId}` 返回 `{records, total, page}`（分页 Map）。

---

## 附：跨模块「降级/占位」字段速查（写页面务必兜底）
| 字段 | 实际值 | 处理 |
|------|--------|------|
| Feed `isCollected` | 恒 false | 勿据此展示收藏态 |
| Feed `isFollowed` | 恒 true | 勿据此展示关注按钮 |
| Order `addressSnapshot` | 硬编码 Mock | 勿当真地址回显 |
| Order `items[].skuImage` | 恒 null | 图片空兜底 |
| FollowVO `nickname/avatar` | 可能 null | 需二次取用户信息 |
| Favorite list | `{total, list:[noteId]}` | 需取笔记详情 |
| Recommend feed | `List<{noteId,...}>` | 需取笔记详情 |
| Search note | `coverImage/highlightTitle` | 字段名与 NoteCard 不同 |

> 这些「能跑但业务不对」的坑，全部来自读服务实现而非文档——写页面时对照本表即可避免。
