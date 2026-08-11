# 用户模块（my-xhs-user）

> 来源：`my-xhs-user/src/main/java/com/myxhs/user/*`（直接读源码产出）

## 一、业务边界
负责：注册、登录、注销、Token 刷新、个人信息、收货地址、拉黑。
网关地址前缀：`/api/user/**`（登录/注册/验证码走 `/auth/**`，需在 Gateway 白名单）。

## 二、认证流程（核心）

### 1. 获取图形验证码
```
GET /api/user/auth/captcha
→ 200 { "data": { "captchaKey": "uuid", "captchaImage": "data:image/png;base64,..." } }
```
- 验证码存 Redis，**5 分钟过期，一次性**（GETDEL 原子消费）。
- 前端登录/注册**必须**先拉验证码，再带 `captchaKey + captchaCode` 提交。

### 2. 注册
```
POST /api/user/auth/register
body: { username, password, captchaKey, captchaCode, phone? }
```
规则（读 `UserService.register`）：
- 用户名：4~32 位，`^[a-zA-Z0-9_]+$`，唯一。
- 密码：6~64 位，BCrypt 加密存储。
- 手机号：选填，`^1[3-9]\d{9}$`，若提供则唯一。
- 默认 `nickname = username`，`gender=0`，`status=1`。
- 分布式锁防并发注册；重复用户名/手机号 → 业务错误码。

### 3. 登录
```
POST /api/user/auth/login
body: { username, password, captchaKey, captchaCode }
→ 200 { "data": { "accessToken", "refreshToken", "hmacSecret" } }
```
规则：
- 先校验验证码 → 检查账号是否锁定 → 查用户 → 校验密码。
- **账号锁定**：密码连续错 5 次 → 锁定 15 分钟（`ACCOUNT_LOCKED`）。
- `status=0` → 账号已禁用，不可登录。
- 登录成功 → 生成 Token 对，**单设备登录**（旧 token 被踢入黑名单）。

### 4. 刷新 Token
```
POST /api/user/auth/refresh?refreshToken=xxx
```
- 用 refresh token 换新 Token 对；旧 token 进黑名单。
- 并发刷新有分布式锁保护；被其他设备覆盖 → 需重新登录。

### 5. 注销
```
POST /api/user/auth/logout   (Authorization: Bearer xxx, 可选 refreshToken)
```
- access + refresh token 都进黑名单，清 Redis 映射。

> **前端注意**：登录成功后把 `accessToken` 存 localStorage；刷新 token 的时机是 401 时。前端 `client.ts` 目前只在 401 时清 token 跳登录页，未做自动 refresh——如需自动续期需补逻辑。

## 三、个人信息

### 获取我的信息
```
GET /api/user/me            (需登录)
→ UserInfoResponse
```
返回字段（含敏感字段）：`id, username, nickname, avatar, gender, birthday, phone, email, signature, status, createdAt`
- **gender 取值**：0=未知, 1=男, 2=女（前端展示需映射）。
- **status 取值**：1=正常, 0=禁用。

### 获取他人公开信息
```
GET /api/user/{userId}/info   (公开，无需登录)
→ UserPublicInfoResponse
```
只返回：`id, username, nickname, avatar, gender, signature, createdAt`（**不含 phone/email**）。

> 用户主页（`UserProfilePage`）应使用 home 模块的 `/api/home/user/{id}` 聚合接口，而非此处。

### 更新我的信息
```
PUT /api/user/me   (需登录)
body: { nickname?, avatar?, gender?, birthday?, phone?, email?, signature? }
```
- 只更新非 null 字段；至少一个字段有值。
- 改手机号需唯一；禁用用户不可改。
- 前端**用户信息更新后应调用 `/user/me` 刷新本地缓存**（后端走缓存 Cache-Aside + 延迟双删）。

### 修改密码
```
PUT /api/user/me/password   (需登录)
body: { oldPassword, newPassword }
```
- 旧密码错误 → 拒绝；成功后**注销当前用户所有活跃 token** → 前端需跳登录页重新登录。

## 四、收货地址（下单前置依赖）

```
GET    /api/user/address/list          → AddressVO[]（默认排前）
GET    /api/user/address/default       → AddressVO | null
GET    /api/user/address/{id}          → AddressVO
POST   /api/user/address               body: AddressCreateRequest
PUT    /api/user/address/{id}          body: AddressUpdateRequest
DELETE /api/user/address/{id}
PUT    /api/user/address/{id}/default
```

### AddressVO 字段（后端实际）
```
id, receiverName, receiverPhone, province, city, district, detailAddress,
isDefault(1/0), createdAt, updatedAt
```
- **`receiverPhone` 在 VO 中已脱敏**：`138****1234`（下单时后端会用未脱敏的，前端展示脱敏值即可）。

### AddressCreateRequest / AddressUpdateRequest 字段（请求体）
```
receiverName, receiverPhone, province, city, district, detailAddress, isDefault?
```

> ⚠️ **前端 API 层缺陷（必须修）**：`src/api/auth.ts` 的 `addAddress` 用了 `AddressRequest` 类型
> （字段为 `name/phone/detail`），与后端 `receiverName/receiverPhone/detailAddress` **不一致**。
> 写地址页前需同步修正 `src/types/index.ts` 的 `AddressRequest`。

### 业务规则
- 每用户地址上限 **20 条**（`ADDRESS_LIMIT_EXCEEDED`）。
- **第一条地址自动设为默认**。
- 默认地址全局唯一：设新默认会自动取消旧默认；删除默认地址后自动把最新一条设为默认。
- 所有地址操作：分布式锁 + 归属校验（他人地址 → `FORBIDDEN`）。

## 五、拉黑（屏蔽）

```
POST   /api/user/block/{targetUserId}
DELETE /api/user/block/{targetUserId}
GET    /api/user/block/list   → Set<userId>
```
- 不能拉黑自己。
- 存 Redis Set，TTL 365 天。

## 六、前端接入注意汇总
1. 登录/注册都要图形验证码，注册成功前端可自动登录。
2. 改密成功、被踢出（401）→ 跳登录页。
3. `/user/me` 与 `/{userId}/info` 字段不同，别混用。
4. 修复 `AddressRequest` 字段名（`receiverName/receiverPhone/detailAddress`）。
5. `AddressVO` 的 `receiverPhone` 已脱敏，别用来做校验/提交。
