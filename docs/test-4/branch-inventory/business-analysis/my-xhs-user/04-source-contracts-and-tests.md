# my-xhs-user 源码契约、测试与工程补充分析

## 1. 分析边界与模块定位

本文件补充前 3 份分析未充分展开的启动、配置、DTO、实体、Mapper、测试和构建契约；结论只基于当前工作树源码与配置，不把注释或历史修复说明当作运行事实。`my-xhs-user` 是认证、用户资料、地址及屏蔽关系的状态中心，入口由 Spring MVC 控制器承接，状态分别落在 MySQL、Redis、RocketMQ 和 JWT/HMAC 会话中。

核心状态流如下：

```text
HTTP DTO 校验
  -> Controller（X-User-Id / 内部调用令牌）
  -> Service
       -> MyBatis-Plus t_user / t_user_address
       -> Redis token、验证码、缓存、屏蔽集合、锁
       -> RocketMQ 缓存删除兜底
  -> Response DTO
```

## 2. 指定文件的源码级契约

### 2.1 启动与安全基础

| 文件 | 职责 | 关键约束 | 对业务的影响 |
|---|---|---|---|
| `my-xhs-user/src/main/java/com/myxhs/user/UserApplication.java:6-10` | Spring Boot 启动入口，并扫描 `com.myxhs.user` 与 `com.myxhs.common`。 | common 包中的过滤器、配置和基础设施 Bean 也会进入容器；启动成功依赖两包的组件扫描结果。 | user 不是孤立 Spring 应用，common 的认证信任边界、缓存和响应封装会直接影响所有接口；扫描范围过宽时也可能引入未预期 Bean。 |
| `my-xhs-user/src/main/java/com/myxhs/user/config/JwtProperties.java:10-22` | 将 `jwt.*` 绑定为签名密钥及 access/refresh 过期时间。 | 过期时间单位是毫秒；代码默认 access 30 分钟、refresh 7 天，secret 没有运行时强制非空或长度校验。 | `TokenService` 用同一 secret 生成和解析两类 Token；默认密钥或空配置会使跨环境凭据可预测、轮换困难，时间单位误配会扩大或缩短全部会话窗口。 |
| `my-xhs-user/src/main/java/com/myxhs/user/config/PasswordEncoderConfig.java:17-23` | 注册 BCrypt `PasswordEncoder` Bean。 | 使用默认 `BCryptPasswordEncoder()` strength；没有密码升级策略、成本参数外置或旧算法兼容策略。 | 注册、改密和登录比对共享同一算法；成本提高会增加登录/注册 CPU 消耗，算法迁移需要同时处理存量密码。 |

### 2.2 七个 request DTO

| 文件 | 职责 | 关键约束 | 对业务的影响 |
|---|---|---|---|
| `my-xhs-user/src/main/java/com/myxhs/user/dto/request/RegisterRequest.java:12-33` | 定义注册用户名、密码、可选手机号和图形验证码输入。 | 用户名 4~32 位且只允许字母/数字/下划线；密码 6~64 位；手机号和验证码有格式/非空校验。 | DTO 只挡住格式，不保证用户名/手机号并发唯一性，最终仍依赖 `UserService` 查询加数据库唯一索引；验证码是注册链路的前置一次性凭据。 |
| `my-xhs-user/src/main/java/com/myxhs/user/dto/request/LoginRequest.java:10-24` | 定义用户名密码登录及验证码输入。 | 四个字段均 `@NotBlank`，未限制用户名长度或密码长度。 | 认证层会接收较大输入并交给 BCrypt/查询；验证码消费发生在用户查询之前，错误尝试会消耗验证码，符合一次性安全语义但增加客户端重试成本。 |
| `my-xhs-user/src/main/java/com/myxhs/user/dto/request/RefreshTokenRequest.java:8-10` | 以 JSON body 承载 refresh token，避免 query string 进入访问日志。 | `refreshToken` 没有 `@NotBlank`；Controller 手动判空，但空白字符串可继续进入解析。 | 凭据不再直接出现在 query 中，但 body、日志和异常处理仍需脱敏；空白输入的错误语义依赖 TokenService，而不是统一 Bean Validation。 |
| `my-xhs-user/src/main/java/com/myxhs/user/dto/request/ChangePasswordRequest.java:10-18` | 定义旧密码和新密码。 | 两者非空；新密码 6~64 位，旧密码无长度约束。 | 改密要求先比对旧 BCrypt 密码；成功后生产实现会撤销用户凭据，因此测试必须覆盖“数据库更新失败/撤销失败”的一致性边界。 |
| `my-xhs-user/src/main/java/com/myxhs/user/dto/request/UpdateUserRequest.java:15-45` | 定义资料部分更新字段，并通过 `hasNoFields()` 防止空更新。 | 昵称/签名、头像有长度上限；性别 0~2；生日必须过去；手机号、邮箱有格式校验；所有字段可空。 | `null` 表示不更新，无法表达“清空字段”；`hasNoFields()` 保护了空 `UPDATE`，但空字符串未统一转 null，部分字段可能以空值进入更新。手机号唯一性仍是先查后写，依靠数据库约束兜底。 |
| `my-xhs-user/src/main/java/com/myxhs/user/dto/request/AddressCreateRequest.java:12-45` | 定义新增地址的收货人、电话、行政区、详细地址和默认标记。 | 文本必填且有长度上限；电话必须为中国大陆手机号；`isDefault` 可空。 | `UserAddressService` 将首条地址强制设为默认，即使请求未要求；地址上限和默认切换由服务层/锁维护，DTO 本身不表达数量或跨字段约束。 |
| `my-xhs-user/src/main/java/com/myxhs/user/dto/request/AddressUpdateRequest.java:15-79` | 定义地址部分更新，并通过手写 setter 把空白字符串转为 null。 | 非 null 字段才更新；空字符串在 Jackson 反序列化阶段转 null；电话仅对非 null 值做 Pattern 校验。 | 这是“不能清空字段”的显式契约，同时会把前端传入的空白视为“不修改”；只设置 `isDefault=false` 不会取消当前默认，默认状态变更只处理 false→true。 |

### 2.3 五个 response DTO

| 文件 | 职责 | 关键约束 | 对业务的影响 |
|---|---|---|---|
| `my-xhs-user/src/main/java/com/myxhs/user/dto/response/UserInfoResponse.java:15-45` | `/me` 用户信息输出，并提供手机号/邮箱脱敏工具。 | DTO 注释描述脱敏，但字段 builder 本身不会自动脱敏；`maskPhone` 对短号原样返回，`maskEmail` 对 `@` 前只有一个字符的地址原样返回。 | 当前 `UserService.toUserInfoResponse` 在 `:545-560` 直接返回原手机号和邮箱，实际与 DTO 的脱敏意图不一致；敏感信息是否暴露由转换方法决定，而不是 DTO 类型保证。 |
| `my-xhs-user/src/main/java/com/myxhs/user/dto/response/UserPublicInfoResponse.java:15-38` | 公开主页及内部资料调用的精简响应。 | 明确不含 phone、email、birthday；只输出身份、展示资料和创建时间。 | 公开接口能避免直接泄露隐私，但批量查询仍逐用户走缓存/数据库；内部调用复用该 DTO，通知等下游只能得到精简资料。 |
| `my-xhs-user/src/main/java/com/myxhs/user/dto/response/AddressVO.java:11-44` | 地址查询/写入结果对象。 | 包含 receiverPhone 但只靠转换层脱敏；默认值使用 Integer 0/1；包含创建、更新时间。 | 地址写入后直接返回 VO，若转换漏掉脱敏则收货电话泄露；下单读取默认地址依赖 `isDefault` 与逻辑删除查询一致。 |
| `my-xhs-user/src/main/java/com/myxhs/user/dto/response/TokenResponse.java:9-23` | 返回 access token、refresh token 和 per-session HMAC secret。 | access 30 分钟、refresh 7 天是文档化约束；HMAC secret 与 refresh 同生命周期并按 userId 覆盖。 | 返回的不只是 JWT，而是 Gateway 验签所需的会话密钥；同一用户再次登录会替换 Redis 会话并使旧设备失效，响应契约因此天然是单设备模型。 |
| `my-xhs-user/src/main/java/com/myxhs/user/dto/response/CaptchaResponse.java:9-17` | 返回验证码 key 和 Base64 PNG。 | 图片以 `data:image/png;base64,` 前缀返回；key 是客户端后续提交的关联句柄，不是验证码内容。 | 生成接口把图片传输成本放到同步请求；验证码内容只应留在 Redis，若日志或异常输出内容会破坏挑战机制。 |

### 2.4 实体与 Mapper

| 文件 | 职责 | 关键约束 | 对业务的影响 |
|---|---|---|---|
| `my-xhs-user/src/main/java/com/myxhs/user/entity/User.java:13-49` | 将 `t_user` 映射为用户聚合记录，继承公共 id、逻辑删除、时间字段。 | `@TableName("t_user")`；status 约定 0/1；role 约定 `OPERATOR/TECH`；密码字段保存 BCrypt 结果。 | `role` 会被注册写入并作为 JWT claim 发给 Gateway，但初始化 SQL 的 `t_user` 没有 role 列。因此只能表述为“初始化 SQL 与 Java 模型漂移”，不能据此断言线上数据库一定缺列；最终事实需以实际数据库 schema 核验。 |
| `my-xhs-user/src/main/java/com/myxhs/user/entity/UserAddress.java:11-38` | 将 `t_user_address` 映射为用户地址记录。 | 继承公共逻辑删除字段；`userId`、`isDefault` 是业务状态核心，但实体没有数据库级唯一默认约束声明。 | 地址归属、默认切换和删除补默认依赖 service 的 userId 锁与 MyBatis-Plus 逻辑删除；任何绕过 service 的写入都可能制造多个默认地址或孤立地址。 |
| `my-xhs-user/src/main/java/com/myxhs/user/mapper/UserMapper.java:3-11` | 继承 `BaseMapper<User>` 提供标准 CRUD。 | 无自定义 SQL；查询是否自动排除 deleted 依赖 MyBatis-Plus 全局逻辑删除配置和实体父类映射。 | 注册/登录/资料更新/管理员删除都共享同一 CRUD 语义；schema 漂移会在 insert/select 时集中暴露，Mapper 本身没有字段兼容层。 |
| `my-xhs-user/src/main/java/com/myxhs/user/mapper/UserAddressMapper.java:3-11` | 继承 `BaseMapper<UserAddress>` 提供地址 CRUD。 | 无自定义 SQL；逻辑删除、字段命名转换和条件拼接依赖全局 MyBatis-Plus 配置。 | `deleteById` 的“删除”是逻辑删除前提；列表、默认地址和补默认查询必须始终使用同一逻辑删除规则，否则已删地址可能继续成为默认地址。 |

## 3. 已确认的业务与工程风险补充

### 3.1 批量资料接口的集合与异常语义

`UserService.batchGetUserPublicInfo` 对 null/空集合返回空 Map，对 null 元素跳过；不存在用户的 `USER_NOT_FOUND` 被跳过，其他 `BizException` 重新抛出（`my-xhs-user/src/main/java/com/myxhs/user/service/UserService.java:277-297`）。这定义了“部分成功”而不是全量失败，但没有批量大小上限，且每个 ID 调用一次 cache-aside（`UserService.java:272-274`），形成 N+1 放大。测试重点应明确：空集合是否必须 200+空对象、重复 ID 是否去重、null ID 是否忽略、不存在 ID 是否出现在结果中、Redis/DB 异常是否整批失败；这些语义目前主要由实现而非接口契约固定。

### 3.2 block list 的 null NPE 与生产/测试实现漂移

生产屏蔽列表使用 `StringRedisTemplate.opsForSet()` 写入字符串成员（`my-xhs-user/src/main/java/com/myxhs/user/service/UserService.java:515-531`），读取时直接把 `members()` 传给 `HashSet`（`UserService.java:535-540`）。Redis 返回 null 时这里会 NPE；空集合应被规范化为空集合。现有 `UserServiceTest` 仍注入 `RedisOperator` 并验证 `sAdd`（`my-xhs-user/src/test/java/com/myxhs/user/service/UserServiceTest.java:51-56,141-153`），与生产实现不一致，不能证明屏蔽链路可用。

### 3.3 地址逻辑删除依赖

全局配置把 `deleted` 设为逻辑删除字段、1 为已删、0 为未删（`my-xhs-user/src/main/resources/application.yml:103-115`）；实体继承 `BaseEntity`，但地址服务的业务正确性仍依赖 MyBatis-Plus 自动追加逻辑删除条件。删除默认地址后按 `created_at DESC` 选择一条 `isDefault=0` 地址（`my-xhs-user/src/main/java/com/myxhs/user/service/UserAddressService.java:215-247`），注释称“第一条”但实现是最新地址；同时锁在事务提交前释放（`UserAddressService.java:197-213`），数据库没有唯一默认约束时仍存在提交窗口。

### 3.4 role schema 结论边界

Java 在 `User.java:48-49` 声明 role，注册在 `UserService.java:115-126` 写入，登录在 `UserService.java:218-222` 传给 TokenService；而仓库初始化 SQL 的 `t_user` 字段范围在 `sql/migration/user/V1__init_user.sql:13-32`，对应部署副本也在 `deploy/docker/my-xhs-deploy-zip/sql/migration/user/V1__init_user.sql:13-32`，两份均未看到 role。正确结论是初始化 SQL 与 Java 模型漂移，不能断言线上数据库缺列。上线判断必须执行受控 schema 核验，例如实际环境的 `SHOW CREATE TABLE t_user`，本次未执行。

### 3.5 Actuator、硬编码凭据与 Dockerfile 构建契约

- Actuator 暴露 `health,info,prometheus,metrics`，且 health `show-details: always`（`my-xhs-user/src/main/resources/application.yml:123-151`）。这扩大了管理面和运行信息暴露范围；`redis.health` 被关闭不等于其它指标端点已受保护。
- Redis fallback 密码和 JWT fallback secret 出现在 `application.yml:80-85,117-121`，主从 MySQL root 凭据直接出现在 `application-datasource.properties:2-9`。Nacos/环境变量覆盖不能消除凭据已经进入仓库和构建上下文的事实，必须轮换并改为外部密钥注入。
- `Dockerfile:1-3` 只有 `ARG MODULE`、基础镜像和 runtime 阶段，没有复制 jar、启动命令、端口、健康检查或模块构建步骤。它隐含依赖 `my-xhs-base` 已预置运行契约；单独执行 `docker build` 无法从该文件推导产物来源和启动方式，属于 Dockerfile 构建契约不完整，而不是仅仅缺少注释。

## 4. 配置、日志与依赖契约

| 文件 | 职责 | 关键约束 | 对业务的影响 |
|---|---|---|---|
| `my-xhs-user/src/main/resources/application.yml:1-172` | 定义端口、Tomcat、Nacos/Sentinel、Redis、Feign、MyBatis-Plus、JWT、Actuator、RocketMQ 与内部令牌。 | 端口 19001；Redis/Nacos/RocketMQ 地址为固定内网地址；JWT 与 Redis 有 fallback 凭据；逻辑删除依赖全局配置；内部/管理员 token 默认可为空。 | 连接外部中间件和状态系统的契约集中在本地配置；环境切换、凭据泄露、空 token 和 Actuator 暴露会直接影响可用性与安全边界。Feign 500ms/2000ms 超时也要求下游调用不能把同步链路拖长（`application.yml:60-79`）。 |
| `my-xhs-user/src/main/resources/application-datasource.properties:1-9` | 提供主库 3306、从库 3307 的 JDBC 与 root 凭据。 | 开启 readwrite；主从 URL、用户名和密码明文配置；连接超时 5s、socket 超时 30s。 | 读写路由由 common/数据源装配决定，业务代码没有显式路由标记；主从延迟可能使更新后读和缓存回填看到旧数据，明文凭据则是工程安全高风险。 |
| `my-xhs-user/src/main/resources/logback-spring.xml:10-121` | 配置控制台、异步滚动文件、JSON 文件和 MDC trace/span/userId 输出。 | info/error 文件分别滚动 30 天/3GB 和 1GB；JSON 写 `/logs`；异步队列 1024/512；root 同时挂四类 appender。 | 异步日志降低业务线程阻塞，但队列满、容器无 `/logs` 写权限或文件系统容量不足会影响观测；日志 pattern 不主动打印密码/token，但异常和请求日志仍需验证不会携带凭据。 |
| `my-xhs-user/pom.xml:12-140` | 声明 Web、Validation、MyBatis-Plus、MySQL、Redis、Redisson、RocketMQ、Nacos、Sentinel、JWT、common、Lombok、SkyWalking 和测试依赖，并使用 Spring Boot Maven 插件。 | 依赖版本由 parent 管理；打包为 jar；没有显式 Surefire/Failsafe、编译器或独立容器打包契约；XML `plugins` 缩进虽不影响解析，但维护性较差。 | 生产功能依赖大量外部基础设施，单元测试理论上可 mock；但测试源码构造器与生产构造器已漂移，依赖声明并不能保证测试可编译。 |

## 5. 三个测试 Java：编译性、覆盖与实现一致性

### 5.1 `AddressServiceTest.java`

测试通过 Mockito 注入 `UserAddressMapper`、`RedisOperator`、`RedissonClient`，并手工设置 `addressLimit=20`（`my-xhs-user/src/test/java/com/myxhs/user/service/AddressServiceTest.java:45-72`），覆盖列表、新增、删除和设置默认（`AddressServiceTest.java:74-168`）。其构造器参数与当前 `UserAddressService` 的三个依赖一致，因此从静态签名看**可编译**；但删除测试只 mock `isDefault=0` 路径，未验证默认删除后的补默认、逻辑删除过滤、归属越权、空地址列表和锁释放。新增测试 mock `RedisOperator.set`，与当前服务实现的地址缓存写入契约仍需结合 `UserAddressService.java:398-437` 核对，不能只凭断言 insert 成功判定缓存一致。

### 5.2 `AuthServiceTest.java`

登录测试覆盖成功、密码错误、用户不存在，验证码测试覆盖生成和正确/错误校验（`my-xhs-user/src/test/java/com/myxhs/user/service/AuthServiceTest.java:78-191`）。当前源码下该文件**不可编译**：`UserService` 生产构造器需要 `UserMapper、TokenService、CaptchaService、RedisOperator、RedissonClient、StringRedisTemplate、CacheHelper、PasswordEncoder、TransactionTemplate`，测试在 `:69-74` 只传 7 个参数；`CaptchaService` 生产构造器需要 `RedisOperator` 与 `StringRedisTemplate`，测试 `:75` 只传 1 个参数。即使补齐构造器，验证码测试 mock 的 `RedisOperator.set/get/delete` 也与生产生成使用 `StringRedisTemplate`、校验使用 `getAndDelete` 的实现（`my-xhs-user/src/main/java/com/myxhs/user/service/CaptchaService.java:34-36,55-58,76-89`）不一致，当前测试不能证明生产验证码链路。

### 5.3 `UserServiceTest.java`

测试覆盖资料更新、缓存查询、用户不存在、屏蔽成功和屏蔽自己（`my-xhs-user/src/test/java/com/myxhs/user/service/UserServiceTest.java:76-162`）。当前源码下该文件**不可编译**，原因同样是 `UserService` 构造器在 `:65-73` 少传 `StringRedisTemplate` 与 `TransactionTemplate`。此外，屏蔽测试在 `:141-153` mock/verify `RedisOperator.sAdd`，但生产代码在 `UserService.java:515-540` 使用 `StringRedisTemplate`，即便修复构造器也会出现测试与生产实现不一致。测试没有覆盖 batch 空集合、不存在 ID、null members NPE、手机号并发唯一性、role token claim、禁用用户和改密后的凭据撤销。

### 5.4 测试结论

本轮实际执行了 `mvn -pl my-xhs-user -am test -DskipTests`，在 `testCompile` 阶段失败，确认 `AuthServiceTest` 和 `UserServiceTest` 存在 6 个编译错误：UserService 构造器缺少 `StringRedisTemplate` 与 `TransactionTemplate`，CaptchaService 构造器缺少 `StringRedisTemplate`，login 调用缺少 clientIp 参数。`AddressServiceTest` 未暴露编译错误，但尚未执行测试逻辑。当前三份测试不能作为生产回归门禁：AddressServiceTest 分支覆盖不足；AuthServiceTest、UserServiceTest 明确编译失败，且三者均不同程度保留旧 Redis 抽象。

## 6. 风险与测试重点

| 分类 | 当前事实 | 优先测试/验证 |
|---|---|---|
| 代码 Bug | block list `members()` 为 null 时构造 HashSet 可能 NPE；DTO 脱敏工具与 `/me` 转换不一致。 | Redis 返回 null、空集合、真实 StringRedisTemplate 集成；验证 `/me` 与公开资料的字段边界。 |
| 业务逻辑 | 批量资料是部分成功；空集合/不存在 ID/异常语义由实现决定；地址默认补选顺序与注释不一致。 | 明确 API 契约并测试空集合、重复/null/不存在 ID、非 USER_NOT_FOUND 异常和默认地址删除后的选择。 |
| 数据一致性 | 默认地址无数据库唯一约束；地址逻辑删除正确性依赖 MyBatis-Plus 全局装配；主从可能延迟。 | 并发新增/切换/删除，事务提交窗口，实际 SHOW CREATE TABLE 与 migration 对账。 |
| 安全 | fallback 凭据、root 数据库密码、Actuator details/metrics 暴露；内部 token 默认空。 | 密钥扫描、环境覆盖验证、Actuator 访问控制、直连 user 服务和空 token 行为。 |
| 工程/部署 | Dockerfile 没有 jar 来源、启动命令、健康检查；测试构造器已漂移。 | 在隔离构建环境验证 base image 的输入输出契约；修正测试依赖注入后再运行编译和单元测试。 |

## 7. 35 文件覆盖状态表

依据 `docs/test-4/branch-inventory/business-analysis/my-xhs-user/00-file-coverage-baseline.md` 的 35 文件候选池。`target/` 为构建产物，不计入 35 文件。

状态说明：`已读取+有行号证据` 表示完成静态源码审阅，不代表真实中间件或接口运行验证通过；`测试不可执行` 表示当前测试不能作为回归门禁；`暂不分析` 表示明确排除。

| # | 文件 | 状态 |
|---:|---|---|
| 1 | `my-xhs-user/Dockerfile` | 已读取+有行号证据 |
| 2 | `my-xhs-user/pom.xml` | 已读取+有行号证据 |
| 3 | `my-xhs-user/src/main/java/com/myxhs/user/UserApplication.java` | 已读取+有行号证据 |
| 4 | `my-xhs-user/src/main/java/com/myxhs/user/config/JwtProperties.java` | 已读取+有行号证据 |
| 5 | `my-xhs-user/src/main/java/com/myxhs/user/config/PasswordEncoderConfig.java` | 已读取+有行号证据 |
| 6 | `my-xhs-user/src/main/java/com/myxhs/user/consumer/CacheEvictConsumer.java` | 已覆盖（前文链路，补充结论见 `03-data-distributed-engineering.md:47-53`） |
| 7 | `my-xhs-user/src/main/java/com/myxhs/user/controller/AuthController.java` | 已覆盖（前文链路，接口证据见 `02-business-flow.md:3-18,60-68`） |
| 8 | `my-xhs-user/src/main/java/com/myxhs/user/controller/UserAddressController.java` | 已覆盖（前文链路，接口证据见 `02-business-flow.md:85-98`） |
| 9 | `my-xhs-user/src/main/java/com/myxhs/user/controller/UserController.java` | 已覆盖（前文链路，接口证据见 `02-business-flow.md:70-83,100-111`） |
| 10 | `my-xhs-user/src/main/java/com/myxhs/user/dto/request/AddressCreateRequest.java` | 已读取+有行号证据 |
| 11 | `my-xhs-user/src/main/java/com/myxhs/user/dto/request/AddressUpdateRequest.java` | 已读取+有行号证据 |
| 12 | `my-xhs-user/src/main/java/com/myxhs/user/dto/request/ChangePasswordRequest.java` | 已读取+有行号证据 |
| 13 | `my-xhs-user/src/main/java/com/myxhs/user/dto/request/LoginRequest.java` | 已读取+有行号证据 |
| 14 | `my-xhs-user/src/main/java/com/myxhs/user/dto/request/RefreshTokenRequest.java` | 已读取+有行号证据 |
| 15 | `my-xhs-user/src/main/java/com/myxhs/user/dto/request/RegisterRequest.java` | 已读取+有行号证据 |
| 16 | `my-xhs-user/src/main/java/com/myxhs/user/dto/request/UpdateUserRequest.java` | 已读取+有行号证据 |
| 17 | `my-xhs-user/src/main/java/com/myxhs/user/dto/response/AddressVO.java` | 已读取+有行号证据 |
| 18 | `my-xhs-user/src/main/java/com/myxhs/user/dto/response/CaptchaResponse.java` | 已读取+有行号证据 |
| 19 | `my-xhs-user/src/main/java/com/myxhs/user/dto/response/TokenResponse.java` | 已读取+有行号证据 |
| 20 | `my-xhs-user/src/main/java/com/myxhs/user/dto/response/UserInfoResponse.java` | 已读取+有行号证据 |
| 21 | `my-xhs-user/src/main/java/com/myxhs/user/dto/response/UserPublicInfoResponse.java` | 已读取+有行号证据 |
| 22 | `my-xhs-user/src/main/java/com/myxhs/user/entity/User.java` | 已读取+有行号证据 |
| 23 | `my-xhs-user/src/main/java/com/myxhs/user/entity/UserAddress.java` | 已读取+有行号证据 |
| 24 | `my-xhs-user/src/main/java/com/myxhs/user/mapper/UserAddressMapper.java` | 已读取+有行号证据 |
| 25 | `my-xhs-user/src/main/java/com/myxhs/user/mapper/UserMapper.java` | 已读取+有行号证据 |
| 26 | `my-xhs-user/src/main/java/com/myxhs/user/service/CaptchaService.java` | 已覆盖（测试一致性见本文件第 5 节） |
| 27 | `my-xhs-user/src/main/java/com/myxhs/user/service/TokenService.java` | 已覆盖（前文 Token 链路，补充 role 证据见本文件第 3.4 节） |
| 28 | `my-xhs-user/src/main/java/com/myxhs/user/service/UserAddressService.java` | 已覆盖（前文地址链路，补充逻辑删除依赖见本文件第 3.3 节） |
| 29 | `my-xhs-user/src/main/java/com/myxhs/user/service/UserService.java` | 已覆盖（前文资料/批量/屏蔽链路，补充证据见本文件第 3 节） |
| 30 | `my-xhs-user/src/main/resources/application-datasource.properties` | 已读取+有行号证据 |
| 31 | `my-xhs-user/src/main/resources/application.yml` | 已读取+有行号证据 |
| 32 | `my-xhs-user/src/main/resources/logback-spring.xml` | 已读取+有行号证据 |
| 33 | `my-xhs-user/src/test/java/com/myxhs/user/service/AddressServiceTest.java` | 已读取+有行号证据 |
| 34 | `my-xhs-user/src/test/java/com/myxhs/user/service/AuthServiceTest.java` | 已读取+有行号证据 |
| 35 | `my-xhs-user/src/test/java/com/myxhs/user/service/UserServiceTest.java` | 已读取+有行号证据 |

本轮未发现候选池中的“未覆盖”或“暂不分析”文件；但“已读取+有行号证据”不等于运行验证通过。部署 SQL、common 依赖类用于核对 role/schema、缓存补偿和直连信任边界，不属于该 35 文件候选池。