# internal-endpoints

## 1. 盘点范围

首轮扫描了以下信任头与端点：

- `X-Internal-Call`
- `X-Admin-Call`
- `X-User-Id`
- `Authorization`

涉及 controller：

- analytics
- cart
- content
- counter
- coupon
- im
- inventory
- notification
- order
- payment
- product
- search
- user

## 2. 端点分组

### 内部业务调用

典型端点包括：

- inventory：`/preDeduct`、`/confirm`、`/release`、`/refund-restore`、`/tcc/*`
- order：`/pay-success`、`/pay-fail`、`/refund-success`、`/refund-fail`、`/pay-amount`、`/status`
- payment：`/callback/*`、`/refund-callback/*`、`/status/*`
- coupon：`/discount/*`、`/use`、`/return`
- user/content/product：`/internal/*`、`/sku/batch`、`/comment/internal/*`

这些端点大多在 controller 内显式校验 `X-Internal-Call`，同时公共 `GatewayAuthTrustFilter` 会在内部调用场景信任调用方携带的 `X-User-Id`。

### 管理操作

典型端点包括：

- user：删除用户
- inventory：初始化、重初始化、库存对账
- cart：购物车对账
- coupon：模板创建、模板状态修改
- product：SPU/SKU 管理
- search：索引重建、热搜置顶/屏蔽
- counter/analytics：计数与关系修复
- notification/im：在线数查询
- recommend：手动计算

这些端点目前主要依赖 `X-Admin-Call`，很多方法没有在服务内同时校验当前登录用户或角色。

## 3. 已确认问题

### 3.1 撤销 token 的直连旁路

已写入 `02-findings/critical/F-001-revoked-token-bypass-on-direct-service-ports.md`。

根因是：gateway 查黑名单，但 `GatewayAuthTrustFilter` 的直连 JWT 分支不查黑名单。

### 3.2 管理 token 的直连旁路

已写入 `02-findings/high/F-002-admin-token-alone-can-operate-direct-service-ports.md`。

根因是：服务内很多管理方法只校验 `X-Admin-Call`，而公共直连过滤器不处理该 header。

## 4. 盘点后的统一模型

当前系统实际存在三种不同保护强度：

1. **gateway 外部请求**：JWT + gateway 黑名单 + 可能的 HMAC
2. **业务服务直连 + access JWT**：JWT 签名 + `X-User-Id` 覆盖，但不查 gateway 黑名单
3. **业务服务直连 + internal/admin token**：共享 header token，部分端点不要求用户 JWT

这三种模型的行为不一致，是本专题的核心结构风险。

## 5. 待补证据

1. 服务端口的网络暴露范围是否仅限内网
2. gateway 是否已经在网络层禁止外部直达 19001+
3. `myxhs.admin.token` / `myxhs.internal.token` 是否有轮换机制
4. 所有管理端点是否都有独立审计日志
5. `X-User-Role` 是否在 AI 团队后续代码中被服务端直接消费

## 6. 结论

本专题当前不应继续停留在“header 是否能伪造”的局部问题上，真正的边界问题是：

> 同一个 token/身份在 gateway 路径、直连服务路径、内部调用路径上的失效与授权语义不一致。

后续修复优先级应先收敛这三条路径，再谈细化角色模型。