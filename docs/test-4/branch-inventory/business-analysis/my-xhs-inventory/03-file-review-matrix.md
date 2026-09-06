# my-xhs-inventory 文件级 Review 矩阵

## 数量

- 顶层文件：2
- `docs/`：1
- `src/main/java`：26
- `src/main/resources`：7（3 配置 + 4 Lua）
- `src/test/java`：4
- 非 `target` 总数：40

## 状态

- `深度审查`：核心 Service、TCC、Consumer、Job、Lua
- `契约审查`：DTO、Entity、Mapper、启动类、Dockerfile、配置、模块文档
- `测试已执行`：已进入 Surefire 且有结果
- `待运行确认`：需要真实 Redis/MySQL/MQ/锁/多实例

## 逐文件清单

### 顶层与文档
- `Dockerfile`：契约审查/待运行确认
- `docs/CODE-REVIEW.md`：文档与源码差异审查
- `pom.xml`：依赖和构建契约审查

### 主源码 26 个
- `InventoryApplication.java`：启动、扫描、Feign、调度契约审查
- `config/RedisScriptConfig.java`：4 个 Lua Bean 审查
- `controller/InventoryController.java`：接口、参数和基础内部/管理边界深度审查
- `entity/Inventory.java`：库存字段契约审查
- `mapper/InventoryMapper.java`：库存、Fence、Outbox、补偿 SQL 审查
- `service/InventoryService.java`：初始化、分桶、预扣、确认、释放、退款、恢复、扩容、对账深度审查
- `service/InventoryTccService.java`：TCC Fence、Try、Confirm、Cancel 深度审查
- `feign/ProductFeignClient.java`：SKU 依赖审查
- `hot/HotSkuDetector.java`：热点检测审查
- `consumer/OrderTransactionConsumer.java`：订单事务消息审查
- `consumer/InventoryDeductConsumer.java`：库存持久化消费者审查
- `consumer/InventoryCacheEvictConsumer.java`：Canal 缓存失效审查
- `job/InventoryCompensationJob.java`：异常补偿审查
- `job/InventoryOutboxSenderJob.java`：Outbox 发送审查
- `job/InventoryReconcileJob.java`：库存对账审查
- `job/PreDeductTimeoutJob.java`：预扣超时审查
- `job/TccTimeoutJob.java`：TCC 超时审查
- `dto/TccDeductRequest.java`：TCC 请求契约审查
- `dto/event/InventoryDeductEvent.java`：事件契约审查
- `dto/response/StockVO.java`：响应契约审查
- `dto/request/InventoryInitRequest.java`：初始化参数审查
- `dto/request/ReinitRequest.java`：重建参数审查
- `dto/request/PreDeductRequest.java`：预扣参数审查
- `dto/request/ConfirmDeductRequest.java`：确认参数审查
- `dto/request/ReleaseStockRequest.java`：释放参数审查
- `dto/request/RefundRestoreRequest.java`：退款参数审查

### resources 7 个
- `application.yml`：连接、调度、令牌和 Actuator 审查
- `application-datasource.properties`：主从和连接参数审查
- `logback-spring.xml`：日志输出与敏感字段审查
- `lua/prededuct.lua`：分桶预扣边界
- `lua/release.lua`：库存释放边界
- `lua/confirm.lua`：确认删除边界
- `lua/reconcile_buckets.lua`：桶总量对账边界

### 测试 4 个
- `InventoryServiceTest.java`：测试已修复构造器并执行
- `InventoryCompensationJobTest.java`：补偿测试已执行
- `InventoryOutboxSenderJobTest.java`：Outbox 测试已执行
- `OrderTransactionConsumerTest.java`：事务消费者测试已执行

## 测试结果

本轮从仓库根目录执行 `mvn -pl my-xhs-inventory -am test`：
- common：53 个通过
- inventory：15 个通过
- failures：0
- errors：0

这些是 Mockito 测试结果，Lua 真实 Redis、MQ、MySQL、TCC、锁和并发仍未验证。
