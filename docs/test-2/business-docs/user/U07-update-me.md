# U07: 更新用户信息 — PUT /api/user/me

## § 源码分析

- **Controller**: `UserController.java:52` → `@PutMapping("/me")`, 参数 `@RequestHeader("X-User-Id")` + `@Valid @RequestBody UpdateUserRequest` (含avatar/phone/nickname等)
- **Service**: `UserService.java:229` → `updateUserInfo()`
  - 查MySQL `t_user` by userId
  - 手机号唯一性校验(`SELECT COUNT(*) WHERE phone=? AND id!=userId`)
  - `LambdaUpdate` 更新t_user字段
  - `delayDoubleDelete(USER_INFO + userId)` — 先删Redis缓存→更新DB→延迟再删(防并发读回填旧值)
  - 直查DB返回最新数据(不读缓存)
- **下游**: MySQL `t_user` UPDATE + Redis `USER_INFO` DEL + MQ `CACHE_EVICT_TOPIC` (延迟双删)

## § 业务逻辑

提交更新字段(nickname/avatar/phone等) → 校验手机号唯一 → LambdaUpdate写MySQL → 延迟双删Redis缓存(先删→DB更新→延迟再删) → 直查MySQL返回最新信息(确保不读过期缓存)

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `cat /tmp/test_token.txt` | 401 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X PUT /api/user/me -d '{"nickname":"新名"}'` | 200, 更新后的用户信息 |
| MySQL | `SELECT nickname FROM t_user WHERE id=?` | 新值 |
| Redis | `r.get('myxhs:user:info:{userId}')` 延迟后检查 | 缓存被清除或更新 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | DB直查返回 | ✅ |
| 并发 | LambdaUpdate乐观更新 | ✅ |
| 一致性 | delayDoubleDelete延迟双删 | ✅ |
| 安全 | phone唯一性校验 | ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -i -X PUT http://localhost:19000/api/user/me \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"nickname":"测试新昵称"}'
```

## § ASCII流转图

```
curl PUT /api/user/me + Authorization + Body {nickname,...}
  → Gateway → my-xhs-user:19001 UserController.updateCurrentUser()
    → UserService.updateUserInfo()
      → MySQL: SELECT * FROM t_user WHERE id=?
      → MySQL: SELECT COUNT(*) FROM t_user WHERE phone=? AND id!=?
      → Redis DEL myxhs:user:info:{userId}  (延迟双删-第1次)
      → MySQL: UPDATE t_user SET ... WHERE id=?
      → delayDoubleDelete → MQ CACHE_EVICT_TOPIC  (延迟双删-第2次)
      → MySQL: SELECT * FROM t_user WHERE id=?  (直查DB)
      → 返回更新后数据
```
