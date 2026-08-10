# 11-notification 通知服务 — curl 测试记录

> 端口：19013 | 数据库：my_xhs_notification | 测试日期：2026-07-29

---

## Gateway + JWT + HMAC per-session 重测（2026-08-03）

> 测试时间：2026-08-03 15:24-15:25
> 测试入口：Gateway 19000
> 认证方式：JWT + HMAC per-session secret + X-User-Id header
> 测试用户：notif_* (register→login, userId=2084178708358258690)
> 测试脚本：`scripts/test-11-notification.py`

### 12 个用例结果（9 端点 + 2 异常）

| # | 用例 | HTTP | code | msg | 结果 |
|:--:|------|:--:|:--:|------|:--:|
| 1 | GET 通知列表 | 200 | 200 | 操作成功 | ✅ |
| 2 | GET 未读数 | 200 | 200 | 操作成功 | ✅ |
| 3 | POST 全部已读 | 200 | 200 | 操作成功 | ✅ |
| 4 | POST 发送通知(dev) | 200 | 200 | 操作成功 | ✅ |
| 5 | GET 列表(验证创建) | 200 | 200 | 操作成功 | ✅ |
| 6 | POST 标记已读 | 200 | 200 | 操作成功 | ✅ |
| 7 | POST 按类型已读 | 200 | 200 | 操作成功 | ✅ |
| 8 | POST SSE Ticket | 200 | 200 | 操作成功 | ✅ |
| 9 | GET SSE 连接 | 200 | — | established | ✅ |
| 10 | GET 在线人数 | 200 | 200 | 操作成功 | ✅ |
| 11 | 异常: SSE 缺 ticket | 400 | 40001 | 缺少参数 | ✅ |
| 12 | 异常: 读不存在通知 | 200 | 404 | 通知不存在 | ✅ |

**12/12 全部通过**。

### 模块特性

- **无请求体 DTO**：8 个业务端点全走 `X-User-Id` Header + Query/Path 参数，唯一 JSON body 是 dev 环境 `NotificationEventDTO`
- **无校验注解**：模块内 grep `@Valid`/`@NotNull` 等零命中，请求合法性完全依赖调用方
- **无 Feign 出站调用**：notification 只消费 RocketMQ，不主动调其他服务（home 模块反向 Feign 调用其 `/unread-count`）
- **SSE 两步法**：`POST /sse/ticket`（需 JWT+HMAC 获取 30s ticket）→ `GET /sse?ticket=xxx`（ticket 鉴权，Gateway 白名单直通）
- **Gateway 白名单修复**：SSE 端点 + IM `/online-count` 原不在白名单。已修复两处（`white-list` + `hmac-white-list`）

### MySQL（端口 13306）

- `my_xhs_notification.t_notification`：`id=2084178709025136641`, `is_read=1`（test/send → read/{id} 链路确认）✅
- 字段含 `is_aggregated` + `aggregate_id` (Lua 聚合未读)、`notify_date` (日期维度聚合分桶)

### traceId 全链路

- traceId=`8030f02fcc5a4c8ab566973e4a159902`
- Gateway：`>>> GET /api/notification/list` → 鉴权通过
- Notification：`[ACCESS] GET /api/notification/list, status=200, rt=5ms`
- **Gateway ↔ Notification traceId 一致** ✅

### 工程知识点

1. **SSE 测试策略**：SSE 是长连接，Gateway 有 3s response-timeout。测试只需验证 HTTP 200 连接建立，不需等事件推送
2. **No DTO Design**：模块设计极简——所有业务端点不含 `@RequestBody`，验证完全靠调用方 + Service 层
3. **dev Profile 依赖**：`NotificationTestController` 是 `@Profile("dev")`，生产不可用。测试依赖它创建通知数据
4. **read-all 幂等**：无通知时调用不报错，返回 200（SQL UPDATE 影响 0 行不抛异常）

### 13 层验证汇总

| 层 | 验证项 | 结果 |
|:--:|------|:--:|
| L1 | API 响应 HTTP 200 + code 200 | ✅ |
| L2 | Gateway ↔ Notification traceId 一致 (8030...) | ✅ |
| L3 | Redis：SSE ticket 30s TTL + 在线计数 | ✅ |
| L4 | MySQL：t_notification 创建→标记已读 is_read=1 | ✅ |
| L5 | ACCESS 日志 + xxl-job 心跳 | ✅ |
| L6 | MQ：N/A（notification 接受 MQ，本测试走 dev send 直接调 Service） | N/A |
| L7 | SSE 两步法（ticket→connect） | ✅ |
| L8 | 异常用例：40001（缺 ticket）+ 404（不存在通知） | ✅ |
| L9 | @RateLimit：N/A（notification 未配置限流） | N/A |
| L10 | Sentinel：N/A | N/A |
| L11 | SkyWalking：N/A | N/A |
| L12 | Gateway JWT auth + HMAC per-session secret（含 SSE） | ✅ |
| L13 | Actuator /health UP | ✅ |

## 测试用例概览

| # | 接口 | 方法 | 说明 | 状态 |
|:--:|---|---|---|---|
| 1 | `/api/notification/sse/online-count` | GET | SSE 在线连接数 | ✅ |
| 2 | `/api/notification/sse/ticket` | POST | 获取 SSE Ticket | ✅ |
| 3 | `/api/notification/sse` | GET | SSE 长连接 | ✅ |
| 4 | `/api/notification/test/send` | POST | 模拟通知事件 | ✅ |
| 5 | `/api/notification/unread-count` | GET | 未读计数 | ✅ |
| 6 | `/api/notification/list` | GET | 通知列表 + 分页 | ✅ |
| 7 | `/api/notification/read/{id}` | POST | 单条已读 | ✅ |
| 8 | `/api/notification/read-by-type/{type}` | POST | 按类型已读 | ✅ |
| 9 | `/api/notification/read-all` | POST | 全部已读 | ✅ |

---

## 1. GET /api/notification/sse/online-count

```bash
curl -s http://localhost:19013/api/notification/sse/online-count
```

**响应** (L1)：
```json
{"code":200,"data":{"onlineCount":0},"success":true}
```
HTTP 200，body 正常。

**ACCESS 日志** (L2)：
```
[ACCESS] GET /api/notification/sse/online-count, status=200, rt=10ms, ip=127.0.0.1
traceId=7e9d49e597b34c70b3e3d20065ff3543
```
traceId 正常生成，rt=10ms。

**应用日志** (L5)：无需额外业务日志——该接口仅返回 `emitters.size()`。

**Nacos 注册** (L6)：`my-xhs-notification` 已在 Nacos 注册（health check 确认）。

**Actuator 健康检查** (L13)：
```
GET /actuator/health → {status: UP, db: UP, redis: UP, nacos: UP, sentinel: UP}
```
全部组件正常。

**ES traceId 索引** (L14)：✅ 已由中间件团队修复——Logstash grok 提取 [32位hex]，traceId 作为独立字段索引

**Prometheus 指标** (L15)：✅ 329 指标行正常暴露，`application="my-xhs-notification"`

**工程分析**：
- 这是一个无认证的调试接口，返回 `ConcurrentHashMap.size()`，O(1) 内存操作
- 多实例部署时只反映**本实例**的连接数，不是全局在线数——要获取全局需要 SUM(所有实例的 onlineCount)
- 未使用 Redis 路由（`notify:sse:{userId}` keys 计数）——后者可以拿到全局在线数

---

## 2. POST /api/notification/sse/ticket

```bash
curl -s -X POST http://localhost:19013/api/notification/sse/ticket \
  -H "X-User-Id: 10001"
```

**响应** (L1)：HTTP 200，ticket=32位UUID，expiresIn=30s

**ACCESS** (L2)：traceId 生成正常，rt=15ms

**Redis** (L3)：`notify:sse:ticket:{ticket}` = "10001", TTL 符合 30s ✅

**ES traceId** (L14)：✅ 已验证——中间件团队修复 Logstash grok，traceId 作为独立字段可精确检索

**工程分析**：
- `getAndDelete` 保证 Ticket 一次性使用（Redis 单线程原子操作）
- 30 秒 TTL：正常 SSE 连接耗时 < 5 秒，兜底足够
- 无 JWT 验证，依赖 Gateway HMAC 签名做入口过滤

---

## 3. GET /api/notification/sse — SSE 长连接

> 先 POST /sse/ticket → 再 GET /sse?ticket=…

**SSE 事件流** (L1)：`event:connected data:{"msg":"SSE连接建立成功"}`

**Redis 路由** (L3，连接存活期间)：
```
notify:sse:10001 = "21.214.97.212:19013"    ← serverId 正确
notify:sse:ticket:{ticket} = None             ← getAndDelete 一次性消费 ✅
```

**App 日志** (L5)：`[SSE] 连接建立: userId=10001, 当前在线=1`

**ES traceId** (L14)：`traceId=dd10dca3...` → ES 精确命中

**工程分析**：
- `SseEmitter(0L)` 永不超时，心跳保活
- 断开后 onCompletion/onError/onTimeout 清理 Redis key
- Ticket 的 `getAndDelete` 保证一次性使用——截获后无法重用

---

## 4. POST /api/notification/test/send — 模拟点赞通知

```bash
curl -X POST http://localhost:19013/api/notification/test/send \
  -d '{"type":1,"senderId":10002,"senderName":"测试用户B","targetUserId":10001,"targetId":100}'
```

**响应** (L1)：HTTP 200

**ACCESS** (L2)：traceId=c55bde24b7…, rt=222ms

**MySQL** (L4)：
```
id=2082390138970755073, type=1, title="点赞通知"
content="测试用户B 赞了你的笔记" ← {sender} 模板渲染正确
aggregate_count=1, is_read=0
```

**Redis** (L3)：
```
notify:unread:10001 = 1           ← 新建通知 → INCR
notify:agg:10001:1:100 = 2082390138970755073  ← 窗口锁，值与 MySQL ID 一致
```

**App 日志** (L5)：`[测试] 模拟 → [通知] 处理完成: targetUserId=10001, type=1`

**ES traceId** (L14)：✅ traceId=c55bde24b7… → ES 命中

**工程分析**：
- 模板渲染链路：`PushTemplate.selectByType("LIKE")` → `{sender} 赞了你的笔记` → replace → `测试用户B 赞了你的笔记`
- `processEvent` 用 `result.getId().equals(notification.getId())` 判断是否新建——新建时 INCR，聚合时不 INCR
- 聚合窗口 Key `10001:1:100` = 主通知 ID——后续同窗口通知用此 ID 更新 aggregate_count

---

## 5. GET /api/notification/unread-count

```
GET /api/notification/unread-count, X-User-Id: 10001
```

**响应** (L1)：`{"total":1,"details":{"1":1}}` — 与 Redis `notify:unread:10001=1, notify:unread:type:10001={1:1}` 一致

**工程分析**：未读数从 Redis String + Hash 读取（非 MySQL COUNT），O(1)。details 只包含 count>0 的类型，空的 type field 不返回。

---

## 6. GET /api/notification/list

```
GET /api/notification/list?page=1&size=5, X-User-Id: 10001
```

**响应** (L1)：total=4, pages=1, 4 条按 created_at DESC
- 聚合标题正确（"等2人赞了你的笔记" "等2人评论了你的笔记"）
- 模板渲染正确（"{sender} 赞了你的笔记" → "测试用户B 赞了你的笔记"）

**工程分析**：`selectPage + LambdaQueryWrapper`，索引命中 `idx_user_id_created`。已读通知仍出现在列表中（列表用 deleted 过滤，不用 is_read）。

---

## 7. POST /api/notification/read/{id}

```
POST /api/notification/read/2082390138970755073, X-User-Id: 10001
```

**响应** (L1)：HTTP 200

**Redis** (L3)：`total 1→0, type {1:1}→{1:0}` — Lua SAFE_DECR ✅

**MySQL** (L4)：`is_read 0→1` ✅

**ACCESS** (L2)：traceId=c7f569f45c…, rt=8ms

**工程分析**：幂等设计——`markAsRead()` 先判断 `is_read==1`，已读直接返回避免重复 DECR。Lua `SAFE_DECR` 保证并发时不会减为负数。

---

## 8. POST /api/notification/read-by-type/{type}

先发送一条 type=2 通知，再 `/read-by-type/2`

**响应** (L1)：HTTP 200

**Redis** (L3)：`total=0, type={1:0,2:0}` — Lua RESET_BY_TYPE 原子减法 ✅

**MySQL** (L4)：2 条 type=2 通知全部 `is_read=1`

**工程分析**：Lua 保证 `HGET→DECRBY总→HSET 0` 三步原子，防止并发导致总未读多减。

---

## 9. POST /api/notification/read-all

先发送 type=3 通知 → `POST /api/notification/read-all`

**响应** (L1)：HTTP 200

**Redis** (L3)：`total=None, type={}` — 直接 DELETE 两个 Key ✅

**MySQL** (L4)：`total=6, unread=0` ✅

**工程分析**：`resetUnread()` 直接删除 Redis Key，不保留 0 值。下次 INCR 自动重建。`markAllAsRead()` 用 `UPDATE ... WHERE is_read=0` 条件更新避免无效写入。

---

## L7 XXL-Job：unreadReconcileJob 对账验证

在 Admin 创建 JobGroup + Job（handler=unreadReconcileJob），触发执行：

- 人为设 Redis `notify:unread:10001=999` → 触发对账 → Redis 修复为 DB 值 `2`
- 日志：`[对账] 修复: userId=10001, redis=999, db=2` ✅
- 日志：`[对账] 完成: 检查2个用户, 修复1个` ✅

---

## L8 MQ 消费者验证

通过 `MqTestProducer` 发送 RocketMQ 消息到 `NOTIFICATION_TOPIC`：

- 消费线程：`ConsumeMessageThread_notification-event-consumer-group_1` ✅
- 处理日志：`[通知] 处理完成: targetUserId=10001, type=3, senderId=10002` ✅
- MySQL：通知记录写入（sender=`MQ测试用户`, type=3 FOLLOW） ✅
- 幂等：msgId 去重 (Redis 24h) + 跳过自己给自己的通知

---

## L10 Sentinel 流控验证

通过 Sentinel Dashboard API 创建 QPS=1 流控规则（`/api/notification/unread-count`）：

- 第 1 次请求：HTTP 200 ✅
- 第 2 次请求：`Blocked by Sentinel (flow limiting)` HTTP 429 ✅
- Sentinel transport 端口 8731、Dashboard 8858 通讯正常

---

