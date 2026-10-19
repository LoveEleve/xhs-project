---
id: C-015
slug: gateway生产化收尾
status: done
created: 2026-10-19
updated: 2026-10-19
iteration: ITER-015
relations:
  relates_to:
    - slug: docs/学习/模块对照/12-gateway
      reason: §7 七条线索收口（灰度/版本死路径、HMAC 默认关闭、密钥 fail-closed）
  depends_on: []
---

# C-015 gateway 生产化收尾（Phase 4 收官 · 轻扫）

> **立项（2026-10-19）**：用户口径"快速收尾整个项目"。核心链路已由 §7 梳理把关，本轮只做**收口级修正**：
> **F1** BodyCacheFilter 在 HMAC 关闭时无条件缓冲所有写请求（纯开销 + 1MB 上限副作用）；
> **F2** 灰度/版本路由 = 死路径（属性无消费者 + 10% 用户日志噪音——与 im LB 同族）；
> **F3** 网关缺 JWT 密钥 fail-closed（im 有；演示密钥下可伪造全站 Token）；**F4** hmacSecret 死配置。
> **不展开**：权限/白名单治理（一嘴带过）；用户级限流与灰度 LB（记录演进）。

## 契约影响

- 无 REST/MQ 变更；移除 GrayRouteFilter/ApiVersionFilter（属性无消费者）；移除 hmacSecret 配置项
- 启动新增 fail-closed：`gateway.auth.secret` 为空/演示密钥 → 拒绝启动（与 im 一致）
- 测试：gateway 首次引入测试依赖 + 套件，回归 14→15 模块

## 测试策略

- 单测：AuthFilter（白名单/401/黑名单/Redis 503/类型/sub）、BodyCache（改后 HMAC 关闭短路 + 开启缓存 + multipart + 413）、HMAC（关闭透传/缺头/过期/密钥缺失/重放/通过）
- 无运行时
