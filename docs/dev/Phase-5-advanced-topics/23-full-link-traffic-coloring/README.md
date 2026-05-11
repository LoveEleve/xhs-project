# 全链路流量染色

> 跨服务专题 | 开发阶段：Phase-5 | 状态：⏳ 待开发

## 专题概要

全链路流量染色实现压测流量隔离，确保压测不污染生产数据。

## 涉及服务

- my-xhs-gateway（染色 Header 注入）
- 全部业务服务（Header 透传 + 影子表路由）
- RocketMQ（消息属性携带染色标记）

## 核心方案

| 环节 | 实现方式 | 说明 |
|------|----------|------|
| 入口标记 | Gateway GlobalFilter | 注入 `X-Traffic-Tag` 等 6 个 Header |
| 同步透传 | Feign RequestInterceptor | 自动传播到下游服务 |
| 异步透传 | MQ 消息属性 | RocketMQ Message Property 携带 |
| 线程池透传 | TaskDecorator | 异步任务继承染色上下文 |
| 数据隔离 | 影子表 | ShardingSphere Hint 路由到 `_shadow` 表 |

## 面试高频问题

- 全链路流量染色是什么？为什么需要？
- 压测流量怎么保证不污染生产数据？
- 异步场景下染色标记怎么传播？

---

> 📋 详细文档请在开发时基于 [_template.md](../../_template.md) 填写完整内容
