# my-xhs-gateway 模块总览

## 1. 当前模块定位

`my-xhs-gateway` 是整个系统的外部入口、鉴权入口、路由入口，不是普通业务服务。

它当前承担 5 类职责：
- 接收外部 HTTP 请求
- 做 JWT / HMAC / 白名单 / 限流等入口校验
- 基于 Nacos 服务发现把请求分发到下游微服务
- 统一处理入口日志、异常、TraceId、流量染色
- 暴露网关侧指标与健康检查

因此，`gateway` 的业务分析不能只按“单服务 CRUD”方式写，必须先看入口边界和下游分发职责。

## 2. 本轮覆盖基准清单

基准来自：`docs/test-4/branch-inventory/project-directories/my-xhs-gateway/inventory.md`

顶层直接子项：
- `Dockerfile`
- `docs/`
- `pom.xml`
- `src/`
- `target/`（生成物，不作为源码事实）

本轮候选文件池（已纳入首轮分析范围）：
- `my-xhs-gateway/Dockerfile`
- `my-xhs-gateway/pom.xml`
- `my-xhs-gateway/docs/CODE-REVIEW.md`
- `my-xhs-gateway/src/main/resources/application.yml`
- `my-xhs-gateway/src/main/resources/logback-spring.xml`
- `my-xhs-gateway/src/main/java/com/myxhs/gateway/GatewayApplication.java`
- `my-xhs-gateway/src/main/java/com/myxhs/gateway/config/AuthProperties.java`
- `my-xhs-gateway/src/main/java/com/myxhs/gateway/config/GatewayConfig.java`
- `my-xhs-gateway/src/main/java/com/myxhs/gateway/config/GatewayMetricsConfig.java`
- `my-xhs-gateway/src/main/java/com/myxhs/gateway/config/RateLimiterConfig.java`
- `my-xhs-gateway/src/main/java/com/myxhs/gateway/filter/ApiVersionFilter.java`
- `my-xhs-gateway/src/main/java/com/myxhs/gateway/filter/BodyCacheFilter.java`
- `my-xhs-gateway/src/main/java/com/myxhs/gateway/filter/GatewayAuthFilter.java`
- `my-xhs-gateway/src/main/java/com/myxhs/gateway/filter/GrayRouteFilter.java`
- `my-xhs-gateway/src/main/java/com/myxhs/gateway/filter/HmacSignatureFilter.java`
- `my-xhs-gateway/src/main/java/com/myxhs/gateway/filter/RateLimitFilter.java`
- `my-xhs-gateway/src/main/java/com/myxhs/gateway/filter/RequestLogFilter.java`
- `my-xhs-gateway/src/main/java/com/myxhs/gateway/filter/TrafficColoringFilter.java`
- `my-xhs-gateway/src/main/java/com/myxhs/gateway/handler/CachingFilteringWebHandler.java`
- `my-xhs-gateway/src/main/java/com/myxhs/gateway/handler/GlobalExceptionHandler.java`

## 3. 当前代码事实（首轮）

从 `pom.xml` 与 `application.yml` 可以先确认以下事实：
- 技术栈是 `Spring Cloud Gateway + WebFlux`
- 通过 `Nacos Discovery` 找下游实例
- 已接入 `Sentinel` 相关依赖与配置，但具体限流能力仍需继续核实 Java 配置与运行行为
- 通过 `Redis` 做 token 黑名单与部分安全辅助能力
- 不接数据库，显式排除了 DataSource / JPA 等自动配置
- 当前路由表显式配置 16 条：14 个核心业务服务路由、单独的 `recommend-service` 路由（复用 `my-xhs-search`），以及 `ai-app-service` 特殊路由
- 灰度路由、版本路由、流量染色在当前代码中已出现入口能力，但是否形成完整闭环仍需继续核实

## 4. 历史参考与差异点

可参考：
- `my-xhs-gateway/docs/CODE-REVIEW.md`（模块内历史评审文档）
- `docs/test-2/service-analysis/16-gateway/`
- `docs/test-3/methodology/TEST-METHODOLOGY.md`

但当前事实优先级仍然是：当前代码与当前运行结果 > 历史文档。

## 5. 当前分支改动关注点

这里记录的是“当前分支/当前工作区的近期改动关注点”，不是模块基础事实。

当前已识别的关注点包括：
- JWT/HMAC 路径收口
- `hmac-enabled` 改为可开关
- `JWT_SECRET` 改为支持环境变量覆盖
- 旧 IP 替换为当前部署地址
- 标准 ELK 落盘日志链路适配

## 6. 本轮尚未展开的内容

本文件只完成模块定位和候选文件池建立，尚未深入展开：
- Filter 链顺序与职责
- 具体路由分发表
- 鉴权链、白名单、HMAC 白名单的实际边界
- 异常处理、日志、指标、限流和流量染色实现
- 当前分支是否还遗留网关侧代码问题

这些内容在下一轮继续展开。
