# my-xhs-user 测试重点

## 1. 当前已确认问题与待核验项

| 状态 | 分类 | 问题 | 影响 |
|---|---|---|---|
| 待核验 | Schema/代码 | Java/Token 使用 `role`，初始化 SQL 未定义 | 只能确认初始化 SQL 与 Java 模型漂移，线上是否缺列必须用 `SHOW CREATE TABLE` 确认 |
| 已确认 | 业务逻辑 | 仅 Refresh Token 调注销可能返回成功但未吊销 refresh | 用户以为退出，旧 refresh 仍可用 |
| 已确认 | 数据隐私 | `/me` 实际返回完整 phone/email，与脱敏意图不一致 | 是否符合产品策略需确认 |
| 已确认 | 一致性 | 改密后凭证吊销失败被吞掉 | 旧凭证可能继续有效 |
| 已确认 | MQ | CacheEvictConsumer 非法消息直接确认 | 缓存失效兜底静默丢失 |
| 已确认 | 缓存 | 延迟双删第一次失败即不安排后续补偿 | 更新后缓存可能保留旧值 |
| 已确认 | 性能 | 批量资料逐个查询且无数量限制 | Redis/DB 请求放大 |
| 已确认 | 地址逻辑 | 删除默认地址实际选最新地址，注释称第一条 | 实现与注释语义不一致 |
| 已确认 | 测试工程 | 屏蔽列表生产与测试使用不同 Redis API | 测试无法证明生产行为 |
| 业务逻辑 | 仅 Refresh Token 调注销可能返回成功但未吊销 refresh | 用户以为退出，旧 refresh 仍可用 |
| 数据隐私 | `/me` 实际返回完整 phone/email，与脱敏意图不一致 | 敏感信息暴露 |
| 一致性 | 改密后凭证吊销失败被吞掉 | 密码已变更但旧凭证可能仍有效 |
| MQ | CacheEvictConsumer 非法消息直接确认 | 缓存失效兜底静默丢失 |
| 缓存 | 延迟双删第一次失败即不安排后续补偿 | 更新后缓存可能长期保留旧值 |
| 性能 | 批量资料逐个查询且无数量限制 | Redis/DB 请求放大 |
| 地址逻辑 | 删除默认地址实际选最新地址，注释称第一条 | 业务结果与设计意图不一致 |
| 测试工程 | 屏蔽列表生产与测试使用不同 Redis API | 测试无法证明生产行为 |

## 2. 鉴权基础检查

仅验证：
- Gateway 注入的用户 ID 是否被 user 服务正确使用
- 管理/内部 Header 缺失或错误时是否拒绝
- 禁用用户已有 Token 是否仍能完成关键操作

不在本轮展开 JWT/HMAC 算法细节。

## 3. 后续测试分层

### L1 业务
- 注册：验证码、重复用户名/手机号、禁用状态
- 登录：密码错误、账号锁、IP 锁、成功后单设备 Token 覆盖
- Token：刷新轮换、并发刷新、注销、改密、删除
- 资料：`/me`、公开资料、批量资料、资料更新
- 地址：新增上限、默认地址、并发切换、删除默认地址
- 屏蔽：新增、删除、列表、重复操作

### L2 数据
- MySQL：`t_user.role`、逻辑删除、地址默认状态、唯一索引
- Redis：验证码 GETDEL、Token TTL、黑名单 TTL、缓存删除和回填
- MQ：缓存失效 topic、消费成功/失败、重试/DLQ
- 主从：更新后立即读、缓存回填是否读到旧值

### L3 质量
- 并发重复注册
- 并发刷新同一 refresh token
- 并发新增/切换/删除默认地址
- 批量资料数量上限和 N+1 放大
- Redis、MySQL、RocketMQ 单点故障下的返回和补偿
- 锁租约小于业务耗时的场景

### L4 可观测性
- user 服务 TraceId 是否贯穿 Redis/MQ 日志
- 登录失败锁、Token 轮换、缓存补偿是否有可检索日志
- Prometheus HTTP/业务指标
- ES 中用户日志是否泄露密码、验证码、Token、完整手机号/邮箱

## 4. 当前阶段

这里只整理测试重点，不立即执行接口测试。测试必须在源码问题处理和数据库 schema 核对后，逐条形成执行记录。

当前测试前置阻断：
- `AuthServiceTest.java` 与 `UserServiceTest.java` 的构造器/方法调用已与当前生产代码漂移，当前不能作为回归门禁
- `UserServiceTest` 仍按 `RedisOperator` 验证屏蔽列表，而生产实现使用 `StringRedisTemplate`
- `role` 是否缺失于实际数据库必须通过受控 `SHOW CREATE TABLE t_user` 确认，不能只根据初始化 SQL 下结论
- 测试源码编译问题已修复，当前 14 个 user 单元测试全部通过；真实 schema 核验和业务接口测试仍待执行

## 5. 最近验证结果
- `mvn -pl my-xhs-user -am test`：common 53 个、user 14 个测试全部通过
- `mvn -pl my-xhs-user -am -Dmaven.test.skip=true package`：构建成功
- user 服务 `19001/actuator/health`：`UP`
- 本轮未启动 AI 服务，AI 不属于当前 user 分析范围
