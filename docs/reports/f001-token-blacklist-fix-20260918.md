# F-001 修复：直连服务端口的 Token 吊销名单校验（GatewayAuthTrustFilter）2026-09-18

> 目标：关闭 review-1 Critical 项 F-001——"已注销/改密/踢下线的 access token 绕过网关直连 19001+ 端口仍可继续使用"。
> 处置详情见 `docs/review-1/02-findings/critical/F-001-revoked-token-bypass-on-direct-service-ports.md` 修复处置节。

## 一、修复（common，全服务生效）

- `GatewayAuthTrustFilter.resolveJwtUserId()`：解析 JWT 后新增吊销校验——
  命中 `RedisKeyConstants.USER_TOKEN_BLACKLIST + jti` → 视为已吊销，剥离 `X-User-Id`（400）；
  Redis 异常 fail-closed（与网关 `GatewayAuthFilter.isBlacklisted` 同口径）。
- 注入：`ObjectProvider<StringRedisTemplate>` + `@Qualifier("stringRedisTemplate")`，锁定**业务 Redis**。

## 二、实施中暴露的次生 Bug（修复首版静默失效的根因）

- 现象：直连携带**有效 JWT** 也返回 400 `缺少必要请求头: X-User-Id`（覆盖分支未生效）。
- 根因：user 等服务同时存在两个 `StringRedisTemplate`（业务 `stringRedisTemplate` 与缓存 `cacheStringRedisTemplate`，缓存库 16380，见 `RedisConfig`/`RedisMultiSourceConfig`）；
  首版 `getIfAvailable()` 类型解析抛 `NoUniqueBeanDefinitionException`，被 `resolveJwtUserId` catch 吞掉 → 所有直连 JWT 按无效剥离。
- 修复：`@Qualifier("stringRedisTemplate")` 按名限定。定位手段：debug 日志 + 临时 WARN 仪器化。
- 教训：**多候选 Bean 环境下不要用按类型 `getIfAvailable()` 取模板**；黑名单必须查业务 Redis，绝不能落到缓存 Redis（否则 fail-open）。

## 三、实测（19001 直连，15 服务全量发布后 23:16）

| # | 场景 | 结果 |
|---|---|---|
| ① | 有效 JWT + 伪造 `X-User-Id: 1` → `GET /api/user/me` | 200，返回 JWT 真实身份（覆盖生效） |
| ② | `POST /api/user/auth/logout` | 200 |
| ③ | 同一 token 直连 `GET /api/user/me` | **400 拒绝**；审计日志 `已吊销 Token 直连被拒 jti=...` |
| ④ | 内部调用（`X-Internal-Call`） | 200 |
| ⑤ | 网关正常流量（带 token `/api/user/me`、`/api/home/feed`） | 200、200 |

## 四、附带修复：content 基线启动回归

批量发布暴露：JPA Demo（`content.jpa.enabled`）引入后，**基线（开关关闭）启动失败**——`JpaRepositoriesAutoConfiguration` 仍激活并要求 `entityManagerFactory`。
修复：content yml 排除 `org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration`（与已有 `HibernateJpaAutoConfiguration` 排除配套），基线恢复 200。
教训：演示开关代码必须**双态可启**（开/关都要能启动），并纳入批量发布回归。

## 五、残余风险

直连端口仍绕过网关 HMAC/限流/审计；未配置 `jwt.secret` 的服务（如 cart）JWT 分支不生效（直连一律剥离，fail-closed）。长期方向仍是收缩直连暴露面。
