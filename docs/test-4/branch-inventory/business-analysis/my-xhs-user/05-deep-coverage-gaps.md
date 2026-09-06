# my-xhs-user 第三轮 Review 遗漏补齐

## 1. 结论边界

本报告记录第三轮查漏时的源码事实和测试前置问题；后续已对测试与 `block list null` 进行修复。结论优先级为当前源码事实，其次是已记录的构建/测试结果；真实 Redis、MySQL、RocketMQ、Nacos 的部分行为仍需单独核验。涉及主从延迟、逻辑删除插件装配、真实消息重试、schema 和部署镜像的内容标为“运行态确认”。本报告聚焦状态变化、异常传播、一致性和测试有效性，不逐行翻译源码。

模块定位：`my-xhs-user` 是认证会话、用户资料、地址和屏蔽关系的状态中心。HTTP 入口通过 DTO 与 Header 进入 Service，Service 同步访问 MySQL/Redis/Redisson，并通过 RocketMQ 做缓存删除兜底；Gateway 依赖 JWT claim 和会话状态完成后续请求放行。

## 2. 关键链路与遗漏分支

### 2.1 AuthController 注销的所有分支

`AuthController.java:73-86` 的行为不是“无条件注销”：

- `Authorization` 缺失或为 null：`accessToken` 保持 null，不调用 `userService.logout`，直接 `R.ok()`（`AuthController.java:74-86`）。这是已确认的静默成功分支。
- `Authorization` 不以 `Bearer ` 开头：同样跳过注销并返回成功（`AuthController.java:80-86`）。任意错误 scheme、大小写不匹配和前缀不完整都落入该分支。
- `Authorization` 以 `Bearer ` 开头：截取第 7 位之后的内容；即使结果为空字符串，也会调用 `userService.logout`（`AuthController.java:80-85`）。空 Bearer 的错误语义由 TokenService 吞掉，而不是 Controller 拦截。
- refresh body 缺失：`refreshToken` 为 null，但只要 access token 存在，仍调用 `logout(accessToken, null)`（`AuthController.java:76-85`）。
- refresh body 存在但 token 为 null：与上一个分支等价；空字符串也会传入 Service，因为注销接口没有 refresh 的非空校验（`AuthController.java:76-84`）。
- access 缺失但 refresh 存在：Controller 不会调用 Service，refresh 也不会被黑名单处理（`AuthController.java:78-85`）。因此“仅携带 refreshToken 注销”在入口层是确认不可达的。
- Service 抛异常：Controller 没有本地补偿或错误转换（`AuthController.java:83-86`），最终行为依赖全局异常处理，需运行态确认。

这造成一个明确业务/安全边界：未携带合法 Bearer 的请求表面返回成功，客户端无法判断会话是否真正失效；尤其 refresh-only 注销被静默忽略。建议先明确注销契约：是否允许 access-only、refresh-only、双 token；无效输入应返回明确错误或保持幂等但必须实际清理可清理状态。

### 2.2 TokenService：blacklist/logout/generate/refresh 的异常与部分成功

#### generateTokenPair

`TokenService.java:67-113` 先生成 access/refresh JWT（`68-76`），读取旧 access 并尝试黑名单（`78-82`），随后依次写入 access、refresh、HMAC 三个 Redis key（`84-105`）。这是多步非事务操作：

- 旧 access 解析失败只记录并继续，`blacklistOldToken` 会吞异常（`TokenService.java:323-329`）；已过期 token 可跳过是合理的，但 Redis 黑名单写失败也会被隐藏。
- access 写成功、refresh 写失败或 HMAC 写失败时，没有回滚已写 key，也没有撤销已返回前生成的 JWT（`TokenService.java:85-105`）。调用方得到异常，但 Redis 可能残留半套会话，属于已确认的部分成功风险。
- 旧 access 黑名单只覆盖旧 access；旧 refresh 由新的 refresh 映射覆盖，但未在此处加入黑名单（`TokenService.java:78-95`）。refresh 是否立即失效依赖 Redis 映射比较，而不是 JWT 黑名单。
- TTL 用毫秒除以 1000（`87-95,101-105`）。配置为小于 1000ms 时会得到 0 秒 TTL，边界行为需运行态确认。

#### refreshToken

`TokenService.java:125-197` 的异常边界较完整，但存在状态竞争：解析异常被统一为 `TOKEN_INVALID`（`128-135`）；类型错误、黑名单命中分别在 `137-148` 返回明确错误；锁获取失败、线程中断分别在 `155-157,190-192` 处理；锁释放有 `isHeldByCurrentThread` 保护（`193-196`）。

锁内依次进行二次黑名单检查、Redis refresh 精确匹配、数据库用户状态查询、旧 refresh 黑名单、生成新 token（`160-188`）。因此：

- 旧 refresh 已加入黑名单（`183-184`）后，`generateTokenPair` 又读取旧 access 并写新会话（`186-188`）。若生成阶段任一 Redis 写失败，旧 refresh 已撤销、旧会话可能部分清理、新会话可能部分写入，属于已确认的部分成功。
- `generateTokenPair` 会再次把旧 access 加黑名单（`78-82`），存在重复解析/重复写入，但不改变主要语义。
- `userMapper.selectById` 的禁用/删除判断在 Redis 会话校验之后（`175-180`），数据库或主从路由异常并未在这里转换为明确的服务不可用错误，需运行态确认全局异常映射。
- `Long.valueOf(claims.getSubject())` 的格式异常不在专门 catch 中，会落入 `catch (Exception)` 并按 Redis/锁异常路径降级为无锁获取后的错误处理范围之外，实际响应需运行态确认（`168-180,190-197`）。
- 锁 lease 10 秒（`155`）若数据库查询或 Redis 操作超过 lease，第二个线程可能进入；源码没有 watchdog 配置证据，需运行态压测确认。

#### logout、revokeAllTokens、invalidateUserCredentials

`logout` 先调用 `blacklistToken(accessToken)`，可选地调用 refresh 黑名单（`TokenService.java:203-207`）。`blacklistToken` 解析或写入失败只 warn 不抛出（`292-299`），因此注销可能返回成功但没有完成黑名单。

随后 logout 尝试从 access 解析 userId，失败时用 refresh 解析兜底（`211-223`），再删除 access、refresh、HMAC 映射（`225-227`）；Redis 清理异常整体被吞并只 warn（`229-231`）。access 缺失但 Controller 未调用的入口问题见 2.1；若 Service 被直接调用，refresh 兜底只能清映射，无法按 jti 将未知旧 access 加黑名单（`217-222`）。

`revokeAllTokens` 读取当前 access/refresh，分别尝试黑名单，再删除三类映射（`TokenService.java:242-263`），方法本身没有总 try/catch；Redis 读、黑名单写或删除失败会中断后续步骤，形成“先处理 access、后处理 refresh、再删除映射”的部分成功。`invalidateUserCredentials` 虽有总 catch（`269-287`），但异常被记录后返回，调用方无法知道凭据是否完全失效。

`UserService.changePassword` 先更新密码，再调用 `invalidateUserCredentials`，随后又调用 `revokeAllTokens`（`UserService.java:387-397`）。两次撤销存在重复操作；更重要的是数据库密码更新已提交/参与当前事务后，Redis 撤销失败不会回滚密码，旧 token 可能在运行态短时间继续有效。源码可确认该一致性缺口，实际窗口长度需运行态确认。

### 2.3 UserService：改密、删除、登录失败计数、批量资料、屏蔽

- 改密：用户不存在、禁用和旧密码错误分别在 `UserService.java:370-385` 返回；新密码 BCrypt 编码、DB 更新、两套凭据撤销在 `387-398`。没有检查 `updateById` 返回行数，更新 0 行仍可能继续撤销并返回成功；这是代码 Bug 候选，需用 mapper 返回 0 的测试确认业务期望。
- 删除：`deleteUser` 先 `selectById`、逻辑删除、撤销 token、删除用户信息缓存（`UserService.java:250-261`）。`@Transactional` 覆盖 DB 与 Redis 调用，但 Redis 不受数据库事务控制；Redis 异常会触发数据库回滚意图，却不能回滚已执行的 Redis 黑名单/删除，反过来也可能因 `revokeAllTokens` 异常阻止缓存清理。事务与外部状态不具原子性是已确认的分布式风险。管理员鉴权入口仅依赖 `X-Admin-Call` 校验（`UserController.java:41-48`），真实令牌配置为空时的放行/拒绝需运行态确认。
- 登录失败计数：验证码先消费（`UserService.java:170-172`）；IP 锁、账号锁检查在 `176-189`；用户不存在直接抛密码错误，**没有调用 `incrementLoginFail`**（`192-200`），所以不存在用户不会累计失败计数，这是已确认的保护逻辑遗漏。密码错误才计数（`208-212`）；IP 计数达到 20 即锁 IP（`412-425`），账号计数和来源 IP 集合在 `428-458`，只有失败次数达到 5 且不同 IP 至少 2 个才锁账号。Redis increment/set/delete 任一步异常会中断密码错误后的错误抛出路径，需运行态确认最终响应；`clearLoginFail` 只清账号计数和 IP 集合，不清 IP 计数（`464-468`），可能导致正常登录后该 IP 仍被历史失败数锁定，这是业务策略需明确而非默认正确。
- 批量资料：null/空集合返回不可变空 Map，null ID 跳过，不存在用户跳过，其它 `BizException` 整批抛出（`UserService.java:277-296`）。每个 ID 都走一次 `getUserPublicInfo`/cache-aside（`282-287`），没有批量上限、去重策略由 Set 偶然保证，也没有 DB 批量查询；N+1 和恶意大集合放大是已确认性能/业务风险。Controller 直接接受 `Set<Long>`，没有 `@Valid` 或大小限制（`UserController.java:97-100`）。
- 屏蔽：写入 `StringRedisTemplate` Set、设置 365 天 TTL（`UserService.java:515-531`）；自己屏蔽被拒绝（`515-518`）。读取直接将 `members()` 传给 `HashSet`（`537-540`），Redis 返回 null 时 NPE，是已确认代码 Bug；屏蔽目标是否存在、已删除用户是否可屏蔽、取消不存在成员是否应成功均未校验。测试仍 mock 旧 `RedisOperator`，不能证明生产实现。

### 2.4 UserAddressService：锁前查询、锁释放、默认缓存、逻辑删除

- 新增先获取用户地址锁，再在锁内查询数量（`UserAddressService.java:58-73`），并在首条地址或显式默认时取消旧默认、插入、写默认地址缓存（`85-105`）。锁粒度能保护同一服务实例/同一 Redis 锁下的数量与默认切换，但没有数据库唯一默认约束证据。
- 更新存在明确的锁前查询：`updateAddress` 先在未持锁状态调用 `getAndVerifyOwnership`（`131-134`），获取锁后没有重新查询，随后按地址和 userId 更新（`135-174`）。并发删除/修改、状态变化和“先读后锁”之间存在陈旧判断窗口；最终 update 条件有归属约束但返回查询只按 `selectById`（`164-174`），更新 0 行仍可能返回旧/逻辑删除状态。
- 删除和设置默认是先取锁再进入 `doDeleteAddress`/`doSetDefaultAddress`，查询归属在锁内（`UserAddressService.java:197-216,329-348`）。三条带锁路径都在 finally 中仅当当前线程持有锁才 unlock（`109-115,176-183,205-212,337-344`），锁释放代码本身正确；但 Spring 事务提交发生在方法返回之后，finally 的 unlock 先于事务提交，导致“锁已释放、事务未提交”的窗口，这是已确认分布式一致性风险。
- 删除默认地址后先逻辑删除、清默认缓存，再按 `isDefault=0` 且 `createdAt DESC` 选择最新地址，并条件更新为默认（`215-247`）。注释所谓“第一条”与实现最新地址不一致；这是已确认业务语义漂移。查询能否自动追加 `deleted=0` 依赖全局 MyBatis-Plus 配置，配置声明在 `application.yml:103-115`，实际插件装配仍需运行态确认。
- 默认缓存读路径先取 ID，再 `selectById`，检查 userId；失配时删除缓存并查 DB 默认地址，命中后写回 30 分钟缓存（`UserAddressService.java:286-315`）。缓存命中对象若已逻辑删除、非默认但仍能被 `selectById` 返回，是否被过滤依赖 TableLogic 运行配置；缓存 TTL、删除/切换时主动更新路径分别在 `404-407`、`223-245`、`356-366`。非默认字段更新不影响默认 ID，因此不需要重写 ID，但地址内容缓存不存在，默认 ID 二次查库仍可能读到主从旧数据。
- `cancelDefaultAddress` 是按 userId + isDefault=1 批量清零（`389-395`），没有检查影响行数；`setDefaultAddress` 的 `updateById` 也不检查返回值（`356-366`）。并发/绕过 Service 写入可产生多个默认地址，源码未提供数据库约束兜底。

## 3. CacheHelper 与 CacheEvictConsumer

### 3.1 两种缓存删除 API

`CacheHelper.deleteAfterUpdate(String... keys)` 是同步删除 API（`CacheHelper.java:254-287`）：每个 key 最多尝试 3 次；Redis `delete` 返回 false 在第一次就被视为“key 不存在即成功”（`260-269`），Redis 不可用立即失败并继续处理其它 key（`271-285`）。它只返回聚合 boolean，不负责发送 MQ；注释声称失败等待 MQ，但本方法本身没有发送动作，调用方必须另行集成，属于工程契约不完整。

`delayDoubleDelete(String key)` 是立即删一次、调度延迟删一次（`CacheHelper.java:313-352`），可变参数重载逐 key 调用（`358-362`）。第一次 Redis 不可用时直接 return，不调度第二次，也没有 MQ 兜底（`314-323`）；第二次异常才尝试向 `CACHE_EVICT_TOPIC` 发送纯 key（`327-350`）。因此第一次失败路径与文档宣称的“三重保障”不一致。调度器是每个 Bean 单线程线程池（`48-58`），大量写入时可能排队；线程池关闭时任务丢失需要运行态确认。

另外，`getWithCacheAside` 在 Redis 不可用时查 DB 不回填（`101-134`），锁版在锁失败后等待 100ms，仍 miss 则直接查 DB（`175-235`）；热点故障会把流量推向数据库。该降级策略是已确认设计，容量和背压效果需压测。

### 3.2 非法消息与重试注解

`CacheEvictConsumer` 的监听注解设置 topic、消费组和 `maxReconsumeTimes=3`（`CacheEvictConsumer.java:34-39`）。消息体以 `{` 开头时按 JSON 解析，否则按纯 key 解析（`44-56`）；null/空 key 记录 warning 后直接 return（`58-60`），非法消息不会抛异常，因此不会触发重试。这是已确认的“非法消息静默丢弃”语义，若消息格式错误本应进入 DLQ，则属于业务/工程缺口。

Redis 删除异常统一抛 `RuntimeException`（`67-70`），可以触发 RocketMQ 重试；但删除返回 false 仍记录成功（`63-65`），无法区分 key 不存在与底层失败。注解是最多 3 次，而代码文案写“指数退避，最多 16 次”（`67-70`，类注释 `27-29`），文档与实际配置漂移；真实 broker 的重试次数、退避和 DLQ 主题必须运行态确认。

## 4. 分类风险清单

### 4.1 代码 Bug

1. `UserService.getBlockList` 对 null `members()` 构造 `HashSet` 可能 NPE（`UserService.java:537-540`）。
2. `AuthController` 只在 access Bearer 存在时调用注销，refresh-only 直接成功返回（`AuthController.java:73-86`）。
3. 不存在用户名登录不调用失败计数，绕过账号/IP失败策略的一部分（`UserService.java:192-200`）。
4. `changePassword`、地址默认切换/删除补默认均不检查 DB 更新行数（`UserService.java:387-397`；`UserAddressService.java:237-247,356-366`）。
5. `CacheHelper.delayDoubleDelete` 第一次删除失败直接结束，不进入 MQ 兜底（`CacheHelper.java:314-323`）。
6. `CacheEvictConsumer` 非法消息 return 且 delete false 记成功（`CacheEvictConsumer.java:58-65`）。

### 4.2 业务逻辑

1. 注销“返回成功”不代表会话已撤销；输入契约和幂等语义未统一（`AuthController.java:74-86`）。
2. 改密后两次撤销重复，且 DB 密码与 Redis 凭据不是原子事务（`UserService.java:387-398`）。
3. 登录失败策略只对已存在用户的错误密码计数；不存在用户、验证码失败、禁用用户的计数策略不同（`UserService.java:170-212`）。
4. 批量资料对不存在用户部分成功，对其它业务异常整批失败，接口没有正式契约和大小边界（`UserService.java:277-296`；`UserController.java:97-100`）。
5. 删除默认地址后选择最新地址而非注释所称第一条（`UserAddressService.java:226-234`）。

### 4.3 分布式

1. Token 生成、刷新、注销、撤销均是多次 Redis 操作，无跨 key 原子性；任一步失败都会留下部分状态（`TokenService.java:85-105,183-188,203-231,242-262`）。
2. 地址锁在事务提交前释放，DB 唯一默认状态存在提交窗口（`UserAddressService.java:197-212`）。
3. 地址更新锁前查询、锁内不重读（`UserAddressService.java:131-174`）。
4. 主从读写与缓存回填可能出现旧读；延迟双删的 500ms 只是经验配置（`CacheHelper.java:290-308`；`application-datasource.properties:2-8`）。
5. RocketMQ 只在延迟第二删异常时发送，第一删失败没有消息补偿（`CacheHelper.java:314-352`）。

### 4.4 微服务

1. user 通过 `@SpringBootApplication` 同时扫描 `com.myxhs.user` 与 `com.myxhs.common`（`UserApplication.java:6-10`），公共 Bean 会直接进入信任边界。
2. Gateway 依赖 Token 中 role claim；role 变更不会影响存量 token，直到重新登录（`TokenService.java:57-76`；`UserService.java:218-222`）。
3. 内部/管理员端点使用 Header token，配置默认可为空（`UserController.java:41-48,71-89`；`application.yml:169-174`），是否被公共过滤器保护需运行态确认。
4. Feign 连接/读取超时为 500ms/2000ms，用户服务同步下游调用需验证超时传播与统一错误映射（`application.yml:60-68`）。

### 4.5 性能

1. 批量公开资料是逐 ID cache-aside，存在 N+1 和无上限请求放大（`UserService.java:277-296`）。
2. BCrypt 默认编码器会在登录/注册/改密消耗 CPU，strength 未外置（`PasswordEncoderConfig.java:20-23`）。
3. 延迟双删使用单线程调度器，突发写入可能堆积（`CacheHelper.java:48-58,327-352`）。
4. Cache miss、锁失败和 Redis 故障均可能直接打 DB；线程池/连接池容量与降级峰值需压测（`CacheHelper.java:101-134,207-235`；`application.yml:8-15,94-99`）。
5. 登录失败计数每次可能执行多次 Redis 操作，包括 Set 成员读取（`UserService.java:412-458`）。

### 4.6 工程

1. `AuthServiceTest`、`UserServiceTest` 与生产构造器/方法签名漂移，当前构建记录为 testCompile 失败；见第 5 节。
2. `AddressServiceTest` 未为 delete/setDefault 配置 `redissonClient.getLock` 返回值，静态上无法覆盖真实锁路径，运行时很可能 NPE；见第 5 节。
3. `CacheEvictConsumer` 注解最多 3 次与异常文案最多 16 次矛盾（`CacheEvictConsumer.java:27-29,34-39,67-70`）。
4. Dockerfile 只有模块参数和基础镜像，没有 jar 复制、启动命令、端口或健康检查（`my-xhs-user/Dockerfile:1-3`）。
5. pom 没有显式 Surefire/Failsafe、编译器或容器打包契约，且 plugins 闭合缩进异常但 XML 结构仍需 Maven 解析验证（`my-xhs-user/pom.xml:125-140`）。
6. 日志异步队列、文件路径和滚动容量需要部署权限/磁盘运行态验证（`logback-spring.xml:72-99,115-121`）。

### 4.7 安全基础

1. JWT fallback secret、Redis fallback password 和 MySQL root 凭据在仓库配置中明文存在（`application.yml:80-85,117-121`；`application-datasource.properties:2-8`），这是已确认的凭据暴露，应轮换并改为外部密钥注入。
2. `JwtProperties` 虽注释要求至少 32 字节，但没有校验；secret 可为空或弱配置（`JwtProperties.java:15-22`）。
3. Actuator 暴露 health/info/prometheus/metrics 且 health details always（`application.yml:123-151`）；是否被网关和直连端口限制需运行态确认。
4. `/me` 响应直接返回完整 phone/email，和 DTO 的脱敏注释不一致；当前是否允许用户查看自己的完整信息需业务确认（`UserService.java:545-560`；`UserInfoResponse.java:10-45`）。
5. 地址 VO 依赖转换层脱敏，实体和缓存中的手机号仍是明文（`UserAddressService.java:413-425`；`UserAddress.java:19-38`）。
6. refresh body 避免 query 日志泄露，但 DTO 没有 `@NotBlank`，Controller 只拦 null/空字符串，不拦空白（`RefreshTokenRequest.java:8-10`；`AuthController.java:61-67`）。

## 5. 测试可执行性与实现漂移

### 5.1 AddressServiceTest 的 lock mock

`AddressServiceTest.setUp` 只构造三个依赖并设置地址上限（`AddressServiceTest.java:59-72`）。新增测试显式 mock `getLock`、`tryLock`、持有线程和 unlock（`101-115`），所以新增路径具备锁对象。删除测试和设置默认测试没有配置 `redissonClient.getLock(...)`（`AddressServiceTest.java:131-168`），而生产方法会立即调用 `lock.tryLock`（`UserAddressService.java:197-204,329-336`）。Mockito 默认返回 null，直接执行这两项测试存在 NPE 风险；即便补上 lock mock，也没有验证 unlock、补默认、缓存失效、逻辑删除过滤和越权。

### 5.2 AuthServiceTest 编译错误与漂移

测试在 `AuthServiceTest.java:71-75` 仍按旧构造器传 7 个 UserService 参数、按旧构造器传 1 个 CaptchaService 参数；生产 UserService 需要 9 个依赖（`UserService.java:44-55`），CaptchaService 需要 RedisOperator 与 StringRedisTemplate（`CaptchaService.java:32-35`）。测试还在 `AuthServiceTest.java:101,124,141` 调用无 `clientIp` 的 `userService.login(request)`，而生产签名是 `login(LoginRequest,String)`（`UserService.java:160-168`）。这些是已确认编译错误，不是测试断言失败。

实现也已漂移：验证码生成生产写 `StringRedisTemplate`（`CaptchaService.java:50-58`），校验使用 GETDEL（`76-89`），测试却 mock `RedisOperator.set/get/delete`（`AuthServiceTest.java:152-185`）；登录 token 测试 stub `generateTokenPair(USER_ID)`，生产传入 `(user.getId(), user.getRole())`（`AuthServiceTest.java:94-99`；`UserService.java:218-220`）。即使补齐构造器，现有 stub 也不能证明生产路径。

### 5.3 UserServiceTest 编译错误与漂移

`UserServiceTest.java:70-73` 同样少传 `StringRedisTemplate` 与 `TransactionTemplate`；生产字段和构造器证据为 `UserService.java:44-55`。屏蔽测试 mock/verify `RedisOperator.sAdd`（`UserServiceTest.java:141-153`），生产使用 `StringRedisTemplate.opsForSet().add/remove/members`（`UserService.java:515-540`），因此实现已漂移。测试没有覆盖改密、删除、失败计数、批量部分成功、null members、role claim、禁用账号和 cache/DB 异常。

已记录的构建结论是：执行 `mvn -pl my-xhs-user -am test -DskipTests` 在 `testCompile` 阶段失败，确认 AuthServiceTest/UserServiceTest 的构造器和 login 参数错误；`-DskipTests` 只跳过执行，不跳过测试编译。当前不能把 target 中已有 jar/class 当作本轮源码测试通过证据。

## 6. 运行态确认清单

以下不是当前源码可单独确认的结论：

- 实际 `SHOW CREATE TABLE t_user/t_user_address` 是否包含 `role`、逻辑删除列、默认地址约束；初始化 SQL 与 Java role 漂移证据见 `User.java:48-49`、`UserService.java:123-126`，SQL 需另行核验。
- MyBatis-Plus `@TableLogic` 是否确实作用于所有 `selectById/selectList/selectOne/deleteById`，配置声明仅见 `application.yml:103-115`。
- Redisson lock lease、watchdog、事务提交与多实例并发窗口。
- Redis Sentinel 实际连接、GETDEL 支持、StringRedisTemplate 与 RedisOperator 的序列化一致性、Redis 失败时的错误映射。
- RocketMQ broker 实际重试次数、退避、DLQ、非法消息告警和消费者幂等。
- Nacos/Sentinel/Feign 的真实注册、配置覆盖、超时和直连 user 端口暴露。
- Actuator 访问控制、内部 token 为空时的过滤器行为、Gateway 对 role claim 和注销后的 JWT 失效认知。
- Docker base image 是否隐含 jar、启动命令、端口和健康检查；单独 Docker build/run 需要隔离环境验证。
- 主从延迟、DB 事务提交耗时、批量接口峰值、BCrypt CPU、缓存调度队列和日志队列丢弃情况。

## 7. 35 文件逐文件状态

状态定义：`已读取+有行号证据` 表示本轮直接读取并在本报告或既有报告中有源码行号；`仅读取摘要` 表示只保留摘要、无本轮逐文件证据；`测试不可执行` 表示已读取但当前不能作为可执行回归门禁；`暂不分析` 表示明确排除。本轮没有把任何候选文件标为仅读取摘要或暂不分析。

| # | 文件 | 状态 | 证据/说明 |
|---:|---|---|---|
| 1 | `my-xhs-user/Dockerfile` | 已读取+有行号证据 | `Dockerfile:1-3` |
| 2 | `my-xhs-user/pom.xml` | 已读取+有行号证据 | `pom.xml:12-15,18-131,133-140` |
| 3 | `src/main/java/com/myxhs/user/UserApplication.java` | 已读取+有行号证据 | `UserApplication.java:6-10` |
| 4 | `src/main/java/com/myxhs/user/config/JwtProperties.java` | 已读取+有行号证据 | `JwtProperties.java:10-22` |
| 5 | `src/main/java/com/myxhs/user/config/PasswordEncoderConfig.java` | 已读取+有行号证据 | `PasswordEncoderConfig.java:17-23` |
| 6 | `src/main/java/com/myxhs/user/consumer/CacheEvictConsumer.java` | 已读取+有行号证据 | `CacheEvictConsumer.java:34-73` |
| 7 | `src/main/java/com/myxhs/user/controller/AuthController.java` | 已读取+有行号证据 | `AuthController.java:52-86` |
| 8 | `src/main/java/com/myxhs/user/controller/UserAddressController.java` | 已读取+有行号证据 | `UserAddressController.java:21-95` |
| 9 | `src/main/java/com/myxhs/user/controller/UserController.java` | 已读取+有行号证据 | `UserController.java:41-153` |
| 10 | `src/main/java/com/myxhs/user/dto/request/AddressCreateRequest.java` | 已读取+有行号证据 | `AddressCreateRequest.java:12-45` |
| 11 | `src/main/java/com/myxhs/user/dto/request/AddressUpdateRequest.java` | 已读取+有行号证据 | `AddressUpdateRequest.java:15-79` |
| 12 | `src/main/java/com/myxhs/user/dto/request/ChangePasswordRequest.java` | 已读取+有行号证据 | `ChangePasswordRequest.java:11-18` |
| 13 | `src/main/java/com/myxhs/user/dto/request/LoginRequest.java` | 已读取+有行号证据 | `LoginRequest.java:9-24` |
| 14 | `src/main/java/com/myxhs/user/dto/request/RefreshTokenRequest.java` | 已读取+有行号证据 | `RefreshTokenRequest.java:8-10` |
| 15 | `src/main/java/com/myxhs/user/dto/request/RegisterRequest.java` | 已读取+有行号证据 | `RegisterRequest.java:11-33` |
| 16 | `src/main/java/com/myxhs/user/dto/request/UpdateUserRequest.java` | 已读取+有行号证据 | `UpdateUserRequest.java:15-45` |
| 17 | `src/main/java/com/myxhs/user/dto/response/AddressVO.java` | 已读取+有行号证据 | `AddressVO.java:11-43` |
| 18 | `src/main/java/com/myxhs/user/dto/response/CaptchaResponse.java` | 已读取+有行号证据 | `CaptchaResponse.java:9-17` |
| 19 | `src/main/java/com/myxhs/user/dto/response/TokenResponse.java` | 已读取+有行号证据 | `TokenResponse.java:9-23` |
| 20 | `src/main/java/com/myxhs/user/dto/response/UserInfoResponse.java` | 已读取+有行号证据 | `UserInfoResponse.java:15-45` |
| 21 | `src/main/java/com/myxhs/user/dto/response/UserPublicInfoResponse.java` | 已读取+有行号证据 | `UserPublicInfoResponse.java:15-38` |
| 22 | `src/main/java/com/myxhs/user/entity/User.java` | 已读取+有行号证据 | `User.java:13-49` |
| 23 | `src/main/java/com/myxhs/user/entity/UserAddress.java` | 已读取+有行号证据 | `UserAddress.java:11-38` |
| 24 | `src/main/java/com/myxhs/user/mapper/UserAddressMapper.java` | 已读取+有行号证据 | `UserAddressMapper.java:3-11` |
| 25 | `src/main/java/com/myxhs/user/mapper/UserMapper.java` | 已读取+有行号证据 | `UserMapper.java:3-11` |
| 26 | `src/main/java/com/myxhs/user/service/CaptchaService.java` | 已读取+有行号证据 | `CaptchaService.java:32-89` |
| 27 | `src/main/java/com/myxhs/user/service/TokenService.java` | 已读取+有行号证据 | `TokenService.java:67-337` |
| 28 | `src/main/java/com/myxhs/user/service/UserAddressService.java` | 已读取+有行号证据 | `UserAddressService.java:57-437` |
| 29 | `src/main/java/com/myxhs/user/service/UserService.java` | 已读取+有行号证据 | `UserService.java:160-584` |
| 30 | `src/main/resources/application-datasource.properties` | 已读取+有行号证据 | `application-datasource.properties:1-9` |
| 31 | `src/main/resources/application.yml` | 已读取+有行号证据 | `application.yml:1-174` |
| 32 | `src/main/resources/logback-spring.xml` | 已读取+有行号证据 | `logback-spring.xml:10-121` |
| 33 | `src/test/java/com/myxhs/user/service/AddressServiceTest.java` | 测试不可执行 | 已读取；`AddressServiceTest.java:101-115` 仅新增路径 mock lock，`131-168` 缺失 lock mock |
| 34 | `src/test/java/com/myxhs/user/service/AuthServiceTest.java` | 测试不可执行 | 已读取；构造器/签名错误 `AuthServiceTest.java:71-75,101,124,141` |
| 35 | `src/test/java/com/myxhs/user/service/UserServiceTest.java` | 测试不可执行 | 已读取；构造器和 Redis 实现漂移 `UserServiceTest.java:70-73,141-153` |

## 8. 修复与验证结果

### 已修复
- `UserService.getBlockList` 对 Redis `members()` 返回 null 的处理：null 现在规范化为空集合，避免 NPE。
- user 测试构造器、login 参数和 Redis mock 已同步当前生产签名。
- 根 `pom.xml` 增加 Surefire 3.2.5，使 JUnit 5 测试真正执行。
- AddressServiceTest 的删除/设默认路径补齐 Redisson lock mock。

### 验证
- `mvn -pl my-xhs-user -am test`：`my-xhs-common` 53 个测试、user 14 个测试全部通过。
- `mvn -pl my-xhs-user -am -Dmaven.test.skip=true package`：构建成功。
- user 服务已用新 jar 重启，`19001/actuator/health` 返回 `UP`。

## 9. 是否达到 98%

不能把“测试通过”直接等同于 98% 业务覆盖。候选池 35 个文件已逐个读取并有行号证据，主要业务链和关键问题已覆盖；但真实 schema、主从路由、RocketMQ 重试/DLQ、Redis 故障、并发锁窗口和完整接口链路仍需运行态验证。因此当前结论是：**静态源码覆盖达到目标范围，整体业务验证仍未达到可证明的 98%**。