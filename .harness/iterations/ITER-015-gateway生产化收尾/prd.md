---
iteration: ITER-015
status: confirmed
created: 2026-10-19
updated: 2026-10-19
---

# PRD: gateway 生产化收尾（轻扫）

## 需求

| ID | 需求 | 优先级 |
|----|------|--------|
| REQ-1 | **F1** BodyCacheFilter 按 `hmac-enabled` 短路（HMAC 关闭时 body 无任何需求方——下游转发走原始流；原实现无条件缓冲所有写请求） | P0 |
| REQ-2 | **F2** 移除 GrayRouteFilter/ApiVersionFilter（死路径：exchange 属性无消费者；自动灰度 10% 只产生日志噪音） | P0 |
| REQ-3 | **F3** JWT 密钥 fail-closed（空/演示密钥拒绝启动——对齐 im；原实现演示密钥可启动 → 全站 Token 可伪造） | P0 |
| REQ-4 | **F4** 移除 hmacSecret 死配置（per-session secret 已取代全局密钥） | P1 |
| REQ-5 | **收口**：测试套件（≥15）+ 十五模块回归 + §8 回写（12-gateway）+ 17-网关与HMAC 材料同步 | P0 |

## 验收标准（AC）

| ID | 归属 | 验收标准 |
|----|------|---------|
| AC-1 | REQ-1 | HMAC 关闭时 BodyCache 直接放行（不读 body/不设 413）；开启时缓存 + multipart 跳过 + >1MB 413（单测） |
| AC-2 | REQ-2 | 两过滤器零残留（grep/编译）；TrafficColoring 头透传不受影响 |
| AC-3 | REQ-3 | 空/演示密钥 → IllegalStateException（单测） |
| AC-4 | REQ-4 | hmacSecret 字段/配置零残留（grep） |
| AC-5 | REQ-5 | 测试 ≥15 + 十五模块全绿 + §8/材料回写 |

## 记录（讲）

灰度/版本实例级 LB（VersionLoadBalancer 演进）；用户级限流（Sentinel param flow 演进）；HMAC 启用需前端 per-session secret 协议；黑名单键硬编码同步要求；白名单 ~81 条治理观察。
