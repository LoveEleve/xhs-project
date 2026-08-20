# token-lifecycle

## 1. 令牌生命周期概览

当前模型是：

1. 登录生成 `accessToken + refreshToken + hmacSecret`
2. access/refresh 均按 `userId` 存 Redis（单设备登录）
3. 刷新时校验 refresh token、黑名单、Redis 当前值、账号状态
4. 注销/删号/改密时把活跃 token 拉黑并删除 Redis 映射
5. gateway 对 access token 查黑名单，服务内直连则依赖 JWT 或 header 收紧

## 2. 登录阶段

`TokenService.generateTokenPair()` 做了几件关键事：

1. 生成 access token
2. 生成 refresh token
3. 若旧 access 存在，则加入黑名单
4. 把新 access / refresh 写入 Redis
5. 生成 per-session `hmacSecret` 写入 Redis，并返回给前端

这说明系统采用的是：

- **JWT + Redis 黑名单** 混合模型
- **单设备登录** 语义
- **前端签名 secret 随会话发放** 模式

## 3. 刷新阶段

`TokenService.refreshToken()` 的安全约束相对完整：

1. 只接受 type=refresh
2. 检查 refresh token 黑名单
3. 用 Redisson 锁防并发刷新
4. 校验 Redis 当前保存的 refresh token 是否与传入一致
5. 校验账号状态（禁用/删除禁止续期）
6. 将旧 refresh token 拉黑
7. 生成新 token 对

这条链路体现出一个很重要的设计选择：

- access 的即时可控主要靠 gateway 查黑名单
- refresh 的唯一性和单设备语义主要靠 Redis 当前值

## 4. 注销/删号/改密

### 注销

`logout()`：

- 把 access 拉黑
- 把 refresh 拉黑
- 删除 Redis 中该 userId 的 access/refresh/hmacSecret

### 删号

`revokeAllTokens()` + 逻辑删除：

- 取当前活跃 access/refresh
- 拉黑当前活跃 token
- 删除 Redis 映射与 hmac secret

### 改密

`invalidateUserCredentials()`：

- 拉黑当前活跃 access/refresh
- 删除 Redis 映射
- 删除 hmac secret

初步结论：这三类动作都已经把 **token 与 hmacSecret 一起失效** 作为安全闭环的一部分。

## 5. 首版判断

### 优点

1. refresh 并发刷新控制较完整
2. 单设备登录语义明确
3. HMAC secret 与会话生命周期绑定，而不是固定全局密钥
4. 删号/改密/注销都考虑了“凭证即时失效”

### 风险点

1. 运行时强依赖 Redis，尤其是黑名单和 hmacSecret
2. 黑名单、当前 token、hmacSecret 分散在多类 key，故障排查复杂
3. role 作为 claim 嵌在 token 中，权限变更不是实时收敛
4. 服务端口直连并不查黑名单，更多依赖 JWT + header 收紧模型

## 6. 本专题后续问题

1. access token 在绕过 gateway、直连服务端口时，是否仍会被黑名单阻断，还是仅靠“旧 token 不再刷新”间接失效
2. refresh 覆盖单设备登录后，旧 access 在 30 分钟内是否完全被 gateway 拦住，是否存在旁路
3. HMAC secret 是否在所有重新登录、改密、删号、封禁路径都能一致失效
4. 账号禁用在“已持有 access token 的请求”上，是否即时生效，还是要等 access 过期