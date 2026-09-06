# my-xhs-user 数据、分布式与工程分析

## 1. Redis 数据

| 类型 | Key | 用途 |
|---|---|---|
| 验证码 | `myxhs:user:captcha:{key}` | 5 分钟，一次性 GETDEL |
| Access | `myxhs:user:token:access:{userId}` | 单设备 access 映射 |
| Refresh | `myxhs:user:token:refresh:{userId}` | refresh 校验与轮换 |
| HMAC | `myxhs:user:hmac:secret:{userId}` | 登录会话密钥 |
| 黑名单 | `myxhs:user:token:blacklist:{jti}` | 注销/改密/删除后的撤销 |
| 用户缓存 | `myxhs:user:info:{userId}` | Cache Aside |
| 登录失败 | `RedisKeyConstants` 对应账号/IP key | 暴力破解控制 |
| 屏蔽列表 | `myxhs:user:block:{userId}` | StringRedisTemplate Set |
| 地址默认值 | `myxhs:user:address:default:{userId}` | 默认地址 ID 缓存 |

Redis 不是单一辅助组件，而是认证状态、用户缓存和反滥用状态的共同依赖。Redis 故障时注册/登录、Token 撤销和缓存一致性会分别进入不同降级路径，必须区分“可用性降级”和“安全状态丢失”。

## 2. MySQL 数据

核心表：
- `t_user`
- `t_user_address`

当前最大风险是 schema 与代码漂移：Java `User` 和注册/Token 逻辑使用 `role`，但历史初始化 SQL 的 `t_user` 定义没有 `role`。必须以当前实际数据库 `SHOW CREATE TABLE t_user` 为最终事实；如果列确实不存在，注册或登录可能在写入/读取阶段失败。

用户服务配置了主从数据源，但业务代码没有明确标注读写路由。更新后立即读、缓存回填和默认地址读都可能遇到主从延迟，产生“数据库已更新但缓存重新写回旧值”的窗口。

## 3. 锁与事务

### 注册
- username 锁防并发创建
- phone 依赖唯一索引兜底
- DB 写入在事务中

### 地址
- userId 锁保护数量、默认切换和删除后补默认
- 锁租约固定 10 秒
- 事务提交前释放锁，可能出现锁已释放但事务尚未提交的窗口
- 数据库没有唯一默认地址约束

### Token 刷新
- 以旧 refresh JWT 的 jti 加锁
- 锁内再次检查黑名单和 Redis refresh 映射
- 轮换过程仍不是 Redis 原子事务

## 4. 缓存一致性

资料更新采用立即删除 + 延迟双删；延迟任务在 JVM 内存中，服务崩溃会丢失。第一次删除异常时当前实现可能直接结束，无法安排后续删除和 MQ 兜底。

CacheEvictConsumer 对非法消息直接 return，消息会被消费确认，无法重试；合法消息删除 Redis 失败则抛异常，才能交由 RocketMQ 重试。

因此当前方案是“尽力最终一致”，不是严格的 DB/Redis 原子一致。若要求更高可靠性，应引入 Outbox/本地消息表或基于 binlog 的缓存失效链路。

## 5. 微服务交互

- Gateway 注入 `X-User-Id`，user 服务自身不解析 JWT
- 其他服务通过用户 ID 查询公开资料或默认地址
- 缓存失效通过 RocketMQ `CACHE_EVICT_TOPIC` 兜底
- 用户登录产生的 Token/HMAC 状态被 Gateway 消费

服务被绕过 Gateway 直接访问时，Header 信任边界必须由 common 的 `GatewayAuthTrustFilter` 和内部调用规则兜底，不能只依赖网关。

## 6. 性能问题

- 批量公开资料逐个查询，存在 N+1 Redis/DB 放大
- 验证码图片生成、绘制和 Base64 编码可能造成 CPU/内存压力
- CacheHelper 单线程延迟删除器可能积压
- 10 秒固定锁租约无法覆盖所有慢 SQL/锁等待场景
- 主从延迟可能导致缓存回填旧数据

## 7. 工程问题

- Dockerfile 只有基础镜像声明，缺少模块独立构建/启动/健康检查契约
- 测试仍 Mock 旧的 RedisOperator，而生产屏蔽列表使用 StringRedisTemplate
- 认证测试存在构造器/方法签名与当前生产代码不一致风险
- Java 微服务不是自动恢复容器，关机重启后必须重新启动并检查 Nacos
