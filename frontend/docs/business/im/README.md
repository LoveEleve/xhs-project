# 即时通讯模块（my-xhs-im）

> 来源：`my-xhs-im` 源码直读。

## 一、业务边界
- 会话列表、聊天记录、标记已读、未读数、WebSocket 实时聊天。
- 网关前缀 `/api/im/**`。全部需登录。

## 二、REST 接口

### 会话列表（分页）
```
GET /api/im/conversations?page&size   (需登录) → { records: ConversationVO[], total, page }
```
```
ConversationVO: peerId, peerName, peerAvatar, lastContent, lastMsgType,
                unreadCount, updatedAt
```

### 聊天记录（分页）
```
GET /api/im/messages/{peerId}?page&size   (需登录) → { records: ImMessageVO[], total, page }
```
```
ImMessageVO: id, senderId, receiverId, content, msgType, createdAt
```
> ⚠️ 返回是**分页 Map**（`{records,total,page}`），不是数组。
> 前端 `api/im.ts` 的 `getImMessages` 类型为 `ImMessageVO[]`，**需修正**为 `{records,total}`。

### 标记已读 / 未读数
```
POST /api/im/read/{peerId}
GET  /api/im/unread-count   → { total }
```

### 在线人数（管理端）
```
GET /api/im/online-count   (需 X-Admin-Call) → { localOnline, serverId }
```

## 三、WebSocket 实时聊天（核心）
```
1. POST /api/im/ws/ticket   (需登录) → { "ticket": "短期JWT(5分钟)" }
2. const ws = new WebSocket('ws://host/api/im/ws?ticket=' + ticket)   ← 路径含 /api/im/ws
```
消息协议（`JSON`）：
- 收：`CHAT`(新消息), `ACK`(已送达), `TYPING`(输入中), `PONG`(心跳响应)
- 发：`PING`(心跳), `CHAT`({toUserId,content}), `ACK`({msgId}), `READ`({peerId})
```
用短期 ticket 而非长期 token，避免在 WS URL 暴露 token。
```
> ⚠️ 前端 `api/im.ts` 的 `getWsTicket` 返回 `{ticket}`，正确。注意 WS 路径是 `/api/im/ws`。
> Vite 代理 `ws:true` 已开启，本地调试可直连。

## 四、前端接入注意汇总
1. 会话/消息都返回 `{records,total,page}` 分页 Map，修正 `api/im.ts` 类型。
2. WS 地址用 ticket，路径 `/api/im/ws`；心跳 `PING` 保持连接。
3. 发消息用 `CHAT`；收到 `CHAT` 追加到当前会话并更新未读数。
4. 进入会话调用 `/im/read/{peerId}` 清未读。
