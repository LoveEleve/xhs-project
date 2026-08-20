# G1-06 用户信息用例

> 组：G1 认证与用户 | 入口：**gateway(19000)** | 依赖：G1-03 登录（{accessToken}/{hmacSecret}/{userId}）
> 时间引用：矩阵 #23（缓存 30min）、#24（空值 2min）

## 代码实证（2026-08-12）
- `GET /me`：`getWithCacheAside(USER_INFO, 30min)`（缓存 30min + 空值 2min 防穿透）；X-User-Id 由 gateway 注入
- `GET /{userId}/info`：公开接口（white-list `/api/user/*/info`，**无 token 可访问**），与 /me 共享缓存（不同字段提取）
- `PUT /me`：更新 DB → **delayDoubleDelete 缓存**（先 DB 后删缓存）→ 返回直查值；**需 HMAC 签名**（不在 hmac-white-list）
- `PUT /me/password`：**hmac-white-list 内（免 HMAC 签名）**，需 JWT；旧密码错 → **40108 密码错误**
- 改密码：matches 旧密码 → BCrypt encode → updateById（单条，无事务长占）✅

## 用例清单

### G1-06-01 /me 正常（缓存回填实证）
- **入口**：`GET /api/user/me`（Authorization: Bearer {accessToken}）
- **L1 断言**：200；响应含 username/nickname/avatar/gender/phone（脱敏？——实测：phone 是否完整返回——若返回完整手机号 → 观察点）
- **L2**：
  ```
  EXISTS myxhs:user:info:{userId}     # = 1（首次 miss → DB → 回填）
  TTL  myxhs:user:info:{userId}       # ~1800s（30min）
  ```

### G1-06-02 公开信息（无 token）
- **入口**：`GET /api/user/{userId}/info`（**不带 Authorization**）
- **L1 断言**：200（白名单公开）；响应字段为用户公开信息（不含 phone/email？——实测断言敏感字段是否剔除）
- **L2**：与 /me 共享缓存 key（EXISTS 同一 key）

### G1-06-03 缓存一致性（改资料 → 立即新值）
- **入口**：`PUT /api/user/me`（HMAC 签名）改 nickname="new_nick_{ts}"
- **L1 断言**：200；响应 nickname = 新值（返回直查值）
- **L2**：
  ```sql
  SELECT nickname FROM my_xhs_user.t_user WHERE id={userId};   # = 新值
  ```
  ```
  EXISTS myxhs:user:info:{userId}      # = 0（delayDoubleDelete 已删）
  # 立即 GET /me → 200 新值（缓存 miss 回填新值）
  ```
- **注意**：delayDoubleDelete 异步双删（第一次删+延迟再删）——极端时序下可能读到旧缓存（毫秒级窗口）——若实测读到旧值，重试一次（正常）

### G1-06-04 空值防穿透（缓存 2min）
- **入口**：`GET /api/user/99999999/info`（不存在用户）→ 200 + 空数据（错误码还是空对象？——实测记录）
- **L2**：`EXISTS myxhs:user:info:99999999` = 1（NULL_PLACEHOLDER，2min）
- **二次查询**：立即再查同 ID → 不落 DB（观察 user 日志无第二次查询/或响应一致）
- **兜底**：删空值 key 后查询 → DB 查询路径（行为对比）

### G1-06-05 改密码成功
- **入口**：`PUT /api/user/me/password`（**无 HMAC 签名**——白名单实证）
  ```json
  {"oldPassword":"{旧密码}","newPassword":"NewPass@123"}
  ```
- **L1 断言**：200
- **L2**：`SELECT password FROM t_user WHERE id={userId}` → 新 BCrypt（$2a$10$，与旧哈希不同）
- **登录验证**：新密码登录成功（G1-03 流程）

### G1-06-06 改密码旧密码错误
- **入口**：oldPassword 错误
- **L1 断言**：**40108**（"旧密码错误"）
- **L2**：password 未变

### G1-06-07 部分更新不覆盖（字段级）
- **入口**：`PUT /me` 仅改 signature
- **L2**：`SELECT nickname, signature` → nickname 保持原值、signature 更新（LambdaUpdateWrapper 只 set 非空字段）

### G1-06-08 越权边界（自签其他 uid access）
- **工具**：sign_jwt(99999, "access", now+600)（G1-04 工具）
- **入口**：带自签 token 调 `PUT /me` 改 nickname
- **L1 断言**：200（X-User-Id 被 gateway 覆盖为 99999 → 更新 uid=99999 用户——**越权验证**：只能改自己（99999），不能改他人；若 99999 不存在 → 更新 0 行）
- **L2**：t_user 其他用户数据未被改（无越权 ✅）——若响应 200 且 target 非本人 → 记录
- **说明**：验证 gateway X-User-Id 覆盖机制（P1-3）

## 执行记录
| 用例 | 时间 | 结果 | 问题/备注 |
|---|---|---|---|
| G1-06-01~08 | | | |

## 断言关键词速查
- 40108 旧密码错误 / 200 正常
- 关键 L2：user:info 缓存 TTL 30min、delayDoubleDelete 后 MISS、空值 2min、password BCrypt 变更

## 深度 REVIEW 补充（2026-08-12）

### 代码实证
- **delayDoubleDelete 完整实现**（CacheHelper:313-335）：第一次删（同步）→ **500ms 后第二次删**（覆盖并发读回填）→ Redis 不可用时 **MQ 兜底**（消费者恢复后重试）——PUT 后立即 GET 是缓存 MISS ✅（同步先删）；06-03 的"毫秒窗口"实际不存在（同步删先执行）
- **公开字段白名单**（UserPublicInfoResponse）：id/username/nickname/avatar/gender/signature/createdAt——**无 phone/email/birthday/status** ✅ 隐私剔除正确
- /me 全字段（本人含 phone/email/status）✅
- **UpdateUserRequest 校验**：nickname≤64/avatar≤512/phone 格式/email 格式/signature≤256（更新侧也有校验 ✅）

### 新增用例

#### G1-06-09 直连服务端口伪造 X-User-Id（P1-3 剥离实证）
- **入口**：直连 `localhost:19001/api/user/me`（**不经 gateway**）+ 伪造 `X-User-Id: 1` + 无 JWT
- **L1 断言**：GatewayAuthTrustFilter 剥离 X-User-Id → 400/401（X-User-Id 缺失——实测记录具体码）
- **对照**：带合法 JWT 直连 → 200（JWT subject 覆盖）
- **说明**：验证 P1-3 端口信任模型（直连不能伪造身份）

#### G1-06-10 公开字段白名单断言
- `GET /api/user/{userId}/info` 响应体**不含** phone/email/status/birthday 字段（json 断言 keys 集合）

#### G1-06-11 更新字段校验
| 分支 | 值 | 预期 |
|---|---|---|
| nickname>64 | "a"×65 | 40002 |
| phone 格式错 | "123" | 40002 |
| email 格式错 | "abc" | 40002 |
| signature>256 | "a"×257 | 40002 |
- **L2**：t_user 无变化

### 观察项登记
- **T-012（观察）**：改密码**不轮换 hmac secret、不失效旧 token**——与 T-005 构成"改密后旧凭证仍有效"完整语义（业界常配合失效）；建议改密后：清 token/黑名单 + 换 hmac secret（**已并入 ISSUES**）

### 审查确认
- 公开用户 info 可枚举（username/nickname）——社交平台标准设计 ✅ 正常
- 更新缓存一致性：同步首删 + 500ms 二次删 + MQ 兜底 ✅（业界最佳实践）

## 修复后同步（2026-08-12 T-012）
- **06-05 改密成功后补充断言**：旧 access 调 /me → **401**（T-012 黑名单）；旧 refresh 刷新 → 40103；hmac secret key 已删（EXISTS=0）；需重新登录拿新 token/secret
