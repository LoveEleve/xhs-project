# F-027 全链路测试脚本把“直连服务端口 + 伪造 X-User-Id”固化为默认用法

## 严重度

Medium

## 涉及文件

- `full-chain-test-v3.sh:35-49`
- `full-chain-test-v3.sh:42`
- `full-chain-test-v3.sh:98-387`
- 同类脚本：`full-chain-test.sh`、`full-chain-test-v2.sh`

## 现象

全链路测试脚本的默认模型不是“统一经 gateway 访问”，而是：

1. 直接请求各业务服务端口（19001~19016）
2. 主动附带 `Authorization` 和 `X-User-Id`
3. 在脚本里内置基础设施密码与测试账号

这会把“绕过 gateway 直连服务 + 手工构造身份头”变成团队默认实践。

## 证据

1. `call()` 默认追加 `-H "Authorization: Bearer $TOKEN" -H "X-User-Id: $XID"`：`full-chain-test-v3.sh:42`。
2. 脚本大量直接访问 `http://localhost:19001`、`19002`、`19011` 等业务端口，而不是统一走 `19000` 网关：`full-chain-test-v3.sh:98-387`。
3. 脚本内置 Redis 密码、测试账号：`full-chain-test-v3.sh:10-11`。

## 影响

1. 测试方式本身在规避系统真实的信任边界，容易掩盖 gateway 鉴权、HMAC、限流、header 注入的问题。
2. 团队成员会习惯用业务端口 + 伪造头排查问题，进一步放大 F-001/F-002 这类风险。
3. 一旦脚本被复制到其他环境，基础设施密码与直连方式会直接传播。

## 修复建议

1. 默认全链路测试应统一走 gateway，仅对“内部接口专项测试”保留单独脚本。
2. 普通业务脚本不应主动注入 `X-User-Id`，应只携带登录态，由 gateway 注入身份头。
3. 将 Redis 密码、测试账号改成环境变量或本地 secrets 文件。
4. 在脚本顶部明确标注：哪些请求是“故意绕过 gateway 的内部接口测试”。

## 是否需要补充验证

不需要额外验证；脚本本身已构成证据。