---
iteration: ITER-015
status: confirmed
created: 2026-10-19
updated: 2026-10-19
---

# 深度审查: C-015（gateway 生产化收尾）

## 审查清单（12 项）

### A. 核心修正
- **A1 F1 BodyCache 短路** ✅ —— `hmac-enabled=false` 直接放行（原无条件缓冲所有写请求：≤1MB 内存 + >1MB 413 副作用）；开启路径回归锁定（缓存/装饰/多 part/413）
- **A2 F2 死路径移除** ✅ —— Gray/ApiVersion 两过滤器删除（属性无消费者 + 10% 日志噪音）；TrafficColoring 头透传不受影响（独立过滤器）
- **A3 F3 fail-closed** ✅ —— 空/演示密钥启动拒绝（对齐 im；部署环境已设 JWT_SECRET——否则 im 无法启动，无部署风险）
- **A4 F4 死配置** ✅ —— hmacSecret 字段 + application.yml + 部署 nacos 三处移除

### B. 语义保全
- **B1 失败语义** ✅ —— 黑名单 fail-closed 503 / HMAC 密钥 fail-closed 403 / nonce fail-open（既有不改）
- **B2 顺序模型** ✅ —— 日志(+100) → 鉴权(+1000) → BodyCache(+1100) → HMAC(+1500) → 限流(+2500)（灰度/版本移除后的最终链）
- **B3 限流** ✅ —— Sentinel 路由级 + Nacos 源 + 30s 真空兜底 + T-106 双 BlockHandler（未动）

### C. 工程与证据
- **C1 测试首建** ✅ —— 20 用例 / 3 类（Auth 8 / BodyCache 5 / HMAC 7）；mock exchange + Mono.block()（不引 reactor-test）
- **C2 十五模块复跑** ✅ —— **723 全绿**（gateway 首纳）
- **C3 文档同步** ✅ —— §8 + 17-网关材料

## 发现汇总（🟡 4 处，均已修复）

| # | 发现 | 类型 | 修复 |
|---|------|------|------|
| F1 | BodyCache 无条件缓冲（纯开销 + 1MB 上限副作用） | 性能 | hmac-enabled 短路 |
| F2 | 灰度/版本死路径 | 死代码 | 移除 |
| F3 | JWT 密钥缺 fail-closed（演示密钥可启动） | 安全 | 启动拒绝 |
| F4 | hmacSecret 死配置 | 死配置 | 三处移除 |

## 结论

12 项全部闭环。gateway 以"轻扫"口径收尾：4 处收口级修正 + 测试首建 + 文档同步。证据：十五模块 **723 全绿**。
