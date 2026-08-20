# F-002 管理端点对直连服务端口只校验 X-Admin-Call，共享管理令牌一旦泄漏可直接执行高危操作

## 严重度

High

## 涉及服务

- `my-xhs-common`
- `my-xhs-user`
- `my-xhs-inventory`
- `my-xhs-search`
- 以及其他采用同模式的管理端点

## 涉及文件

- `my-xhs-common/src/main/java/com/myxhs/common/web/GatewayAuthTrustFilter.java:77`
- `my-xhs-user/src/main/java/com/myxhs/user/controller/UserController.java:51`
- `my-xhs-inventory/src/main/java/com/myxhs/inventory/controller/InventoryController.java:57`
- `my-xhs-inventory/src/main/java/com/myxhs/inventory/controller/InventoryController.java:147`
- `my-xhs-search/src/main/java/com/myxhs/search/controller/SearchController.java:164`
- `my-xhs-search/src/main/java/com/myxhs/search/controller/SearchController.java:190`

## 现象

多个管理端点在服务内只检查 `X-Admin-Call`，不在服务端再要求“当前请求必须有真实登录用户/真实管理员身份”。

这在“经 gateway 访问”时依赖前置 JWT 鉴权兜底；但**一旦绕过 gateway 直连服务端口，共享 admin token 本身就足以执行管理操作**。

## 触发条件

1. 攻击者拿到 `myxhs.admin.token`（环境变量、日志、脚本、配置泄漏等）
2. 请求直连业务服务端口，而不是走 gateway
3. 命中只在控制器里校验 `X-Admin-Call` 的管理端点

## 证据

1. `GatewayAuthTrustFilter` 在“无有效 JWT 且非内部调用”时只会剥离 `X-User-Id`，不会拦截或清理 `X-Admin-Call`：`my-xhs-common/.../GatewayAuthTrustFilter.java:77`。
2. user 删除用户端点只校验 `X-Admin-Call`：`my-xhs-user/.../UserController.java:51` 起，方法内仅 `isAdminCall(adminCall)`。
3. inventory 的高危管理端点同样只校验 `X-Admin-Call`，例如库存初始化 `my-xhs-inventory/.../InventoryController.java:57` 与全量对账 `:147`。
4. search 的热搜人工置顶/屏蔽也只校验 `X-Admin-Call`，例如 `my-xhs-search/.../SearchController.java:164`、`:190`。
5. 代码注释里多处写的是“经 gateway 需 JWT + X-Admin-Call”，但这种约束没有在服务内落地成强校验；一旦绕过 gateway，就退化为共享令牌单因子保护。

## 影响

1. admin token 一旦泄漏，攻击者无需再拥有任意用户 JWT，就可直接对服务端口执行管理动作。
2. 影响的不只是查询类接口，还包括删号、库存初始化、对账触发、热搜干预等高危写操作。
3. 因为 admin token 是共享凭据，无法区分是谁执行了管理操作，审计粒度也较差。

## 修复建议

1. 管理端点不要只依赖 `X-Admin-Call`；至少要同时要求真实登录态，并在服务端核验管理员角色或专门的管理主体。
2. 若保留 `X-Admin-Call`，应把它降级为“第二因子”或“服务间授权因子”，而不是唯一判据。
3. `GatewayAuthTrustFilter` 需要明确处理 `X-Admin-Call` / `X-User-Role` 这类高信任头，避免服务端口直连时默认放过。
4. 对外禁止直连 19001+ 服务端口，或至少在网络层做收口，不把安全完全压在应用约定上。

## 残余风险

即使补上服务内的登录态与角色校验，共享 admin token 仍然是高价值横向凭据；后续最好继续收敛为更细粒度、可审计的管理授权模型。

## 是否需要补充验证

需要。建议抽样验证：不经 gateway，直连 search 服务端口，只带 `X-Admin-Call` 调 `/api/search/hot/pin`，确认当前是否可成功执行。