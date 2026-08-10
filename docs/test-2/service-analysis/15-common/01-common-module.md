# 15-common 公共模块 — 架构文档

> 端口：无（JAR 库） | 数据库：无 | 更新时间：2026-07-30

---

## 1. 模块定位

common 是 my-xhs 的共享基础库，所有微服务模块依赖它。不独立部署。

核心能力：
- **统一响应**：`R<T>` + `PageResult` + `ResultCode` + 自动包装
- **可观测性**：TraceId 全链路传播（HTTP/MQ/Feign/线程池）、Micrometer 指标
- **注解 AOP**：`@DistributedLock` / `@Idempotent` / `@RateLimit`
- **数据访问**：读写分离、TCC 防悬挂、MyBatis-Plus 分页
- **工具类**：JWT、Redis 操作、雪花 ID、号段 ID、SpEL

---

## 2. 核心模块

| 模块 | 说明 |
|---|---|
| response/ | `R<T>` 统一响应体，全局异常处理 |
| trace/ | 6 字段链路上下文（traceId/userId/grayTag/apiVersion/abGroup/pressureTest） |
| annotation/ + aspect/ | 分布式锁、幂等、限流注解 + AOP 实现 |
| config/ | 20+ 配置类（Redis/MyBatis/Jackson/Feign/Async/Sentinel/Zone） |
| datasource/ | 读写分离路由（ThreadLocal > @Transactional > SQL 分析） |
| tcc/ | TCC 防悬挂/空回滚 |
| cache/ | Redis 操作封装 + Cache Aside 延迟双删 |
| constants/ | 50+ Redis Key 定义 |
| zone/ | 多活 Zone 优先路由 |
| loadbalancer/ | 最少连接负载均衡器 |

---

## 3. 被所有模块依赖

所有 14 个业务模块（user/content/analytics/counter/product/cart/inventory/coupon/order/payment/notification/im/home/search/gateway）均引入 `my-xhs-common`。

打包方式：JAR（不可独立运行）。
