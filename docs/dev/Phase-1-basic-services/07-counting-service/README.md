# 计数服务

> 所属服务：my-xhs-counter (9004) | 开发阶段：Phase-1 | 状态：⏳ 待开发

## 功能概要

- 统一计数（点赞数/收藏数/评论数/粉丝数）
- Buffer-Trigger 批量刷盘
- 计数查询（单条/批量）

## 涉及数据库表

- `t_counter` — 计数表

## 关键技术点

- **Buffer-Trigger 模式**：内存聚合 → 定时/定量刷盘到 DB
  - 触发条件：缓冲区达到 1000 条 或 间隔 5 秒
  - 防丢失：JVM ShutdownHook 兜底刷盘
- Redis Hash 存储实时计数：`counter:{targetType}:{targetId}`
- MQ 消费点赞/收藏/评论/关注事件

## 面试高频问题

- 为什么不直接更新 DB，要用 Buffer-Trigger？
- Buffer-Trigger 服务宕机数据会丢吗？
- 计数服务怎么保证最终一致性？

---

> 📋 详细文档请在开发时基于 [_template.md](../../_template.md) 填写完整内容
