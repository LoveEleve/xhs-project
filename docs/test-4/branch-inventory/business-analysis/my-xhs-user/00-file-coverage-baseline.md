# my-xhs-user 文件覆盖基线

## 顶层盘点

依据：`docs/test-4/branch-inventory/project-directories/my-xhs-user/inventory.md`

- `Dockerfile`
- `pom.xml`
- `src/`
- `target/`（构建生成物，不纳入源码逻辑分析）

## 本轮源码与配置候选池

### 顶层构建/运行文件
- `my-xhs-user/Dockerfile`
- `my-xhs-user/pom.xml`

### Java 源码
- `src/main/java/com/myxhs/user/UserApplication.java`
- `src/main/java/com/myxhs/user/config/JwtProperties.java`
- `src/main/java/com/myxhs/user/config/PasswordEncoderConfig.java`
- `src/main/java/com/myxhs/user/consumer/CacheEvictConsumer.java`
- `src/main/java/com/myxhs/user/controller/AuthController.java`
- `src/main/java/com/myxhs/user/controller/UserAddressController.java`
- `src/main/java/com/myxhs/user/controller/UserController.java`
- `src/main/java/com/myxhs/user/dto/request/AddressCreateRequest.java`
- `src/main/java/com/myxhs/user/dto/request/AddressUpdateRequest.java`
- `src/main/java/com/myxhs/user/dto/request/ChangePasswordRequest.java`
- `src/main/java/com/myxhs/user/dto/request/LoginRequest.java`
- `src/main/java/com/myxhs/user/dto/request/RefreshTokenRequest.java`
- `src/main/java/com/myxhs/user/dto/request/RegisterRequest.java`
- `src/main/java/com/myxhs/user/dto/request/UpdateUserRequest.java`
- `src/main/java/com/myxhs/user/dto/response/AddressVO.java`
- `src/main/java/com/myxhs/user/dto/response/CaptchaResponse.java`
- `src/main/java/com/myxhs/user/dto/response/TokenResponse.java`
- `src/main/java/com/myxhs/user/dto/response/UserInfoResponse.java`
- `src/main/java/com/myxhs/user/dto/response/UserPublicInfoResponse.java`
- `src/main/java/com/myxhs/user/entity/User.java`
- `src/main/java/com/myxhs/user/entity/UserAddress.java`
- `src/main/java/com/myxhs/user/mapper/UserAddressMapper.java`
- `src/main/java/com/myxhs/user/mapper/UserMapper.java`
- `src/main/java/com/myxhs/user/service/CaptchaService.java`
- `src/main/java/com/myxhs/user/service/TokenService.java`
- `src/main/java/com/myxhs/user/service/UserAddressService.java`
- `src/main/java/com/myxhs/user/service/UserService.java`

### 配置
- `src/main/resources/application-datasource.properties`
- `src/main/resources/application.yml`
- `src/main/resources/logback-spring.xml`

### 测试源码
- `src/test/java/com/myxhs/user/service/AddressServiceTest.java`
- `src/test/java/com/myxhs/user/service/AuthServiceTest.java`
- `src/test/java/com/myxhs/user/service/UserServiceTest.java`

## 覆盖规则

- `src/main/java` 实际为 27 个 Java 文件
- 目标候选文件总数：35 个
- 逐个阅读后在分析文档标记：已覆盖 / 未覆盖 / 暂不分析
- 35 个候选文件必须逐一给出源码/配置/测试证据；仅列出文件名不算完成覆盖
- `target/` 只记录为构建产物，不纳入源码逻辑覆盖
- DTO、Entity、Mapper 不因代码量少而默认跳过；至少确认字段约束、持久化映射和上下游契约
- 测试源码纳入覆盖，但测试结果与业务现状分开记录
