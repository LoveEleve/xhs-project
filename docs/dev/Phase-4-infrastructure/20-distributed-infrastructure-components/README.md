# 分布式基础组件

> 所属服务：my-xhs-common | 开发阶段：Phase-4 | 状态：⏳ 待开发

## 功能概要

- 雪花 ID 生成器（Redis 分配 WorkerId）
- 号段模式 ID（用户ID，对外可读）
- @DistributedLock 分布式锁注解
- @Idempotent 幂等注解
- @RateLimit 限频注解
- 统一响应体 R\<T\> + 错误码体系
- RedisOperator / CacheHelper 缓存封装

## 关键技术点

- **Redisson 分布式锁**：Watchdog 自动续期，支持 FAIR/REENTRANT/READ/WRITE
- **幂等机制**：AOP + Redis SET NX + Token 机制
- **限频**：Redis Lua 脚本滑动窗口
- **雪花 ID**：解决分库分表后 ID 冲突
- **统一序列化**：Jackson（不用 fastjson，安全考虑）

## 面试高频问题

- 分布式锁怎么实现的？Watchdog 是什么原理？
- 幂等怎么做的？Token 机制是怎么回事？
- 雪花 ID 有什么问题？怎么解决时钟回拨？

---

> 📋 详细文档请在开发时基于 [_template.md](../../_template.md) 填写完整内容
