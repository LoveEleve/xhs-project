# my-xhs-user 已知故障与陷阱

## 一、测试陷阱

### 1. 验证码一次性消费
- **现象**: 同一个验证码key用第二次返回"验证码错误"
- **根因**: `CaptchaService.verifyCaptcha()` 校验后立即 `redis.delete(key)`
- **应对**: 每次登录必须重新获取验证码

### 2. Token 30分钟过期
- **现象**: 测试中途突然401
- **根因**: access token TTL=1800s，过期后需refresh
- **应对**: 每30分钟重新获取Token，或测试前检查Token剩余TTL

### 3. 登录锁定
- **现象**: 连续输错密码5次后，即使密码正确也锁定
- **根因**: Redis `USER_LOGIN_FAIL:{username}` 计数到5 → 写 `USER_LOGIN_LOCK:{username}` 15分钟
- **应对**: 删除Redis中这两个key解除锁定

### 4. Gateway X-User-Id覆盖
- **现象**: curl -H "X-User-Id: {other}" 无效，始终是当前Token用户
- **根因**: `GatewayAuthFilter` 从JWT subject提取userId → `set()` 覆盖客户端传入的X-User-Id
- **应对**: 需要操作其他用户时，必须用该用户的Token登录

## 二、代码陷阱

### 5. 地址列表路径 `/list`
- **现象**: `GET /api/user/address` 返回404
- **根因**: 实际路径是 `GET /api/user/address/list` (UserAddressController:31)
- **应对**: 必须带 `/list`

### 6. 更新头像不在独立端点
- **现象**: `PUT /api/user/me/avatar` 返回404
- **根因**: 头像通过 `PUT /api/user/me` 的 `UpdateUserRequest.avatar` 字段更新
- **应对**: 用 U07 更新头像

### 7. 修改密码路径
- **现象**: `PUT /api/user/auth/change-password` 返回404
- **根因**: 实际路径是 `PUT /api/user/me/password` (UserController:62)
- **应对**: 用正确的路径

## 三、依赖故障

### 8. Redis不可用
- **现象**: 登录返回500
- **根因**: Token需要写入Redis，验证码从Redis读取
- **验证**: `python3 -c "r=redis.Redis(host='21.130.247.89',port=6379,password='Xhs@2026#Redis');r.ping()"`
- **修复**: 检查Redis容器 + Sentinel状态

### 9. MySQL Slave SQL线程停
- **现象**: 地址列表有时返回旧数据
- **根因**: 读请求路由到Slave(3307)，如果复制断了数据不更新
- **验证**: `mysql -P 3307 -e "SHOW SLAVE STATUS\G" | grep Running`
- **修复**: 参考部署文档修复GTID复制

## 四、并发陷阱

### 10. 注册并发
- **现象**: 同时注册相同用户名成功两次？
- **根因**: Redisson `USER_REGISTER_LOCK:{username}` 仅锁同用户名，不锁同手机号
- **应对**: MySQL t_user表username+phone都有唯一索引，第二次insert会报DuplicateKeyException

### 11. 不能屏蔽自己
- **现象**: `POST /block/{自己userId}` 返回错误
- **根因**: `UserService.blockUser()` 校验 `userId == targetUserId` → 直接抛异常
- **应对**: 测试时使用其他用户的userId作为targetUserId

### 12. 大量屏蔽列表性能
- **现象**: 屏蔽>1000用户后 `GET /block/list` 变慢
- **根因**: SMEMBERS全量拉取 + MySQL WHERE id IN(...) 大量ID
- **应对**: 生产需加游标分页 + Redis SSCAN 分批

### 13. 屏蔽后仍能查看已缓存内容
- **现象**: 屏蔽某用户后，Feed仍能短暂看到其内容
- **根因**: 屏蔽只控制关系链，不影响已推送到收件箱的历史笔记（仅在Feed查询时过滤，缓存期30min）
- **应对**: 测试Feed隔离时需清空Redis收件箱缓存后重新拉取

## 五、代码级缺陷（需修复）

### 14. 验证码并发消费（已修复）
- **现象**: 两个并发请求可消费同一验证码
- **根因**: `CaptchaService.verifyCaptcha()` 先 GET 再 DEL，两步非原子
- **修复**: 改用 `GETDEL` 命令（Redis 6.2+），GET+DELETE 原子执行

### 15. 并发删除/设置默认地址无锁（已修复）
- **现象**: 并发删除默认地址A+设置默认地址C → 可能产生双默认地址
- **根因**: `deleteAddress` 和 `setDefaultAddress` 未获取 `USER_ADDRESS_LOCK`
- **修复**: 两方法统一加 Redisson 锁，与 createAddress/updateAddress 共用锁 key

### 16. 验证码明文写入日志（已修复）
- **现象**: ELK 日志可读出验证码明文 → 配合暴力 OCR 绕过验证码
- **根因**: `log.info("[验证码] 生成成功, key={}, code={}", key, code)` 明文输出
- **修复**: 日志只输出 key，不输出 code

### 17. 更新后读从库返回旧数据（待修复）
- **现象**: 修改昵称/手机号后接口立即返回旧值
- **根因**: `updateUserInfo` 更新 MASTER 后 `getUserInfoDirect` 调用 `selectById` 被路由到 SLAVE
- **建议**: 加 `@Transactional` 强制事务内读走 master，或 write-through 回填缓存

### 18. 新注册用户立即登录失败（待修复）
- **现象**: register 写 MASTER 后立即 login → `selectOne` 走 SLAVE → 查不到用户
- **根因**: 主从复制延迟内 SLAVE 未同步
- **建议**: login 的用户查询加 `@Transactional` 或手动设置 `DataSourceContextHolder.set(MASTER)`

### 19. adminToken 默认值硬编码
- **现象**: 12 个服务 `${ADMIN_TOKEN:my-xhs-admin-token-2026}` 硬编码默认值
- **风险**: 生产未设 ADMIN_TOKEN 环境变量时，攻击者可用固定头调用所有管理端点
- **建议**: 生产部署时通过 Nacos/环境变量注入真正的管理员令牌
