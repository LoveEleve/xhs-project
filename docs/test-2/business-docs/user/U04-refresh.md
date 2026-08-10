# U04: Token刷新 — POST /api/user/auth/refresh

## § 源码分析

- **Controller**: `AuthController.java:53` → `@PostMapping("/refresh")`, 参数 `@RequestParam("refreshToken")`
- **Service**: `TokenService.java:100` → `refreshToken()`
  - 解析refreshToken → 提取jti, userId
  - Redisson锁 `TOKEN_REFRESH_LOCK + jti` — 防并发刷新
  - 二次查黑名单 `USER_TOKEN_BLACKLIST + jti` — 防已登出刷新
  - 校验Redis中存的refreshToken值与传入一致(L167-170) — 防伪造
  - 旧access+refresh入黑名单(`Redis SET...TTL=Token剩余有效期`)
  - 删Redis旧Token键
  - 重新生成access(30min)+refresh(7d)
- **下游**: Redis `USER_TOKEN_ACCESS/REFRESH/BLACKLIST/HMAC_SECRET`

## § 业务逻辑

提交refreshToken → 解析JWT获取jti → Redisson分布式锁防并发 → 查黑名单防已登出刷新 → 校验Redis存的refreshToken与传入一致 → 旧Token入黑名单 → 删旧Redis Token键 → 生成新Token对 → 写Redis → 返回新accessToken+refreshToken

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已有登录Token | `cat /tmp/test_token.txt` 非空 | 无refreshToken可用 |
| Redis可连 | `python3 -c "r.ping()"` | 无法校验refreshToken |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -i POST /refresh?refreshToken=xxx` | 200, 新accessToken, 新refreshToken |
| Redis | `r.exists('myxhs:user:token:blacklist:{旧jti}')` | 1 (旧Token已入黑名单) |
| Redis | `r.get('myxhs:user:token:access:{userId}')` | 新Token值, TTL≈1800 |
| JWT | `base64 decode 新accessToken payload` | jti更新(非旧jti) |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | RT < 200ms? | ✅ |
| 微服务 | 无Feign | ✅ |
| 并发 | Redisson TOKEN_REFRESH_LOCK+jti | ✅ |
| 安全 | 黑名单二次校验+refreshToken值比对 | ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
REFRESH=$(python3 -c "
import json,base64
payload=json.loads(base64.urlsafe_b64decode('$TOKEN'.split('.')[1]+'=='))
print(payload.get('refresh',''))
")
curl -s -i -X POST "http://localhost:19000/api/user/auth/refresh?refreshToken=$REFRESH"

# 新Token保存
NEW_TOKEN=$(上述响应 | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['accessToken'])")
echo "$NEW_TOKEN" > /tmp/test_token.txt
```

## § ASCII流转图

```
curl POST /auth/refresh?refreshToken=xxx
  → Gateway (JWT not required for /auth/**)
    → my-xhs-user:19001 AuthController.refreshToken()
      → TokenService.refreshToken()
        → 解析refreshToken → 获取jti, userId
        → Redisson RLock TOKEN_REFRESH_LOCK+{jti}
        → Redis EXISTS myxhs:user:token:blacklist:{jti} → 二次校验
        → Redis GET myxhs:user:token:refresh:{userId} → 值比对
        → Redis SET myxhs:user:token:blacklist:{旧jti} TTL=剩余有效期
        → Redis DEL myxhs:user:token:access:{userId}
        → Redis DEL myxhs:user:token:refresh:{userId}
        → 重新JWT生成 → Redis SET新Token对
      → 返回 {accessToken, refreshToken}
```
