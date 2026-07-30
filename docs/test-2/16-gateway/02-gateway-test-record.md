# 16-gateway API 网关 — curl 测试记录

> 端口：19000 | 数据库：无 | 测试日期：2026-07-30

---

## 测试用例概览

| # | 场景 | 预期 | 结果 |
|:--:|------|:----:|:----:|
| 1 | 白名单路径直接放行 | 200 | ✅ |
| 2 | 非白名单路径无 Token | 401 | ✅ |
| 3 | JWT + HMAC 完整签名 | 200 | ✅ |
| 4 | 无 HMAC 签名 | 403 | ✅ |
| 5 | HMAC 签名不匹配 | 403 | ✅ |
| 6 | timestamp 超过 5 分钟 | 403 | ✅ |
| 7 | nonce 重复使用 | 第1次200，第2次403 | ✅ |

---

## 1. 白名单路径直接放行

```bash
curl -s "http://localhost:19000/api/user/10001/info"
```

**响应** ✅ L1：HTTP 200，返回用户信息。

`/api/user/*/info` 在 JWT 白名单中，Gateway 跳过鉴权直接路由到 my-xhs-user。

---

## 2. 非白名单路径无 JWT

```bash
curl -s -X POST "http://localhost:19000/api/im/ws/ticket" -H "X-User-Id: 10001"
```

**响应** ✅ L1：HTTP 401
```json
{"code":401,"message":"缺少认证信息","data":null}
```

`/api/im/ws/ticket` 不在白名单中，无 `Authorization` Header 被拦截。

---

## 3. JWT + HMAC 完整签名

```python
# 构造 JWT（HMAC-SHA256 签名）
token = jwt.encode({"sub":"10001","type":"access","exp":now+1800}, jwt_secret, "HS256")

# 构造 HMAC
sign_str = "POST" + "/api/im/ws/ticket" + timestamp + nonce
signature = base64(HmacSHA256(sign_str, hmac_secret))

# 请求
POST /api/im/ws/ticket
Authorization: Bearer {token}
X-Timestamp: {ts}
X-Nonce: {nonce}
X-Signature: {signature}
```

**响应** ✅ L1：HTTP 200，ticket 返回。

**ACCESS 日志** ✅ L2：traceId 正常，`[Gateway] >>>` 和 `[Gateway] <<<` 记录完整。

---

## 4. 无 HMAC 签名

```bash
curl -s -X POST "http://localhost:19000/api/im/ws/ticket" \
  -H "Authorization: Bearer {token}"
```

**响应** ✅ L1：HTTP 403
```json
{"code":403,"message":"签名校验失败：非公开接口必须携带 X-Timestamp、X-Nonce、X-Signature"}
```

---

## 5. HMAC 签名不匹配

```bash
curl -s -X POST "http://localhost:19000/api/im/ws/ticket" \
  -H "Authorization: Bearer {token}" \
  -H "X-Timestamp: {ts}" -H "X-Nonce: {nonce}" -H "X-Signature: FAKE"
```

**响应** ✅ L1：HTTP 403

---

## 6. timestamp 超 5 分钟

```python
old_ts = now - 360_000  # 6 分钟前
```

**响应** ✅ L1：HTTP 403

---

## 7. nonce 重复

第 1 次请求（正常）：HTTP 200
第 2 次请求（相同 nonce）：HTTP 403

**Redis** ✅ L3：`SET myxhs:gateway:nonce:{nonce} 1 NX EX 300`。

---

## 层验证汇总

| 层 | 内容 | 状态 | 备注 |
|:--:|------|:----:|------|
| L1 | API 响应 | ✅ | 全部符合预期 |
| L2 | ACCESS 日志 | ✅ | Gateway 入/出站日志 |
| L3 | Redis | ✅ | nonce 去重验证 |
| L4 | MySQL | N/A | |
| L5 | 应用日志 | ✅ | 鉴权/签名日志 |
| L6 | Nacos 注册 | ✅ | my-xhs-gateway 已注册 |
| L12 | Gateway 路由 | ✅ | 白名单/鉴权/签名全链路 |
| L13 | Actuator | ✅ | UP |
| L14 | ES 日志 | 🟡 | traceId 缺失（同其他模块） |
| L15 | Prometheus | ✅ | 233 行指标（修复后） |

**修正记录**：

| 问题 | 修复 | 涉及文件 |
|---|---|---|
| `/api/recommend/**` 无路由，请求 404 | 新增 recommend-service 路由 | `application.yml` |
| Prometheus 指标 0 行（缺少 micrometer-registry-prometheus 依赖） | 新增依赖 | `pom.xml` |

**发现的问题**：
1. 🟡 L14 ES traceId 缺失 — 同其他模块，中间件处理中
