# my-xhs-im 测试执行计划

> 7端点 | 链7 | WebSocket 两步鉴权 + 对端用户必须存在

---

## 一、前置准备

```bash
TOKEN=$(cat /tmp/test_token.txt)

# 确认 im 在线
curl -sf localhost:19014/actuator/health >/dev/null || echo "im DOWN"

# 确认 IM 库和表存在
mysql -h 21.130.247.89 -P 3306 -u root -p'Xhs@2026#MySQL' -N -e "USE my_xhs_im; SHOW TABLES;" 2>/dev/null

# chaintest_u2 作为对端用户 (W03 消息列表需要 peerId)
# 从链1 U14 注册后查看 MySQL 获取 u2 的 userId
PEER_ID=$(mysql -h 21.130.247.89 -P 3306 -u root -p'Xhs@2026#MySQL' -N -e "USE my_xhs_user; SELECT id FROM t_user WHERE username='chaintest_u2';" 2>/dev/null)
echo "PEER_ID (chaintest_u2)=$PEER_ID"
```

## 二、执行顺序

| 顺序 | 端点 | 依赖 | 产出文件 | 正常+异常 |
|:--:|------|------|------|:--:|
| 1 | W01-ws-ticket | Token | execution/im/W01-ws-ticket.md | ✅ 正常 |
| 2 | WS-websocket | W01 ticket | execution/im/WS-websocket.md | ✅ 正常 |
| 3 | W02-conversations | Token | execution/im/W02-conversations.md | ✅ 正常 |
| 4 | W03-messages-peer | Token + PEER_ID | execution/im/W03-messages-peer.md | ✅ 正常 |
| 5 | W04-mark-read | Token + PEER_ID | execution/im/W04-mark-read.md | ✅ 正常 |
| 6 | W05-unread-count | Token | execution/im/W05-unread-count.md | ✅ 正常 |
| 7 | W06-online-count | Token | execution/im/W06-online-count.md | ✅ 正常 |

> WS-websocket 需要 WebSocket 客户端 (浏览器或 `wscat`) — curl 无法直接测试 WS 端点
> W03 需要有对端用户 — pre-test-init Step 9.3 已注册 chaintest_u2

## 三、异常场景

| 场景 | 端点 | 预期 |
|------|------|------|
| 缺少 JWT | W01 | 401 |
| 不存在的 peerId | W03 | `data:[]` (非异常) |
| 未读为空 | W05 | `{"unreadCount":0}` (非异常) |
| 重复取 ticket | W01 | 多次调用返回新 ticket |

---
## 测试要点补充（2026-08-10，实测修正）

- **认证**：走 gateway 19000，JWT+HMAC。
- **WS-websocket**：ws://host/api/im/ws?ticket=，需 **WebSocket 客户端**（websocket-client/wscat），curl 测不了；握手是 HMAC 白名单端点（浏览器WS无法签名，#49 已修）。
- **W06-online-count**：需 `X-Admin-Call`。
- **W03-messages-peer**：GET /api/im/messages/{peerId}，peer 需存在。
- **W04-mark-read**：POST /api/im/read/{peerId}。

---
## L0-L4 逐端点核对清单

### W01-ws-ticket / WS-websocket
- [ ] L0: token+HMAC
- [ ] L1: 拿ticket → 200; WS连接(websocket-client) → handshake OK
- [ ] L2: Redis `myxhs:im:route:{userId}` 在线路由
- [ ] L3: ticket两步鉴权
### W03-messages / W04-read
- [ ] L1: 消息列表/标记已读 → 200
- [ ] L2: MySQL `t_chat_message`
- [ ] L3: 对端存在
