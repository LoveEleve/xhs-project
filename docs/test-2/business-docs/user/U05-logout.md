# U05: 登出 — POST /api/user/auth/logout

## § 源码分析

- **Controller**: `AuthController.java:61` → `@PostMapping("/logout")`, 参数 `@RequestHeader("Authorization")` + `@RequestParam("refreshToken", required=false)`
- **Service**: `TokenService.java:170` → `logout()`
  - 从Authorization Header提取accessToken → 解析jti, userId
  - accessToken入黑名单 `USER_TOKEN_BLACKLIST + jti` (TTL=剩余有效期)
  - refreshToken(如有)入黑名单
  - 删Redis: `USER_TOKEN_ACCESS + userId`, `USER_TOKEN_REFRESH + userId`, `USER_HMAC_SECRET + userId`
- **下游**: Redis Token黑名单 + 3个Token键DELETE

## § 业务逻辑

提交Authorization Header(accessToken) + refreshToken(可选) → 解析JWT获取jti和userId → accessToken写入黑名单(TTL=剩余有效期) → refreshToken同入黑名单 → 删除Redis中该用户的3个Token键 → 返回success。之后该用户任何操作401，需重新登录。

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已有登录Token | `cat /tmp/test_token.txt` 非空 | 无Token可登出 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -i POST /auth/logout -H "Authorization: Bearer $TOKEN"` | 200, success=true |
| Redis | `r.exists('myxhs:user:token:blacklist:{jti}')` | 1 (已入黑名单) |
| Redis | `r.exists('myxhs:user:token:access:{userId}')` | 0 (已删除) |
| 反验证 | `curl /api/user/me -H "Authorization: Bearer $TOKEN"` | 401 (Token已失效) |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | RT < 100ms? | ✅ |
| 安全 | 黑名单TTL=剩余有效期(过期自动清理) | ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -i -X POST http://localhost:19000/api/user/auth/logout \
  -H "Authorization: Bearer $TOKEN"

# 验证Token已失效
curl -s -o /dev/null -w "%{http_code}" http://localhost:19000/api/user/me \
  -H "Authorization: Bearer $TOKEN"
# 预期: 401
```

## § ASCII流转图

```
curl POST /auth/logout + Authorization: Bearer {accessToken}
  → Gateway → my-xhs-user:19001 AuthController.logout()
    → TokenService.logout()
      → 解析accessToken → jti, userId
      → Redis SET myxhs:user:token:blacklist:{jti} TTL=剩余有效期
      → (如有refreshToken) Redis SET blacklist:{jti_refresh}
      → Redis DEL myxhs:user:token:access:{userId}
      → Redis DEL myxhs:user:token:refresh:{userId}
      → Redis DEL myxhs:user:hmac:secret:{userId}
    → 返回 {success:true}
```
