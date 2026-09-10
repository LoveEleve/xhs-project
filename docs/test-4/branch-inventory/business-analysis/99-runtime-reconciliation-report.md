# 运行态复核报告（2026-09-06）

> 背景：此前各模块均为源码级分析 + Mockito 单测，从未做过真实的业务级端到端运行验证。
> 复核确认：**「中间件进程在跑」≠「链路就绪」**。一次性暴露并修复了 11 类运行态问题。

## 复核方式

- 注入测试数据（用户/商品/库存/地址/推送模板），走 gateway 完整调用真实业务接口
- 验证 SQL、Redis、MQ、XXL-Job 全部中间件产物
- 全链路：下单 → 事务消息 → 库存预扣 → 支付(payment服务) → 库存确认 → 发货 → 收货 → 退款 → 库存回补/退券 → 关单

## 发现并修复的问题（11 类）

| # | 问题 | 影响 | 修复 |
|---|------|------|------|
| 1 | XXL-Job 调度全缺失（xxl_job_info/group 空表，10 executor 在线 0 任务） | 关单/超时/补偿/对账等全部兜底从未运行 | 全量注册 19 任务并验证执行（init-xxljob.sql） |
| 2 | RocketMQ 业务 topic 全缺失（仅 INVENTORY_TOPIC，autoCreateTopicEnable=false） | 所有 MQ 发送报 "No route info" 失败 | 全量创建 18 个 topic（init-rocketmq-topics.sh） |
| 3 | RefundNotifyCompensateJob 部分退款误全额（资损） | 部分退款被补偿任务误判全额 → 释放全部库存+退券+订单置已退款 | 只补偿 t_payment.status=3 的支付单 |
| 4 | payment 测试构造器缺参/死代码/补偿 Job 缩进日志失真 | 测试编译失败、误导日志 | 修复，5 测试通过 |
| 5 | 支付/退款结果 MQ 无消费端（PAY/REFUND_RESULT_TOPIC） | Feign 失败时无即时兜底 | 新增 order 侧两消费者，端到端验证 |
| 6 | t_user 缺 role 列 | 登录 BadSqlGrammar | 补 DDL + init-all.sql |
| 7 | MQ 消息 Long→String 跨模块解析断裂（JacksonConfig 全局序列化） | inventory 下单预扣断链、order/home 消费失败 | 4 个消费端兼容字符串/数字 id |
| 8 | order 已完成(3)订单退款被拒 | 收货后退款无法收敛、库存不回补 | onRefundSuccess 允许 1/3→5 |
| 9 | 4 张事件表缺失（t_cart_event/t_note_event/t_payment_event/t_product_behavior） | 事件落库失败（日志告警） | 补 DDL + init-all.sql（两份） |
| 10 | TransactionConfig 全局事务失效（@ConditionalOnBean 类级时序） | **所有读写分离服务 @Transactional 静默无效**，中间异常产生脏数据 | 移除类级条件，jcmd 确认 15 服务全加载 |
| 11 | product 布隆过滤器启动时序（启动时 DB 空 → 后插数据被拦截） | 商品详情查不到 | 重启重建（测试数据先于启动或重启） |

## 端到端验证结果（全部实测通过）

```
下单(skuId=3,qty=2,398元) → 事务消息 → 库存预扣 120→118/0→2 ✓
支付(order→payment服务) → t_payment=1 / 订单0→1 / 库存确认 locked2→0 ✓
发货1→2 ✓ 确认收货2→3 ✓
退款(payment) → t_refund=1 / t_payment=3 / 订单3→5 / 库存回补118→120 ✓
关单: orderCloseJob 关闭超时订单0→4 + 库存回补180/0 ✓
券: 建券(满300减50) → 领券(COUPON_CLAIM_TOPIC消费) → 下单核销2999-50=2949
    → 退款 → 券回退status1→0/used_order_id→NULL ✓
购物车: 加购 → CART_TOPIC → MySQL同步 + 事件落库(补表后) ✓
发布笔记: 事务原子(笔记+本地消息) → FEED_TOPIC → FeedPushConsumer 推送 ✓
本地消息兜底: LocalMessageRetryJob 补发(20:06 脏数据笔记被补推) ✓
MQ即时兜底: 支付成功Feign+MQUU(幂等共存) ✓
```

## 遗留说明

- 均为**设计权衡/正确终态**项（详见 payment 模块分析文档 P-2~P-5）
- Mock 支付渠道：order→payment 服务链路已真实走通（pay/refund/回调/对账），仅渠道资金入账为模拟
- 测试数据（5 订单/2 退款/2 笔记/1 券）保留，可复用后续联调

## 第一轮补测（2026-09-06 第二轮全模块接口冒烟）

对 user/content/product/cart/inventory/coupon/social(analytics/counter) 全部核心接口实测，
额外发现并修复 2 类问题：

| # | 问题 | 影响 | 修复 |
|---|------|------|------|
| 12 | gateway BodyCacheFilter 对无 body 的 POST/PUT/DELETE 空响应 | block 拉黑/取消、logout 等无 body 写接口经 gateway 挂起（HTTP 200 空 body） | `DataBufferUtils.join` 后 `defaultIfEmpty`，已修并验证 |
| 13 | TransactionConfig 类级 @ConditionalOnBean 时序 bug（二轮迭代） | ① 类级条件评估过早→读写分离服务未加载事务管理器，所有 @Transactional 静默无效 ② 去掉后 home（无 DataSource 纯 Redis/MQ 服务）启动失败 | 条件移至 @Bean 方法级：有 DataSource 创建事务管理器、无则跳过；15 服务全量验证 |
| 14 | search ES 索引任务 LocalDateTime 序列化失败 + 单条脏 SPU 阻塞批次 | 商品增量/重建索引持续失败（成功=0/3）；无 SKU 的 SPU 中断整个批次 | ElasticsearchConfig 注册 JavaTimeModule；IncrementalIndexSyncJob/IndexRebuildJob 单条 try-catch 跳过；清理 3 个污染 SPU。product_index 0→5 文档，商品搜索高亮匹配 |
| 15 | order 无效收货地址下单成功（降级空地址） | addressId 必填但获取失败时降级空地址 → 无地址订单（无法收货） | resolveAddressSnapshot 改为抛 ADDRESS_NOT_FOUND 拒绝下单，59 测试全绿 |
| 17b | payment PaymentService.reconcile() 死代码（无调度入口） | 支付对账逻辑从不执行 | 新增 PaymentReconcileJob(@XxlJob) + 注册；触发验证 totalRecords=4 |
| 16 | search 推荐行为写库失败（t_user_behavior 归属库错误） | deploy init-all.sql 把 t_user_behavior 建在 my_xhs_analytics 段，但 search 数据源连 my_xhs_content → BadSqlGrammar 表不存在，BehaviorReportConsumer 持续重试，推荐行为无法落库 | 移到 my_xhs_content 段（对齐根目录版+新枚举1-曝光~7-停留）；线上补建表，行为上报成功落库 |

**构建流程教训**：并行 `mvn -am package` 存在本地仓库竞争，部分服务内嵌 common 为旧版
（事务修复未生效）。必须**串行 clean package** 且先 `mvn install my-xhs-common` 与根 pom。

补测通过（未发现问题）：注册登录、地址 CRUD、block、个人信息、发布/删除笔记、评论增删查、
点赞/取消/计数(SOCIAL_TOPIC→counter)、收藏/取消、关注/取关/关系、SPU/SKU 创建、类目树、
购物车增删改查/数量/合并、领券核销退券、库存预扣确认。

第二轮补测（im/notification/search 运行态实测）：
- notification：NOTIFICATION_TOPIC 消费→t_notification 落库→模板渲染"点赞通知"→未读=1→标记已读归零 ✅
- search：note_index 4 文档（Canal 同步正常）+ 笔记搜索高亮匹配；product_index 0→5（修复后增量任务商品=3）✅
- im：REST（ticket 签发/会话/历史/未读/已读）全 200；WS 握手鉴权 fail-closed（无 ticket im 拒绝，日志"缺少 ticket 参数"）✅
- 全链路最终回归：下单→预扣→支付→确认→发布→Feed 推送→点赞→计数→搜索→通知 全部通过，15 服务 UP

## 第三轮 全模块测试矩阵闭环（2026-09-08）

15 个服务模块补齐 L1-L4 测试用例矩阵（gateway 07、其余 01），核心项实测并标注状态。
同时为 analytics/counter/home/im/notification/search 补齐 02 深度分析（10 部分）。

### P0 一致性专项实测（资损核心）

| 专项 | 场景 | 结果 |
|---|---|---|
| order 事务消息 | 半消息→本地事务→COMMIT→库存预扣 | ✅ 链路通 |
| order 本地消息补发 | 注入 status=2/retry=5 → Job 补发 | ✅ 成功=1 |
| inventory 并发预扣 | 20 并发，Redis/MySQL 一致 | ✅ 149→129 不超卖 |
| inventory 超量预扣 | 5×qty=50 超库存 | ✅ 只扣100 超额拒绝 |
| coupon 并发领券 | 10 并发，perUserLimit=2 | ✅ remain98→96 限领2 张 30013 |
| analytics 点赞幂等 | 重复点赞/取消 | ✅ Set-based 不重复/归0 |

### 安全边界确认（非缺陷）

- 内部接口（SKU 批量/支付回调）外部调用 401/403 拒绝
- `GatewayAuthTrustFilter` 剥离未认证伪造 X-User-Id
- 管理接口需 X-Admin-Call（fail-closed）
- order 无效 addressId 拒绝（已修复）

### 跨服务证据链

analytics→counter（计数）、analytics→notification（通知落库）、content→counter（评论计数）、
content→FEED_TOPIC→home（推送）、product→PRODUCT_INDEX_TOPIC→search（ES 索引）、
order→payment→inventory→coupon 交易闭环。

### 待专项（P1/P2，需专门客户端/故障注入/压测）

故障注入（Redis/MQ 宕机降级）、DLQ 死信（MQ 持续失败）、事务回查 broker 异常注入、
TraceId 端到端断言、SSE 实时推送、WS 消息路由、推荐质量（需行为数据量）、限流触发、HMAC 重放。

## 第四轮 专项验证（2026-09-08）

| 专项 | 场景 | 结果 |
|---|---|---|
| HMAC 签名/重放 | 临时启用验证后关闭 | ✅ 合法200/缺签403/篡改403/nonce重放403/过期403；默认关闭不影响业务 |
| 限流压测 | @RateLimit 5/60s | ✅ 同用户第6次40202，Lua滑动窗口精确 |
| SSE 实时推送 | ticket→长连接→心跳→通知 | ✅ event:notification(聚合"等2人赞了")+unread-count |
| TraceId 端到端 | 注入X-Trace-Id跨order→MQ→inventory | ✅ 三服务日志同traceId |

### 已闭环项汇总（运行态复核+矩阵+专项）

- 17 类运行态问题修复
- 15 服务 L1-L4 测试矩阵 + P0 一致性专项（并发/幂等/超卖）
- HMAC/限流/SSE/TraceId 安全与可观测专项
- 剩余：DLQ 死信、事务回查 broker 注入、推荐质量（需专门环境/客户端/数据量）

## 第五轮 混沌工程故障注入（2026-09-08）

发现项目 common 内置混沌框架（`common/chaos`：ChaosAutoConfiguration + ChaosInterceptor，
`chaos.enabled` + `faults` 配置，支持 DELAY/EXCEPTION/RETURN_NULL，按 Service/Controller/Mapper
方法 AOP 注入）。此前覆盖对账标注"暂不分析"，本轮验证其可用并用于故障注入。

| 注入场景 | 目标 | 结果 |
|---|---|---|
| home 聚合异常 | CartAggService.getCartAgg EXCEPTION | ✅ 故障真实注入(聚合500)，关闭恢复200 |
| gateway 下游超时 | user UserService.getUserInfo DELAY 11s | ✅ 触发 gateway 5s 超时(PT5S) |

### 第 17 个运行态修复（混沌注入暴露）

- **现象**：gateway 下游超时返回 **500** 而非 504
- **根因**：下游超时抛 `ResponseStatusException(504)`，其 cause 链无 ConnectException/TimeoutException
  类型（超时被包装），`GlobalExceptionHandler.determineHttpStatus` 未识别 → 落入 500
- **修复**：识别 `ResponseStatusException` 取其 statusCode
- **验证**：修复后 gateway 超时返回 `{"code":504,"message":"请求超时，请稍后重试"}`，日志 status=504

### 混沌框架能力与限制

- AOP 切点：`com.myxhs..service/controller/mapper`（不拦截 feign 包；注入 Service 层会绕过 Feign fallback）
- 注入目标匹配：类名.方法名（SimpleName），支持通配
- 用途：验证异常映射、超时降级、回滚/补偿、调用方容错

## 经验

运行态复核发现了源码分析 + 单测无法覆盖的问题：topic/job/schema/启动时序/跨模块消息契约/
无 body 请求/gateway 响应体/构建并行 race/异常状态码映射。
混沌工程（项目内置 chaos 框架）可真实注入 DELAY/EXCEPTION 暴露正常路径测不到的异常处理缺陷。
此后模块分析文档的「待运行确认」项应尽快实测闭环，而非依赖「应有兜底」的假设。

## 剩余待测项分类（48 项，需专门环境/构造）

以下项无法在当前单实例环境安全验证，按所需条件分类（诚实标注，不伪称通过）：

### A. 基础设施级故障注入（需 ChaosBlade 或停中间件，风险高）
- Redis 不可用降级（限流/幂等降级放行、缓存穿透）——停 Redis 触发哨兵切换，影响全局
- MQ 持续失败/DLQ 死信——需 broker 长时间不可用或消费持续失败
- inventory MQ 失败回滚（预扣 Redis/Outbox 一致）、扩容窗口保护
- counter Buffer 刷盘失败重试+对账（需 DB 写入失败）
- analytics Redis/MQ 故障降级

### B. 并发压测（需压测工具）
- cart 清空与加购并发、事件乱序（CHECK/DELETE 同毫秒）、对账并发
- notification 未读并发 mark-read
- 各服务乐观锁并发竞态

### C. 专门 MQ 消息构造（需带 tag 的 canal/flat 消息）
- counter MQ 重复消息去重、LIKE/UNLIKE 乱序、懒迁移（消息需 tag 匹配 consumer selectorExpression）
- search ES 版本防乱序（需 canal 格式消息含 ts/binlog version）
- coupon 幽灵券（MQ 超时但 broker 已投递）

### D. 双实例/专门客户端
- notification SSE 跨实例推送（SseCrossInstanceSubscriber）
- im 跨实例路由（一致性哈希 + Redis pub/sub）、离线消息补发
- home 大V发件箱（需粉丝数达阈值）、推送失败断点续推
- 超50项购物车合并放大

### E. 业务数据量
- 推荐质量（需行为数据量支撑 ItemCF/热池）

### F. 代码逻辑已确认（无直接运行入口或低风险）
- product 分类环路校验（树构建 visited 保护，无写接口触发）
- inventory TCC Try/Confirm/Cancel（需 TCC 调用入口）
- cart 6 个 Lua 原子性（代码审查 + 部分运行验证）
- admin 白名单 method 边界

### 已安全验证的故障场景（本轮）
- 停 cart → home 聚合 503（Feign fallback 降级）
- 停 inventory → order 30004（fail-closed）
- 停 product → order 50002（fail-closed）
- 停 MQ broker → order 下单 500（事务消息 fail-closed）
- chaos DELAY 11s → gateway 504（修复后）
- chaos EXCEPTION → 聚合异常注入生效
- iptables 阻断 Redis 6379 → gateway 鉴权 fail-closed(401) + 业务层请求 hang(客户端超时待优化) + Redis 恢复后自愈
- ChaosBlade 下载受阻(GitHub超时/OSS镜像404), 改用 iptables 实现宿主机级故障注入

