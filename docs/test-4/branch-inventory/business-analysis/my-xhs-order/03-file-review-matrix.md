# my-xhs-order 文件级 Review 矩阵

## 数量

- 顶层：2
- `docs/`：1
- `src/main/java`：45
- `src/main/resources`：3
- `src/test/java`：5
- 非 `target` 总数：56

## 状态定义

- `深度审查`：Service/Listener/Consumer/Job/分片/事务消息结合分支与异常分析
- `契约审查`：DTO/Entity/Mapper/Repository/Feign/配置/启动类
- `测试已执行`：已进入 Surefire 且有结果
- `待运行确认`：需真实 MySQL 分片/MQ/多实例

## 分组

### 顶层与文档
- `Dockerfile`：契约审查/待运行确认
- `docs/CODE-REVIEW.md`：历史评审与当前代码差异审查
- `pom.xml`：依赖与构建审查（无 Dubbo；订单不依赖 Cart，Feign 仅 Product/Inventory/Coupon/Payment/User）

### 配置 3
- `application.yml`：支付模式、线程池、Redis/MQ/XXL、Actuator
- `sharding-config.yaml`：4库×4表 user_id 分片
- `logback-spring.xml`：异步/JSON/TraceId

### 主源码 45
- `OrderApplication.java`：启动、数据源排除、手动分片数据源
- `config/MappingDataSourceConfig.java`、`PaymentDataSourceConfig.java`、`ShardingSphereDataSourceConfig.java`：独立数据源与分片
- `service/OrderService.java`：核心，状态机/下单/支付/退款/补偿
- `service/OrderTransactionService.java`：事务消息本地事务
- `service/OrderEventService.java`：事件流与状态收敛
- `service/MockPayService.java`：Mock 支付（非原子已记录）
- `listener/OrderTransactionListener.java`：事务消息/回查
- `consumer/OrderCloseConsumer.java`、`OrderCompensationConsumer.java`
- `job/OrderCloseJob.java`、`LocalMessageRetryJob.java`、`OrderMappingRepairJob.java`
- `mapper`×5、`repository`×2、`entity`×7、`dto`×6、`feign`×10、`controller`×1

### 测试 5
- `OrderServiceTest`：27 个通过
- `OrderControllerTest`：22 个通过
- `OrderCompensationConsumerTest`：3 个通过
- `OrderTransactionServiceTest`：4 个通过
- `OrderMappingRepairJobTest`：2 个通过

## 结论

- 56/56 已覆盖
- 本轮 order 58 个测试全部通过
- C1/C2 已修复（构造器/断言）
- 明确缺陷：死代码 Mapper 方法、注释失真、映射修复全表扫描、列表无分页、券折扣静默降级、预扣竞态、补偿 DLQ 无重放等已记录，未全部修改
- 运行级行为（分片事务消息、支付回调、补偿、预扣）待真实环境确认
