---
iteration: ITER-015
status: confirmed
created: 2026-10-19
updated: 2026-10-19
---

# 测试用例: gateway 生产化收尾

| TC | 归属 | 期望 | 自动化 |
|----|------|------|--------|
| TC-1 | AC-1 | HMAC 关闭：BodyCache 直接放行（socket 不读 body） | 是 |
| TC-2 | AC-1 | HMAC 开启：POST body 缓存 + 下游可重读 | 是 |
| TC-3 | AC-1 | multipart 跳过 / Content-Length>1MB → 413 | 是 |
| TC-4 | AC-2 | 灰度/版本过滤器零残留（grep/编译） | 是 |
| TC-5 | AC-3 | 空/演示 JWT 密钥 → 启动拒绝 | 是 |
| TC-6 | AC-4 | hmacSecret 零残留（grep） | 是 |
| TC-7~15 | 各 AC | Auth（白名单/401×4/503/注入）+ HMAC（关闭/缺头/过期/密钥缺失/重放/通过） | 是 |
| TC-16 | AC-5 | 十五模块全绿 + §8/材料 | 是 |
