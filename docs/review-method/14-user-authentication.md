# 14 用户与认证

> 复审维度 14 | 覆盖模块：01-user | 领域专属检查项
>
> 你是谁：分布式系统审查员。不凭记忆，逐条执行以下指令。本维度为**领域维度**——覆盖 01-user 模块独有的用户生命周期、认证、授权问题。
> 通用安全规则（Token空值/JWT签名/HMAC/端点鉴权）见 07 维度；IDOR归属见 01.6。

---


**执行本维度后，必须在审查报告中输出 `[14] 14 用户与认证：发现 N 项`。缺失此标题 = 本维度未被执行。执行每条检查项时，标注来源维度编号（如 [14]）。**
## 检查项

### 14.1 用户注册验证链完整性 | 透镜：业务/工程

**必须检查**：注册流程的每一步是否有防护——手机/邮箱验证码不可重用、注册频率限制、邀请码单次有效。

**怎么查**：
```bash
grep -rn 'register\|signup\|createUser\|sendCode\|verifyCode\|captcha\|inviteCode' my-xhs-user/src/main/java/com/myxhs/user/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 验证码可重用 | 同一验证码多次注册→盗用他人手机号注册 |
| 无发送频率限制 | 用户可手动触发每秒 100 次短信→短信费用爆炸 |
| 验证码明文对比 | `if(inputCode.equals(storedCode))`→可被预测 |
| 邀请码无消耗 | 同一邀请码无限使用→邀请奖励滥用 |

**案例**：验证码存储在 Redis 但无发送频率保护——攻击者可刷短信验证码消耗费用。

---

### 14.2 JWT 刷新与吊销 | 透镜：工程/生产级

**必须检查**：JWT 的 refresh token 机制是否安全；用户修改密码/被管理员封禁后，旧 JWT 是否立即失效。

**怎么查**：
```bash
grep -rn 'refreshToken\|refresh.*[Tt]oken\|revokeAllTokens\|revoke.*[Tt]oken\|JwtBlacklist\|tokenBlacklist\|token.*invalid' my-xhs-user/src/main/java/com/myxhs/user/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| refreshToken 无过期 | refreshToken 永久有效→一旦泄漏=永久访问 |
| refreshToken 可无限换新 | 一个 refreshToken 产生无限个新 accessToken→无法吊销 |
| 修改密码后 JWT 仍有效 | 旧密码被盗→改密码→旧 JWT 仍可登录至过期→攻击窗口 = JWT TTL |
| 封禁用户 JWT 仍有效 | `status=0` 但 JWT 仍有效→封禁无效——需 Gateway 实时检查用户状态 |

**案例**：my-xhs `revokeAllTokens` 用 HMAC per-session secret（每次登录重新生成）——旧 JWT 在新 secret 下签名无效→自然吊销（`UserService.revokeAllTokens` 修复加 HMAC 签名验证）。

---

### 14.3 禁用用户即时拦截 | 透镜：生产级/盲区

**必须检查**：用户被封禁（status=0）后，是否需等到 JWT 过期才生效——还是每个请求实时校验用户状态。

**怎么查**：
```bash
grep -rn 'status.*=.*0\|isBanned\|isDisabled\|isActive\|isFrozen' my-xhs-user/src/main/java/
grep -rn 'X-User-Id\|userId' gateway/src/main/java/ | grep -i 'filter\|interceptor\|auth'
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 仅在登录时检查状态 | 用户登录后被封禁→JWT 仍有效→封禁延迟至 JWT 过期（可能 7 天） |
| Gateway 不检查用户状态 | userId 通过 JWT 校验后直接路由→封禁用户所有端点的假象 |
| 状态只在 user 模块检查 | 其他服务不查用户状态→封禁用户仍然可以发笔记/评论 |

**修复**：Gateway 拦截器实时查 Redis `user:status:{userId}`——或每个写端点调 user Feign 验证状态。

**案例**：my-xhs 封禁用户需等 JWT 过期才生效→整改方案：Gateway 统一拦截 + Redis 用户状态缓存。

---

### 14.4 密码与账户安全 | 透镜：工程/生产级

**必须检查**：密码修改是否需验证原密码；密码重置是否有二因素验证；账户找回路径是否有防撞库。

**怎么查**：
```bash
grep -rn 'changePassword\|resetPassword\|forgotPassword\|findPassword\|updatePassword' my-xhs-user/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 改密码不需要旧密码 | 拿到 session/JWT 直接改密码→账户被盗也无法恢复 |
| 重置密码无二次验证 | 仅凭验证码重置→验证码泄露→密码被改 |
| 账户找回路径暴露用户存在 | `reset?phone=138xxx` 返回"用户不存在" vs "验证码已发送"→可枚举用户手机号 |
| 多次错误密码无锁 | 暴力破解无上限→字典攻击可成功 |

**案例**：（全特性面预置检查项——my-xhs 密码修改/重置/账户找回的安全机制需逐路径审计。）

---

### 14.5 多设备登录与会话管理 | 透镜：工程/并发

**必须检查**：同一用户多设备登录时的 JWT/session 管理——是否支持踢下线、每设备独立 secret、登录设备列表。

**怎么查**：
```bash
grep -rn 'session\|deviceId\|logout\|kickout\|forceLogout\|concurrent.*login\|device.*list' my-xhs-user/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 无设备维度 | 所有设备共享同一 JWT→A 设备登录后 B 设备的 secret 覆盖 A→A 被踢 |
| 退出登录不清除 | `logout` 不清 Redis token→旧 token 仍可用 |
| 无并发限制 | 同 user 的 100 个 JWT 同时有效→泄漏一个全泄漏 |

**案例**：my-xhs HMAC per-session secret 隐含支持多设备独立——每次登录生成新的 `hmacSecret`，各设备独立工作。

---

### 14.6 用户数据删除与合规 | 透镜：业务/生产级

**必须检查**：用户注销/数据删除是否符合"被遗忘权"——是否真删除用户数据 or 仅逻辑删除（status=-1）。

**怎么查**：
```bash
grep -rn 'deleteAccount\|deleteUser\|注销\|permanently.*delete\|hard.*delete\|@TableLogic' my-xhs-user/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 仅逻辑删除 | 用户申请删除→status=-1→但实际上数据全保留→合规风险 |
| 关联数据未级联删除 | 删用户→但笔记/评论/点赞残留→孤儿数据 or 合规问题 |
| 删除未通知下游 | 用户删除→MQ 不发→search/analytics/counter 仍保留数据 |

**案例**：（全特性面预置检查项——my-xhs 用户数据删除的合规/级联清理路径需逐环节核查。）

---

## 跨维度引用

| 相关概念 | 主维度 | 说明 |
|---------|:--:|------|
| JWT 签名/算法/密钥安全 | 07.2 | HS256/RS256/alg:none/吊销 |
| HMAC per-session 签名 | 07.5 | X-Timestamp/X-Nonce/X-Signature |
| Token 空值绕过 | 01.9 | `"".equals("")` fail-closed |
| 密码加密存储 | 07.3 | BCrypt/密码外部化 |
| 端点鉴权全覆盖 | 07.6 | ADMIN_TOKEN/用户端点 |

---

## 验证命令汇总

```bash
mvn compile test-compile -pl my-xhs-user -am
mvn test -pl my-xhs-user

# 注册/验证链路
grep -rn 'register\|signup\|sendCode\|verifyCode\|captcha' my-xhs-user/src/main/java/

# JWT 刷新/吊销
grep -rn 'refreshToken\|refresh.*Token\|revokeAllTokens\|revoke.*Token\|hmacSecret' my-xhs-user/src/main/java/

# 用户状态检查
grep -rn 'isActive\|isDisabled\|isBanned\|status.*=.*0\|user.*status' my-xhs-user/src/main/java/

# 密码安全
grep -rn 'changePassword\|resetPassword\|forgotPassword\|updatePassword\|BCrypt\|encode' my-xhs-user/src/main/java/

# 多设备/会话
grep -rn 'session\|deviceId\|logout\|kickout\|hmacSecret' my-xhs-user/src/main/java/

# 账号删除
grep -rn 'deleteAccount\|deleteUser\|注销\|@TableLogic' my-xhs-user/src/main/java/
```
