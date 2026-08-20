# my-xhs 前端 API 参考

> 供写前端的 AI 直接使用——无需重读后端代码
> Gateway 入口：`http://{host}:{port}`，所有请求经此转发

---

## 一、鉴权流程

### 1.1 获取 JWT Token

```
POST /api/user/auth/login
Content-Type: application/json
{"phone": "13800000001", "password": "test123456"}

→ 200 { "code": 200, "data": { "token": "eyJ...", "userId": 1001, "hmacSecret": "uuid..." } }
```

### 1.2 使用 Token

后续所有请求带 `Authorization: Bearer {token}`。Gateway 自动注入 `X-User-Id` 到下游。

### 1.3 登出

```
POST /api/user/auth/logout
Authorization: Bearer {token}
→ 200 Token 被加入黑名单
```

### 1.4 刷新 Token

```
POST /api/user/auth/refresh
Authorization: Bearer {token}
→ 200 { "data": { "token": "新token", "hmacSecret": "新secret" } }
```

---

## 二、统一响应格式

```json
{ "code": 200, "message": "success", "data": {...} }
{ "code": 401, "message": "缺少认证信息", "data": null }
{ "code": 403, "message": "无权访问管理接口", "data": null }
```

分页响应：`{ "code": 200, "data": { "records": [...], "total": 100, "size": 20, "current": 1 } }`

---

## 三、端点速查表

### 用户模块 `/api/user/`

| 方法 | 路径 | 说明 | 需要Token |
|------|------|------|:--:|
| GET | `/auth/captcha` | 获取图形验证码 | ❌ |
| POST | `/auth/register` | 注册 `{phone,password,code}` | ❌ |
| POST | `/auth/login` | 登录→返回JWT | ❌ |
| POST | `/auth/refresh` | 刷新Token | ✅ |
| POST | `/auth/logout` | 登出 | ✅ |
| GET | `/me` | 我的信息 | ✅ |
| PUT | `/me` | 修改个人信息 | ✅ |
| PUT | `/me/password` | 修改密码 `{oldPwd,newPwd}` | ✅ |
| GET | `/{userId}/info` | 用户公开信息 | ❌ |
| GET | `/address/list` | 地址列表 | ✅ |
| GET | `/address/default` | 默认地址 | ✅ |
| GET | `/address/{id}` | 地址详情 | ✅ |
| POST | `/address` | 新增地址 `{name,phone,province,city,district,detail}` | ✅ |
| PUT | `/address/{id}` | 修改地址 | ✅ |
| DELETE | `/address/{id}` | 删除地址 | ✅ |
| PUT | `/address/{id}/default` | 设为默认 | ✅ |
| POST | `/block/{targetUserId}` | 拉黑 | ✅ |
| DELETE | `/block/{targetUserId}` | 取消拉黑 | ✅ |
| GET | `/block/list` | 拉黑列表 | ✅ |

### 内容模块 `/api/note/`、`/api/comment/`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/note/publish` | 发布笔记 `{title,content,noteType,coverUrl...}` |
| POST | `/note/draft` | 存草稿 |
| PUT | `/note/{id}` | 编辑笔记 |
| DELETE | `/note/{id}` | 删除笔记 |
| GET | `/note/detail/{id}` | 笔记详情 |
| GET | `/note/user/{userId}` | 用户笔记列表 |
| GET | `/note/my` | 我的笔记（需Token） |
| POST | `/note/{id}/publish` | 发布草稿 |
| POST | `/note/upload/image` | 上传图片→返回URL |
| POST | `/note/{id}/share` | 分享笔记 |
| POST | `/comment` | 发表评论 `{noteId,content,parentId?,replyToId?}` |
| DELETE | `/comment/{id}` | 删除评论 |
| GET | `/comment/list/{noteId}` | 评论列表 |
| GET | `/comment/children/{parentId}` | 子评论 |

### 社交模块 `/api/social/`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/like` | 点赞 `{bizId,bizType(1笔记/2评论)}` |
| DELETE | `/like` | 取消点赞 `{bizId,bizType}` |
| GET | `/like/status?bizId&bizType` | 是否已点赞 |
| GET | `/like/batch-status?bizType&bizIds=1,2,3` | 批量查询 |
| POST | `/favorite` | 收藏 |
| DELETE | `/favorite` | 取消收藏 |
| GET | `/favorite/status` | 收藏状态 |
| GET | `/favorite/list` | 收藏列表 |
| POST | `/follow/{targetUserId}` | 关注 |
| DELETE | `/follow/{targetUserId}` | 取消关注 |
| GET | `/following/{userId}` | 关注列表 |
| GET | `/follower/{userId}` | 粉丝列表 |
| GET | `/common/{targetUserId}` | 共同关注 |
| GET | `/relation/{targetUserId}` | 关注关系（单向/互关/无） |

### 搜索模块 `/api/search/`、`/api/recommend/`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/search/note?keyword&page&size&sort` | 搜索笔记 |
| GET | `/search/product?keyword&categoryId&minPrice&maxPrice&sort` | 搜索商品 |
| GET | `/search/suggest?prefix` | 搜索建议（自动补全） |
| GET | `/search/history` | 搜索历史 |
| DELETE | `/search/history` | 清空历史 |
| DELETE | `/search/history/{keyword}` | 删除单条 |
| GET | `/search/hot` | 热搜榜 |
| GET | `/search/hot/snapshot?date=2026-08-06` | 历史热搜 |
| GET | `/recommend/feed` | 推荐Feed |
| GET | `/recommend/similar/{noteId}` | 相似笔记 |
| POST | `/recommend/behavior` | 上报行为 `{targetId,targetType,action}` |

### 商品模块 `/api/product/`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/spu/{spuId}` | SPU详情 |
| GET | `/spu/list?page&size&categoryId` | SPU列表 |
| GET | `/sku/{skuId}` | SKU详情 |
| GET | `/sku/batch?ids=1,2,3` | 批量查SKU |
| GET | `/sku/list/{spuId}` | SPU的SKU列表 |
| GET | `/category/tree` | 分类树 |

### 购物车 `/api/cart/`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/add` | 加购物车 `{skuId,quantity}` |
| PUT | `/quantity` | 改数量 `{skuId,quantity}` |
| DELETE | `/{skuId}` | 删除 |
| PUT | `/check` | 勾选 `{skuId,checked}` |
| PUT | `/check-all` | 全选 `{checked}` |
| GET | `/list` | 购物车列表 |
| POST | `/merge` | 合并购物车 `{items:[{skuId,quantity}]}` |
| DELETE | `/clear` | 清空 |
| GET | `/count` | 购物车数量 |

### 优惠券 `/api/coupon/`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/template/{id}` | 券模板详情 |
| POST | `/claim` | 领券 `{templateId}` |
| GET | `/user/list?status=0` | 我的券列表（0未使用/1已用/2过期） |
| GET | `/user/available` | 可用券列表 |

### 订单 `/api/order/`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/create` | **创建订单** `{skuItems:[{skuId,quantity}],couponId?,addressId}` |
| GET | `/{orderId}` | 订单详情 |
| GET | `/list?status=` | 订单列表 |
| POST | `/cancel?orderId=` | 取消订单 |
| POST | `/confirm?orderId=` | 确认收货 |
| POST | `/pay/create` | 发起支付 `{orderId,payType}` |
| GET | `/pay/status/{orderId}` | 支付状态 |

### 首页聚合 `/api/home/`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/feed?lastScore&size` | 关注Feed流（游标分页） |
| GET | `/note/{noteId}` | 笔记详情聚合（笔记+作者+计数+社交状态） |
| GET | `/product/{spuId}` | 商品详情聚合 |
| GET | `/user/{targetUserId}` | 用户主页聚合 |
| GET | `/cart` | 购物车聚合（购物车+库存+券） |

### 通知 `/api/notification/`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/sse/ticket` | 获取SSE Ticket（有效期30秒） |
| GET | `/sse?ticket=xxx` | SSE长连接（EventSource） |
| GET | `/list?type&page&size` | 通知列表 |
| GET | `/unread-count` | 未读数（total+分类） |
| POST | `/read/{id}` | 标记单条已读 |
| POST | `/read-by-type/{type}` | 按类型标记已读 |
| POST | `/read-all` | 全部已读 |

### IM `/api/im/`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/ws/ticket` | 获取WebSocket Ticket |
| GET | `/conversations` | 会话列表 |
| GET | `/messages/{peerId}` | 聊天消息 |
| POST | `/read/{peerId}` | 标记已读 |
| GET | `/unread-count` | 未读数 |
| GET | `/online-count` | 在线人数 |

---

---

## 四、间接覆盖的服务

以下模块**无独立前端端点**，数据通过聚合接口获取：

| 服务 | 数据获取方式 | 示例 |
|------|------|------|
| inventory | 库存通过 `/api/home/product/{spuId}` 中的 `stock` 字段 | 商品详情页 |
| payment | 支付通过 `/api/order/pay/create` 发起，状态通过 `/api/order/pay/status/{id}` | 支付页 |
| counter | 计数嵌入详情接口（`likeCount/collectCount/commentCount`） | Feed/笔记详情 |
| analytics | 社交操作通过 `/api/social/**` 端点（已列入社交模块） | 点赞/关注 |

---

## 五、SSE 连接示例

```javascript
// 1. 获取 Ticket
const ticketResp = await fetch('/api/notification/sse/ticket', {
  method: 'POST', headers: { 'Authorization': 'Bearer ' + token }
});
const { ticket } = (await ticketResp.json()).data;

// 2. 建立 SSE 连接
const es = new EventSource('/api/notification/sse?ticket=' + ticket);
es.addEventListener('notification', e => { /* 新通知 */ });
es.addEventListener('unread-count', e => { /* 未读数更新 */ });
es.addEventListener('connected', e => { /* 连接成功 */ });
```

## 六、WebSocket 连接示例

```javascript
// 1. 获取 Ticket
const ticketResp = await fetch('/api/im/ws/ticket', {
  method: 'POST', headers: { 'Authorization': 'Bearer ' + token }
});

// 2. 建立 WS 连接
const ws = new WebSocket('ws://host:port/ws?ticket=' + ticket);
ws.onmessage = e => {
  const msg = JSON.parse(e.data);
  switch(msg.type) {
    case 'CHAT': /* 收到消息 */ break;
    case 'ACK': /* 消息已送达 */ break;
    case 'TYPING': /* 对方正在输入 */ break;
    case 'PONG': /* 心跳响应 */ break;
  }
};
ws.send(JSON.stringify({ type: 'PING' })); // 心跳
ws.send(JSON.stringify({ type: 'CHAT', toUserId: 1002, content: 'hello' }));
ws.send(JSON.stringify({ type: 'ACK', msgId: 'xxx' }));
ws.send(JSON.stringify({ type: 'READ', peerId: 1002 }));
```

## 七、常见错误码

| code | 含义 | 处理方式 |
|:--:|------|------|
| 200 | 成功 | — |
| 400 | 参数错误 | 检查请求体 |
| 401 | 未登录/Token无效 | 跳登录页 |
| 403 | 无权限 | 检查请求路径 |
| 404 | 资源不存在 | — |
| 429 | 请求频率过高 | 稍后重试 |
| 500 | 服务端错误 | 提示用户 |

## 八、关键数据模型

### 笔记 (`NoteVO`)
```json
{ "id": Long, "title": String, "content": String, "coverUrl": String,
  "noteType": 1(图文)/2(视频), "userId": Long, "tags": [String],
  "likeCount": Long, "collectCount": Long, "commentCount": Long,
  "createdAt": String }
```

### 商品 (`SkuVO`)
```json
{ "id": Long, "spuId": Long, "name": String, "price": BigDecimal,
  "image": String, "specs": { "颜色": "红色", "尺码": "M" } }
```

### 订单 (`OrderVO`)
```json
{ "orderId": Long, "orderNo": String, "status": 0(待付)/1(已付)/2(已发货)/3(已完成)/4(已取消)/5(已退款),
  "totalAmount": BigDecimal, "payAmount": BigDecimal, "discountAmount": BigDecimal,
  "items": [{ "skuId": Long, "skuName": String, "quantity": Int, "price": BigDecimal }],
  "createdAt": String }
```

### Feed 流 游标分页
```
第一页：GET /api/home/feed?size=20
→ { notes: [...], nextCursor: "1234567890", hasMore: true }

NoteCardVO 结构：
{ "noteId": Long, "title": String, "coverUrl": String, "noteType": 1/2,
  "authorId": Long, "authorNickname": String, "authorAvatar": String,
  "likeCount": Long, "collectCount": Long, "commentCount": Long,
  "isLiked": Boolean, "isCollected": Boolean, "isFollowed": Boolean }
```
