# gateway 测试用例 01 — 鉴权与白名单

> 依据：`business-analysis/my-xhs-gateway/` 源码事实 + `application.yml` white-list/hmac-white-list。
> 执行要求：逐条记录 实际请求 / 预期 / 实际结果 / 下游证据，不得用"服务健康"代替。

## 测试目标
验证 JWT 鉴权入口、白名单放行、鉴权边界（无/非法/过期 token、Header 注入与清理）。

## 用例清单

| # | 用例 | 请求构造 | 预期 | 状态 |
|---|------|----------|------|------|
| A1 | 白名单放行：登录 | POST /api/user/auth/login（无 token） | 放行到 user，200 | ⬜ |
| A2 | 白名单放行：验证码 | GET /api/user/auth/captcha（无 token） | 放行，200 | ⬜ |
| A3 | 白名单放行：商品公开详情 | GET /api/product/spu/1（无 token） | 放行（spu 详情公开），200 | ⬜ |
| A4 | 白名单放行：笔记详情 | GET /api/note/detail/{id}（无 token） | 放行，200 | ⬜ |
| A5 | 非白名单无 token 拒绝 | GET /api/order/list（无 token） | 401，不进下游 | ⬜ |
| A6 | 非白名单非法 token 拒绝 | GET /api/order/list + Authorization: Bearer invalid | 401 | ⬜ |
| A7 | 非白名单过期 token | 构造过期 JWT 调 /api/order/list | 401/403 | ⬜ |
| A8 | 合法 token 通过 + X-User-Id 注入 | 登录拿 token 调 /api/order/list | 200，下游收到 X-User-Id=10001 | ⬜ |
| A9 | 鉴权通过后 X-User-Role 注入/清理 | token 含 role + 调受 role 影响的接口 | 下游 X-User-Role 来自 JWT（无残留） | ⬜ |
| A10 | HMAC 白名单接口免签名 | 白名单写接口（登录）不带签名 | 放行 | ⬜ |
| A11 | 非 HMAC 白名单写接口需签名 | POST /api/order/create 不带 HMAC | 拒绝（HMAC 校验失败） | ⬜ |
| A12 | 方法级白名单边界 | GET 白名单路径但 POST（如 /api/note/detail POST） | 按 method 判定，非白名单则鉴权 | ⬜ |

## 下游证据
- gateway 日志 RequestLogFilter `>>>`/`<<<`（是否到达下游 + 状态码）
- 下游（user/order/product）ACCESS 日志（是否真正到达服务）
- 未到达下游 = 被 gateway 拦截（正确）；到达且 200 = 白名单/鉴权正确

## 已实测结果（执行时更新）
- A1/A2：登录/验证码白名单放行 ✅（运行态复核已验证）
- A5：无 token 调订单 → 401 ✅
- A8：登录后 token 调订单接口正常，X-User-Id 注入 ✅

## 覆盖对账
- 白名单来源：application.yml white-list（认证/公开读接口）
- HMAC 白名单独立配置（hmac-white-list），JWT 与 HMAC 是两套独立维度
- 本文件覆盖 gateway 鉴权入口；路由/超时见 02，HMAC/body 见 03
