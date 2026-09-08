# gateway 测试用例 06 — 覆盖对账

> 依据：`project-directories/my-xhs-gateway/inventory.md` 与模块实际文件。

## 文件覆盖

| 文件 | 源码分析 | 运行态实测 | 说明 |
|------|:---:|:---:|------|
| GatewayAuthFilter | ✅ | ✅ | JWT 鉴权/白名单/Header 注入清理（01） |
| BodyCacheFilter | ✅ | ✅ | body 缓存/413/无 body 修复（03） |
| HmacSignatureFilter | ✅ | ⬜ | HMAC 校验/nonce（03 待实测） |
| RateLimitFilter | ✅ | ⬜ | Sentinel 限流/非法 QPS 保护（04 待实测） |
| TrafficColoringFilter | ✅ | ⬜ | 灰度 Header（04 待实测） |
| GrayRouteFilter | ✅ | ⬜ | 灰度路由（04 待实测） |
| GlobalExceptionHandler | ✅ | ✅ | 异常 cause 链映射（02 部分） |
| RequestLogFilter | ✅ | ✅ | 访问日志/traceId（05） |
| GatewayConfig | ✅ | ✅ | 路由注册（02 B1） |
| AuthProperties | ✅ | ✅ | 白名单配置来源（01） |
| ApiVersionFilter | ✅ | ⬜ | 版本 Header（未实测） |

## 覆盖结论
- 源码：gateway 核心 filter 已全覆盖分析
- 运行态：鉴权/白名单/无 body/路由/日志已实测；HMAC 签名链路、限流触发、灰度 Header 透传、API 版本待实测

## 未覆盖/待实测原因
- HMAC：需要签名算法构造客户端（测试客户端未实现签名逻辑）
- 限流：需要高频真实请求压测
- 灰度：需要多实例 + LoadBalancer 场景
- API 版本：需确认前端实际使用

## 测试要求
- 逐条记录 实际请求/预期/实际/下游证据（见 01-05）
- 不得用"服务健康"替代业务接口验证
