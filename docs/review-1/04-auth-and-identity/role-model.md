# role-model

## 1. 当前设计

角色数据当前来自 `t_user.role`，在登录/刷新时写入 JWT claim，再由 gateway 注入 `X-User-Role`。

链路为：

```text
t_user.role
  -> TokenService.generateTokenPair()
  -> JWT claim(role)
  -> GatewayAuthFilter
  -> X-User-Role
  -> 下游服务
```

## 2. 当前优点

1. gateway 无需查库
2. WebFlux 网关保持无状态
3. 角色和身份由同一份 token 绑定下发

## 3. 当前注意点

### 3.1 生效时机是会话级快照

role 不是实时查库，而是登录/刷新时快照进 token。

意味着：

- 用户角色变更后，不会立刻体现在既有 access token 上
- 是否允许这段窗口，取决于业务安全要求

### 3.2 服务端口直连的角色头收紧尚未看到统一实现

当前 `GatewayAuthTrustFilter` 只处理 `X-User-Id`，没有看到统一的 `X-User-Role` 覆盖或剥离逻辑。

这意味着一旦下游服务开始信任 `X-User-Role`：

- 经过 gateway 的请求由 gateway 覆盖，问题较小
- 直连服务端口的请求，如果服务端自己不校验来路，存在伪造角色头的空间

首轮判断：这是一个需要重点验证的设计缺口。

## 4. 后续验证重点

1. 现在哪些服务已经消费 `X-User-Role`
2. 这些服务是否还有额外的来路校验
3. 角色边界是否只在 gateway 建立，还是在服务内也有兜底
4. 是否需要把 `X-User-Role` 纳入 trust filter 的统一收紧范围