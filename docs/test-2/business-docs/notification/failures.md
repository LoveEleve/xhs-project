# my-xhs-notification 已知故障与陷阱

## 一、SSE陷阱

### T01: SSE ticket 30秒过期
- **现象**: 客户端拿到ticket后30秒内未建立SSE连接 → 建立时返回 "Ticket无效或已过期"
- **根因**: SseTicketService 30秒TTL + 一次性消费
- **应对**: 获取ticket后立即建立SSE连接

### T02: SseEmitter 超时设置
- **现象**: SSE连接断开、客户端频繁重连
- **根因**: SseEmitter 超时 0 = 永不超时 → 依赖心跳保活(每15s ping)
- **验证**: 客户端监听 heartbeat 事件 → 连续3次未收到则重连

### T03: read/** 回溯匹配冲突(T06)
- **现象**: `POST /api/notification/read-by-type/1` 返回类型错误
- **根因**: `/read/{id}` 匹配 `read-by-type` 为 @PathVariable, "by-type" 无法转为 Long
- **应对**: Spring 精确匹配优先 → `/read-by-type/{type}` 定义在 `/read/{id}` 之后即可

## 二、配置陷阱

### @Profile("dev")
- **现象**: N09 test/send 接口 404
- **根因**: NotificationTestController 必需 `@Profile("dev")`，生产环境该Bean不存在
- **验证**: `curl /api/notification/test/send` → 404(dev) / 200(test)

## 三、SSE连接陷阱

### SSE 不自动重连
- **现象**: 刷新页面后不再接收通知
- **根因**: EventSource 使用 cookie/session 维持，页面刷新后需要重新建立连接
- **应对**: 前端在组件挂载时重新调用 ticket → connect 两步法

### 用户不在线通知丢失
- **现象**: 发送通知时用户不在线→再次上线看不到
- **根因**: SseEmitter.sendToUser 找不到 Emitter 时静默跳过 → 通知已写入MySQL
- **应对**: 客户端上线后调用 GET /list 拉取未读通知

## 代码级缺陷（3 P0 + 7 P1，链7深审 2026-08-09）

### P0-A ✅ NotificationEventConsumer幂等标记阻塞重试（已修复）
- **位置**: `NotificationEventConsumer.java:76-80`
- **现象**: processEvent失败→MQ重试→每次因幂等标记`isFirstProcess==false`直接return→通知永久丢失
- **修复**: catch中先`removeMark`再throw

### P0-B 聚合窗口5min vs唯一索引按天冲突（待修复）
- **位置**: `NotificationAggregator.java:47` + `sql/mysql-user-init.sql:152`
- **现象**: 5分钟窗口过期→INSERT撞`uk_aggregate`→DuplicateKey→通知丢失
- **修复**: 聚合窗口TTL改为当天结束或INSERT ON DUPLICATE KEY UPDATE

### P0-C IM WebSocket Gateway白名单拦截（待验证）
- **位置**: `gateway/application.yml:269-298` 白名单缺`/api/im/ws`
- **现象**: 浏览器WS握手无法带Authorization→Gateway 401
- **修复**: 白名单加`/api/im/ws`

### N1 Redis不可用SSE抛异常（待修复）
### N3 SSE无连接上限ticket无限（待修复）
### N4 SseEmitter 30min硬超时（待修复）
### M2 路由竞态afterConnectionClosed误删新连接（待修复）
### M3 seqNo Redis INCR无异常处理（待修复）
### M4 upsertConversation读改写非原子未读少计（待修复）
### M1 jwt.secret两处默认不一致（待修复）
