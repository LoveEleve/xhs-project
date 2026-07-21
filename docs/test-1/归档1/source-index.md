# my-xhs 源码索引

> 最后更新：2026-05-30 | 扫描范围：16 个模块，393 个 Java 源文件

---

## 总体统计

| 指标 | 数值 |
|------|:---:|
| 模块数 | 16 |
| Java 源文件 | 393 |
| 单元测试 | 2（仅 common 模块） |
| Controller | 23 |
| Service/ServiceImpl | 41 |
| Mapper | 39 |
| Entity | 30+ |
| DTO/VO | 60+ |
| Enum | 10+ |

---

## 1. my-xhs-common（公共模块）— 54 个 Java 文件

> **完成度**: ⭐⭐⭐⭐⭐  核心基础设施，所有模块依赖
> **包路径**: `com.myxhs.common`

### 1.1 统一响应 & 错误码

| 文件 | 类名 | 说明 | 状态 |
|------|------|------|:---:|
| `result/R.java` | `R<T>` | 统一响应体：code/message/data/timestamp | ✅ |
| `result/ResultCode.java` | `ResultCode` | 错误码枚举，5位数分层编码（200/4xxxx/5xxxx） | ✅ |
| `exception/BusinessException.java` | `BusinessException` | 业务异常，携带 ResultCode | ✅ |
| `exception/GlobalExceptionHandler.java` | `GlobalExceptionHandler` | 全局异常处理，统一返回 R | ✅ |

### 1.2 注解驱动切面（AOP）

| 文件 | 类名 | 说明 | 状态 |
|------|------|------|:---:|
| `annotation/DistributedLock.java` | `@DistributedLock` | 分布式锁注解（SpEL key） | ✅ |
| `annotation/Idempotent.java` | `@Idempotent` | 幂等性注解（SpEL key + TTL） | ✅ |
| `annotation/RateLimit.java` | `@RateLimit` | 限流注解（滑动窗口） | ✅ |
| `annotation/LogOperation.java` | `@LogOperation` | 操作日志注解 | ✅ |
| `aop/DistributedLockAspect.java` | — | 分布式锁 AOP 切面实现 | ✅ |
| `aop/IdempotentAspect.java` | — | 幂等 AOP 切面实现 | ✅ |
| `aop/RateLimitAspect.java` | — | 限流 AOP 切面实现 | ✅ |

### 1.3 缓存 & 锁

| 文件 | 类名 | 说明 | 状态 |
|------|------|------|:---:|
| `cache/CacheHelper.java` | `CacheHelper` | 多级缓存工具（Redis + Caffeine），Double Check 防击穿 | ✅ |
| `cache/RedisOperator.java` | `RedisOperator` | Redis 操作封装（String/Hash/Set/ZSet/Lock） | ✅ |
| `config/RedisConfig.java` | — | Redis 序列化配置（Jackson2Json） | ✅ |
| `config/RedissonConfig.java` | — | Redisson 分布式锁配置 | ✅ |

### 1.4 ID 生成

| 文件 | 类名 | 说明 | 状态 |
|------|------|------|:---:|
| `id/SnowflakeIdGenerator.java` | — | 雪花算法 ID 生成器（CosId） | ✅ |
| `id/SegmentIdGenerator.java` | — | 号段模式 ID 生成器（数据库分段，本地缓存） | ✅ |

### 1.5 链路追踪

| 文件 | 类名 | 说明 | 状态 |
|------|------|------|:---:|
| `trace/TraceContext.java` | — | TraceId/SpanId 上下文 | ✅ |
| `trace/TraceContextHolder.java` | — | ThreadLocal 持有 TraceContext | ✅ |
| `trace/TraceIdGenerator.java` | — | TraceId 生成器 | ✅ |
| `filter/TraceFilter.java` | — | Web 层 TraceId 注入 Filter | ✅ |
| `feign/FeignTraceInterceptor.java` | — | Feign 调用 TraceId 透传 | ✅ |
| `mq/MqTraceInterceptor.java` | — | RocketMQ 消息 TraceId 透传 | ✅ |
| `config/TransmittableThreadPoolConfig.java` | — | 线程池 TraceId 透传配置（TTL） | ✅ |

### 1.6 自动配置

| 文件 | 说明 | 状态 |
|------|------|:---:|
| `config/MyBatisPlusConfig.java` | MyBatis-Plus 分页/乐观锁/逻辑删除 | ✅ |
| `config/JacksonConfig.java` | Jackson 序列化配置（时间格式/长整型） | ✅ |
| `config/WebMvcConfig.java` | WebMvc 拦截器注册 | ✅ |
| `config/AsyncConfig.java` | 异步任务线程池配置 | ✅ |
| `config/FeignConfig.java` | Feign 客户端配置 | ✅ |
| `config/SentinelConfig.java` | Sentinel 规则配置 | ✅ |
| `chaos/ChaosAutoConfiguration.java` | 混沌工程自动配置（预留注入点） | ✅ |
| `chaos/ChaosInterceptor.java` | 混沌工程拦截器 | ✅ |
| `health/ApplicationReadinessIndicator.java` | 应用就绪健康指示器 | ✅ |
| `lifecycle/GracefulShutdownListener.java` | 优雅停机监听器 | ✅ |
| `lifecycle/ApiMetricsFilter.java` | API 业务指标采集 Filter | ✅ |
| `lifecycle/BusinessMetrics.java` | 业务指标 Bean | ✅ |
| `spel/SpELParser.java` | SpEL 表达式解析工具 | ✅ |
| `data/DataGenerator.java` | 测试数据生成器框架 | ✅ |
| `data/generator/` | User/Note/Product 数据生成器 | ✅ |

> **AutoConfiguration 注册**: `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 注册了约 20 个自动配置类

---

## 2. my-xhs-gateway（网关）— 11 个文件

> **完成度**: ⭐⭐⭐⭐⭐  7 层过滤链 + 全局异常处理
> **端口**: 19000 | **框架**: Spring Cloud Gateway (WebFlux)

| 文件 | 类名 | 说明 | 状态 |
|------|------|------|:---:|
| `GatewayApplication.java` | — | 启动类，排除 DataSource 自动配置 | ✅ |
| `config/AuthProperties.java` | — | JWT/HMAC 密钥+白名单配置 | ✅ |
| `config/GatewayConfig.java` | — | Sentiel 规则/Redis Template/CORS 配置 | ✅ |
| `filter/RequestLogFilter.java` | — | ① 请求日志 + TraceId 生成（order=100） | ✅ |
| `filter/GatewayAuthFilter.java` | — | ② JWT 鉴权 + Token 黑名单（order=1000） | ✅ |
| `filter/TrafficColoringFilter.java` | — | ③ 全链路流量染色（order=1200） | ✅ |
| `filter/HmacSignatureFilter.java` | — | ④ HMAC-SHA256 签名防篡改/防重放（order=1500） | ✅ |
| `filter/RateLimitFilter.java` | — | ⑤ 基于 Redis 滑动窗口限流（order=2500） | ✅ |
| `filter/GrayRouteFilter.java` | — | ⑥ 灰度路由（Nacos 元数据）（order=3000） | ✅ |
| `filter/ApiVersionFilter.java` | — | ⑦ API 版本路由（order=3100） | ✅ |
| `handler/GlobalExceptionHandler.java` | — | 全局异常 → 统一 JSON 错误响应 | ✅ |

---

## 3. my-xhs-user（用户服务）— 26 个文件

> **完成度**: ⭐⭐⭐⭐  注册登录/用户信息/收货地址
> **端口**: 19001 | **数据库**: MySQL-User:13306 → my_xhs_user

| 文件 | 类名 | 说明 | 状态 |
|------|------|------|:---:|
| `UserApplication.java` | — | 启动类 | ✅ |
| `config/JwtProperties.java` | — | JWT 配置（密钥/过期时间） | ✅ |
| `config/PasswordEncoderConfig.java` | — | BCryptPasswordEncoder Bean | ✅ |
| `controller/AuthController.java` | — | 注册/登录/刷新Token/退出 | ✅ |
| `controller/UserController.java` | — | 用户详情/更新/修改密码 | ✅ |
| `controller/UserAddressController.java` | — | 收货地址 CRUD + 默认地址 | ✅ |
| `entity/User.java` | — | t_user 实体 | ✅ |
| `entity/UserAddress.java` | — | t_address 实体 | ✅ |
| `dto/request/LoginRequest.java` | — | 登录请求（验证码登录支持） | ✅ |
| `dto/request/RegisterRequest.java` | — | 注册请求 | ✅ |
| `dto/response/` | — | UserVO / LoginVO / TokenVO / AddressVO | ✅ |
| `service/UserService.java` | — | 用户业务逻辑（注册/登录/信息管理） | ✅ |
| `service/UserAddressService.java` | — | 地址业务逻辑 | ✅ |
| `service/TokenService.java` | — | JWT Token 生成/校验/黑名单 | ✅ |
| `service/CaptchaService.java` | — | 图形验证码（Kaptcha） | ✅ |
| `mapper/UserMapper.java` | — | 用户 Mapper | ✅ |
| `mapper/UserAddressMapper.java` | — | 地址 Mapper | ✅ |
| `consumer/CacheEvictConsumer.java` | — | 缓存删除兜底消费者 | ✅ |

---

## 4. my-xhs-home（首页聚合服务）— 38 个文件

> **完成度**: ⭐⭐⭐⭐  Feed 流聚合，多源并发调用
> **端口**: 19015

| 文件 | 类名 | 说明 | 状态 |
|------|------|------|:---:|
| `HomeApplication.java` | — | 启动类 | ✅ |
| `controller/HomeController.java` | — | 首页 Feed / 发现页 / 分类浏览 | ✅ |
| `controller/TopicController.java` | — | 话题（创建/详情/列表/搜索/关注） | ✅ |
| `service/FeedService.java` | — | Feed 流聚合核心（并发+降级+组装） | ✅ |
| `service/NoteAggregationService.java` | — | 笔记信息批量聚合（用户/互动数据） | ✅ |
| `service/TopicService.java` | — | 话题业务逻辑 | ✅ |
| `service/RecommendService.java` | — | 推荐召回封装 | ✅ |
| `service/HomeConfigService.java` | — | 首页模块配置管理 | ✅ |
| `dto/request/` | — | Feed 请求/话题请求 | ✅ |
| `dto/response/` | — | FeedVO / TopicVO / AggregatedNoteVO / ModuleItemVO | ✅ |
| `consumer/FeedCacheConsumer.java` | — | Feed 缓存更新消费者 | ✅ |

---

## 5. my-xhs-content（内容服务）— 23 个文件

> **完成度**: ⭐⭐⭐⭐  笔记/评论 CRUD + DFA 敏感词过滤
> **端口**: 19002 | **数据库**: MySQL-Content:13307 → my_xhs_content

| 文件 | 类名 | 说明 | 状态 |
|------|------|------|:---:|
| `ContentApplication.java` | — | 启动类 | ✅ |
| `controller/NoteController.java` | — | 笔记（发布/草稿/编辑/删除/详情/图片上传） | ✅ |
| `controller/CommentController.java` | — | 评论（发表/删除/游标分页/楼中楼） | ✅ |
| `entity/Note.java` | — | t_note 实体（含审核状态+笔记类型） | ✅ |
| `entity/Comment.java` | — | t_comment 实体（楼中楼 parentId/replyToId） | ✅ |
| `enums/NoteStatus.java` | — | 笔记状态枚举（含 TRANSITIONS 流转校验） | ✅ |
| `enums/AuditStatus.java` | — | 审核状态枚举 | ✅ |
| `enums/NoteType.java` | — | 笔记类型枚举（图文/视频） | ✅ |
| `filter/DFAFilter.java` | — | DFA 敏感词 Trie 树过滤器 + Redis Pub/Sub 热更新 | ✅ |
| `service/NoteService.java` | — | 笔记业务（DFA + Cache Aside + 延迟双删） | ✅ |
| `service/CommentService.java` | — | 评论业务（游标分页 + 批量防 N+1） | ✅ |
| `service/FileStorageService.java` | — | 文件存储接口 | ✅ |
| `service/LocalFileStorageService.java` | — | 本地磁盘实现（分层目录 + UUID 命名） | ✅ |
| `mapper/NoteMapper.java` | — | 笔记 Mapper | ✅ |
| `mapper/CommentMapper.java` | — | 评论 Mapper | ✅ |
| `config/FileUploadConfig.java` | — | 静态资源映射 | ✅ |
| `config/RedisPubSubConfig.java` | — | Redis Pub/Sub 敏感词同步 | ✅ |
| `dto/request/` | — | CommentCreateRequest / NotePublishRequest / NoteUpdateRequest | ✅ |
| `dto/response/` | — | NoteDetailVO / NoteItemVO / CommentVO | ✅ |

---

## 6. my-xhs-product（商品服务）— 22 个文件

> **完成度**: ⭐⭐⭐⭐  三级分类 + SPU/SKU 管理
> **端口**: 19006 | **数据库**: MySQL-Content:13307 → my_xhs_product

| 文件 | 类名 | 说明 | 状态 |
|------|------|------|:---:|
| `ProductApplication.java` | — | 启动类 | ✅ |
| `controller/ProductController.java` | — | 商品（SPU详情/SKU列表/分类查询/搜索/新品/热销） | ✅ |
| `entity/Category.java` | — | t_category（三级分类树） | ✅ |
| `entity/Spu.java` | — | t_spu | ✅ |
| `entity/Sku.java` | — | t_sku | ✅ |
| `enums/CategoryLevel.java` | — | 分类层级枚举 | ✅ |
| `service/ProductService.java` | — | 商品查询（Cache Aside + Redis ZSet 排序） | ✅ |
| `service/CategoryService.java` | — | 分类树构建 + 缓存 | ✅ |
| `service/SkuService.java` | — | SKU 价格/库存查询 | ✅ |
| `mapper/` | — | CategoryMapper / SpuMapper / SkuMapper | ✅ |
| `consumer/ProductCacheConsumer.java` | — | 商品缓存更新消费者 | ✅ |
| `dto/request/` / `dto/response/` | — | ProductSearchRequest / SpuVO / SkuVO / CategoryVO | ✅ |

---

## 7. my-xhs-order（订单服务）— 35 个文件

> **完成度**: ⭐⭐⭐⭐  ShardingSphere 分库分表 + 本地消息表
> **端口**: 19011 | **数据库**: MySQL-Order:13308 → my_xhs_order(0-3) + my_xhs_payment

| 文件 | 类名 | 说明 | 状态 |
|------|------|------|:---:|
| `OrderApplication.java` | — | 启动类 | ✅ |
| `controller/OrderController.java` | — | 下单/取消/确认收货/详情/列表 | ✅ |
| `entity/Order.java` | — | t_order（分库分表分片键: user_id） | ✅ |
| `entity/OrderItem.java` | — | t_order_item（订单快照 sku_name/price） | ✅ |
| `entity/LocalMessage.java` | — | 本地消息表（事务消息保证分布式一致性） | ✅ |
| `entity/OrderSnapshot.java` | — | 订单快照（事件溯源） | ✅ |
| `enums/OrderStatus.java` | — | 订单状态枚举（含状态流转） | ✅ |
| `service/OrderService.java` | — | 下单流程（库存预占→优惠券核销→支付发起→本地消息表） | ✅ |
| `service/LocalMessageService.java` | — | 本地消息表（轮询补偿 + 死信处理） | ✅ |
| `service/OrderQueryService.java` | — | 订单查询（ShardingSphere路由 + 订单号映射表） | ✅ |
| `mapper/` | — | OrderMapper / OrderItemMapper / OrderNoMappingMapper / LocalMessageMapper / OrderSnapshotMapper | ✅ |
| `config/ShardingSphereDataSourceConfig.java` | — | ShardingSphere-JDBC 数据源配置 | ✅ |
| `consumer/OrderStatusConsumer.java` | — | 订单状态变更消费者（支付/取消回调） | ✅ |
| `dto/` | — | OrderCreateRequest / OrderVO / OrderItemVO 等 | ✅ |

---

## 8. my-xhs-payment（支付服务）— 26 个文件

> **完成度**: ⭐⭐⭐  Mock 支付 + 退款
> **端口**: 19012 | **数据库**: MySQL-Order:13308 → my_xhs_payment

| 文件 | 类名 | 说明 | 状态 |
|------|------|------|:---:|
| `PaymentApplication.java` | — | 启动类 | ✅ |
| `controller/PaymentController.java` | — | 支付/退款/查询/回调 | ✅ |
| `entity/Payment.java` | — | t_payment（支付记录） | ✅ |
| `entity/Refund.java` | — | t_refund（退款单） | ✅ |
| `enums/PayType.java` | — | 支付方式枚举（支付宝Mock/微信Mock） | ✅ |
| `enums/PaymentStatus.java` | — | 支付状态枚举 | ✅ |
| `enums/RefundStatus.java` | — | 退款状态枚举 | ✅ |
| `service/PaymentService.java` | — | Mock 支付 + 退款流程 + MQ 回调 | ✅ |
| `config/` | — | 数据源配置 | ✅ |
| `consumer/` | — | 支付回调消费者 | ✅ |
| `dto/` | — | PaymentCreateRequest / PaymentVO / RefundVO | ✅ |

---

## 9. my-xhs-search（搜索服务）— 33 个文件

> **完成度**: ⭐⭐⭐⭐  ES 全文搜索 + Completion Suggester + 推荐召回
> **端口**: 19016 | **ES**: 21.91.124.110:19200

| 文件 | 类名 | 说明 | 状态 |
|------|------|------|:---:|
| `SearchApplication.java` | — | 启动类 | ✅ |
| `config/ElasticsearchConfig.java` | — | ES 8.x Java Client（RestClient + Transport） | ✅ |
| `config/IndexInitializer.java` | — | 启动时自动创建索引（IK分词器+Mapping） | ✅ |
| `config/RecommendThreadPoolConfig.java` | — | 推荐召回线程池（10c/20m/100q） | ✅ |
| `controller/SearchController.java` | — | 笔记搜索/商品搜索/搜索建议/历史/热搜/索引重建 | ✅ |
| `controller/RecommendController.java` | — | 推荐 Feed/相似笔记/行为上报/触发计算 | ✅ |
| `service/SearchService.java` | — | 搜索核心（bool查询+高亮+Search After分页） | ✅ |
| `service/SuggestService.java` | — | Completion Suggester + 搜索建议 | ✅ |
| `service/HotSearchService.java` | — | XXL-Job 定时计算热搜榜（Redis ZSet） | ✅ |
| `service/RecommendService.java` | — | 四层推荐流水线（召回→粗排→精排→重排） | ✅ |
| `service/ItemCFService.java` | — | 基于物品的协同过滤（共现矩阵） | ✅ |
| `service/CacheRefreshService.java` | — | 推荐缓存刷新 | ✅ |
| `service/RecallService.java` | — | 多路召回（协同/内容/热度/随机） | ✅ |
| `consumer/NoteIndexSyncConsumer.java` | — | 消费 NOTE_INDEX_TOPIC → ES 索引更新（ExternalGte 防乱序） | ✅ |
| `consumer/ProductIndexSyncConsumer.java` | — | 消费 PRODUCT_INDEX_TOPIC → ES 索引更新 | ✅ |
| `consumer/BehaviorReportConsumer.java` | — | 消费用户行为 → t_user_behavior 落库 | ✅ |
| `dto/` | — | NoteSearchRequest/VO, ProductSearchRequest/VO, HotSearchVO, RecommendFeedVO, RecallItem | ✅ |

---

## 10. my-xhs-cart（购物车服务）— 17 个文件

> **完成度**: ⭐⭐⭐  基本 CRUD + 缓存
> **端口**: 19008 | **数据库**: MySQL-Content:13307 → my_xhs_cart

| 文件 | 类名 | 说明 | 状态 |
|------|------|------|:---:|
| `CartApplication.java` | — | 启动类 | ✅ |
| `controller/CartController.java` | — | 购物车（添加/修改数量/删除/列表/选中/清空） | ✅ |
| `entity/CartItem.java` | — | t_cart_item | ✅ |
| `service/CartService.java` | — | 购物车逻辑（缓存 + DB 双写） | ✅ |
| `mapper/CartItemMapper.java` | — | 购物车 Mapper | ✅ |
| `config/XxlJobConfig.java` | — | XXL-Job 执行器配置 | ✅ |

---

## 11. my-xhs-coupon（优惠券服务）— 19 个文件

> **完成度**: ⭐⭐⭐  模板管理 + 领取 + 核销
> **端口**: 19010 | **数据库**: MySQL-Content:13307 → my_xhs_coupon

| 文件 | 类名 | 说明 | 状态 |
|------|------|------|:---:|
| `CouponApplication.java` | — | 启动类 | ✅ |
| `controller/CouponController.java` | — | 优惠券（领取/列表/使用/查询模板） | ✅ |
| `entity/CouponTemplate.java` | — | t_coupon_template | ✅ |
| `entity/UserCoupon.java` | — | t_user_coupon | ✅ |
| `service/CouponService.java` | — | 领取（Redis Lua 防超发）+ 核销 | ✅ |
| `mapper/` | — | CouponTemplateMapper / UserCouponMapper | ✅ |

---

## 12. my-xhs-inventory（库存服务）— 17 个文件

> **完成度**: ⭐⭐⭐  库存预占 + 释放 + 扣减
> **端口**: 19009 | **数据库**: MySQL-Inventory:13309 → my_xhs_inventory

| 文件 | 类名 | 说明 | 状态 |
|------|------|------|:---:|
| `InventoryApplication.java` | — | 启动类 | ✅ |
| `controller/InventoryController.java` | — | 库存（查询/预占/释放/扣减） | ✅ |
| `entity/Inventory.java` | — | t_inventory（available_stock + locked_stock） | ✅ |
| `service/InventoryService.java` | — | MySQL 行锁实现扣减 + Redis Lua 预占 | ✅ |
| `mapper/InventoryMapper.java` | — | 库存 Mapper | ✅ |
| `consumer/` | — | 订单取消释放库存消费者 | ✅ |

---

## 13. my-xhs-analytics（社交分析服务）— 23 个文件

> **完成度**: ⭐⭐⭐⭐  点赞/收藏/关注（Redis 权威+MQ 异步落库）
> **端口**: 19003 | **数据库**: MySQL-User:13306 → my_xhs_analytics

| 文件 | 类名 | 说明 | 状态 |
|------|------|------|:---:|
| `AnalyticsApplication.java` | — | 启动类 | ✅ |
| `config/RedisScriptConfig.java` | — | Lua 脚本预加载 | ✅ |
| `controller/FavoriteController.java` | — | 收藏/取消收藏/状态/列表 | ✅ |
| `controller/FollowController.java` | — | 关注/取关/检查/关注列表/粉丝列表 | ✅ |
| `controller/LikeController.java` | — | 点赞/取消点赞/状态检查 | ✅ |
| `entity/Favorite.java` | — | t_favorite（MySQL 持久化兜底） | ✅ |
| `entity/Follow.java` | — | t_follow | ✅ |
| `entity/Like.java` | — | t_like | ✅ |
| `dto/event/` | — | FavoriteEvent / LikeEvent（MQ 消息体） | ✅ |
| `mapper/` | — | FavoriteMapper / FollowMapper / LikeMapper | ✅ |
| `consumer/` | — | 4 个消费者（收藏/取消/点赞/取消）异步落库 | ✅ |

---

## 14. my-xhs-counter（计数服务）— 14 个文件

> **完成度**: ⭐⭐  基础计数功能
> **端口**: 19004 | **数据库**: MySQL-Content:13307 → my_xhs_counter

| 文件 | 类名 | 说明 | 状态 |
|------|------|------|:---:|
| `CounterApplication.java` | — | 启动类 | ✅ |
| `controller/CounterController.java` | — | 计数（查询/增量） | ✅ |
| `entity/Counter.java` | — | t_counter（target_type × count_type） | ✅ |
| `service/CounterService.java` | — | Redis 计数器 + MySQL 异步落库 | ✅ |
| `mapper/CounterMapper.java` | — | 计数 Mapper | ✅ |
| `config/XxlJobConfig.java` | — | XXL-Job 执行器配置 | ✅ |

---

## 15. my-xhs-im（即时通讯服务）— 16 个文件

> **完成度**: ⭐⭐⭐  WebSocket 基础通讯
> **端口**: 19014 | **数据库**: MySQL-User:13306 → my_xhs_im

| 文件 | 类名 | 说明 | 状态 |
|------|------|------|:---:|
| `ImApplication.java` | — | 启动类 | ✅ |
| `controller/ImController.java` | — | 聊天（发送/历史/会话列表） | ✅ |
| `entity/ChatMessage.java` | — | t_chat_message | ✅ |
| `service/ChatService.java` | — | 消息存储 + WebSocket 推送 | ✅ |
| `service/WebSocketService.java` | — | WebSocket 连接管理（userId ↔ Session） | ✅ |
| `service/SessionService.java` | — | 会话列表管理 | ✅ |
| `mapper/` | — | ChatMessageMapper / ChatSessionMapper | ✅ |
| `config/WebSocketConfig.java` | — | WebSocket 端点配置 | ✅ |

---

## 16. my-xhs-notification（通知服务）— 19 个文件

> **完成度**: ⭐⭐⭐⭐  通知聚合去重 + 推送模板
> **端口**: 19013 | **数据库**: MySQL-User:13306 → my_xhs_notification

| 文件 | 类名 | 说明 | 状态 |
|------|------|------|:---:|
| `NotificationApplication.java` | — | 启动类 | ✅ |
| `controller/NotificationController.java` | — | 通知（列表/已读/未读数） | ✅ |
| `controller/PushController.java` | — | 推送任务（创建/执行/查询） | ✅ |
| `entity/Notification.java` | — | t_notification（聚合去重: uk_aggregate） | ✅ |
| `entity/PushTemplate.java` | — | t_push_template | ✅ |
| `service/NotificationService.java` | — | 通知聚合（同类型+同目标+同日合并） | ✅ |
| `service/PushService.java` | — | 推送任务（模板渲染 + 批量推送） | ✅ |
| `service/RedissonService.java` | — | Redisson 延迟队列 | ✅ |
| `mapper/` | — | NotificationMapper / PushTemplateMapper | ✅ |
| `consumer/` | — | 通知事件消费者（点赞/评论/关注通知） | ✅ |

---

## 17. 配置文件索引

### 应用配置
| 文件 | 说明 |
|------|------|
| `pom.xml` | 父 POM，统一版本管理（Spring Boot 3.2.5 + Cloud 2023.0.1 + Alibaba 2023.0.1.0） |
| `docker-compose.yml` | 4 MySQL + Redis + Nacos + RocketMQ + ES + Canal + XXL-Job + Prometheus + Grafana + SkyWalking |
| `my-xhs-*/src/main/resources/application.yml` | 15 个模块的应用配置 |
| `my-xhs-order/src/main/resources/sharding-config.yaml` | ShardingSphere 分库分表（4 库 × 4 表） |

### 基础设施配置
| 文件 | 说明 |
|------|------|
| `sql/mysql-user-init.sql` | MySQL-User:13306（6 个数据库 + canal 账号） |
| `sql/mysql-content-init.sql` | MySQL-Content:13307（6 个数据库 + canal 账号） |
| `sql/mysql-order-init.sql` | MySQL-Order:13308（6 个数据库 + canal 账号） |
| `sql/mysql-inventory-init.sql` | MySQL-Inventory:13309（1 个数据库 + canal 账号） |
| `config/rocketmq/broker.conf` | RocketMQ Broker 配置 |
| `config/canal/conf/` | Canal 3 个 instance 配置 |
| `config/prometheus/` | Prometheus 采集 + 9 条告警规则 |
| `config/grafana/` | Grafana Prometheus 数据源 |
| `setup-firewall.sh` | iptables 防火墙（仅允许 21.214.97.212） |

---

## 18. 关键发现 & 待改进

### ✅ 做得好的
- **common 模块**: 注解驱动切面（@DistributedLock/@Idempotent/@RateLimit）设计优秀
- **网关 7 层过滤**: 日志→JWT→染色→HMAC→限流→灰度→版本，完整链路
- **content DFA 过滤器**: Trie 树 + Redis Pub/Sub 热更新
- **search 推荐四层流水线**: 召回→粗排→精排→重排，设计完整
- **analytics 读写分离**: Redis 为权威数据源，MQ 异步落库 MySQL
- **notification 通知聚合**: uk_aggregate 唯一索引防重复
- **order 分库分表**: ShardingSphere 4 库 × 4 表，订单号映射表解决非分片键查询

### ⚠️ 需要关注的
- **0 测试**: 393 个 Java 文件，仅 2 个测试类
- **counter 模块偏薄**: 仅 14 个文件，计数器功能较简单
- **feign 调用耦合**: home 模块通过 Feign 调用 content/user/product/search 等，跨模块耦合较强
- **payment Mock 模式**: 支付为 Mock 实现，生产需对接真实支付网关
- **推荐算法简化**: Item-CF 为简化实现，未用成熟的推荐框架

---

> 本索引覆盖项目全部 393 个 Java 源文件和关键配置文件，可作为后续源码分析的快速定位参考。
