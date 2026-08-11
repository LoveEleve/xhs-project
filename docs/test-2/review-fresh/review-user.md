# User 模块 Review

## 安全 / 信任模型
1. **[高·系统性] 服务端口信任 X-User-Id + 手工 X-Internal-Call 校验**
   所有 controller 直接 `@RequestHeader("X-User-Id")` 信任该头（如 UserController.java:33）。
   X-Internal-Call/X-Admin-Call 校验是**逐端点手工** `internalToken.equals(v)`，非统一过滤器，
   新增端点易漏。整个安全依赖"防火墙封服务端口"这一前置假设——一旦 19001+ 端口对外可达，
   X-User-Id 可伪造、内部 token（默认 my-xhs-internal-token-2026）可命中即完全越权。
   建议：统一 InternalCall 拦截器/过滤器集中校验，端口级网络安全兜底。

2. **[中] X-Internal-Call 校验用 `.equals()` 非恒定时间**  (CouponController.java:42)
   共享静态 token 的时序侧信道（实际利用难度高，防御性建议用 MessageDigest.isEqual）。

## 业务逻辑
3. **[中] 登录锁定可被滥用做账号 DoS** (UserService.java:356)
   任意人可对目标用户名连续 5 次错密码 → 账号锁定 15 分钟，无 IP 维度限制。
   建议：锁定策略加入 IP 维度，或失败升级为验证码而非直接锁账号。

4. **[低] `@Transactional` 包裹 login/register** (UserService.java:153, 70)
   BCrypt matches（~50-100ms 计算）+ Redis GETDEL/自增 全部在 DB 事务内 → 长时间占连接。
   login 只读可去事务；验证码校验应在事务外用 GETDEL 再进事务，避免"验证码已消费但注册回滚"。

5. **[低] updateUserInfo 手机号唯一性 check-then-update 竞态** (UserService.java:270-279)
   检查与更新非原子；若 DB 无 phone 唯一索引，并发下可重复；有索引则可能抛 DuplicateKeyException → 500（未捕获，与 register 不同）。

## 缓存 / 数据
6. **[中] 用户 PII(phone/email) 缓存到 Redis 30min** (UserService.java:380-392)
   select 含 phone/email 存入缓存；虽是 Cache Aside 常规做法，但敏感字段进缓存需注意 Redis 泄露面。

## Token / 会话
7. **单设备登录**：generateTokenPair 覆盖旧 token 并黑名单化，符合设计。
8. **[低] refreshToken 也会轮换 per-session HMAC secret** (TokenService.java:79-87)
   刷新后旧 hmacSecret 立即失效，在途请求/前端未同步会短暂 401；需前端在 refresh 后更新 secret。

## 验证码
9. **[低] 4位验证码 + 无生成频率限制** (CaptchaService.java:38,40)
   32^4≈104万组合，生成接口未限流；叠加登录无 IP 限流有爆破风险。GETDEL 一次性消费实现正确。
