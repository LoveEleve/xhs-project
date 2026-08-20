# G7-01 通知用例（事件/聚合/列表/已读/未读/SSE/对账）

> 组：G7 通知IM计数 | 服务：notification(19013) + 联动 analytics/content（事件源）| 入口：**gateway(19000)**
> 依赖：G1 登录 + G2 社交（点赞/评论/关注触发通知事件）
> 时间引用：矩阵 **#5**（unreadReconcileJob xxl 每5分钟）、**#28**（SSE ticket 30s 一次性）、**#2**（心跳 10s）、**#26**（限流窗口）、**#23**（模板缓存）
> 前提：notification UP、xxl 任务 5 存在启用（组 5，每5分钟）、**notification 以 dev profile 启动（N09-test-send 测试端点可用——start-all.sh JAVA_OPTS_DEV 实证）**

## 代码实证（2026-08-14，G7 梳理全量核实）

### 端点与安全（gateway:19000 → notification:19013）
| 端点 | 鉴权 | 说明（代码实证） |
|---|---|---|
| POST /api/notification/sse/ticket | **JWT（免 HMAC）**——**执行实证：hmac-white-list `/api/notification/sse/**` 覆盖 sse/ticket**（初版文档误判需 HMAC）| 生成 30s 一次性 ticket（GETDEL 消费）；无 token → 401 |
| GET /api/notification/sse?ticket= | **JWT 白名单**（ticket 应用层校验）| SSE 长连接（30min 兜底超时/心跳保活）；ticket 无效 → 401"Ticket无效或已过期" |
| GET /api/notification/list | JWT + HMAC | 分页 + type 筛选；size≤50；orderByDesc(createdAt) |
| GET /api/notification/unread-count | **JWT（免 HMAC）** | UnreadCountVO{total, details{type:count}} |
| POST /api/notification/read/{id} | JWT + HMAC | @RateLimit 30/60s；幂等（is_read=1 直接返回）；归属校验（他人 → NOT_FOUND"通知不存在"）|
| POST /api/notification/read-by-type/{type} | JWT + HMAC | @RateLimit 10/60s；Lua 原子重置分类 |
| POST /api/notification/read-all | JWT + HMAC | @RateLimit 5/60s |
| GET /api/notification/sse/online-count | **JWT 白名单 + X-Admin-Call** | 本实例 SSE 在线数 |
| POST /api/notification/test/send | **dev profile**（N09）| 直接投递 NotificationEventDTO（绕过 MQ——dashboard 403 教训 #14）|

### 事件处理链路（NotificationService.processEvent，代码实证）
`NotificationEventConsumer`（NOTIFICATION_TOPIC，notify-consumer-group）→ processEvent：
1. **buildNotification**：查 PushTemplate（`t_push_template` selectByType，**本地缓存**）→ 模板渲染 title（{sender}/{target}/{title}/{content} 占位符）；无模板 → 默认"某用户与你互动"
2. **聚合**（NotificationAggregator.processWithAggregate）：**Key=`myxhs:notification:agg:{uid}:{type}:{targetId}`，TTL=当天剩余秒数（自然日，最短 60s）**——Lua SETNX 原子：
   - 窗口内第一条 → INSERT t_notification（aggregate_count=1, is_read=0）→ Redis 值替换为真实 ID
   - 后续 → incrementAggregateCount（原子）+ buildAggregateTitle（模板聚合标题 {sender}等{count}人{action} 或默认格式）→ updateAggregateTitle
   - PENDING 自旋重试 3 次（50/100/200ms）→ 失败降级独立插入
3. **未读计数**：新建才 increment（Lua 原子 INCR total + HINCRBY type）；聚合更新不加
4. **SSE 推送**：isOnline（本实例）→ pushNotification（notification 事件）+ pushUnreadCount（unread-count 事件）；跨实例 → Redis Pub/Sub

### 未读计数（UnreadCountService，代码实证）
- `myxhs:notification:unread:{uid}`（String total）+ `myxhs:notification:unread:type:{uid}`（Hash type→count）
- increment：Lua ATOMIC_INCR（INCR total + HINCRBY type 原子）
- decrement：Lua SAFE_DECR（**DECR 后不小于 0**）
- resetByType：Lua RESET_BY_TYPE（HGET type → total 减去 → type 归零，原子）
- forceSetUnread（对账修复用）：SET total + HSET 分类
- getUnreadCount：total（String）+ details（Hash，>0 才返回）

### SSE（SseEmitterManager + SseTicketService，代码实证）
- ticket：`myxhs:notification:sse:ticket:{uuid}` 30s TTL；**GETDEL 一次性**（用后即删）
- 连接：emitters ConcurrentHashMap（userId→SseEmitter）；**同用户新连接踢旧**（oldEmitter.complete）；30min 兜底超时；Redis `myxhs:notification:sse:{uid}`=serverId（30s TTL 心跳续期）
- 心跳：@Scheduled 10s——Pipeline 批量续期 + 发送 `heartbeat` 事件（发送失败清理连接）
- 事件推送：connected（建连时）/notification/unread-count/heartbeat
- 跨实例：Redis Pub/Sub channel `myxhs:notification:sse:channel`（SseCrossInstanceSubscriber）；单实例环境验证路由 key 即可

### 对账（UnreadReconcileJob，代码实证）
- xxl#5 unreadReconcileJob（组 5，`0 0/5 * * * ?` 每5分钟，trigger_status=1）
- 扫描 t_notification is_read=0（**id 游标**——坑 #51 教训，非 userId 游标）→ 按 userId+type 计数 → 与 Redis 对比 → 不等则 **forceSetUnread（以 DB 为准）**；LIMIT 5000；批间 sleep 50ms
- handleSuccess"对账完成，检查 N 个用户，修复 M 个"

### 模板表（t_push_template）
- 预置数据（LIKE/COMMENT/FOLLOW/SYSTEM/ORDER 五类 title_template/content_template/aggregate_title_template）——**执行前确认行存在**（对方部署含 seed）

### 错误码
200 / 401（ticket 无效）/ 403（管理/签名）/ NOT_FOUND 404"通知不存在"（他人或不存在）

## 前置准备（执行时记录快照）
```python
from testlib import *
# 1. 注册用户 g7n_（接收者）+ g7s_（发送者）——或复用 G1/G2 用户
# 2. 清理：DEL myxhs:notification:unread* + myxhs:notification:agg* + myxhs:notification:sse*
#    + DELETE FROM my_xhs_notification.t_notification WHERE user_id IN (...)
# 3. 确认 t_push_template 有 5 类模板（SELECT * FROM my_xhs_notification.t_push_template）
```

## 用例清单

### G7-01-01 通知事件全链路（核心——test/send 直投）
- **前置**：g7n_ 接收者、g7s_ 发送者；模板就绪
- **入口**：`POST /api/notification/test/send`（**dev profile；需 JWT+HMAC**）body=`{"type":1,"senderId":{g7s},"senderName":"g7sender","targetUserId":{g7n},"targetId":{noteId},"targetType":1,"targetName":"G7测试笔记","content":"点赞内容"}`（type=1 点赞）
- **L1**：200
- **L2**（等 1-3s）：
  ```
  # ① t_notification 落库：user_id/**title="点赞通知"（t_push_template.title_template 原样，无占位符——运行库实证）**/content=模板渲染（content_template="{sender} 赞了你的笔记"）/sender_name/type=1/is_read=0/aggregate_count=1
  # ② 未读计数：GET myxhs:notification:unread:{g7n.uid}=1；HGET type key 1=1
  # ③ 列表可见：GET /api/notification/list → 含该通知
  ```
- **🔍 人工观察**：Kibana notification 日志（模板渲染/聚合/未读递增）
- **注意**：test/send 走的是 processEvent 同代码路径（非 MQ）——MQ 真实链路由 G2 社交事件覆盖（G2-03 点赞→通知已验证）

### G7-01-02 通知聚合（当天窗口）
- **前置**：G7-01-01 已产生主通知（aggregate_count=1）
- **步骤**：再投 2 次同类型同目标事件（不同 senderName，如 "g7sender2"/"g7sender3"）
- **L2**（等 1-3s）：
  ```
  # ① 主通知 aggregate_count=3（incrementAggregateCount 原子）
  # ② 标题更新：buildAggregateTitle（**aggregate_title_template=NULL → 默认格式 "{sender}等{count}人{action}"**；如 "g7sender3 等3人赞了你的笔记"——LIKE defaultAction）
  # ③ t_notification 仅 1 行（该 uid+type+targetId）——被聚合事件不落库（存储层聚合实证）
  # ④ 未读计数 total=1 不变（聚合更新不增加——代码实证）
  ```
- **窗口语义**：Key TTL=当天剩余秒——**跨自然日测试**（23:59 边界）标注：次日事件生成新主通知
- **清理**：DEL agg key + 删 t_notification 测试行

### G7-01-03 通知列表（分页/筛选）
- **前置**：构造 3+ 条通知（含 type=1/2）
- **入口**：`GET /api/notification/list?page=1&size=2`（JWT+HMAC）
- **L1**：200；records=2；total≥3；orderByDesc(createdAt)（最新在前）
- **筛选**：`?type=2` → 仅 type=2；size=999 → 截断 50
- **归属**：他人 token 查 → total=0（userId 隔离）

### G7-01-04 未读计数
- **前置**：2 条未读（type=1×1 + type=2×1）
- **入口**：`GET /api/notification/unread-count`（**JWT 免 HMAC**）
- **L1**：200；data.total=2；data.details={1:1, 2:1}
- **L2**：Redis 核对（total String=2；Hash type=2 个 field）；**无未读 → total=0 + details={}**
- **无 token → 401；无签名 → 200（免 HMAC 实证）**

### G7-01-05 标记单条已读
- **入口**：`POST /api/notification/read/{id}`（JWT+HMAC）
- **L1**：200
- **L2**：t_notification is_read=1；未读 total 1→0、type Hash 对应 -1（Lua 防负）
- **幂等**：重复 read → 200（is_read=1 直接返回，**未读不再减**——total 不 <0）
- **归属负面**：他人通知 id → 404"通知不存在"；不存在 id → 404
- **限流**：连打 31 次 → 第 31 次 **40202**（30/60s）

### G7-01-06 按类型/全部已读（Lua 原子）
- **前置**：未读 total=3（type1×2 + type2×1）
- **入口**：`POST /api/notification/read-by-type/1` → L2：type1 Hash=0、total=1（Lua RESET_BY_TYPE 原子）
- `POST /api/notification/read-all` → L2：total=0、Hash 空
- **限流**：readByType 10/60s、readAll 5/60s（各自连打超限 → 40202）

### G7-01-07 SSE ticket（30s 一次性，矩阵 #28）
- **入口**：`POST /api/notification/sse/ticket`（JWT+HMAC）
- **L1**：200；data.ticket 非空、data.expiresIn=30
- **L2**：`myxhs:notification:sse:ticket:{ticket}` 存在 TTL≈30
- **一次性**：GET /api/notification/sse?ticket={ticket} 消费后 → Redis key 已删（GETDEL）；**再用同一 ticket 建连 → 401"Ticket无效或已过期"**
- **过期**：DEL ticket key → 建连 → 401（#28 ③ 操纵）

### G7-01-08 SSE 连接 + 心跳（核心）
- **前置**：拿新 ticket
- **入口**：`GET /api/notification/sse?ticket={ticket}`（**JWT 白名单，带 token 即可**）
- **L1 断言**：HTTP 200、Content-Type text/event-stream；首个事件 `event: connected`（"SSE连接建立成功"）
- **L2**：`myxhs:notification:sse:{uid}`=serverId（TTL 30s 心跳续期）
- **心跳**：保持连接 12-15s → 收到 `event: heartbeat`（ts）；Redis key TTL 刷新（**续期实证**）
- **断开**：客户端关闭 → 10s 内 key 消失（onCompletion 清理 + 心跳清理双路径）
- **🔍 人工观察**：Kibana"[SSE] 连接建立/心跳完成"日志
- **注意**：urllib 长连接需线程读（或 socket readline 带超时）；**建连后 30s 内完成断言**（TTL 由心跳续期维持）

### G7-01-09 SSE 实时推送（在线时新通知）
- **前置**：g7n_ SSE 连接保持中
- **步骤**：投新通知（test/send type=3 关注）
- **L2**（等 1-3s）：SSE 流收到 `event: notification`（NotificationVO JSON：id/type/title/isRead/aggregateCount）+ `event: unread-count`（total/details）
- **离线对照**：断开连接 → 投通知 → **无推送**（pushNotification 返回 false 分支——通知列表可查，SSE 无事件）
- **🔍 人工观察**：Kibana"[通知] 处理完成" + SSE 推送日志

### G7-01-10 SSE 跨实例路由（单实例标注）
- **L1 代码实证**：pushNotification 三步（本实例直推 → Redis 路由存在→Pub/Sub 跨实例 → 不在线 false）；SseCrossInstanceSubscriber 消费 channel `myxhs:notification:sse:channel`
- **单实例环境**：只验证路由 key 存在（`myxhs:notification:sse:{uid}`=serverId）——跨实例推送无法实测（标注 L1+运行态部分）

### G7-01-11 未读对账（unreadReconcileJob xxl#5，核心）
- **构造漂移**（③ 操纵）：SQL 造 2 条未读通知（is_read=0）+ **DEL Redis 未读 key**（模拟丢失）→ 当前 unread-count=0 ≠ DB=2
- **触发**：xxl admin API 手动触发 id=5
- **L2**：
  ```
  # ① xxl_job_log：trigger_code=200 + handle_code=200"对账完成，检查 N 个用户，修复 1 个"
  # ② Redis 未读恢复：total=2 + type Hash（以 DB 为准 forceSetUnread 实证）
  ```
- **反向漂移**：Redis 手改 total=99（高于 DB）→ 触发 → total 修正回 DB 值（2）
- **幂等**：无漂移时触发 → 修复 0 个

### G7-01-12 通知测试端点（N09，dev profile）
- **入口**：`POST /api/notification/test/send` 缺 type/targetUserId → **40002**（@NotNull）
- **profile**：非 dev 不加载（@Profile("dev")——运行环境=dev 实证）；无签名 → 403（JWT+HMAC）

### G7-01-13 通知鉴权矩阵（安全）
- ① `/api/notification/list`、`read/*`、`read-by-type/*`、`read-all`、`sse/ticket` 无 token → **401**；带 token 无 HMAC → **403**
- ② `/api/notification/unread-count` 带 token 无 HMAC → **200**（免 HMAC）
- ③ `/api/notification/sse` 无 token → **200 放行到应用层**（JWT 白名单）→ 无 ticket → 401"Ticket无效或已过期"
- ④ `/api/notification/sse/online-count` 无 X-Admin-Call → 403
- ⑤ 未知路径 → 404

### G7-01-14 通知限流（矩阵 #26）
- read 30/60s、readByType 10/60s、readAll 5/60s——各连打 N+1 → 40202；**执行前 DEL 限流 key**（`myxhs:notification:read:NotificationController:markAsRead:{uid}` 等）

## 执行记录
| 用例 | 时间 | 结果 | 问题/备注 |
|---|---|---|---|
| G7-01-01 通知事件全链路 | 19:0x | ✅ | test/send 直投；title=模板原样/聚合标题默认格式实证；senderId 可选 |
| G7-01-02 聚合 | 19:05 | ⚠️（T-098） | 3 次 1 行/未读不加/agg TTL ✅；**标题 count 偶发滞后（等2人 vs count=3，从库竞态 T-098 待修）** |
| G7-01-03 列表 | 19:08 | ✅ | 分页/筛选/归属隔离 |
| G7-01-04 未读 | 19:09 | ✅ | total/details/免 HMAC |
| G7-01-05 单条已读 | 19:10 | ✅ | 乐观锁/幂等/他人 404 |
| G7-01-06 按类型/全部已读 | 19:11 | ✅ | Lua 原子归零 |
| G7-01-07 SSE ticket | 19:12 | ✅ | 30s/GETDEL 一次性；复用=HTTP200+body401 |
| G7-01-08 SSE 连接+心跳 | 19:14 | ✅ | connected/heartbeat 10s/续期/断开清理异步 |
| G7-01-09 SSE 实时推送 | 19:16 | ✅ | notification+unread-count 事件 |
| G7-01-10 跨实例 | — | L1 | 单实例标注 |
| G7-01-11 对账 xxl#5 | 19:18 | ✅ | 双向修复/检查1修复1 |
| G7-01-12 test 端点 | 19:19 | ✅ | 40002 校验 |
| G7-01-13 鉴权 | 19:20 | ✅ | sse/ticket 免 HMAC 实证 |
| G7-01-14 限流 | 19:21 | ✅ | read 31→40202/readAll 6→40202 |

## 断言关键词速查
- 200 / 401（ticket 无效）/ 403 / 404（通知不存在）/ 40002（事件 DTO）/ 40202（限流）
- 关键 L2：t_notification 落库+聚合、未读 Lua 三脚本（INCR/SAFE_DECR/RESET_BY_TYPE）、agg key 当天 TTL、SSE connected/heartbeat/notification 事件、ticket GETDEL 一次性、xxl#5 handle 200

## 深度 REVIEW 补充（2026-08-14 第一轮，代码实证核对）
### L0/L1 已核
- ✅ 端点安全矩阵（8 端点 + test 端点 dev profile；unread-count 免 HMAC、sse 白名单+应用层 ticket）
- ✅ 事件链路 4 步（模板渲染/聚合 Lua SETNX/未读原子三脚本/SSE 三路推送）
- ✅ 聚合窗口=当天剩余秒（自然日，最短 60s）+ PENDING 自旋 3 次 + 降级独立插入
- ✅ 未读 Lua：ATOMIC_INCR/SAFE_DECR（防负）/RESET_BY_TYPE（原子归零）
- ✅ SSE：ticket GETDEL 一次性 30s、同用户踢旧、心跳 10s 续期+清理、跨实例 Pub/Sub
- ✅ 对账：id 游标（坑 #51）、以 DB 为准、批间 50ms、LIMIT 5000
- ✅ xxl#5 运行库确认（组 5、每5分钟、trigger_status=1）

### 第五轮执行实证修正（2026-08-14，G7-01 执行中发现）
- ✅ **sse/ticket 免 HMAC**（初版分析漏 `/api/notification/sse/**` Ant 通配覆盖——执行实证 200）
- ✅ **NotificationEventDTO 校验范围**：仅 type/targetUserId @NotNull——**senderId/senderName 等可选**（缺 senderId 投递成功，模板默认"某用户"）
- ✅ **复用 ticket 响应格式**：HTTP 200 + body `{"code":401,"message":"Ticket无效或已过期"}`（SSE 端点异常格式——非 HTTP 401，断言按 body code）
- ✅ **SSE 断开清理异步**：socket 断开后 key 数秒内清理（onCompletion 异步；30s TTL 兜底）——非立即
- ✅ **心跳续期**：10s 周期 TTL 复位实测（TTL 采样含复位到 29/30）
- ✅ **未读对账双向修复**（xxl#5）：Redis=0→DB 回填（4 条）、Redis=99→DB 修正；handle 200"检查 1 个用户，修复 1 个"
- ✅ **限流实测**：read 31 次→40202、readAll 6 次→40202

### 第四轮深度 REVIEW（2026-08-14，链路实测——执行前置验证）
- ✅ **SSE 链路实测全通**：ticket 签发（expiresIn=30、Redis TTL=30）→ gateway 建连（HTTP 200 + text/event-stream）→ 收到 `connected` 事件（"SSE连接建立成功"）→ 路由 key `myxhs:notification:sse:{uid}`=serverId（实测 **21.214.97.212:19013**）TTL 30s——G7-01-07/08/09 前置就绪
- ✅ **NotificationMapper SQL 语义**：markAsRead 乐观锁（`is_read=0 AND deleted=0` 才 UPDATE——幂等依赖）；markAllReadByType/markAllAsRead 同语义；incrementAggregateCount 原子自增 + updateAggregateTitle 分离（并发安全）
- ✅ **UnreadCountVO 序列化**：details=Map<Integer,Integer>（Jackson key→字符串，如 {"1":1}）——断言用 `data.details["1"]`（R4 字符串 key）

### 第三轮深度 REVIEW（2026-08-14，环境+代码补充）
- ✅ **notification 运行 profile=dev 实证**（进程 -Dspring.profiles.active=dev）——N09 test/send 可用
- ✅ **notification 无服务端鉴权拦截器**（端口信任模型 #60）——REST 直连 19013 可绕过 gateway（鉴权断言经 gateway 执行）
- ✅ SseCrossInstanceSubscriber channel 确认（`myxhs:notification:sse:channel`，handleCrossInstanceMessageJson 原样 JSON 推送防 Long→Integer 丢失）
- ✅ ImHandshakeInterceptor 拒绝语义：ticket 缺失/类型错误/解析失败 → **return false（握手 403）**

### 第二轮深度 REVIEW（2026-08-14，运行库实证）
- ✅ **t_push_template 5 条预置**（like/comment/follow/system/order）；**title_template="点赞通知"等（无占位符→title 恒模板原样）**；**aggregate_title_template=NULL→聚合标题走默认格式**；content_template 含 {sender} 占位符（content 为 null 时渲染）——G7-01-01/02 断言已按此修正
- ✅ **t_notification 表结构**：is_read/aggregate_count/is_aggregated/aggregate_id/notify_date/deleted 全存在（断言字段有效）
- ✅ **NotificationEventConsumer MQ 路径差异**（test/send 绕过）：一级幂等 `myxhs:notification:consumed:{msgId}` 24h + **跳过自己给自己通知**（sender==targetUserId）+ 消费失败删幂等标记重试——**MQ 真实链路由 G2 社交事件覆盖，G7 用 test/send 聚焦业务逻辑**
- ✅ xxl#5 运行库确认（组 5、每5分钟、trigger_status=1）

### 风险/观察项
- [ ] 聚合窗口自然日语义（跨日产生新主通知——测试标注边界）
- [ ] test/send 绕过 MQ（与真实链路差一个 MQ 环节——G2 社交事件补 MQ 路径）
- [ ] SSE 30min 兜底超时（心跳保活下不触发；标注观察）
- [ ] 跨实例推送单实例无法实测（标注 L1）
- [ ] 模板本地缓存无 TTL（改模板需重启——观察项）

### 待 L2 确认
- [ ] 聚合标题实际渲染值（模板 aggregate_title_template）
- [ ] SSE 心跳 TTL 续期实测
- [ ] 对账 handle_msg 文案
- [ ] 限流 key 格式（#26：{prefix}:{Class}:{method}:{uid}）
