---
iteration: ITER-015
status: confirmed
created: 2026-10-19
updated: 2026-10-19
---

# 测试报告: gateway 生产化收尾（轻扫）

## 执行摘要

- 执行时间: 2026-10-19（收口级：① 核心修正 → ② 测试首建 → ③ 收口）
- 命令: 十五模块 `mvn -o ... -am test`
- 结果: ✅ **十五模块 723 全绿**（common 100 / product 35 / coupon 24 / order 132 / inventory 33 / payment 73 / search 66 / cart 33 / counter 15 / analytics 29 / content 17 / notification 57 / im 50 / home 39 / **gateway 20（首纳）**）

## 用例执行结果（要点）

| TC | 归属 | 结果 | 断言/证据 |
|----|------|------|----------|
| TC-1 | AC-1 | 🟢 | `BodyCacheFilterTest#hmacDisabledShortCircuits`：HMAC 关闭直接放行（不缓存） |
| TC-2/3 | AC-1 | 🟢 | 开启时 POST 缓存 + 装饰器重读；multipart 跳过；Content-Length>1MB → 413 |
| TC-4 | AC-2 | 🟢 | GrayRouteFilter/ApiVersionFilter 零残留（编译/grep） |
| TC-5 | AC-3 | 🟢 | `GatewayAuthFilterTest#failClosedOnInsecureSecret`：空/演示密钥 → 拒绝启动 |
| TC-6 | AC-4 | 🟢 | hmacSecret 零残留（grep） |
| TC-7~12 | AC-5 | 🟢 | Auth：白名单放行/缺头 401/类型错 401/黑名单 401/Redis 503/sub 空 401/注入 set 覆盖（伪造 X-User-Id=666 被覆盖为 9） |
| TC-13~15 | AC-5 | 🟢 | HMAC：关闭透传/缺头 403/坏+过期时间戳 403/无身份 403/密钥缺失 403/Redis fail-closed 403/验签通过 + nonce 重放 403 |
| TC-16 | AC-5 | 🟢 | 十五模块 **723 全绿** |

## 收口清单

- [x] 十五模块全量回归 723 全绿（gateway 20 首纳）
- [x] §8 回写（12-gateway）
- [x] 面试材料同步（17-网关与HMAC：灰度/版本移除、hmac-secret 移除、BodyCache 短路、fail-closed）
- [x] change 卡 done
- [x] 运行时验证按项目规则后置
