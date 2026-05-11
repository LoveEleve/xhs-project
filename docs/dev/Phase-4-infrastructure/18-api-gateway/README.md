# API 网关

> 所属服务：my-xhs-gateway (9000) | 开发阶段：Phase-4 | 状态：⏳ 待开发

## 功能概要

- 路由转发（Spring Cloud Gateway）
- 统一鉴权（JWT Token 校验）
- 全局限流（Redis + Lua 滑动窗口）
- 全局异常处理
- 流量染色（压测标记 Header 传播）
- 服务降级（Sentinel 集成）

## 涉及数据库表

- 无

## 关键技术点

- **流量染色 6 个 Header**：`X-Traffic-Tag`、`X-Pressure-Test`、`X-Gray-Version` 等
- Gateway GlobalFilter 注入染色标记
- Feign RequestInterceptor 透传 Header
- MQ 消息属性携带染色信息
- Sentinel 限流 + 降级分级（读降级/写延迟降级/存储降级/功能降级）

## 面试高频问题

- 网关做了哪些事情？
- 全链路流量染色怎么实现的？
- 限流算法用的什么？为什么？

---

> 📋 详细文档请在开发时基于 [_template.md](../../_template.md) 填写完整内容
