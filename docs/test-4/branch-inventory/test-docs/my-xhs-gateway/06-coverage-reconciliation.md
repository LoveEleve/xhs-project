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
| ApiVersionFilter | ✅ | ✅ | v2/v9/无版本均200；非v1记录日志+存exchange属性，降级不拒绝（实测） |

## 覆盖结论
- 源码：gateway 核心 filter 已全覆盖分析
- 运行态：鉴权/白名单/无 body/路由/日志/HMAC签名/限流触发/灰度Header/API版本 全部实测通过

## 未覆盖/待实测原因
- 无（HMAC 5场景、限流40202、灰度G-L3-05、API版本均已在专项轮实测）
- 唯一待真实场景：多版本实例（v2 实例）并存时的 LoadBalancer 实例过滤，当前部署无 v2 实例，仅验证解析/日志/降级

## 测试要求
- 逐条记录 实际请求/预期/实际/下游证据（见 01-05）
- 不得用"服务健康"替代业务接口验证
