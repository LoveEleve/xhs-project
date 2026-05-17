# 26-分库分表实践 Code Review

## 评分：8.5/10（对标 P8，修复后）

| 维度 | 修复前 | 修复后 | 说明 |
|------|--------|--------|------|
| 架构设计 | 8 | 8.5 | 4库×4表、绑定表、映射表路由方案合理 |
| 分布式安全 | 7 | 8.5 | worker-id 动态计算、游标分页、映射补录 |
| 代码质量 | 8 | 8.5 | 注释详尽、职责清晰、命名规范 |
| 生产可用性 | 6.5 | 8.5 | 支付表独立数据源、映射表补录机制 |
| 面试价值 | 8.5 | 9 | 事务消息+分库分表+映射表路由，面试亮点多 |

---

## 发现的问题及修复记录

### P0-1：Snowflake worker-id 硬编码为 1（多实例 ID 冲突）

**问题**：`sharding-config.yaml` 中 `worker-id: 1` 硬编码，多实例部署时所有实例 worker-id 相同，产生 ID 冲突。

**修复**：在 `ShardingSphereDataSourceConfig` 中动态计算 worker-id：
1. 优先使用环境变量 `WORKER_ID`（K8s/Docker 注入）
2. 其次使用系统属性 `-Dworker.id=N`
3. 兜底：基于本机 IP 后两段计算 `(ip[2] * 256 + ip[3]) % 1024`

**修复文件**：`ShardingSphereDataSourceConfig.java`

---

### P0-2：PaymentMapper 路由到分片库（支付表在独立库）

**问题**：`PaymentMapper` 使用 ShardingSphere 数据源，但 `t_payment` 表在 `my_xhs_payment` 独立库中。ShardingSphere 对未配置分片规则的表会透传到默认数据源（ds0 = my_xhs_order_0），导致支付表操作路由到错误的库。

**修复**：
1. 新增 `PaymentDataSourceConfig`：为支付表创建独立的 HikariCP 数据源
2. 新增 `PaymentRepository`：使用 `paymentJdbcTemplate` 操作支付表
3. 重写 `MockPayService`：将 `PaymentMapper` 替换为 `PaymentRepository`

**修复文件**：`PaymentDataSourceConfig.java`、`PaymentRepository.java`、`MockPayService.java`

---

### P1-1：selectTimeoutOrders 无游标分页

**问题**：`LIMIT #{limit}` 没有游标，超时订单 > 100 条时后面的永远处理不到。

**修复**：改为 `id > #{lastId} ORDER BY id ASC LIMIT #{limit}` 游标分页，`OrderCloseJob` 循环处理直到没有更多数据。

**修复文件**：`OrderMapper.java`、`OrderCloseJob.java`

---

### P1-2：映射表写入失败无补录机制

**问题**：`saveOrderNoMapping` 失败后只打日志，没有补录逻辑。通过订单号查询将永远找不到该订单。

**修复**：新增 `OrderMappingRepairJob` 定时任务，每 5 分钟扫描最近 1 小时的订单，检查映射表是否存在对应记录，不存在则补录。

**修复文件**：`OrderMappingRepairJob.java`、`OrderMapper.java`（新增 `selectRecentOrders`）

---

### P1-3：多 JdbcTemplate Bean 冲突

**问题**：引入 `PaymentDataSourceConfig` 后，`SegmentIdGenerator` 等组件注入 `JdbcTemplate` 时发现 2 个候选 Bean，启动失败。

**修复**：
1. `ShardingSphereDataSourceConfig` 中创建 `@Primary` 的默认 `JdbcTemplate`
2. `OrderNoMappingRepository` 和 `PaymentRepository` 改用显式构造函数 + `@Qualifier`

**修复文件**：`ShardingSphereDataSourceConfig.java`、`OrderNoMappingRepository.java`、`PaymentRepository.java`

---

## 技术亮点（面试加分项）

| # | 亮点 | 面试价值 |
|---|------|----------|
| 1 | **4库×4表 INLINE 分片** — user_id 库路由 + 表路由，16 张逻辑表 | ⭐⭐⭐⭐⭐ |
| 2 | **订单号映射表** — 非分片键查询的路由方案 + 定时补录兜底 | ⭐⭐⭐⭐⭐ |
| 3 | **绑定表** — 订单/明细/消息/快照同分片键，避免笛卡尔积 | ⭐⭐⭐⭐ |
| 4 | **多数据源隔离** — ShardingSphere(分片) + Mapping(公共) + Payment(独立) | ⭐⭐⭐⭐⭐ |
| 5 | **Snowflake worker-id 动态计算** — 环境变量 > 系统属性 > IP 自动计算 | ⭐⭐⭐⭐ |
| 6 | **游标分页** — id > lastId 替代 OFFSET，避免深分页性能问题 | ⭐⭐⭐⭐ |
| 7 | **ShardingSphere 5.5.1 + Spring Boot 3.x** — 解决 JAXB/SnakeYAML 兼容性 | ⭐⭐⭐ |

---

## 面试话术

### Q: 你们的分库分表方案是怎么设计的？

A: 我们订单服务采用 ShardingSphere-JDBC 5.5.1 实现分库分表，4 库 × 4 表 = 16 张逻辑表。分片键选择 user_id（而非 order_id），原因是：
1. 订单的核心查询场景是"我的订单列表"，按 user_id 分片可以避免跨库查询
2. 订单创建时 user_id 已知，可以精确路由
3. 订单明细、本地消息表、快照表都配置为绑定表，使用相同分片键，保证关联查询不产生笛卡尔积

### Q: 非分片键查询怎么处理？比如通过订单号查订单？

A: 我们设计了订单号映射表（t_order_no_mapping），存储在不分片的公共库中。流程是：
1. 下单时同步写入映射表（orderNo → userId + orderId）
2. 查询时先查映射表获取 userId，再路由到分片库精确查询
3. 映射表写入失败有定时任务兜底补录（每 5 分钟扫描最近 1 小时的订单）

### Q: 分布式 ID 怎么保证不冲突？

A: ShardingSphere 内置的 Snowflake 算法，worker-id 通过三级策略动态计算：
1. 优先使用环境变量 WORKER_ID（K8s Downward API 注入 Pod 序号）
2. 其次使用 JVM 系统属性 -Dworker.id
3. 兜底基于本机 IP 后两段计算 (ip[2]*256+ip[3]) % 1024

### Q: 支付表为什么不走分片？

A: 支付表属于支付域，查询维度是 order_id/payment_no，不适合用 user_id 分片。而且支付表数据量远小于订单表，无需分片。我们为支付表配置了独立的 HikariCP 数据源，通过 JdbcTemplate 操作，完全绕过 ShardingSphere 路由。

---

## 验证结果

| 测试项 | 结果 |
|--------|------|
| 编译通过 | ✅ |
| 服务启动（7.88s） | ✅ |
| ShardingSphere 4 个分片数据源初始化 | ✅ |
| Snowflake worker-id 动态计算日志 | ✅ |
| 下单 → 分片路由正确（user_id % 4） | ✅ |
| 支付 → 独立库写入正确（my_xhs_payment） | ✅ |
| 订单号查询 → 映射表路由正确 | ✅ |
| 映射表补录定时任务注册 | ✅ |
| 超时关单游标分页 | ✅ |
