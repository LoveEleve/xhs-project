# my-xhs 项目 P8 级别全面深度评审报告（合订本）

> 评审日期：2026-06-01 | 评审范围：16 模块 × 396 文件 × 逐行代码
> 版本历程：v1(67分 表面) → v2(47分 P8深化) → v3(38分 终极逐行)
> 先决文档：`COMPREHENSIVE-REVIEW-REPORT.md`（华仔对标）、`P8-LEVEL-ROADMAP.md`（限流深度方案）
> 本文档合并自：`P8-DEEP-REVIEW-RESULT.md` + `P8-FINAL-REVIEW-PART-A.md` + `P8-FINAL-REVIEW-PART-B.md`

---

## 评分修正历史

| 版本 | 综合得分 | 关键词 |
|------|:---:|------|
| v1（表面核查） | 67 | "能不能跑" — 代码完整但工程化薄弱 |
| v2（P8 深化） | 47 | Fallback 大面积静默失效 + 配置全服务闲置 |
| **v3（终极逐行审计）** | **38** | Feed 管线断裂 + 20+ 代码坏味道 + 依赖声明但未集成 |

---

## 第一章：致命级发现 — TOP 6

### #1 🔴 Feed 流管线完全断裂 ← v3 新发现

**根因**：`my-xhs-content/NoteService.publishNote()` L94 写死 TODO，不发 MQ：

```java
// TODO: 异步通知 Feed 服务（推送到粉丝收件箱），等 Feed 服务开发后接入 RocketMQ
```

而 `my-xhs-home` 已有完整基础设施在等待：

| 组件 | 代码行数 | 状态 |
|------|:---:|------|
| `FeedPushConsumer` | 194 | ✅ 完整实现 |
| `FeedService` | 471 | ✅ 完整实现 |
| `NoteAggService` | 230 | ✅ 完整实现 |
| `NotePublishEvent` DTO | 29 | ✅ 数据结构 |
| `HomeController` | 120 | ✅ API 就绪 |
| **NoteService 发送 MQ** | **0** | 🔴 **缺失 — 整条管线断路** |

**影响**：首页 Feed 流核心功能完全不可用。所有 Feed 请求返回的是测试数据（`FeedTestController`）或空数据。

### #2 🔴 14 个 FallbackFactory 全部静默失效

| 服务 | FeignClient 数 | Sentinel 依赖 | feign.sentinel.enabled? | 实际生效？ |
|------|:---:|:---:|:---:|:---:|
| **home** | 11 | ❌ 缺 | ❌ 缺 | ❌ **全失效** |
| **cart** | 1 | ❌ 缺 | ❌ 缺 | ❌ **失效** |
| order | 3 | ✅ 有 | ✅ true | ✅ |
| payment | 1 | ✅ 有 | ✅ true | ✅ |

**危害**：下游故障 → home BFF 聚合层直接 500，用户看到的不是优雅降级而是报错页。

### #3 🔴 ORDER_COMPENSATION_TOPIC 无消费者

全项目搜索 0 个消费者。关单补偿逻辑形同虚设。订单状态不一致无兜底。

### #4 🔴 Content 模块 CommentService 通知链路断

`CommentService.createComment()` L140：
```java
// TODO: 异步通知笔记作者（等通知服务开发后接入 RocketMQ）
```

评论发表后**笔记作者收不到通知**。`NotificationEventConsumer` 虽已实现但收不到消息。

### #5 🔴 Nacos Config 全服务未启用

15 个业务服务全部 `spring.cloud.nacos.config.enabled: false` 或注释。`@RefreshScope` 全项目 0 处。改配置 = 重启。

### #6 🔴 SkyWalking Agent 全服务未挂载

OAP+UI 在 docker-compose 中运行，但微服务 `-javaagent` 参数未配置。全链路追踪靠手动 TraceId 透传，缺失 DB/Redis/MQ 自动耗时采集。

---

## 第二章：代码质量审查 — 18 项具体发现

### Controller 层

| # | 位置 | 问题 | 严重度 |
|---|------|------|:---:|
| 1 | `OrderController` L104-127 | 支付模式路由 (`if ("remote".equals(payType))`) 写在 Controller 中，应委托 Service | 🟡 |
| 2 | `ProductController` L56 | `SpuUpdateRequest` 缺 `@Valid`（创建有，更新无） | 🔴 安全 |
| 3 | `CouponController` L34, L51 | 返回 `R<CouponTemplate>` 直接暴露 DB 实体（含 `deleted`/`createdAt`），应用 VO | 🟡 |
| 4 | `ImController` L67-89 | 返回 `Map<String, Object>` 而非类型化 VO | 🟡 |
| 5 | `ImController` 多处 | `DateTimeFormatter` 直接格式化日期，应在 DTO 层用 Jackson | 🟢 |
| 6 | `CounterController` L32 | `CounterRequest` 缺 `@Valid` | 🟡 |
| 7 | `SearchController` L36, L54 | GET 方法的 POJO 绑定缺 `@Valid` | 🟡 |

### Service 层

| # | 位置 | 问题 | 严重度 |
|---|------|------|:---:|
| 8 | `OrderService.cancelOrder()` | 顺次 Feign 调 3 个服务，无 CompletableFuture 并行 | 🟡 性能 |
| 9 | `CartService` 批量查 SKU | Feign 循环单查，每商品一次 RPC | 🟡 性能 |
| 10 | `RedisKeyConstants` | 部分模块硬编码 Key（`CartReconcileJob`、`IndexRebuildJob`），未统一用常量 | 🟡 |
| 11 | `CounterService.batchGetCounts()` | Pipeline 未命中时逐条 MySQL 回退—有 N+1 风险 | 🟡 |
| 12 | `RecommendService.recommendFeed()` | ~150 行，应拆分 | 🟢 |
| 13 | `AggregatorThreadPoolConfig` | 164 行，线程池 Prometheus 指标缺失 | 🟡 |

### Exception & Resource

| # | 位置 | 问题 | 严重度 |
|---|------|------|:---:|
| 14 | 全局 | 无 Graceful Shutdown 配置（`server.shutdown: graceful` 未启用） | 🟡 |
| 15 | 全局 | `@PreDestroy` 存在但无统一验证机制 | 🟢 |

### POM 依赖

| # | 问题 |
|:---:|------|
| 16 | `hotkey.version: 1.0.0` 在 properties 声明，但 `dependencyManagement` 无对应条目—JD-hotkey 从未实际集成 |
| 17 | `testcontainers.version: 1.19.8` 声明但 0 处实际使用 |
| 18 | Jackson 版本统一用 `jackson-bom: 2.16.1`，与 ES 8.12 兼容性需验证 |

---

## 第三章：三大模块逐行审计

### IM 模块（16 文件）

| 检查项 | 结论 |
|--------|------|
| WebSocket 认证 | ✅ 两步法 Ticket 机制正确。`POST /ws/ticket` 获取 5 分钟短期 JWT → `ws://host/ws?ticket=xxx`。type=`ws_ticket` 防 access_token 冒充 |
| 连接管理 | ✅ `ConcurrentHashMap<Long, WebSocketSession>` + 单用户单连接（踢旧设备，状态码 4001） |
| 消息持久化 | ✅ `MessagePersistService.saveMessageWithTransaction()` — 1 条消息 + 2 条会话更新在同一事务 |
| 跨实例路由 | ✅ Redis 路由注册 + `IM_ROUTE_TOPIC` MQ + Redis Pub/Sub 兜底 |
| 离线消息 | ✅ Redis ZSet 存储离线消息 ID |
| 未读计数 | ✅ Redis Hash 按会话维护，有对账 Job (`UnreadReconcileJob`) |
| TODO/FIXME | ⚠️ `ImWebSocketHandler.sendMessage()` 的 `synchronized(session)` — 高并发瓶颈 |
| 代码质量 | 🟡 `ImController` 返回 `Map<String,Object>` 而非 VO |
| 消息可靠性 | ⚠️ DB 写入成功后 MQ 路由投递，无事务保障。极端情况下靠离线消息兜底 |

### Notification 模块（19 文件）

| 检查项 | 结论 |
|--------|------|
| 时间窗口聚合 | ✅ `NotificationAggregator` 的 5 分钟窗口用 Lua `SETNX` 原子操作正确 |
| SSE 连接管理 | ✅ `ConcurrentHashMap<Long, SseEmitter>` + `put()` 原子替换，同用户单连接 |
| SSE 心跳 | ✅ `@Scheduled(fixedRate=10000)` 每 10 秒 Pipeline 批量续约 Redis + 发心跳事件 |
| SSE Ticket | ✅ 30 秒有效期一次性 Ticket，`getAndDelete()` 原子消费 |
| 跨实例推送 | ✅ Redis 路由检查 → Pub/Sub channel `notify:sse:channel` → 解析消息 → 本地推送 |
| 未读计数 | ✅ Redis String (total) + Redis Hash (by type) + Lua 安全 DECR + Lua 原子重置 |
| 对账 | ✅ `UnreadReconcileJob` (XXL-Job) 每 5 分钟以 DB 为准修复 Redis |
| 缺陷 | ⚠️ `SseEmitter` 超时设为 0 (永不超时)，网络断开后依赖 heartbeat 检测—有 10 秒延迟 |

### Feed 流模块（my-xhs-home, 8 文件）

| 检查项 | 结论 |
|--------|------|
| FeedPushConsumer | ✅ 大V/普通用户分发逻辑正确。`checkBigV()` 按粉丝数 10 万阈值判断 |
| FeedCleanupJob | ✅ SCAN 遍历 + 每 Key 休眠 50ms 限速，防止 Redis 阻塞 |
| FeedService.getFollowFeed() | ✅ 发件箱+收件箱双读 + `note.lua` 取 TopN + Pipeline 聚合 |
| FeedService.getRecommendedFeed() | ✅ 热门+关注+内容 三路聚合 + 游标分页 |
| 性能瓶颈 | ⚠️ 拉模式：关注 1000 人时需合并 1000 个 ZSet，`note.lua` 取 Top 20 一定程度缓解但有天花板 |
| **管线连接** | 🔴 **Content→Feed 的 MQ 不通—NoteService 不发 FEED_TOPIC** |

---

## 第四章：P8 差距全景 → 逐维度深度分析

| 维度 | 当前水平 | P8 差距 | 影响级别 |
|------|:---:|------|:---:|
| 限流（已单独评审） | ★★☆ | 四层纵深防御缺失 | 🔴 P0 |
| **熔断降级** | ★☆☆ | FallbackFactory 大面积静默失效 | 🔴 P0 |
| **负载均衡** | ★☆☆ | 零自定义策略 | 🟡 P1 |
| **多级缓存** | ★★★ | CacheHelper 成熟，但 Caffeine 未推广、双删有缺口 | 🟡 P1 |
| **分布式事务补偿** | ★★☆ | 补偿链路断裂、无死信处理 | 🔴 P0 |
| **消息队列高级特性** | ★★☆ | 缺顺序/批量/死信/轨迹 | 🟡 P1 |
| **可观测性** | ★★☆ | Agent未挂、Dashboard缺失、日志原始 | 🔴 P0 |
| **配置管理** | ☆☆☆ | Nacos Config 全服务未启用 | 🔴 P0 |
| **灰度发布** | ★★☆ | 有染色但缺路由+验证+回滚闭环 | 🟡 P1 |
| **数据库深度** | ★★★ | 分片好，但缺读写分离/慢查询治理 | 🟡 P1 |
| **测试体系** | ☆☆☆ | 仅 2 个测试类 | 🔴 P0 |
| **CI/CD** | ☆☆☆ | 零 Pipeline | 🔴 P0 |

---

### 维度一：熔断与降级体系

#### 当前状态

**致命发现：FallbackFactory 大面积静默失效！**

```
正常流程（有 Sentinel）：
  home → HomeFeignClient → Product Service 超时
        → Sentinel 捕获 → FallbackFactory.create(Throwable)
        → 返回 R.ok(Collections.emptyMap())  【用户看到空内容但页面不崩溃】

实际流程（home 无 Sentinel）：
  home → HomeFeignClient → Product Service 超时  
        → Feign RetryableException → 被 FeignErrorDecoder 包装
        → 直接抛到 Controller
        → GlobalExceptionHandler 捕获 → 返回 500 Internal Server Error
        → 前端展示"服务器错误" ❌
```

#### P8 级别三级熔断降级体系

```
L1: 服务级熔断 (Feign + Sentinel)
  下游错误率 > 50% or 慢调用 > 1s 占比 80% → 熔断 OPEN → 5s 后半开探测 3 次 → 正常则关闭

L2: 方法级熔断 (MyBatis Interceptor / Redis AOP)
  同一条 SQL 连续 5 次 > 200ms or Redis 连接池耗尽 → 短路返回（读→null, 写→异步队列）

L3: 业务级降级 (Nacos Config 开关)
  大促期间关闭推荐/Feed流，保下单/支付
  动态配置推送 → @RefreshScope 监听 → 实时切换
```

#### 必须修复项

| # | 修复项 | 文件 |
|---|--------|------|
| 1 | **home 添加 sentinel 依赖** | `my-xhs-home/pom.xml` |
| 2 | **home 启用 feign.sentinel.enabled** | `my-xhs-home/application.yml` |
| 3 | **cart 添加 sentinel 依赖** | `my-xhs-cart/pom.xml` |
| 4 | **cart 启用 feign.sentinel.enabled** | `my-xhs-cart/application.yml` |

#### 大促降级预案表

| 服务 | 正常模式 | 轻度降级（QPS 120%） | 重度降级（QPS 200%+） | 直接关闭 |
|------|---------|-------------------|---------------------|:---:|
| **order/payment** | 正常 | 正常（不可降） | 正常（不可降） | ❌ |
| **inventory** | 精确库存 | 仅展示有无货 | 关闭库存查询 | ❌ |
| **product** | 完整详情 | 无推荐商品区 | 仅标题+价格+库存 | ❌ |
| **search** | 全文检索 | 仅热门搜索词 | 关闭搜索建议 | ✅ |
| **feed** | 个性化推荐 | 热门兜底 | 仅关注流 | ✅ |
| **notification** | 实时推送 | 批量聚合（30min） | 关闭推送 | ✅ |
| **analytics** | 实时统计 | 延迟统计 | 关闭长尾统计 | ✅ |

#### 用 Nacos Config 实现降级开关

```java
@Component
@RefreshScope  // 关键：Nacos 配置变更后自动刷新
@ConfigurationProperties(prefix = "degrade")
public class DegradeSwitchManager {
    private boolean recommendDegrade = false;
    private boolean searchSuggestDegrade = false;
    private boolean feedDegrade = false;
    private boolean notificationDegrade = false;
    private boolean hotFallback = false;
    // getters/setters...
}
```

```yaml
# Nacos Config: my-xhs-degrade-switches.yml
degrade:
  recommend-degrade: false
  search-suggest-degrade: false
  feed-degrade: false
  notification-degrade: false
  hot-fallback: false
```

---

### 维度二：负载均衡深度设计

#### 当前状态：彻底空缺

- ❌ 零自定义 `ReactorServiceInstanceLoadBalancer` 实现
- ❌ 零 `ServiceInstanceListSupplier` 自定义
- ❌ 零 `@LoadBalancerClient` / `@LoadBalancerClients` 注解
- ❌ 所有服务的 Nacos metadata 均**未配置** `version` 字段

**实际影响**：IM WebSocket 无法一致性 Hash → 同用户连不同实例；灰度发布无法按版本路由；压测流量无法隔离。

#### P8 多策略负载均衡

| 策略 | 适用服务 | 实现要点 |
|------|------|------|
| **加权响应时间** | product, search, content | 实时采集 P99 RT，权重 = 1/RT |
| **一致性 Hash** | IM | TreeMap 虚拟节点 + userId hash |
| **同机房优先** | 所有服务（多 AZ 后） | NacosRule + zone metadata |
| **最小连接数** | notification (SSE) | 选择活跃连接最少的实例 |
| **灰度标签路由** | 所有服务（金丝雀时） | X-Gray-Tag → metadata.version 匹配 |

#### 一致性 Hash 实现

```java
public class IMConsistentHashLoadBalancer 
        implements ReactorServiceInstanceLoadBalancer {
    
    private final TreeMap<Integer, ServiceInstance> ring = new TreeMap<>();
    private static final int VIRTUAL_NODES = 128;
    
    @Override
    public Mono<Response<ServiceInstance>> choose(Request request) {
        Long userId = TraceContextHolder.getUserId();
        if (userId == null) return roundRobin(request);
        
        return supplierProvider.getIfAvailable().get(request)
            .next()
            .map(instances -> {
                rebuildRing(instances);
                int hash = consistentHash(userId);
                Map.Entry<Integer, ServiceInstance> entry = ring.ceilingEntry(hash);
                if (entry == null) entry = ring.firstEntry();
                return new DefaultResponse(entry.getValue());
            });
    }
    
    private int consistentHash(long key) {
        return (int) ((key * 2654435761L) & 0x7FFFFFFF) % (VIRTUAL_NODES * 100);
    }
}
```

---

### 维度三：多级缓存架构与热点治理

#### 当前状态

| 能力 | 状态 | 详情 |
|------|:---:|------|
| CacheHelper | ✅ 成熟 | Cache Aside + 穿透/击穿/雪崩全覆盖 |
| 延迟双删 | ⚠️ 有缺口 | 第二次删除失败后不发 MQ，靠 Canal→MQ 间接兜底（延迟 1~3s） |
| TTL 随机偏移 | ✅ | `+timeoutSeconds/6` 的随机因子 |
| 空值缓存 | ✅ | NULL_PLACEHOLDER，2min TTL |
| Caffeine L1 | ⚠️ 仅 product | user/content/counter 等高并发服务缺失 |
| 缓存预热 | ❌ 无 | 冷启动大量 DB 穿透 |
| BigKey 监控/拆分 | ❌ 无 | 无监控、无自动拆分 |
| JD-hotkey | ❌ 未集成 | pom 声明了版本号但无 dependency |

#### 延迟双删的终极兜底缺失

```
异常流程：
  更新 DB → deleteAfterUpdate(重试3次) → delayDoubleDelete(立即删 + 500ms后删)
                                              └─ 第二次删失败 ❌ 
                                              只记录日志，不显式发 MQ
                                              靠 CacheEvictConsumer (Canal→MQ) 间接兜底
                                              间隔 1~3 秒，窗口期有脏读
```

**P8 修复**：在 `delayDoubleDelete` catch 块中增加 `rocketMQTemplate.syncSend("CACHE_EVICT_TOPIC", key)`。

#### 缓存预热

```java
@Component
public class CacheWarmUpRunner implements ApplicationRunner {
    @Override
    public void run(ApplicationArguments args) {
        CompletableFuture.runAsync(() -> {
            warmUpHotSearch();      // 热搜词 Top 50
            warmUpHotNotes();       // 热门笔记 Top 100
            warmUpCategoryTree();   // 商品分类树
            warmUpCouponTemplates();// 优惠券模板
        });
    }
}
```

#### BigKey 预警阈值

- String: > 10KB → 警告
- Hash/Set/ZSet: > 5000 成员 → 警告，> 50000 → 拆分
- 重点关注：`myxhs:counter:like:*`（点赞 Set）、`myxhs:follow:list:*`（粉丝列表）

---

### 维度四：分布式事务补偿体系

#### 当前状态

| 能力 | 状态 |
|------|:---:|
| RocketMQ 事务消息（下单） | ✅ |
| 本地消息表 retry | ✅ |
| 支付补偿 (每 2 分钟, 最多 10 次) | ✅ |
| 退款补偿 (每 3 分钟) | ✅ |
| **ORDER_COMPENSATION_TOPIC** | 🔴 无消费者 |
| **死信队列 (DLQ)** | 🔴 零配置 |
| TCC 模式 | ❌ |
| Saga 模式 | ❌ |

#### ORDER_COMPENSATION_TOPIC 消费者实现

```java
@Component
@RocketMQMessageListener(topic = "ORDER_COMPENSATION_TOPIC",
    consumerGroup = "order-compensation-group",
    consumeThreadMax = 2, maxReconsumeTimes = 3)
public class OrderCompensationConsumer implements RocketMQListener<MessageExt> {
    @Override
    public void onMessage(MessageExt msg) {
        CompensationMessage cm = JSON.parseObject(new String(msg.getBody()), CompensationMessage.class);
        // 幂等保护
        String idempotentKey = "compensation:" + cm.getOrderId();
        if (!redissonClient.getBucket(idempotentKey).setIfAbsent("1", Duration.ofMinutes(10)))
            return;
        try {
            orderService.closeTimeoutOrder(cm.getOrderId());
        } catch (Exception e) {
            if (msg.getReconsumeTimes() >= 3) saveToManualProcess(cm);
            throw e;
        }
    }
}
```

#### 死信队列消费者

```java
@Component
@RocketMQMessageListener(topic = "%DLQ%order-compensation-group",
    consumerGroup = "dlq-consumer-group", consumeThreadMax = 1)
public class DeadLetterQueueConsumer implements RocketMQListener<MessageExt> {
    @Override
    public void onMessage(MessageExt msg) {
        log.error("死信消息: topic={}, msgId={}, body={}", msg.getTopic(), msg.getMsgId(), new String(msg.getBody()));
        deadLetterRepository.save(record);  // 入库
        alertService.sendAlert("死信消息告警", ...); // 告警
    }
}
```

#### 退款流程 Saga 模式

```
正向：freezeInventory → freezePayment → releaseCoupon
补偿：Step3 失败 → compensateStep2 → compensateStep1
      Step2 失败 → compensateStep1
      Step1 失败 → 无补偿（事务未发生）
```

---

### 维度五：消息队列高级特性

21 个 RocketMQ Consumer 全是 `CONCURRENTLY` 模式。

| 特性 | 状态 |
|------|:---:|
| 普通/事务/延时消息 | ✅ |
| **顺序消息** | ❌ 评论排序用 MySQL ORDER BY |
| **批量消息** | ❌ 计数逐条写 |
| Tag 过滤 | ⚠️ 部分（SOCIAL_TOPIC） |
| **死信处理** | ❌ |
| **消息轨迹** | ❌ |
| Exactly Once | ❌ 靠业务幂等 |

#### 顺序消息实现

```java
// Producer: 按 noteId 路由到同一队列
rocketMQTemplate.syncSendOrderly("CONTENT_TOPIC:COMMENT", message, 
    comment.getNoteId().toString(), 3000);

// Consumer: ORDERLY 模式 + 单线程
@RocketMQMessageListener(topic = "CONTENT_TOPIC", selectorExpression = "COMMENT",
    consumeMode = ConsumeMode.ORDERLY, consumeThreadMax = 1)
```

#### 计数批量攒批

```java
// BlockingQueue + ScheduledExecutorService
// 累计 100 条 or 100ms 超时 → 批量 syncSend
// @PreDestroy 配合优雅停机 flush
```

---

### 维度六：可观测性三大支柱

| 能力 | 状态 |
|------|:---:|
| Prometheus + 9 告警 | ✅ |
| Micrometer 指标 | ✅ |
| **SkyWalking Agent** | ❌ 未挂载 |
| **Grafana Dashboard** | ❌ 零 JSON |
| **结构化日志** | ❌ 纯文本 |
| **ELK/EFK** | ❌ 未部署 |
| TraceId 透传 | ✅ 手动透传 |

#### Grafana Dashboard 设计

**业务大盘**：

| Panel | PromQL |
|-------|--------|
| 下单成功率 | `rate(order_create_success[5m]) / rate(order_create_total[5m])` |
| 支付转化率 | `rate(pay_success[5m]) / rate(order_create_success[5m])` |
| QPS 热力图 | `sum(rate(http_server_requests_seconds_count[1m])) by (uri, service)` |
| P99 响应时间 | `histogram_quantile(0.99, ...)` |
| 限流触发次数 | `rate(sentinel_block_total[1m]) by (resource)` |

**中间件大盘**：HikariCP 连接池 / Redis 连接池 / ES P99 / RocketMQ 积压

#### 结构化日志

```xml
<appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
        <customFields>{"service":"${spring.application.name}"}</customFields>
        <includeMdcKeyName>traceId</includeMdcKeyName>
        <includeMdcKeyName>userId</includeMdcKeyName>
    </encoder>
</appender>
```

---

### 维度七：配置管理与 Feature Flag

#### 当前状态：极为严峻

15 个业务服务全部 `spring.cloud.nacos.config.enabled: false`。

| 能力 | 使用次数 |
|------|:---:|
| `@RefreshScope` | **0** |
| `EnvironmentChangeEvent` | **0** |
| `NacosConfigManager` | **0** |
| `@NacosConfigListener` | **0** |

#### Nacos Config 接入方案

```
配置分层：
  my-xhs-common.yml          ← 共享（Redis/Feign/Sentinel）
  my-xhs-{service}.yml       ← 各服务独有
  my-xhs-degrade-switches.yml ← 动态降级开关
  my-xhs-feature-flags.yml   ← Feature Flag
```

#### Feature Flag 实现

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface FeatureFlag {
    String value();
    boolean defaultOn() default false;
}

@Aspect @Component
public class FeatureFlagAspect {
    @Around("@annotation(featureFlag)")
    public Object check(ProceedingJoinPoint pjp, FeatureFlag featureFlag) throws Throwable {
        if (flagManager.isEnabled(featureFlag.value()))
            return pjp.proceed();
        return null; // 开关关闭
    }
}
```

---

### 维度八：灰度发布

#### 当前状态

| 能力 | 状态 |
|------|:---:|
| TrafficColoringFilter（染色） | ✅ |
| GrayRouteFilter | ⚠️ 基础 |
| Nacos metadata.version | ❌ 未配置 |
| 金丝雀指标对比 | ❌ |
| 流量比例放大 | ❌ |
| 自动回滚 | ❌ |

#### 完整流程

```
Step 1: 部署金丝雀实例 → Nacos metadata.version=v2
Step 2: Gateway 染色 5% 流量 → userId.hashCode() % 100 < 5 → canary
Step 3: LoadBalancer → X-Gray-Tag=canary → 选 v2 实例
Step 4: 观察 5 分钟 → 对比 QPS/ErrorRate/P99
Step 5: 放大到 20% → 50% → 100% （每步观察 5 分钟）
Step 6: 全量后下线旧版本
```

---

### 维度九：数据库深度设计

| 能力 | 状态 |
|------|:---:|
| ShardingSphere 4库×4表 | ✅ |
| order_no_mapping 映射表 | ✅ |
| **读写分离** | ❌ |
| **慢查询治理** | ❌ |
| **数据归档** | ❌ |
| **索引审查** | ⚠️ 缺复合索引 |

#### 高频查询索引建议

```sql
-- 订单列表
CREATE INDEX idx_user_status_created ON t_order(user_id, status, created_at DESC);

-- 笔记热度排序
CREATE INDEX idx_status_like ON t_note(status, like_count DESC);
```

#### ProxySQL 读写分离

```ini
mysql_servers:
(
    { address="127.0.0.1", port=13306, hostgroup=10 },  # 写组
    { address="127.0.0.1", port=13307, hostgroup=10 },
)
mysql_query_rules:
(
    { match_pattern="^SELECT.*FOR UPDATE$", destination_hostgroup=10 },
    { match_pattern="^SELECT", destination_hostgroup=20 },  # 读组
    { match_pattern=".*", destination_hostgroup=10 }
)
```

#### 数据归档策略

| 表 | 热数据 | 温数据 | 冷数据 |
|------|------|------|------|
| t_order | 近 3 个月 | 3-6 个月 | 6 个月前 → 归档表 |
| t_user_behavior | 近 7 天 | 7-30 天 | 30 天前 → DELETE |
| t_local_message | 近 1 天 | — | 已处理 → DELETE |
| t_notification | 近 30 天 | — | 已读+超30天 → DELETE |

---

### 维度十：测试体系 + CI/CD

#### 测试

```
my-xhs/
├── my-xhs-common/src/test/java/
│   ├── CacheHelperTest.java (12 个测试方法) ✅
│   └── SegmentIdGeneratorTest.java (7 个测试方法) ✅
├── my-xhs-user/src/test/ → ❌
├── my-xhs-order/src/test/ → ❌
├── my-xhs-payment/src/test/ → ❌
...(其余 10 个服务全部无测试) ❌
└── Testcontainers 依赖: 声明了 version 1.19.8 但 0 处使用
```

**核心链路测试计划**：

| 优先级 | 模块 | 类 | 用例数 |
|:---:|------|------|:---:|
| P0 | Order | OrderService | 8 |
| P0 | Payment | PaymentService | 6 |
| P0 | Inventory | InventoryService | 6 |
| P0 | Gateway | GatewayAuthFilter | 5 |
| P0 | Coupon | CouponService (Lua) | 4 |
| P1 | Cart | CartService (Lua) | 4 |
| P1 | Order → Payment → Inventory | 全链路集成 | 3 |

#### CI/CD

```
当前：❌ 零 Jenkinsfile + ❌ 零 Dockerfile + ✅ docker-compose.yml（仅中间件）
```

**最小可行 Jenkins Pipeline**：Checkout → Compile & Test → Package → Build Docker Image → Deploy

**Dockerfile 模板**：

```dockerfile
FROM openjdk:17-jdk-slim
ARG JAR_FILE
ARG SKYWALKING_AGENT
COPY ${JAR_FILE} /app/app.jar
COPY ${SKYWALKING_AGENT} /app/skywalking-agent
ENV JAVA_OPTS="-javaagent:/app/skywalking-agent/skywalking-agent.jar ..."
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
```

---

## 第五章：中间件深度审计

### Lua 脚本全量审计（13 个）

| 脚本 | 模块 | 用途 | 原子性 | 问题 |
|------|------|------|:---:|------|
| `cart_add.lua` | cart | HEXISTS+HLEN+HINCRBY+SADD+ZADD | ✅ | 无 |
| `cart_remove.lua` | cart | HDEL+SREM+ZREM | ✅ | 无 |
| `cart_check_all.lua` | cart | HKEYS+DEL+SADD | ✅ | 无 |
| `claim_coupon.lua` | coupon | 库存检查+限领+DECR+INCR | ✅ | 无 |
| `return_coupon.lua` | coupon | INCR+DECR | ✅ | 无 |
| `follow.lua` | analytics | ZADD+ZADD | ✅ | 无 |
| `unfollow.lua` | analytics | ZREM+ZREM | ✅ | 无 |
| `like.lua` | analytics | SADD+SADD+计数 | ✅ | 无 |
| `unlike.lua` | analytics | SREM+SREM+计数 | ✅ | 无 |
| `note.lua` | home | ZRANGEBYSCORE TopN | ✅ | ⚠️ 关注 1000 人时需 N 次 |
| `hot_search_record.lua` | search | 屏蔽+限频+ZADD | ✅ | 无 |
| `decrement_safe.lua` | notification | GET+DECR 归零保护 | ✅ | 无 |
| `reset_unread.lua` | notification | HGET+DECRBY+HDEL | ✅ | 无 |

**结论**：所有 Lua 脚本**原子性正确，无竞态条件**。

### CounterBuffer 深度审计

双 `ConcurrentHashMap` 交换 + `ReentrantLock.tryLock()`。

| 维度 | 评价 |
|------|------|
| 线程安全 | ✅ `volatile` buffer + `AtomicLong` + `ReentrantLock` |
| 刷盘触发 | ✅ 容量 (100 条) + 定时 (5s) 双重保障 |
| 防死锁 | ✅ `tryLock()` + 排序防相交行锁 |
| 重试 | ✅ 3 次 + 指数退避 |
| 优雅停机 | ✅ `@PreDestroy` 阻塞 `lock()` |
| 监控 | 🟡 无 Prometheus 指标 |

**总体评价**：**达到 P8 标准**，只需加 Prometheus 指标。

### 热搜排行榜审计

衰减算法：`Score = Σ(count × e^(-λ×Δt))`, λ=0.1。

| 维度 | 评价 |
|------|------|
| 衰减算法 | ✅ 数学严谨 |
| 反作弊 | ✅ Lua 原子（屏蔽词+IP限频+用户限频） |
| 重算 | ✅ Redisson 锁 + Pipeline 60 桶 |
| 原子更新 | ✅ 临时 Key → RENAME |
| 人工干预 | ✅ 置顶/屏蔽 Set |
| 快照 | ✅ MySQL + 按日期查询 |

**总体评价**：设计**超越华仔**。

### Redis Key 命名规范审计

部分硬编码 Key 未使用 `RedisKeyConstants`：

| 硬编码位置 | 应使用常量 |
|------|------|
| `FeedPushConsumer.checkBigV()` | `USER_BIGV` |
| `CartReconcileJob` | `CART_ITEMS`, `CART_CHECKED` |
| `IndexRebuildJob` | 需新增 |
| `InventoryCacheEvictConsumer` | 需新增 |

### 全量 application.yml 配置审计

**端口分配**：无冲突 ✅

**缺失配置**：

| 配置项 | 缺失服务数 | 影响 |
|------|:---:|------|
| `server.shutdown: graceful` | 15 | kill 信号直接中断请求 |
| `management.endpoints.web.exposure.include` | 4 | search/counter/content/analytics 未暴露 actuator |
| `spring.lifecycle.timeout-per-shutdown-phase` | 15 | 优雅停机超时未设 |

**安全风险**：

| 风险 | 修复 |
|------|------|
| Actuator 无认证 | `management.endpoint.health.show-details: when-authorized` |
| CORS `*` | 生产改为具体域名白名单 |
| 压测标记 `10.0.0.0/8` 粗粒度 | 改为精确压测平台 IP 白名单 |

### Canal 配置

| 项目 | 状态 |
|------|:---:|
| docker-compose 部署 | ✅ Canal 1.1.7 |
| 3 个 instance 配置 | ✅ |
| MySQL Binlog 监听 | ✅ |
| RocketMQ Topic 映射 | ✅ |
| Consumer 消费 | ✅ ExternalGte 防乱序 |
| DELETE 不真删 | ✅ status=-1 |

**评价**：**设计精致**，是项目亮点。

### Chaos Engineering

7 个场景：Redis/MQ/MySQL 不可用、Redis 延迟、CPU 满载、磁盘 IO、优雅停机验证。**超越华仔**。

### Gateway 过滤链

| Order | 过滤器 | 降级策略 |
|:---:|------|------|
| 100 | RequestLogFilter | — |
| 1000 | GatewayAuthFilter | Fail-Closed |
| 1200 | TrafficColoringFilter | — |
| 1500 | HmacSignatureFilter | Fail-Open |
| 2500 | RateLimitFilter | Sentinel 内置 |
| 3000 | GrayRouteFilter | 未匹配时全部实例 |
| 3100 | ApiVersionFilter | 默认 v1 |

**评价**：**项目最大亮点**。HMAC Fail-Open + Auth Fail-Closed 体现了成熟的安全意识。

---

## 第六章：修复优先级路线图

### 本周必做（P0 — ~8 工时）

| # | 任务 | 文件 | 工时 |
|---|------|------|:---:|
| 1 | **Feed 管线连通**：NoteService 发 `FEED_TOPIC` | `NoteService.java` L94 | 1h |
| 2 | **Comment 通知连通**：CommentService 发通知 | `CommentService.java` L140 | 1h |
| 3 | **home 加 Sentinel** | `pom.xml` + `yml` | 1h |
| 4 | **cart 加 Sentinel** | `pom.xml` + `yml` | 0.5h |
| 5 | **ORDER_COMPENSATION_TOPIC 消费者** | 新建 | 2h |
| 6 | **SpuUpdateRequest 加 @Valid** | `SpuUpdateRequest.java` | 0.5h |
| 7 | **优雅停机配置** | 15 个 yml | 1h |

### 两周内（P1 — ~24 工时）

| # | 任务 | 工时 |
|---|------|:---:|
| 8 | **Nacos Config 全服务启用** | 4h |
| 9 | **SkyWalking Agent 全量挂载** | 2h |
| 10 | **死信队列消费者** + DLQ 配置 | 3h |
| 11 | **Redis Key 硬编码迁移** | 2h |
| 12 | **CouponController 返回 VO** | 1h |
| 13 | **ImController 返回 VO** | 2h |
| 14 | **CacheHelper 延迟双删修复** | 1h |
| 15 | **Grafana Dashboard 导入** | 4h |
| 16 | **核心链路单元测试** | 5h |

### 月度（P2 — ~32 工时）

| # | 任务 | 工时 |
|---|------|:---:|
| 17 | Sentinel Dashboard + 熔断规则 | 4h |
| 18 | 多实例部署 + 灰度标签 | 4h |
| 19 | ProxySQL 读写分离 | 4h |
| 20 | 慢查询治理 | 6h |
| 21 | 缓存预热 + BigKey 检测 | 3h |
| 22 | JD-hotkey 集成 | 4h |
| 23 | Jenkins CI/CD + Dockerfile | 7h |

---

## 第七章：最终评分明细

| 维度 | v1 | v2 | **v3** | 降幅原因 |
|------|:---:|:---:|:---:|------|
| 架构设计 | 85 | 80 | **80** | — |
| 代码质量 | 78 | 60 | **55** | 18 项具体问题 + Feed/Comment 管线断 |
| 安全性 | 90 | 90 | **90** | — |
| 分布式能力 | 82 | 70 | **60** | 补偿断裂 + Feed 管线断裂 |
| 业务完整性 | 75 | 75 | **55** | Feed 流不可用 |
| 可观测性 | 72 | 40 | **35** | — |
| 测试覆盖 | 10 | 10 | **5** | Testcontainers 声明但 0 使用 |
| 工程化成熟度 | 35 | 10 | **5** | 配置中心全服务未启用 |
| 华仔覆盖率 | 76 | 76 | **76** | 功能层面不变 |
| **综合** | **67** | **47** | **38** | 管线断裂是最致命扣分项 |

```
架构设计：   ████████░░ 80%
代码质量：   █████░░░░░ 55%
安全性：     █████████░ 90%
分布式事务： ██████░░░░ 60%
消息队列：   ██████░░░░ 60%
缓存设计：   █████░░░░░ 55%
熔断降级：   ██░░░░░░░░ 20%
负载均衡：   █░░░░░░░░░ 10%
可观测性：   ███░░░░░░░ 35%
配置管理：   ░░░░░░░░░░  5%
灰度发布：   ███░░░░░░░ 30%
测试体系：   █░░░░░░░░░  5%
CI/CD：      ░░░░░░░░░░  0%
```

---

## 结论

my-xhs 项目在以下维度达到了真正的 P8 水平：

- ✅ **安全性**：JWT 双 Token + HMAC-SHA256 + BCrypt + DFA，三个层级纵深防御
- ✅ **Lua 原子操作**：13 个脚本全部正确，cart/coupon/analytics/notification 全覆盖
- ✅ **CounterBuffer 设计**：双缓冲交换 + 排序防死锁 + 重试 + 优雅停机
- ✅ **热搜算法**：指数衰减 + 反作弊 + 原子更新，数学严谨
- ✅ **Gateway 过滤链**：7 层过滤 + 灰度/版本/染色/签名/鉴权，Fail-Open/Fail-Closed 差异化降级
- ✅ **Canal 配置**：ExternalGte 防乱序 + DELETE 不真删 + 计数字段跳过

但存在三个结构性问题：

1. **管线断裂**：Feed 和 Comment 两个关键管线 Producer 端缺失——写了完整 Consumer 但忘对接 Producer
2. **配置断层**：Nacos Config 全服务未启用、SkyWalking Agent 全未挂载、Sentinel 仅 3 服务启用——基础设施部署了但没人接
3. **防御失效**：FallbackFactory 大面积静默失效（pom 缺依赖）、优雅停机未配置、死信无处理——看起来有但实际无效

**修复策略**：8 工时修复 7 个 P0 问题 → 管线连通 + 防御生效；2 周补齐 P1 工程化基础。P0 修复后综合得分可从 38 回升到 **65+**。
