# 优雅停机与服务治理

> 跨服务专题 | 开发阶段：Phase-5 | 状态：⏳ 待开发

## 专题概要

本专题聚焦微服务上下线过程中的稳定性保障，确保 K8s 滚动更新、服务发布时零请求丢失。

## 涉及服务

- 全部 15 个业务服务

## 核心内容

| 维度 | 方案 | 说明 |
|------|------|------|
| 优雅停机 | `server.shutdown=graceful` | 等待现有请求完成，最长30秒 |
| Nacos 下线 | PreStop 钩子先注销 | 先从注册中心下线，不再接新请求 |
| 健康检查 | Actuator + K8s Probe | Liveness/Readiness/Startup 三种探针 |
| 异常分级处理 | BizException/SysException/RemoteException | 业务异常WARN、系统异常ERROR+告警 |
| Feign 超时 | 连接5s + 读取10s | 防止线程池被慢调用耗尽 |
| Feign 重试 | 全局 NEVER_RETRY | 非幂等接口绝对不能重试 |
| 降级预案 | 多级降级开关 + 一键预案 | Redis不可用预案、DB不可用预案 |

## 重点覆盖

- Spring Boot 优雅停机配置
- K8s PreStop 钩子 + terminationGracePeriodSeconds
- Nacos 服务注册/注销生命周期
- 全局异常处理器分级策略
- Feign 6 大坑及防坑方案
- 降级预案管理（一键切换）

## 面试高频问题

- K8s 滚动更新时怎么保证请求不丢？
- 服务下线后 Nacos 有延迟怎么办？
- Feign 调用超时了怎么处理？为什么全局关闭重试？
- 线上出问题了怎么快速降级？

---

> 📋 详细文档请在开发时基于 [_template.md](../../_template.md) 填写完整内容
