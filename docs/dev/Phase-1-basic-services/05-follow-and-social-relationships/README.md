# 关注与社交关系

> 所属服务：my-xhs-social (9003) | 开发阶段：Phase-1 | 状态：⏳ 待开发

## 功能概要

- 关注/取关
- 关注列表、粉丝列表
- 共同关注
- 话题系统（话题创建 + 话题下笔记聚合）
- 热门话题（实时计数排序 Top10）
- 关注数/粉丝数联动计数服务

## 涉及数据库表

- `t_follow` — 关注关系表（10亿级）

## 关键技术点

- Redis ZSet 存储关注/粉丝列表：`social:following:{userId}`、`social:follower:{userId}`
- SINTER 实现共同关注
- 大V关注数优化（异步 MQ 通知 Feed 服务）
- 大V粉丝分桶存储（`social:follower:bucket:{userId}:{N}`）
- **话题系统**：Redis ZSet 聚合话题下笔记（score=发布时间）
- **热门话题**：Redis ZSet 实时计数排序（score=浏览量）
- 幂等处理（重复关注）
- Lua 原子操作（关注+计数1步完成）

## 面试高频问题

- 关注关系用什么数据结构存储？为什么用 ZSet 而不是 List？
- 共同关注怎么实现？性能如何？
- 大V的粉丝列表很大怎么处理？粉丝分桶是什么原理？
- 话题系统怎么设计的？热门话题怎么计算？

---

> 📋 详细文档请在开发时基于 [_template.md](../../_template.md) 填写完整内容
