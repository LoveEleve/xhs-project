# 07 安全与鉴权

> 复审维度 07 | 每个模块必查 | 9 透镜全覆盖，鉴权的 fail-closed + 凭据安全为本维度核心
>
> 你是谁：分布式系统审查员。不凭记忆，逐条执行以下指令。每个检查项标注了透镜类型，覆盖
> 业务逻辑/工程正确性/分布式正确性/生产级/可扩展性/并发/微服务/性能/盲区 全部 9 个维度。
> 注：Token 空值绕过（`"".equals("")`）的完整判定见 01.9。

---


**执行本维度后，必须在审查报告中输出 `[07] 07 安全与鉴权：发现 N 项`。缺失此标题 = 本维度未被执行。执行每条检查项时，标注来源维度编号（如 [07]）。**
## 检查项

### 7.1 Gateway 鉴权边界完整性 | 透镜：微服务/工程

**必须检查**：Gateway 层的鉴权是否覆盖了所有入口端点——用户请求是否**全部经过** JWT 校验；内部 Feign 调用是否只经过 X-Internal-Call 而非 JWT。

**怎么查**：
```bash
grep -rn 'GatewayAuthFilter\|gateway.*filter\|JwtAuth\|JwtFilter\|AuthorizationFilter' gateway/src/main/java/
grep -rn '@CrossOrigin\|/public\|/callback\|/webhook' my-xhs-<module>/src/main/java/com/myxhs/*/controller/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 免鉴权路径过多 | `/public/** + /callback/** + /health/** + /actuator/**` 全部无鉴权→可能暴露敏感信息 |
| JWT 过期时间过长 | Token 7 天有效→用户封禁后 JWT 仍有效→攻击窗口（需 Session 级即时拦截） |
| Feign 端点也用 JWT | 内部调用的端点加了 JWT 校验→Feign 调用需传 Token→JWT 过期后内部调用失败 |
| X-Internal-Call 加在用户端点 | 用户端点加 X-Internal-Call 检查→Gateway 进来的合法用户请求被拒（见 7.4） |

**案例**：Gateway JWT+HMAC 是用户面充分边界——在用户 Controller 加 X-Internal-Call 会阻断 Gateway 进来的合法请求（`X-Internal-Call` 仅用于 Feign 跨服务调用）。

---

### 7.2 JWT 安全 | 透镜：工程/生产级

**必须检查**：JWT 的签名算法是否安全、有效期是否合理、是否有吊销机制。

**怎么查**：
```bash
grep -rn 'Jwts\|JWT\|token\|HS256\|RS256\|jjwt\|SecretKey' my-xhs-<module>/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| `alg: none` 未禁用 | 攻击者发 alg=none（无签名）JWT→通过校验 |
| HS256 + 弱密钥 | 对称算法密钥太短→暴力破解→伪造任意用户 JWT |
| JWT 无过期时间 | JWT 永久有效→一旦泄漏永久可用 |
| 无吊销机制 | 用户修改密码/封禁后→旧 JWT 仍有效至过期→攻击窗口 = TTL |
| JWT 中存敏感信息 | `userId + passwordHash + phone` 全放 JWT→解码即泄露 |

**案例**：用户 status=0（封禁）不应等到 JWT 过期才生效——每个写端点都需检查用户状态或 Gateway 统一拦截（Session 级即时禁止）。

---

### 7.3 密码与凭据安全 | 透镜：工程/生产级

**必须检查**：用户密码存储是否使用 BCrypt/SHA-256（非明文/非 MD5）；凭据（数据库密码/Nacos/Redis/OSS key）是否外部化。

**怎么查**：
```bash
grep -rn 'password\|passwd\|secret\|apiKey\|accessKey' my-xhs-<module>/src/main/resources/ | grep -v '${'
grep -rn 'BCrypt\|PasswordEncoder\|encode\|matches' my-xhs-<module>/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 密码明文存储 | `INSERT user(password='123456')` → 数据库泄漏→全部密码暴露 |
| 凭据硬编码 | `private static final String API_KEY = "sk-xxx"` → 代码泄漏→凭据泄漏 |
| 凭据未外部化 | `application.yml` 中写死 `admin-token: abc123`→无环境变量→所有环境同一 Token |
| BCrypt 迭代次数太低 | `BCrypt(4)`（2^4=16 次）→过于脆弱→应 ≥10（1024 次） |

**案例**：my-xhs 14 个模块 ADMIN_TOKEN/INTERNAL_TOKEN 曾硬编码在 Controller yml 中（已全部改 `@Value` + 环境变量）。

---

### 7.4 内部服务互信 | 透镜：微服务/分布式

**必须检查**：跨服务 Feign 调用是否携带了内部认证信息——X-Internal-Call/INTERNAL_CALL_TOKEN；内部端点是否校验了这些信息。

**怎么查**：
```bash
grep -rn 'X-Internal-Call\|INTERNAL_CALL\|InternalCall' my-xhs-<module>/src/main/java/
grep -rn '@FeignClient' my-xhs-<module>/src/main/java/com/myxhs/*/feign/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| Feign 不传认证 | Feign 调用无 `X-Internal-Call` Header→接收方校验缺失→内部端点可被外部调用 |
| INTERNAL_CALL_TOKEN 为空绕过 | 同 01.9 空 token 绕过——见该维度 |
| 内部端点无鉴权 | `/internal/**` 无任何鉴权→外部猜中路径直接调用 |
| FeignConfig 未全局配置 | 每个 Feign 写一次 Interceptor→漏一个漏全部 |

**案例**：`InternalCallFeignConfig` 曾缺 INTERNAL_CALL_TOKEN 外部化 + 空 token 不检查（已修复 3 个模块的 Feign 配置 + 全部 12 处校验点加 `!isEmpty` 前置）。

---

### 7.5 HMAC 签名校验 | 透镜：工程/微服务

**必须检查**：非公开接口（内部 API/回调）是否有 HMAC 签名校验；X-Timestamp/X-Nonce 防重放是否完备。

**怎么查**：
```bash
grep -rn 'HMAC\|Hmac\|X-Signature\|X-Timestamp\|X-Nonce\|hmacSha256' my-xhs-<module>/src/main/java/
grep -rn 'NonceValidator\|TimestampValidator\|SignatureValidator' my-xhs-<module>/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| secret 用全局固定值 | 所有用户同一 HMAC secret→一个泄露全暴露 | per-session UUID 密钥（每次登录生成新 secret） |
| Nonce 无过期 | Nonce 仅查存在不查时间→可以 1 小时后重放 | Nonce + Timestamp 组合，Timestamp >5min 拒绝 |
| 签名算法弱 | 用 MD5 而不用 HMAC-SHA256→被伪造 | 永远用 HMAC-SHA256 |
| 只校验签名不校验 body | 修改 body 后签名不变→可篡改内容 | 签名 = HMAC(body + timestamp + nonce + secret) |

**案例**：my-xhs HMAC 用 per-session UUID（登录响应 `hmacSecret`），GatewayAuthFilter(401) + HmacSignatureFilter(403) 双重安全。

---

### 7.6 端点鉴权全覆盖 | 透镜：业务/盲区

**必须检查**：模块的每个端点（Controller 方法）是否都有鉴权——有没有遗漏的公开端点、内部调用未加认证的端点。

**怎么查**：
```bash
grep -rn '@GetMapping\|@PostMapping\|@PutMapping\|@DeleteMapping' my-xhs-<module>/src/main/java/com/myxhs/*/controller/ -A3 | grep -B3 '@PreAuthorize\|@PermitAll\|@Anonymous\|ADMIN_TOKEN'
```
逐端点确认：鉴权注解/鉴权参数是否存在。

**判定**：
- 端点无任何鉴权注解且不在 ignorePaths 中→**零保护**
- 管理员端点无 ADMIN_TOKEN 校验→普通用户可调管理接口
- 接口路径在 ignorePaths 但方法没有—不是每个方法都需要鉴权

**案例**：5 个管理员端点无权限校验（counter/CounterController、product/ProductController 等多个模块）——修复加 ADMIN_TOKEN 校验。

---

### 7.7 审计日志覆盖 | 透镜：生产级

**必须检查**：敏感操作（管理员操作/用户封禁/支付/退款/库存手动修改）是否有审计日志——包含操作人、操作时间、操作内容。

**怎么查**：
```bash
grep -rn 'log.info\|log.warn\|logger' my-xhs-<module>/src/main/java/com/myxhs/*/controller/ | grep -iE 'admin|delete|ban|freeze|refund|manual'
grep -rn '@Audit\|auditLog\|AuditEvent\|operationLog' my-xhs-<module>/src/main/java/
```

**判定**：
- 管理员操作无日志→取消订单/封禁用户→不可追溯→安全合规风险
- 日志不包含操作人→只看得到"有人删了订单"看不到是谁
- 日志无失败操作记录→只记成功不记失败→安全审计盲区

**案例**：（全特性面预置检查项——my-xhs 02-07 模块的审计日志覆盖度待评估，08-15 未审模块的 admin 端点需逐项核查。）

---

### 7.8 鉴权框架耦合 | 透镜：可扩展性

**必须检查**：鉴权逻辑是否散落在 Controller 中手动 if-then，还是通过统一的拦截器/过滤器/注解实现。切换认证框架的成本。

**怎么查**：
```bash
grep -rn 'ADMIN_TOKEN\|INTERNAL_TOKEN\|X-Internal-Call' my-xhs-<module>/src/main/java/com/myxhs/*/controller/ | wc -l
grep -rn '@PreAuthorize\|@Secured\|@RolesAllowed\|SecurityFilterChain' my-xhs-<module>/src/main/java/ | wc -l
```

**判定**：
- 鉴权逻辑散落在 10+ Controller 中→每个 Controller 手动 `if(token.equals(...))`→切 Spring Security 全模块重写
- 统一 Gateway filter + 注解驱动→切框架只改 Gateway
- **记录即可，不强制修改。**

**案例**：my-xhs 鉴权散落在 Controller + GatewayAuthFilter + HmacSignatureFilter——切 Spring Security OAuth2 为全局改造。

---

## 跨维度引用

| 相关概念 | 主维度 | 说明 |
|---------|:--:|------|
| Token 空值绕过（`"".equals("")`） | 01.9 | fail-closed 空值前置检查 |
| RateLimit 限流 | 01.11 | 写端点限流 + prefix 命名空间 |
| IDOR 归属校验 | 01.6 | 资源归属 + 假 IDOR 修复 |
| MQ 消费者鉴权 | 04 | MQ ACL / Consumer Group 凭证 |

---

## 验证命令汇总

```bash
mvn compile test-compile -pl <module> -am
mvn test -pl <module>

# Gateway 鉴权 filter
grep -rn 'AuthFilter\|JwtFilter\|GatewayFilter' gateway/src/main/java/

# JWT 实现
grep -rn 'Jwts\|JWT\|token' my-xhs-<module>/src/main/java/ | grep -iv 'RefreshToken\|CounterToken'

# 密码加密
grep -rn 'BCrypt\|PasswordEncoder\|encode\|matches' my-xhs-<module>/src/main/java/

# 凭据外部化
grep -rn 'admin.token\|token.*=.*"\|secret.*=.*"\|password.*=.*"' my-xhs-<module>/src/main/resources/ | grep -v '${'

# 内部调用认证
grep -rn 'X-Internal-Call\|INTERNAL_CALL\|InternalCall' my-xhs-<module>/src/main/java/

# HMAC 签名
grep -rn 'HMAC\|Hmac\|X-Signature\|X-Timestamp\|X-Nonce' my-xhs-<module>/src/main/java/

# 端点鉴权缺口
grep -rn '@GetMapping\|@PostMapping\|@PutMapping\|@DeleteMapping' my-xhs-<module>/src/main/java/com/myxhs/*/controller/ | while read -r line; do
  f=$(echo "$line" | cut -d: -f1); l=$(echo "$line" | cut -d: -f2)
  head -$((l+5)) "$f" | tail -5 | grep -qE 'ADMIN_TOKEN|INTERNAL_TOKEN|INTERNAL_CALL|RateLimit' || echo "NO AUTH: $f:$l"
done

# admin 端点审计日志
grep -rn 'log.info\|log.warn' my-xhs-<module>/src/main/java/com/myxhs/*/controller/ | grep -iE 'admin|delete|ban|freeze|refund'

# 鉴权散落度
grep -rn 'ADMIN_TOKEN\|INTERNAL_TOKEN' my-xhs-<module>/src/main/java/com/myxhs/*/controller/ | wc -l
```
