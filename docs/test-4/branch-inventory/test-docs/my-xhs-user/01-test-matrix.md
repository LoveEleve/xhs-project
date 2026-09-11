# my-xhs-user 测试用例矩阵（L1-L4）

## L1 业务

| ID | 用例 | 请求/数据 | 预期 | 状态 |
|---|---|---|---|---|
| U-L1-01 | 注册 | POST /api/user/auth/register | 用户创建 | ✅ 带验证码200 |
| U-L1-02 | 登录 | POST /auth/login | access+refresh token | ✅ |
| U-L1-03 | 验证码 | GET /auth/captcha | captchaKey+image | ✅ |
| U-L1-04 | 验证码错误 | 错误 code 登录 | 40104 | ✅ |
| U-L1-05 | 密码错误锁账号 | 错误密码40108 | 锁定阈值待独立环境 | ⚠️ |
| U-L1-06 | 个人信息 | GET /api/user/me | 用户信息 | ✅ |
| U-L1-07 | 修改资料 | PUT /api/user/me | 更新 | ✅ |
| U-L1-08 | 地址 CRUD | 增删改查 address | 地址管理 | ✅ |
| U-L1-09 | block 拉黑/取消 | POST/DELETE /block/{id} | 拉黑关系 | ✅ |
| U-L1-10 | block 列表 | GET /block/list | 拉黑用户列表 | ✅ |

## L2 数据

| ID | 验证点 | 证据 | 状态 |
|---|---|---|---|
| U-L2-01 | t_user 登录/角色 | role 读入 JWT claim | ✅ |
| U-L2-02 | t_user_address | 归属校验 | ✅ |
| U-L2-03 | Redis block set | `myxhs:user:block:{id}` | ✅ |
| U-L2-04 | Redis token 存取 | access/refresh | ✅ |

## L3 质量

| ID | 用例 | 预期 | 状态 |
|---|---|---|---|
| U-L3-01 | 越权地址 | 操作他人地址 | 403/404 | ✅ 403无权操作 |
| U-L3-02 | 越权 block | block 自己/他人 | 拒绝 | ✅ 自block 400 |
| U-L3-03 | 登录失败计数 | 单次40108 | 累计锁定待独立环境 | ⚠️ |
| U-L3-04 | 令牌过期刷新 | refresh token | 新 accessToken | ✅ 200 |
| U-L3-05 | 注销 | logout | 原token 401已被注销 | ✅ |

## L4 可观测

| ID | 验证点 | 状态 |
|---|---|---|
| U-L4-01 | 登录/注册审计日志 | ✅ |
| U-L4-02 | ✅ captcha/login HTTP指标暴露 | ⬜ |
| U-L4-03 | TraceId 跨 gateway/user | ✅ |

## 已实测
- U-L1-02/03/06/07/08/09/10、L2-01/03 ✅
- 注册/验证码错误/锁账号/越权/令牌刷新/注销 待专项
