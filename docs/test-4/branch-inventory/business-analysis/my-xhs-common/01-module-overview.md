# my-xhs-common 模块总览

## 1. 当前模块定位

公共基础设施模块（非独立部署服务），被全部 15 个微服务依赖。提供：事务管理、读写分离数据源、全局 Jackson 序列化、限流/幂等/分布式锁切面、缓存操作、异常处理、MQ trace 透传、Feign 内部调用鉴权、健康检查、配置类。

**运行态复核证实**：common 是多数跨模块问题的根因所在（事务失效、MQ 字符串 Long、无 body 空响应），必须在运行时验证而非假设正确。

## 2. 核心能力

```text
事务: TransactionConfig → DataSourceTransactionManager(30s默认超时) [已修复@ConditionalOnBean时序]
数据源: 读写分离(master/slave + SQL前缀路由 + slave降级) | ShardingSphere(订单/库存分片)
序列化: JacksonConfig Long→String(防JS精度) + LocalDateTime格式
切面: RateLimit(Lua滑动窗口) → DistributedLock(Redisson) → Idempotent(Redis SETNX)
安全: FeignInternalCallInterceptor(X-Internal-Call fail-closed) + AccessTokenGuard
缓存: RedisOperator/CacheHelper/CacheEvictMessage
可观测: MqTraceHelper(trace透传) + GlobalExceptionHandler + health indicators
```

## 3. 当前关键事实

- `TransactionConfig` 曾因类级 `@ConditionalOnBean(DataSource.class)` 时序问题导致**所有读写分离服务无事务管理器**，`@Transactional` 静默失效（发布笔记脏数据、建SPU报错）——已改方法级条件并全量验证
- `JacksonConfig` 全局 Long→String（防前端 JS 精度丢失），导致所有 Spring ObjectMapper 构造的 MQ body 中 id 变字符串，消费端必须兼容——多模块消费端已修复
- 读写分离数据源：`ReadWriteRoutingDataSource` 支持手动/事务只读/SQL 前缀三策略，从库故障降级主库
- 所有 AOP 切面（限流/幂等/锁）在 Redis 不可用时**降级放行**（保可用性、牺牲保护）
- Feign 内部调用令牌 fail-closed：未配置 `INTERNAL_TOKEN` 时内部调用直接失败

## 4. 范围

- 主源码 120 个 java 文件；核心运行时路径已覆盖，datagen（数据生成）/chaos（混沌）等工具标注暂不分析
- 覆盖对账见 `03-file-review-matrix.md`
