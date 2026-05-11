# 点赞收藏

> 所属服务：my-xhs-social (9003) | 开发阶段：Phase-1 | 状态：⏳ 待开发

## 功能概要

- 笔记点赞/取消点赞
- 笔记收藏/取消收藏
- 点赞/收藏状态查询（批量）
- 联动计数服务异步更新

## 涉及数据库表

- `t_like` — 点赞记录表
- `t_favorite` — 收藏记录表

## 关键技术点

- Redis Set 判断是否已点赞/收藏：`social:like:{noteId}`、`social:fav:{noteId}`
- @Idempotent 幂等注解防重复操作
- RocketMQ 异步通知计数服务 + 推送服务
- 批量查询优化（Pipeline）

## 面试高频问题

- 点赞怎么保证幂等？
- 高并发点赞性能怎么保证？
- 点赞数和实际点赞记录怎么保持一致？

---

> 📋 详细文档请在开发时基于 [_template.md](../../_template.md) 填写完整内容
