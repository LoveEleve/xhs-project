# gateway 测试用例 02 — 路由与超时

> 依据：GatewayConfig 路由、GlobalExceptionHandler 异常映射、超时配置。

## 测试目标
验证路由匹配、下游超时/连接拒绝的响应映射、异常包装。

## 用例清单

| # | 用例 | 请求构造 | 预期 | 状态 |
|---|------|----------|------|------|
| B1 | 有效路由转发 | 正常 token 调 /api/product/spu/1 | 200，路由到 product | ⬜ |
| B2 | 未知路径 | GET /api/nonexistent | 404 映射 | ⬜ |
| B3 | 下游超时映射 | 调会超时的下游接口（模拟慢下游） | 503/504 沿 cause 链映射 | ⬜ |
| B4 | 下游连接拒绝 | 调未启动服务端口 | 503（连接异常映射） | ⬜ |
| B5 | 路由到 AI 服务（若配置） | AI 路由路径 | 按配置路由/拒绝 | ⬜ |
| B6 | SSE/WebSocket 长连接路由 | /api/notification/sse | 放行 + 不超时中断 | ⬜ |

## 下游证据
- gateway RequestLogFilter 状态码（200/404/503/504）
- GlobalExceptionHandler 是否按 cause 链识别（连接/超时/路由）
- 下游服务日志（请求是否到达、耗时）

## 已实测结果
- B1：商品路由 200 ✅（运行态复核）
- B6：SSE 端点白名单 ✅（未真连 SSE）

## 覆盖对账
- 超时配置：connect/read timeout（feign/httpclient）
- 异常映射：GlobalExceptionHandler cause 链（已修复 T-xxx）
