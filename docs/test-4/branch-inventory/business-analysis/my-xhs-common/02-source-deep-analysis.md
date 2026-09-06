# my-xhs-common 源码深度分析

## 1. 模块定位

公共基础设施（120 个 java），被所有微服务引用。不做独立部署，但它的正确性决定所有服务的运行行为——**运行态复核证明多数跨模块问题根因在 common**。

## 2. 关键能力与源码流转

### 2.1 事务管理 `TransactionConfig`
- 全局唯一 `DataSourceTransactionManager`，默认超时 30s，`validateExistingTransaction=true`
- **运行态发现并修复**：原类级 `@ConditionalOnBean(DataSource.class)` 在 @Configuration 类上评估早于 DataSource bean 注册 → 读写分离服务（content/user/product 等）未加载本类 → 无 transactionManager → `@Transactional` 静默退化为无事务（发布笔记中间异常但 insert 已提交产生脏数据，建 SPU 报 `Transaction synchronization is not active`）
- 修复：条件移到 `@Bean` 方法级（`@ConditionalOnBean(DataSource.class)` + `@ConditionalOnMissingBean`），有 DataSource 创建、无 DataSource（home 纯 Redis/MQ 服务）跳过
- 验证：15 服务 jcmd 确认 TransactionConfig 加载，事务回归通过

### 2.2 读写分离数据源 `ReadWriteRoutingDataSourceConfig` + `ReadWriteRoutingDataSource` + `DataSourceContextHolder`
- 三层路由策略（优先级递减）：
  1. `DataSourceContextHolder` ThreadLocal 手动指定（getConnection 时读后即 `clear()`，一次性消费）
  2. `TransactionSynchronizationManager.isCurrentTransactionReadOnly()` → SLAVE
  3. 默认 MASTER（注释声称 SQL 前缀分析，但 `determineCurrentLookupKey` 实际未实现 SQL 分析，见风险 R-1）
- 从库不可用自动降级主库 + 30s 探测恢复
- `isReadOperation(sql)` 静态方法按前缀判断读写（但未被路由逻辑调用，疑死代码，见 R-1）

### 2.3 Jackson 全局配置 `JacksonConfig`
- `Long/long → ToStringSerializer`（防前端 JS 精度丢失，雪花 19 位）
- LocalDateTime 格式 `yyyy-MM-dd HH:mm:ss`、LocalDate `yyyy-MM-dd`、忽略未知属性
- **跨模块影响**：所有 Spring ObjectMapper 序列化 Long 为 String → MQ body 中 userId/skuId/orderId 等变字符串 → 消费端 `canConvertToLong()`/`(Number)` 强转失败。已系统性修复各消费端兼容解析（inventory/order/home）

### 2.4 切面体系（执行顺序 RateLimit(10) → DistributedLock(50) → Idempotent(100)）
- `RateLimitAspect`：Redis Lua ZSet 滑动窗口限流；Redis 不可用降级放行（丢保护保可用）
- `DistributedLockAspect`：Redisson MUTEX/READ/WRITE/FAIR；Watchdog 自动续期（leaseTime=-1）；Redis 不可用降级放行
- `IdempotentAspect`：Redis SETNX 幂等；BizException/参数异常删除标记可重试，超时异常保留标记防重复
- `IdempotentMessageAspect`：MQ 消息幂等（但 RocketMQ Listener 直接实现接口时 AOP 不拦截，官方建议用 MessageIdempotentHelper——订单/库存消费者已直接内置幂等）

### 2.5 内部调用鉴权 `FeignInternalCallInterceptor` + `AccessTokenGuard`
- 全局 Feign 拦截器注入 `X-Internal-Call`（token 从环境变量 INTERNAL_TOKEN，fail-closed：未配置不带头 → 对端拒绝）
- `AccessTokenGuard`：统一校验 `X-Admin-Call`/`X-Internal-Call`，`requireXxxConfigured` 启动即校验（未配置拒绝启动）
- 各服务 Controller 用 `@RequestHeader` + `accessTokenGuard.isXxxCall()` 保护内部/管理端点

### 2.6 可观测性
- `MqTraceHelper`：MQ 消息透传 traceId/userId/灰度/AB/压测标记到 Header，消费端恢复 MDC；兼容旧方法
- `GlobalExceptionHandler`：统一异常码响应（业务异常 400xx、未知异常 500）
- health indicators：Redis/RocketMQ/就绪探针（`RocketMQHealthIndicator` 反射检查 producer）

## 3. 数据流转与中间件参与点

| 项 | 内容 |
|---|---|
| Redis | 限流 ZSet key、幂等 SETNX key、缓存（RedisOperator）、锁（Redisson）、消息幂等 |
| MySQL | 读写分离（master 3306/slave 3307），非分片库走 common 路由；分片库（order/inventory）走 ShardingSphere |
| MQ | trace 标记 Header 注入/恢复 |
| Feign | 全局 X-Internal-Call 注入 |

## 4. 跨模块与分布式行为

- **事务与读写分离的交互**：`@Transactional(readOnly=true)` 自动路由 slave；但手动 `DataSourceContextHolder` 优先且读后即清（R-1 风险）
- **MQ 契约**：JacksonConfig 全局序列化是字符串 id 问题的根因，各消费端需兼容
- **安全**：内部/管理令牌 fail-closed，未配置拒绝启动/拒绝调用

## 5. 性能与工程质量

- 限流/幂等/锁均 Lua/Redis 原子操作，性能可控；Redis 降级放行策略保可用
- 数据源 Hikari 双池（master 20/slave 10），slave 降级机制完整
- datagen（数据生成）/chaos（混沌）为工具类，非运行时路径

## 6. 鉴权基础检查

- 内部/管理端点统一 `AccessTokenGuard` fail-closed
- Feign 全局注入 X-Internal-Call，对端校验
- gateway 负责 JWT 鉴权 + X-User-Id 注入（`GatewayAuthTrustFilter`）

## 7. 本轮修复（运行态复核）

1. **TransactionConfig 事务失效**（@ConditionalOnBean 类级时序）→ 方法级条件，全量验证
2. **无 body POST 空响应**（gateway BodyCacheFilter，common 无直接关系但影响全链）→ 已修
3. **MQ 字符串 Long 消费端兼容**（JacksonConfig 序列化根因）→ 各消费端已修
4. **并行构建 race**：`-am package` 并行时本地仓库 common 竞争致部分服务内嵌旧版 → 串行 clean package + 先 install common

## 8. 风险与待确认

- R-1 `determineCurrentLookupKey` 未实现 SQL 前缀分析（注释与实现不符），默认走主库；`isReadOperation` 未被调用（可能死代码）。实际影响：读多写少场景全走主库，从库闲置（功能正确、性能未达预期）
- R-2 `DataSourceContextHolder` 读后即清：多条 SQL 事务内手动指定路由只对第一条生效（设计权衡，需运行确认是否有依赖场景）
- R-3 限流/幂等/锁 Redis 降级放行：故障时保护失效（设计权衡）

## 9. 测试重点

- 事务回滚（@Transactional 生效）、读写分离路由、限流阈值、幂等重试、分布式锁并发、内部令牌 fail-closed
