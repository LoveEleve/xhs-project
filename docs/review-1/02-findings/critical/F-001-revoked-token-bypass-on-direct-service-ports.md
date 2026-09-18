# F-001 撤销后的 Access Token 仍可直连服务端口继续使用

## 严重度

Critical

## 涉及服务

- `my-xhs-gateway`
- `my-xhs-common`
- 所有依赖 `X-User-Id` 的 Servlet 业务服务

## 涉及文件

- `my-xhs-gateway/src/main/java/com/myxhs/gateway/filter/GatewayAuthFilter.java:119`
- `my-xhs-common/src/main/java/com/myxhs/common/web/GatewayAuthTrustFilter.java:69`
- `my-xhs-common/src/main/java/com/myxhs/common/web/GatewayAuthTrustFilter.java:77`
- `my-xhs-user/src/main/java/com/myxhs/user/service/TokenService.java:203`
- `my-xhs-user/src/main/java/com/myxhs/user/service/TokenService.java:242`
- `my-xhs-user/src/main/java/com/myxhs/user/service/TokenService.java:269`

## 现象

系统宣称注销、删号、改密、单设备踢下线都会让旧 token 立即失效；但这条保证只在 gateway 成立。若请求绕过 gateway 直连 19001+ 服务端口，**已被撤销的 access token 仍会被当成有效身份接受，直到 JWT 自身过期**。

## 触发条件

1. 用户先登录获取 access token
2. 该 token 之后被注销、删号、改密或新登录踢下线
3. 攻击者或客户端不再走 gateway，而是直连某个业务服务端口
4. 请求携带旧 `Authorization: Bearer <revoked-access-token>`

## 证据

1. gateway 会显式查黑名单并拒绝已撤销 token：`GatewayAuthFilter` 在 `my-xhs-gateway/.../GatewayAuthFilter.java:119` 调用 `isBlacklisted(jti)`，命中后直接 401。
2. 各服务的直连信任过滤器 `GatewayAuthTrustFilter` 只会“解析 JWT -> 覆盖 X-User-Id”或“剥离伪造 X-User-Id”，**没有任何黑名单检查**：见 `my-xhs-common/.../GatewayAuthTrustFilter.java:69` 与 `my-xhs-common/.../GatewayAuthTrustFilter.java:77`。
3. `GatewayAuthTrustFilter` 自己的注释已经明确假设“服务端口(19001+)直连可达”：`my-xhs-common/.../GatewayAuthTrustFilter.java:26`。
4. user 服务在注销、删号、改密时确实会把 token 拉黑，说明系统设计意图是“立即失效”：`TokenService.logout()`、`revokeAllTokens()`、`invalidateUserCredentials()` 分别位于 `my-xhs-user/.../TokenService.java:203`、`:242`、`:269`。

## 影响

1. 注销并不能真正阻断旧 token 的继续使用，只是阻断了经 gateway 的路径。
2. 单设备登录被部分绕过：旧设备拿到旧 access token 后，仍可在 token 过期前直连业务端口继续调用。
3. 删号、改密、封禁的即时失效语义不成立，存在安全窗口。
4. 任意只依赖 `X-User-Id` 的用户写接口都受影响，包括用户、订单、购物车、内容、通知、IM 等大量端点。

## 修复建议

1. 在 `GatewayAuthTrustFilter` 的 JWT 分支增加黑名单校验，与 gateway 保持一致。
2. 若出于依赖约束不想在 common 里查黑名单，至少要让直连端口只接受来自 gateway 的可信来路，而不是接受任意有效 JWT。
3. 对“注销/删号/改单密码/踢下线”补一组**绕过 gateway 的直连测试**，验证失效是否真正即时生效。

## 残余风险

即使补上黑名单校验，服务端口直连仍然会绕过 gateway 的其他能力（如 HMAC、路由限流、统一审计）；因此长期更稳的方向仍是收缩直连暴露面。 

## 是否需要补充验证

需要。建议实测一条最短路径：登录拿 token → 调用 `/api/user/me` 成功 → 注销 → 直连 user 19001 再次带旧 access token 调 `/api/user/me`，确认当前是否仍返回 200。

---

## 修复处置（2026-09-18，已修复并验证）

| 项 | 内容 |
|---|---|
| 状态 | ✅ 已修复：15 服务全量发布，5 场景实测通过（23:16） |
| 修复点 | `GatewayAuthTrustFilter` JWT 分支新增吊销名单校验，与网关同口径 |
| 判定 | 命中 `RedisKeyConstants.USER_TOKEN_BLACKLIST + jti` → 视为已吊销，剥离 `X-User-Id`（fail-closed）；Redis 异常同样 fail-closed |
| 注入 | `ObjectProvider<StringRedisTemplate>` + `@Qualifier("stringRedisTemplate")` 锁定业务 Redis（非缓存 Redis） |

### 实施中发现并修复的次生问题（否则修复静默失效）

首版按类型取模板（`getIfAvailable()`）；user 等服务同时存在两个 `StringRedisTemplate`
（业务 `stringRedisTemplate` 与缓存 `cacheStringRedisTemplate`，见 `RedisConfig` / `RedisMultiSourceConfig`，缓存库为 16380），
类型解析抛 `NoUniqueBeanDefinitionException` 并被 `resolveJwtUserId` 的 catch 吞掉 →
**所有直连 JWT 都按无效剥离**（fail-closed），表现为“直连 + 有效 JWT 也返回 400 缺少 X-User-Id”。
改为 `@Qualifier("stringRedisTemplate")` 后恢复。定位手段：`[安全] JWT 解析失败(忽略)` debug + 临时 WARN。

### 实测验证（直连 19001，2026-09-18 23:16）

| 场景 | 请求 | 结果 |
|---|---|---|
| ① 有效 JWT + 伪造 `X-User-Id: 1` | `GET /api/user/me` | 200，返回 JWT 真实身份（覆盖生效） |
| ② 注销 | `POST /api/user/auth/logout` | 200 |
| ③ 已吊销 JWT 直连 | `GET /api/user/me` | **400 拒绝**；审计日志 `已吊销 Token 直连被拒 jti=...` |
| ④ 内部调用 | `X-Internal-Call` + `X-User-Id` | 200 |
| ⑤ 网关正常流量 | 带 token `/api/user/me`、`/api/home/feed` | 200、200 |

### 残余与口径

1. 直连端口仍绕过网关其他能力（HMAC、路由限流、统一审计），长期方向仍是收缩直连暴露面（残余风险不变）。
2. 未配置 `jwt.secret` 的服务（如 cart）JWT 分支不生效：直连一律剥离 `X-User-Id`（fail-closed，无此漏洞面）。
3. 报告：`docs/reports/f001-token-blacklist-fix-20260918.md`。