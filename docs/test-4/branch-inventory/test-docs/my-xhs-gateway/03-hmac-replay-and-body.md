# gateway 测试用例 03 — HMAC、重放与 body

> 依据：HmacSignatureFilter、BodyCacheFilter、hmac-white-list。

## 测试目标
验证 HMAC 签名校验、nonce 防重放、请求体缓存与超限处理。

## 用例清单

| # | 用例 | 请求构造 | 预期 | 状态 |
|---|------|----------|------|------|
| C1 | 合法 HMAC 签名写接口 | 带正确签名 POST /api/order/create | 放行 | ⬜ |
| C2 | 缺 HMAC 头写接口 | POST /api/order/create 无 X-Hmac-* | 拒绝 4xx | ⬜ |
| C3 | 错误签名 | 篡改 body 后原签名 | 拒绝（bodyHash 不匹配） | ⬜ |
| C4 | nonce 重复（重放） | 同一签名请求重复发送 | 第二次拒绝（nonce 已消费） | ⬜ |
| C5 | HMAC 白名单接口免签名 | 白名单写接口无签名 | 放行 | ⬜ |
| C6 | body >1MB | 超大 body POST | 413（BodyCacheFilter） | ⬜ |
| C7 | 无 body POST/PUT/DELETE | 空 body 写接口（block/logout） | 正常放行（defaultIfEmpty 修复） | ✅ |
| C8 | multipart 跳过缓存 | multipart 上传 | 放行（不缓存 body） | ⬜ |

## 下游证据
- HmacSignatureFilter 拒绝日志（原因：缺签名/bodyHash 不匹配/nonce 重复）
- BodyCacheFilter 413 日志（超限）
- 下游是否收到正确 body（缓存重建后一致）

## 已实测结果
- C7：无 body POST/DELETE 修复后正常 ✅（block 拉黑/取消/列表全通过）

## 覆盖对账
- HMAC nonce 先消费、XFF/流量标签可信边界 暂不改动（基础风险记录）
- multipart >1MB 上传场景未实测（需真实大文件）
