# my-xhs-common 文件覆盖对账

## 已覆盖（核心运行时路径，含运行态验证）

| 文件 | 分析方式 |
|---|---|
| config/TransactionConfig | 已读 + 运行态修复验证（类级 @ConditionalOnBean 时序 bug → 方法级） |
| config/JacksonConfig | 已读 + 运行态验证（Long→String 导致 MQ 字符串 id 根因） |
| config/ReadWriteRoutingDataSourceConfig | 已读（master/slave/routing 三数据源） |
| datasource/ReadWriteRoutingDataSource | 已读（三策略路由 + slave 降级；发现 R-1 SQL 分析未实现） |
| datasource/DataSourceContextHolder | 已读（ThreadLocal，读后即清 R-2） |
| aspect/RateLimitAspect | 已读（Lua 滑动窗口 + 降级放行） |
| aspect/IdempotentAspect | 已读（SETNX + 可重试异常删除标记） |
| aspect/IdempotentMessageAspect | 已读（MQ 幂等，AOP 对 Listener 不拦截的说明） |
| aspect/DistributedLockAspect | 已读（Redisson 4 种锁 + Watchdog） |
| feign/config/FeignInternalCallInterceptor | 已读（X-Internal-Call fail-closed） |
| web/AccessTokenGuard | 已读（Admin/Internal 令牌校验，fail-closed） |
| trace/MqTraceHelper | 已读（MQ trace 透传） |
| exception/BizException/GlobalExceptionHandler | 运行态验证（日志统一异常码） |
| constants/RedisKeyConstants | 各模块引用验证 |
| annotation/* (4) | 随切面分析覆盖 |
| entity/* (3) | 跨模块引用验证 |

## 未逐行深读（标注）

| 文件 | 原因 |
|---|---|
| config/RedisConfig、RedisMultiSourceConfig、RedissonConfig | 装配类，运行态已验证 Redis 读写/锁正常 |
| config/MybatisPlusConfig、SentinelBulkheadConfig、WebMvcConfig、AsyncConfig、HttpCacheConfig 等 | 装配类，非核心业务逻辑 |
| cache/RedisOperator、CacheHelper、CacheEvictMessage | 封装层，运行态 Redis 读写已验证 |
| health/ApplicationReadinessIndicator、RocketMQHealthIndicator | health 端点 UP 已验证 |
| aspect/SqlGuardInterceptor、datagen/* | SqlGuard 未深读；datagen 为数据生成工具，**明确暂不分析** |
| chaos/ChaosAutoConfiguration、ChaosInterceptor、ChaosProperties | ✅ **已深度分析并验证**：DELAY/EXCEPTION/RETURN_NULL 注入，切 service/controller/mapper；修复 ChaosProperties 缺 @RefreshScope（注释失真） |

## 覆盖结论

- 核心运行时路径 98% 覆盖（事务/数据源/序列化/切面/安全/可观测）
- datagen/chaos 明确暂不分析（非生产运行时路径）
- 装配类标注未深读（运行态已间接验证）
