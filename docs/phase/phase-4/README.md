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
| my-xhs-admin | 9013 | my_xhs_admin | ❌ 已存在(增强) | 后台管理（补齐完整CRUD和权限体系） |

> **注意**：
> - `my-xhs-gateway` 和 `my-xhs-common` 目前只是空壳（仅 Application.java），Phase 4 需要填充完整实现
> - `my-xhs-admin` 目前也是空壳，Phase 4 需要补齐完整的后台管理功能
> - `my-xhs-im` 需要新建模块，端口分配 9014（admin 已占用 9013）

### 2.1 端口对照说明

| 服务 | 技术规格大纲端口 | 实际端口 | 说明 |
|------|-----------------|---------|------|
| my-xhs-gateway | 9000 | **9000** | 一致 |
| my-xhs-im | 9013 | **9014** | admin 实际占用 9013，im 调整为 9014 |
| my-xhs-admin | 9014 | **9013** | 实际端口与大纲不一致，以 application.yml 为准 |

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
| admin-service | /api/admin/** | my-xhs-admin | ✅ | 后台管理 |

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
WebMvcConfig.java              — CORS + 拦截器
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
CosIdConfig.java               — CosId配置类（雪花ID生成器）
RedissonConfig.java            — Redisson配置类（分布式锁依赖）
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

#### 3.20.5 核心技术点

| 技术点 | 方案 | 关键实现 |
|--------|------|----------|
| 幂等 | Redis SET NX + SpEL | AOP拦截→解析Key→SET NX→成功放行，失败拒绝 |
| 分布式锁 | Redisson RLock + SpEL | AOP拦截→解析Key→tryLock→执行→finally unlock |
| 限流 | Redis Lua滑动窗口 | ZREMRANGEBYSCORE+ZCARD判断窗口内请求数 |
| SpEL解析 | Spring ExpressionParser | 从方法参数中动态提取Key值，支持`#userId`、`#request.orderId`等 |
| 雪花ID | CosId | 解决时钟回拨问题，自动分配workerId，比手写更可靠 |
| 统一响应 | R\<T\>封装 | code=0成功，code≠0失败，message描述，data泛型 |
| 用户上下文 | ThreadLocal | Filter/Interceptor存入userId，Service层获取 |

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

- [ ] 补齐 `BaseEntity.java`（id + createdAt + updatedAt，id使用雪花算法填充）
- [ ] 补齐 `R.java`（统一响应封装：success/fail/error静态方法）
- [ ] 补齐 `PageResult.java`（分页结果封装）
- [ ] 补齐 `UserContextHolder.java`（ThreadLocal存储userId/traceId）
- [ ] 补齐 `JsonUtil.java`（FastJSON2封装）
- [ ] 补齐 `RedisUtil.java`（Redis通用操作封装）
- [ ] 补齐 `SpELParser.java`（SpEL表达式解析，支持方法参数提取）
- [ ] 实现 `@Idempotent` + `IdempotentAspect`（Redis SET NX + SpEL + 过期时间）
- [ ] 实现 `@DistributedLock` + `DistributedLockAspect`（Redisson RLock + SpEL + 自动释放）
- [ ] 实现 `@RateLimit` + `RateLimitAspect`（Redis Lua滑动窗口 + SpEL）
- [ ] 实现 `IdGeneratorUtil.java`（CosId雪花ID生成器）
- [ ] 实现 `CosIdConfig.java`（CosId自动配置）
- [ ] 实现3个异常类：`IdempotentException` / `DistributedLockException` / `RateLimitException`
- [ ] common pom.xml 添加 CosId 依赖
- [ ] 父 pom.xml 添加 CosId 依赖版本管理
- [ ] 编写单元测试验证注解功能

### Step 2：Gateway 鉴权+签名+日志（2天）

- [ ] 补齐 gateway application.yml 路由规则（product/cart/inventory/coupon/order/payment/search/home/notification/im/admin）
- [ ] 实现 `AuthGlobalFilter.java`（JWT鉴权+Redis黑名单+白名单放行）
- [ ] 实现 `HmacSignatureFilter.java`（HMAC-SHA256签名校验+timestamp+nonce防重放）
- [ ] 实现 `RequestLogFilter.java`（入站请求日志+TraceId注入）
- [ ] 实现 `JwtUtil.java`（JWT解析/验证工具类，Gateway专用）
- [ ] 实现 `HmacUtil.java`（HMAC-SHA256签名工具类）
- [ ] 实现 `CorsConfig.java`（跨域配置）
- [ ] 实现 `GatewayExceptionHandler.java`（全局异常处理）
- [ ] 实现 `GatewayResponse.java`（统一响应DTO）
- [ ] Nacos配置：白名单路径列表
- [ ] Redis Key：`user:token:blacklist:{userId}` / `gateway:nonce:{nonce}`

### Step 3：Gateway 限流+灰度+版本（1.5天）

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

### Step 6：Admin 后台管理（2天）

- [ ] 补齐 admin application.yml Redis 配置
- [ ] 实现管理员登录/权限验证
- [ ] 实现笔记审核管理（分页查询+审核通过/拒绝）
- [ ] 实现用户管理（查询+封禁/解封）
- [ ] 实现商品管理（查询+上下架）
- [ ] 实现订单管理（查询+退款审批）
- [ ] 实现举报处理（查询+处理）
- [ ] 实现 Content/Order/User 等服务的 FeignClient

### Step 7：全链路联调（1天）

- [ ] Gateway→所有服务路由验证
- [ ] JWT鉴权：白名单放行+Token校验+黑名单
- [ ] HMAC签名：正常请求通过+篡改拒绝+重放拒绝+过期拒绝
- [ ] Sentinel限流：超限返回429
- [ ] 灰度路由：灰度请求路由到灰度实例
- [ ] IM WebSocket连接+消息收发+会话列表+已读
- [ ] Admin CRUD 全功能验证
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
        - id: admin-service
          uri: lb://my-xhs-admin
          predicates:
            - Path=/api/admin/**

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

# HMAC签名（仅管理端和支付接口需要）
gateway:
  hmac:
    enabled: true
    secret-key: your-hmac-secret-key
    max-timestamp-diff: 300000  # 5分钟
    nonce-cache-seconds: 300
    include-paths:
      - /api/admin/**
      - /api/payment/**

# Sentinel限流
spring:
  cloud:
    sentinel:
      transport:
        dashboard: localhost:8080
      datasource:
        flow:
          nacos:
            server-addr: localhost:8848
            data-id: gateway-flow-rules
            rule-type: flow
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

### 10.4 my-xhs-admin（✅ 已存在模块，需增强）

```yaml
server:
  port: 9013

spring:
  application:
    name: my-xhs-admin
  datasource:
    url: jdbc:mysql://localhost:3306/my_xhs_admin?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai
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
```

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
| 7 | gateway 仅4条路由规则 | 缺少 product/cart/inventory/coupon/order/payment/search/home/notification/im/admin 路由 | ✅ 已补充（15条路由） |
| 8 | gateway 仅空壳 Application | 需实现6个Filter + 3个Config + Handler + Util | ❌ 待实现 |
| 9 | admin application.yml 缺少 Redis 配置 | 需补充 Redis 连接信息 | ✅ 已补充 |
| 10 | admin 仅空壳 Application | 需实现完整的后台管理CRUD | ❌ 待实现 |
| 11 | im 数据库 my_xhs_im 未创建 | 需建库建表（t_chat + t_chat_user_relation） | ❌ 待创建 |
| 12 | Sentinel Dashboard 未部署 | 限流规则管理控制台 | ❌ 待部署 |

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
| my-xhs-admin | 9013 | my_xhs_admin | 4 |
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