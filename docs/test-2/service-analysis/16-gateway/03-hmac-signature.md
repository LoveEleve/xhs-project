# Gateway HMAC 签名设计 — 深度技术分析

> 关联源码：`HmacSignatureFilter.java` / `GatewayAuthFilter.java` / `AuthProperties.java`

---

## 业务背景

API 网关面临的安全威胁：

| 威胁 | 说明 | 防护 |
|---|---|---|
| 篡改 | 中间人修改请求参数 | HMAC 签名 |
| 重放 | 截获合法请求重复发送 | nonce + timestamp |
| 越权 | 伪造用户身份 | JWT 鉴权（见另一篇） |
| 时序攻击 | 通过比较耗时猜测签名 | 常量时间比较 |

HMAC（Hash-based Message Authentication Code）：用密钥对请求内容计算摘要，接收方用同一密钥重算并比对。**无密钥无法伪造合法签名**。

---

## 签名生成（客户端）

```
signStr = HTTP_METHOD + URI_PATH + timestamp + nonce
signature = Base64(HmacSHA256(signStr, hmacSecret))
```

```python
# 客户端示例
import hmac, hashlib, base64, time

secret = "myxhs-hmac-secret-key-2024"
ts = str(int(time.time() * 1000))
nonce = uuid.uuid4().hex

sign_str = f"POST/api/im/ws/ticket{ts}{nonce}"
signature = base64.b64encode(
    hmac.new(secret.encode(), sign_str.encode(), hashlib.sha256).digest()
).decode()

headers = {
    "X-Timestamp": ts,
    "X-Nonce": nonce,
    "X-Signature": signature
}
```

**签名内容不含 body**：当前实现只签 method+path+timestamp+nonce。如果请求 body 被篡改，签名仍有效。生产环境建议把 body 摘要（如 SHA256(body)）加入签名串。

---

## 服务端校验（4 步）

```
1. HMAC 白名单路径 → 跳过
2. timestamp 有效期检查（5 分钟）
3. nonce 防重放（Redis SET NX EX 300）
4. 签名比对（常量时间）
```

### 1. timestamp 有效期

```java
long requestTime = Long.parseLong(timestamp);
long currentTime = System.currentTimeMillis();
if (Math.abs(currentTime - requestTime) > 5 * 60 * 1000) {
    return forbidden("请求已过期");
}
```

**为什么 5 分钟**：太短（1 分钟）→ 客户端与服务端时钟偏差导致误伤；太长（30 分钟）→ 重放窗口太大。5 分钟是业界常用值（AWS SigV4 用 15 分钟，微信支付用 5 分钟）。

### 2. nonce 防重放

```java
// Lua SET NX EX：同一 nonce 5 分钟内只能使用一次
String nonceKey = "myxhs:gateway:nonce:" + nonce;
Boolean isNew = stringRedisTemplate.execute(NONCE_SET_SCRIPT,
        Collections.singletonList(nonceKey), "300");
if (Boolean.FALSE.equals(isNew)) {
    return forbidden("重复请求");
}
```

**nonce 必须唯一**：每次请求生成新 nonce（UUID）。如果攻击者重放整个请求（相同 timestamp + nonce + signature），nonce 已被消费 → 拒绝。

### 3. 签名比对

```java
String expectedSignature = hmacSha256(signStr, hmacSecretKey);
if (!MessageDigest.isEqual(
        expectedSignature.getBytes(UTF_8),
        signature.getBytes(UTF_8))) {
    return forbidden("签名不匹配");
}
```

**为什么用 MessageDigest.isEqual 而不是 equals**：

```
❌ String.equals()：逐字符比较，遇到第一个不同字符就返回 false
   → 攻击者可以通过响应时间差异逐字节推断签名（时序攻击）
   → 32 字节签名可被逐字节猜出（约 32 × 256 次尝试）

✅ MessageDigest.isEqual()：固定时间比较（无论差异在哪都遍历全部）
   → 无法通过时间差异推断签名
```

---

## 密钥管理

```yaml
gateway:
  auth:
    hmac-secret: myxhs-hmac-secret-key-2024   # HMAC 密钥
    secret: MyXhs@2026#JwtSecretKey!ForTokenSign  # JWT 密钥（分开管理）
```

**为什么 HMAC 密钥和 JWT 密钥分离**：
- 职责不同：JWT 验证身份，HMAC 防篡改
- 泄露隔离：一个密钥泄露不影响另一个
- 轮换独立：可以分别轮换

---

## HMAC 白名单

```yaml
gateway:
  auth:
    hmac-white-list:
      - /api/user/auth/**
      - /api/note/detail/**
      - /api/counter/**
```

白名单内的路径跳过 HMAC 校验（如公开接口）。**注意**：白名单是双刃剑——路径过宽会削弱签名保护。

---

## 幂等 vs 防重放

| 概念 | 层次 | 目的 |
|---|---|---|
| 防重放（HMAC nonce） | Gateway | 防止同一请求重复发送 |
| 幂等（@Idempotent） | 业务服务 | 防止重复执行业务操作 |

两者互补：Gateway 防网络层重放（5 分钟窗口），业务层防业务级重复（如重复下单，24h 窗口）。

---

## 面试 Q&A

**Q: HMAC 签名和数字签名（RSA）的区别？**
A: HMAC 是对称密钥（双方共享密钥），速度快、实现简单。数字签名是公钥加密（私钥签名、公钥验证），适合多方信任场景（客户端不持有服务端密钥）。内部 API 网关用 HMAC 足够。

**Q: 如果客户端时钟不准导致 timestamp 校验失败怎么办？**
A: 5 分钟容忍窗口覆盖大部分时钟偏差。客户端时间严重偏差（>5min）需要 NTP 校准。也可以返回"时间偏差过大"错误让客户端校准。

**Q: nonce 在 Redis 中存 300 秒，但 timestamp 也是 5 分钟，重复窗口怎么算？**
A: 两者独立。timestamp 拒绝 5 分钟外的请求；nonce 拒绝 5 分钟内的重复。组合效果：同一请求只能在 5 分钟内重放一次，且重放时 nonce 已消费 → 拒绝。

---

## 生产实验

| 场景 | 结果 |
|---|---|
| 完整签名请求 | 200 ✅ |
| 无签名 Header | 403 ✅ |
| 签名不匹配 | 403 ✅ |
| timestamp 超 5 分钟 | 403 ✅ |
| nonce 重复（第 2 次） | 403 ✅ |

Redis 验证：`SET myxhs:gateway:nonce:{nonce} 1 NX EX 300`。

---

## 发散

### body 签名

当前签名不含 body。改进方案：

```
signStr = method + path + timestamp + nonce + SHA256(body)
```

body 摘要加入签名串后，body 篡改会导致签名校验失败。GET 请求无 body 时摘要为空串。

### AWS SigV4

AWS 的 SigV4 是工业级实现：签名包含 region/service/日期/body 摘要，支持临时凭证。如果未来对外开放 API，可以参考 SigV4 设计完整签名体系。
