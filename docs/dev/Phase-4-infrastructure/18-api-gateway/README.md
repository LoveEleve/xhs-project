# API 网关

> 所属服务：my-xhs-gateway (9000) | 开发阶段：Phase-4 | 状态：⏳ 待开发

---

## 🎯 一、需求分析

### 1.1 业务场景

API 网关是所有外部请求的统一入口，基于 Spring Cloud Gateway（WebFlux + Netty）实现。Phase-4 在现有网关骨架基础上补齐 6 大核心能力：①JWT 统一鉴权（GlobalFilter 校验 Token，白名单放行）；②HMAC-SHA256 签名校验（防篡改 + 防重放）；③Sentinel 限流熔断（接口级 + 用户级 QPS 限制）；④灰度路由（Nacos 元数据标记灰度实例）；⑤全链路流量染色（6 个 Header 全链路透传）；⑥API 版本路由。

### 1.2 功能边界

| 功能项 | 是否实现 | 说明 |
|--------|---------|------|
| 路由转发 | ✅ | 15 个服务的路由规则，Nacos 动态路由 |
| JWT 鉴权 | ✅ | GlobalFilter 校验 Token + Redis 黑名单 |
| 白名单放行 | ✅ | Nacos 配置白名单路径，AntPathMatcher 匹配 |
| HMAC 签名校验 | ✅ | timestamp + nonce + HMAC-SHA256 防篡改防重放 |
| Sentinel 限流 | ✅ | 接口级 + 用户级 QPS 限制，Nacos 数据源 |
| Sentinel 熔断 | ✅ | 慢调用比例 + 异常比例熔断 |
| 灰度路由 | ✅ | X-Gray-Tag 请求头匹配 Nacos 元数据 |
| API 版本路由 | ✅ | X-Api-Version 请求头路由到不同版本实例 |
| 流量染色 | ✅ | 6 个 Header 注入 + 全链路透传 |
| WebSocket 路由 | ✅ | IM 服务 WebSocket 升级端点 |
| 全局异常处理 | ✅ | 统一错误响应格式 |
| 请求日志 | ✅ | 入站请求日志 + TraceId 注入 |
| CORS 跨域 | ✅ | 全局跨域配置 |

### 1.3 数据量预估

| 指标 | 预估值 | 计算依据 |
|------|--------|----------|
| 网关 QPS | 50000 | 所有请求的入口 |
| 路由规则数 | 15 条 | 15 个微服务 |
| 白名单路径 | 20+ | 登录/注册/公开详情等 |
| 限流规则 | 30+ | 按接口 + 用户维度 |

---

## 🏗️ 二、架构设计

### 2.1 整体架构图

```
Client → my-xhs-gateway(9000) [Spring Cloud Gateway]
              │
              ├── GlobalFilter Chain（按 Order 顺序执行）：
              │     ├── RequestLogFilter (Order=-100)     → 请求日志 + TraceId
              │     ├── AuthGlobalFilter (Order=-50)      → JWT鉴权 + 白名单
              │     ├── HmacSignatureFilter (Order=-40)   → HMAC签名校验
              │     ├── RateLimitFilter (Order=-30)       → Sentinel限流
              │     ├── GrayRouteFilter (Order=-20)       → 灰度路由
              │     └── ApiVersionFilter (Order=-10)      → API版本路由
              │
              ├── Nacos: 路由规则 + 白名单 + 限流规则
              ├── Redis: Token黑名单 + nonce去重 + 限流计数
              └── 路由转发 → 15个微服务
```

### 2.2 Filter 链处理流程

```
请求入站
    │
    ▼
┌─────────────────┐
│ RequestLogFilter │ → 记录请求日志，注入 X-Trace-Id
└────────┬────────┘
         ▼
┌─────────────────┐
│ 白名单匹配       │──── 是 ──▶ 跳过鉴权，直接路由
│ AntPathMatcher  │
└────────┬────────┘
         │ 否
         ▼
┌─────────────────┐
│ AuthGlobalFilter │ → 解析JWT → 校验签名 → 检查Redis黑名单
│                 │ → 提取userId → 放入Header X-User-Id
└────────┬────────┘
         ▼
┌─────────────────────┐
│ HmacSignatureFilter │ → 校验 X-Timestamp/X-Nonce/X-Signature
│                     │ → timestamp 5分钟内 + nonce Redis去重
└────────┬────────────┘
         ▼
┌─────────────────┐
│ RateLimitFilter │ → Sentinel 限流判断
└────────┬────────┘
         ▼
┌─────────────────┐
│ GrayRouteFilter │ → X-Gray-Tag 匹配灰度实例
└────────┬────────┘
         ▼
┌─────────────────┐
│ ApiVersionFilter│ → X-Api-Version 匹配版本实例
└────────┬────────┘
         ▼
┌─────────────────┐
│ 路由转发到目标服务│
└─────────────────┘
```

### 2.3 流量染色全链路透传

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

染色标记定义：
| 标记              | 说明                    | 用途                |
|-------------------|------------------------|---------------------|
| X-Trace-Id        | 全链路追踪ID            | 串联15个服务的日志    |
| X-User-Id         | 用户ID                  | 下游服务获取当前用户  |
| X-Gray-Tag        | 灰度标记(beta/stable)   | 灰度发布路由         |
| X-Api-Version     | API版本号(v1/v2)        | 多版本API路由        |
| X-AB-Group        | AB测试分组(A/B/C)       | 推荐策略/UI实验      |
| X-Pressure-Test   | 压测标记(true/false)    | 压测流量隔离写影子表  |
```

---

## 🗄️ 三、数据库设计

> 网关服务不拥有数据库。鉴权白名单、限流规则等通过 Nacos 配置中心管理。

---

## 🔑 四、缓存设计

### 4.1 Redis Key 规范

| Key Pattern | 类型 | 过期时间 | 说明 |
|-------------|------|----------|------|
| `user:token:blacklist:{userId}` | String | Access Token 剩余有效期 | Token 黑名单（登出/改密码时加入） |
| `gateway:nonce:{nonce}` | String | 5min | 请求防重放（nonce 唯一性校验） |
| `gateway:ratelimit:{api}:{userId}` | ZSet | 滑动窗口 | 接口限流计数 |

---

## 📡 五、接口设计

> 网关本身不暴露业务 API，所有接口由后端服务提供。网关的路由规则清单如下：

| 路由ID | 路径匹配 | 目标服务 | 鉴权 | 说明 |
|--------|---------|---------|------|------|
| user-service | /api/user/** | my-xhs-user | 部分白名单 | 登录/注册放行 |
| content-service | /api/note/**, /api/comment/** | my-xhs-content | ✅ | 笔记/评论 |
| analytics-service | /api/social/** | my-xhs-analytics | ✅ | 社交 |
| counter-service | /api/counter/** | my-xhs-counter | ❌ | 计数（公开） |
| product-service | /api/product/** | my-xhs-product | 部分白名单 | 商品详情放行 |
| cart-service | /api/cart/** | my-xhs-cart | ✅ | 购物车 |
| inventory-service | /api/inventory/** | my-xhs-inventory | ✅ | 库存 |
| coupon-service | /api/coupon/** | my-xhs-coupon | ✅ | 优惠券 |
| order-service | /api/order/** | my-xhs-order | ✅ | 订单 |
| payment-service | /api/payment/** | my-xhs-payment | ✅ | 支付 |
| search-service | /api/search/** | my-xhs-search | 部分白名单 | 搜索放行 |
| home-service | /api/home/** | my-xhs-home | 部分白名单 | 发现流放行 |
| notification-service | /api/notification/** | my-xhs-notification | ✅ | 通知 |
| im-service | /api/im/** | my-xhs-im | ✅ | IM（含 WebSocket） |
| recommend-service | /api/recommend/** | my-xhs-search | ✅ | 推荐 |

---

## 💻 六、核心代码实现

### 6.1 JWT 鉴权过滤器

```java
/**
 * JWT 鉴权 GlobalFilter
 * 1. 白名单路径放行
 * 2. 解析 JWT Token → 校验签名 → 检查 Redis 黑名单
 * 3. 提取 userId → 放入 Header X-User-Id
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    @Value("${gateway.auth.white-list}")
    private List<String> whiteList;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // 1. 白名单放行
        if (isWhiteListed(path)) {
            return chain.filter(exchange);
        }

        // 2. 提取 Token
        String token = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (StringUtils.isBlank(token) || !token.startsWith("Bearer ")) {
            return unauthorized(exchange, "未登录");
        }
        token = token.substring(7);

        // 3. 解析 JWT
        Claims claims;
        try {
            claims = JwtUtil.parseToken(token);
        } catch (Exception e) {
            return unauthorized(exchange, "Token无效");
        }

        // 4. 检查 Redis 黑名单（登出/改密码后 Token 失效）
        Long userId = claims.get("userId", Long.class);
        String blacklistKey = "user:token:blacklist:" + userId;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(blacklistKey))) {
            return unauthorized(exchange, "Token已失效");
        }

        // 5. 放入 Header 供下游服务使用
        ServerHttpRequest request = exchange.getRequest().mutate()
            .header("X-User-Id", String.valueOf(userId))
            .build();

        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() { return -50; }
}
```

### 6.2 HMAC 签名校验过滤器

```java
/**
 * HMAC-SHA256 签名校验
 * 防篡改：重新计算签名与请求头签名比对
 * 防重放：timestamp 5分钟内 + nonce Redis去重
 */
@Component
public class HmacSignatureFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String timestamp = request.getHeaders().getFirst("X-Timestamp");
        String nonce = request.getHeaders().getFirst("X-Nonce");
        String signature = request.getHeaders().getFirst("X-Signature");

        // 1. timestamp 5分钟内有效
        long requestTime = Long.parseLong(timestamp);
        if (Math.abs(System.currentTimeMillis() - requestTime) > 5 * 60 * 1000) {
            return forbidden(exchange, "请求已过期");
        }

        // 2. nonce 防重放（Redis SET NX 5min）
        String nonceKey = "gateway:nonce:" + nonce;
        Boolean isNew = redisTemplate.opsForValue()
            .setIfAbsent(nonceKey, "1", 5, TimeUnit.MINUTES);
        if (Boolean.FALSE.equals(isNew)) {
            return forbidden(exchange, "重复请求");
        }

        // 3. 重新计算 HMAC-SHA256 签名
        String method = request.getMethod().name();
        String path = request.getURI().getPath();
        String signStr = method + path + timestamp + nonce;
        String expectedSignature = HmacUtil.hmacSha256(signStr, secretKey);

        if (!expectedSignature.equals(signature)) {
            return forbidden(exchange, "签名校验失败");
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() { return -40; }
}
```

### 6.3 流量染色过滤器

```java
/**
 * 流量染色 GlobalFilter
 * 注入 6 个 Header，全链路透传
 */
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

        // 2. 灰度标记（根据用户ID/百分比决定）
        String grayTag = determineGrayTag(request);
        builder.header("X-Gray-Tag", grayTag);

        // 3. AB测试分组
        String abGroup = determineABGroup(request);
        builder.header("X-AB-Group", abGroup);

        // 4. 压测标记
        String pressureTest = request.getHeaders().getFirst("X-Pressure-Test");
        if ("true".equals(pressureTest)) {
            builder.header("X-Pressure-Test", "true");
        }

        return chain.filter(exchange.mutate().request(builder.build()).build());
    }

    @Override
    public int getOrder() { return -100; }
}
```

---

## ⚖️ 七、方案对比

### 7.1 限流算法：滑动窗口 vs 令牌桶 vs 漏桶

| 维度 | 滑动窗口（✅ 选定） | 令牌桶 | 漏桶 |
|------|-------------------|--------|------|
| 精确度 | 高（精确到毫秒） | 中 | 中 |
| 突发流量 | 允许窗口内突发 | 允许突发（桶内有令牌） | 不允许（匀速） |
| 实现复杂度 | 中（Redis ZSet） | 中 | 低 |
| 适用场景 | API 限流 | 消息队列消费 | 流量整形 |

**选择理由**：API 限流需要精确控制窗口内请求数，滑动窗口最适合。Redis ZSet 天然支持按时间戳排序和范围删除。

### 7.2 鉴权方案：网关统一鉴权 vs 各服务独立鉴权

| 维度 | 网关统一鉴权（✅ 选定） | 各服务独立鉴权 |
|------|----------------------|---------------|
| 维护成本 | 低（一处修改） | 高（15 个服务都要改） |
| 性能 | 好（不合法请求不打到后端） | 差（每个服务都要解析 Token） |
| 灵活性 | 中（白名单配置化） | 高（每个服务自定义） |
| 一致性 | 高 | 低（容易遗漏） |

---

## 🐛 八、踩坑记录

### 8.1 WebFlux 不能使用 WebMVC 的 JWT 工具

- **现象**：引入 spring-boot-starter-web 后 Gateway 启动报错
- **原因**：Gateway 基于 WebFlux（Netty），不能与 WebMVC（Tomcat）共存
- **解决**：Gateway 专用 JwtUtil，不依赖 HttpServletRequest

### 8.2 Sentinel 规则不生效

- **现象**：配置了限流规则但不生效
- **原因**：Gateway 需要使用 `sentinel-spring-cloud-gateway-adapter`，不是普通的 sentinel-web-servlet
- **解决**：引入 Gateway 专用适配器 + Nacos 数据源

### 8.3 灰度路由匹配不到实例

- **现象**：X-Gray-Tag=gray 但路由到了正常实例
- **原因**：Nacos 实例元数据未正确标记
- **解决**：灰度实例注册时在 Nacos 元数据中标记 `gray-tag=gray`

---

## 📊 九、测试验证

| 测试场景 | 输入 | 预期结果 | 通过 |
|----------|------|----------|------|
| 白名单放行 | GET /api/user/login | 不需要 Token 即可访问 | ⬜ |
| JWT 鉴权 | 无 Token 访问需鉴权接口 | 返回 401 | ⬜ |
| Token 黑名单 | 登出后使用旧 Token | 返回 401 | ⬜ |
| HMAC 签名 | 篡改请求体 | 返回 403 签名校验失败 | ⬜ |
| 防重放 | 重复 nonce | 返回 403 重复请求 | ⬜ |
| Sentinel 限流 | 超过 QPS 阈值 | 返回 429 | ⬜ |
| 灰度路由 | X-Gray-Tag=gray | 路由到灰度实例 | ⬜ |
| 流量染色 | 正常请求 | 下游服务收到 6 个 Header | ⬜ |

---

## 🎤 十、面试考察点

### Q1: 网关做了哪些事情？

> 1. "6 大核心能力：路由转发、JWT 鉴权、HMAC 签名校验、Sentinel 限流熔断、灰度路由、流量染色"
> 2. "GlobalFilter 链式处理，按 Order 顺序执行，职责单一"
> 3. "白名单机制：登录/注册等公开接口放行，Nacos 配置化管理"
> 4. "统一入口：不合法请求在网关层拦截，不打到后端服务"

### Q2: 全链路流量染色怎么实现的？

> 1. "网关层注入 6 个 Header：X-Trace-Id、X-User-Id、X-Gray-Tag、X-Api-Version、X-AB-Group、X-Pressure-Test"
> 2. "同步透传：Feign RequestInterceptor 自动传播 Header 到下游服务"
> 3. "异步透传：MQ 消息属性携带染色标记；线程池 TaskDecorator 继承 ThreadLocal"
> 4. "压测隔离：X-Pressure-Test=true 时写影子表，不污染生产数据"

### Q3: 限流算法用的什么？为什么？

> 1. "Redis ZSet 滑动窗口：ZADD 记录请求时间戳，ZREMRANGEBYSCORE 移除窗口外记录，ZCARD 统计窗口内数量"
> 2. "为什么不用令牌桶：令牌桶适合匀速消费场景（MQ），API 限流需要精确控制窗口内请求数"
> 3. "为什么不用固定窗口：固定窗口有临界突发问题（窗口切换瞬间 2 倍流量）"
> 4. "Lua 脚本保证原子性，一次 Redis 调用完成判断 + 记录"

---

## 📚 参考资料

| 资料 | 相关章节 | 参考内容 |
|------|----------|----------|
| 📄 phase-4/README.md | §3.18 | API网关增强完整设计 |
| 📄 02-module-detailed-design.md | §2 | 网关路由/安全/流量治理/染色 |
| 📖 《凤凰架构》 | 第4章 | 服务容错三板斧：限流/熔断/降级 |
