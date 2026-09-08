# my-xhs-gateway 测试用例矩阵（L1-L4）

> 详细用例见 01-auth-and-whitelist.md ~ 06-coverage-reconciliation.md，此表为汇总矩阵。

## L1 鉴权与路由

| ID | 用例 | 请求 | 预期 | 状态 |
|---|---|---|---|---|
| G-L1-01 | 白名单放行 | /api/user/auth/login 无token | 放行200 | ✅ |
| G-L1-02 | 非白名单无token | /api/order/list | 401 | ✅ |
| G-L1-03 | 非法token | 伪造 Bearer | 401 | ✅ |
| G-L1-04 | 合法token+X-User-Id注入 | 登录后调接口 | 200+下游X-User-Id | ✅ |
| G-L1-05 | 服务路由 | 各 /api/{service}/** | 路由到正确服务 | ✅ |
| G-L1-06 | 未知路径 | /api/nonexistent | 404 | ✅ |

## L2 数据与下游

| ID | 验证点 | 证据 | 状态 |
|---|---|---|---|
| G-L2-01 | 下游服务到达 | 下游 ACCESS 日志 | ✅ |
| G-L2-02 | 无body POST/DELETE | block/logout | ✅ 修复 |
| G-L2-03 | body>1MB | 超大POST | 413 | ⬜ |

## L3 质量

| ID | 用例 | 预期 | 状态 |
|---|---|---|---|
| G-L3-01 | 限流触发 | 高频 | 429/限流 | ⬜ |
| G-L3-02 | HMAC 签名 | 合法/缺失/篡改 | 放行/拒绝 | ⬜ |
| G-L3-03 | nonce 重放 | 重复签名 | 拒绝 | ⬜ |
| G-L3-04 | 下游超时映射 | 慢下游 | 503/504 | ⬜ |
| G-L3-05 | 灰度 Header | X-Gray-Tag | 透传/路由 | ⬜ |
| G-L3-06 | 异常 cause 映射 | 连接拒绝/超时 | 正确状态码 | ✅ |

## L4 可观测

| ID | 验证点 | 状态 |
|---|---|---|
| G-L4-01 | RequestLog 访问日志 | ✅ |
| G-L4-02 | TraceId 透传下游 | ✅ |
| G-L4-03 | Prometheus 指标 | ⬜ |
| G-L4-04 | Actuator health | ✅ |

## 已实测
- G-L1-01~06、G-L2-01/02、G-L3-06、G-L4-01/02/04 ✅
- 限流触发/HMAC/重放/超时映射/灰度/413/指标 待专项（需压测/签名客户端）
