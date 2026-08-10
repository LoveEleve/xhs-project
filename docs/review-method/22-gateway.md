# 22 网关路由

> 复审维度 22 | 覆盖模块：gateway | 领域专属检查项
>
> 你是谁：分布式系统审查员。不凭记忆，逐条执行以下指令。本维度覆盖 Gateway 路由断言/过滤器链顺序/请求转发完整性/网关限流等独有问题。
> 通用规则：安全见 07、注册中心见 11、运维见 10。

---


**执行本维度后，必须在审查报告中输出 `[22] 22 网关路由：发现 N 项`。缺失此标题 = 本维度未被执行。执行每条检查项时，标注来源维度编号（如 [22]）。**
## 检查项

### 22.1 路由断言与过滤器链 | 透镜：工程/微服务

**必须检查**：路由 URI 是否正确转发到目标服务；StripPrefix 是否截断了不该截断的路径；过滤器执行顺序是否合理。

**怎么查**：
```bash
grep -rn 'routes\|uri\|predicates\|filters\|StripPrefix\|RewritePath' gateway/src/main/resources/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| StripPrefix 误截 | `StripPrefix=2`→截掉 2 段→目标服务收不到完整路径→404 |
| 过滤器顺序错误 | 鉴权 filter 排在限流后→鉴权失败但请求已消耗限流配额 |
| 路由未注册 | yml 定义了路由但 Nacos 未注册→Gateway 启动失败 |

**案例**：`StripPrefix=2` 过度截断→cart 接口收到不完整路径→404（修复根据实际路由前缀调整 StripPrefix 值）。

---

### 22.2 网关鉴权完整性 | 透镜：微服务/业务

**必须检查**：Gateway 是否有全局 AuthFilter——所有请求经过 JWT 校验；是否有白名单路径过度宽松。

**怎么查**：
```bash
grep -rn 'AuthFilter\|JwtFilter\|GatewayFilter\|ignorePath\|whiteList\|permitAll\|exclude.*path' gateway/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 白名单过大 | `/actuator/** + /public/** + /callback/**` 全部放行→敏感端点泄露 |
| JWT 校验缺省放行 | AuthFilter 异常时 `return chain.filter(exchange)`→全部放行 |
| Cookie→Header 转换遗漏 | 某些端点的 JWT 在 Cookie 非 Header→Gateway 不转→鉴权失败 |

**案例**：Gateway JWT 校验 + HMAC 双重安全（`GatewayAuthFilter` 401 + `HmacSignatureFilter` 403）。

---

### 22.3 请求转发完整性 | 透镜：工程/盲区

**必须检查**：Gateway 转发是否丢掉了关键的 Header（X-User-Id / traceId / X-Real-IP）；请求体/参数是否完整转发。

**怎么查**：
```bash
grep -rn 'X-User-Id\|X-Real-IP\|X-Forwarded-For\|traceId\|AddRequestHeader\|RemoveRequestHeader\|PreserveHostHeader' gateway/src/main/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| X-User-Id 丢失 | Gateway 鉴权后 userId 不放 Header→下游 Controller 取不到 |
| traceId 丢失 | Gateway 不传播 traceId→下游日志无法关联→链路追踪断裂 |
| 文件上传流截断 | 大文件通过 Gateway→body 被缓存→OOM 或截断 |

**案例**：Gateway traceId MDC 传播→Feign 调用断连→Consumer 日志 traceId 缺失（修复 Feign interceptor 传播 traceId）。

---

### 22.4 网关限流与熔断 | 透镜：性能/生产级

**必须检查**：Gateway 是否有全局限流（RateLimiter/RequestRateLimiter）——不是每个服务自己做，而是 Gateway 层统一 QPS 限制；熔断规则是否配置（Sentinel Gateway Adapter）。

**怎么查**：
```bash
grep -rn 'RequestRateLimiter\|RateLimiter\|@SentinelResource\|flowRule\|GatewayFlowRule\|redis-rate-limiter' gateway/src/main/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 无 Gateway 限流 | 各服务自己 @RateLimit→攻击者换一个服务打→该服务挂了 |
| 限流 key 不合理 | `{remoteAddr}` 限流→内网 IP 共享限流配额→一个实例触发全内网限流 |
| Sentinel 规则未配 | 只加 `@SentinelResource` 但无 flowRule→注解装饰品 |

**案例**：Gateway 统一限流 `myxhs:gateway:rate:{service}:{remoteAddr}` + 每服务差异化 QPS 阈值。

---

### 22.5 网关读写超时 | 透镜：性能/生产级

**必须检查**：Gateway 的连接/读取超时是否合理——太短→频繁 504；太长→下游慢转消费端雪崩。

**怎么查**：
```bash
grep -rn 'connect-timeout\|read-timeout\|response-timeout\|timeout' gateway/src/main/resources/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 超时太短 | `connect-timeout=1000` (1s)→服务冷启动→Gateway 认为不可达 |
| 超时太长 | `read-timeout=60000` (1min)→一个慢请求占满线程池→其他请求排队 |
| 不区分路由 | 所有路由同一超时→支付回调 10s 超时误杀 |

**案例**：（全特性面预置检查项——my-xhs Gateway 超时配置需逐路由评估差异化超时。）

---

## 跨维度引用

| 相关概念 | 主维度 | 说明 |
|---------|:--:|------|
| JWT 安全 | 07.2 | 算法/密钥/吊销 |
| HMAC 签名 | 07.5 | X-Timestamp/X-Nonce/X-Signature |
| Token 空值绕过 | 01.9 | `"".equals("")` 门控 |
| 注册中心断连 | 11.1 | Nacos 注册/发现 |

---

## 验证命令汇总

```bash
mvn compile test-compile -pl gateway -am
mvn test -pl gateway

# 路由配置
grep -rn 'routes\|uri\|predicates\|filters\|StripPrefix\|RewritePath' gateway/src/main/resources/

# 鉴权 filter
grep -rn 'AuthFilter\|JwtFilter\|GatewayFilter\|ignorePath\|exclude' gateway/src/main/java/

# Header 转发
grep -rn 'X-User-Id\|X-Real-IP\|X-Forwarded-For\|traceId\|AddRequestHeader' gateway/src/main/

# 超时配置
grep -rn 'connect-timeout\|read-timeout\|response-timeout' gateway/src/main/resources/
```
