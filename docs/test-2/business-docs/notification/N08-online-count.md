# N08: SSE在线人数 — GET /api/notification/sse/online-count

## § 源码分析

- **Controller**: `NotificationController.java:129` → `@GetMapping("/sse/online-count")`, 参数 `X-Admin-Call`
- **Service**: `SseEmitterManager.getOnlineCount()`
  - 返回 ConcurrentHashMap<userId, SseEmitter>.size()
- **鉴权**: `isAdminCall(adminCall)` → 403 if not
- **下游**: 内存 Map 查询

## § 业务逻辑

管理/运维接口 → 查询当前SSE活跃连接数 → 用于监控SSE连接池状态

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| Admin Token | `X-Admin-Call` 匹配 `myxhs.admin.token` | 403 "无权访问管理接口" |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl /api/notification/sse/online-count` | 200, {onlineCount: N} |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | AdminToken校验 | ✅ |
| 可观测 | 实时连接数监控 | ✅ |

## § curl

```bash
curl -s http://localhost:19000/api/notification/sse/online-count \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Admin-Call: my-xhs-admin-token-2026"
```

## § ASCII流转图

```
GET /api/notification/sse/online-count + X-Admin-Call
  → NotificationController.getOnlineCount(adminCall)
  → isAdminCall? → no: 403
  → yes: sseEmitterManager.getOnlineCount()
  → 返回 {onlineCount: N}
```
