# my-xhs-user 用户服务

> 16个端点 | AuthController + UserController + UserAddressController

---

## 架构概览

```
curl → Gateway → user(19001) → MySQL(t_user/t_user_address)
                              → Redis(USER_INFO/TOKEN/CAPTCHA/BLACKLIST)
                              → MQ(CACHE_EVICT_TOPIC → CacheEvictConsumer)
                              → 无Feign调用
```

## 端点清单

### 认证 (AuthController: `/api/user/auth`)

| ID | 方法 | 路径 | 说明 |
|------|------|------|------|
| U01 | GET | `/captcha` | 获取验证码(base64内嵌图片) |
| U03 | POST | `/login` | 登录→JWT Token |
| U04 | POST | `/refresh` | Token刷新 |
| U05 | POST | `/logout` | 登出→Token黑名单 |
| U14 | POST | `/register` | 注册新用户 |

### 用户信息 (UserController: `/api/user`)

| ID | 方法 | 路径 | 说明 |
|------|------|------|------|
| U06 | GET | `/me` | 当前用户信息 |
| U07 | PUT | `/me` | 更新用户信息(含头像) |
| U16 | PUT | `/me/password` | 修改密码 |
| U09 | GET | `/{userId}/info` | 查看他人公开信息 |

### 地址 (UserAddressController: `/api/user/address`)

| ID | 方法 | 路径 | 说明 |
|------|------|------|------|
| U10 | POST | `` | 新增地址 |
| U11 | GET | `/list` | 地址列表 |
| U12 | PUT | `/{id}` | 修改地址 |
| U13 | DELETE | `/{id}` | 删除地址 |

### 屏蔽 (UserController: `/api/user/block`)

| ID | 方法 | 路径 | 说明 |
|------|------|------|------|
| U-B1 | POST | `/block/{targetUserId}` | 屏蔽用户(Redis Set, 365天TTL) |
| U-B2 | DELETE | `/block/{targetUserId}` | 取消屏蔽 |
| U-B3 | GET | `/block/list` | 屏蔽列表(SMEMBERS+MySQL IN查询)

## Key Redis

| Key | 用途 | TTL |
|------|------|:--:|
| `myxhs:user:captcha:{key}` | 验证码 | 5min |
| `myxhs:user:token:access:{userId}` | access token | 30min |
| `myxhs:user:token:refresh:{userId}` | refresh token | 7d |
| `myxhs:user:token:blacklist:{jti}` | 登出黑名单 | Token剩余期 |
| `myxhs:user:info:{userId}` | 用户缓存 | 30min |
| `myxhs:user:address:default:{userId}` | 默认地址缓存 | 30min |
| `myxhs:user:login:fail:{username}` | 登录失败计数 | — |
| `myxhs:user:login:lock:{username}` | 登录锁定 | 15min |
| `myxhs:user:block:{userId}` | 屏蔽列表 | 365天 |

## Key MySQL

| 表 | 库 | 说明 |
|------|------|------|
| t_user | my_xhs_user | 用户(username/phone uk) |
| t_user_address | my_xhs_user | 收货地址(is_default) |