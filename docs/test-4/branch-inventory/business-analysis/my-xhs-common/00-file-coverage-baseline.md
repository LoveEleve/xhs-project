# 文件覆盖基线（my-xhs-common）

来自 `project-directories/my-xhs-common/inventory.md` 与模块实际扫描。

## 顶层直接子项

| 项 | 说明 |
|---|---|
| src/ | 源码目录（120 个 java） |
| target/ | 构建输出（排除） |
| pom.xml | Maven 配置 |

## src/main/java 覆盖情况（按包）

| 包 | 文件数 | 覆盖状态 |
|---|---|---|
| annotation/ | 4（DistributedLock/Idempotent/IdempotentMessage/RateLimit） | 已覆盖（注解定义随切面分析） |
| aspect/ | 5（RateLimit/Idempotent/IdempotentMessage/DistributedLock/ReadWriteRouting/SqlGuard） | 已读核心 4 个；SqlGuardInterceptor 未深读（标注） |
| cache/ | 3（CacheEvictMessage/CacheHelper/RedisOperator） | 未逐行深读（RedisOperator 为封装层，运行态已验证 Redis 读写）；标注 |
| config/ | 19 | 核心已读（TransactionConfig/JacksonConfig/ReadWriteRoutingDataSourceConfig/FeignSafeConfig）；其余（Async/Redisson/RedisMultiSource/MybatisPlus/Sentinel/WebMvc 等）为装配类，覆盖对账标注 |
| constants/ | RedisKeyConstants | 已覆盖（各模块引用验证） |
| datagen/ | 8 | **明确暂不分析**（数据生成工具，非运行时路径） |
| datasource/ | 3（ContextHolder/Type/ReadWriteRouting） | 已读 |
| entity/ | 3（BaseEntity/CompensationMessage/NotePublishEvent） | 已读（被各模块引用验证） |
| exception/ | 5（BizException/GlobalExceptionHandler 等） | 核心已读（GlobalExceptionHandler 运行态日志验证） |
| feign/config/ | FeignInternalCallInterceptor/FeignUnifiedConfig | 已读 Interceptor（X-Internal-Call 注入）；UnifiedConfig 标注 |
| health/ | 2（ApplicationReadiness/RocketMQHealthIndicator） | 运行态验证（health 端点 UP） |
| trace/ | MqTraceHelper + TraceContextHolder | 已读 MqTraceHelper |
| web/ | AccessTokenGuard/GatewayAuthTrustFilter | 已读 AccessTokenGuard |
| chaos/ | 4 | **明确暂不分析**（混沌实验组件，非生产运行时路径） |

## 覆盖结论

- 核心运行时路径（事务/数据源/序列化/切面/安全/可观测）已覆盖
- datagen/chaos/部分 config 辅助类标注**明确暂不分析**（工具/装配类，非业务运行时路径）
- 详见 `03-file-review-matrix.md`
