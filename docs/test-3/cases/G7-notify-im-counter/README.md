# G7-notify-im-counter — 通知/IM/计数

> 服务：notification(19013) + im(19014) + counter(19004) + 联动 analytics(19003)/content(19002)/gateway(19000) | 入口：**gateway(19000)**
> 依赖：G1 登录 + G2 社交（点赞/评论/关注产生通知事件与计数事件）+ G6 Feed（未读计数联动）
> 时间引用：矩阵 **#4**（counterReconcileJob xxl 每天3点）、**#5**（unreadReconcileJob xxl 每5分钟）、**#2**（CounterBuffer 5s、SseEmitterManager 心跳 10s）、**#27**（IM 心跳 30s/在线 90s TTL）、**#28**（SSE ticket 30s）、**#26**（本组限流窗口）

## 业务范围
通知：社交事件（点赞/评论/关注/系统/订单）→ MQ 消费 → 模板渲染 → **当天聚合窗口**（同类型同目标合并）→ 未读计数（Redis 原子 Lua）→ SSE 实时推送（ticket 两步鉴权 + 10s 心跳 + Redis 路由跨实例）→ 未读对账（xxl#5）
IM：WebSocket 实时聊天（ticket 两步鉴权 + 写扩散双写事务 + 会话级 seqNo + 路由投递 + 离线消息 + 已读回执 + 心跳保活）→ REST（会话/历史/已读/未读）
计数：**MQ 事件驱动写入**（SOCIAL_TOPIC 10 事件）→ Redis INCR（Lua 去重 2h + 归零保护 + 30 天 TTL）→ **CounterBuffer 攒批 5s 刷盘** t_counter → 查询两级缓存（Redis→MySQL）→ LikeSet 幂等计数 → 对账（xxl#4 + analytics 权威修正）

## 归属定时/联动任务
- **notification 域**：unreadReconcileJob（xxl#5，组 5，每5分钟）；SseEmitterManager.heartbeat（@Scheduled 10s）；NotificationEventConsumer（NOTIFICATION_TOPIC）
- **im 域**：进程内仅心跳（PING 驱动续期，无定时任务）；ImRouteSubscriber（Redis Pub/Sub 每实例专属 channel）
- **counter 域**：CounterBuffer.scheduledFlush（@Scheduled 5s）；CounterReconcileJob（xxl#4，组 3，每天3点）；CounterEventConsumer（SOCIAL_TOPIC 10 事件）

## 用例文档
- **G7-01-notification.md**：事件/聚合/列表/已读/未读/SSE/对账（14 用例）
- **G7-02-im.md**：WS 连接/聊天/离线/会话/已读/在线/踢线/鉴权（12 用例）
- **G7-03-counter.md**：查询/批量/Buffer/dedup/LikeSet/对账/TTL/鉴权（10 用例）

## 关键数据关注矩阵（代码实证 2026-08-14）
| 用例域 | Redis key | MySQL | MQ/其他 |
|---|---|---|---|
| 通知未读 | `myxhs:notification:unread:{uid}`（总 String）+ `myxhs:notification:unread:type:{uid}`（Hash 分类）| t_notification（is_read/aggregate_count）| NOTIFICATION_TOPIC |
| 通知聚合窗口 | `myxhs:notification:agg:{uid}:{type}:{targetId}`（当天剩余秒 TTL，值=主通知 ID）| t_notification（聚合更新主通知 aggregate_count）| — |
| SSE | `myxhs:notification:sse:ticket:{ticket}`（30s 一次性 GETDEL）、`myxhs:notification:sse:{uid}`（30s 心跳续期，值=serverId）| — | Redis Pub/Sub `myxhs:notification:sse:channel` |
| IM 在线路由 | `myxhs:im:route:{uid}`（90s TTL=serverId）、`myxhs:im:online:{uid}`（90s TTL）| — | WS `ws://gw/api/im/ws?ticket=` |
| IM 会话 | `myxhs:im:seq:{conversationId}`（INCR 会话序列号）、`myxhs:im:unread:{uid}`（Hash peerId→未读）、`myxhs:im:offline:{uid}`（ZSet msgId，score=时间，7 天 TTL/1000 上限）| t_chat_message（分片 conversationId）、t_chat_user_relation（unread_count）| Redis Pub/Sub `myxhs:im:route:{serverId}` |
| 计数 | `myxhs:counter:{targetType}:{targetId}:{countType}`（30 天 TTL）、`myxhs:counter:dedup:{msgId}`（2h）、`myxhs:like:set:{targetType}:{targetId}`（Set）| t_counter（Buffer upsert）| SOCIAL_TOPIC：LIKE/UNLIKE/FAVORITE/UNFAVORITE/COMMENT/UNCOMMENT/SHARE/VIEW/FOLLOW/UNFOLLOW |

## 执行纪律（G1-G6 教训 + G7 特有）
- 服务重启必须带 INTERNAL_TOKEN/ADMIN_TOKEN（#79-1）；notification/im/counter 内部 Feign 依赖内部令牌
- **限流（#26 实证）**：notification read 30次/60s、readByType 10次/60s、readAll 5次/60s；im read 20次/60s；counter get **1s 窗口 50 次**（唯一秒级限流！）reconcile 2次/60s——执行前 DEL 限流 key（`myxhs:{prefix}:{Class}:{method}:{uid}`）
- **鉴权矩阵（gateway 实证，见各文档）**：counter get/batch-get **公开免签**；notification sse*、im ws/online-count 在 JWT 白名单；im conversations/unread-count、notification unread-count 免 HMAC；其余 JWT+HMAC
- **写后读延迟**：通知 MQ 消费 1-3s；CounterBuffer 刷盘 5-15s（断言 t_counter 前等）；主从 1-2s
- **SSE 测试**：Python urllib 长连接（readline 超时控制）或 curl `-N --max-time`；ticket 30s 一次性（GETDEL）——**建连前先拿 ticket，30s 内完成**
- **WS 测试**：Python websocket-client（检查可用性）或原生 socket 握手（Sec-WebSocket-Key）；心跳 PING 间隔 ≤90s 否则路由过期
- **IM 未读/通知未读是两套独立体系**（im:unread vs notification:unread）——断言勿混
- 聚合窗口=**当天剩余秒数**（自然日）——同用例多次事件可能跨日合并（断言 aggregate_count 递增）
- xxl 触发后查 xxl_job_log（trigger_code=200 + handle_code + handle_msg）
- **清理（G7 前置，2026-08-14 实测残留）**：t_notification=4 / t_chat_message=15 / **t_chat_user_relation=6（user_id=10001/10002 历史模拟数据）** / t_counter=278 全为测试数据（g6 回归+历史 Task）——执行前 DELETE；Redis G7 域 key；**通知事件测试用 NotificationTestController（dev profile，已确认运行环境）而非 MQ 投递**（dashboard 403 教训 #14）

## 执行记录
| 文档 | 用例 | 时间 | 结果 | 问题/备注 |
|---|---|---|---|---|
| G7-01-notification.md | 14 用例 | 2026-08-15 16:14~16:21 | ✅ 14/14 | 回归（Task9 后）；T-098 修复确认（聚合标题 count 无滞后）；对账双向；SSE 全链路 |
| G7-02-im.md | 12 用例 | 2026-08-15 16:22~16:28 | ✅ 12/12 | 回归；T-094 行为确认（access 冒充经 gateway 101 死隧道，im 层拒绝日志实证）；离线/踢线/心跳全过 |
| G7-03-counter.md | 10 用例 | 2026-08-15 16:29~16:34 | ✅ 10/10 | 回归；Buffer 刷盘/去重/LikeSet 权威/归零/对账双向（含 analytics 权威修正）；秒级限流 |

> **36/36 全绿**。登记 T-094~098（观察项）；执行修正：sse/ticket、ws/ticket 均免 HMAC（Ant 通配覆盖初版判断）、ImMessageVO 无 seqNo、SSE 断开清理异步、reconcile 限流优先鉴权。
> **2026-08-14 全量回归（第二次）36/36 全绿**：新增 T-098。
> **2026-08-14 第三轮全量回归（第三次，脏数据再次清零后）36/36 全绿零失败**：T-098 稳定复现确认；对账语义确定性验证（Redis 缺失→回填 DB）；SSE 断开测试改 socket 方式（urllib close 不稳）。数据已清理（MySQL 7 表 0、Redis G7 域 0）。
