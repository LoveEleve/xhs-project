# 消息推送与通知

> 所属服务：my-xhs-push (9012) | 开发阶段：Phase-2 | 状态：⏳ 待开发

## 功能概要

- SSE 实时推送通知（点赞/评论/关注/系统通知）
- 通知聚合（时间窗口内合并："张三等3人赞了你的笔记"）
- 未读计数（Red Dot）
- 通知列表查询 / 标记已读

## 涉及数据库表

- `t_notification` — 通知记录表

## 关键技术点

- **SSE（Server-Sent Events）** 推送通知（非 WebSocket，更轻量）
- 聚合通知算法：时间窗口（5分钟）内同类型同目标合并
- Redis Key 设计：`push:unread:{userId}` → Hash(type→count)
- MQ 消费多种事件类型统一处理

## 面试高频问题

- 为什么用 SSE 而不是 WebSocket 做通知推送？
- 通知聚合怎么实现的？
- 未读消息数怎么高效维护？

---

> 📋 详细文档请在开发时基于 [_template.md](../../_template.md) 填写完整内容
