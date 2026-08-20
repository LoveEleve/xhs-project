# trust-boundary

## 1. 首版结论

`my-xhs` 的身份模型不是“每个服务自己鉴权”，而是：

1. **gateway** 负责外部请求的 JWT/HMAC 校验与身份注入
2. **common/GatewayAuthTrustFilter** 负责服务端口直连时的信任收紧
3. **业务服务** 普遍直接消费 `X-User-Id`，少数关键入口再叠加 `X-Internal-Call` / `X-Admin-Call`

这是一个典型的“网关建身份、服务消费身份、common 兜底收边界”的模型。

## 2. 信任边界分层

### A. 外部入口边界

入口在 `my-xhs-gateway`。

`GatewayAuthFilter` 的职责：

- 白名单放行
- 解析 Bearer JWT
- 校验 token 类型必须为 access
- 查 Redis 黑名单
- 用 `set()` 注入 `X-User-Id`
- 从 JWT claim 注入 `X-User-Role`
- 注入 `X-Trace-Id`

初步判断：这里的**伪造头覆盖策略是正确方向**，因为它显式用 `set()` 覆盖客户端传入的同名 header，降低了直接伪造 `X-User-Id` / `X-User-Role` 的风险。

### B. 服务端口直连边界

入口在各 Servlet 服务内的 `GatewayAuthTrustFilter`。

它的逻辑是：

1. 有合法 `X-Internal-Call` → 直接信任调用方携带的 `X-User-Id`
2. 否则尝试从 Bearer JWT 解析 `userId`，并覆盖请求头
3. 否则剥离伪造的 `X-User-Id`

这说明系统已经意识到：**服务端口可直连时，单纯信任 header 会形成水平越权**。

初步判断：这个过滤器是整个身份模型的关键安全补丁。

### C. 内部调用边界

内部服务调用大量依赖 `FeignInternalCallInterceptor` 自动带 `X-Internal-Call`。

含义是：

- 内部服务之间并不重新走完整用户鉴权
- 只要持有 `INTERNAL_TOKEN`，下游就信任来路
- 用户上下文则通过 `X-User-Id` 等 header 在内部横向传递

这是一种高效但脆弱的模型：**内部 token 一旦泄漏，很多服务都会把来路当作可信内部调用**。

### D. 管理端点边界

以 `my-xhs-user` 为例，`/api/user/internal/delete/{userId}` 依赖 `X-Admin-Call` 判定管理权限。

从代码注释看，设计预期是：

- 经过 gateway，需要登录 JWT
- 同时带 `X-Admin-Call`
- HMAC 白名单只负责免签，不负责放宽登录态

初步结论：管理端点是**双要素模型**（已登录 + 管理令牌），但服务端代码本身只显式校验 `X-Admin-Call`，登录态依赖 gateway 前置保证。

## 3. 当前模型的优点

### 3.1 优点一：外部伪造头风险有集中治理

`GatewayAuthFilter` 与 `GatewayAuthTrustFilter` 组合，已经在两层位置处理了 `X-User-Id` 伪造问题：

- 经过 gateway：直接覆盖
- 绕过 gateway 直连服务：JWT 覆盖或剥离

相比“控制器里手写校验 header”，这个设计更稳。

### 3.2 优点二：token 撤销链路已考虑 Redis 黑名单

从 `TokenService` 与 `GatewayAuthFilter` 看，注销、删号、改单密码都会试图把 token 加入黑名单，并在 gateway 层拦截。

这说明系统不是纯无状态 JWT，而是有“撤销后立即失效”的安全需求。

### 3.3 优点三：角色注入走 JWT claim，而非网关查库

`role` 写入 JWT，再由 gateway 注入 `X-User-Role`，降低了 gateway 查库和状态耦合。

对于 WebFlux 网关，这是合理的实现方向。

## 4. 当前模型的脆弱点

### 4.1 内部调用信任粒度较粗

`GatewayAuthTrustFilter` 只要看到 `X-Internal-Call == myxhs.internal.token` 就完全信任调用方。

这意味着：

- internal token 是全局高价值凭据
- 下游默认信任上游带来的用户上下文
- 若内部 token 泄漏，攻击面不是一个接口，而是整条内部调用链

这是首轮 review 需要重点验证的地方。

### 4.2 管理端点的“登录态依赖前置网关”是隐式约束

`UserController.deleteUser()` 只显式检查 `X-Admin-Call`，没有在服务内再核验用户角色或登录用户身份。

如果请求绕过 gateway 直达服务端口，且拿到了 admin token，服务内不会再做“当前用户是否有管理权限”的二次确认。

这不一定是 bug，但它是**强假设**，后续要确认其他管理端点是否一致。

### 4.3 role 的真源与生效时机是“登录态快照”

当前 role 从 `t_user.role` 写入 JWT claim，gateway 不查库。

这带来一个明确语义：

- role 变更不是实时生效
- 只有重新登录/刷新后才体现在后续请求中

这个设计本身没问题，但必须确认业务是否接受“权限变更存在 token 生命周期窗口”。

## 5. 首轮待验证问题

1. 各服务是否都接入了 `GatewayAuthTrustFilter`，还是存在漏网服务
2. 所有 internal/admin 端点是否都只靠共享 token，没有更细粒度授权
3. `X-User-Role` 是否像 `X-User-Id` 一样在服务端口直连时被统一收紧，还是仅靠 gateway 层注入
4. 是否存在某些关键写接口只信任 header，而不校验来路
5. token 撤销在所有关键路径上是否真正即时生效

## 6. 下一步

接下来应分别下钻：

- `token-lifecycle.md`：登录、刷新、注销、删号、改密的凭证生命周期
- `internal-endpoints.md`：internal/admin 端点清单与保护方式
- `role-model.md`：角色传递、角色生效时机、服务内兜底现状