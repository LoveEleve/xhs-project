# my-xhs-notification 架构分析

## 一、服务拓扑

```
端口: 19013
JVM:  -Xms256m -Xmx256m
日志: /tmp/r_notification.log
SkyWalking: my-xhs-notification → OAP 21.130.247.89:11800
Nacos:     namespace=my-xhs, server-addr=21.130.247.89:18848
```

## 二、SSE 两步法机制

```
SSE 是一种服务器单向推技术(server→client)，但 EventSource API 不支持自定义 Header ——
无法在 URL 参数外携带 Token。解决方案：两步法。

Step 1: POST /sse/ticket
  → HTTP POST (Header 可带 X-User-Id) → JWT ticket 30 秒有效

Step 2: GET /sse?ticket=xxx
  → URL 参数 ticket → validateAndConsume(一次性消费, 防重放)
  → SseEmitterManager.createConnection → 注册到 Map<userId, SseEmitter>
```

## 三、推送链路

```
业务服务(其他模块) → MQ 或 Feign 调用通知接口
  → NotificationService.createNotification()
    → MySQL INSERT t_notification(user_id, type, content)
    → SseEmitterManager.sendToUser(userId, data)
      → 从 Map 取出 SseEmitter → emitter.send(SseEmitter.event().data(...))
      → 用户不在线: SseEmitter 不存在, 静默跳过(下次登录查询列表)

心跳: 每 15s 发送 "ping" 事件 → emitter.send(SseEmitter.event().name("heartbeat").data("ping"))
超时: 0(永不超时) → 依赖心跳+客户端重连
```

## 四、通知类型聚合

```
notify_date 生成列(MySQL):
  → 同一天、同一类型、同一用户的新通知合并到同一条记录
  → 更新 content 字段(追加内容)，不创建新行
```

## 五、SSE 连接管理

```
SseEmitterManager:
  → ConcurrentHashMap<userId, SseEmitter>
  → onCompletion/onTimeout/onError → remove(userId)
  → getOnlineCount() → map.size()

订阅发布通知:
  → notificationService.send(userId, notification)
  → emitter = map.get(userId)
  → if emitter != null: emitter.send(data)
```

## 六、⚠️ T06 陷阱

```
read/** 路由冲突:
  POST /read/{id}        → 单条已读 ← @PathVariable Long id
  POST /read-by-type/{type} → 批量已读 ← 如果 /read/** 通配拦截，read-by-type 返回 405

原因: Spring 路由匹配 /read/** 可能匹配到 /read-by-type, 从 @PathVariable 读取 "by-type" 作为 Long 失败
解决: /read-by-type 定义在 /read/{id} 之后 → 精确匹配优先
```
