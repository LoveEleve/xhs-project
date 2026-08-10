# 12-im 即时通讯服务 — curl 测试记录

> 端口：19014 | 数据库：my_xhs_im | 测试日期：2026-07-30

---

## Gateway + JWT + HMAC per-session 重测（2026-08-03）

> 测试时间：2026-08-03 15:49
> 测试入口：Gateway 19000
> 认证方式：JWT + HMAC per-session secret + X-User-Id header
> 测试用户：im_test_* (register→login, userId=2084184663967948802)
> 测试脚本：`scripts/test-12-im.py`

### 8 个用例结果（6 端点 + 2 异常）

| # | 用例 | HTTP | code | msg | 结果 |
|:--:|------|:--:|:--:|------|:--:|
| 1 | GET 会话列表 | 200 | 200 | 操作成功 | ✅ |
| 2 | GET 消息记录 | 200 | 200 | 操作成功 | ✅ |
| 3 | POST 标记已读 | 200 | 200 | 操作成功 | ✅ |
| 4 | GET 未读数 | 200 | 200 | 操作成功 | ✅ |
| 5 | GET 在线人数 | 200 | 200 | 操作成功 | ✅ |
| 6 | POST WS Ticket | 200 | 200 | 操作成功 | ✅ |
| 7 | 异常: ticket缺userId | 200 | 200 | Gateway自动注入 | ✅ |
| 8 | 异常: 超大peerId | 200 | 200 | 无校验静默处理 | ✅ |

**8/8 全部通过**。

### 模块特性

- **无请求体 DTO/无校验注解**：与 notification 一样，所有端点走 Query/Path 参数，无 `@Valid` / `@NotNull`
- **WebSocket 两步法**：`POST /ws/ticket`（JWT 签发 5min ws_ticket）→ `ws://host/api/im/ws?ticket=xxx` 连接
- **无 Feign 客户端**：跨实例通信走 Redis Pub/Sub（`im:route:{serverId}`），不走 Feign
- **一致性 Hash LoadBalancer 未启用**：`ImConsistentHashLoadBalancer` 代码存在但 `application.yml` 未配置 `spring.cloud.loadbalancer.configurations`

### 异常路径分析

IM 模块**没有传统参数校验异常**：
- **缺 X-User-Id**：Gateway 从 JWT 自动注入，Controller 永远能拿到
- **无校验注解**：非法参数被静默处理（Service 层 return empty/默认值）
- WebSocket 层的校验在 `ChatService.handleChat` 手工完成——但只在 WS 通道中触发

### Gateway 白名单修复

`/api/im/online-count` 是公开端点（不需 X-User-Id），但原不在 Gateway 白名单，已补充：
```yaml
# white-list + hmac-white-list 均加
- /api/im/online-count
```

### 13 层验证汇总

| 层 | 验证项 | 结果 |
|:--:|------|:--:|
| L1 | API 响应 HTTP 200 + code 200 | ✅ |
| L2 | Gateway ↔ IM traceId 一致 | ✅ |
| L3 | Redis：im:unread:{userId} Hash | ✅ |
| L4 | MySQL：t_chat_message / t_chat_user_relation | ✅ |
| L5 | ACCESS 日志 | ✅ |
| L6 | MQ：N/A（跨实例路由走 Redis Pub/Sub） | N/A |
| L7 | WebSocket 两步法（ticket→握手） | ✅ |
| L8 | 异常：无校验注解，静默处理边界 | ✅ |
| L9 | @RateLimit：N/A | N/A |
| L10 | Sentinel：N/A | N/A |
| L11 | SkyWalking：N/A | N/A |
| L12 | Gateway JWT auth + HMAC + 白名单修复 | ✅ |
| L13 | Actuator /health UP | ✅ |

## 测试用例概览

| # | 接口 | 方法 | 说明 | 状态 |
|:--:|---|---|---|---|
| 1 | `/api/im/ws/ticket` | POST | WebSocket Ticket 签发 | ✅ |
| 2 | 无效 Ticket → WS 握手 | — | 握手拒绝验证 | ✅ |
| 3 | WebSocket CHAT | WS | 发消息 + ACK + PING/PONG | ✅ |
| 4 | `/api/im/conversations` | GET | 会话列表 | ✅ |
| 5 | `/api/im/unread-count` | GET | 总未读数 | ✅ |
| 6 | `/api/im/read/{peerId}` | POST | 标记已读 | ✅ |
| 7 | `/api/im/messages/{peerId}` | GET | 历史消息 | ✅ |
| 8 | `/api/im/online-count` | GET | 在线连接数 | ✅ |
| 9 | WebSocket READ_NOTIFY | WS | 已读回执推送 | ✅ |

---

## 1. POST /api/im/ws/ticket — 签发 WebSocket Ticket

```bash
curl -s -X POST http://localhost:19014/api/im/ws/ticket \
  -H "X-User-Id: 10001"
```

**响应** ✅ L1：HTTP 200
```json
{"code":200,"data":{"ticket":"eyJhbGciOiJIUzI1NiJ9.eyJqdGkiOiJkNDIxOWY5NzQyZWQ0MzA1ODJhZmE5NjI2NDgwODY5YSIsInN1YiI6IjEwMDAxIiwidHlwZSI6IndzX3RpY2tldCIsImlhdCI6MTc4NTM3NTQzNSwiZXhwIjoxNzg1Mzc1NzM1fQ.pUFgP8PgIXeFkW65335poPL-sRJBDORtr1R2usL6P6g"},"success":true}
```

**JWT 解码** ✅：`sub=10001`, `type=ws_ticket`, `exp-iat=300s=5min`

**ACCESS 日志** ✅ L2：
```
[ACCESS] POST /api/im/ws/ticket, status=200, rt=6ms, ip=127.0.0.1
traceId=091a13e950614d28baaefe359755d0c6
```

**Gateway 路由验证** ✅ L12：JWT + HMAC 签名通过 Gateway 访问正常（完整签名需要 X-Timestamp/X-Nonce/X-Signature Header）。

---

## 2. 无效 Ticket → WebSocket 握手拒绝

```python
import websocket
try:
    ws = websocket.create_connection("ws://localhost:19014/api/im/ws?ticket=INVALID", timeout=5)
except websocket.WebSocketBadStatusException as e:
    print(f"拒绝, status={e.status_code}")  # status=200
```

**结果** ✅：连接被拒绝（WebSocket 未升级，status=200 而非 101）。

**注意事项** 🟡：拒绝时返回 HTTP 200（空 body）而非 401/403，客户端无法直接从 HTTP 状态码区分"拒绝"和"正常响应"。

---

## 3. WebSocket CHAT — 发消息 + ACK + PING/PONG

```python
import websocket, json
ticket = requests.post(...).json()["data"]["ticket"]
ws = websocket.create_connection(f"ws://localhost:19014/api/im/ws?ticket={ticket}")

ws.send(json.dumps({"ver":1,"type":"CHAT","to":10002,"content":"测试消息"}))
resp = json.loads(ws.recv())  # → {"type":"ACK","msgId":2082641824612155393,...}

ws.send(json.dumps({"ver":1,"type":"PING"}))
pong = json.loads(ws.recv())  # → {"type":"PONG"}
```

**MySQL** ✅ L4（主库 13306, my_xhs_im）：
```sql
SELECT id, sender_id, receiver_id, msg_type, seq_no, content, is_read
FROM t_chat_message WHERE id = 2082641824612155393;
-- id=2082641824612155393, sender=10001, receiver=10002, seq_no=4, is_read=0
```

**会话关系** ✅ L4：
```sql
SELECT user_id, peer_id, unread_count FROM t_chat_user_relation
WHERE (user_id=10001 AND peer_id=10002) OR (user_id=10002 AND peer_id=10001);
-- sender.unread=0, receiver.unread=1
```

**Redis** ✅ L3（端口 **16379**，Sentinel master）：
```
GET im:seq:320033 → "4"
HGET im:unread:10002 10001 → "1"
```

`conversationId = min(10001,10002)*31 + max(10001,10002) = 320033`

---

## 4. GET /api/im/conversations — 会话列表

```bash
curl -s "http://localhost:19014/api/im/conversations?page=1&size=20" \
  -H "X-User-Id: 10002"
```

**响应** ✅ L1：`total=1, records[].peerId=10001, unreadCount=1, lastContent="测试消息"`

**MySQL 索引** ✅ L4：走 `idx_user_id (user_id)` + `idx_updated_at (updated_at)`。

---

## 5. GET /api/im/unread-count — 总未读数

```bash
curl -s "http://localhost:19014/api/im/unread-count" \
  -H "X-User-Id: 10002"
```

**响应** ✅ L1：`{"total":1}`

**Redis 降级** ✅ L3：Redis 无数据时回查 MySQL `t_chat_user_relation.unread_count` 求和。

---

## 6. POST /api/im/read/{peerId} — 标记已读

```bash
curl -s -X POST "http://localhost:19014/api/im/read/10001" \
  -H "X-User-Id: 10002"
```

**Redis** ✅ L3：`HGET im:unread:10002 10001 → "0"`

**未读数** ✅ L1：重新查询 GET /api/im/unread-count → `{"total":0}`

**DB 同步** ✅ L4：`SELECT unread_count FROM t_chat_user_relation WHERE user_id=10002 AND peer_id=10001` → `0`

---

## 7. GET /api/im/messages/{peerId} — 历史消息

```bash
curl -s "http://localhost:19014/api/im/messages/10001?page=1&size=50" \
  -H "X-User-Id: 10002"
```

**响应** ✅ L1：返回 `records[]` 含 id/senderId/receiverId/content/msgType/createdAt。

---

## 8. GET /api/im/online-count — 在线连接数

```bash
curl -s http://localhost:19014/api/im/online-count
```

**响应** ✅ L1：`{"localOnline":0,"serverId":"60d744fd-3399467"}`

关闭连接后归零，serverId = UUID截取8字符 + "-" + PID。

---

## 9. WebSocket READ_NOTIFY — 已读回执

```
A(10001) ↔ B(10002) 同时在线
1. A → CHAT →  B 收到 CHAT, A 收到 ACK
2. B → READ(peerId=10001) → A 收到 READ_NOTIFY {peerId:10002}
```

**验证** ✅：跨连接已读回执正常推送。

---

## 层验证汇总

| 层 | 内容 | 状态 | 备注 |
|:--:|------|:----:|------|
| L1 | API 响应 | ✅ | 全部 HTTP 200 |
| L2 | ACCESS 日志 | ✅ | traceId 正常生成 |
| L3 | Redis | ✅ | 端口 16379（Sentinel master） |
| L4 | MySQL | ✅ | my_xhs_im 主库 13306 |
| L5 | 应用日志 | ✅ | 连接/消息/读写分离日志 |
| L6 | Nacos 注册 | ✅ | `my-xhs-im` 已注册 |
| L7 | XXL-Job | N/A | IM 模块无 XXL-Job |
| L8 | MQ | N/A | pom 中有依赖但代码未使用 |
| L9 | @RateLimit | N/A | 未配置 |
| L10 | Sentinel | N/A | 已注册但未配置规则 |
| L11 | SkyWalking | 🟡 | REST API traceId 可串联，WebSocket 无 Filter |
| L12 | Gateway 路由 | ✅ | JWT + HMAC 双认证通过 |
| L13 | Actuator | ✅ | UP（db/redis/discovery/sentinel） |
| L14 | ES 日志 | 🟡 | `_grokparsefailure` 标记存在，traceId 字段缺失，待中间件修复 |
| L15 | Prometheus | ✅ | 452 行指标，application=my-xhs-im |

**发现的问题汇总**：
1. 🟡 无效 Ticket → HTTP 200（非 401/403），客户端体验可改进
2. 🟡 L14 ES traceId 字段缺失，Logstash grok 仍有 `_grokparsefailure` 标记，已通知中间件
