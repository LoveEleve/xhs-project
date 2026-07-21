# my-xhs 模块详细设计

> 每个模块的设计决策必须回答：1. 生产环境遇到什么问题？ 2. 为什么用这个方案？ 3. 故障了怎么办？

---

## 1. my-xhs-common — 公共基础模块

> 所有服务共享的基础能力，不依赖任何业务概念

### 1.1 统一响应体系

| 组件 | 说明 | 生产场景 |
|------|------|----------|
| `R<T>` | 泛型统一响应体 | 15个服务返回格式统一，前端/Gateway不需要适配各种格式 |
| `IErrorCode` | 错误码接口 | 错误码抽象为接口，业务枚举实现，新增服务时规范一致 |
| `BizErrorCode` | 业务错误码枚举 | 参数校验失败40001、用户不存在40101，前端根据code做差异化提示 |
| `SysErrorCode` | 系统错误码枚举 | 系统级错误和应用级错误区分开，监控告警只关注系统级 |

### 1.2 注解式能力

> 📖 《分布式协议与算法实战》第4章 — 一致性哈希 + 分布式锁选型
>
> **Redis锁 vs ZooKeeper锁对比**（书中核心内容）：
>
> | 维度 | Redis(Redisson) | ZooKeeper | my-xhs选择 |
> |------|-----------------|-----------|-----------|
> | 性能 | 高(内存操作,10万+TPS) | 低(磁盘IO+网络广播) | ✅ Redis |
> | 可靠性 | 中(主从切换可能丢锁) | 高(ZAB协议保证一致性) | Redis+看门狗 |
> | 实现方式 | SET NX + 过期+看门狗续期 | 临时顺序节点+Watch | Redisson封装 |
> | 适用场景 | 性能优先(库存扣减/领券) | 可靠性优先(主备选主) | my-xhs场景 |
>
> **为什么选Redisson而不是手写Redis锁？**
> "手写SET NX + EXPIRE有两个致命问题：1) 非原子(SET成功但EXPIRE失败=死锁)
>  2) 业务未执行完锁已过期(业务30秒，锁10秒=并发问题)
>  Redisson的看门狗(Watchdog)自动续期解决了问题2——锁持有期间每10秒自动续期，
>  业务执行完毕释放锁。不需要提前估算业务执行时间"

| 注解 | 功能 | 生产场景 | 核心实现 |
|------|------|----------|----------|
| `@Idempotent` | 幂等拦截 | 用户双击下单/领券，不幂等会重复扣库存，资损事故 | AOP + Redis SET NX + Token机制 |
| `@DistributedLock` | 分布式锁 | 高并发扣库存/领券，不加锁会超卖，运营事故 | AOP + Redisson + LockType枚举(FAIR/REENTRANT/READ/WRITE) |
| `@RateLimit` | 接口限频 | 接口被恶意刷（1秒点赞100次），恶意消耗服务资源 | AOP + Redis Lua滑动窗口 + 降级策略 |

### 1.3 缓存封装

| 组件 | 说明 | 生产场景 |
|------|------|----------|
| `RedisOperator<T>` | 泛型Redis操作封装 | 统一Redis操作入口，避免各服务重复写，出问题只改一处 |
| `CacheHelper` | 缓存助手(Cache Aside封装) | 读：先缓存→没有读DB→写缓存；写：先更新DB→删缓存。封装后不会漏删缓存 |

### 1.4 分布式基础

| 组件 | 说明 | 生产场景 |
|------|------|----------|
| 雪花ID生成器 | 基于Redis分配WorkerId | 分库分表后数据库自增ID会冲突，雪花ID保证全局唯一且趋势递增，适合B+树索引 |
| 号段模式ID | MySQL号段模式 | 用户ID用号段模式，比雪花ID更短（8~10位 vs 18位），适合对外暴露的URL |
| 分片算法 | 一致性哈希+虚拟节点+范围分片 | 不同业务选不同分片策略：订单按买家ID哈希，优惠券按用户ID范围 |
| 分布式序列化 | Jackson统一 + FastJSON2 | Spring Boot 3.x 标配Jackson，FastJSON2 2.x（已完全重写，安全性改善），不使用fastjson 1.x（历史安全漏洞多） |

### 1.5 消息封装

| 组件 | 说明 | 生产场景 |
|------|------|----------|
| `RocketMQTemplate` | 消息发送封装 | 统一消息发送入口，加入TraceId、幂等Key，排查问题时能关联 |
| `BaseMQConsumer` | 消费者基类 | 统一消费逻辑：幂等校验→业务处理→ACK→异常进死信，新服务继承即可 |
| `LocalMessageTable` | 本地消息表 | MQ Broker故障时，本地消息表定时扫描补偿，保证消息不丢 |
| `DeadLetterHandler` | 死信队列处理 | 消费重试16次仍失败，进死信队列，必须有人工介入机制 |

### 1.6 请求上下文

| 组件 | 说明 | 生产场景 |
|------|------|----------|
| `UserContext` | 用户上下文(ThreadLocal) | Gateway解析JWT后，用户信息透传到业务服务，每个服务自己解析JWT是重复且不一致的 |
| `TraceIdFilter` | TraceId过滤器 | 15个服务的日志散在各处，没有TraceId根本串不起来，线上排查靠它 |
| `RequestContextHolder` | 请求上下文 | 微服务间调用需要传递TraceId、UserId、灰度标记，否则下游服务看不到调用链 |

### 1.7 全局异常

| 组件 | 说明 | 生产场景 |
|------|------|----------|
| `BizException` | 业务异常 | 业务校验失败抛出，统一捕获返回友好信息，不暴露内部细节 |
| `SysException` | 系统异常 | 系统级错误(网络超时、DB异常)，必须触发告警，人工介入 |
| `RemoteException` | 远程调用异常 | Feign调用失败，记录目标服务+接口+耗时，定位调用链问题 |
| `GlobalExceptionHandler` | 全局异常处理器 | 分级处理：Biz返回友好信息，Sys记录日志+告警，Remote降级 |

### 1.8 Feign配置

| 组件 | 说明 | 生产场景 |
|------|------|----------|
| 超时配置 | 连接5s + 读取10s | 不能无限等，Feign默认60s太长，线程池会被耗尽 |
| 重试配置 | 全局NEVER_RETRY + 幂等接口单独开启 | 非幂等接口(下单)重试=重复扣库存，资损事故 |
| 拦截器 | 透传TraceId+UserId+GrayTag | 跨服务调用链路追踪需要TraceId贯穿，否则无法排查跨服务问题 |

### 1.8.1 Feign六大坑及防坑方案

> 生产环境踩过的坑，my-xhs 必须规避。

**坑1：GET请求传POJO参数丢失**

```java
// ❌ Feign把对象当RequestBody发，GET没有Body，参数全丢
@GetMapping("/api/inventory/deduct")
Boolean deduct(StockDeductRequest request);

// ✅ 用@SpringQueryAnnotation，对象字段自动转为URL查询参数
@GetMapping("/api/inventory/deduct")
Boolean deduct(@SpringQueryAnnotation StockDeductRequest request);
```

**坑2：请求头丢失（UserId/TraceId透传断裂）**

```java
// 问题：Gateway解析的userId放在Header，但Feign调用下游时Header不会自动传
// 解决：FeignRequestInterceptor
@Component
public class FeignRequestInterceptor implements RequestInterceptor {
    @Override
    public void apply(RequestTemplate template) {
        ServletRequestAttributes attrs = (ServletRequestAttributes) 
            RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletRequest request = attrs.getRequest();
            template.header("X-User-Id", request.getHeader("X-User-Id"));
            template.header("X-Trace-Id", request.getHeader("X-Trace-Id"));
            template.header("X-Gray-Tag", request.getHeader("X-Gray-Tag"));
        }
    }
}
```

**坑3：@RequestParam不指定name（编译后参数名丢失）**

```java
// ❌ 不指定name，编译后参数名变成arg0/arg1
@GetMapping("/api/order/query")
OrderVO query(@RequestParam Long orderId);

// ✅ 必须指定name
@GetMapping("/api/order/query")
OrderVO query(@RequestParam("orderId") Long orderId);
```

**坑4：超时配置不当（默认60秒）**

```yaml
feign:
  client:
    config:
      default:
        connectTimeout: 5000
        readTimeout: 10000
      my-xhs-inventory:          # 库存扣减要求快速响应
        connectTimeout: 3000
        readTimeout: 5000
```

**坑5：重试导致重复扣减**

```java
// ✅ 全局关闭重试（下单等非幂等接口绝对不能重试）
@Bean
public Retryer neverRetry() {
    return Retryer.NEVER_RETRY;
}
// 如果某些幂等接口需要重试，单独配置该Feign Client的Retryer
```

**坑6：异步线程中RequestContextHolder为null**

```java
// ❌ CompletableFuture中Feign调用，ThreadLocal在子线程丢失
// ✅ 手动传递上下文
ServletRequestAttributes attrs = (ServletRequestAttributes) 
    RequestContextHolder.getRequestAttributes();

CompletableFuture.supplyAsync(() -> {
    RequestContextHolder.setRequestAttributes(attrs);
    try {
        return inventoryService.deduct(request);
    } finally {
        RequestContextHolder.resetRequestAttributes();
    }
});
```

### 1.9 优雅停机

| 配置 | 说明 | 生产场景 |
|------|------|----------|
| `server.shutdown=graceful` | 等待现有请求完成 | K8s滚动更新杀Pod，正在处理的订单请求被中断会导致数据不一致 |
| `spring.lifecycle.timeout-per-shutdown-phase=30s` | 最长等30秒 | 30秒内处理不完的请求强制断开，防止Pod永远不退出 |
| PreStop钩子 | 从Nacos注销+等待 | 先从注册中心下线，不再接收新请求，等现有请求完成再退出 |

---

## 2. my-xhs-gateway — API网关

> 📖 **知识来源**：《凤凰架构》第4章 — 服务容错
> - 核心观点："服务容错三板斧：限流、熔断、降级。网关是统一入口，必须在网关层做容错"
> - 限流："令牌桶/漏桶算法，超过阈值直接拒绝，保护后端服务"
> - 熔断："下游服务异常比例超阈值，自动熔断，不再调用，避免雪崩"
> - 降级："非核心功能降级返回默认值，保证核心链路不挂"
> - my-xhs对照：Sentinel限流+熔断+降级，统一在Gateway层

### 2.1 路由管理

| 能力 | 实现 | 生产场景 |
|------|------|----------|
| Nacos动态路由 | 路由配置存Nacos，变更实时生效 | 微服务上下线不需要重启网关，发布不停服 |
| 路由规则 | Path匹配 + StripPrefix | 前端访问路径统一加服务前缀，网关去掉前缀转发，路径规范一致 |
| 白名单 | 登录/注册/验证码等接口免鉴权 | 不是所有接口都需要登录，白名单配置化管理 |

### 2.2 安全能力

| 能力 | 实现 | 生产场景 |
|------|------|----------|
| JWT内部校验 | 解析Token获取UserId/Role | 未登录请求不能打到业务服务，统一拦截减少后端压力 |
| HMAC签名校验 | GlobalFilter + HMAC-SHA256(timestamp+nonce+body+secretKey) | 接口被恶意调用/篡改，签名保证请求未被篡改（微信/支付宝开放平台标配） |
| 防重放攻击 | timestamp 5分钟过期 + nonce Redis去重 | 同一个请求不能重复提交，5分钟内同一nonce只能出现一次 |
| 限频 | Redis Lua滑动窗口 | 同一IP 1秒请求100次，明显是攻击或爬虫 |

### 2.3 流量治理

| 能力 | 实现 | 生产场景 |
|------|------|----------|
| Sentinel限流 | 热点参数限流(按SKU ID限流) | 秒杀商品详情页QPS 1万+，不限流DB扛不住 |
| Sentinel熔断 | 慢调用比例+异常比例熔断 | 库存服务挂了，订单不能跟着挂，熔断后走降级 |
| Sentinel降级 | 自定义降级响应(返回默认值/提示) | 降级不是报错，是返回可接受的结果，用户体验不中断 |
| 灰度路由 | Nacos元数据(版本标签)+请求头灰度标识 | 新版本先给5%流量，验证无误再扩到100%，降低发布风险 |
| API版本路由 | Header版本路由 /api/v1/** 和 /api/v2/** | APP老版本用v1接口，新版本用v2，两套并存3个月，避免强制升级 |

### 2.4 可观测性

| 能力 | 实现 | 生产场景 |
|------|------|----------|
| TraceId注入 | GlobalFilter生成/透传 | 全链路日志关联，没有TraceId线上问题根本查不了 |
| 请求日志 | 记录请求路径+方法+耗时+状态码 | 线上问题排查需要看请求链路，特别是5xx错误 |
| 慢接口告警 | 接口超5秒记录WARN日志 | 及时发现性能瓶颈，5秒以上用户体验已经很差 |

### 2.5 流量染色与全链路标记

> **面试必问**："灰度发布怎么做？全链路追踪怎么实现？压测流量怎么隔离？"
>
> 📖 **知识来源**：慕课网「Java+大数据+AI架构师实战营」流量染色平台章节
> - 核心观点："流量染色=请求头标记+全链路透传，一次标记全程有效"
> - 应用场景："灰度发布、AB测试、压测隔离、全链路追踪"

#### 2.5.1 流量染色架构

```
┌───────────────────────────────────────────────────────────────────────┐
│                         流量染色全链路透传                              │
├───────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  Gateway染色 → HTTP Header透传 → Feign透传 → MQ透传 → 异步任务透传      │
│      ↓              ↓                ↓           ↓            ↓       │
│  X-Trace-Id    X-Gray-Tag      X-AB-Group   X-Pressure   ThreadLocal  │
│  X-User-Id     X-Api-Version                                          │
│                                                                       │
└───────────────────────────────────────────────────────────────────────┘
```

#### 2.5.2 染色标记定义

| 标记 | 说明 | 用途 |
|------|------|------|
| `X-Trace-Id` | 全链路追踪ID | 串联15个服务的日志+链路 |
| `X-Gray-Tag` | 灰度标记(beta/stable) | 灰度发布路由 |
| `X-Api-Version` | API版本号(v1/v2) | 多版本API路由 |
| `X-AB-Group` | AB测试分组(A/B/C) | 不同推荐策略/UI实验 |
| `X-Pressure-Test` | 压测标记(true/false) | 压测流量隔离，写影子表 |
| `X-User-Id` | 用户ID | 下游服务获取当前用户 |

#### 2.5.3 Gateway染色Filter

```java
@Component
public class TrafficDyeingFilter implements GlobalFilter, Ordered {
    
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpRequest.Builder builder = request.mutate();
        
        // 1. TraceId（没有则生成）
        String traceId = request.getHeaders().getFirst("X-Trace-Id");
        if (StringUtils.isEmpty(traceId)) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        builder.header("X-Trace-Id", traceId);
        
        // 2. 灰度标记（根据用户ID/IP/百分比决定）
        String grayTag = determineGrayTag(request);
        builder.header("X-Gray-Tag", grayTag);
        
        // 3. AB测试分组
        String abGroup = determineABGroup(request);
        builder.header("X-AB-Group", abGroup);
        
        // 4. 压测标记（特定Header或特定用户）
        String pressureTest = request.getHeaders().getFirst("X-Pressure-Test");
        if ("true".equals(pressureTest)) {
            builder.header("X-Pressure-Test", "true");
        }
        
        // 5. 用户ID（从JWT解析）
        String userId = extractUserIdFromToken(request);
        if (userId != null) {
            builder.header("X-User-Id", userId);
        }
        
        return chain.filter(exchange.mutate().request(builder.build()).build());
    }
    
    private String determineGrayTag(ServerHttpRequest request) {
        // 灰度策略：
        // 1. 请求头显式指定 → 直接使用
        // 2. 用户在灰度名单 → beta
        // 3. 用户ID尾号0-1 → beta（10%灰度）
        // 4. 其他 → stable
        String explicit = request.getHeaders().getFirst("X-Gray-Tag");
        if (StringUtils.hasText(explicit)) {
            return explicit;
        }
        
        String userId = extractUserIdFromToken(request);
        if (userId != null && grayListService.isInGrayList(userId)) {
            return "beta";
        }
        if (userId != null && userId.endsWith("0") || userId.endsWith("1")) {
            return "beta";
        }
        return "stable";
    }
}
```

#### 2.5.4 Feign透传Interceptor

```java
// 确保Feign调用时染色标记不丢失
@Component
public class TrafficDyeingFeignInterceptor implements RequestInterceptor {
    
    private static final List<String> DYEING_HEADERS = Arrays.asList(
        "X-Trace-Id", "X-Gray-Tag", "X-AB-Group", 
        "X-Pressure-Test", "X-User-Id", "X-Api-Version"
    );
    
    @Override
    public void apply(RequestTemplate template) {
        ServletRequestAttributes attrs = (ServletRequestAttributes) 
            RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return;
        }
        
        HttpServletRequest request = attrs.getRequest();
        for (String header : DYEING_HEADERS) {
            String value = request.getHeader(header);
            if (StringUtils.hasText(value)) {
                template.header(header, value);
            }
        }
    }
}
```

#### 2.5.5 MQ透传

```java
// RocketMQ发送时带上染色标记
public void sendWithDyeing(String topic, Object payload) {
    Message<Object> message = MessageBuilder.withPayload(payload)
        .setHeader("X-Trace-Id", TraceContext.getTraceId())
        .setHeader("X-Gray-Tag", TraceContext.getGrayTag())
        .setHeader("X-Pressure-Test", TraceContext.isPressureTest())
        .build();
    
    rocketMQTemplate.send(topic, message);
}

// RocketMQ消费时恢复染色上下文
@RocketMQMessageListener(topic = "xxx-topic")
public class XxxConsumer implements RocketMQListener<MessageExt> {
    @Override
    public void onMessage(MessageExt message) {
        // 恢复染色上下文
        TraceContext.setTraceId(message.getProperty("X-Trace-Id"));
        TraceContext.setGrayTag(message.getProperty("X-Gray-Tag"));
        TraceContext.setPressureTest("true".equals(message.getProperty("X-Pressure-Test")));
        
        try {
            // 业务逻辑
        } finally {
            TraceContext.clear();
        }
    }
}
```

#### 2.5.6 压测流量隔离

```java
// 压测流量写影子表
@Aspect
@Component
public class PressureTestAspect {
    
    @Around("@annotation(com.myxhs.common.annotation.PressureTestAware)")
    public Object around(ProceedingJoinPoint point) throws Throwable {
        if (TraceContext.isPressureTest()) {
            // 切换到影子数据源
            DynamicDataSourceContextHolder.push("shadow");
            try {
                return point.proceed();
            } finally {
                DynamicDataSourceContextHolder.pop();
            }
        }
        return point.proceed();
    }
}

// 影子表命名规则：原表名_shadow
// t_order → t_order_shadow
// 压测数据不会污染生产数据
```

### 2.6 容量与故障预案

> 📖 《高并发系统：设计原理与实践》第3章 — 服务降级
>
> **降级场景分类**（书中核心框架）：
>
> | 降级类型 | 场景 | my-xhs落地 |
> |----------|------|-----------|
> | **读降级** | 首页判断用户是否点赞，高峰期统一显示"未点赞" | ✅ 高峰期跳过点赞状态查询 |
> | **写延迟降级** | 订单后同步发券，高峰期改为异步延迟发券 | ✅ 下单后MQ异步发券 |
> | **存储降级** | DB故障但缓存命中率>99%，暂时降级DB | ✅ Redis可用时降级走缓存 |
> | **功能降级** | 非核心功能(推荐/热搜)暂时关闭 | ✅ 降级开关控制 |
>
> **降级预案管理**（书中关键实践）：
> "降级开关可能达到几百个，故障时难以快速找到。解决方案：
>  将多个相关降级开关组成**预案**，预案与特定故障场景关联，一键执行预案修复故障。
>  例如：Redis不可用预案 = 开启读降级 + 开启写延迟 + 开启本地限流"

| 项目 | 说明 |
|------|------|
| 容量估算 | 网关单实例承载3000~5000 QPS，生产环境至少2实例，峰值1万QPS需3~4实例 |
| 故障预案 | 网关全部实例挂 → K8s自动重启 + Nacos本地缓存兜底；Nacos不可用 → 本地缓存路由表，新服务注册不了但不影响已有路由 |
| 降级策略 | 后端服务全挂 → 返回统一降级页面；Redis不可用 → 限流降级为本地计数 |

---

## 3. my-xhs-user — 用户服务

### 3.1 核心功能

| 功能 | 实现 | 生产场景 |
|------|------|----------|
| 注册 | 账号密码 + 参数校验 + 唯一性保证 | 高并发注册，唯一索引+分布式锁保证账号唯一，DB唯一索引兜底 |
| 登录 | 账号密码校验 → 生成双Token | Access Token 30分钟过期被窃取影响有限，Refresh 7天免频繁登录 |
| Token刷新 | Refresh Token换Access Token | Access过期后用Refresh换新的，不需要重新登录，体验好 |
| Token注销 | Redis黑名单 | 用户主动退出登录，Token还在有效期内，必须拉黑，否则被盗号后无法阻止 |
| 图形验证码 | Kaptcha生成 → Redis存储(5分钟过期) | 防止注册/登录接口被暴力破解，消耗服务资源 |

### 3.2 验证码技术点说明

> 验证码的**发送**（JavaMail/SMS SDK）无技术深度，不作为设计重点。技术深度在**存储+校验+限频**。

| 技术点 | 实现 | 生产场景 |
|--------|------|----------|
| 存储与过期 | Redis String + 5分钟TTL | 验证码短时效，过期自动删除，不占存储 |
| 校验与消费 | 校验后立即删除(防重用) | 验证码用1次就失效，不能重复使用 |
| 限频 | @RateLimit 同IP 1分钟3次 | 防刷，短信/邮件有成本 |
| 发送方式 | 开发环境Mock，生产环境接SMS SDK | 发送本身无技术深度，`SmsService`接口+Mock实现，可运行 |

### 3.3 缓存策略

| 数据 | 缓存方案 | 生产场景 |
|------|----------|----------|
| 用户基本信息 | Cache Aside + 延迟双删 | 每次请求都要查用户信息，走DB扛不住；延迟双删防旧值回填 |
| 用户画像标签 | Redis Set | 推荐需要用户偏好标签，Set支持交并集运算(共同关注等) |
| 登录Token | Redis String + TTL | Token校验走Redis，不查DB，毫秒级校验 |
| 验证码 | Redis String + 5分钟TTL | 验证码短时效，过期自动删除 |

### 3.4 风控

| 场景 | 策略 | 生产场景 |
|------|------|----------|
| 注册限频 | @RateLimit 同一IP 1小时最多注册5次 | 同一IP短时间内大量注册，是刷号/养号行为 |
| 登录限频 | @RateLimit 同一账号 5分钟最多失败5次 | 暴力破解密码，5次失败后锁定15分钟 |
| 验证码限频 | 同一IP 1分钟最多获取3次 | 验证码接口被刷会浪费短信/邮件资源，有成本 |

### 3.5 收货地址

| 功能 | 实现 | 生产场景 |
|------|------|----------|
| CRUD | 基础增删改查 | — |
| 地址数量限制 | 每用户最多20条 | 不能无限添加，否则数据量不可控，单用户查询变慢 |
| 默认地址 | Redis缓存默认地址ID | 下单时默认选中，每次查DB太慢 |
| 地址脱敏 | 返回时手机号中间4位打码 | 前端展示脱敏，防信息泄露，合规要求 |

### 3.6 用户余额字段说明

> `t_user` 表中 `balance` 字段为**虚拟账户余额**，用于优惠券/积分兑换等站内场景，**不涉及真实支付**。
> 生产环境中真实支付走第三方支付网关（支付宝/微信），本项目按需求排除支付模块。
> balance 相关操作（充值、扣减、退款）需在用户服务内部保证事务一致性，不可跨服务直接操作。

### 3.7 容量与故障预案

| 项目 | 说明 |
|------|------|
| 容量估算 | 1000万用户，活跃率10% = 100万DAU，用户信息缓存命中率>95%，DB读QPS < 500 |
| 数据生命周期 | 用户基本信息永久热数据；登录日志30天；最后登录时间实时更新 |
| 故障预案 | 用户服务挂 → Gateway熔断降级返回"系统繁忙"；Redis不可用 → 走DB+限流降级 |
| 数据安全 | 密码BCrypt加密（慢哈希防暴力破解）；手机号/邮箱脱敏存储；Token黑名单防盗用 |

---

## 4. my-xhs-content — 内容服务

### 4.1 笔记核心

| 功能 | 实现 | 生产场景 |
|------|------|----------|
| 笔记CRUD | 草稿→审核→发布 状态机 | 笔记必须审核才能发布，否则涉黄涉政，合规风险 |
| 笔记类型 | 图文笔记 | 小红书核心内容形态 |
| 笔记标签 | 标签体系 + 笔记-标签多对多关联 | 推荐和搜索都需要标签维度，无标签无法做内容分发 |
| 笔记图片 | 本地磁盘存储 + `FileStorageService`接口抽象 | 不依赖云OSS，代码跑起来即可；接口抽象保证生产环境零代码切OSS |

### 4.2 文件存储技术点说明

> 文件**上传**（MultipartFile写入磁盘/OSS SDK）无技术深度，不作为设计重点。技术深度在**存储策略的可切换性**。

| 技术点 | 实现 | 生产场景 |
|--------|------|----------|
| 接口抽象 | `FileStorageService`接口 + `LocalFileStorage`实现 | 开发环境本地磁盘，生产环境切`OssFileStorage`，业务代码零改动 |
| 文件命名 | UUID + 时间戳防冲突 | 文件名冲突会覆盖，UUID保证唯一 |
| 文件大小限制 | Spring Boot配置 max-file-size=5MB | 防止大文件上传耗尽磁盘/带宽 |
| 图片压缩 | 可选：Thumbnailator压缩后存储 | 节省存储空间，提升加载速度 |

### 4.3 笔记状态机

```
草稿(DRAFT) ──发布──→ 待审核(PENDING) ──机审通过──→ 已发布(PUBLISHED)
    ↑                    │                      │
    │                   审核驳回                  │
    │                    ↓                       │
    │              审核驳回(REJECTED)             │
    │                    │                       │
    └──── 重新编辑 ──────┘                       │
                                                 │
                          已发布 ──作者删除──→ 已删除(DELETED)
```

### 4.4 评论系统

| 功能 | 实现 | 生产场景 |
|------|------|----------|
| 评论CRUD | 评论+楼中楼(parentId) | 小红书评论是核心互动，楼中楼增加社交深度 |
| 评论排序 | 时间排序 + 热度排序 | 热门评论优先展示，提升互动率 |
| 评论敏感词 | DFA算法 + 10万词库 | 10万词库毫秒匹配，比SQL LIKE快100倍，评论实时性要求高 |
| 评论顺序消息 | RocketMQ顺序消息(同一笔记的评论有序) | 评论需要按时间顺序展示，乱序体验差 |

### 4.5 DFA敏感词算法

```
为什么用DFA而不是SQL LIKE？
- SQL LIKE：10万词 × 1篇文章 = 10万次LIKE查询，秒级
- DFA Trie树：构建1次，1篇文章遍历1遍，毫秒级
- 生产考量：10万敏感词用SQL查性能不可接受，DFA Trie树构建1次查1遍，O(n)复杂度n是文章长度
- 注意：DFA Trie树需要热更新机制，运营新增敏感词后实时生效（Redis Pub/Sub通知刷新）
```

### 4.6 点赞/收藏

| 功能 | 实现 | 生产场景 |
|------|------|----------|
| 点赞/收藏 | Redis Set + MQ异步到计数服务 | 10万赞如果同步写DB，DB会挂，必须异步 |
| 批量写入 | Buffer-Trigger攒批100条写1次DB | 点赞事件每秒几千条，逐条写DB扛不住 |
| 去重 | Redis Set保证同一用户不能重复点赞 | 用户对同一笔记只能点1次赞，重复点赞覆盖而不是新增 |

### 4.7 笔记缓存策略

| 数据 | 缓存方案 | 生产场景 |
|------|------|----------|
| 笔记详情 | Cache Aside + 逻辑过期 | 热点笔记QPS高，逻辑过期避免缓存击穿（不设TTL，过期后异步更新，返回旧数据不阻塞） |
| 笔记列表 | Redis Zset(按时间/热度排序) | 列表翻页用Zset分数范围查询，性能好 |
| 笔记标签 | Redis Set | 标签查询走缓存，不查DB |

### 4.8 容量与故障预案

| 项目 | 说明 |
|------|------|
| 容量估算 | 5000万笔记，日新增10万；热点笔记缓存命中率>90%，冷笔记走DB可接受 |
| 数据生命周期 | 热数据6个月(MySQL+Redis) → 温数据2年(MySQL) → 冷数据永久(MySQL冷库) → 不删除，标记不可见 |
| 故障预案 | 笔记服务挂 → Feed流降级不展示新笔记；评论服务异常 → 返回空评论列表，笔记仍可浏览 |
| 内容安全 | 机审优先(Trie+AI)，人审兜底；审核不通过的笔记自动进入人工审核队列 |

---

## 5. my-xhs-analytics — 分析服务

> **领域定位**：Feed流是社交平台的核心，推拉模型是经典的系统设计题。当前在 my-xhs 中实现**完整版**，
> 覆盖关注体系、推拉结合Feed流、大V处理、粉丝分桶。后续可独立深入：推荐算法、内容去重、
> 阅读进度同步、实时性优化。Feed流本身足够做一个独立学习项目（微博/抖音/Twitter本质就是Feed流）。
>
> 📖 **知识来源**：《亿级流量系统架构设计与实战》第4章 — 微信朋友圈Feed流方案
> - 核心观点："普通用户发动态→推送到所有好友收件箱（写扩散）；大V发动态→不推送，读取时再聚合（读扩散）"
> - 关键权衡：推模式写放大但读极快，拉模式读放大但写极快，推拉结合两全其美
> - 大V阈值：粉丝>10万标记为大V，极端大V(>500万)走拉模式
> - my-xhs对照：完全对齐，关注流推模式+发现流拉模式+大V判断+粉丝分桶

### 5.1 关注体系

| 功能 | 实现 | 生产场景 |
|------|------|----------|
| 关注/取关 | Redis Zset(score=关注时间) | 粉丝列表按关注时间排序，Zset天然支持 |
| 粉丝列表 | Redis Zset + 分页 | 大V百万粉丝，不能一次全查，分页+缓存 |
| 共同关注 | Redis Set交集运算 | 你关注的人也关注了他，社交推荐基础 |
| 话题 | 话题创建 + 话题下笔记聚合(Redis Zset) | 小红书浏览主要靠话题，#北京美食 |
| 热门话题 | Redis Zset(实时计数) | 话题浏览量实时排序，Top10就是热门话题 |

### 5.2 Feed流架构（核心）

| Feed类型 | 模式 | 生产场景 |
|----------|------|----------|
| 关注流(关注的人发的笔记) | **推模式**：发笔记→写入所有粉丝的收件箱 | 关注流需要实时看到，拉模式大V粉丝千万太慢 |
| 发现流(推荐内容) | **拉模式**：实时聚合多种来源 | 非关注内容实时聚合，推模式数据量太大 |

#### 推模式实现

> 📖 《亿级流量系统架构设计与实战》第11章 — Feed流设计
>
> 书中的微信朋友圈推模式架构：
> ```
> 发动态 → 写入动态表 → 查好友列表 → 遍历写入好友收件箱 → 完成推送
> ```
> 
> **关键洞察——推模式的写扩散问题**：
> "推模式本质是写扩散——1条动态要写N份（N=粉丝数）。大V有100万粉丝，
>  发1条动态要写100万次Redis。必须做异步+限速，否则Redis瞬间被打爆"
> 
> **大V处理策略**：
> "粉丝数>10万标记为大V。大V发动态走异步慢推（不阻塞发动态接口），
>  极端大V(>500万)走拉模式，不做推扩散。拉模式在用户打开时实时聚合"

```
用户发笔记 → 
  1. 写入笔记表
  2. 查粉丝列表 → 遍历写入每个粉丝的收件箱(Redis ZSet, score=发布时间)
  3. 大V(粉丝>10万) → 异步慢推(MQ异步，不阻塞发笔记)
  4. 普通用户 → 同步快推(粉丝少秒级完成)
  5. 极端大V(粉丝>500万) → 不推送，读取时实时拉取

粉丝分桶（大V优化）：
  大V的粉丝列表按ID分桶存储：
  - social:follower:bucket:{userId}:0 → ZSet(0-100万粉丝)
  - social:follower:bucket:{userId}:1 → ZSet(100-200万粉丝)
  - 推送时按桶并行推，单桶推送完成即确认，不阻塞

生产注意事项：
- 推模式写扩散，大V发1条笔记 = N次Redis写入，N可能=100万
- 必须做异步+限速，否则Redis被打爆
- 极端大V(粉丝>500万)走拉模式，不做推扩散
```

#### 拉模式实现

> 📖 《亿级流量系统架构设计与实战》第11章 — Feed流设计
>
> 书中拉模式的关键权衡：
> "拉模式每次打开都要聚合所有关注人的动态，RT比推模式高。
>  但拉模式不写扩散，大V发动态0次额外写入，写入压力极低。
>  缓存策略：聚合结果缓存5分钟，5分钟内的重复请求直接返回缓存"
> 
> **读扩散 vs 写扩散的本质权衡**：
> | 维度 | 推模式(写扩散) | 拉模式(读扩散) |
> |------|---------------|---------------|
> | 写入代价 | 高(1条动态→N次写入) | 低(1条动态→1次写入) |
> | 读取代价 | 低(直接读收件箱) | 高(实时聚合) |
> | 实时性 | 强(推送即达) | 弱(5分钟延迟) |
> | 适用场景 | 关注流(实时性要求高) | 发现流(可接受延迟) |

```
用户打开发现页 → 
  1. 聚合：关注标签的笔记 + 热门话题笔记 + 好友点赞笔记
  2. 排序：时间倒序 + 热度加权
  3. 缓存：结果缓存5分钟

生产注意事项：
- 拉模式每次打开都要聚合，RT比推模式高
- 缓存5分钟是可接受的延迟，强实时性要求走推模式
```

### 5.3 社交缓存

| 数据 | 缓存方案 | 生产场景 |
|------|------|----------|
| 关注列表 | Redis Zset | Zset天然支持时间排序+分页 |
| 粉丝列表 | Redis Zset + 缓存分桶 | 大V粉丝太多，分桶存储减少单Key压力 |
| 关注关系 | Redis Set | O(1)判断是否关注，不用查DB |
| 话题笔记 | Redis Zset(分数=时间) | 话题下笔记按时间排序 |

### 5.4 容量与故障预案

| 项目 | 说明 |
|------|------|
| 容量估算 | 10亿关注关系(人均关注100人)，50亿点赞；关注关系缓存命中率>99% |
| 数据生命周期 | 关注关系永久热数据；点赞记录1年(超过1年的点赞不参与排序) |
| 故障预案 | 社交服务挂 → Feed流降级为发现流(拉模式)；关注关系Redis不可用 → 走DB(性能降级) |
| 大V处理 | 粉丝>10万异步慢推；粉丝>500万走拉模式；粉丝列表分桶存储 |

---

## 6. my-xhs-product — 商品服务

> 📖 **知识来源**：《高并发系统：设计原理与实践》第6章 — 多级缓存
> - 核心观点："热点数据必须多级缓存：本地缓存→Redis→DB。单靠Redis扛不住秒杀级QPS"
> - 本地缓存边界："适合本地缓存——分类树/配置(低频变化)；不适合——库存/价格(高频变化)"
> - HotKey探测："JD-HotKey方案：热点Key自动探测→升级到本地缓存→Redis压力降90%"
> - my-xhs对照：Caffeine本地缓存(L1)+Redis(L2)+MySQL(L3)，HotKey探测自动升级

### 6.1 多级缓存架构

> 📖 《超大流量》第4章 — 本地缓存策略选择
>
> **Caffeine三种过期策略对比**（书中核心内容）：
>
> | 策略 | 特点 | 适用场景 | my-xhs使用 |
> |------|------|----------|-----------|
> | `expireAfterWrite` | 过期后其他线程**阻塞等待**新值 | 强一致性要求 | ❌ 不用 |
> | `expireAfterAccess` | 未读写即回收，访问续期 | 访问频率不确定 | 部分场景 |
> | `refreshAfterWrite` | 过期后**异步刷新**，其他线程返回旧值 | 高性能+可接受短暂脏读 | ✅ 商品详情 |
>
> **关键洞察**：
> "商品详情用`refreshAfterWrite`——过期后异步刷新，用户拿到的是旧值但RT极低。
>  宁可让用户看到1秒前的商品信息，也不能让用户等500ms查DB。
>  这就是'最终一致性'在缓存场景的正确打开方式——
>  可接受短暂脏读的数据用refreshAfterWrite，强一致性的数据走DB"

```
多级缓存读取流程：
1. 请求 → Caffeine本地缓存(L1) → 命中 → 直接返回 (RT < 1ms)
2. 未命中 → Redis(L2) → 命中 → 返回 + 异步回填L1 (RT < 5ms)
3. 未命中 → MySQL(L3) → 返回 + 异步回填L1+L2 (RT < 50ms)

HotKey探测自动升级：
1. JD-HotKey探测到某Key QPS>500 → 标记为热点
2. 推送到各实例 → 升级到Caffeine本地缓存
3. 后续请求直接L1返回 → Redis压力降90%
4. 热度下降后自动从本地缓存移除 → 防止内存泄漏
```

### 6.2 商品模型

```
SPU(标准化产品单元)
 ├── 属性组(AttributeGroup)
 │    ├── 属性(Attribute)：屏幕分辨率、运行内存...
 │    └── 属性值(AttributeValue)：1080P、8G...
 ├── SKU(库存量单元)
 │    ├── 规格组合(Spec组合)：颜色=白色 + 内存=8G
 │    ├── 价格
 │    └── 库存
 └── 品牌(Brand)
```

### 6.3 核心功能

| 功能 | 实现 | 生产场景 |
|------|------|----------|
| SPU/SKU CRUD | 完整电商模型 | SPU是商品抽象，SKU是可售卖单元，标准电商模型 |
| 分类树 | 无限级分类 + 缓存整棵树 | 分类是树形结构，整棵树缓存到Redis，变更频率极低 |
| 品牌CRUD | 品牌关联分类 | — |

### 6.4 商品详情缓存（Caffeine使用场景之一）

```
为什么商品详情可以用Caffeine？
- QPS最高：电商首页/详情页占总流量70%+
- 数据变更少：商品信息修改频率低(一天几次)
- 一致性可接受：变更后通过MQ通知清缓存，秒级生效

缓存链路：
请求 → Caffeine本地缓存(0.01ms) → Redis(0.5ms) → MySQL(5ms)
         ↓ 未命中                    ↓ 未命中        ↓
         查Redis                    查MySQL        返回
         ↓ 命中                     ↓ 命中          ↓
         返回                       写Caffeine      写Redis→写Caffeine

生产注意事项：
- Caffeine本地缓存会导致同一服务不同实例短暂不一致（最多5秒）
- 商品价格变更必须走MQ通知所有实例清缓存
- 不适合库存等强实时性数据
```

### 6.5 Canal数据同步

| 同步模式 | 实现 | 生产场景 |
|----------|------|----------|
| 增量同步 | Canal监听binlog → RocketMQ → 消费写入ES | MySQL→ES实时同步，不能每次全量扫 |
| 全量重建 | XXL-Job每周日凌晨全量重建ES索引 | 增量可能丢数据，全量重建保证一致性 |
| 同步校验 | XXL-Job每天对账MySQL和ES数据量 | 增量同步可能漏数据，对账发现差异自动修复 |

### 6.6 容量与故障预案

| 项目 | 说明 |
|------|------|
| 容量估算 | 100万SPU，1000万SKU；详情页QPS 5000+，Caffeine本地缓存承载70% |
| 数据生命周期 | 商品信息永久热数据；下架商品90天后归档 |
| 故障预案 | 商品服务挂 → 详情页降级返回缓存数据；ES不可用 → 走DB LIKE查询(性能降级) |

---

## 7. my-xhs-cart — 购物车服务

> 📖 **知识来源**：《大型网站技术架构：核心原理与案例分析》（李智慧）第4章
> - 核心观点："购物车是高频读写场景，Redis是最佳存储。但纯Redis有数据丢失风险，必须异步持久化"
> - 数据结构："Hash存商品→数量，Set存选中状态，ZSet加排序——3种Redis结构协同，不是1个Key搞定"
> - my-xhs对照：3种Redis结构 + Redis→MySQL异步落库 + 定时对账 + 启动恢复

### 7.1 核心功能

> 📖 《大型网站技术架构》（李智慧）第4章 + 《亿级流量系统》第12章 — 购物车设计
>
> **3种Redis结构协同**（书中核心方案）：
> ```
> 1. Hash — 存商品→数量       cart:items:{userId}     → {skuId: quantity}
> 2. Set  — 存选中的商品ID     cart:checked:{userId}   → {skuId1, skuId2}
> 3. ZSet — 存排序(加购时间)   cart:sort:{userId}      → {skuId: timestamp}
> ```
>
> **为什么不能1个Key搞定？**
> "如果只用Hash存，选中状态和排序怎么处理？
>  - 方案A：全放Hash，field带前缀如`checked:skuId`、`sort:skuId` → 查询时需要过滤，复杂
>  - 方案B：3种结构协同 → HMGET拿数量 + SMEMBERS拿选中 + ZRANGE拿排序，各取所需
>  方案B更清晰，每种操作用最适合的数据结构"
>
> **匿名购物车合并**（书中方案）：
> "未登录时购物车存Redis(临时Key，7天过期)，登录后合并：
>  1. 读取匿名购物车 2. 遍历合并到正式购物车(相同sku数量取大值)
>  3. 删除匿名购物车Key 4. 返回合并后的购物车"

| 功能 | 实现 | 生产场景 |
|------|------|----------|
| 加购 | Redis Hash(userId→skuId→数量) | 购物车读写频繁，DB扛不住，Redis Hash结构天然适合 |
| 并发加购 | Redis HSETNX去重 | 同一商品快速点2次加购，不能变成2条记录 |
| 合并匿名购物车 | 登录后匿名购物车合并到正式购物车 | 用户未登录时加购，登录后合并，提升转化率 |
| 商品失效标记 | 商品下架→MQ→标记购物车对应项失效 | 商品下架了，购物车要标记出来，不然用户下单会失败 |
| 选中/全选 | Redis Set存储选中的skuId | 结算时只计算选中商品 |
| 排序 | Redis ZSet(score=加购时间) | 购物车按加购时间排序，最近加的排前面 |

### 7.2 购物车持久化方案

> **生产环境购物车数据不能只存Redis**。Redis故障（主从切换/内存淘汰/误删）会导致用户购物车全部丢失，直接影响下单转化率。

| 方案 | 实现 | 生产场景 |
|------|------|----------|
| 异步落库 | Redis写入成功 → MQ异步写入MySQL | 购物车数据有MySQL兜底，Redis故障后可从MySQL恢复 |
| 定时全量同步 | XXL-Job每天凌晨2点 Redis→MySQL全量对账 | 异步落库可能丢数据，全量对账保证一致 |
| 启动恢复 | 服务启动时检查Redis Key存在性，不存在则从MySQL加载 | Redis主从切换或清库后，购物车数据从MySQL恢复 |
| 容忍延迟 | 购物车不涉及金额计算，异步落库的短暂不一致可接受 | 用户感知不到延迟，最差情况重新加购 |

### 7.3 容量与故障预案

| 项目 | 说明 |
|------|------|
| 容量估算 | 100万DAU × 人均10件 = 1000万条；Redis Hash存储，内存占用约2GB |
| 数据生命周期 | 购物车数据30天过期，超期自动清理；匿名购物车7天过期 |
| 故障预案 | Redis不可用 → 从MySQL恢复购物车(性能降级)；MQ不可用 → 购物车仍可操作，MySQL落库延迟 |

---

## 8. my-xhs-order — 订单服务

> **领域定位**：订单/交易是电商核心，分布式事务+分库分表+状态机是架构师必经之路。当前在 my-xhs 中实现**完整版**，
> 因为这是核心交易链路不能简化。后续可独立深入：TCC分布式事务、支付集成、对账系统、退款链路、
> 订单拆单/合单。交易系统本身足够做一个独立学习项目（淘宝/京东的交易中台）。
>
> 📖 **知识来源**：
> - 《深入理解分布式事务》第6章 — 可靠消息最终一致性方案
>   "事务消息Half→本地事务→Commit/Rollback，RocketMQ回查机制兜底"
> - 《凤凰架构》第3章 — 本地消息表兜底
>   "本地事务中同时写业务表和消息表，定时任务扫描消息表投递MQ，投递成功后标记已处理"
> - my-xhs对照：事务消息+本地消息表双保险，完全对齐两本书的方案

### 8.1 核心链路

> 📖 事务消息下单流程详解（对照《深入理解分布式事务》第6章 + 《凤凰架构》第3章）
>
> **《分布式事务》中的事务消息原理**：
> ```
> 1. 生产者发送Half消息（对消费者不可见）
> 2. Broker存储Half消息，返回确认
> 3. 生产者执行本地事务（创建订单+扣库存+扣券）
> 4. 本地事务成功 → 发送Commit → 消息对消费者可见
> 5. 本地事务失败 → 发送Rollback → 消息被删除
> 6. 如果Broker未收到Commit/Rollback → 回查本地事务状态 → 自动补发
> ```
>
> **《凤凰架构》中的本地消息表兜底**：
> "事务消息的回查机制依赖Broker正常运行。如果Broker整体不可用，
>  本地消息表是最后的兜底：本地事务中同时写业务表和消息表，
>  定时任务扫描消息表投递MQ，投递成功后标记已处理。
>  本地消息表和业务表在同一个事务中，保证了原子性。"
>
> **my-xhs双保险方案**：
> - 主方案：RocketMQ事务消息（正常情况）
> - 兜底方案：本地消息表 + XXL-Job定时扫描补偿（MQ不可用时）
> - 两者互补：事务消息实时性好但依赖Broker；本地消息表延迟高但不依赖Broker

| 功能 | 实现 | 生产场景 |
|------|------|----------|
| 下单 | RocketMQ事务消息 + 本地消息表双保险 | 下单要同时创建订单+扣库存+扣券，任何一步失败都要回滚 |
| 模拟支付 | MockPayController — 不接第三方，直接改订单状态 | 不依赖支付宝/微信SDK，链路完整跑通；生产环境切真实支付只需加1个实现类 |
| 下单异步编排 | CompletableFuture(查用户/商品/库存/券并行) | 串行600ms，并行150ms，4个服务查询无依赖关系可以并行 |
| 状态机 | Spring StateMachine + 状态转换规则配置化 | 订单9种状态，硬编码if-else维护不了，状态机清晰 |
| 超时关单 | RocketMQ延时消息(主) + XXL-Job定时扫描(兜底) | 延时消息是主方案，但MQ可能丢消息，定时任务兜底 |
| 取消订单 | 取消→释放库存→退券→通知 四步联动 | 取消不是只改状态，关联资源都要释放 |

### 8.2 订单状态机

```
已创建 ──确认──→ 已确认 ──支付──→ 已支付 ──履约──→ 已履约 ──出库──→ 出库中 ──配送──→ 配送中 ──签收──→ 已签收
  │                              │
  │超时/取消                      │退款
  ↓                              ↓
已取消                          已退款
```

> 共9种状态，与技术规格大纲DDL定义一致：1已创建 2已确认 3已支付 4已履约 5出库中 6配送中 7已签收 8已取消 9已退款

### 8.3 模拟支付方案

> 本项目不集成支付宝/微信支付（避免第三方SDK依赖和商户配置），但支付是电商链路的关键环节，
> 没有支付则订单永远停在"已创建"，购物车/库存/优惠券的业务闭环无法验证。
> 因此在订单服务内部实现**模拟支付**，1个接口完成支付状态变更，链路完整跑通。

```java
// 支付接口抽象
public interface PayService {
    PayResult pay(PayRequest request);
}

// 模拟支付实现（当前使用）
@Service
public class MockPayService implements PayService {
    public PayResult pay(PayRequest request) {
        // 直接返回支付成功，不调用第三方
        return PayResult.success(request.getOrderNo(), "MOCK_" + IdUtil.fastSimpleUUID());
    }
}

// 生产环境：新增 AlipayPayService / WechatPayService 实现类
// 通过 @ConditionalOnProperty 切换，业务代码零改动
```

| 维度 | 说明 |
|------|------|
| 代码量 | 1个接口 + 1个Mock实现 + 1个Controller，不超过80行 |
| 依赖 | 不依赖支付宝/微信SDK，不需要商户号 |
| 链路 | 完整跑通：下单→模拟支付→出库→配送→签收 |
| 切换成本 | 生产环境切真实支付只需加1个实现类 + 配置开关 |
| 用户操作 | 前端点"支付"按钮 → 直接成功 → 订单状态变更 |

### 8.4 分库分表

| 配置 | 说明 | 生产场景 |
|------|------|----------|
| 分片策略 | 按buyer_id哈希分库(4库) + 按order_id哈希分表(8表/库) | 按买家ID分片，同一买家的订单在同一库，查询不需要跨库 |
| 分片算法 | 自定义一致性哈希+虚拟节点 | 一致性哈希扩容时数据迁移量最小 |
| 主键策略 | 雪花ID(Redis分配WorkerId) | 分库分表后自增ID会冲突 |
| 读写分离 | ShardingSphere主从路由 | 写走主库，读走从库，订单列表查询打到从库 |

### 8.5 订单快照

| 功能 | 实现 | 生产场景 |
|------|------|----------|
| 创建快照 | 下单时保存商品/价格/地址快照JSON | 商品价格会变，下单时必须快照，否则价格争议 |
| 快照版本链 | 每次状态变更保存快照 | 订单状态流转过程可追溯，处理客诉需要 |
| Canal同步ES | 订单数据增量同步ES | 订单列表按条件搜索，MySQL多条件分页慢，ES快 |

### 8.6 容量与故障预案

| 项目 | 说明 |
|------|------|
| 容量估算 | 日订单5万，3个月约450万订单，分4库8表后单表约14万行，性能良好 |
| 数据生命周期 | 热数据3个月(MySQL主库) → 温数据1年(MySQL从库) → 冷数据3年(归档库) → 3年后归档到对象存储 |
| 故障预案 | 订单服务挂 → 下单接口降级返回"系统繁忙"；库存扣减失败 → 事务消息回滚+本地消息表补偿 |
| 数据安全 | 订单敏感信息(收货人/电话/地址)加密存储；订单号不含业务信息(防猜测) |

---

## 9. my-xhs-inventory — 库存服务

> **领域定位**：秒杀/库存扣减是经典的高并发问题，很多公司有独立的秒杀团队。当前在 my-xhs 中实现**完整版**，
> 4版演进（DB直接扣→Redis预扣→分桶→自动均衡）是核心技术亮点。后续可独立深入：秒杀排队、
> 防刷策略、动态定价、库存预测、多级缓存预热。库存系统本身足够做一个独立学习项目。
>
> 📖 **知识来源**：《超大流量分布式系统架构解决方案》第7章 — 抢购技术
> - 核心观点："1000库存拆10个桶每桶100，用户按userId%10路由到固定桶，单桶扣减失败遍历其他桶尝试"
> - 关键洞察："热点Key分桶，本质是把N个请求对1个Key的竞争，分散成对N个Key的竞争"
> - 自动均衡："监控各桶余量，低于阈值从其他桶借"
> - my-xhs对照：完全对齐，4版演进最终到分桶+自动均衡方案

### 9.1 核心功能

| 功能 | 实现 | 生产场景 |
|------|------|----------|
| 库存初始化 | Lua脚本 + 分桶初始化(DB→Redis多桶) | 热门商品10万人抢，1个Redis Key是热点，分10个桶分散压力 |
| 预扣减 | Lua原子操作(检查+扣减1步完成) | 高并发扣库存，先查再减2步操作不是原子的，Lua脚本1步完成 |
| 分桶路由 | 按userId % bucketCount路由到不同桶 | 分散热点，避免单Key瓶颈 |
| 预扣回退 | 预扣超时自动回退 + 取消订单主动回退 | 下单预扣库存但30分钟没支付，不回退就库存泄漏 |
| 合并扣DB | Buffer-Trigger攒批写DB | 扣减是Redis操作，但最终要落DB，攒批写减少DB压力 |
| 库存对账 | XXL-Job每小时Redis↔DB对账修复 | Redis和DB数据可能不一致，定时对账修复 |
| 低库存预警 | 库存<阈值→MQ通知→运营补货 | 库存卖完了用户还下单会失败，需要提前预警 |

### 9.2 库存扣减方案演进

> 📖 《超大流量分布式系统架构解决方案》第4章 — 大促抢购核心技术
>
> **关键洞察——热点Key分桶原理**：
> "1000库存拆10个桶每桶100。用户按userId%10路由到固定桶。
>  单桶扣减失败 → 遍历其他桶尝试。桶间自动均衡：监控各桶余量，
>  低于阈值从其他桶借。本质是把N个请求对1个Key的竞争，分散成对N个Key的竞争"
>
> **关键洞察——库存脏读可接受**：
> "对读场景而言，完全可以接受库存脏读。因为读到的'有库存'仅代表那一刻有库存，
>  最终扣减时才真正判断。宁可让少量用户看到'有库存'后扣减失败，也不能让库存
>  读请求把缓存击穿。这是'最终一致性'在库存场景的正确打开方式"

```
第1版：DB直接扣
  UPDATE stock SET count=count-1 WHERE id=? AND count>0
  问题：QPS 500就扛不住
  
  📖 《超大流量》："DB的行锁是瓶颈，UPDATE同一行只能串行执行，
  500 QPS后RT急剧上升，用户感知到卡顿"

第2版：Redis预扣 + 异步落DB
  Lua: if count>0 then count=count-1 return 1 else return 0 end
  问题：热门商品1个Key是热点
  
  📖 《超大流量》："Redis单Key的QPS极限约10万，但秒杀场景10万人同时抢1个Key，
  加上网络开销，单Key成为热点。热点Key分桶是终极解法"

第3版：Redis预扣 + 分桶
  1个商品库存拆10个桶，按userId路由
  问题：分桶不均匀导致某些桶先卖完
  
  📖 《超大流量》："分桶路由userId%10，如果用户分布不均匀(如某号段用户活跃)，
  某些桶先卖完。需要桶间自动均衡——卖完的桶自动路由到其他桶"

第4版：Redis预扣 + 分桶 + 自动均衡
  桶卖完自动路由到其他桶 + Buffer-Trigger批量写DB
  → 最终方案
  
  📖 《超大流量》："自动均衡是第4版的核心。XXL-Job定时检测各桶余量，
  发现某桶余量<20%则从余量多的桶借调。这样即使初始分配不均匀，
  运行一段时间后各桶会趋于均衡"
```

### 9.3 容量与故障预案

| 项目 | 说明 |
|------|------|
| 容量估算 | 1000万SKU，热门SKU分8桶，普通SKU分2桶；秒杀QPS 1万，Redis分桶后单桶QPS < 2000 |
| 数据生命周期 | 库存数据永久热数据；库存流水10亿(按月分表，6个月后归档) |
| 故障预案 | Redis不可用 → 降级走DB直接扣(性能降级+限流)；对账不一致 → 自动修复+告警 |
| 超卖防护 | Lua原子操作保证不超卖；XXL-Job对账发现超卖→告警+人工介入 |

---

## 10. my-xhs-coupon — 优惠券服务

### 10.1 核心功能

| 功能 | 实现 | 生产场景 |
|------|------|----------|
| 模板创建 | 责任链模式(满减/折扣/限时各一个节点) | 优惠券规则多，每种规则一个节点，新规则加节点不改老代码 |
| 领券 | Lua原子操作(扣库存+绑定用户1步完成) | 领券是扣库存+绑定用户两步，Lua保证原子性，否则超领 |
| MQ异步落库 | 先Redis扣减返回，MQ异步写DB | 领券QPS高(秒杀)，先Redis扣减快速返回，MQ异步写DB |
| 延时过期 | RocketMQ延时消息(主) + XXL-Job定时扫描(兜底) | 券到期改状态，延时消息精确触发，定时扫描兜底 |
| 分库分表 | 按用户ID哈希分库分表 | 用户量千万级，券表更大，必须分 |
| 分片推送 | 按用户ID分100片，XXL-Job并行推送 | 100万人发券，不能1次全查，分片并行推 |
| 券核销 | 下单时核销 + 退款退券 | 下单用券要核销，退款要把券退回来 |

### 10.2 责任链模式

```
创建优惠券模板请求
  ↓
┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│ 基础规则校验  │───→│ 库存规则校验  │───→│ 时间规则校验  │───→ 创建成功
│ (名称/类型)   │    │ (数量/限制)   │    │ (起止时间)    │
└──────────────┘    └──────────────┘    └──────────────┘
       ↓ 不通过           ↓ 不通过           ↓ 不通过
     返回错误            返回错误            返回错误
```

### 10.3 容量与故障预案

| 项目 | 说明 |
|------|------|
| 容量估算 | 1万券模板，10亿用户券；分4库32表后单表约780万行 |
| 数据生命周期 | 未使用券有效期内存活；已使用/已过期券1年后归档 |
| 故障预案 | 领券Redis不可用 → 降级走DB(性能降级+限流)；超领 → 对账发现后标记异常+告警 |

---

## 11. my-xhs-search — 搜索服务

> **领域定位**：搜索是一个完整的职业方向（搜索工程师），ES本身就有足够的技术深度。当前在 my-xhs 中实现**完整版**，
> 覆盖ES索引设计、分词策略、搜索建议、Canal增量同步、全量重建。后续可独立深入：搜索排序优化、
> 个性化搜索、搜索广告、ES集群调优、向量搜索。搜索系统本身足够做一个独立学习项目。
>
> 📖 **知识来源**：《分布式系统实战派》第8章 — 搜索系统设计
> - 核心观点："搜索服务必须独立，不能耦合在业务服务中。增量同步(Canal+MQ)保证近实时，全量重建保证最终一致"
> - 增量vs全量："增量轻量但可能丢数据，全量重但保证一致。两者结合：增量为主+定期全量兜底"
> - my-xhs对照：Canal→MQ→ES增量 + XXL-Job每周全量重建

### 11.1 核心功能

| 功能 | 实现 | 生产场景 |
|------|------|----------|
| 商品搜索 | ES多条件查询(关键词+分类+品牌+价格区间) | MySQL LIKE全表扫描，10万商品要3秒，ES毫秒级 |
| 笔记搜索 | ES全文搜索(标题+内容+标签) | 笔记内容更长，更不能用MySQL查 |
| 搜索建议 | ES Completion Suggester + 前缀匹配 | 输入'手'，下拉提示'手机/手表/手链'，减少用户输入 |
| 热搜榜 | 滑动窗口实时计算+防刷策略 | 实时热搜是社交平台标配，微博热搜就是这个场景 |
| 热搜词清理 | XXL-Job每天凌晨清理7天前的热搜词 | 热搜有时效性，不能让去年热搜一直占着 |
| 搜索统计 | 搜索词→MQ→聚合统计 | 运营需要知道用户在搜什么，指导选品和内容运营 |
| 搜索日志 | 异步记录搜索行为(搜索词+结果数+耗时) | 搜索优化需要数据支撑，零结果搜索词需要分析 |

### 11.2 热搜榜实时计算

> **面试必问**："微博热搜是怎么实现的？"
>
> 📖 **知识来源**：慕课网「Java+大数据+AI架构师实战营」热搜榜实时计算章节
> - 核心观点："热搜=滑动窗口聚合+时间衰减+防刷策略"
> - 实时性要求："热搜必须实时更新，不能用定时任务批量计算"

#### 11.2.1 热搜计算架构

```
┌──────────────────────────────────────────────────────────────────┐
│                      热搜榜实时计算架构                            │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  用户搜索 → 防刷过滤 → RocketMQ → 消费聚合 → Redis ZSet → 热搜榜   │
│                                    ↓                             │
│                          XXL-Job定时快照 → MySQL(历史热搜)         │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

#### 11.2.2 热度计算公式

```
热度分 = 搜索量 × 时间衰减因子 × 质量因子

时间衰减因子：
  decay = e^(-λ × t)
  λ = 0.1（衰减系数）
  t = 距离当前时间的小时数
  
  示例：1小时前的搜索权重=0.9，6小时前=0.55，24小时前=0.09

质量因子（防刷）：
  1. 同IP 1分钟内搜索同一词 → 只计1次
  2. 同用户 5分钟内搜索同一词 → 只计1次
  3. 搜索后无点击行为 → 权重×0.5
  4. 搜索词长度<2或>50 → 不计入热搜
```

#### 11.2.3 滑动窗口实现

```java
// 热搜消费者 - 滑动窗口聚合
@RocketMQMessageListener(topic = "search-event-topic")
public class HotSearchConsumer implements RocketMQListener<SearchEvent> {
    
    @Override
    public void onMessage(SearchEvent event) {
        String keyword = event.getKeyword();
        
        // 1. 防刷校验
        if (!antiSpamCheck(event)) {
            return;
        }
        
        // 2. 计算热度增量（带时间衰减）
        double score = calculateScore(event);
        
        // 3. 滑动窗口聚合（Redis ZSet）
        // Key: hot:search:realtime, Score: 热度分, Member: 搜索词
        redisTemplate.opsForZSet().incrementScore(
            "hot:search:realtime", keyword, score);
        
        // 4. 记录搜索词最后更新时间（用于清理过期词）
        redisTemplate.opsForHash().put(
            "hot:search:lastUpdate", keyword, System.currentTimeMillis());
    }
    
    private boolean antiSpamCheck(SearchEvent event) {
        // 同IP 1分钟内同一词只计1次
        String ipKey = "hot:search:ip:" + event.getIp() + ":" + event.getKeyword();
        if (redisTemplate.hasKey(ipKey)) {
            return false;
        }
        redisTemplate.opsForValue().set(ipKey, "1", 1, TimeUnit.MINUTES);
        
        // 同用户 5分钟内同一词只计1次
        if (event.getUserId() != null) {
            String userKey = "hot:search:user:" + event.getUserId() + ":" + event.getKeyword();
            if (redisTemplate.hasKey(userKey)) {
                return false;
            }
            redisTemplate.opsForValue().set(userKey, "1", 5, TimeUnit.MINUTES);
        }
        
        return true;
    }
}
```

#### 11.2.4 热搜榜查询

```java
// 获取实时热搜Top50
public List<HotSearchVO> getHotSearchList(int topN) {
    Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet()
        .reverseRangeWithScores("hot:search:realtime", 0, topN - 1);
    
    List<HotSearchVO> result = new ArrayList<>();
    int rank = 1;
    for (ZSetOperations.TypedTuple<String> tuple : tuples) {
        HotSearchVO vo = new HotSearchVO();
        vo.setRank(rank++);
        vo.setKeyword(tuple.getValue());
        vo.setScore(tuple.getScore());
        vo.setHot(calculateHotLevel(tuple.getScore())); // 热/爆/沸
        result.add(vo);
    }
    return result;
}

// 热度等级
private String calculateHotLevel(double score) {
    if (score > 100000) return "沸";
    if (score > 50000) return "爆";
    if (score > 10000) return "热";
    return "";
}
```

#### 11.2.5 热搜数据维护

```java
// XXL-Job: 每5分钟快照热搜到MySQL（历史分析用）
@XxlJob("hotSearchSnapshotJob")
public void hotSearchSnapshot() {
    List<HotSearchVO> hotList = getHotSearchList(100);
    LocalDateTime now = LocalDateTime.now();
    
    List<HotSearchSnapshot> snapshots = hotList.stream()
        .map(vo -> new HotSearchSnapshot(vo.getKeyword(), vo.getScore(), now))
        .collect(Collectors.toList());
    
    hotSearchSnapshotMapper.batchInsert(snapshots);
}

// XXL-Job: 每小时清理低热度词（防止ZSet无限膨胀）
@XxlJob("hotSearchCleanJob")
public void hotSearchClean() {
    // 删除热度分<100的词
    redisTemplate.opsForZSet().removeRangeByScore("hot:search:realtime", 0, 100);
    
    // 删除24小时未更新的词
    Map<Object, Object> lastUpdate = redisTemplate.opsForHash().entries("hot:search:lastUpdate");
    long threshold = System.currentTimeMillis() - 24 * 60 * 60 * 1000;
    
    for (Map.Entry<Object, Object> entry : lastUpdate.entrySet()) {
        if ((Long) entry.getValue() < threshold) {
            redisTemplate.opsForZSet().remove("hot:search:realtime", entry.getKey());
            redisTemplate.opsForHash().delete("hot:search:lastUpdate", entry.getKey());
        }
    }
}
```

### 11.3 索引设计

```json
// 商品索引
{
  "mappings": {
    "properties": {
      "spuId":       { "type": "long" },
      "spuName":     { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
      "subTitle":    { "type": "text", "analyzer": "ik_max_word" },
      "categoryId":  { "type": "long" },
      "brandId":     { "type": "long" },
      "price":       { "type": "double" },
      "saleCount":   { "type": "long" },
      "createTime":  { "type": "date" },
      "suggest":     { "type": "completion" }
    }
  }
}

// 笔记索引
{
  "mappings": {
    "properties": {
      "noteId":      { "type": "long" },
      "title":       { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
      "content":     { "type": "text", "analyzer": "ik_max_word" },
      "tags":        { "type": "keyword" },
      "likeCount":   { "type": "long" },
      "createTime":  { "type": "date" },
      "suggest":     { "type": "completion" }
    }
  }
}
```

### 11.3 容量与故障预案

| 项目 | 说明 |
|------|------|
| 容量估算 | 100万SPU + 5000万笔记索引；搜索QPS 2000，ES单节点可承载 |
| 数据生命周期 | 搜索日志7天热索引 → 30天温索引 → 90天删除 |
| 故障预案 | ES不可用 → 降级走DB LIKE查询(性能降级+限流)；增量同步失败 → 全量重建兜底 |

---

## 12. my-xhs-notification — 通知中心

> 服务命名与 `00-技术规格大纲.md` 对齐：`my-xhs-notification`（端口9013）
>
> **项目定位**：通知系统是一个完整的领域，技术深度足够独立成一个学习项目。当前在 my-xhs 中实现**精简版**，
> 覆盖核心技术点（MQ异步分发、SSE集群推送、通知聚合、Bitmap已读），架构上保证可扩展。
> 后续可独立深入：多端同步、通知偏好设置、推送策略A/B测试等。
>
> 📖 **知识来源**：《亿级流量系统架构设计与实战》第5章 — IM/通知系统设计
> - 核心观点："通知必须异步，不能阻塞主流程。点赞→发MQ→消费生成通知→聚合→推送"
> - 通知聚合："1万人赞你→1条通知'张三等9999人赞了你的笔记'，时间窗口内聚合"
> - SSE vs WebSocket："通知是单向推送，SSE基于HTTP足够；IM才需要WebSocket全双工"
> - my-xhs对照：SSE推送（非WebSocket）+MQ异步+聚合+Bitmap已读

### 12.1 核心功能

| 功能 | 实现 | 生产场景 |
|------|------|----------|
| 通知类型 | 点赞/收藏/关注/评论/系统通知 | 用户需要感知互动，否则体验断裂 |
| 异步分发 | 事件→RocketMQ→消费→写入收件箱 | 点赞事件不能同步写通知表，否则点赞接口变慢 |
| 实时推送 | SSE(Server-Sent Events) + Redis Pub/Sub集群广播 | 用户在线时实时收到通知；SSE比WebSocket更轻量，基于HTTP无需额外协议 |
| 通知聚合 | "100人赞了你的笔记"合并为1条 | 1万人点赞不能生成1万条通知，聚合减少存储和推送压力 |
| 已读未读 | Redis Bitmap(每个用户1个Bitmap，位索引=通知ID) | 1万个通知，Bitmap只占1.2KB，Set要80KB |
| 红点计数 | Redis Hash实时维护未读数 | App角标数字，实时性要求高 |

### 12.2 通知推送方案选型

> **SSE vs WebSocket 选型说明**

| 方案 | 优点 | 缺点 | my-xhs采用 |
|------|------|------|-------------|
| WebSocket (Netty) | 全双工、低延迟 | 需要额外Netty服务端、运维复杂、集群方案需要Redis Pub/Sub | ❌ 过度设计 |
| SSE (Server-Sent Events) | 基于HTTP、Spring原生支持、自动重连、运维简单 | 单向推送(服务端→客户端)、需要客户端定期发请求更新状态 | ✅ 通知场景只需服务端推送 |
| 短轮询 | 实现最简单 | 延迟高、浪费带宽 | ❌ 体验差 |

```
SSE集群方案：
客户端 ←SSE→ 实例A
客户端 ←SSE→ 实例B

问题：用户连着实例A，通知消费在实例B怎么办？
解决：实例B → Redis Pub/Sub发布 → 所有实例收到 → 各自推送给本地SSE连接的用户

为什么不选WebSocket：
- 通知是单向推送场景，不需要全双工通信
- SSE基于HTTP，Spring MVC原生支持，不需要额外引入Netty
- 运维简单：不需要管理WebSocket连接的生命周期
- 如果未来需要IM全双工通信，IM服务单独用WebSocket
```

### 12.3 通知聚合规则

> 📖 《亿级流量系统架构设计与实战》第6章 — 海量推送系统
>
> **通知聚合原理**（书中核心方案）：
> "1万人赞你，不能发1万条通知。时间窗口内同类通知聚合：
>  窗口5分钟内，同一笔记的点赞→合并为1条'张三等9999人赞了你的笔记'。
>  窗口过期后不再合并，开新窗口。这样1万次操作变成1-2条通知。"
>
> **聚合策略**：
> ```
> | 通知类型 | 是否聚合 | 原因 |
> |----------|----------|------|
> | 点赞 | ✅聚合 | 1万赞→1条，信息密度最高 |
> | 收藏 | ✅聚合 | 同上 |
> | 评论 | ❌不聚合 | 每条评论内容不同，用户要看具体内容 |
> | 关注 | ❌不聚合 | 每个关注者独立，用户要看是谁 |
> | @提及 | ❌不聚合 | 涉及不同场景 |
> ```
>
> **时间窗口算法**：
> ```
> 1. 收到点赞事件 → 查Redis是否有该笔记的聚合通知
> 2. 有 → 追加操作者到聚合列表 → 更新通知文案
> 3. 无 → 创建新通知 → 设置5分钟窗口( Redis Key TTL )
> 4. 窗口内后续点赞 → 追加到聚合列表
> 5. 窗口过期 → 聚合完成 → 新的点赞开新窗口
>
> Redis Key设计：
>   notify:aggregate:{userId}:{noteId}:{type} → ZSet(操作者ID, 时间)
>   TTL = 5分钟(窗口期)
> ```

```
单条：用户A赞了你的笔记《XXX》
聚合：用户A等3人赞了你的笔记《XXX》
大聚合：100人赞了你的笔记《XXX》

聚合条件：
- 同一笔记的点赞通知 → 合并（5分钟时间窗口）
- 同一笔记的收藏通知 → 合并（5分钟时间窗口）
- 关注通知 → 不合并（每个关注者独立）
- 评论通知 → 不合并（内容不同）
```

### 12.4 容量与故障预案

| 项目 | 说明 |
|------|------|
| 容量估算 | 100万DAU × 日均5条通知 = 500万条/天；Bitmap存储已读状态，1万通知/人仅1.2KB |
| 数据生命周期 | 热数据1个月(Redis+MySQL) → 温数据6个月(MySQL) → 冷数据1年(归档库) |
| 故障预案 | 推送服务挂 → 通知入库不推送，服务恢复后补推；SSE断开 → 客户端自动重连 |
| 降级策略 | Redis不可用 → 通知写入MySQL，红点计数走DB(性能降级) |

---

## 13. my-xhs-counter — 计数服务

> **领域定位**：计数是社交平台的基础能力，高频写+最终一致性是核心挑战。
>
> 📖 **知识来源**：《亿级流量系统架构设计与实战》第3章 — 微博计数器方案
> - 核心观点："写请求先落Redis，本地缓冲区攒批合并，定时批量持久化。相同Key增减合并（+1, -1, +1 = +1），DB写入次数降100倍"
> - 书中方案：Redis INCR → 本地缓冲 → 定时合并 → 批量INSERT
> - my-xhs对照：完全对齐，Buffer-Trigger攒批+合并+批量写+对账修复

### 13.1 核心功能

| 功能 | 实现 | 生产场景 |
|------|------|----------|
| 计数类型 | 点赞/收藏/评论/分享/浏览 | 小红书笔记的各种互动计数 |
| 存储方案 | Redis Hash(noteId→likeCount/collectCount/...) | 计数读写频繁，DB扛不住 |
| 批量写DB | Buffer-Trigger攒批100条写1次 | 每秒几万次计数变更变成每秒几次批量写入 |
| 对账修复 | XXL-Job每天凌晨Redis↔DB全量对账 | Buffer-Trigger可能丢数据，对账修复 |
| 计数查询 | Redis直接读 | 实时性要求高，直接Redis读 |

### 13.2 Buffer-Trigger原理

> 📖 《亿级流量系统架构设计与实战》第8章 — 计数器设计
>
> 书中的微博计数器架构：
> ```
> 点赞 → Redis INCR(实时扣减) → 本地缓冲区 → 攒批合并 → 批量INSERT → 对账修复
> ```
> 
> **关键洞察1——合并策略**：
> "同一Key的多次增减可以合并。100次+1和50次-1，合并后只需1次+50。
>  原本100次DB写入合并为1次，DB压力降100倍"
> 
> **关键洞察2——批量写入SQL**：
> "用 INSERT ... ON DUPLICATE KEY UPDATE 而非 UPDATE。
>  因为计数记录可能不存在（首次点赞），UPDATE会忽略，INSERT保证写入"
> 
> **关键洞察3——对账修复**：
> "Buffer在内存中，服务重启会丢数据。必须定时对账：Redis值 vs DB值，
>  差异超过阈值自动修复，并记录修复日志供人工审计"

```
什么是Buffer-Trigger？
- 攒批写入工具：数据先放内存Buffer，达到阈值(数量/时间)后批量写DB
- 解决问题：高频写操作(点赞/计数变更)逐条写DB扛不住

流程（对照书中方案）：
1. 点赞 → Redis INCR扣减(实时) + Buffer-Trigger记录(内存)
2. Buffer满(100条) 或 超时(5秒) → 合并同Key增减 → 批量写DB
3. 批量写SQL：INSERT INTO t_counter(biz_type, biz_id, count) VALUES(?, ?, ?) 
              ON DUPLICATE KEY UPDATE count = count + VALUES(count)
4. 写入失败 → 重试3次 → 仍失败记录失败日志(XXL-Job对账修复)

合并策略示例：
  笔记123的计数：+1(点赞), +1(点赞), -1(取消赞), +1(点赞) → 合并为+2 → 1次DB写入
  原4次DB写入 → 合并后1次，减少75%

生产注意事项：
- Buffer在内存中，服务重启会丢失 → 对账修复兜底
- 不适合强一致性场景(库存不用Buffer-Trigger)
- 对账发现差异后自动修复，并记录修复日志供人工审计
```

### 13.3 容量与故障预案

| 项目 | 说明 |
|------|------|
| 容量估算 | 5000万笔记 × 5种计数 = 2.5亿计数Key；Redis内存约5GB |
| 数据生命周期 | 计数实时存Redis；MySQL流水表每天清理，只保留聚合值 |
| 故障预案 | Redis不可用 → 计数不可用，业务降级不展示计数；对账不一致 → 自动修复+告警 |

---

## 14. my-xhs-home — 首页聚合服务

> 聚合服务，无独立数据库，调用其他服务组合首页数据
>
> 📖 **知识来源**：慕课网「Java+大数据+AI架构师实战营」推荐系统章节
> - 核心观点："推荐系统分召回→粗排→精排→重排四层，召回决定候选集，排序决定最终展示"
> - 多路召回："协同过滤+内容+热门+关注+地理，多路召回保证召回率，去重合并保证效率"
> - my-xhs对照：5路召回+简单粗排+预留精排接口

### 14.1 核心功能

| 功能 | 实现 | 生产场景 |
|------|------|----------|
| 首页Feed流 | 调用Social获取关注Feed + 调用Note获取推荐笔记 | 首页是用户打开APP的第一个页面，QPS最高 |
| 首页聚合 | 并行调用多个服务 → 聚合排序 → 返回 | 首页数据来源多，串行调用RT叠加，必须并行 |
| 缓存策略 | 整体缓存5分钟 + 热点笔记本地缓存 | 首页QPS 1万+，不缓存DB扛不住 |
| 个性化推荐 | 基于用户标签+行为的多路召回 | 冷启动用热门内容，有行为后用协同过滤+内容召回 |

### 14.2 推荐系统架构（召回层）

> **面试必问**："小红书的推荐是怎么做的？"

```
┌─────────────────────────────────────────────────────────────────┐
│                        推荐系统四层架构                           │
├─────────────────────────────────────────────────────────────────┤
│  召回层(Recall)    →  粗排(Pre-Rank)  →  精排(Rank)  →  重排(Re-Rank)  │
│  候选集1万+           候选集500          候选集50        最终展示20    │
│  多路召回+合并         简单规则           ML模型(预留)     多样性+去重   │
└─────────────────────────────────────────────────────────────────┘
```

#### 14.2.1 多路召回策略

| 召回路 | 实现 | 召回逻辑 | 召回量 |
|--------|------|----------|--------|
| **协同过滤** | Redis存用户行为矩阵 | 相似用户喜欢的笔记 → 你可能也喜欢 | 200 |
| **内容召回** | ES向量检索 | 用户历史浏览笔记的标签/分类 → 相似内容 | 200 |
| **热门召回** | Redis ZSet热度榜 | 全站/分类热门TopN | 100 |
| **关注召回** | Social服务Feed流 | 关注作者的最新笔记 | 200 |
| **地理召回** | ES geo_distance | 同城/附近的笔记（LBS场景） | 100 |

```java
// 多路召回并行执行
public List<NoteVO> multiRecall(Long userId, RecallRequest request) {
    CompletableFuture<List<Long>> cfRecall = CompletableFuture.supplyAsync(
        () -> collaborativeFilteringRecall(userId, 200));
    CompletableFuture<List<Long>> contentRecall = CompletableFuture.supplyAsync(
        () -> contentBasedRecall(userId, 200));
    CompletableFuture<List<Long>> hotRecall = CompletableFuture.supplyAsync(
        () -> hotRecall(request.getCategoryId(), 100));
    CompletableFuture<List<Long>> followRecall = CompletableFuture.supplyAsync(
        () -> followRecall(userId, 200));
    CompletableFuture<List<Long>> geoRecall = CompletableFuture.supplyAsync(
        () -> geoRecall(request.getLat(), request.getLng(), 100));
    
    // 合并去重
    Set<Long> merged = new LinkedHashSet<>();
    merged.addAll(cfRecall.join());
    merged.addAll(contentRecall.join());
    merged.addAll(hotRecall.join());
    merged.addAll(followRecall.join());
    merged.addAll(geoRecall.join());
    
    return merged.stream().limit(1000).collect(Collectors.toList());
}
```

#### 14.2.2 协同过滤召回实现

```
用户行为矩阵（Redis Hash）：
  user:behavior:{userId} → {noteId1: 3, noteId2: 1, noteId3: 5}
  分数 = 浏览1分 + 点赞2分 + 收藏3分 + 评论2分

相似用户计算（离线 XXL-Job）：
  1. 遍历用户行为矩阵
  2. 计算用户间余弦相似度
  3. 存储相似用户 user:similar:{userId} → ZSet(相似用户ID, 相似度)

召回逻辑：
  1. 获取相似用户Top20
  2. 获取相似用户高分笔记
  3. 过滤用户已看过的
  4. 返回召回结果
```

#### 14.2.3 粗排与精排

```
粗排（简单规则）：
  score = 热度分 × 0.3 + 新鲜度分 × 0.3 + 相关度分 × 0.4
  
  热度分 = log(点赞数 + 收藏数×2 + 评论数×3)
  新鲜度分 = 1 / (1 + 发布距今小时数/24)
  相关度分 = 用户标签与笔记标签的交集数 / 用户标签总数

精排（预留ML模型接口）：
  // 当前用简单规则，后续接入ML模型
  public interface RankingService {
      List<NoteVO> rank(Long userId, List<NoteVO> candidates);
  }
  
  @Service
  public class SimpleRankingService implements RankingService {
      // 简单规则实现
  }
  
  // 后续可替换为：
  @Service
  public class MLRankingService implements RankingService {
      // 调用TensorFlow Serving / ONNX模型
  }

重排（多样性+去重）：
  1. 相同作者的笔记不能连续出现
  2. 相同分类的笔记间隔>=3
  3. 已曝光笔记降权（Redis Bitmap记录曝光）
```

### 14.3 首页数据流

```
用户打开首页 →
  1. 召回层（并行5路召回）
     ├── 协同过滤召回(200)
     ├── 内容召回(200)
     ├── 热门召回(100)
     ├── 关注召回(200)
     └── 地理召回(100)
  
  2. 合并去重 → 候选集800+
  
  3. 粗排（热度+新鲜度+相关度）→ 候选集200
  
  4. 精排（当前简单规则，预留ML接口）→ 候选集50
  
  5. 重排（多样性+去重）→ 最终展示20
  
  6. 缓存5分钟 → 返回
```

### 14.4 冷启动策略

| 用户类型 | 行为数据 | 召回策略 |
|----------|----------|----------|
| 新用户(无行为) | 无 | 热门召回100% + 地理召回 |
| 低活用户(行为<10) | 少量 | 热门60% + 内容30% + 关注10% |
| 活跃用户(行为>100) | 充足 | 协同过滤40% + 内容30% + 热门20% + 关注10% |

### 14.5 容量与故障预案

| 项目 | 说明 |
|------|------|
| 容量估算 | 首页QPS 1万，缓存命中率>90%后下游服务QPS < 1000 |
| 故障预案 | Social不可用 → Feed流降级为纯推荐；Note不可用 → 返回缓存数据 |
| 降级策略 | 所有下游不可用 → 返回静态热门内容(缓存在Redis) |
| 召回降级 | 协同过滤服务不可用 → 降级为热门+内容召回 | |

---

## 15. my-xhs-im — 即时通讯服务

> **项目定位**：IM系统是一个独立的技术领域，复杂度足以单独做一个项目（微信/QQ本质就是IM系统）。
> 当前在 my-xhs 中实现**精简版私信**（一对一文字聊天），覆盖核心技术点（WebSocket+Netty、离线消息、
> 会话管理、未读计数），架构上保证可扩展。后续可独立深入：群聊、已读回执、消息漫游、音视频等。

### 15.1 核心功能

| 功能 | 实现 | 生产场景 | 版本 |
|------|------|----------|------|
| 私信发送 | WebSocket(Netty) + MQ异步落库 | 私信需要实时送达，WebSocket全双工通信 | 精简版✅ |
| 私信接收 | WebSocket推送 + 离线消息拉取 | 在线实时推，离线后上线拉取未读消息 | 精简版✅ |
| 会话列表 | Redis Zset(score=最后消息时间) | 会话列表按最近消息排序 | 精简版✅ |
| 未读计数 | Redis Hash(conversationId→unreadCount) | 角标显示未读数 | 精简版✅ |
| 消息存储 | MySQL分表(按发送者ID) | 聊天记录量大，需要分表 | 精简版✅ |
| 群聊 | chat_type字段预留 | 架构上预留，精简版不实现 | 后续扩展⏳ |
| 已读回执 | — | 消息已读状态同步 | 后续扩展⏳ |
| 消息漫游 | — | 历史消息按需拉取 | 后续扩展⏳ |
| 音视频通话 | — | 需要RTC基础设施 | 后续扩展⏳ |

### 15.2 IM与通知推送的技术差异

```
为什么IM用WebSocket而通知用SSE？
- IM需要双向通信：发送消息 + 接收消息，SSE只能服务端推送
- 通知只需单向推送：服务端→客户端，SSE足够
- IM的WebSocket连接需要维护消息ACK机制，比通知复杂得多
- 两个服务独立部署，互不影响
```

### 15.3 容量与故障预案

| 项目 | 说明 |
|------|------|
| 容量估算 | 50亿聊天记录(按月分表)；10万并发WebSocket连接 |
| 数据生命周期 | 热数据3个月(MySQL) → 温数据1年(归档) → 冷数据3年(对象存储) |
| 故障预案 | WebSocket断开 → 客户端自动重连 + 拉取离线消息；Netty不可用 → 消息写入MySQL，恢复后推送 |

---

## 17. 服务间依赖与调用关系

```
Gateway → User(鉴权)
       → Home(首页聚合) → Social(Feed流) + Note(热门/推荐) + Counter(计数)
       → Note(笔记) → Counter(计数) + Social(点赞/收藏)
       → Social(关注/Feed)
       → Product(商品) → Inventory(库存) + Search(搜索)
       → Cart(购物车) → Product(商品信息)
       → Order(下单) → Inventory(扣库存) + Coupon(扣券) + User(地址)
       → Search(搜索) → Product(索引) + Note(索引)
       → Push(通知) → Counter(计数)
       → IM(私信)
```

### 关键调用链路

| 场景 | 调用链 | RT目标 |
|------|--------|--------|
| 首页浏览 | Gateway→Home→(Social+Note+Counter并行) | < 200ms |
| 笔记详情 | Gateway→Note→(Counter+Social并行) | < 100ms |
| 下单 | Gateway→Order→(Inventory+Coupon+User并行)→MQ异步 | P99 < 500ms |
| 搜索 | Gateway→Search→ES | < 200ms |
| 点赞 | Gateway→Social→Redis→MQ→(Counter+Push) | < 50ms(同步部分) |
