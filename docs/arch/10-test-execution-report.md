# my-xhs 测试执行报告

> **执行时间**: 2026-07-12
> **测试环境**: 网关 localhost:19000，中间件 21.130.247.89
> **测试文档**: 基于 09-curl-test-plan.md v5.0
> **重要发现**: 直连服务需 `X-User-Id` header；验证码从日志提取（临时方案）

---

## 第1组: 基础连通性测试

### 1.1 网关健康检查 ✅

```bash
curl -s http://localhost:19000/actuator/health | jq '.status, .components.redis.status'
```

**响应**: `"UP"`, Redis `"UP"` (v7.4.9)。15 个服务全部在 Nacos 注册。

### 1.2 网关路由验证 ✅

```bash
# 直连 vs 网关
curl -s http://localhost:19001/api/user/auth/captcha | jq '.code'   # 200
curl -s http://localhost:19000/api/user/auth/captcha | jq '.code'   # 200
```

**结论**: 直连和网关返回一致，路由正常。

---

## 第2组: 用户认证测试

### 2.1 获取验证码 ✅

```bash
curl -s http://127.0.0.1:19001/api/user/auth/captcha | jq '{code, captchaKey: .data.captchaKey}'
```

**响应**: `code=200`, `captchaKey` 非空, `captchaImage` 为 Base64 图片

### 2.2 注册 ⏭️

testuser 已在 `test-data-init.sql` 中预置 (id=10001)，跳过。

### 2.3 用户登录 ✅

```bash
curl -s -X POST http://127.0.0.1:19001/api/user/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"Test@123456","captchaKey":"xxx","captchaCode":"XXXX"}'
```

**响应**: `code=200`, `accessToken` 为有效 JWT, `refreshToken` 非空

### 2.4 获取当前用户信息 ✅

```bash
curl -s http://127.0.0.1:19001/api/user/me \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 10001"
```

**响应**: `code=200`, `id=10001`, `username="testuser"`, `nickname="测试用户A"`

**MySQL 验证**:
```
SELECT id, username, nickname FROM my_xhs_user.t_user WHERE id=10001;
→ 10001 | testuser | 测试用户A  ✅ API 与 DB 一致
```

### 2.5 Token 刷新 ✅

```bash
curl -s -X POST "http://127.0.0.1:19001/api/user/auth/refresh?refreshToken=$REFRESH_TOKEN"
```

**注意**: refreshToken 作为 `@RequestParam` 传递。**响应**: `code=200`, 返回新 accessToken

### 2.6 用户注销 ✅

```bash
curl -s -X POST http://127.0.0.1:19001/api/user/auth/logout -H "Authorization: Bearer $TOKEN"
```

**响应**: `code=200`

---

## 第3组: 用户信息管理

### 3.1 更新用户信息 ✅

```bash
curl -s -X PUT http://127.0.0.1:19001/api/user/me \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 10001" \
  -d '{"nickname":"测试用户A-NEW","signature":"这是我的签名"}'
```

**响应**: `code=200`

### 3.2 验证更新 ✅

```bash
curl -s http://127.0.0.1:19001/api/user/10001/info \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 10001"
```

**响应**: `code=200`, `nickname="测试用户A-NEW"`, `signature="这是我的签名"`
已恢复为"测试用户A"

---

## 28.3: 用户屏蔽功能（新增接口）

### 屏蔽用户 ✅

`POST /api/user/block/10002` → `code=200`

### 屏蔽列表 ✅

`GET /api/user/block/list` → `code=200`, `data: ["10002"]`

### 屏蔽自己（边界）✅

`POST /api/user/block/10001` → `code=400`, `message="不能屏蔽自己"`

### 取消屏蔽 ✅

`DELETE /api/user/block/10002` → `code=200`，列表恢复 `[]`

---

---

## 第4组: 内容/笔记测试

### 4.1 发布笔记 ✅ (Bug修复后)

**Bug修复**: 1) body在INSERT前设置; 2) getPayload()加@JsonIgnore避免循环引用

**curl**: `POST /api/note/publish`
**响应**: `code=200`，MySQL `t_note` 写入成功 (id=2076147855673843713)

### 4.2 笔记详情 ✅

**curl**: `GET /api/note/detail/{id}`
**响应**: `code=200`, `title="测试笔记-title"`, `content="这是测试内容"` — 与DB一致

### 4.3 我的笔记列表 ✅

**curl**: `GET /api/note/my?page=1&size=5`
**响应**: `code=200`, count=1

### 4.4 用户笔记列表 ✅

**curl**: `GET /api/note/user/{userId}?page=1&size=5`
**响应**: `code=200`, count=1

---

## 第5组: 评论测试

### 5.1 发表评论 ✅

**curl**: `POST /api/comment`，MySQL写入 (id=2076148108451962882)

### 5.2 评论列表 ✅

**curl**: `GET /api/comment/page/{noteId}`
**响应**: `code=200`, count=1

### 5.3 评论计数 ✅

**curl**: `GET /api/comment/count/{noteId}`
**响应**: `code=200`, count=1

---

## 28.5: 笔记分享（新增接口）✅

**curl**: `POST /api/note/{id}/share`
**响应**: `code=200`, 多次调用均可

---

## 第6组: 社交互动测试

### Bug修复: JSON 序列化循环引用
8 个 Event 类 `getPayload()` 返回 `this` 导致 Jackson 无限递归，统一加 `@JsonIgnore` 修复。

### 6.1 点赞 ✅ | 6.2 取消点赞 ✅ | 6.3 收藏 ✅ | 6.4 取消收藏 ✅ | 6.5 关注 ✅ | 6.6 取关 ✅

**结论**: MQ 一直正常，之前误判为 MQ 不可达。

---

## 第7组: 购物车测试

### 7.1 加入购物车 ✅ | 7.2 列表 ✅ | 7.3 角标 ✅ | 7.4 清空购物车(新增) ✅

---

**测试进度**: 第1组 ✅ | 第2组 ✅ | 第3组 ✅ | 第4组 ✅ | 第5组 ✅ | 第6组 ⚠️(需MQ) | 第7组 ✅ | 28.3 ✅ | 28.5 ✅ | 28.2 ✅
