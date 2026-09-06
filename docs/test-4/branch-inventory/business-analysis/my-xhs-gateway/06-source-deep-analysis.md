# my-xhs-gateway 源码深度分析

## 1. 分析范围

本篇基于当前工作区 `refactor/elk-auth-simplify`，按源码逻辑分析，不逐行翻译代码。

分析重点：
- Filter 链的执行顺序与上下游约束
- WebFlux 请求体、Header、Exchange attribute 和 Reactor 生命周期
- JWT、HMAC、Redis 黑名单、nonce 防重放
- 路由、Nacos、Sentinel、灰度/版本/流量染色
- 异常、日志、指标和运行边界

## 2. 模块入口与初始化

`GatewayApplication` 只负责启动 Spring Boot，并排除 JDBC 与事务管理器自动配置。这个选择把网关限制在响应式 HTTP 入口层，避免因公共依赖或自动配置误创建数据库连接。

`pom.xml` 的依赖关系说明网关的运行边界：
- Gateway/WebFlux：处理请求和响应式过滤链
- Nacos Discovery：将 `lb://my-xhs-*` 解析为服务实例
- LoadBalancer：完成服务名到实例地址的选择
- Sentinel：接入 Gateway 流量控制
- JJWT：解析和校验 JWT
- Redis：黑名单、per-user HMAC secret、nonce 去重
- Actuator/Micrometer：健康检查和 Prometheus 指标
- Logback JSON 与 SkyWalking：日志及链路观测

网关不直接访问 MySQL、RocketMQ 或 Elasticsearch；这些由下游服务完成。

## 3. 全局请求流转

注意：下图是基于自定义 GlobalFilter order 的逻辑顺序，不等同于已经通过运行态证明的完整框架过滤器顺序；Sentinel 自动注册过滤器、route filter 和自定义 WebHandler 仍需运行态核对。

```text
客户端
  -> BodyCacheFilter
  -> RequestLogFilter
  -> GatewayAuthFilter
  -> TrafficColoringFilter
  -> HmacSignatureFilter
  -> RateLimitFilter
  -> GrayRouteFilter
  -> ApiVersionFilter
  -> Route Predicate 匹配
  -> LoadBalancer/Nacos 选择实例
  -> 下游微服务
  -> Response 回流
  -> RequestLogFilter 记录状态和耗时
```

源码中各 GlobalFilter 的 order 形成如下约束：

| 阶段 | Filter | Order | 设计意图 |
|---|---|---:|---|
| 请求体准备 | `BodyCacheFilter` | 最高优先级附近 | 让后续 HMAC 和下游都能读取 body |
| 入站日志 | `RequestLogFilter` | 最高优先级+100 | 生成 TraceId、记录入口 |
| 身份认证 | `GatewayAuthFilter` | +1000 | 先确定用户身份，再让下游逻辑使用可信 Header |
| 流量标签 | `TrafficColoringFilter` | +1200 | 基于身份和来源生成流量属性 |
| 完整性校验 | `HmacSignatureFilter` | +1500 | 在用户身份确定后读取 per-user secret |
| 限流 | `RateLimitFilter` | +2500 | 认证和签名后控制业务流量 |
| 灰度属性 | `GrayRouteFilter` | +3000 | 注入灰度属性 |
| 版本属性 | `ApiVersionFilter` | +3100 | 注入版本属性 |

`CachingFilteringWebHandler` 会把 GlobalFilter 与 route filter 合并、排序，并按 routeId 缓存。它使用 `Mono.defer` 保持订阅时执行，说明过滤器逻辑不能依赖“构造时已经执行”的假设。

## 4. 请求体缓存与响应式语义

### 4.1 BodyCacheFilter

写请求才读取 body，GET/HEAD 等请求直接进入后续链。multipart 请求被跳过，原因是文件体不适合被统一聚合到内存。

普通 body 的处理逻辑是：
1. 判断请求方法和 Content-Type
2. 如果 Exchange 已有缓存 body，则不重复读取
3. 用 `DataBufferUtils.join` 聚合 body
4. 转成 byte array
5. 通过 `ServerHttpRequestDecorator` 重新提供可重复读取的 body
6. 将缓存内容写入 Exchange attribute

这解决了 WebFlux body 只能消费一次的问题：HMAC 需要读取 body 摘要，但 Gateway 转发仍需要原 body。

异常路径包括：
- 读取失败时降级为空 body
- 超过 1 MB 时放弃原始 body，后续只看到空 body
- multipart 直接跳过缓存

这里的 1 MB 不是严格的流式上限，因为 `join` 可能先完成聚合再进行大小判断。超大请求仍可能在判断前消耗内存。

### 4.2 Body 与 HMAC 的耦合

HMAC 的签名材料包含 body hash。对于普通 JSON 请求，缓存后的 body 可以被 HMAC 和下游重复读取；对于 multipart，body 缓存被跳过，签名逻辑只能使用空 body 语义。因此上传接口不能简单套用普通 JSON 的 HMAC 设计。

## 5. RequestLogFilter 与链路上下文

请求进入时：
- 优先使用客户端已有 TraceId，否则生成新的 32 位 TraceId
- 把 TraceId 放入 Exchange attribute
- 通过 request mutate 注入 `X-Trace-Id`
- 构造 `sw8` Header 传给下游
- 记录 method、path、query、remote address 和 user context

下游完成后，通过 `then(Mono.fromRunnable(...))` 记录响应状态；通过 `doFinally` 计算耗时和清理上下文。使用 `System.nanoTime()` 计算耗时，避免系统时钟回拨影响 RT。

关键响应式问题：MDC 是线程绑定的，而 Reactor 可能在线程之间切换。当前代码只在当前线程写入 MDC，并不能保证所有异步日志自动带有同一个 TraceId。Exchange attribute 传播比 MDC 更可靠，但日志框架仍需要 Reactor Context 或专门的 MDC bridge 才能覆盖完整链路。

客户端可传入 `X-Trace-Id` 也意味着 TraceId 不能天然视为可信安全标识。必须限制长度、字符集，并避免把它作为权限或审计唯一依据。

## 6. JWT 鉴权状态机

```text
请求
  -> 路径命中 JWT white-list？
       是 -> 直接放行
       否 -> 读取 Authorization
               缺失/格式错误 -> 401
               解析失败 -> 401
               type != access -> 401
               jti 黑名单命中 -> 401
               Redis 查询异常 -> 401（Fail-Closed）
               校验成功 -> 覆盖 X-User-Id/X-User-Role -> 放行
```

成功校验后使用 `set` 覆盖用户 Header，而不是 `add` 追加，目的是阻断客户端伪造 `X-User-Id` 和 `X-User-Role`。下游看到的身份应来自 JWT，而不是客户端原始 Header。

SecretKey 在 `@PostConstruct` 初始化，避免每次请求重复构建密钥，也避免并发请求下的延迟初始化竞态。

### 6.1 JWT 风险

- `sub` 为空时仍可能注入空的 `X-User-Id`，应把 subject 非空作为 access token 的必要条件
- `jti` 缺失时无法形成有效黑名单 key，撤销能力不完整
- 当前主要依赖签名、过期时间和 `type`，issuer、audience 等声明校验需要确认是否有统一要求
- JWT secret 在本地配置仍存在 fallback 风险，环境变量和 Nacos 的优先级需明确
- 白名单主要按路径配置，未细分 HTTP method；同一路径不同方法的安全边界容易被误配

## 7. HMAC 鉴权状态机

HMAC 默认由 `hmac-enabled` 控制。关闭时直接放行，不执行时间戳、nonce、secret 和签名计算。

开启时：

```text
请求
  -> HMAC white-list？
       是 -> 放行
       否 -> 读取 timestamp/nonce/signature
               缺失 -> 403
               timestamp 非法或超过 ±5 分钟 -> 403
               Redis nonce SETNX 失败 -> 视为重放，403
               Redis nonce 异常 -> 当前实现放行
               X-User-Id 缺失 -> 403
               读取 per-user secret 失败/不存在 -> 403
               计算 canonical string + bodyHash
               MessageDigest.isEqual 比较
                 失败 -> 403
                 成功 -> 放行
```

签名材料包含 method、path、query、timestamp、nonce 和 body hash，设计目标是防止路径、参数、请求体和时间窗口被篡改。

### 7.1 HMAC 的关键问题

1. **nonce 消费早于签名验证**

当前先用 Redis Lua 占用 nonce，再读取 secret 和验证 signature。攻击者可以用错误签名抢先占用合法 nonce，随后合法请求会被当作重复请求拒绝。更合理的设计是签名验证成功后再占用 nonce，或者把“验证与占用”放入可证明安全的原子流程。

2. **nonce Redis 异常与 secret Redis 异常策略不一致**

nonce 异常时倾向放行，per-user secret 读取异常时拒绝。前者保障可用性但削弱防重放，后者保障安全性。需要把策略写成明确的安全决策，而不是由不同异常处理自然形成。

3. **HMAC 白名单是完全跳过，不是降级校验**

`/api/user/me/password` 等路径若被列入 HMAC 白名单，写操作可能只剩 JWT。配置原则中又要求写接口必须签名，这两者存在冲突，必须按 HTTP method 和业务敏感度重新审核。

4. **全局 HMAC secret 字段可能未使用**

代码初始化了全局 HMAC secret key，但实际签名使用 Redis per-user secret。若确认无调用方，应删除死字段；如果有 fallback 设计，应明确 fallback 条件。

5. **错误响应 fallback 不能用字符串拼接**

ObjectMapper 失败后再用字符串拼接 JSON，遇到引号、换行或控制字符可能产生非法响应。错误响应应始终使用可靠编码器，无法序列化时返回固定安全文本。

## 8. 流量染色、灰度和版本

`TrafficColoringFilter` 读取用户身份和来源信息，写入灰度、版本、AB 和压测相关属性。Exchange attribute 适合在当前网关内部传播，但写入下游 Header 时必须区分可信属性和客户端输入。

当前实际能力分三层：
- Header 解析
- Exchange attribute 注入
- LoadBalancer 实例筛选

源码目前明确完成前两层，未发现第三层的实例过滤实现。因此“支持灰度/版本路由”不能写成已完成，只能写成入口属性已接入、路由闭环未完成。

风险：
- 客户端可直接指定部分灰度/版本/AB Header
- 压测判断信任 `X-Forwarded-For` 的客户端值，可能被伪造
- `remoteAddress` 为空时存在边界异常可能
- `X-Gray-Tag` 等值缺少枚举约束
- 匿名请求不会自动进入用户灰度分组

## 9. Sentinel 限流

`RateLimitFilter` 不实现限流算法，而是把 route metadata 转换成 Sentinel Gateway FlowRule，再交给 Sentinel Gateway filter 执行。

规则主要从 route 的 `rate-limit-qps` 读取，默认值为 100 QPS。当前 route metadata 已按用户、内容、搜索、订单、支付等场景配置不同阈值。

启动时存在两条路径：
- 如果判断到 Nacos 数据源已配置，则等待动态规则
- 如果没有配置或等待超时，则加载本地 route metadata fallback

实际代码重点是 routeId 限流，而不是用户级限流。虽然存在 `KeyResolver`，但当前 `RateLimitFilter` 没有把用户 key 接入 Sentinel 规则。因此不能把当前实现描述为“用户级限流已完成”。

风险：
- 多网关实例下是实例本地限流，不是全局总量
- Nacos 晚到时 fallback 可能覆盖或替换动态规则
- metadata 为 0、负数或非法字符串时缺乏严格校验
- 代码只看到 FlowRule，不能把它描述为完整熔断能力

## 10. 路由分发

`application.yml` 当前显式配置 16 条路由：
- 14 个核心业务服务路由：user、content、search、order、payment、inventory、analytics、counter、product、cart、coupon、home、notification、im
- `recommend-service`：复用 `my-xhs-search`
- `ai-app-service`：将 `/ai-api/**` 重写到后端 `/api/**`

需要特别区分：路由 metadata 中虽然写了 AI 路由约 31 分钟响应超时，但 application.yml 的注释明确说明 metadata 是设计规范，实际请求当前使用全局 Gateway HttpClient timeout。不能把 31 分钟写成已经生效的运行事实。AI SSE 需要验证全局 timeout 是否会在约 10 秒左右提前断开，以及是否需要实现 metadata 到真实 HttpClient 配置的转换。该路由需要验证：
- RewritePath 是否保留正确的 query
- 长连接期间连接池是否被错误回收
- 网关和下游的 timeout 是否一致
- SSE 断开时是否正确释放资源

Nacos Discovery 负责实例发现，LoadBalancer 负责实例选择。服务注册成功不代表所有 route predicate 都正确，必须按路径和 HTTP method 做接口级验证。

## 11. CachingFilteringWebHandler

该类复制并扩展 Gateway 默认过滤器执行行为，按 routeId 缓存排序后的过滤器链，并在路由刷新事件时清空缓存。

优点：
- 避免每次请求重复合并和排序
- 路由刷新后可以重新构建
- `Mono.defer` 保留响应式惰性

风险：
- 依赖 Spring Cloud Gateway 内部执行契约，框架升级时容易失效
- route refresh 事件丢失会继续使用旧链
- 仅凭类存在不能证明它已被实际注册为网关 handler，需要核对配置和运行态 Bean

## 12. 异常处理

`GlobalExceptionHandler` 把连接异常映射为 503，把连接/响应超时映射为 504，把未找到路由映射为 404，未知异常映射为 500。

如果响应已经提交，则不再二次写入响应，避免 SSE、流式响应或下游已写出部分内容时发生二次提交异常。

需要注意：异常可能被 `WebClientRequestException`、`ResponseStatusException` 或 Reactor 包装，当前只检查顶层类型时，某些连接拒绝和超时未必能落到预期 503/504。

错误响应序列化失败后的字符串 fallback 与 HMAC Filter 同样存在格式安全问题。

## 13. 可观测性

网关暴露 health、info、prometheus、metrics，并给 Micrometer 指标添加 `application=my-xhs-gateway` 标签。Logback 使用 JSON 文件落盘，Filebeat 后续采集该文件进入 ELK。

日志配置的实际组合需要单独看清：控制台、两个异步文件 appender 与同步 JSON `RollingFileAppender` 同时存在。JSON 文件并未因为旁边存在异步 appender 就自动变成异步，写文件仍可能发生在请求线程关联的日志调用路径上。

当前应重点确认：
- MDC 是否跨 Reactor 线程完整传播
- `X-Trace-Id` 是否限制格式和长度
- query 中是否可能记录 token、签名或个人信息
- Actuator `show-details=always` 是否对公网暴露
- JSON appender 是否同步阻塞 WebFlux 线程
- 日志中 userId、jti、nonce、IP 是否符合脱敏和留存要求

## 14. 源码覆盖补充

以下文件和逻辑已纳入本轮源码级分析：

| 文件 | 已覆盖重点 |
|---|---|
| `Dockerfile` | 基础镜像契约、是否包含构建/运行用户/健康检查 |
| `pom.xml` | Gateway、Nacos、Sentinel、LoadBalancer、JWT、Redis、Actuator、日志依赖边界 |
| `GatewayApplication.java` | WebFlux 启动与 JDBC/JPA 自动配置排除 |
| `AuthProperties.java` | JWT/HMAC secret、开关、JWT/HMAC 两组白名单绑定 |
| `GatewayConfig.java` | CORS、WebClient/Gateway converter 等网关基础配置 |
| `GatewayMetricsConfig.java` | Micrometer common tags 的注册范围 |
| `RateLimiterConfig.java` | KeyResolver 声明与是否实际接入 Sentinel 的差异 |
| `BodyCacheFilter.java` | 写请求、multipart、缓存、超限、读取异常、body 重放 |
| `RequestLogFilter.java` | TraceId、sw8、MDC、入站/出站日志、异常生命周期 |
| `GatewayAuthFilter.java` | 白名单、Bearer、JWT claims、黑名单、Header 覆盖、错误响应 |
| `TrafficColoringFilter.java` | 灰度、版本、AB、压测标签和 XFF 信任边界 |
| `HmacSignatureFilter.java` | 开关、白名单、时间窗、nonce、secret、bodyHash、常量时间比较 |
| `RateLimitFilter.java` | metadata 规则转换、Sentinel callback、fallback 与响应码 |
| `GrayRouteFilter.java` | 灰度属性与用户 hash 分支，未实现实例筛选 |
| `ApiVersionFilter.java` | 版本属性注入和默认版本分支 |
| `CachingFilteringWebHandler.java` | 已确认未注册且无引用，已删除死代码 |
| `GlobalExceptionHandler.java` | 异常分类、响应已提交、序列化 fallback、包装异常风险 |
| `application.yml` | 16 条路由、全局 timeout、白名单、Redis/Nacos/Sentinel/Actuator |
| `logback-spring.xml` | 控制台、异步文件、同步 JSON、MDC 字段和滚动策略 |
| `docs/CODE-REVIEW.md` | 历史结论与当前源码差异识别 |

## 15. 覆盖对账

- 目录基准：`docs/test-4/branch-inventory/project-directories/my-xhs-gateway/inventory.md`
- 顶层直接子项：`Dockerfile`、`docs/`、`pom.xml`、`src/`、`target/`
- 源码/配置/模块文档候选文件：20 个
- 已读并纳入分析：20 个
- 未读候选文件：0 个
- `target/` 未纳入源码逻辑分析，原因是构建生成物，不是源码事实
- `project-directories/` 只提供模块顶层盘点，不能代替 `src/` 内部逐文件扫描；本轮已额外扫描并核对 `src/main/java` 与 `src/main/resources`
- 当前仍需运行态验证但不属于“未读文件”：Sentinel 动态 datasource 是否接线、完整 Filter order 是否与自动装配组件重复、route metadata 是否被框架实际采用

## 16. 待修问题清单

### 16.1 已确认的代码 Bug / 逻辑缺陷
- Nacos 网关配置键与 `AuthProperties` 绑定路径不一致，可能导致配置中心密钥不生效：`config/nacos/my-xhs-gateway.yaml:4-8`、`AuthProperties.java:18-19`
- `GatewayAuthFilter` 在 JWT 没有 role 时不清理客户端原有 `X-User-Role`，存在 Header 伪造残留：`GatewayAuthFilter.java:135-142`
- `BodyCacheFilter` 超过 1 MB 后静默替换为空 body，而不是返回 413，可能把错误请求转发给下游：`BodyCacheFilter.java:64-72`
- `HmacSignatureFilter` 先消费 nonce 再验签，错误请求可以抢占合法 nonce：`HmacSignatureFilter.java:149-215`
- `GrayRouteFilter` 的自动灰度分支被前置 `TrafficColoringFilter` 写入的默认 `stable` 覆盖，形成死分支组合：`TrafficColoringFilter.java:57-61`、`GrayRouteFilter.java:63-80`
- `CachingFilteringWebHandler` 曾未注册为 Bean/Handler，当前缓存逻辑不生效；该死代码已删除

### 16.2 业务与入口逻辑风险
- JWT/HMAC 白名单主要按路径，不按 HTTP method；写接口可能只剩 JWT
- 管理/内部路径部分被 JWT 白名单直接放行，保护责任完全下沉给下游
- AI 路由本轮明确不纳入整改和验证范围，原有 timeout 结论暂不推进
- 灰度、版本、AB 标签允许客户端传入，业务分组和路由边界不可信

### 16.3 分布式与微服务问题
- Sentinel 当前主要按 routeId 做 JVM 本地限流，不是全局限流，也没有确认用户级参数限流
- Redis 同时承载 JWT 黑名单、HMAC secret、nonce；不同故障场景分别 fail-closed/fail-open，策略不一致
- Nacos 动态限流数据源未确认，规则晚到或不完整时可能导致路由限流缺口
- Sentinel 自动 Filter 与自定义限流 Filter 的实际组合仍需运行态确认
- 下游连接/超时异常可能被 WebClient/Reactor 包装，当前 handler 只看顶层异常类型

### 16.4 性能问题
- WebFlux 请求链中使用同步 `StringRedisTemplate`，Redis 慢时可能阻塞 Netty EventLoop
- Body 使用 `DataBufferUtils.join` 后才判断大小，超大请求仍可能先造成内存压力
- 同步 JSON RollingFileAppender 可能产生请求线程 I/O
- 多网关实例下 Sentinel 本地计数无法提供统一配额

### 16.5 工程与配置问题
- `server.tomcat`、`spring.mvc.pathmatch` 与 WebFlux/Netty 网关不匹配，容易误导调优
- 错误响应序列化失败时多处使用字符串拼接 fallback
- `GatewayConfig` 存在未使用 import
- 当前微服务依靠手动启动，关机重启后不会自动恢复；Docker 中间件与 Java 进程恢复机制不一致

### 16.6 鉴权基础检查
- `hmac-enabled=false` 时 HMAC 逻辑默认不执行，本轮不把 HMAC 细节作为主要整改主线
- 只保留 JWT 可用性、管理/内部边界和客户端身份 Header 防伪造检查
- secret fallback、Actuator 暴露和 Header 来源仍需在部署安全层确认

## 17. 补充核验：此前遗漏的关键实现事实

### 17.1 同步 Redis 调用与 WebFlux EventLoop

`GatewayAuthFilter` 与 `HmacSignatureFilter` 通过 `StringRedisTemplate` 的同步 API 读取黑名单、per-user secret，并执行 Lua nonce 操作。虽然网关整体是 WebFlux，但同步 Redis 调用仍会占用处理请求的线程；在高延迟或 Redis 故障时，可能阻塞 Netty EventLoop，造成请求排队、RT 放大甚至网关级联不可用。

这不是“用了 WebFlux 就自动非阻塞”的情况。需要通过运行态线程栈、Redis 延迟和高并发压测确认影响，并评估响应式 Redis API 或专用调度线程池的改造边界。

### 17.2 CORS 当前实现

`GatewayConfig` 的 CORS 配置不能只依据历史评审结论判断。当前需要核对允许的 origin pattern、`allowCredentials`、Header、method 和预检响应。若允许凭证，origin 不能被不可信客户端任意扩大；否则会形成 CSRF 风险。CORS 是浏览器访问控制，不替代 JWT、HMAC 或服务端权限校验。

### 17.3 RequestLogFilter 异常生命周期

`chain.filter(...).then(...)` 只在上游正常完成时执行响应记录；异常信号不会进入该 `then` 回调。`doFinally` 能清理资源，但不等于已经记录异常状态和耗时。因此必须单独验证下游连接拒绝、超时、取消和客户端断开时是否有完整日志。

### 17.4 GrayRouteFilter 与 TrafficColoringFilter 的组合

`TrafficColoringFilter` 可能先为请求写入默认 `stable` 标签，后续 `GrayRouteFilter` 只有在没有显式标签时才执行按 userId hash 的灰度分配。两个 Filter 组合后，自动灰度分支可能被默认值覆盖，不能只分别分析两个类，必须验证它们的实际执行顺序和属性覆盖关系。

### 17.5 自定义 WebHandler 与 Sentinel 自动过滤器

`CachingFilteringWebHandler` 的代码本身不能证明它已替代框架默认 handler。需要从 Bean 注册、自动配置条件和启动日志确认实际实例；同时核对 Sentinel 自动过滤器与自定义 `RateLimitFilter` 是否重复执行，避免把“类存在”误判成“运行链路已启用”。

## 18. 最终结论

本篇记录源码审查结果，当前已覆盖候选池 20 个文件，但“文件已阅读”不等于“所有运行行为已验证”。以下事项必须进入后续测试或代码修复：同步 Redis 阻塞、AI 路由真实超时、HMAC nonce 先消费、XFF 信任、method 级白名单、CORS 边界、Filter 实际注册、异常日志完整性和 Sentinel 规则接线。

后续修复必须单独记录问题、影响范围、修复理由和验证结果。

## 19. 本轮复核、修复与运行验证

### 已确认并已修复

| 问题 | 修复位置 | 验证结果 |
|---|---|---|
| Nacos 配置键与 `AuthProperties` 不匹配 | `config/nacos/my-xhs-gateway.yaml`、`deploy/docker/my-xhs-deploy-zip/config/nacos/my-xhs-gateway.yaml` | 配置结构已统一为 `gateway.auth.secret` / `gateway.auth.hmac-secret` |
| JWT 无 role 时残留客户端 `X-User-Role` | `GatewayAuthFilter.java:135-142` | 已先 remove，再按 JWT role 设置 |
| Body 超过 1MB 静默转空 body | `BodyCacheFilter.java:64-85` | 已改为返回 HTTP 413，不再把原请求伪装为空 body |
| 自动灰度被默认 stable 阻断 | `TrafficColoringFilter.java:57-61,102-110` | 已取消默认 stable Header 覆盖，交给 `GrayRouteFilter` 判断 |
| 未注册的 `CachingFilteringWebHandler` 死代码 | 删除 `handler/CachingFilteringWebHandler.java` | 已确认无引用；gateway 编译成功 |
| 非法/零/负数 Sentinel QPS 缺少保护 | `RateLimitFilter.java:218-232` | 已改为仅接受正数，非法值回退默认 QPS |
| 异常 cause 被包装时无法映射 503/504/404 | `GlobalExceptionHandler.java:111-132` | 已沿 cause 链识别连接、超时和路由异常 |

### 本轮明确不接入

- AI 路由与 AI 服务不纳入本轮验证或整改
- HMAC nonce 先消费、XFF/流量标签可信边界、同步 Redis 阻塞、CORS 和 Actuator 暴露暂不改动；其中鉴权细节按当前范围只保留基础风险记录
- 灰度/版本实例筛选仍不接入，因为当前项目没有完整 LoadBalancer 实现，贸然接入会改变路由运行模型

### 运行验证

- `gateway`、`user`、`content`、`analytics`、`counter`、`product`、`cart`、`inventory`、`coupon`、`order`、`payment`、`notification`、`im`、`home`、`search` 共 15 个核心服务均已启动
- 对应 actuator health 均返回 `status=UP`
- Nacos `my-xhs` 命名空间已发现 15 个服务
- Docker 中间件 28 个容器均为 healthy
- AI `my-xhs-ai-app` / `my-xhs-ai-mcp` 未启动，符合本轮范围

### 尚未通过业务请求验证的事项

- 网关管理白名单与 HTTP method 边界
- Sentinel 实际规则加载、限流响应和动态数据源
- 下游连接拒绝/超时的异常包装映射
- 同步 Redis 调用对 WebFlux EventLoop 的实际影响
- SSE/长连接的真实 timeout
- 灰度/版本 Header 是否由可信边界生成

