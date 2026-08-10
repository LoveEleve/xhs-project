# W06: IM在线人数 — GET /api/im/online-count

## § 源码分析

- **Controller**: `ImController.java:109` → `@GetMapping("/online-count")`, 参数 `X-Admin-Call`
- **Service**: `OnlineRouteService.getOnlineCount()`
  - 返回所有IM实例当前在线用户数
  - 遍历 ConcurrentHashMap<userId, WebSocketChannel> 计数
- **鉴权**: `isAdminCall(adminCall)` → 403 if not
- **下游**: 内存 Map (OnlineRouteService在线用户表)

## § 业务逻辑

管理/运维接口 → 查询当前IM WebSocket活跃连接数 → 用于监控WebSocket连接池 → 多实例场景各实例独立计数

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| Admin Token | `X-Admin-Call` 匹配 `myxhs.admin.token` | 403 "无权访问管理接口" |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl /api/im/online-count` + `X-Admin-Call` | 200, {"onlineCount":N} |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | AdminToken校验 | ✅ |
| 可观测 | WS连接健康监控 | ✅ |

## § curl

```bash
curl -s http://localhost:19014/api/im/online-count \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Admin-Call: my-xhs-admin-token-2026"
```

## § ASCII流转图

```
GET /api/im/online-count + X-Admin-Call
  → ImController.getOnlineCount(adminCall)
  → isAdminCall? → no: 403
  → yes: OnlineRouteService.getOnlineCount()
  → ConcurrentHashMap<userId, WebSocketChannel>.size()
  → 返回 {onlineCount: N}
```
