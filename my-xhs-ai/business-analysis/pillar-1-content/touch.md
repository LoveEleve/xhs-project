# 用户触达：通知与私信

## 业务问题（AI 能回答）
- 通知发送量、已读率、推送成功率/失败原因。
- 私信消息量、未读积压、在线情况。

## 口径与定义
- **通知聚合**：同一天同 type 同 user_id 不新建，`ON DUPLICATE KEY` 追加 content（"3人赞了你的笔记"）。
- **SSE**：实时推送 + heartbeat。
- **私信(IM)**：WebSocket 收发，存 t_chat_message + 双向 t_chat_user_relation；Redis pub/sub 跨实例路由。

## 数据来源与就绪度
| 指标 | 来源 | 就绪度 |
|------|------|:---:|
| 通知发送量/已读率 | t_notification | ✅ |
| 推送成功率/失败原因 | t_push_task / t_push_task_fail | ✅ |
| SSE 在线/延迟 | SseEmitter | ⚠️ 需观测 |
| 私信消息量/未读积压 | t_chat_message / relation | ✅ |

## 关键不变量 / 可信边界
- 通知聚合窗口（当天剩余秒）边界处理是关键。
- 推送失败有 t_push_task_fail 结构（失败原因），是**基础设施异常诊断好数据源**。
- SSE/IM 需正确鉴权（jwt.secret ≥256 位）。

## 关联诊断
- "通知推送失败率升高" → t_push_task_fail 失败原因分布归因。
- "私信积压/消息延迟" → 跨实例路由 + 未读计数。
