# 通知模块（my-xhs-notification）

> 来源：`my-xhs-notification` 源码直读。

## 一、业务边界
- 通知列表、未读数、标记已读、**SSE 实时推送**。
- 网关前缀 `/api/notification/**`。全部需登录。

## 二、接口

### 通知列表（分页 + 类型筛选）
```
GET /api/notification/list?type&page&size   (需登录) → Page<NotificationVO>
```
```
NotificationVO: id, type, title, content, senderId, senderName, senderAvatar,
                targetId, targetType, isRead(0/1), aggregateCount, createdAt
```
- `page`/`size` 参数；`size` 上限 50。
- 返回标准 MyBatis-Plus Page（`records/total/size/current`）——与前端 `PageData` 一致 ✓。

### 未读数
```
GET /api/notification/unread-count   (需登录) → UnreadCountVO
```
```
UnreadCountVO: total, details(Map<type,count>)
```

### 标记已读
```
POST /api/notification/read/{id}
POST /api/notification/read-by-type/{type}
POST /api/notification/read-all
```

## 三、SSE 实时推送（核心）
```
1. POST /api/notification/sse/ticket   (需登录) → { "ticket": "短期ticket" }   (30秒有效)
2. const es = new EventSource('/api/notification/sse?ticket=' + ticket)
```
事件：
- `notification`：新通知
- `unread-count`：未读数更新
- `connected`：连接成功
```
事件源不携带 token（用 ticket 换取身份，避免 URL 暴露长期 token）。
```

> ⚠️ 前端 `notificationStore` 已实现完整 SSE 生命周期（✅ 已接线）：
> - **接线**：`AppLayout` 在登录（有 token）时调用 `connectSSE()`，登出/token 变化时 `disconnectSSE()`。
> - **续期**：ticket 30 秒过期，store 每 25 秒重新取 ticket 重建连接，避免静默断开。
> - **重连**：`onerror`/建连失败走指数退避重连（3s→…→30s 上限）。
> - 同时保留 30s 轮询 `unread-count` 作为兜底。

## 四、前端接入注意汇总
1. 通知列表返回标准分页，前端可用现有 `PageData`。
2. 未读 `isRead`：0=未读，1=已读。
3. 用 SSE 实现实时未读数角标；ticket 短期有效，需处理过期重取/重连。
4. 通知类型 `type` 用于分类 Tab（如点赞/评论/关注/系统），后端 `UnreadCountVO.details` 提供各类型未读数。
