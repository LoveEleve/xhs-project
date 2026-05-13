# Phase 4：基础设施 — 详细梳理

> 🎯 目标：补齐基础设施，系统具备生产级接入和治理能力
>
> ⚠️ **前置条件**：Phase 1 + Phase 2 + Phase 3 全部功能开发完成并验收通过

### Phase 间依赖清单

| 依赖Phase | 依赖的功能点 | 本Phase使用场景 |
|-----------|------------|----------------|
| Phase 1 | 用户体系（JWT签发/刷新） | Gateway鉴权需校验JWT Token |
| Phase 1 | 内容服务、社交服务、计数服务 | Gateway路由规则需要覆盖 |
| Phase 2 | 商品/库存/购物车/优惠券/订单/支付 | Gateway路由规则需要覆盖 |
| Phase 2 | 订单分库、优惠券分库 | 雪花ID替代分库自增ID |
| Phase 3 | 搜索/Home BFF/通知 | Gateway路由规则需要覆盖 |
| Phase 3 | 通知服务（SSE+MQ+Bitmap） | IM离线推送依赖通知服务Feign |

---

## 一、Phase 4 概览

| 序号 | 功能 | 涉及服务 | 核心技术 |
|------|------|----------|----------|
| 18 | API网关增强 | 网关服务 | JWT鉴权、HMAC签名校验、Sentinel限流、灰度路由、流量染色、API版本路由 |
| 19 | 即时通讯IM | IM服务 | WebSocket、消息存储、已读回执、会话管理 |
| 20 | 分布式基础组件 | 公共模块 | 雪花ID（CosId）、@DistributedLock注解、@Idempotent注解、@RateLimit注解 |

---

## 二、涉及的模块与端口

| 服务 | 端口 | 数据库 | 本阶段新增 | 说明 |
|------|------|--------|-----------|------|
| my-xhs-gateway | 9000 | — | ❌ 已存在(增强) | API网关（补齐GlobalFilter链、灰度、限流、HMAC） |
| my-xhs-im | 9014 | my_xhs_im | ✅ 新建 | 即时通讯服务（WebSocket私信、会话列表） |
| my-xhs-common | — | — | ❌ 已存在(增强) | 公共模块（补齐4个自定义注解+雪花ID） |

> **注意**：
> - `my-xhs-gateway` 和 `my-xhs-common` 目前只是空壳（仅 Application.java），Phase 4 需要填充完整实现
> - `my-xhs-im` 需要新建模块，端口分配 9014

### 2.1 端口对照说明

| 服务 | 技术规格大纲端口 | 实际端口 | 说明 |
|------|-----------------|---------|------|
| my-xhs-gateway | 9000 | **9000** | 一致 |
| my-xhs-im | 9013 | **9014** | 实际端口与大纲不一致，以 application.yml 为准 |

> **原则**：所有端口以各服务 `application.yml` 实际配置为准，技术规格大纲仅作初始参考。

---

## 三、功能详细梳理

### 功能 18：API网关增强

#### 3.18.1 功能描述

API网关是所有外部请求的统一入口，Phase 4 在现有网关骨架基础上，补齐6大核心能力：①JWT统一鉴权（GlobalFilter校验Token，白名单放行）；②HMAC-SHA256签名校验（防篡改+防重放，timestamp+nonce机制）；③Sentinel限流熔断（接口级+用户级QPS限制）；④灰度路由（Nacos元数据标记灰度实例，请求头`X-Gray-Tag`路由匹配）；⑤全链路流量染色（Feign+MQ+异步线程透传`X-Trace-Id`+`X-Gray-Tag`）；⑥API版本路由（Header `X-Api-Version` 匹配不同服务版本）。

网关路由规则需要补齐 Phase 2/3 缺失的路由（product/cart/inventory/coupon/order/payment/search/home），当前仅配置了 user/content/analytics/counter 4条路由。

#### 3.18.2 涉及服务

| 服务 | 角色 | 说明 |
|------|------|------|
| my-xhs-gateway | 主服务 | 统一入口，GlobalFilter链式处理 |
| my-xhs-user | 被调用 | JWT Token签发/刷新 |
| my-xhs-im | 被调用 | WebSocket路由（需特殊处理，非HTTP） |

#### 3.18.3 API 接口清单

> 网关本身不暴露业务API，所有接口由后端服务提供。网关的路由规则清单如下：

| 路由ID | 路径匹配 | 目标服务 | 鉴权 | 说明 |
|--------|---------|---------|------|------|
| user-service | /api/user/** | my-xhs-user | 部分白名单 | 登录/注册放行，其他需鉴权 |
| content-service | /api/note/**,/api/comment/** | my-xhs-content | ✅ | 笔记/评论 |
| analytics-service | /api/social/** | my-xhs-analytics | ✅ | 社交 |
| counter-service | /api/counter/** | my-xhs-counter | ❌ | 计数（公开） |
| product-service | /api/product/** | my-xhs-product | 部分白名单 | 商品详情放行，管理需鉴权 |
| cart-service | /api/cart/** | my-xhs-cart | ✅ | 购物车 |
| inventory-service | /api/inventory/** | my-xhs-inventory | ✅ | 库存 |
| coupon-service | /api/coupon/** | my-xhs-coupon | ✅ | 优惠券 |
| order-service | /api/order/** | my-xhs-order | ✅ | 订单 |
| payment-service | /api/payment/** | my-xhs-payment | ✅ | 支付 |
| search-service | /api/search/** | my-xhs-search | 部分白名单 | 搜索放行，管理需鉴权 |
| home-service | /api/home/** | my-xhs-home | 部分白名单 | 发现流放行，关注流需鉴权 |
| notification-service | /api/notification/** | my-xhs-notification | ✅ | 通知 |
| im-service | /api/im/** | my-xhs-im | ✅ | IM（含WebSocket升级端点 /api/im/ws） |

#### 3.18.4 数据库表

> 网关服务不拥有数据库。鉴权白名单、限流规则等通过 Nacos 配置中心管理。

#### 3.18.5 Redis Key 清单

| Key | 数据结构 | TTL | 说明 |
|-----|---------|-----|------|
| `gateway:ratelimit:{api}:{userId}` | String | 滑动窗口 | 接口限流计数 |
| `gateway:nonce:{nonce}` | String | 5min | 请求防重放（nonce唯一性校验） |
| `user:token:blacklist:{userId}` | String | Access Token剩余有效期 | Token黑名单（登出/改密码时加入） |

#### 3.18.6 Java 文件清单

**filter/**
```
AuthGlobalFilter.java          — JWT鉴权过滤器（校验Token+黑名单+白名单放行）
HmacSignatureFilter.java       — HMAC签名校验过滤器（防篡改+防重放）
RequestLogFilter.java          — 请求日志过滤器（记录入站请求+TraceId注入）
RateLimitFilter.java           — Sentinel限流过滤器（接口级+用户级QPS）
GrayRouteFilter.java           — 灰度路由过滤器（X-Gray-Tag请求头匹配Nacos元数据）
ApiVersionFilter.java          — API版本路由过滤器（X-Api-Version请求头路由）
```

**config/**
```
SentinelConfig.java            — Sentinel规则配置（Nacos数据源加载限流规则）
CorsConfig.java                — 跨域CORS配置
GrayRouteConfig.java           — 灰度路由配置（灰度实例元数据管理）
```

**handler/**
```
GatewayExceptionHandler.java   — 全局异常处理（统一错误响应格式）
```

**util/**
```
JwtUtil.java                   — JWT工具类（解析/验证Token，Gateway专用，不依赖WebMVC）
HmacUtil.java                  — HMAC-SHA256签名工具类
```

**dto/**
```
GatewayResponse.java           — 网关统一响应（code+message+data）
```

#### 3.18.7 核心技术点

| 技术点 | 方案 | 关键实现 |
|--------|------|----------|
| JWT鉴权 | GlobalFilter + Redis黑名单 | 解析Token→校验签名→检查黑名单→提取userId→放入Header |
| 白名单机制 | Nacos配置 + AntPathMatcher | 登录/注册/公开详情等路径放行，不需要Token |
| HMAC签名 | timestamp + nonce + signature | 请求头含X-Timestamp/X-Nonce/X-Signature，5分钟内有效，nonce防重放 |
| Sentinel限流 | Gateway适配器 + Nacos数据源 | 按接口+用户维度限流，规则从Nacos动态加载 |
| 灰度路由 | Nacos元数据 + Gateway路由 | 灰度实例在Nacos标记`gray-tag=gray`，请求头`X-Gray-Tag=gray`匹配 |
| 流量染色 | Header透传 | 网关注入`X-Trace-Id`+`X-User-Id`+`X-Gray-Tag`，下游服务+Feign+MQ透传 |
| API版本 | Header X-Api-Version | v1→稳定版实例，v2→新版本实例，Nacos元数据标记版本 |
| WebSocket路由 | Spring Cloud Gateway原生支持 | `/api/im/ws` 路由到 IM 服务的 WebSocket 端点 |

#### 3.18.8 鉴权过滤器处理流程

```
请求入站
    │
    ▼
┌─────────────────┐
│ 白名单匹配      │──── 是 ──▶ 放行
│ AntPathMatcher  │
└────────┬────────┘
         │ 否
         ▼
┌─────────────────┐
│ 提取Token       │──── 无Token ──▶ 返回401
│ Authorization   │
└────────┬────────┘
         │ 有Token
         ▼
┌─────────────────┐
│ 校验JWT签名     │──── 签名无效 ──▶ 返回401
│ jjwt解析        │
└────────┬────────┘
         │ 签名有效
         ▼
┌─────────────────┐
│ 检查Token黑名单 │──── 已加入 ──▶ 返回401
│ Redis GET       │
└────────┬────────┘
         │ 未加入
         ▼
┌─────────────────┐
│ 提取userId      │
│ 注入Header      │
│ X-User-Id       │
│ X-Trace-Id      │
└────────┬────────┘
         │
         ▼
       放行
```

#### 3.18.9 HMAC签名验证流程

```
客户端请求（需签名的接口）
    │
    ▼
┌──────────────────────────────────────┐
│ 1. 拼接签名字符串                     │
│    String signStr = method + path    │
│                   + timestamp + nonce│
│                   + body(如有)       │
│                                      │
│ 2. HMAC-SHA256签名                   │
│    signature = HmacSHA256(signStr,   │
│                           secretKey) │
│                                      │
│ 3. 请求头携带                        │
│    X-Timestamp: 1715409600000        │
│    X-Nonce: uuid                     │
│    X-Signature: hmac-sha256值        │
└──────────────────────────────────────┘
         │
         ▼
┌──────────────────────────────────────┐
│ Gateway校验                          │
│ 1. timestamp与当前时间差>5min → 拒绝 │
│ 2. nonce在Redis中已存在 → 重放拒绝   │
│ 3. 重新计算HMAC，与signature比对     │
│ 4. 不匹配 → 篡改拒绝                │
│ 5. 匹配 → nonce写入Redis(TTL=5min)  │
└──────────────────────────────────────┘
```

---

### 功能 19：即时通讯IM

#### 3.19.1 功能描述

即时通讯服务基于 WebSocket（Spring WebFlux + Netty）实现实时私信功能。支持文本/图片/语音/视频/自定义消息类型，消息持久化到 MySQL（分表），会话列表使用 Redis ZSet 维护（按最新消息时间排序），未读计数使用 Redis String。已读回执通过 WebSocket 推送，使用 Redis Bitmap 记录已读状态。消息发送流程：客户端→WebSocket→IM服务→MQ异步持久化→WebSocket推送给接收方→更新会话列表和未读数。

#### 3.19.2 涉及服务

| 服务 | 角色 | 说明 |
|------|------|------|
| my-xhs-im | 主服务 | WebSocket连接管理、消息收发、会话管理 |
| my-xhs-user | 被调用 | 用户信息查询（Feign） |
| my-xhs-notification | 被调用 | 离线消息通知推送（Feign） |

#### 3.19.3 API 接口清单

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| GET | /api/im/ws | WebSocket连接端点 | ✅ Token参数 |
| GET | /api/im/conversations | 会话列表 | ✅ |
| GET | /api/im/conversations/{targetUserId}/messages | 聊天记录（分页） | ✅ |
| POST | /api/im/message/send | 发送消息（HTTP方式，备用） | ✅ |
| PUT | /api/im/conversations/{targetUserId}/read | 标记已读 | ✅ |
| GET | /api/im/unread/count | 未读消息总数 | ✅ |
| DELETE | /api/im/conversations/{targetUserId} | 删除会话 | ✅ |

#### 3.19.4 数据库表

**t_chat**（预估 50亿，分表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 消息ID（雪花算法生成） |
| send_uid | BIGINT | 发送者UID |
| accept_uid | BIGINT | 接收者UID |
| content | LONGTEXT | 消息内容 |
| msg_type | TINYINT | 消息类型:0通知1文本2图片3语音4视频5自定义 |
| chat_type | TINYINT | 聊天类型:0私聊1群聊 |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

> KEY `idx_send_uid` (send_uid), KEY `idx_accept_uid` (accept_uid), KEY `idx_send_accept_created` (send_uid, accept_uid, created_at)

**分表策略**：
- 分表键：`send_uid % 64`（按发送者ID取模）
- 分表数：64张（t_chat_00 ~ t_chat_63）
- 路由规则：查询发送者消息→按 send_uid 路由；查询接收者消息→按 accept_uid 路由（冗余写入两份，保证按发送/接收方查询都能命中同一分表）
- 未来扩展：若数据量继续增长，可在分表基础上再按时间分库

**t_chat_user_relation**（预估 10亿）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 记录ID |
| send_uid | BIGINT | 发送者UID |
| accept_uid | BIGINT | 接收者UID |
| content | LONGTEXT | 最后一条消息内容 |
| un_read_count | INT | 未读数 |
| msg_type | TINYINT | 消息类型 |
| chat_type | TINYINT | 聊天类型 |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

> KEY `idx_accept_uid` (accept_uid, created_at), KEY `idx_send_accept` (send_uid, accept_uid, created_at)

#### 3.19.5 Redis Key 清单

| Key | 数据结构 | TTL | 说明 |
|-----|---------|-----|------|
| `im:conversation:{userId}` | ZSet | 永久 | 会话列表 score=最新消息时间 member=对方userId |
| `im:unread:{userId}:{targetUserId}` | String | 永久 | 单会话未读数 |
| `im:unread:total:{userId}` | String | 永久 | 总未读消息数 |
| `im:online:{userId}` | String | 5min | 在线状态（心跳续期） |
| `im:read:bitmap:{userId}:{targetUserId}` | Bitmap | 90d | 已读状态位图 |

#### 3.19.6 Java 文件清单

**controller/**
```
ImWebSocketHandler.java        — WebSocket处理器（连接/断开/消息收发）
ImConversationController.java — 会话列表/已读/删除
ImMessageController.java      — HTTP消息发送（备用）
```

**service/**
```
ImMessageService.java          — 消息业务接口
ImMessageServiceImpl.java      — 消息业务实现（发送/持久化/推送）
ImConversationService.java     — 会话业务接口
ImConversationServiceImpl.java — 会话业务实现（列表/未读/已读）
ImOnlineService.java           — 在线状态管理接口
ImOnlineServiceImpl.java       — 在线状态管理实现（Redis心跳）
```

**ws/**
```
WebSocketSessionManager.java   — WebSocket会话管理（userId↔Session映射）
WebSocketMessageDispatcher.java — 消息分发器（根据消息类型路由处理）
```

**mq/**
```
ChatMessageProducer.java       — 聊天消息生产者（发送→MQ→持久化+推送）
ChatMessageConsumer.java       — 聊天消息消费者（MQ→持久化→推送接收方）
```

**config/**
```
WebSocketConfig.java           — WebSocket配置（端点注册、跨域、拦截器）
RedisConfig.java               — Redis序列化配置
WebFluxConfig.java             — WebFlux CORS + 拦截器（Reactive栈，非WebMvc）
```

**mapper/**
```
ChatMapper.java                — 聊天记录Mapper
ChatUserRelationMapper.java    — 会话关系Mapper
```

**entity/**
```
Chat.java                      — 聊天记录实体
ChatUserRelation.java          — 会话关系实体
```

**dto/**
```
ChatMessageDTO.java            — WebSocket消息DTO（type+content+targetUserId）
ChatMessageVO.java             — 聊天消息VO
ConversationVO.java            — 会话VO（对方信息+最后一条消息+未读数）
```

**feign/**
```
UserFeignClient.java           — 用户服务Feign（获取用户信息）
NotificationFeignClient.java   — 通知服务Feign（离线推送）
```

#### 3.19.7 核心技术点

| 技术点 | 方案 | 关键实现 |
|--------|------|----------|
| WebSocket | Spring WebFlux + Netty | 基于Reactive栈，单机支撑10万连接 |
| 消息持久化 | MQ异步写入 | 发送→MQ→Consumer批量写入MySQL，不阻塞主流程 |
| 会话列表 | Redis ZSet | score=最新消息时间，ZREVRANGE分页获取 |
| 未读计数 | Redis INCR/DECR | 发送消息INCR未读，标记已读DECR未读 |
| 在线检测 | Redis Key + 心跳 | 客户端每30秒心跳，服务端刷新Key TTL=5min |
| 离线推送 | 通知服务Feign | 接收方不在线时，调用Notification服务推送 |
| 已读回执 | WebSocket推送 + Bitmap | 标记已读→推送对方→Bitmap记录已读状态 |
| 消息ID | 雪花算法 | 全局唯一，有序，不依赖MySQL自增 |

#### 3.19.8 消息发送流程

```
发送消息
┌────────┐   ┌──────────┐   ┌──────────┐   ┌──────────┐
│ 客户端  │──▶│WebSocket │──▶│ IM服务   │──▶│ RocketMQ │
│ 发送   │   │ Handler  │   │ 校验+入队│   │ 异步持久化│
└────────┘   └──────────┘   └──────────┘   └────┬─────┘
                                                   │
                    ┌──────────────────────────────┤
                    ▼                              ▼
          ┌──────────────┐              ┌──────────────┐
          │ Consumer     │              │ Consumer     │
          │ 持久化MySQL  │              │ 推送接收方    │
          │ 更新会话列表  │              │ WebSocket    │
          └──────────────┘              └──────┬───────┘
                                               │
                                    ┌──────────┴──────────┐
                                    ▼                     ▼
                              ┌──────────┐         ┌──────────┐
                              │ 在线推送  │         │ 离线推送  │
                              │ WebSocket│         │ 通知服务  │
                              └──────────┘         └──────────┘
```

---

### 功能 20：分布式基础组件

#### 3.20.1 功能描述

公共基础组件是所有微服务共用的基础设施，Phase 4 需要在 my-xhs-common 中实现4个核心自定义注解组件：①`@Idempotent`幂等注解（基于Redis SET NX，防止重复提交/重复消费）；②`@DistributedLock`分布式锁注解（基于Redisson，支持SpEL参数解析、可重入、自动释放）；③`@RateLimit`限流注解（基于Redis滑动窗口，支持接口级+用户级限流）；④雪花ID生成器（基于CosId，替代MySQL自增ID，提供全局唯一有序ID）。

#### 3.20.2 涉及服务

| 服务 | 角色 | 说明 |
|------|------|------|
| my-xhs-common | 主服务 | 注解定义+AOP切面+工具类 |
| 所有服务 | 使用方 | 引入common模块即可使用4个注解 |

#### 3.20.3 组件清单

##### 3.20.3.1 @Idempotent 幂等注解

**功能**：防止接口重复提交/MQ重复消费，基于 Redis SET NX + 过期时间实现。

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
    /** 幂等Key的SpEL表达式，支持从方法参数中提取 */
    String key();
    /** 过期时间（秒），默认60秒 */
    long expireSeconds() default 60;
    /** 时间单位 */
    TimeUnit timeUnit() default TimeUnit.SECONDS;
    /** Key前缀 */
    String prefix() default "idempotent";
    /** 重复请求时的提示信息 */
    String message() default "请勿重复操作";
}
```

**Redis Key**：`idempotent:{prefix}:{key的SpEL解析值}`

**AOP切面**：`IdempotentAspect.java`
- 拦截标注了 `@Idempotent` 的方法
- 解析SpEL表达式生成Key
- 执行 `SET NX EX` 操作
- 设置成功→放行；设置失败→抛出 `IdempotentException`

##### 3.20.3.2 @DistributedLock 分布式锁注解

**功能**：基于 Redisson 的分布式锁，支持SpEL参数解析、可重入、自动释放、等待超时。

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {
    /** 锁Key的SpEL表达式 */
    String key();
    /** 等待获取锁的超时时间（秒），默认3秒 */
    long waitTime() default 3;
    /** 锁自动释放时间（秒），默认30秒 */
    long leaseTime() default 30;
    /** 时间单位 */
    TimeUnit timeUnit() default TimeUnit.SECONDS;
    /** Key前缀 */
    String prefix() default "lock";
    /** 获取锁失败时的提示信息 */
    String message() default "操作过于频繁，请稍后重试";
}
```

**Redis Key**：`lock:{prefix}:{key的SpEL解析值}`

**AOP切面**：`DistributedLockAspect.java`
- 拦截标注了 `@DistributedLock` 的方法
- 解析SpEL表达式生成Key
- 获取 Redisson RLock，`tryLock(waitTime, leaseTime)`
- 获取成功→执行方法→finally释放锁
- 获取失败→抛出 `DistributedLockException`

##### 3.20.3.3 @RateLimit 限流注解

**功能**：基于 Redis 滑动窗口算法的限流，支持接口级+用户级双重限流。

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
    /** 限流Key（默认使用方法签名） */
    String key() default "";
    /** 时间窗口（秒） */
    int windowSeconds() default 1;
    /** 窗口内最大请求数 */
    int maxRequests() default 100;
    /** 是否按用户限流（从Header提取X-User-Id） */
    boolean perUser() default false;
    /** Key前缀 */
    String prefix() default "ratelimit";
    /** 限流提示信息 */
    String message() default "请求过于频繁，请稍后重试";
}
```

**Redis Key**：`ratelimit:{prefix}:{key}:{userId(如果perUser=true)}`

**AOP切面**：`RateLimitAspect.java`
- 拦截标注了 `@RateLimit` 的方法
- 基于Redis Lua脚本实现滑动窗口
- 超出限制→抛出 `RateLimitException`

**Lua脚本核心逻辑**：
```lua
-- 移除窗口外的记录
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1])
-- 统计窗口内请求数
local count = redis.call('ZCARD', KEYS[1])
if count < tonumber(ARGV[2]) then
    redis.call('ZADD', KEYS[1], ARGV[3], ARGV[3])
    redis.call('EXPIRE', KEYS[1], ARGV[4])
    return 1
else
    return 0
end
```

**Lua脚本参数映射**：

| 参数 | 值 | 说明 |
|------|---|------|
| KEYS[1] | `ratelimit:{prefix}:{key}:{userId}` | 限流ZSet Key |
| ARGV[1] | 当前时间戳（毫秒） - windowSeconds * 1000 | 窗口下界（移除过期记录） |
| ARGV[2] | maxRequests | 窗口内最大请求数 |
| ARGV[3] | 当前时间戳（毫秒） | ZADD score（当前请求时间） |
| ARGV[4] | windowSeconds | Key过期时间（秒） |

##### 3.20.3.4 雪花ID生成器（CosId）

**功能**：全局唯一有序ID生成，替代MySQL自增ID，支持分布式环境。

**方案选型**：
| 方案 | 优点 | 缺点 | 选择 |
|------|------|------|------|
| MySQL自增 | 简单 | 单点、性能差、分库分表不唯一 | ❌ |
| UUID | 简单、无依赖 | 无序、存储空间大、索引效率低 | ❌ |
| 手写雪花算法 | 高性能 | 时钟回拨问题、workerId分配复杂 | ❌ |
| **CosId** | 高性能、解决时钟回拨、Provider自动化workerId | 引入额外依赖 | ✅ |

**CosId配置**：
```yaml
cosid:
  machine:
    enabled: true
    distributor:
      type: manual
      manual:
        machine-id: 1
  generator:
    enabled: true
    provider:
      default:
        type: snowflake
        snowflake:
          epoch: 1704067200000  # 2024-01-01 00:00:00
```

**工具类**：`IdGeneratorUtil.java`
```java
@Component
public class IdGeneratorUtil {
    private final IdGenerator idGenerator;

    public IdGeneratorUtil(CosIdProvider cosIdProvider) {
        this.idGenerator = cosIdProvider.getProvider().getIdGenerator("default");
    }

    public long nextId() {
        return idGenerator.nextId();
    }
}
```

#### 3.20.4 Java 文件清单

**annotation/**
```
Idempotent.java               — 幂等注解
DistributedLock.java           — 分布式锁注解
RateLimit.java                 — 限流注解
```

**aspect/**
```
IdempotentAspect.java          — 幂等AOP切面（Redis SET NX + SpEL解析）
DistributedLockAspect.java     — 分布式锁AOP切面（Redisson RLock + SpEL解析）
RateLimitAspect.java           — 限流AOP切面（Redis Lua滑动窗口）
```

**id/**
```
IdGeneratorUtil.java           — 雪花ID生成工具类（基于CosId）
```

**exception/**
```
IdempotentException.java       — 幂等异常
DistributedLockException.java  — 分布式锁获取失败异常
RateLimitException.java        — 限流异常
```

**spel/**
```
SpELParser.java                — SpEL表达式解析器（从方法参数中提取Key值）
```

**config/**
```
MyXhsCommonAutoConfiguration.java — 自定义Starter自动配置类（@Import + @Conditional组合）
CosIdConfig.java               — CosId配置类（雪花ID生成器）
RedissonConfig.java            — Redisson配置类（分布式锁依赖）
```

**META-INF/**
```
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
                               — Spring Boot 3.x 自动配置声明（替代spring.factories）
```

**dto/**
```
BaseEntity.java                — 实体基类（id + createdAt + updatedAt）
R.java                         — 统一响应封装（code + message + data）
PageResult.java                — 分页结果封装
```

**util/**
```
UserContextHolder.java         — 用户上下文工具（ThreadLocal存储userId/traceId）
JsonUtil.java                  — JSON工具类（FastJSON2封装）
RedisUtil.java                 — Redis工具类（通用操作封装）
```

---

### 📚 训练营知识补充（§3.20.5 ~ §3.20.11）

> 以下内容来自云原生架构训练营的学习总结。**这些知识不是"仅供参考"——它们已落地到 my-xhs 项目的实现步骤中**：
> - **Spring 7大扩展点（§3.20.9）** → Step 1：自定义 Starter 自动配置、AOP 切面实现
> - **Java 扩展点五层架构（§3.20.10）** → Step 1/2：Java SPI（CosId Provider 发现）、JCA Provider（HMAC 签名）、SLF4J SPI（traceId 传播）
> - **Dubbo SPI（§3.20.8）** → mini-rpc 迭代方向、理解 Sentinel Slot Chain 原理
> - **注册中心选型（§3.20.5）** → 确认 Nacos 选型，理解 Nacos 在灰度路由中的元数据能力
> - **微服务拆分策略（§3.20.6）** → 验证 my-xhs 拆分合理性，理解 IM 服务为何用 WebFlux
> - **Dubbo 选型分析（§3.20.11）** → 确认 Spring Cloud 选型，理解何时需要 Dubbo

#### 3.20.5 注册中心选型对比（P2 补充）

> Stage-3 课程 009-010 讲了 Eureka 注册与发现，但缺少注册中心横向对比。以下是批判性分析。

| 维度 | Eureka | Nacos | ZooKeeper | Consul |
|------|--------|-------|-----------|--------|
| **CAP模型** | AP（高可用） | AP+CP（可切换） | CP（强一致） | CP（Raft协议） |
| **健康检查** | 客户端心跳（30s） | 心跳（临时实例）/ TCP（持久实例） | 会话保持（Session） | HTTP/TCP/gRPC脚本 |
| **配置中心** | ❌ 无 | ✅ 内置 | ⚠️ 可做但弱 | ✅ KV存储 |
| **推送方式** | 定时全量拉取（30s） | 长轮询（1.x）/ gRPC推送（2.x） | Watch事件通知 | Watch + Long Polling |
| **实例类型** | 仅临时实例 | 临时+持久 | 持久（临时需自己实现） | 临时+持久 |
| **跨集群同步** | ❌ Peer-to-Peer全量 | ✅ 异步Distro协议 | ❌ 需Observer节点 | ✅ WAN Federation |
| **多活支持** | ❌ 不支持 | ✅ Namespace + Group + Cluster | ⚠️ 需自研 | ✅ 数据中心感知 |
| **社区活跃度** | ❌ 停止维护（2.x不再开源） | ✅ 阿里巴巴（活跃） | ✅ Apache（稳定） | ✅ HashiCorp（活跃） |
| **适用场景** | — | Spring Cloud + K8s | Hadoop/Kafka/Dubbo老项目 | 多语言/多数据中心 |

**批判性思考**：
- 小马哥课程仍讲 Eureka，但 **Eureka 2.x 已停止开发**，1.x 进入维护模式，**新项目不应再使用**
- Nacos 是当前 Java 微服务注册中心的**最佳选择**：AP+CP双模式、配置中心一体化、Spring Cloud/Alibaba 双生态
- ZooKeeper 做注册中心是**反模式**——CP模型在注册发现场景不合适（网络分区时不可用比不一致更可怕），但 Dubbo 社区仍在用
- Consul 适合**多语言微服务 + 多数据中心**场景，如 Go/Python 混合架构

**my-xhs 选型结论**：Nacos，已在用，无需更换。

#### 3.20.6 微服务拆分策略理论（P2 补充）

> Stage-3 课程 008 讲了微服务架构升级，缺少拆分策略理论。以下是补充，与 my-xhs 的实际拆分对照。

| 拆分策略 | 核心思想 | 优点 | 缺点 | 适用场景 |
|---------|---------|------|------|---------|
| **按业务域拆分** | 按业务边界划分（用户/商品/订单） | 团队自治，职责清晰 | 可能导致大服务 | 最常见，my-xhs 采用 |
| **按子域拆分**（DDD） | 限界上下文=微服务（核心域/支撑域/通用域） | 架构与业务对齐 | 需要DDD建模能力 | 复杂业务系统 |
| **按能力拆分** | 按技术能力（搜索/推荐/消息） | 技术深度 | 跨域协调复杂 | 基础设施团队 |
| **按数据拆分** | 每个服务独占数据 | 数据自治 | 跨服务查询难 | 数据敏感型 |

**my-xhs 的拆分验证**：

| my-xhs 服务 | 拆分依据 | 对应DDD子域 | 评估 |
|-------------|---------|-----------|------|
| my-xhs-user | 业务域 | 通用域（用户管理） | ✅ 独立性高 |
| my-xhs-product | 业务域 | 核心域（商品） | ✅ 独立性高 |
| my-xhs-order | 业务域 | 核心域（交易） | ✅ 独立性高 |
| my-xhs-content | 业务域 | 核心域（内容/笔记） | ✅ 独立性高 |
| my-xhs-search | 能力 | 支撑域（搜索能力） | ✅ 技术深度 |
| my-xhs-recommend | 能力 | 支撑域（推荐能力） | ✅ 技术深度 |
| my-xhs-im | 业务域 | 通用域（即时通讯） | ✅ 独立性高 |
| my-xhs-gateway | 基础设施 | — | ✅ 无业务逻辑 |
| my-xhs-bff | 基础设施 | — | ✅ 仅聚合 |

**关键原则**：
1. **服务间通过API/MQ通信，禁止跨库直连**（my-xhs 已遵守）
2. **每个服务独立数据库**（my-xhs 已遵守，除分库分表场景外）
3. **服务内高内聚，服务间低耦合**（my-xhs 的 BFF 聚合模式是合理的）
4. **先粗后细**（先按业务域拆大服务，业务复杂后再拆子服务）

#### 3.20.7 核心技术点（功能20主体）

> ⚠️ 本节属于功能20的主体内容，非训练营补充。

| 技术点 | 方案 | 关键实现 |
|--------|------|----------|
| 幂等 | Redis SET NX + SpEL | AOP拦截→解析Key→SET NX→成功放行，失败拒绝 |
| 分布式锁 | Redisson RLock + SpEL | AOP拦截→解析Key→tryLock→执行→finally unlock |
| 限流 | Redis Lua滑动窗口 | ZREMRANGEBYSCORE+ZCARD判断窗口内请求数 |
| SpEL解析 | Spring ExpressionParser | 从方法参数中动态提取Key值，支持`#userId`、`#request.orderId`等 |
| 雪花ID | CosId | 解决时钟回拨问题，自动分配workerId，比手写更可靠 |
| 统一响应 | R\<T\>封装 | code=0成功，code≠0失败，message描述，data泛型 |
| 用户上下文 | ThreadLocal | Filter/Interceptor存入userId，Service层获取 |

#### 3.20.8 Dubbo SPI 扩展机制与架构深度（P1 补充）

> Stage-3 课程 024 讲了 Dubbo 内核，但缺少 SPI 扩展机制的深度讲解。以下是补充，与 sca-demo mini-rpc 形成对照学习。

##### （1）Dubbo SPI vs Java SPI

| 维度 | Java SPI | Dubbo SPI |
|------|---------|-----------|
| 配置文件 | `META-INF/services/接口全限定名` | `META-INF/dubbo/接口全限定名` |
| 加载策略 | 一次性加载所有实现 | 按需加载（lazy load） |
| 依赖注入 | ❌ 不支持 | ✅ 自动注入（@Inject） |
| 自适应扩展 | ❌ 不支持 | ✅ @Adaptive（URL参数动态选择实现） |
| 自动激活 | ❌ 不支持 | ✅ @Activate（条件自动激活） |
| Wrapper机制 | ❌ 不支持 | ✅ 自动包装（AOP代理） |

##### （2）Dubbo SPI 核心注解

```java
// 1. @SPI：标记扩展点接口，指定默认实现
@SPI("dubbo")  // 默认使用 dubbo 协议
public interface Protocol {
    @Adaptive  // 自适应扩展，运行时根据URL参数选择实现
    <T> Exporter<T> export(Invoker<T> invoker) throws RpcException;
}

// 2. @Adaptive：自适应扩展
//    运行时生成代理类，根据 URL 中的参数选择具体实现
//    例如 URL: dubbo://host:port?protocol=dubbo → 选择 DubboProtocol
//    例如 URL: dubbo://host:port?protocol=triple → 选择 TripleProtocol

// 3. @Activate：条件自动激活
@Activate(group = CommonConst.PROVIDER, order = -9000)
public class AccessLogFilter implements Filter {
    // Provider端自动激活，用于记录访问日志
}

// 4. @Inject：扩展点依赖注入
public class DubboProtocol extends AbstractProtocol {
    @Inject  // 自动注入 Dispatcher 扩展点
    private Dispatcher dispatcher;
}
```

##### （3）Dubbo 架构扩展点全景

```
+-------------------------------------------------------+
|                    ServiceConfig                       |
|  (服务发布/订阅配置，整合所有扩展点)                       |
+-------------------------------------------------------+
         |              |              |              |
    Protocol        Router         Filter        LoadBalance
    (协议扩展)       (路由扩展)      (过滤器扩展)    (负载均衡扩展)
    dubbo/triple    条件/脚本/标签   监控/限流/日志   random/roundrobin/leastactive
         |              |              |              |
+-------------------------------------------------------+
|                    Invoker 调用链                       |
|  Router → Filter链 → LoadBalance → Protocol → 网络     |
+-------------------------------------------------------+
```

| 扩展点 | 职责 | 内置实现 | 扩展场景 |
|--------|------|---------|---------|
| **Protocol** | 协议编解码+网络传输 | Dubbo/Triple/Rest/Injvm | 自定义协议（如gRPC兼容） |
| **Filter** | 请求拦截链（类似Servlet Filter） | AccessLog/Monitor/Token/Timeout | 限流/熔断/链路追踪/灰度路由 |
| **Router** | 服务路由（从多个Provider中筛选） | Condition/Script/Tag/App | 灰度发布/同机房优先/流量染色 |
| **LoadBalance** | 负载均衡策略 | Random/RoundRobin/LeastActive/ConsistentHash | 一致性哈希/权重路由 |
| **Cluster** | 集群容错策略 | Failover/Failfast/Failsafe/Forking/Broadcast | 自定义容错策略 |
| **Serialization** | 序列化协议 | Hessian2/Fastjson/Protobuf/Kryo | 高性能序列化（如Protobuf） |
| **Transport** | 网络传输层 | Netty/Mina | 自定义网络层 |
| **Registry** | 注册中心 | Zookeeper/Nacos/Redis/Multicast | 自定义注册中心（如etcd） |

##### （4）与 mini-rpc 的对照学习

| Dubbo 扩展点 | mini-rpc 对应实现 | 差距分析 |
|-------------|-----------------|---------|
| Protocol | SimpleProtocol（自定义协议编解码） | ✅ 已实现基础版，可扩展为Triple协议 |
| Filter | ❌ 未实现 | 🔴 需补充，建议实现：链路追踪Filter + 限流Filter |
| Router | ❌ 未实现 | 🟡 可选，灰度路由需依赖注册中心元数据 |
| LoadBalance | SimpleLoadBalance（随机/轮询） | ✅ 基础版已有，可扩展一致性哈希 |
| Cluster | ❌ 未实现 | 🟡 可选，Failover重试是基本需求 |
| Serialization | JSON（默认） | ✅ 可扩展Protobuf/Kryo提升性能 |
| Registry | mini-nacos | ✅ 已实现 |
| Transport | Netty（如有）或Socket | ✅ 可升级为Netty |

**mini-rpc 迭代建议**（按优先级）：
1. **P0**：实现 Filter 链（链路追踪 + 限流 + 监控），这是 RPC 框架最核心的扩展点
2. **P1**：实现 SPI 扩展机制（参考 Dubbo 的 ExtensionLoader），使组件可插拔
3. **P1**：实现 Cluster 容错策略（Failover 重试 + Failfast 快速失败）
4. **P2**：实现 Router 路由策略（灰度发布/同机房优先）
5. **P2**：支持多种序列化协议（Protobuf/Kryo/JSON可切换）

##### （5）序列化协议对比

| 协议 | 性能 | 体积 | 跨语言 | 可读性 | 适用场景 |
|------|------|------|--------|--------|---------|
| **JSON** | 低 | 大 | ✅ | ✅ 高 | 调试/对外API |
| **Hessian2** | 中 | 中 | ✅ | ❌ 二进制 | Dubbo默认协议 |
| **Protobuf** | 高 | 小 | ✅ | ❌ 二进制 | 高性能跨语言 |
| **Kryo** | 高 | 小 | ❌ 仅Java | ❌ 二进制 | Java内部高性能 |
| **FST** | 高 | 小 | ❌ 仅Java | ❌ 二进制 | Java内部高性能 |

**批判性思考**：
- 小马哥课程推荐 Hessian2，但 **Triple 协议（Dubbo 3.x 默认）基于 HTTP/2 + Protobuf**，是更好的选择
- my-xhs 的 mini-rpc 当前使用 JSON 序列化，开发阶段足够，生产环境需切换到 Protobuf 或 Kryo

#### 3.20.9 Java 框架扩展点全景 — Spring 7 大扩展机制（P0 补充）

> 云原生架构训练营§4.1 的核心洞察：理解 Spring Boot 自动配置的前提是掌握 Spring 框架的 7 大扩展点。这些扩展点不是"高级特性"，而是 Spring Boot "约定优于配置"的底层机制。不掌握它们，就无法真正理解 Starter 是如何自动注入 Bean 的。

##### （1）Spring 7 大扩展机制全景

```
┌─────────────────────────────────────────────────────────────────┐
│                   Spring Framework 扩展点全景                     │
│                                                                   │
│  ┌─────────────────────────────────────────────────────────┐     │
│  │ Bean 定义阶段（IoC 容器启动早期）                           │     │
│  │  1. @Import + ImportSelector  → 批量导入 @Configuration    │     │
│  │  2. ImportBeanDefinitionRegistrar → 编程式注册 BeanDefinition│   │
│  │  3. BeanFactoryPostProcessor → 修改 BeanDefinition（占位符、属性覆盖）│
│  └─────────────────────────────────────────────────────────┘     │
│                          ↓                                        │
│  ┌─────────────────────────────────────────────────────────┐     │
│  │ Bean 创建阶段（实例化 + 初始化）                            │     │
│  │  4. FactoryBean → 复杂对象的工厂方法（MyBatis SqlSessionFactory）│  │
│  │  5. BeanPostProcessor → Bean 初始化前后拦截（AOP代理、校验） │    │
│  │     ├─ postProcessBeforeInitialization → @PostConstruct 前置│   │
│  │     └─ postProcessAfterInitialization  → AOP 代理创建点     │   │
│  │  6. Aware 接口族 → 注入容器资源（BeanName/BeanFactory/ApplicationContext）│
│  └─────────────────────────────────────────────────────────┘     │
│                          ↓                                        │
│  ┌─────────────────────────────────────────────────────────┐     │
│  │ Bean 运行阶段                                               │     │
│  │  7. ApplicationListener → 监听容器事件（ContextRefreshed/EnvironmentPostProcess）│
│  └─────────────────────────────────────────────────────────┘     │
└─────────────────────────────────────────────────────────────────┘
```

##### （2）每个扩展点的核心作用与 my-xhs 中的使用

**① @Import + ImportSelector — 条件性批量导入配置类**

```java
// Spring Boot 自动配置的入口：spring.factories / AutoConfiguration.imports
// 每个 Starter 的核心就是这个机制

// 原理：@Import 可以导入三种内容
@Import(NacosConfigConfiguration.class)           // 1. 直接导入一个 @Configuration
@Import(CustomImportSelector.class)                // 2. 导入 ImportSelector（动态选择配置类）
@Import(CustomRegistrar.class)                     // 3. 导入 ImportBeanDefinitionRegistrar

// ImportSelector：根据条件动态选择要导入的配置类
public class NacosConfigImportSelector implements DeferredImportSelector {
    @Override
    public String[] selectImports(AnnotationMetadata metadata) {
        // 读取 spring.factories / AutoConfiguration.imports
        // 返回需要自动配置的全限定类名数组
        return new String[]{
            NacosConfigAutoConfiguration.class.getName(),
            NacosConfigBeanDefinitionRegistrar.class.getName()
        };
    }
}
```

> my-xhs 使用场景：`spring-cloud-starter-alibaba-nacos-config`、`spring-cloud-starter-openfeign` 等 Starter 都依赖此机制。理解后可自定义 Starter。

**② ImportBeanDefinitionRegistrar — 编程式注册 BeanDefinition**

```java
// 比 ImportSelector 更强大：可以编程式控制 BeanDefinition 的所有属性
// MyBatis @MapperScan 就是用这个机制

public class MapperScannerRegistrar implements ImportBeanDefinitionRegistrar {
    @Override
    public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
        // 扫描 @Mapper 注解的接口，为每个接口注册一个 BeanDefinition
        // BeanDefinition 的 beanClass 设为 MapperFactoryBean（FactoryBean 模式）
        ClassPathMapperScanner scanner = new ClassPathMapperScanner(registry);
        scanner.scan("com.myxhs.**.mapper");
    }
}
```

> my-xhs 使用场景：MyBatis Plus 的 `@MapperScan`、Dubbo 的 `@DubboService` 扫描都是这个机制。

**③ BeanFactoryPostProcessor — 修改 BeanDefinition**

```java
// 在 Bean 实例化之前，修改 BeanDefinition 的属性
// Spring Boot 的属性绑定（@Value + ${}）就是通过这个机制实现的

public class PropertyPlaceholderConfigurer implements BeanFactoryPostProcessor {
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory factory) {
        // 遍历所有 BeanDefinition，将 ${xxx} 占位符替换为实际值
        // 这就是为什么 @Value("${server.port}") 能在 Bean 创建前解析
    }
}
```

> my-xhs 使用场景：Nacos Config 的 `NacosConfigProcessor`、`@RefreshScope` 的 `CglibProxyBeanDefinitionRegistry` 都是此机制。

**④ FactoryBean — 复杂对象的工厂**

```java
// 当一个 Bean 的创建逻辑过于复杂，不适合用 @Bean 方法表达时
// MyBatis 的 SqlSessionFactory、Dubbo 的 ReferenceBean 都用 FactoryBean

public class SqlSessionFactoryBean implements FactoryBean<SqlSessionFactory> {
    @Override
    public SqlSessionFactory getObject() {
        // 复杂的创建逻辑：解析 XML → 构建 Configuration → 创建 SqlSessionFactory
        return new SqlSessionFactoryBuilder().build(inputStream);
    }

    @Override
    public Class<?> getObjectType() {
        return SqlSessionFactory.class;
    }
}
```

> my-xhs 使用场景：每个 FeignClient 本质上都是通过 `FeignClientFactoryBean` 创建的代理对象。

**⑤ BeanPostProcessor — Bean 初始化前后拦截**

```java
// Spring AOP 的核心：在 postProcessAfterInitialization 中创建代理对象
// 也是各种"自动注入"的底层机制

// 常见的 BeanPostProcessor 实现：
// - AutowiredAnnotationBeanPostProcessor → 处理 @Autowired/@Value 注入
// - CommonAnnotationBeanPostProcessor → 处理 @Resource/@PostConstruct/@PreDestroy
// - AsyncAnnotationBeanPostProcessor → 处理 @Async（创建AOP代理）
// - ScheduledAnnotationBeanPostProcessor → 处理 @Scheduled
// - ApplicationListenerDetector → 检测 ApplicationListener 接口实现

// 自定义 BeanPostProcessor 示例：自动校验 @ConfigurationProperties
public class ConfigPropertiesValidator implements BeanPostProcessor {
    @Override
    public Object postProcessBeforeInitialization(Object bean, String name) {
        if (bean.getClass().isAnnotationPresent(ConfigurationProperties.class)) {
            // 在 Bean 初始化前校验配置属性
            validateConfigurationProperties(bean);
        }
        return bean;
    }
}
```

> my-xhs 使用场景：`@RateLimit` 注解的 AOP 切面、`@Idempotent` 幂等注解处理、SkyWalking Agent 字节码增强等都可视为广义的 BeanPostProcessor。

**⑥ Aware 接口族 — 注入容器资源**

```java
// Aware 接口让 Bean 获取容器的内部资源
// 不要滥用！只在确实需要容器资源时使用

| Aware 接口 | 注入的资源 | 使用场景 |
|-----------|----------|---------|
| BeanNameAware | Bean 的名称 | 日志记录、缓存Key |
| BeanFactoryAware | BeanFactory | 动态获取 Bean |
| ApplicationContextAware | ApplicationContext | 发布事件、获取环境变量 |
| EnvironmentAware | Environment | 读取配置 |
| ResourceLoaderAware | ResourceLoader | 加载类路径资源 |
| ApplicationEventPublisherAware | 事件发布器 | 发布 ApplicationEvent |
| EmbeddedValueResolverAware | StringResolver | 解析 ${} 占位符 |
```

> my-xhs 使用场景：`DynamicConfig` 类用 `EnvironmentAware` 获取配置值，`NacosConfigListener` 用 `ApplicationEventPublisherAware` 发布配置变更事件。

**⑦ ApplicationListener — 监听容器事件**

```java
// 监听 Spring 容器的生命周期事件
// 常见事件：ContextRefreshedEvent、ContextClosedEvent、EnvironmentPostProcessEvent

@Component
public class GracefulShutdownListener implements ApplicationListener<ContextClosedEvent> {
    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        // 容器关闭时的清理逻辑
        // K8s preStop → Nacos 注销 → sleep 15s → Spring ContextClosed → 优雅停机
        log.info("应用关闭，开始清理资源...");
    }
}
```

> my-xhs 使用场景：Phase-5 的优雅停机机制、Phase-5 的分布式事件设计都用此机制。

##### （3）Spring Boot 自动配置的完整链路

> 将 7 大扩展点串联起来，理解 `@SpringBootApplication` 启动到 Bean 就绪的完整过程：

```
1. main() → SpringApplication.run()
    │
2. 创建 ApplicationContext
    │
3. @Import(AutoConfigurationImportSelector.class)          ← ① @Import + ImportSelector
    │  → 读取 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
    │  → 返回所有自动配置类的全限定名
    │
4. 执行 BeanFactoryPostProcessor                           ← ③ BeanFactoryPostProcessor
    │  → 解析 ${} 占位符 → 替换为实际值
    │  → 处理 @ConditionalOnProperty / @ConditionalOnClass 等条件
    │
5. 实例化 Bean（反射调用构造器）
    │
6. 执行 Aware 接口回调                                     ← ⑥ Aware 接口族
    │  → BeanNameAware / BeanFactoryAware / ApplicationContextAware
    │
7. 执行 BeanPostProcessor#postProcessBeforeInitialization  ← ⑤ BeanPostProcessor 前置
    │  → @PostConstruct 方法调用
    │  → @Autowired / @Value 注入（AutowiredAnnotationBeanPostProcessor）
    │
8. 执行 InitializingBean#afterPropertiesSet / @Bean initMethod
    │
9. 执行 BeanPostProcessor#postProcessAfterInitialization   ← ⑤ BeanPostProcessor 后置
    │  → AOP 代理创建（AbstractAutoProxyCreator）
    │  → 如果是 FactoryBean → 调用 getObject()              ← ④ FactoryBean
    │
10. ApplicationListener 监听 ContextRefreshedEvent         ← ⑦ ApplicationListener
    │  → 所有 Bean 就绪，应用开始处理请求
```

**批判性思考**：
- 这 7 大扩展点是 Spring 框架的"操作系统内核"——不理解它们，写 Spring Boot 代码就是"黑盒编程"
- 但 **不要在业务代码中滥用扩展点**——业务逻辑应尽量用标准的 `@Service`/`@Component`/`@Bean` 表达
- 扩展点的正确使用场景：**框架开发**（自定义 Starter）、**基础设施层**（公共组件）、**面试**（Spring 原理必问）
- my-xhs 项目中最值得深入理解的扩展点组合：`@Import` + `ImportBeanDefinitionRegistrar` + `FactoryBean`（这是所有 Starter 的核心三部曲）

#### 3.20.10 Java 框架扩展点全景 — 超越 Spring 的完整体系（P0 补充）

> 云原生架构训练营§4.1 的核心洞察：Java 生态中不止 Spring 有扩展点，**几乎每个 Java 规范/框架都定义了自己的扩展机制**。这些扩展点遵循同一个设计哲学——"开闭原则"（对扩展开放，对修改封闭），但实现方式各有不同。理解完整的 Java 扩展点体系，才能真正理解"为什么 Spring Boot 自动配置能工作"——因为它的底层依赖了 Java SPI + 条件注解 + Spring 扩展点的组合。

##### （1）Java 扩展点五层架构

```
┌─────────────────────────────────────────────────────────────────┐
│                 Java 扩展点五层架构                               │
│                                                                   │
│  Layer 5: 应用框架扩展点                                           │
│    Spring Boot Conditions / Auto-Configuration / Actuator         │
│    Dubbo SPI / Sentinel Slot Chain / MyBatis Plugin               │
│                                                                   │
│  Layer 4: 基础框架扩展点                                           │
│    Spring 7 大扩展机制（§3.20.9 已覆盖）                             │
│    Servlet Filter / Listener / Interceptor                        │
│    MyBatis Interceptor / TypeHandler                              │
│                                                                   │
│  Layer 3: Java 规范扩展点                                          │
│    JDBC Driver SPI / JNDI SPI / JMX MBean                        │
│    Servlet Container SPI / JSP Tag Library SPI                    │
│    Java Security Provider SPI                                     │
│                                                                   │
│  Layer 2: Java 基础设施扩展点                                       │
│    Java SPI (ServiceLoader) / Java APT / Java Agent              │
│    ClassLoader / Security Manager / Byte Code Instrumentation     │
│                                                                   │
│  Layer 1: JVM 底层扩展点                                           │
│    JVMTI / JNI / JVM CI (GraalVM) / JVM Options                  │
└─────────────────────────────────────────────────────────────────┘
```

##### （2）Layer 2：Java 基础设施扩展点 — 所有框架的地基

**① Java SPI（ServiceLoader）— 最基础的扩展机制**

> Java SPI 是 JDK 内置的服务发现机制，**几乎所有 Java 框架的扩展都基于它或受它启发**。

```java
// Java SPI 三要素：
// 1. 服务接口（Service Interface）
public interface RegistryService {
    void register(ServiceInstance instance);
    void deregister(ServiceInstance instance);
}

// 2. 服务实现（Service Implementation）
public class NacosRegistryService implements RegistryService { ... }
public class ZookeeperRegistryService implements RegistryService { ... }
public class ConsulRegistryService implements RegistryService { ... }

// 3. 服务声明文件（META-INF/services/全限定接口名）
// 文件：META-INF/services/com.myxhs.common.registry.RegistryService
// 内容：
// com.myxhs.common.registry.NacosRegistryService
// com.myxhs.common.registry.ZookeeperRegistryService

// 4. 服务加载
ServiceLoader<RegistryService> loader = ServiceLoader.load(RegistryService.class);
for (RegistryService service : loader) {
    service.register(instance);  // 加载所有实现
}
```

**Java SPI 的关键特性**：

| 特性 | 说明 | 局限 |
|------|------|------|
| **延迟加载** | 遍历 Iterator 时才实例化 | 无法按需加载单个实现 |
| **非线程安全** | ServiceLoader 内部用 LazyIterator，多线程需加锁 | 多线程共用同一 ServiceLoader 实例不安全 |
| **全量加载** | `ServiceLoader.load()` 返回所有实现 | 无法指定加载某一个 |
| **无依赖注入** | 只调用无参构造器 | 无法注入 Spring Bean |

**Java SPI 的四大演进版本**：

| 机制 | 来源 | 核心改进 | 使用场景 |
|------|------|---------|---------|
| `java.util.ServiceLoader` | JDK 6+ | 基础 SPI | JDBC Driver、SLF4J、Spring Factories |
| `SpringFactoriesLoader` | Spring Framework | 按key读取、支持多值 | Spring Boot 自动配置 |
| `AutoConfiguration.imports` | Spring Boot 3.0+ | 替代 spring.factories | Spring Boot 3.x 自动配置 |
| `Dubbo ExtensionLoader` | Apache Dubbo | 按名获取、自适应扩展、AOP包装、自动激活 | Dubbo 全框架扩展 |

**② Java APT（Annotation Processing Tool）— 编译期扩展**

> APT 是 Java 编译期的注解处理机制，在 `javac` 编译阶段扫描源码中的注解，然后自动生成新的 Java 文件或资源文件。它是**唯一在编译期工作的扩展机制**，其他所有扩展点都在运行时。APT 不能修改已有代码，只能生成新文件。
>
> **典型应用**：
> - Lombok（`@Data`、`@Builder` → 生成 getter/setter/构造器）
> - Spring Boot Configuration Processor（`@ConfigurationProperties` → 生成 `spring-configuration-metadata.json`，让 IDE 自动补全配置项）
> - MyBatis Plus（`@Mapper` → 生成实现类）
>
> **在 Java 扩展点体系中的定位**：
> - APT 不生成运行时代码，而是生成**元数据文件**，被运行时框架读取
> - 典型应用链路：`APT → spring-configuration-metadata.json → Spring Boot 自动补全 + @Conditional`
>
> **my-xhs 使用场景**：当 my-xhs-common 的自定义 Starter（如 `@Idempotent`、`@DistributedLock`、`@RateLimit`）需要提供配置属性自动补全时，可通过 APT 生成 `spring-configuration-metadata.json`。当前阶段暂不实现 APT，但理解其定位有助于后续开发自定义 Starter。

**③ Java Agent（Instrumentation API）— 字节码级扩展**

> Java Agent 是 JVM 提供的字节码增强机制，允许在类加载时或运行时修改字节码。

```java
// Java Agent 有两种加载方式：
// 1. 启动时加载（premain）：java -javaagent:skywalking-agent.jar -jar app.jar
// 2. 运行时加载（agentmain）：通过 Attach API 动态挂载

// premain 方法
public class MyAgent {
    public static void premain(String args, Instrumentation inst) {
        // 注册 ClassFileTransformer —— 在类加载时修改字节码
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className,
                                    Class<?> classBeingRedefined,
                                    ProtectionDomain domain, byte[] classfileBuffer) {
                if (className.startsWith("com/myxhs/")) {
                    // 字节码增强：给 Controller 方法注入 Tracing Span
                    return enhanceWithTracing(classfileBuffer);
                }
                return null;  // 不修改
            }
        });
    }
}
```

**Java Agent 的应用场景**：

| 场景 | 工具 | 原理 |
|------|------|------|
| **APM 链路追踪** | SkyWalking Agent / Pinpoint | 拦截 HTTP/RPC/DB 调用，注入 traceId |
| **热部署** | JRebel / HotSwapAgent | 监听 class 文件变更，重新 transform |
| **性能诊断** | Arthas / BTrace | 运行时注入监控代码，不修改源码 |
| **安全审计** | OpenRASP | 拦截敏感操作（文件读写、命令执行） |
| **RASP** | Java Agent + Instrumentation | 运行时应用自我保护 |

> my-xhs 使用场景：SkyWalking Java Agent 就是通过 Java Agent 机制实现无侵入式链路追踪。理解 Java Agent 原理，才能在 Agent 冲突（如 SkyWalking + Arthas 同时挂载）时排查问题。

##### （3）Layer 3：Java 规范扩展点 — Java EE/Jakarta EE 的遗留财富

**① JDBC Driver SPI — 最经典的 Java SPI 应用**

```java
// JDBC 4.0+ 的自动发现机制
// MySQL Driver 的声明文件：META-INF/services/java.sql.Driver
// 内容：com.mysql.cj.jdbc.Driver

// DriverManager 通过 ServiceLoader 自动加载所有 Driver
// 所以 Spring Boot 只需引入 mysql-connector-java 依赖，无需手动 Class.forName()

// JDBC 扩展点全景：
java.sql.Driver                   ← 连接层（SPI 自动发现）
  ↓
javax.sql.DataSource              ← 连接池层（Spring Bean 注册）
  ↓
java.sql.Connection               ← 连接层
java.sql.Statement/PreparedStatement  ← 语句层
java.sql.ResultSet                ← 结果层

// 扩展点：
// - Driver: 每个数据库厂商实现自己的 Driver
// - DataSource: HikariCP/Druid/Tomcat JDBC 实现各自的连接池
// - Statement: 可被代理（MyBatis Plugin 机制就是代理 Statement）
```

**② Servlet Container SPI — Tomcat/Jetty 的扩展机制**

```java
// Servlet 规范的扩展点：
// 1. Servlet → 处理请求（HttpServlet.doGet/doPost）
// 2. Filter → 请求过滤（Filter.doFilter）← 最常用的扩展点
// 3. Listener → 事件监听（ServletContextListener/HttpSessionListener）
// 4. ServletContainerInitializer → 容器启动时的初始化（Spring 用它启动 Context）

// ServletContainerInitializer 是 Servlet 3.0+ 的 SPI 机制
// Spring 的实现：SpringServletContainerInitializer
@HandlesTypes(WebApplicationInitializer.class)  // 声明关注的类型
public class SpringServletContainerInitializer implements ServletContainerInitializer {
    @Override
    public void onStartup(Set<Class<?>> webAppInitializerClasses, ServletContext ctx) {
        // 扫描所有 WebApplicationInitializer 实现
        // 调用它们的 onStartup() 方法 → 启动 Spring ApplicationContext
    }
}
// 这就是为什么 Spring Boot 的 main() 方法能启动内嵌 Tomcat 的原因
```

**③ SLF4J SPI — 日志框架桥接的扩展机制**

```java
// SLF4J 是日志门面，底层通过 SPI 发现日志实现
// 声明文件：META-INF/services/org.slf4j.spi.SLF4JServiceProvider
// Logback 实现：ch.qos.logback.classic.spi.LogbackServiceProvider
// Log4j2 实现：org.apache.logging.slf4j.Log4j2ServiceProvider

// SLF4J 的扩展点：
// 1. SLF4JServiceProvider → 日志实现绑定（SPI 自动发现）
// 2. ILoggerFactory → Logger 工厂
// 3. MDCAdapter → 上下文传播（traceId 透传用这个）

// my-xhs 使用场景：MDC 中的 traceId 就是通过 SLF4J MDCAdapter 传播的
// SkyWalking Agent 会自动在 MDC 中设置 [traceId] 占位符
```

**④ Java Security Provider SPI — 加密算法的扩展机制**

```java
// Java Cryptography Architecture (JCA) 的 Provider SPI
// 每个加密库通过 SPI 注册自己的算法实现
// Bouncy Castle、Conscrypt 等都是通过这个机制注册的

// 声明文件：META-INF/services/java.security.Provider
// Bouncy Castle：org.bouncycastle.jce.provider.BouncyCastleProvider

// my-xhs 使用场景：JWT 签名验证、密码哈希、HTTPS 证书等底层都走 JCA Provider
```

##### （4）Layer 4：基础框架扩展点 — Servlet/MyBatis 的扩展体系

**① Servlet Filter/Interceptor — Web 层的三级扩展**

```
请求处理链路（Spring MVC）：

  Client Request
       ↓
  Servlet Filter（Servlet 规范）        ← 最外层，Servlet 容器级别
    - CharacterEncodingFilter           字符编码
    - HiddenHttpMethodFilter            REST 方法覆盖
    - CorsFilter                        跨域
       ↓
  Spring HandlerInterceptor（Spring MVC）← 中间层，Spring MVC 级别
    - AuthenticationInterceptor         鉴权
    - RateLimitInterceptor              限流
    - LogInterceptor                    日志
       ↓
  @Controller + @Advice（Spring AOP）   ← 最内层，方法级别
    - @RestControllerAdvice             全局异常处理
    - @RateLimit AOP                    自定义注解限流
    - @Idempotent AOP                   幂等校验
```

**② MyBatis Interceptor/Plugin — 数据访问层的扩展机制**

```java
// MyBatis 的四大可拦截对象：
// 1. Executor → SQL 执行器（最上层拦截点）
// 2. StatementHandler → SQL 语句处理器
// 3. ParameterHandler → 参数处理器
// 4. ResultSetHandler → 结果集处理器

// 自定义 MyBatis Interceptor 示例：慢 SQL 记录
@Intercepts({
    @Signature(type = StatementHandler.class, method = "query", args = {Statement.class, ResultHandler.class}),
    @Signature(type = StatementHandler.class, method = "update", args = {Statement.class})
})
public class SlowSqlInterceptor implements Interceptor {
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            return invocation.proceed();  // 执行原方法
        } finally {
            long cost = System.currentTimeMillis() - start;
            if (cost > 1000) {  // 慢 SQL 阈值 1s
                log.warn("Slow SQL: {}ms, SQL: {}", cost, getSql(invocation));
            }
        }
    }
}
```

##### （5）Layer 5：应用框架扩展点 — Spring Boot/Dubbo/Sentinel

**① Spring Boot Conditions — 自动配置的条件扩展**

```java
// Spring Boot 的条件注解是"约定优于配置"的核心机制
// 它们本质是 @Conditional 的扩展，决定某个 @Configuration 是否生效

// 内置条件注解分类：
// 1. 类条件
@ConditionalOnClass(DataSource.class)        // classpath 中有 DataSource 类
@ConditionalOnMissingClass("com.myxhs.XXX")  // classpath 中没有某类

// 2. Bean 条件
@ConditionalOnBean(DataSource.class)         // 容器中已有 DataSource Bean
@ConditionalOnMissingBean(DataSource.class)   // 容器中没有 DataSource Bean

// 3. 属性条件
@ConditionalOnProperty(name = "myxhs.cache.enabled", havingValue = "true")

// 4. 资源条件
@ConditionalOnResource(resources = "classpath:myxhs.properties")

// 5. Web 应用条件
@ConditionalOnWebApplication
@ConditionalOnNotWebApplication

// 6. SpEL 表达式条件
@ConditionalOnExpression("${myxhs.feature.enabled:false}")

// 自定义 Condition 示例：只在 K8s 环境中生效
public class KubernetesCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        // 检查 K8s 环境变量
        String k8sPod = context.getEnvironment().getProperty("KUBERNETES_SERVICE_HOST");
        return k8sPod != null;
    }
}

@Conditional(KubernetesCondition.class)
@Configuration
public class KubernetesAutoConfiguration { ... }
```

**② Dubbo ExtensionLoader — Dubbo 的自适应扩展体系**

> 已在 §3.20.8 详细讲解 Dubbo SPI，此处补充它与 Java SPI 的本质区别：

```
Java SPI vs Dubbo SPI 的根本差异：

Java SPI: "发现所有实现，全量加载"
  ServiceLoader.load(RegistryService.class)
  → 返回 Iterator<RegistryService>
  → 遍历所有实现，无法按需选择
  → 没有名称概念

Dubbo SPI: "按名称获取，自适应扩展"
  ExtensionLoader.getExtensionLoader(RegistryService.class)
    .getExtension("nacos")      ← 按名称获取指定实现
    .getAdaptiveExtension()     ← 自适应扩展（运行时根据 URL 参数选择实现）
    .getActivateExtension()     ← 自动激活扩展（根据条件自动加载）
    .getExtension("nacos")      ← 自动包装（AOP）+ 自动注入（DI）

  声明文件：META-INF/dubbo/com.myxhs.RegistryService
  nacos=com.myxhs.NacosRegistryService
  zookeeper=com.myxhs.ZookeeperRegistryService
  consul=com.myxhs.ConsulRegistryService
```

**③ Sentinel Slot Chain — 限流熔断的扩展链**

```
Sentinel 的 Slot Chain 是责任链模式的扩展机制：

  ProcessorSlotChain（Slot 链）
    │
    ├─ NodeSelectorSlot        → 构建 Context（调用树节点）
    ├─ ClusterBuilderSlot      → 构建集群节点
    ├─ LogSlot                 → 日志记录
    ├─ StatisticSlot           → 实时数据统计（QPS/RT/线程数）
    ├─ AuthoritySlot           → 授权规则（黑白名单）
    ├─ SystemSlot              → 系统保护规则（CPU/Load/RT）
    ├─ FlowSlot                → 流控规则（QPS/线程数限流）
    ├─ DegradeSlot             → 降级规则（熔断）
    └─ FlowSlot 后的自定义 Slot

  扩展方式：
  // 自定义 Slot 示例：熔断后发送告警
  public class AlertSlot extends AbstractLinkedProcessorSlot<DefaultNode> {
      @Override
      public void entry(Context context, ResourceWrapper resource, DefaultNode node,
                        int count, boolean prioritized, Object... args) throws Throwable {
          try {
              fireEntry(context, resource, node, count, prioritized, args);
          } catch (DegradeException e) {
              alertService.sendAlert("熔断告警: " + resource.getName());
              throw e;
          }
      }
  }
```

##### （6）扩展点体系总结 — my-xhs 项目视角

```
my-xhs 项目用到的扩展点分布：

Layer 5（应用框架）:
  ✅ Spring Boot @ConditionalOnXxx      → 自动配置条件
  ✅ Sentinel Slot Chain                 → 限流熔断
  ❌ Dubbo ExtensionLoader               → 未使用（用 Spring Cloud）
  ✅ MyBatis Interceptor                 → 分页插件、慢 SQL

Layer 4（基础框架）:
  ✅ Spring BeanPostProcessor            → AOP、自动注入
  ✅ Spring @Import + ImportSelector     → 自动配置
  ✅ Servlet Filter                      → 编码、跨域、鉴权
  ✅ Spring HandlerInterceptor           → 鉴权、限流

Layer 3（Java 规范）:
  ✅ JDBC Driver SPI                     → MySQL/PostgreSQL 驱动自动发现
  ✅ SLF4J SPI                           → Logback 绑定
  ✅ Servlet Container SPI               → Spring MVC 启动
  ⚠️ Java Security Provider SPI          → JWT/HMAC 底层依赖

Layer 2（Java 基础设施）:
  ✅ Java SPI (ServiceLoader)            → spring.factories 底层机制
  ✅ Java APT                            → 配置元数据生成
  ✅ Java Agent                          → SkyWalking 链路追踪

Layer 1（JVM 底层）:
  ⚠️ JVMTI                               → Arthas 诊断工具
  ❌ JVM CI (GraalVM)                    → 未使用 Native Image
```

**批判性思考**：
- **面试高频考点**：Java SPI → Spring Factories → Dubbo SPI 三者的演进逻辑（面试必问"SPI 有什么问题？Dubbo SPI 怎么解决的？"）
- **框架开发必知**：如果 my-xhs 要开发自定义 Starter，需要组合 Layer 2-5 的扩展点——`Java SPI（声明配置类）→ APT（生成元数据）→ @Conditional（条件生效）→ @Import（导入配置）→ BeanPostProcessor（初始化拦截）`
- **排障必知**：理解 SLF4J SPI 才能排查"日志不输出"问题；理解 Java Agent 才能排查"SkyWalking Agent 导致 OOM"问题
- **Microsphere 框架的野心**：小马哥用 Microsphere 统一了 Layer 3-5 的扩展点（统一 SPI、统一条件、统一配置元数据），思路正确但生态不成熟。my-xhs 不引入 Microsphere，但应理解它要解决的问题

#### 3.20.11 Dubbo 选型分析 — 为什么 my-xhs 选择 Spring Cloud 而非 Dubbo（P0 补充）

> 云原生架构训练营§6 用了整整一个大章讲 Dubbo 3.3 的新架构和生态整合。用户提出"为什么不用 Dubbo？能不能加进来？"，这是一个需要认真回答的架构选型问题。

##### （1）核心结论：my-xhs 选择 Spring Cloud，不建议引入 Dubbo

**但 Dubbo 的设计思想值得深入学习**——它解决的很多问题（高性能 RPC、多协议支持、服务治理精细化）是 Spring Cloud 的薄弱点。

##### （2）为什么不用 Dubbo？— 5 个核心理由

**理由 1：架构复杂度与团队规模不匹配**

```
Spring Cloud 方案（my-xhs 当前）：
  Consumer → Feign（声明式 HTTP 客户端）→ LoadBalancer → Provider
  技术栈：HTTP + JSON + Nacos + Feign
  学习曲线：低（HTTP 协议人人会用）

Dubbo 方案：
  Consumer → Dubbo Reference → Cluster → Directory → Router → LoadBalance → Provider
  技术栈：Triple/HTTP2 + Protobuf + Nacos + Dubbo SPI + 自定义序列化
  学习曲线：高（需理解 Dubbo SPI、Filter Chain、Router 规则、多协议）

  my-xhs 是学习型项目，团队规模 1 人 → Spring Cloud 足够
  如果是阿里/美团级别的团队和流量 → Dubbo 才有必要
```

**理由 2：协议选择 — HTTP/JSON vs Triple/Protobuf**

| 维度 | Spring Cloud (HTTP/JSON) | Dubbo (Triple/Protobuf) |
|------|-------------------------|------------------------|
| **开发体验** | ✅ 人类可读，cURL 可直接调试 | ❌ 二进制格式，需专用工具调试 |
| **性能** | ⚠️ JSON 序列化较慢 | ✅ Protobuf 序列化快 3-10 倍 |
| **跨语言** | ✅ 任何语言都能发 HTTP | ✅ Protobuf 跨语言，但需生成 stub |
| **浏览器兼容** | ✅ 前端可直接调 HTTP API | ❌ 浏览器无法直接调 Triple 协议 |
| **网关兼容** | ✅ Gateway 天然代理 HTTP | ⚠️ Dubbo 协议需 Dubbo Proxy 网关 |

> my-xhs 前端（小程序/H5）需要直接调 HTTP API → Spring Cloud 天然兼容
> 如果用 Dubbo，前端请求还需经过 Dubbo Proxy 网关做协议转换 → 多一层

**理由 3：生态整合 — Spring Cloud 全家桶 vs Dubbo + Spring Cloud 混合**

```
Spring Cloud 全家桶（my-xhs 当前，统一生态）：
  注册中心: Nacos
  配置中心: Nacos
  服务调用: OpenFeign
  负载均衡: Spring Cloud LoadBalancer
  限流熔断: Sentinel
  分布式事务: Seata
  网关: Spring Cloud Gateway
  链路追踪: Micrometer + SkyWalking

Dubbo + Spring Cloud 混合（引入 Dubbo 后的碎片化）：
  注册中心: Nacos（共用）
  配置中心: Nacos（共用）
  服务调用: OpenFeign（HTTP）+ Dubbo（Triple）← 两套调用方式！
  负载均衡: Spring Cloud LB + Dubbo Cluster LB ← 两套负载均衡！
  限流熔断: Sentinel（Spring Cloud 侧）+ Sentinel（Dubbo Filter 侧）← 两套限流！
  网关: Spring Cloud Gateway（HTTP）+ Dubbo Proxy（Triple）← 两套网关！
  
  结论：引入 Dubbo 不会替代 Spring Cloud，而是叠加——复杂度翻倍
```

**理由 4：服务治理粒度 — Dubbo 的精细化 my-xhs 不需要**

```
Dubbo 的精细化服务治理（阿里/美团级别需要）：
  - 方法级路由：orderService.createOrder() → 机房A，orderService.queryOrder() → 机房B
  - 参数级路由：userId=123 → 灰度实例，userId≠123 → 正式实例
  - 多注册中心：同时注册到 Nacos + ZooKeeper + Consul
  - 多协议暴露：同一服务同时暴露 Triple + REST + gRPC
  - 服务降级策略：mock=fallback、force:return null

Spring Cloud 的服务治理（my-xhs 级别足够）：
  - 服务级路由：order-service → 机房A 或 灰度实例
  - 区域感知：同机房优先
  - 限流熔断：接口级 QPS 限流
  - 灰度发布：基于 Gateway 路由规则
  
  my-xhs 不需要方法级/参数级路由 → Spring Cloud 的粒度足够
```

**理由 5：mini-rpc 已覆盖核心学习目标**

> my-xhs 项目有 `mini-rpc` 模块——手写简化版 RPC 框架，覆盖了 Dubbo 的核心设计思想：
> - 服务注册与发现
> - 负载均衡（轮询/随机/加权）
> - 序列化/反序列化
> - 动态代理（JDK Proxy）
> - 网络通信（Netty/HTTP）
> 
> 引入 Dubbo 和手写 mini-rpc 的学习目标是重叠的。**与其引入 Dubbo 增加架构复杂度，不如深入理解 mini-rpc + 研究 Dubbo 源码**。

##### （3）Dubbo 3.3 值得学习但不需要引入的 3 个设计

| 设计点 | Dubbo 做法 | my-xhs 对应方案 | 值得借鉴之处 |
|--------|-----------|---------------|------------|
| **自适应扩展** | `@Adaptive` 注解 + 代码生成 → 运行时根据 URL 参数选择实现 | Spring `@Conditional` + 配置属性 | Dubbo 的自适应扩展更灵活（运行时动态选择），Spring 的条件注解更简洁（启动时静态选择） |
| **Filter Chain** | Dubbo Filter 是责任链，可自定义 Filter 做鉴权/日志/限流 | Spring Cloud 用 Interceptor + AOP | 责任链模式比 AOP 更可控（可中断、可替换、可排序） |
| **Triple 协议** | 基于 HTTP/2 + Protobuf，兼容 gRPC，支持流式通信 | Feign + HTTP/1.1 + JSON | Triple 的流式通信适合 IM 场景，但 my-xhs 的 IM 用 WebSocket |

##### （4）如果一定要引入 Dubbo？— 混合架构方案

> 如果面试官问"你的项目为什么不用 Dubbo"，可以回答"我考虑过混合架构，但评估后选择了纯 Spring Cloud"，然后展示以下分析：

```
混合架构方案（Spring Cloud + Dubbo 共存）：

外部请求（小程序/H5）：
  Client → Gateway → HTTP/JSON → 业务服务

内部调用（服务间高频调用）：
  Consumer → Dubbo Triple/Protobuf → Provider

适用场景：
  - 服务间调用 QPS > 10000 且对 RT 敏感（如订单→库存→支付链路）
  - 需要方法级/参数级路由
  - 需要流式通信（gRPC 兼容）

引入步骤：
  1. 添加 dubbo-spring-boot-starter 依赖
  2. Provider: @DubboService 注解暴露 Triple 协议服务
  3. Consumer: @DubboReference 注解引用远程服务
  4. Nacos 同时作为注册中心（Spring Cloud 和 Dubbo 共用）
  5. Sentinel 同时作为限流框架（Spring Cloud Interceptor 和 Dubbo Filter 共用）

风险：
  - 两套调用方式（Feign + Dubbo Reference）→ 开发者需判断用哪个
  - 两套序列化（JSON + Protobuf）→ 需维护两套 API 契约
  - Dubbo Spring Cloud 整合包可能有版本兼容问题
```

**批判性思考**：
- **选型不是非此即彼**：Spring Cloud 适合"HTTP 为主、快速开发、团队规模中小"的场景；Dubbo 适合"高频内部调用、精细化治理、团队规模大"的场景
- **Dubbo 3.3 的趋势是与 Spring Cloud 融合**：`dubbo-spring-boot-starter` + `@DubboService`/`@DubboReference` 让 Dubbo 几乎可以像 Spring Bean 一样使用。但融合不等于简化——底层仍然有两套体系
- **my-xhs 的正确姿势**：用 Spring Cloud 做主架构，用 mini-rpc 学习 RPC 原理，用 Dubbo 源码研究扩展点设计。三者各司其职，不混用
- **面试加分项**：能说清楚"为什么选 Spring Cloud 不选 Dubbo"比"我两个都用过"更能体现架构思考能力

---

## 四、中间件需求

### 4.1 新增中间件

| 中间件 | 版本 | 用途 | 所属功能 |
|--------|------|------|----------|
| Sentinel | 1.8.7 | 网关限流熔断 | 功能18-网关增强 |

### 4.2 已有中间件（本阶段新用途）

| 中间件 | 版本 | 新增用途 | 所属功能 |
|--------|------|----------|----------|
| Redis | 7.x | 幂等Key/限流计数/在线状态/会话列表/未读计数 | 功能18/19/20 |
| RocketMQ | 5.x | 聊天消息异步持久化 | 功能19-IM |
| Nacos | 2.x | 限流规则数据源/灰度实例元数据 | 功能18-网关增强 |

### 4.3 中间件版本总表

| 中间件 | 版本 | 端口 | 说明 |
|--------|------|------|------|
| MySQL | 8.0+ | 3306 | 主存储 |
| Redis | 7.x | 6379 | 缓存+分布式锁+幂等+限流+会话 |
| Nacos | 2.x | 8848 | 注册中心+配置中心 |
| RocketMQ | 5.x | 9876 | 异步消息 |
| Elasticsearch | 8.12 | 9200 | 搜索引擎（Phase-3已引入） |
| Sentinel | 1.8.7 | 8080(控制台) | 限流熔断 |

---

## 五、MQ Topic 清单

| Topic | 生产者 | 消费者 | 说明 |
|-------|--------|--------|------|
| `chat-message-send` | ImMessageService | ChatMessageConsumer | 聊天消息异步持久化+推送 |

> Phase 1/2/3 的 Topic 不再重复列出。

---

## 六、Feign 调用关系

| 调用方 | 被调用方 | 方法 | 用途 |
|--------|---------|------|------|
| IM | User | `getUserInfo(userId)` | 获取对方用户信息 |
| IM | Notification | `pushOfflineMessage(userId, message)` | 离线消息推送 |

---

## 七、降级规范

| 服务 | 降级策略 | 返回值 |
|------|---------|--------|
| User | 降级返回默认用户 | `{nickname:"用户",avatar:"default.png"}` |
| Notification | 降级忽略推送 | 无操作 |

---

## 八、公共组件复用

| 组件 | 模块 | 说明 |
|------|------|------|
| @Idempotent | my-xhs-common | 幂等注解（Redis SET NX） |
| @DistributedLock | my-xhs-common | 分布式锁注解（Redisson RLock） |
| @RateLimit | my-xhs-common | 限流注解（Redis Lua滑动窗口） |
| IdGeneratorUtil | my-xhs-common | 雪花ID生成器（CosId） |
| R\<T\> | my-xhs-common | 统一响应封装 |
| BaseEntity | my-xhs-common | 实体基类（id+createdAt+updatedAt） |
| UserContextHolder | my-xhs-common | 用户上下文（ThreadLocal） |
| SpELParser | my-xhs-common | SpEL表达式解析器 |
| JsonUtil | my-xhs-common | JSON工具类 |
| RedisUtil | my-xhs-common | Redis工具类 |

---

## 九、实现步骤

### Step 1：common 基础组件（2天）

> 🎓 **训练营知识落地**：本步骤是 Spring 7大扩展点（§3.20.9）+ Java 扩展点五层架构（§3.20.10）在 my-xhs 中的核心应用。3个自定义注解的 AOP 切面本质上是 Layer 5 的 BeanPostProcessor 扩展；自定义 Starter 自动配置依赖 Layer 4 的 @Import + ImportSelector + @Conditional 机制；CosId 的 Provider 自动发现依赖 Layer 2 的 Java SPI 机制。

- [ ] 补齐 `BaseEntity.java`（id + createdAt + updatedAt，id使用雪花算法填充）
- [ ] 补齐 `R.java`（统一响应封装：success/fail/error静态方法）
- [ ] 补齐 `PageResult.java`（分页结果封装）
- [ ] 补齐 `UserContextHolder.java`（ThreadLocal存储userId/traceId）
- [ ] 补齐 `JsonUtil.java`（FastJSON2封装）
- [ ] 补齐 `RedisUtil.java`（Redis通用操作封装）
- [ ] 补齐 `SpELParser.java`（SpEL表达式解析，支持方法参数提取）
- [ ] 实现 `@Idempotent` + `IdempotentAspect`（Redis SET NX + SpEL + 过期时间）— **底层原理：BeanPostProcessor → AOP 代理创建**
- [ ] 实现 `@DistributedLock` + `DistributedLockAspect`（Redisson RLock + SpEL + 自动释放）— **底层原理：BeanPostProcessor → AOP 代理创建**
- [ ] 实现 `@RateLimit` + `RateLimitAspect`（Redis Lua滑动窗口 + SpEL）— **底层原理：BeanPostProcessor → AOP 代理创建**
- [ ] 实现 `IdGeneratorUtil.java`（CosId雪花ID生成器）— **底层原理：Java SPI 自动发现 CosIdProvider**
- [ ] 实现 `CosIdConfig.java`（CosId自动配置）— **底层原理：@Import + @ConditionalOnClass 条件装配**
- [ ] 实现3个异常类：`IdempotentException` / `DistributedLockException` / `RateLimitException`
- [ ] 实现 `MyXhsCommonAutoConfiguration.java`（自定义 Starter 自动配置类）— **训练营知识落地：@Import + ImportSelector + @Conditional 组合，使 common 模块可被其他服务零配置引入**
- [ ] 创建 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（声明自动配置类）— **训练营知识落地：Spring Boot 3.x 自动配置入口，替代 spring.factories**
- [ ] common pom.xml 添加 CosId 依赖
- [ ] 父 pom.xml 添加 CosId 依赖版本管理
- [ ] 编写单元测试验证注解功能

### Step 2：Gateway 鉴权+签名+日志（2天）

> 🎓 **训练营知识落地**：Gateway 的 GlobalFilter 链是 Layer 4 的 Servlet Filter / Spring HandlerInterceptor 扩展点在 WebFlux 中的等价实现。JWT 鉴权 + HMAC 签名校验 + 限流 + 灰度路由组成完整的请求处理链，原理与 Dubbo Filter Chain（§3.20.8）相同——都是责任链模式。

- [ ] 补齐 gateway application.yml 路由规则（product/cart/inventory/coupon/order/payment/search/home/notification/im）
- [ ] 实现 `AuthGlobalFilter.java`（JWT鉴权+Redis黑名单+白名单放行）
- [ ] 实现 `HmacSignatureFilter.java`（HMAC-SHA256签名校验+timestamp+nonce防重放）— **底层原理：JCA Provider SPI（§3.20.10 Layer 3）提供 HmacSHA256 算法实现**
- [ ] 实现 `RequestLogFilter.java`（入站请求日志+TraceId注入）— **底层原理：SLF4J SPI（§3.20.10 Layer 3）MDC 传播 traceId**
- [ ] 实现 `JwtUtil.java`（JWT解析/验证工具类，Gateway专用）
- [ ] 实现 `HmacUtil.java`（HMAC-SHA256签名工具类）
- [ ] 实现 `CorsConfig.java`（跨域配置）
- [ ] 实现 `GatewayExceptionHandler.java`（全局异常处理）
- [ ] 实现 `GatewayResponse.java`（统一响应DTO）
- [ ] Nacos配置：白名单路径列表
- [ ] Redis Key：`user:token:blacklist:{userId}` / `gateway:nonce:{nonce}`

### Step 3：Gateway 限流+灰度+版本（1.5天）

> 🎓 **训练营知识落地**：Sentinel 的 Slot Chain（§3.20.10 Layer 5）是责任链扩展机制，与 Dubbo Filter Chain 原理相同。灰度路由利用了 Nacos 注册中心元数据标记能力（§3.20.5 选型分析中 Nacos 的优势之一）。

- [ ] gateway pom.xml 添加 Sentinel 依赖
- [ ] 父 pom.xml 添加 Sentinel 依赖版本管理
- [ ] 实现 `RateLimitFilter.java`（Sentinel限流：接口级+用户级QPS）
- [ ] 实现 `SentinelConfig.java`（Sentinel规则配置+Nacos数据源加载）
- [ ] 实现 `GrayRouteFilter.java`（灰度路由：X-Gray-Tag请求头匹配Nacos元数据）
- [ ] 实现 `GrayRouteConfig.java`（灰度实例元数据管理）
- [ ] 实现 `ApiVersionFilter.java`（API版本路由：X-Api-Version请求头匹配）
- [ ] Nacos配置：Sentinel限流规则
- [ ] Redis Key：`gateway:ratelimit:{api}:{userId}`

### Step 4：IM 服务骨架（1.5天）

> 🎓 **训练营知识落地**：IM 服务使用 Spring WebFlux + Netty（Reactive 栈），而不是传统的 Spring MVC（Servlet 栈）。这是微服务拆分策略（§3.20.6）中"按能力拆分"的体现——IM 服务因高并发 WebSocket 需求选择 Reactive 技术栈，与其他业务服务的 Servlet 栈不同。

- [ ] 新建 `my-xhs-im` 模块（pom.xml + application.yml + ImApplication.java）
- [ ] pom.xml 添加依赖：WebFlux + WebSocket + Redis + RocketMQ + MyBatis-Plus + MySQL + OpenFeign + Nacos
- [ ] application.yml 配置（端口9014 + MySQL + Redis + Nacos + RocketMQ + WebSocket路径）
- [ ] 父 pom.xml 添加 my-xhs-im 模块
- [ ] 创建数据库 `my_xhs_im`，建表 `t_chat` + `t_chat_user_relation`
- [ ] 实现 `WebSocketConfig.java`（端点注册、CORS、拦截器）
- [ ] 实现 `WebSocketSessionManager.java`（userId↔Session映射，ConcurrentHashMap）
- [ ] 实现 `ImWebSocketHandler.java`（连接建立/断开/消息收发）

### Step 5：IM 消息+会话（2天）

- [ ] 实现 `Chat.java` + `ChatUserRelation.java` 实体
- [ ] 实现 `ChatMapper.java` + `ChatUserRelationMapper.java`
- [ ] 实现 `ImMessageService` / `ImMessageServiceImpl`（发送→MQ→持久化→推送）
- [ ] 实现 `ImConversationService` / `ImConversationServiceImpl`（列表/未读/已读）
- [ ] 实现 `ImOnlineService` / `ImOnlineServiceImpl`（Redis心跳在线检测）
- [ ] 实现 `ChatMessageProducer` + `ChatMessageConsumer`（MQ异步持久化+推送）
- [ ] 实现 `ImConversationController`（会话列表/已读/删除API）
- [ ] 实现 `ImMessageController`（HTTP消息发送备用API）
- [ ] 实现 `UserFeignClient` + `NotificationFeignClient`
- [ ] 实现 DTO/VO：`ChatMessageDTO` / `ChatMessageVO` / `ConversationVO`
- [ ] Redis Key：会话列表/未读计数/在线状态/已读位图

### Step 6：全链路联调（1天）

- [ ] Gateway→所有服务路由验证
- [ ] JWT鉴权：白名单放行+Token校验+黑名单
- [ ] HMAC签名：正常请求通过+篡改拒绝+重放拒绝+过期拒绝
- [ ] Sentinel限流：超限返回429
- [ ] 灰度路由：灰度请求路由到灰度实例
- [ ] IM WebSocket连接+消息收发+会话列表+已读
- [ ] 公共组件：@Idempotent / @DistributedLock / @RateLimit / IdGeneratorUtil

---

## 十、配置文件清单

### 10.1 my-xhs-gateway（✅ 已存在模块，需增强）

```yaml
server:
  port: 9000

spring:
  application:
    name: my-xhs-gateway
  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848
      namespace: public
    gateway:
      routes:
        # Phase 1
        - id: user-service
          uri: lb://my-xhs-user
          predicates:
            - Path=/api/user/**
        - id: content-service
          uri: lb://my-xhs-content
          predicates:
            - Path=/api/note/**,/api/comment/**
        - id: analytics-service
          uri: lb://my-xhs-analytics
          predicates:
            - Path=/api/social/**
        - id: counter-service
          uri: lb://my-xhs-counter
          predicates:
            - Path=/api/counter/**
        # Phase 2
        - id: product-service
          uri: lb://my-xhs-product
          predicates:
            - Path=/api/product/**
        - id: cart-service
          uri: lb://my-xhs-cart
          predicates:
            - Path=/api/cart/**
        - id: inventory-service
          uri: lb://my-xhs-inventory
          predicates:
            - Path=/api/inventory/**
        - id: coupon-service
          uri: lb://my-xhs-coupon
          predicates:
            - Path=/api/coupon/**
        - id: order-service
          uri: lb://my-xhs-order
          predicates:
            - Path=/api/order/**
        - id: payment-service
          uri: lb://my-xhs-payment
          predicates:
            - Path=/api/payment/**
        # Phase 3
        - id: search-service
          uri: lb://my-xhs-search
          predicates:
            - Path=/api/search/**
        - id: home-service
          uri: lb://my-xhs-home
          predicates:
            - Path=/api/home/**
        - id: notification-service
          uri: lb://my-xhs-notification
          predicates:
            - Path=/api/notification/**
        # Phase 4
        - id: im-service
          uri: lb://my-xhs-im
          predicates:
            - Path=/api/im/**

# JWT白名单路径
gateway:
  auth:
    whitelist:
      - /api/user/login
      - /api/user/register
      - /api/user/refresh
      - /api/note/detail/**
      - /api/product/detail/**
      - /api/search/**
      - /api/home/feed
      - /api/counter/**

# HMAC签名（仅支付接口需要）
gateway:
  hmac:
    enabled: true
    secret-key: your-hmac-secret-key
    max-timestamp-diff: 300000  # 5分钟
    nonce-cache-seconds: 300
    include-paths:
      - /api/payment/**
```

> ⚠️ **YAML 注意事项**：上面的 `gateway.auth` 和 `gateway.hmac` 是自定义配置（非 Spring 标准键），与 `spring.cloud.gateway` 是不同的层级。完整的 `spring.cloud` 块应包含 nacos + gateway + sentinel，为避免文档过长，Sentinel 配置示意如下：
> ```yaml
> spring:
>   cloud:
>     sentinel:
>       transport:
>         dashboard: localhost:8080
>       datasource:
>         flow:
>           nacos:
>             server-addr: localhost:8848
>             data-id: gateway-flow-rules
>             rule-type: flow
> ```
```

### 10.2 my-xhs-im（❌ 需新建模块）

```yaml
server:
  port: 9014

spring:
  application:
    name: my-xhs-im
  datasource:
    url: jdbc:mysql://localhost:3306/my_xhs_im?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai
    username: root
    password: root
    driver-class-name: com.mysql.cj.jdbc.Driver
  data:
    redis:
      host: localhost
      port: 6379
  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848
      config:
        server-addr: localhost:8848
        file-extension: yaml

# WebSocket配置
im:
  websocket:
    path: /api/im/ws
    allowed-origins: "*"
    max-connections: 100000
    heartbeat-interval: 30000
    idle-timeout: 300000

# RocketMQ
rocketmq:
  name-server: localhost:9876
  producer:
    group: im-producer-group
    send-message-timeout: 3000
  consumer:
    group: im-consumer-group

# CosId雪花ID
cosid:
  machine:
    enabled: true
    distributor:
      type: manual
      manual:
        machine-id: 1
  generator:
    enabled: true
    provider:
      default:
        type: snowflake
        snowflake:
          epoch: 1704067200000
```

### 10.3 my-xhs-common（✅ 已存在模块，需增强）

> common 模块无 application.yml，配置由各服务自行管理。common 仅提供注解、切面、工具类等代码级组件。

---

## 十一、骨架问题清单

| # | 问题 | 说明 | 状态 |
|---|------|------|------|
| 1 | my-xhs-im 模块未创建 | 需新建即时通讯服务模块（端口9014） | ✅ 已创建 |
| 2 | common 模块无Java文件 | 需补齐4个注解+3个切面+2个工具类+5个DTO | ❌ 待实现 |
| 3 | common pom 缺少 CosId 依赖 | 雪花ID生成器依赖未引入 | ✅ 已补充 |
| 4 | 父 pom 缺少 CosId 版本管理 | 需在 dependencyManagement 中添加 CosId | ✅ 已有，无需修改（cosid.version=2.6.8） |
| 5 | 父 pom 缺少 Sentinel 版本管理 | 需在 dependencyManagement 中添加 Sentinel | ✅ 已补充（sentinel-datasource-nacos + spring-cloud-starter-alibaba-sentinel） |
| 6 | gateway pom 缺少 Sentinel 依赖 | 限流熔断依赖未引入 | ✅ 已补充 |
| 7 | gateway 仅4条路由规则 | 缺少 product/cart/inventory/coupon/order/payment/search/home/notification/im 路由 | ✅ 已补充（14条路由） |
| 8 | gateway 仅空壳 Application | 需实现6个Filter + 3个Config + Handler + Util | ❌ 待实现 |
| 9 | im 数据库 my_xhs_im 未创建 | 需建库建表（t_chat + t_chat_user_relation） | ❌ 待创建 |
| 10 | Sentinel Dashboard 未部署 | 限流规则管理控制台 | ❌ 待部署 |

---

## 十二、端口分配总表（全Phase汇总）

| 服务 | 端口 | 数据库 | Phase |
|------|------|--------|-------|
| my-xhs-gateway | 9000 | — | 1 |
| my-xhs-user | 9001 | my_xhs_user | 1 |
| my-xhs-content | 9002 | my_xhs_note | 1 |
| my-xhs-analytics | 9003 | my_xhs_social | 1 |
| my-xhs-counter | 9004 | my_xhs_counter | 1 |
| my-xhs-product | 9005 | my_xhs_product | 2 |
| my-xhs-order | 9006 | my_xhs_order (分库) | 2 |
| my-xhs-payment | 9007 | my_xhs_payment | 2 |
| my-xhs-inventory | 9008 | my_xhs_inventory | 2 |
| my-xhs-cart | 9009 | my_xhs_cart | 2 |
| my-xhs-coupon | 9010 | my_xhs_coupon (分库) | 2 |
| my-xhs-search | 9011 | my_xhs_search (ES+MySQL) | 3 |
| my-xhs-notification | 9012 | my_xhs_notification | 2 |
| my-xhs-im | 9014 | my_xhs_im | 4 |
| my-xhs-home | 9015 | 无（BFF聚合） | 3 |

---

## Phase 4 核心技术点

- Gateway GlobalFilter链式处理
- HMAC-SHA256签名验证（防篡改+防重放）
- 灰度路由（Nacos元数据 + Gateway路由）
- 全链路流量染色（Feign + MQ + 异步线程透传）
- 4个自定义注解（幂等/分布式锁/限流/JWT）

---

## 文档索引

每个功能的配套文档按以下规范归档：

| 文档类型 | 命名格式 | 说明 |
|----------|----------|------|
| a-前置知识 | `a-前置知识-xxx.md` | Java/JVM/网络知识 + 可运行Demo |
| b-问题驱动实现 | `b-问题驱动实现-xxx.md` | 从0开始以问题驱动推导设计和实现 |
| c-现状梳理 | `c-现状梳理-xxx.md` | 所有文件/类/字段/方法详解 |

### 已完成文档

<!-- 
完成文档后在此添加索引，格式示例：
- [18-API网关](./a-前置知识-API网关.md)
- [18-API网关](./b-问题驱动实现-API网关.md)
- [18-API网关](./c-现状梳理-API网关.md)
-->

> 📌 待编写：当前尚无已完成文档，请按文档编写规范依次创建。

> 📌 文档编写要求：必须参考本地真实框架源码（如Nacos/Dubbo），不能凭空设计。"问题驱动实现"是最重要的文档。