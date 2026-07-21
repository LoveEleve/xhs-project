# 安全合规体系

> 所属维度：安全合规 | 开发阶段：Phase-6 | 涉及服务：Gateway + 全部业务服务

---

## 🎯 一、安全体系总览

### 1.1 接口安全

| 安全项 | 实现方案 | 生产场景 |
|--------|----------|----------|
| 请求签名 | HMAC-SHA256(timestamp+nonce+body+secretKey) | 防篡改+防重放，Gateway GlobalFilter校验 |
| 认证鉴权 | JWT双Token(Access 30min + Refresh 7d) | 未登录请求拦截在Gateway，不打到业务服务 |
| 接口限频 | @RateLimit + Sentinel | 防暴力破解、防恶意刷接口 |
| CORS跨域 | Gateway CorsFilter | 前端跨域，限制允许的域名 |
| 防重放攻击 | timestamp 5分钟过期 + nonce Redis去重 | 同一请求不能重复提交 |

### 1.2 数据安全

| 安全项 | 实现方案 | 生产场景 |
|--------|----------|----------|
| 密码存储 | BCrypt慢哈希 | 防彩虹表破解，即使DB泄露也无法还原明文 |
| 敏感信息脱敏 | 返回DTO中手机号/邮箱打码 | 前端展示脱敏，防信息泄露，合规要求 |
| SQL注入防护 | MyBatis-Plus参数化查询 + 代码Review | 所有SQL必须参数化，禁止拼接用户输入 |
| XSS防护 | 前端输入转义 + 后端过滤HTML标签 | 笔记内容/评论中不能注入脚本 |
| CSRF防护 | JWT Token + SameSite Cookie | 防跨站请求伪造 |
| 文件上传安全 | 文件类型白名单 + 大小限制(5MB) | 防上传恶意脚本/超大文件 |

### 1.3 管理后台安全

| 安全项 | 实现方案 | 生产场景 |
|--------|----------|----------|
| 网络隔离 | Admin服务只在内网暴露 | 外网无法直接访问管理后台 |
| RBAC权限 | 角色→菜单权限映射 | 不同角色看到不同功能，防止越权 |
| 操作审计 | 所有管理操作记录审计日志 | 操作可追溯，出问题可定位到人 |
| 二次确认 | 删除/下架等敏感操作需二次确认 | 防误操作 |

---

## 🏗️ 二、核心实现

### 2.1 HMAC签名流程

```
客户端：
1. 生成timestamp(当前时间戳)和nonce(随机字符串)
2. 拼接签名串：timestamp + nonce + body + secretKey
3. 计算签名：HMAC-SHA256(签名串)
4. 请求Header携带：X-Timestamp, X-Nonce, X-Signature

Gateway校验：
1. 检查timestamp是否在5分钟内 → 超时拒绝(防重放)
2. 检查nonce是否在Redis中存在 → 存在拒绝(防重放)
3. 用相同规则计算签名 → 比对是否一致(防篡改)
4. 校验通过 → nonce写入Redis(TTL=5分钟)
```

### 2.2 HMAC 签名 Gateway Filter 完整实现

```java
/**
 * HMAC-SHA256 签名校验过滤器（Gateway WebFlux）
 * 职责：防篡改 + 防重放
 * 
 * 请求头要求：
 * - X-Timestamp: 当前时间戳（毫秒）
 * - X-Nonce: 随机字符串（UUID）
 * - X-Signature: HMAC-SHA256(timestamp + nonce + body + secretKey)
 */
@Component
public class SignatureGlobalFilter implements GlobalFilter, Ordered {

    @Value("${security.hmac.secret-key}")
    private String secretKey;

    @Value("${security.hmac.expire-seconds:300}") // 5分钟
    private long expireSeconds;

    private final ReactiveStringRedisTemplate redisTemplate;

    /** 签名白名单路径（健康检查、公开接口等不需要签名） */
    private static final Set<String> SIGNATURE_WHITELIST = Set.of(
            "/actuator/health",
            "/api/user/login",
            "/api/user/register",
            "/api/user/refreshToken"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 白名单路径不校验签名（如健康检查、公开接口）
        if (isSignatureWhiteListed(path)) {
            return chain.filter(exchange);
        }

        // 1. 提取签名参数
        String timestamp = request.getHeaders().getFirst("X-Timestamp");
        String nonce = request.getHeaders().getFirst("X-Nonce");
        String signature = request.getHeaders().getFirst("X-Signature");

        if (StringUtils.isAnyBlank(timestamp, nonce, signature)) {
            return unauthorized(exchange, "缺少签名参数");
        }

        // 2. 校验时间戳（防重放：5分钟内有效）
        long requestTime = Long.parseLong(timestamp);
        long currentTime = System.currentTimeMillis();
        if (Math.abs(currentTime - requestTime) > expireSeconds * 1000) {
            return unauthorized(exchange, "请求已过期");
        }

        // 3. 校验 nonce 唯一性（防重放：同一 nonce 不能重复使用）
        String nonceKey = "security:nonce:" + nonce;
        return redisTemplate.opsForValue()
                .setIfAbsent(nonceKey, "1", Duration.ofSeconds(expireSeconds))
                .flatMap(success -> {
                    if (!Boolean.TRUE.equals(success)) {
                        return unauthorized(exchange, "请求重复");
                    }

                    // 4. 读取请求体并校验签名
                    return DataBufferUtils.join(request.getBody())
                            .defaultIfEmpty(DefaultDataBufferFactory.sharedInstance.allocateBuffer(0))
                            .flatMap(dataBuffer -> {
                                byte[] bodyBytes = new byte[dataBuffer.readableByteCount()];
                                dataBuffer.read(bodyBytes);
                                DataBufferUtils.release(dataBuffer);
                                String body = new String(bodyBytes, StandardCharsets.UTF_8);

                                // 5. 计算签名并比对
                                String signStr = timestamp + nonce + body + secretKey;
                                String expectedSignature = hmacSha256(signStr);
                                if (!signature.equals(expectedSignature)) {
                                    return unauthorized(exchange, "签名校验失败");
                                }

                                // 6. 重新包装请求体（因为 body 已被消费）
                                ServerHttpRequest newRequest = request.mutate().build();
                                DataBuffer newBuffer = DefaultDataBufferFactory.sharedInstance
                                        .wrap(bodyBytes);
                                ServerHttpRequest decoratedRequest = new ServerHttpRequestDecorator(newRequest) {
                                    @Override
                                    public Flux<DataBuffer> getBody() {
                                        return Flux.just(newBuffer);
                                    }
                                };

                                return chain.filter(exchange.mutate()
                                        .request(decoratedRequest).build());
                            });
                });
    }

    /**
     * HMAC-SHA256 签名计算
     */
    private String hmacSha256(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Hex.encodeHexString(hash); // Apache Commons Codec
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 计算失败", e);
        }
    }

    /**
     * 判断路径是否在签名白名单中
     */
    private boolean isSignatureWhiteListed(String path) {
        return SIGNATURE_WHITELIST.stream().anyMatch(path::startsWith);
    }

    /**
     * 返回 401 未授权响应
     */
    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":401,\"msg\":\"" + message + "\"}";
        DataBuffer buffer = response.bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -900; // 在鉴权之前执行
    }
}
```

### 2.3 JWT双Token机制

```
登录成功 → 签发AccessToken(30min) + RefreshToken(7d)
          ↓
正常请求 → Header携带AccessToken → Gateway解析+校验
          ↓
AccessToken过期 → 用RefreshToken换新Token → 旧RefreshToken失效
          ↓
RefreshToken过期 → 重新登录
          ↓
修改密码 → 旧Token的jti加入Redis黑名单 → 强制重新登录
```

### 2.4 XSS 防护过滤器

```java
/**
 * XSS 防护过滤器
 * 对请求参数和请求体中的 HTML 标签进行转义
 * 防止用户在笔记内容/评论中注入恶意脚本
 */
@Component
@WebFilter(urlPatterns = "/*")
public class XssFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
        chain.doFilter(new XssHttpServletRequestWrapper(
                (HttpServletRequest) request), response);
    }
}

/**
 * XSS 请求包装器
 * 重写 getParameter / getParameterValues / getHeader 方法
 * 对所有输入进行 HTML 转义
 */
public class XssHttpServletRequestWrapper extends HttpServletRequestWrapper {

    public XssHttpServletRequestWrapper(HttpServletRequest request) {
        super(request);
    }

    @Override
    public String getParameter(String name) {
        String value = super.getParameter(name);
        return value != null ? cleanXss(value) : null;
    }

    @Override
    public String[] getParameterValues(String name) {
        String[] values = super.getParameterValues(name);
        if (values == null) return null;
        return Arrays.stream(values)
                .map(this::cleanXss)
                .toArray(String[]::new);
    }

    @Override
    public String getHeader(String name) {
        String value = super.getHeader(name);
        return value != null ? cleanXss(value) : null;
    }

    /**
     * XSS 清洗：转义 HTML 特殊字符
     * < → &lt;  > → &gt;  " → &quot;  ' → &#x27;  & → &amp;
     * 同时移除 <script>、<iframe>、onerror= 等危险标签/属性
     */
    private String cleanXss(String value) {
        if (StringUtils.isBlank(value)) return value;
        // 1. 移除危险标签
        value = value.replaceAll("(?i)<script[^>]*>.*?</script>", "");
        value = value.replaceAll("(?i)<iframe[^>]*>.*?</iframe>", "");
        // 2. 移除事件属性
        value = value.replaceAll("(?i)\\s+on\\w+\\s*=\\s*['\"]?[^'\"]*['\"]?", "");
        // 3. HTML 实体转义
        value = value.replace("&", "&amp;");
        value = value.replace("<", "&lt;");
        value = value.replace(">", "&gt;");
        value = value.replace("\"", "&quot;");
        value = value.replace("'", "&#x27;");
        return value;
    }
}
```

### 2.5 敏感信息脱敏（注解方式）

```java
/**
 * 脱敏注解 — 标注在 VO 字段上，序列化时自动脱敏
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@JacksonAnnotationsInside
@JsonSerialize(using = DesensitizeSerializer.class)
public @interface Desensitize {
    DesensitizeType type();
}

/**
 * 脱敏类型枚举
 */
public enum DesensitizeType {
    PHONE,    // 138****8000
    EMAIL,    // t***@gmail.com
    ID_CARD,  // 440***********1234
    BANK_CARD // 6222 **** **** 1234
}

/**
 * Jackson 自定义序列化器 — 自动脱敏
 */
public class DesensitizeSerializer extends JsonSerializer<String>
        implements ContextualSerializer {

    private DesensitizeType type;

    @Override
    public void serialize(String value, JsonGenerator gen,
                          SerializerProvider provider) throws IOException {
        if (StringUtils.isBlank(value)) {
            gen.writeString(value);
            return;
        }
        switch (type) {
            case PHONE:
                gen.writeString(value.substring(0, 3) + "****" + value.substring(7));
                break;
            case EMAIL:
                int atIndex = value.indexOf('@');
                gen.writeString(value.charAt(0) + "***" + value.substring(atIndex));
                break;
            case ID_CARD:
                gen.writeString(value.substring(0, 3) + "***********"
                        + value.substring(value.length() - 4));
                break;
            case BANK_CARD:
                gen.writeString(value.substring(0, 4) + " **** **** "
                        + value.substring(value.length() - 4));
                break;
            default:
                gen.writeString(value);
        }
    }

    @Override
    public JsonSerializer<?> createContextual(SerializerProvider prov,
                                               BeanProperty property) {
        Desensitize annotation = property.getAnnotation(Desensitize.class);
        if (annotation != null) {
            DesensitizeSerializer serializer = new DesensitizeSerializer();
            serializer.type = annotation.type();
            return serializer;
        }
        return this;
    }
}

/**
 * 使用示例 — UserVO
 */
public class UserVO {
    private Long id;
    private String nickname;

    @Desensitize(type = DesensitizeType.PHONE)
    private String phone;    // 返回 138****8000

    @Desensitize(type = DesensitizeType.EMAIL)
    private String email;    // 返回 t***@gmail.com
}
```

### 2.6 操作审计日志（AOP 实现）

```java
/**
 * 审计日志注解
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {
    String module();       // 模块名
    String operation();    // 操作描述
}

/**
 * 审计日志切面 — 记录管理后台所有敏感操作
 */
@Aspect
@Component
public class AuditLogAspect {

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint joinPoint, AuditLog auditLog) throws Throwable {
        long startTime = System.currentTimeMillis();
        String operator = SecurityContextHolder.getContext().getUsername();
        String params = JSON.toJSONString(joinPoint.getArgs());

        // 使用 AtomicReference 包装，确保 lambda 中可捕获（effectively final）
        Object result;
        AtomicBoolean success = new AtomicBoolean(true);
        AtomicReference<String> errorMsg = new AtomicReference<>(null);
        try {
            result = joinPoint.proceed();
        } catch (Exception e) {
            success.set(false);
            errorMsg.set(e.getMessage());
            throw e;
        } finally {
            // 异步记录审计日志（不影响主流程性能）
            long costMs = System.currentTimeMillis() - startTime;
            CompletableFuture.runAsync(() -> {
                AuditLogEntity log = new AuditLogEntity();
                log.setModule(auditLog.module());
                log.setOperation(auditLog.operation());
                log.setOperator(operator);
                log.setParams(params);
                log.setSuccess(success.get());
                log.setErrorMsg(errorMsg.get());
                log.setCostMs(costMs);
                log.setIp(RequestContextHolder.getRequestIp());
                log.setCreatedAt(LocalDateTime.now());
                auditLogMapper.insert(log);
            });
        }
        return result;
    }
}

// 使用示例
@AuditLog(module = "笔记管理", operation = "下架笔记")
public void offlineNote(Long noteId) {
    // 管理员下架笔记
}
```

---

## 📋 三、合规要求

| 合规项 | 说明 | my-xhs落地 |
|--------|------|-------------|
| 内容审核 | UGC内容必须审核后才能发布 | 笔记状态机(草稿→待审核→已发布) |
| 敏感词过滤 | 10万词库DFA过滤 | 评论/笔记内容发布前过滤 |
| 隐私保护 | 用户数据最小化收集、脱敏展示 | 手机号/邮箱脱敏，地址打码 |
| 数据留存 | 按法规要求留存日志和订单 | 订单3年、日志90天 |

---

## ⚖️ 四、方案对比

### 4.1 签名方案对比

| 维度 | HMAC-SHA256 | RSA签名 | AES加密 |
|------|------------|---------|---------|
| 性能 | 高(对称) | 低(非对称) | 高(对称) |
| 安全性 | 高(防篡改) | 最高(不可否认) | 中(仅加密) |
| 复杂度 | 低 | 高(证书管理) | 低 |
| 适用场景 | API接口签名 | 支付/金融 | 数据传输加密 |

**最终选择**：HMAC-SHA256 — 性能好、安全性够用、实现简单，微信/支付宝开放平台标配。

---

## 🐛 五、踩坑记录

### 5.1 {待开发时填写}

- **现象**：
- **原因**：
- **解决**：
- **教训**：

---

## 🎤 六、面试考察点

### Q1: 接口安全怎么保证的？

**推荐回答思路**：

> 1. "四层防护：HMAC签名防篡改+防重放、JWT鉴权、@RateLimit防刷、CORS限制域名"
> 2. "签名用HMAC-SHA256，timestamp+nonce+body+secretKey，Gateway GlobalFilter统一校验"
> 3. "防重放：timestamp 5分钟过期 + nonce Redis去重"

### Q2: 密码怎么存储的？为什么用BCrypt？

**推荐回答思路**：

> 1. "BCrypt慢哈希，加盐+多轮计算，即使DB泄露也无法还原明文"
> 2. "vs MD5/SHA256：太快了，每秒能算数十亿次，暴力破解成本极低"
> 3. "BCrypt每秒只能算几千次，暴力破解成本极高"

---

## 📚 参考资料

| 资料 | 相关章节 | 参考内容 |
|------|----------|----------|
| 📄 04-基础设施与部署.md | §11 | 安全合规完整方案 |
| 📄 02-模块详细设计.md | §2.2 | Gateway安全能力 |
| 📄 02-模块详细设计.md | §1.2 | @RateLimit注解实现 |
