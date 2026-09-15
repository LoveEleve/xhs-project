# 逐文件扫描补充（2026-09-15，13 个新点）

1. **压测影子表**：`ShadowTableInterceptor`（MyBatis 拦截器）在压测标记下自动把 SQL 表名改为 `_shadow` 后缀（如 t_order→t_order_shadow），实现压测流量与真实数据隔离（`common/trace/ShadowTableInterceptor.java`）。
2. **审计独立事务模板**：`AuditLogService` 用 `REQUIRES_NEW` 保证审计不受外层事务回滚影响（子类实现 doRecordAuditLog）。
3. **Redis 命令事件**：`EventPublishingRedisCommandInterceptor` 在写命令成功后发布 `RedisCommandEvent`（可用于跨 Zone 同步/审计）；配套 RedisTemplate 代理包装（`zone/redis/interceptor/*`）。
4. **多版本 API**：`@ApiVersion` + `ApiVersionAutoConfiguration`（替换 RequestMappingHandlerMapping，仅 Servlet 服务生效，网关 WebFlux 不受影响）+ ApiVersionCondition/HandlerMapping。
5. **Zone 多活**：`ZonePreferenceFilter` 10 步决策（空/单实例直返、开关、无效 Zone 忽略、优先同 Zone、兜底全量）+ Zone 优先负载均衡（ServiceInstanceListSupplier）+ ZoneContext/ZoneResolver/ZoneConstants。
6. **批量写入执行器**：`BatchInsertExecutor`——CPU 核数×2 线程、JDBC Batch 每批 5000、按 ID 范围分片（线程互不冲突）、实时进度（速度/预估剩余）。
7. **造数框架**：`myxhs.datagen.enabled` + 规模倍数（1.0=标准量：千万级用户/五千万级笔记；0.01=1% 快速测试），内置用户/商品/笔记三类生成器 + 随机数据工厂。
8. **文件存储**：`LocalFileStorageService` 按日期目录 + UUID 文件名 + **文件头魔数校验**（JPEG/PNG/GIF/WebP 防 Content-Type 伪造）；`storage.type=minio` 可切 MinIO。
9. **order 双独立数据源**：支付表（t_payment）与订单号映射表（t_order_no_mapping）各用独立 HikariCP + JdbcTemplate **绕过 ShardingSphere**——支付域未来独立拆分、查询维度（order_id/payment_no）不适配 user_id 分片。
10. **搜索 5 路召回实现**：Hot（热门）/Content（标签匹配）/ItemCF/Geo（**GeoHash 同城**）/Following（关注），策略接口 RecallStrategy 统一。
11. **analytics 10 个 Lua**：点赞/取消/收藏全用双向原子脚本；关注/取关拆 self/target 两段（当前用户侧+目标用户侧），收藏取消带 ZSCORE 检查防回滚覆盖新 score。
12. **Lettuce 命令指标**：自定义 ClientResources 注入 MicrometerCommandLatencyRecorder，记录 `lettuce.command.latency{command,remote}`。
13. **Tomcat 定制器**：`MyXhsTomcatCustomizer` 统一 Connector 协议优化 + MBean 注册（各服务线程按 yml 差异化）。
