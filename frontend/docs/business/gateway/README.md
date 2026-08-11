# 网关模块（my-xhs-gateway）

> 来源：`my-xhs-gateway` 源码直读。

## 一、职责
- 统一入口 `:19000`，路由转发到各微服务。
- **JWT 鉴权**（GatewayAuthFilter）+ **HMAC 签名校验**（HmacSignatureFilter）+ 限流。
- 所有前端请求都经网关，前端只认网关。

## 二、路由（前端只需按前缀调用）
| 前缀 | 目标服务 |
|------|---------|
| `/api/user/**` | my-xhs-user |
| `/api/note/**,/api/comment/**,/api/topic/**` | my-xhs-content |
| `/api/social/**,/api/analytics/**` | my-xhs-analytics |
| `/api/search/**` | my-xhs-search |
| `/api/order/**` | my-xhs-order |
| `/api/product/**` | my-xhs-product |
| `/api/cart/**` | my-xhs-cart |
| `/api/coupon/**` | my-xhs-coupon |
| `/api/inventory/**` | my-xhs-inventory |
| `/api/counter/**` | my-xhs-counter |
| `/api/home/**` | my-xhs-home |
| `/api/notification/**` | my-xhs-notification |
| `/api/im/**` | my-xhs-im |
| `/api/recommend/**` | my-xhs-search |

## 三、JWT 鉴权（白名单 = 无需登录）
白名单路径**无需 token**：
```
/api/user/auth/captcha|register|login|refresh|logout
/api/user/*/info
/api/note/detail/**、/api/note/user/**
/api/comment/list/**、children/**、count/**、page/**
/api/social/following/**、follower/**、/api/social/like/count
/api/counter/get、batch-get
/api/notification/sse、sse/online-count
/api/im/online-count、/api/im/ws
```
- 其余路径必须 `Authorization: Bearer {accessToken}`。
- 校验通过后网关向下游注入 `X-User-Id`（前端不需要自己传）。
- token 需 `type=access`；黑名单（注销/被踢）会拒绝。
- 单设备登录：后登录踢旧设备。

## 四、⚠️ HMAC 签名（前端 P0 必做，当前前端缺失！）

`HmacSignatureFilter` 是 `@Component GlobalFilter`，**无条件生效**。所有**非 HMAC 白名单**的请求必须带签名，否则返回 `403 签名校验失败`。

### 需要签名的（写操作，前端必须实现）
`/api/note/publish`、`/api/cart/**`(写)、`/api/order/create`、`/api/social/like|favorite|follow`(写)、
`/api/coupon/claim` 等——**只要不在 hmac-white-list 里就要签名**。

### HMAC 白名单（无需签名）
```
/api/user/auth/captcha|register|login|refresh|logout
/api/user/*/info、/api/user/me、/api/user/me/password、/api/user/block/**、/api/user/address/**
/api/note/detail/**、/api/note/user/**、/api/comment/**、/api/counter/**
/api/social/following/**、follower/**
（其余 /api/social/** 写操作如 like/favorite/follow 需要签名）
```

### 签名算法（从前端角度）
```
1. 登录时拿到 hmacSecret（TokenResponse.hmacSecret），前端必须保存（当前 authStore 丢弃了！）
2. 每个写请求生成：
   timestamp = 当前毫秒时间戳
   nonce     = 随机唯一串（防重放）
   signStr   = METHOD + path + timestamp + nonce      // 注意：path 不含 query，含 /api 前缀
   X-Signature = Base64( HMAC-SHA256( signStr, hmacSecret ) )
3. 请求头带：X-Timestamp、X-Nonce、X-Signature
```
- 时间戳容差有限（约几十秒），不要缓存旧签名。
- **当前前端 `client.ts` 未实现签名，authStore 未保存 hmacSecret** → 所有写操作（点赞/收藏/关注/加购/下单/发布）都会 403。
  **这是完善前端的第一步必改项。**

## 五、限流
- 网关/各服务有 `@RateLimit`，超限返回 429「请求频率过高」。前端应捕获 429 并提示稍后重试。

## 六、前端接入注意汇总（网关相关）
1. **实现 HMAC 签名**（P0）：保存 hmacSecret + 拦截器对非白名单写请求加 `X-Timestamp/X-Nonce/X-Signature`。
2. 401 → token 失效跳登录；403 → 可能是签名缺失/不匹配（需排查签名实现）；429 → 限流提示。
3. 公开读接口走白名单，无需 token/签名。
4. 不要直接调内部接口（`X-Internal-Call`/`X-Admin-Call`，前端无权限）。
