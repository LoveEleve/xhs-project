# 04-auth-and-identity

## 目标

审查整个系统的身份建立、透传、信任与撤销链路。

## 重点服务

- `my-xhs-gateway`
- `my-xhs-user`
- `my-xhs-common`
- 所有带 internal/admin 入口的服务

## 重点问题

1. JWT 与 HMAC 的边界是否清晰
2. 网关注入的 `X-User-Id`、`X-User-Role`、`X-Trace-Id` 是否可被伪造或覆盖
3. 服务内是否对关键权限做二次兜底
4. token 撤销、逻辑删号、封禁是否能穿透到实际访问路径
5. internal/admin 接口是否只依赖 header 而无额外防护
6. 角色、用户、匿名态三者切换是否有漏洞

## 预期证据

- gateway filter
- user token 生成与刷新逻辑
- internal/admin controller
- 关键服务的鉴权兜底代码
- 配置白名单与免签路径

## 初步产出建议

- `trust-boundary.md`
- `token-lifecycle.md`
- `internal-endpoints.md`
- `role-model.md`