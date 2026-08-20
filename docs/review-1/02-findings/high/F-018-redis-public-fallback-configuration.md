# F-018 Redis 配置包含公开 host/password fallback，配置漂移时可能连接到外部 Redis

## 严重度

High

## 涉及文件

- `my-xhs-common/src/main/java/com/myxhs/common/config/RedisConfig.java:60-65`
- `my-xhs-common/src/main/java/com/myxhs/common/config/RedisMultiSourceConfig.java:24-30`

## 现象

公共 common 模块的 Redis 配置在缺少配置时默认使用：

- host：`21.91.124.110`
- password：`Xhs@2026#Redis`
- business port：`16381`
- cache port：`16380`

这些值直接写在源码中，所有依赖 common 的服务都会继承该 fallback。

## 证据

1. `RedisConfig.defaultRedisConnectionFactory()`：`RedisConfig.java:60-65`。
2. `RedisMultiSourceConfig.cacheRedisConnectionFactory()`：`RedisMultiSourceConfig.java:24-30`。
3. 配置缺失时 Spring 会使用注解中的默认值，而不是失败启动。
4. Redis 承载 token 黑名单、用户 token、HMAC secret、业务缓存和计数数据，属于高价值共享基础设施。

## 触发条件

1. Nacos、本地 yml 或环境变量缺少 Redis host/password。
2. 配置键名漂移、profile 加载错误或新服务未继承正确配置。
3. 服务启动后静默连接 fallback 地址。

## 影响

1. 服务可能把生产数据写入错误/外部 Redis，造成数据泄露或跨环境污染。
2. 如果 fallback Redis 仍可访问，源码公开密码会暴露 token、黑名单和业务缓存。
3. 如果 Redis 连接失败，服务可能部分启动但在运行时出现认证、会话或一致性异常。
4. 多服务配置漂移时，不同服务可能连接不同 Redis，身份与业务状态分裂。

## 修复建议

1. 删除 host/password fallback，缺少关键 Redis 配置时拒绝启动。
2. 将 host、password、端口统一由受保护配置注入，启动时校验不是已知默认值。
3. 按 business/cache 数据源显式区分配置，避免 common 里使用环境相关默认地址。
4. 对运行态 Redis 连接目标和认证失败建立启动探活与告警。

## 是否需要补充验证

检查所有服务的实际 Redis 连接目标、profile 和环境变量，确认没有服务使用源码 fallback。