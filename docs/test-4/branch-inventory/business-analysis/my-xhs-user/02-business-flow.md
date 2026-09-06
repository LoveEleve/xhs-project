# my-xhs-user 业务链路与源码流转

## 1. 注册链路

```text
POST /api/user/auth/register
  -> 参数校验
  -> CaptchaService GETDEL 验证码
  -> username 分布式锁
  -> 查询用户名/手机号唯一性
  -> BCrypt 密码编码
  -> 事务写入 t_user
  -> 返回结果
```

`AuthController` 只负责接收请求和转交服务；业务顺序集中在 `UserService.register`。验证码使用 Redis `GETDEL`，因此同一个验证码并发提交时只有一个请求可以消费成功。注册锁按用户名保护并发创建，但手机号并没有单独锁，最终仍依赖数据库唯一索引。

数据库异常处理通过分析异常消息判断冲突字段，属于对驱动文本的脆弱依赖；应优先使用明确的错误码、唯一性预检查和数据库约束组合。

## 2. 登录链路

```text
POST /api/user/auth/login
  -> 一次性消费验证码
  -> 校验 IP 锁
  -> 校验 username 锁
  -> 查询用户与状态
  -> BCrypt 比对密码
       失败 -> 账号/IP 失败计数 -> 可能加锁
       成功 -> 清账号失败计数
  -> 生成 access/refresh JWT + HMAC secret
  -> Redis 覆盖旧会话
  -> 返回 TokenResponse
```

登录限制同时有账号维度和 IP 维度：账号失败达到阈值且来源 IP 达到多源条件时锁账号，单 IP 失败达到阈值时锁 IP。该设计试图平衡撞库防护与误伤，但登录成功只清理账号失败计数，不清理 IP 失败计数，历史失败可能继续影响后续正常登录。

登录生成 Token 后覆盖 Redis 中旧会话，实际是单设备模型：同一用户后登录会使旧设备的 refresh/access 映射失效。

## 3. Token 生命周期

```text
登录
  -> access JWT 30 分钟
  -> refresh JWT 7 天
  -> Redis access:{userId}
  -> Redis refresh:{userId}
  -> Redis hmac:{userId}

刷新
  -> 解析 refresh JWT
  -> 校验 type
  -> 黑名单校验
  -> 以旧 jti 加 Redisson 锁
  -> 比对 Redis refresh token
  -> 查询用户状态
  -> 拉黑旧 Token
  -> 生成新 Token 对并覆盖 Redis

注销 / 改密 / 删除
  -> 拉黑 Token
  -> 删除 Token 映射
  -> 删除 HMAC secret
```

刷新锁避免同一 refresh token 并发轮换，但等待超时直接失败，客户端需要重试；系统没有复用“已由其他请求生成的新 Token”的机制。

已确认的业务缺陷：注销 Controller 只接受 Access Token；如果客户端只携带 Refresh Token，接口可能返回成功，但 Refresh Token 没有被吊销。

## 4. 用户资料链路

```text
GET /api/user/me 或公开资料
  -> Cache Aside 查询
       Redis 命中 -> 返回
       未命中 -> MySQL 查询 -> 回填 Redis -> 返回
```

缓存查询明确排除了 password，但 `/me` 的手机号、邮箱转换实际返回原值，与响应对象中“脱敏”的意图不一致。

资料更新先写数据库，再做立即删除与延迟双删，更新后直接查库返回，避免将旧缓存作为响应结果。手机号唯一性仍是“先查再写”，最终依赖数据库唯一索引解决并发。

批量公开资料逐个调用单用户查询，形成 N+1 Redis/DB 访问，并且没有批量大小限制。

## 5. 地址链路

```text
地址新增/更新/删除/设默认
  -> userId 分布式锁
  -> 查询与归属检查
  -> 事务写 t_user_address
  -> 删除默认地址缓存
  -> 必要时重新选择默认地址
```

新增地址在锁内检查最多 20 条，第一条自动设默认。更新默认地址时取消旧默认并设置新默认。删除默认地址后按 `created_at DESC` 选一条最新地址作为新默认，但注释表达的是“第一条”，实现与意图不一致。

数据库没有唯一默认地址约束，因此“最多一个默认地址”依赖所有写路径都正确使用同一把分布式锁；锁在事务提交前释放，存在其他线程先获得锁而前一个事务尚未提交的窗口。

## 6. 用户删除与封禁

```text
管理调用
  -> X-Admin-Call 基础校验
  -> 查询用户
  -> 逻辑删除 t_user
  -> 吊销 Token/HMAC
  -> 删除用户缓存
```

用户状态会阻止登录、刷新、资料更新和改密。管理员删除仅处理用户主记录、Token 和用户信息缓存，没有看到地址、屏蔽列表等关联数据的完整清理策略。

## 7. 缓存删除消息

```text
DB 更新
  -> 立即删缓存
  -> 500ms 延迟双删
  -> 第二次删除失败时发送 CACHE_EVICT_TOPIC
  -> CacheEvictConsumer 删除 Redis
  -> 失败抛出以触发 MQ 重试
```

这不是严格事务一致性，而是尽力最终一致性。第一次删除失败时当前实现可能直接返回，导致后续延迟删除和 MQ 兜底不执行。非法消息被直接 return 时会被确认，无法进入重试/DLQ。
