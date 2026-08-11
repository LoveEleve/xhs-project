# U04-refresh — POST /api/user/auth/refresh
> 2026-08-10 | 链1 user | 认证端点

## L1 正常: ?refreshToken=xxx → 200, 新accessToken(len225)
- 异常: 无refreshToken → 40001 "缺少参数: refreshToken"

## 发现的问题
- 参数是 **query param** 非 JSON body(测试文档需注明), 首次用body发返回40001 → 换query后正常。非bug。

## 结果: ✅ 通过
