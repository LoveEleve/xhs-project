# G1-04 Token 生命周期用例

> 组：G1 认证与用户 | 入口：**gateway(19000)**（refresh/logout 端点）
> 依赖：G1-03 登录拿 {accessToken}/{refreshToken}/{userId}
> 时间引用：矩阵 #8（access 30min）、#9（refresh 7 天）、#12（HMAC secret）

## 代码实证（2026-08-12）
- 签发：access=HS256 JWT(30min, type=access)；refresh(7 天, type=refresh)；Redis 存 `user:token:refresh:{userId}`（**单设备：新登录覆盖旧**）；hmac secret 7 天
- 密钥：`MyXhs@2026#JwtSecretKey!ForTokenSign`（自签/伪造测试可用）
- refresh 流程：type 校验(40102) → 黑名单(40103) → 锁(jti) → 二次黑名单 → **Redis 比对（单设备）** → 旧 refresh 入黑名单 → 新 token 对
- logout：access/refresh 入黑名单（TTL=剩余有效期）+ 清 Redis token
- gateway access 校验：验签 + 黑名单（`user:token:blacklist:{jti}`）
- 业务码：40101 过期 / 40102 无效 / 40103 已注销

## 测试工具（自签 JWT，python）
```python
import base64, hmac, hashlib, json, time
SECRET = "MyXhs@2026#JwtSecretKey!ForTokenSign"
def b64(d): return base64.urlsafe_b64encode(d).rstrip(b'=').decode()
def sign_jwt(uid, typ, exp_ts):  # exp_ts = 过期时间戳(秒)
    h = b64(json.dumps({"alg":"HS256","typ":"JWT"}).encode())
    p = b64(json.dumps({"sub":str(uid),"type":typ,"jti":"forge-test","exp":exp_ts,"iat":int(time.time())-3600}).encode())
    s = hmac.new(SECRET.encode(), f"{h}.{p}".encode(), hashlib.sha256).digest()
    return f"{h}.{p}.{b64(s)}"
# 过期 access：exp = now - 60
# 伪造 access：任意 secret 之外的签名 → 验签失败
```

## 用例清单

### G1-04-01 登录后 token 三件套（基线）
- **L2**：access/refresh/hmac secret 均存在（详见 G1-03-01，本用例作为后续基线，记录 {accessToken}/{refreshToken}/{jti}）

### G1-04-02 refresh 换新（正常流程）
- **入口**：`POST /api/user/auth/refresh?refreshToken={refreshToken}`
- **L1 断言**：200 + 新 {accessToken2}/{refreshToken2}（jti 不同）
- **L2**：
  ```
  EXISTS myxhs:user:token:blacklist:{旧refresh_jti}   # = 1（旧 refresh 已入黑名单）
  GET  myxhs:user:token:refresh:{userId}              # = 新 refresh（覆盖）
  ```
- **旧 access 使用**（代码实证 blacklistOldToken，TokenService:60-63）：带旧 access 调 `GET /api/user/me` → **40103**（**刷新/重登时旧 access 立即拉黑**——非等 30min 过期）

### G1-04-03 refresh 用 access 类型 → 40102
- **入口**：`?refreshToken={accessToken}`（类型错误）
- **L1 断言**：**40102** Token 无效（"需要 Refresh Token"）

### G1-04-04 旧 refresh 复用 → 40103
- **入口**：用 G1-04-02 的旧 {refreshToken} 再刷新
- **L1 断言**：**40103**（黑名单命中）

### G1-04-05 单设备覆盖（第二设备登录，双失效）
- **前置**：G1-04-01 用户重新登录（第二设备，得 {refreshToken3}/{accessToken3}）
- **入口**：用第一设备的 {refreshToken} 刷新 + 用第一设备 {accessToken} 调 /me
- **L1 断言**：refresh → **40103**（Redis 比对不匹配）；旧 access → **40103**（blacklistOldToken）
- **L2**：`GET user:token:refresh:{userId}` = refreshToken3；黑名单含第一设备 access jti

### G1-04-06 自签过期 access → 40101
- **工具**：sign_jwt({userId}, "access", now-60)
- **入口**：带过期 access 调 `GET /api/user/me`
- **L1 断言**：**40101** Token 已过期（gateway 验签时 exp 校验）
- **L2**：无 Redis 写入（黑名单不适用——签名有效但过期）

### G1-04-07 伪造 access（错误签名）→ 40102/401
- **工具**：手工构造 HS256 token（错误 secret 签名）
- **入口**：带伪造 access 调 `GET /api/user/me`
- **L1 断言**：**40102**（验签失败）或 401 通用——实测记录具体码

### G1-04-08 logout 注销 → 双 token 立即失效
- **入口**：`POST /api/user/auth/logout?refreshToken={refreshToken4}`（带 Authorization 头）
- **L1 断言**：200
- **L2**：
  ```
  EXISTS myxhs:user:token:blacklist:{access_jti}    # = 1
  EXISTS myxhs:user:token:blacklist:{refresh_jti}   # = 1
  EXISTS myxhs:user:token:refresh:{userId}          # = 0（已清）
  EXISTS myxhs:user:token:access:{userId}           # = 0（已清）
  EXISTS myxhs:user:hmac:secret:{userId}            # = 0（已清，logout 删三 key 实证）
  ```
- **注销后写操作**：带 hmacSecret 签名写请求 → **403 密钥已过期**（hmac secret 已删）——见 G1-05
- **旧 token 使用**：带注销的 access 调 `GET /api/user/me` → **40103**；用注销的 refresh 刷新 → **40103**

### G1-04-09 黑名单 TTL = 剩余有效期
- **L2**：`TTL myxhs:user:token:blacklist:{access_jti}` ≈ access 剩余秒数（30min 窗口内注销 → ~1800s；越晚注销 TTL 越短）

### G1-04-10 并发 refresh（锁，jti 粒度）
- **并发**：同一 refreshToken 并发 2 次刷新
- **L1 断言**：恰一个 200；另一个 **40203**（锁竞争"Token 正在刷新中"）或 40103（二次黑名单）——实测记录
- **L2**：仅一组新 token 生效；旧 refresh 黑名单

## 执行记录
| 用例 | 时间 | 结果 | 问题/备注 |
|---|---|---|---|
| G1-04-01~10 | | | |

## 断言关键词速查
- 40101 过期 / 40102 无效(类型错/验签败) / 40103 已注销(黑名单/覆盖) / 40203 并发刷新锁
- 关键 L2：blacklist key、refresh 单设备覆盖、黑名单 TTL=剩余有效期

## 深度 REVIEW 补充（2026-08-12）

### 代码实证修正（重要）
1. **刷新/重登时旧 access 立即拉黑**（TokenService:60-63 `blacklistOldToken`）——不是等 30min 过期；04-02/04-05 已修正断言 40103
2. **刷新后 hmac secret 换新**（generateTokenPair 重新生成覆盖）——旧 secret 立即失效
3. **logout 清理三 key**：access/refresh/hmac secret 全删（TokenService:188-190）
4. jti = UUID（黑名单 key 正常，无 null 风险）✅

### 新增用例

#### G1-04-11 刷新后 hmac secret 换新
- **前置**：G1-04-02 刷新后（新 {hmacSecret2}）
- **入口**：用**旧 hmacSecret** 签名的写请求（如 PUT /api/user/me）→ **403**（secret 已被覆盖）；用新 {hmacSecret2} → 200
- **L2**：`GET myxhs:user:hmac:secret:{userId}` = hmacSecret2
- **说明**：前端刷新后必须使用新 secret——重要兼容行为

#### G1-04-12 自签过期 refresh → 40101
- **工具**：sign_jwt({userId}, "refresh", now-60)
- **入口**：`?refreshToken={过期refresh}` → **40101**

### 审查确认（无问题）
- 算法：固定 HS256（Keys.hmacShaKeyFor + verifyWith），jjwt 默认拒绝 alg=none ✅
- 黑名单 key 用 jti（UUID 随机）✅

## 第二轮深度 REVIEW（2026-08-12）

### 代码实证
- **refreshToken 流程无 status 检查**（TokenService:106-165 无 userMapper/status 查询）→ 禁用账号仍可刷新
- gateway access 校验 = 验签 + type + 黑名单（**不查 USER_TOKEN_ACCESS Redis**）→ 禁用账号已持有 access 30min 内仍有效
- blacklistOldToken 解析失败（已过期）跳过黑名单 ✅ 无害

### 新增用例

#### G1-04-13 禁用账号 token 续期（T-005 缺陷验证）
- **前置**：G1-02-01 用户登录得 {access}/{refresh}；`UPDATE t_user SET status=0`
- **入口**：
  1. 带 {access} 调 `GET /api/user/me` → 实测：**200 或 40107？**（服务端是否查 status——记录实测）
  2. 用 {refresh} 刷新 → 实测：**200（缺陷）或 40107（已修）**
- **L2**：刷新后新 access 是否可用
- **说明**：验证 T-005 现状；修复建议 = refresh 流程加 status 检查（见 ISSUES T-005）

### 审查补充
- 黑名单 key 用 jti=UUID ✅；alg=none 拒绝 ✅；refresh 轮换（每次刷新旧 refresh 失效）✅
- **遗留观察**：secret 明文（MyXhs@2026#JwtSecretKey!ForTokenSign，配置+Nacos）——可自签任意用户 access（P-D1 关联）；G1 安全用例验证"自签合法 token 越权"（低，已知风险登记）

## 第三轮深度 REVIEW（2026-08-12）

### 代码实证
- `blacklistToken(null)`：parseToken 异常 → catch 跳过（旧 access 不黑名单）
- logout 只带 refreshToken（无 Authorization）：userId 解析失败 → **Redis 三 key 未清**；refresh 已黑名单；**旧 access 30min 内仍有效**
- refreshToken 走 **@RequestParam（query string）**——URL 传递

### 新增用例

#### G1-04-14 alg=none 攻击（jjwt 安全性验证）
- **工具**：构造 `{"alg":"none","typ":"JWT"}` + payload 无签名
- **入口**：带 alg=none token 调 `GET /api/user/me`
- **L1 断言**：**40102/401**（jjwt 拒绝 alg=none）——实证防混淆攻击

#### G1-04-15 空 refresh 参数
- **入口**：`?refreshToken=`（空）
- **L1 断言**：40002（@RequestParam 必填）或 40102——实测记录

#### G1-04-16 logout 只带 refreshToken（T-007 验证）
- **前置**：登录得 {access}/{refresh}；**不带 Authorization 头**调 logout（仅 ?refreshToken=）
- **L1/L2 断言**：
  - logout 返回 200
  - refresh 已黑名单（旧 refresh 刷新 → 40103）
  - **旧 access 实测**：调 `GET /api/user/me` → **200（缺陷：30min 内仍有效）**——T-007 实证
  - Redis：`EXISTS user:token:refresh:{uid}` 与 `hmac:secret:{uid}`（userId 解析失败未清——实测确认）

### 审查补充
- 黑名单 TTL=token 剩余有效期（到点同步失效，无堆积）✅
- login 与 refresh 并发 → 最后写入者胜（Redis 覆盖，最终一致）✅ 审查通过
- **无管理端会话管理**（USER_TOKEN_ACCESS 预留"踢出登录"但无端点）——审查项（演示可接受）
- access 无角色/权限声明（全登录用户同权）——架构审查（演示可接受）

## 修复后同步（2026-08-12 T-008/012）
- **refresh 入口改 body**：`POST /api/user/auth/refresh` body `{"refreshToken":"..."}`（原 query string，T-008 已修）——所有 refresh 用例（04-02~05/10/12/13）请求方式同步
- **T-012 修复后**：改密成功 → 旧 access/refresh 立即黑名单（401）+ hmac secret 删除——06-05 补充断言
- **T-005 修复后**：禁用账号 refresh → 40107（原"可续期"行为已改）
