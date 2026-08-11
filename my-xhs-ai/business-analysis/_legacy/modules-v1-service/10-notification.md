# 10. 通知域（my-xhs-notification）业务逻辑

> 端口 19013 | 9 端点 | 聚合通知 + SSE 实时推送 | 需 --spring.profiles.active=dev

## 一、业务定位
**用户触达**：点赞/评论/关注/系统/订单等通知的聚合与实时推送（SSE），提升留存与互动感知。

## 二、核心业务逻辑
1. **通知生命周期**：外部→NotificationService.create → 写 MySQL + 聚合 → SSE 推送 → 用户阅读。
2. **聚合通知**：同一天同 type 同 user_id 不新建，`ON DUPLICATE KEY` 追加 content → 前端显示"3人赞了你的笔记"。
3. **未读计数**：`COUNT WHERE is_read=0 GROUP BY type` → {total, like, comment, follow} 角标。
4. **SSE**：实时推送 + heartbeat；通道统一管理。

## 三、异常路径
- 聚合窗口边界（当天剩余秒）处理不当 → 跨天聚合错误。
- SSE 连接管理（断线/重连/多实例路由）。

## 四、对 AI 项目（指标/诊断）的价值
| 指标 | 来源 | 就绪度 |
|------|------|:---:|
| 通知发送量/已读率 | t_notification | ✅ |
| 推送成功率/失败原因 | t_push_task / t_push_task_fail | ✅ |
| SSE 在线/推送延迟 | SseEmitter | ⚠️ 需观测 |

> 通知与触达的业务价值明确，且 t_push_task_fail 有失败原因结构，**基础设施异常诊断的好数据源**。
