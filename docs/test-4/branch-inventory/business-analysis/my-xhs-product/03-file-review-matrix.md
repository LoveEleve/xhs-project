# my-xhs-product 文件级 Review 矩阵

状态定义：
- `深度审查`：已结合调用关系、分支和异常路径分析
- `契约审查`：已核对字段、注解、映射或配置影响
- `测试已执行`：已进入 Surefire 并有结果
- `待运行确认`：静态源码无法证明真实环境行为

| # | 文件 | 状态 | 覆盖重点 |
|---:|---|---|---|
| 1 | `Dockerfile` | 契约审查/待运行确认 | 只有基础镜像契约；jar、启动、端口、健康检查依赖 `my-xhs-base` |
| 2 | `pom.xml` | 契约审查 | Product、MyBatis、Redis、Redisson、Nacos、Sentinel、测试依赖和 Spring Boot 打包 |
| 3 | `src/main/java/com/myxhs/product/ProductApplication.java` | 契约审查 | 启动类、组件扫描、异步配置 |
| 4 | `src/main/java/com/myxhs/product/cache/RedisCacheData.java` | 深度审查 | 逻辑过期字段、时间判断、序列化契约 |
| 5 | `src/main/java/com/myxhs/product/controller/ProductController.java` | 深度审查 | SPU/SKU/类目接口、参数边界、限流、幂等、基础内部/管理边界 |
| 6 | `src/main/java/com/myxhs/product/service/SpuService.java` | 深度审查 | SPU 生命周期、Bloom、缓存、锁、逻辑过期、行为事件、分页、状态和回调 |
| 7 | `src/main/java/com/myxhs/product/service/SkuService.java` | 深度审查 | SKU 创建、单查、批量查、按 SPU 查、状态和图片契约 |
| 8 | `src/main/java/com/myxhs/product/service/CategoryService.java` | 深度审查 | 类目缓存、全量构树、环路和深度边界 |
| 9 | `src/main/java/com/myxhs/product/entity/Spu.java` | 契约审查 | 表映射、字段、状态、JSON images、逻辑删除继承 |
| 10 | `src/main/java/com/myxhs/product/entity/Sku.java` | 契约审查 | SKU 字段、stock 冗余、status、规格 JSON |
| 11 | `src/main/java/com/myxhs/product/entity/Category.java` | 契约审查 | parentId、level、sort、status、树关系 |
| 12 | `src/main/java/com/myxhs/product/entity/ProductBehavior.java` | 深度审查 | 浏览事件字段、异步落库和表契约 |
| 13 | `src/main/java/com/myxhs/product/enums/ProductStatus.java` | 契约审查 | 上下架状态和非法值处理 |
| 14 | `src/main/java/com/myxhs/product/mapper/CategoryMapper.java` | 契约审查 | BaseMapper、无自定义 SQL、条件由 Service 负责 |
| 15 | `src/main/java/com/myxhs/product/mapper/ProductBehaviorMapper.java` | 契约审查 | BaseMapper、无批量写入/重试能力 |
| 16 | `src/main/java/com/myxhs/product/mapper/SkuMapper.java` | 契约审查 | BaseMapper、SKU 状态过滤依赖 Service wrapper |
| 17 | `src/main/java/com/myxhs/product/mapper/SpuMapper.java` | 契约审查 | BaseMapper、批量查询、更新影响行数契约 |
| 18 | `src/main/java/com/myxhs/product/dto/request/SkuCreateRequest.java` | 契约审查 | 价格/库存/规格校验，原价关系和 specs JSON 待增强 |
| 19 | `src/main/java/com/myxhs/product/dto/request/SpuCreateRequest.java` | 契约审查 | 名称/类目校验，images 数量和 URL 约束待增强 |
| 20 | `src/main/java/com/myxhs/product/dto/request/SpuUpdateRequest.java` | 契约审查 | 部分更新、null 清空语义、字段长度 |
| 21 | `src/main/java/com/myxhs/product/dto/response/CategoryTreeVO.java` | 契约审查 | children 递归输出、深度/孤立节点结果 |
| 22 | `src/main/java/com/myxhs/product/dto/response/SkuVO.java` | 契约审查 | price/image/status/spuStatus 下游契约 |
| 23 | `src/main/java/com/myxhs/product/dto/response/SpuDetailVO.java` | 契约审查 | SPU 聚合详情和 SKU 列表组合 |
| 24 | `src/main/java/com/myxhs/product/dto/response/SpuItemVO.java` | 契约审查 | 列表轻量字段、缺少价格/分类名的下游影响 |
| 25 | `src/main/resources/application-datasource.properties` | 契约审查/待运行确认 | 主从 JDBC、readwrite、连接超时、明文凭据 |
| 26 | `src/main/resources/application.yml` | 深度审查 | 路由、Redis/Nacos/Sentinel、Feign、Actuator、JWT、敏感配置 |
| 27 | `src/main/resources/logback-spring.xml` | 契约审查 | 普通异步文件、同步 JSON、MDC 字段、滚动策略 |
| 28 | `src/test/java/com/myxhs/product/service/CategoryServiceTest.java` | 测试已执行 | 3 个通过；未覆盖环路/深度/Redis 异常 |
| 29 | `src/test/java/com/myxhs/product/service/SkuServiceTest.java` | 测试已执行 | 4 个通过；已同步上架 SPU 夹具，未覆盖批量下架 SPU |
| 30 | `src/test/java/com/myxhs/product/service/SpuServiceTest.java` | 测试已执行 | 7 个通过；未覆盖 Bloom 初始化失败、并发和真实缓存 |

## 结果统计

- 实际非 `target` 文件：30
- 已读取：30/30
- 深度审查：核心 Service/Controller/缓存/索引链路已覆盖
- 契约审查：DTO/Entity/Enum/Mapper/配置/构建文件已覆盖
- 测试已执行：3 个测试类，14 个测试全部通过
- 真实中间件联调：未执行
- AI：排除

说明：文件级覆盖已完成，但“测试已通过”只代表 Mockito 单元路径，不等于真实数据库、Redis、Canal、RocketMQ、ES 和并发场景全部通过。
