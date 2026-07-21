# my-xhs P8 级别深化评审报告（补充篇）

> 评审日期：2026-06-01 | 基于 `P8-DEEP-REVIEW-PROMPT.md` 逐维度审查
> 先决文档：`COMPREHENSIVE-REVIEW-REPORT.md`（基础评审）、`P8-LEVEL-ROADMAP.md`（限流深度方案）

---

## 总览：P8 差距全景

| 维度 | 当前水平 | P8 差距 | 影响级别 |
|------|:---:|------|:---:|
| 限流（已评审） | ★★☆ | 四层纵深防御缺失 | 🔴 P0 |
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

## 维度一：熔断与降级体系

### 1.1 当前状态

**致命发现：FallbackFactory 大面积静默失效！**

| 服务 | FeignClient 数量 | 有 sentinel 依赖？ | feign.sentinel.enabled? | FallbackFactory 实际生效？ |
|------|:---:|:---:|:---:|:---:|
| **order** | 3 | ✅ | ✅ true | ✅ 生效 |
| **payment** | 1 | ✅ | ✅ true | ✅ 生效 |
| **home** | 11 | ❌ **没有** | ❌ **没有** | ❌ **静默失效** |
| **cart** | 1 | ❌ **没有** | ❌ **没有** | ❌ **静默失效** |

**危害分析**：

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
        → 前端展示"服务器错误" ❌ 用户看到错误页面而非优雅降级
```

**结论**：home 服务 11 个 FallbackFactory 的白写了——它们在运行时永远不会被触发。Cart 同理。

### 1.2 P8 级别三级熔断降级体系

```
┌─────────────────────────────────────────────────────────────────┐
│                    P8 三级熔断降级体系                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│ L1: 服务级熔断 (Feign + Sentinel)                                │
│   Rule：下游错误率 > 50% or 慢调用 > 1s 占比 80%                 │
│   Action：熔断 OPEN → 5s 后半开探测 3 次 → 正常则关闭            │
│   Fallback：返回降级数据（非 500）                                │
│                                                                 │
│ L2: 方法级熔断 (MyBatis Interceptor / Redis AOP)                  │
│   Rule：同一条 SQL 连续 5 次 > 200ms   or   Redis 连接池耗尽      │
│   Action：短路返回（读→null, 写→异步队列）                        │
│                                                                 │
│ L3: 业务级降级 (Nacos Config 开关)                                │
│   Scenario：大促期间关闭推荐/Feed流，保下单/支付                   │
│   Mechanism：动态配置推送 → @RefreshScope 监听 → 实时切换         │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 1.3 必须做的修复（第一优先级）

| # | 修复项 | 文件 | 方案 |
|---|--------|------|------|
| 1 | **home 添加 sentinel 依赖** | `my-xhs-home/pom.xml` | 添加 `spring-cloud-starter-alibaba-sentinel` |
| 2 | **home 启用 feign.sentinel.enabled** | `my-xhs-home/src/main/resources/application.yml` | 添加 `feign.sentinel.enabled: true` |
| 3 | **cart 添加 sentinel 依赖** | `my-xhs-cart/pom.xml` | 同上 |
| 4 | **cart 启用 feign.sentinel.enabled** | `my-xhs-cart/src/main/resources/application.yml` | 同上 |

**修复后要做的**：
- Sentinel Dashboard 为每个 FeignClient 配置熔断规则：
  ```json
  {
    "resource": "com.myxhs.home.feign.ProductFeignClient#getDetail(Long)",
    "grade": 0,           // 慢调用比例
    "count": 1000,        // 最大 RT 1s
    "slowRatioThreshold": 0.5,  // 慢调用比例 50%
    "timeWindow": 10      // 熔断时长 10s
  }
  ```

### 1.4 大促降级预案表

| 服务 | 正常模式 | 轻度降级（QPS 120%） | 重度降级（QPS 200%+） | 直接关闭 |
|------|---------|-------------------|---------------------|:---:|
| **order/payment** | 正常 | 正常（不可降） | 正常（不可降） | ❌ 绝不 |
| **inventory** | 精确库存 | 仅展示有无货 | 关闭库存查询 | ❌ 绝不 |
| **product** | 完整详情 | 无推荐商品区 | 仅标题+价格+库存 | ❌ 绝不 |
| **search** | 全文检索 | 仅热门搜索词 | 关闭搜索建议 | ✅ 可关闭 |
| **feed** | 个性化推荐 | 热门兜底 | 仅关注流 | ✅ 可关闭 |
| **notification** | 实时推送 | 批量聚合（30min） | 关闭推送 | ✅ 可关闭 |
| **analytics** | 实时统计 | 延迟统计 | 关闭长尾统计 | ✅ 可关闭 |

### 1.5 用 Nacos Config 实现降级开关

```java
/**
 * 动态降级开关管理器
 * Nacos Config Key: my-xhs-degrade-switches
 */
@Component
@RefreshScope  // <-- 关键：Nacos 配置变更后自动刷新此 Bean
@ConfigurationProperties(prefix = "degrade")
public class DegradeSwitchManager {
    
    /** 全局限流开关 */
    private boolean globalRateLimit = true;
    
    /** 推荐服务降级开关 */
    private boolean recommendDegrade = false;
    
    /** 搜索建议降级开关 */
    private boolean searchSuggestDegrade = false;
    
    /** Feed 流降级开关 */
    private boolean feedDegrade = false;
    
    /** 通知实时推送降级开关 */
    private boolean notificationDegrade = false;
    
    /** 降级到热门兜底 */
    private boolean hotFallback = false;
    
    // getters/setters...
}
```

```yaml
# Nacos Config: my-xhs-degrade-switches.yml (DEFAULT_GROUP)
degrade:
  global-rate-limit: true
  recommend-degrade: false
  search-suggest-degrade: false
  feed-degrade: false
  notification-degrade: false
  hot-fallback: false
```

**Controller 中使用**：

```java
@RestController
public class RecommendController {
    
    private final DegradeSwitchManager degradeSwitch;
    private final RecommendService recommendService;
    private final HotFallbackService hotFallbackService;
    
    @GetMapping("/api/recommend/feed")
    public R<List<FeedVO>> getFeed(Long userId) {
        if (degradeSwitch.isRecommendDegrade()) {
            // 降级：返回热门兜底
            return R.ok(hotFallbackService.getHotFeed());
        }
        return R.ok(recommendService.getFeed(userId));
    }
}
```

**大促降级操作**：运维人员（或自动化脚本）修改 Nacos 配置 → `@RefreshScope` 在收到 `RefreshScopeRefreshedEvent` 后自动重建 Bean → Controller 下次请求拿到新的开关值 → **全程无需重启，秒级生效**。

---

## 维度二：负载均衡深度设计

### 2.1 当前状态

**彻底的空缺**。全项目：
- ❌ 零自定义 `ReactorServiceInstanceLoadBalancer` 实现
- ❌ 零 `ServiceInstanceListSupplier` 自定义
- ❌ 零 `@LoadBalancerClient` / `@LoadBalancerClients` 注解
- ❌ 所有服务的 Nacos metadata 均**未配置** `version` 字段
- ⚠️ NacosRule 依赖 Nacos 默认策略，所有服务平等轮询

**实际影响**：
- IM WebSocket 无法做一致性 Hash 路由（同一用户可能连接到不同实例）
- 灰度发布无法按实例 metadata 版本号路由流量
- 压测流量无法隔离到独立实例集群

### 2.2 P8 级别多策略负载均衡

| 策略 | 适用 my-xhs 的服务 | 实现类 |
|------|---|------|
| **加权响应时间** (默认) | product, search, content | 实时采集 P99 RT，权重 = 1/RT |
| **一致性 Hash** | IM | TreeMap 虚拟节点 + userId hash |
| **同机房优先** | 所有服务（多 AZ 部署后） | NacosRule + zone metadata |
| **最小连接数** | notification (SSE) | 选择活跃连接最少的实例 |
| **灰度标签路由** | 所有服务（金丝雀发布时） | X-Gray-Tag → metadata.version 匹配 |

### 2.3 实现方案

**一致性 Hash 路由（IM 服务）**：

```java
/**
 * IM WebSocket 负载均衡策略
 * 
 * 需求：同一 userId 的消息始终推送到同一实例
 * 原因：WebSocket 连接绑定在特定实例上
 */
public class IMConsistentHashLoadBalancer 
        implements ReactorServiceInstanceLoadBalancer {
    
    private final ObjectProvider<ServiceInstanceListSupplier> supplierProvider;
    private final TreeMap<Integer, ServiceInstance> ring = new TreeMap<>();
    private static final int VIRTUAL_NODES = 128;  // 虚拟节点数
    
    @Override
    public Mono<Response<ServiceInstance>> choose(Request request) {
        // 从 request 上下文提取 userId
        Long userId = TraceContextHolder.getUserId();
        if (userId == null) {
            // 无 userId 则降级为轮询
            return roundRobin(request);
        }
        
        return supplierProvider.getIfAvailable().get(request)
            .next()
            .map(instances -> {
                // 构建一致性 Hash 环
                rebuildRing(instances);
                
                // 定位虚拟节点
                int hash = consistentHash(userId);
                Map.Entry<Integer, ServiceInstance> entry = 
                    ring.ceilingEntry(hash);
                if (entry == null) {
                    entry = ring.firstEntry();  // 环回到起点
                }
                
                return new DefaultResponse(entry.getValue());
            });
    }
    
    private int consistentHash(long key) {
        // 简单的 hash 函数（生产可用 MurmurHash）
        return (int) ((key * 2654435761L) & 0x7FFFFFFF) % (VIRTUAL_NODES * 100);
    }
}
```

**灰度标签路由**：

```java
/**
 * 按 X-Gray-Tag header 路由到金丝雀实例
 * 
 * 匹配规则：
 *   X-Gray-Tag=canary → 路由到 metadata.version=v2 的实例
 *   X-Gray-Tag=stable  → 路由到 metadata.version=v1 或未标注的实例
 */
@Component
public class GrayLoadBalancerConfig {
    
    @Bean
    @LoadBalancerClients(defaultConfiguration = GrayLoadBalancer.class)
    public LoadBalancerClientFactory loadBalancerClientFactory() {
        return new LoadBalancerClientFactory();
    }
}

public class GrayLoadBalancer implements ReactorServiceInstanceLoadBalancer {
    
    @Override
    public Mono<Response<ServiceInstance>> choose(Request request) {
        // 从 Gateway 注入的 header 获取灰度标签
        String grayTag = request.getContext()
            .getClientRequest()
            .getHeaders()
            .getFirst("X-Gray-Tag");
        
        return supplier.get(request).next().map(instances -> {
            List<ServiceInstance> candidates = instances.stream()
                .filter(inst -> matchGray(inst, grayTag))
                .toList();
            
            if (candidates.isEmpty()) {
                candidates = instances;  // 降级：无匹配则全量
            }
            
            // 在匹配的实例中轮询
            ServiceInstance selected = roundRobin(candidates);
            return new DefaultResponse(selected);
        });
    }
    
    private boolean matchGray(ServiceInstance inst, String grayTag) {
        String version = inst.getMetadata().get("version");
        if ("canary".equals(grayTag)) {
            return "v2".equals(version);  // 灰度流量到 v2
        }
        return !"v2".equals(version);    // 稳定流量到 v1
    }
}
```

---

## 维度三：多级缓存架构与热点治理

### 3.1 当前状态

| 能力 | 状态 | 详情 |
|------|:---:|------|
| CacheHelper（Cache Aside+穿透+击穿+雪崩） | ✅ 成熟 | common 模块，300+ 行，全项目共用 |
| 延迟双删 | ⚠️ 有缺口 | 第二次删除失败后不发 MQ，靠 CacheEvictConsumer（Canal→MQ）间接兜底 |
| TTL 随机偏移 | ✅ 合理 | `+timeoutSeconds/6` 的随机因子 |
| 空值缓存（防穿透） | ✅ 有 | NULL_PLACEHOLDER，2min TTL |
| Caffeine L1 本地缓存 | ⚠️ 仅 product | user/content/counter 等高并发服务缺失 |
| 缓存预热 | ❌ 无 | 冷启动大量 DB 穿透 |
| BigKey 监控/拆分 | ❌ 无 | 无监控、无自动拆分 |
| JD-hotkey 热点探测 | ❌ 未集成 | pom 声明了版本号但无 dependency |
| @Cacheable 注解 | ❌ 零使用 | 全部手写缓存操作 |

### 3.2 关键缺口详细分析

#### 3.2.1 延迟双删的终极兜底缺失

```
正常流程：
  更新 DB → deleteAfterUpdate(立即删, 重试3次) → delayDoubleDelete(立即删 + 500ms后删)
                                              │
                                              └─ 第二次删成功 ✅
异常流程：
  更新 DB → deleteAfterUpdate(立即删, 重试3次) → delayDoubleDelete(立即删 + 500ms后删)
                                              │
                                              └─ 第二次删失败 ❌ 
                                                    │
                                              CacheEvictConsumer (Canal→MQ)
                                              └─ 间隔可能 1~3 秒，窗口期有脏读
```

**代码证据**：`CacheHelper.java` 的 `delayDoubleDelete()` 方法中，`scheduledExecutor.schedule()` 执行第二次删除，失败后只记录日志，**没有**显式发送 MQ。

**P8 修复**：在延迟双删的 catch 块中增加 `rocketMQTemplate.syncSend("CACHE_EVICT_TOPIC", key)`。

#### 3.2.2 缓存预热

**需要预热的 Key 清单**：

| 数据类型 | 预热方式 | 触发时机 |
|---------|---------|---------|
| 商品分类树 | 全量加载到 Caffeine | 应用启动后 10s |
| 热门笔记 Top 100 | 从 Redis ZSet 取 | 应用启动后 5s |
| 热搜词 Top 50 | 从 Redis ZSet 取 | 应用启动后 5s |
| 优惠券模板 | 全量加载到 Redis | 应用启动后 15s |

```java
@Component
public class CacheWarmUpRunner implements ApplicationRunner {
    
    @Override
    public void run(ApplicationArguments args) {
        // 异步预热，不阻塞启动
        CompletableFuture.runAsync(() -> {
            warmUpHotSearch();
            warmUpHotNotes();
            warmUpCategoryTree();
            warmUpCouponTemplates();
        });
    }
}
```

#### 3.2.3 BigKey 检测方案

```bash
# 通过 redis-cli 定期检查（可放入 Jenkins 定时任务）
redis-cli --bigkeys -p 16379

# 或通过 RDB 分析工具
rdb -c memory /data/redis/dump.rdb --bytes 128 -f memory.csv
```

**BigKey 预警阈值**：
- String: > 10KB → 警告
- Hash/Set/ZSet: > 5000 成员 → 警告，> 50000 → 拆分
- 重点关注：`myxhs:counter:like:*`（点赞 Set，可能很大）、`myxhs:follow:list:*`（粉丝列表）

#### 3.2.4 缓存监控指标体系

| 指标 | 来源 | Prometheus 打点位置 |
|------|------|-----|
| 缓存命中率 | Caffeine stats / 自定义计数器 | CacheHelper.get() |
| 缓存穿透次数 | 空值缓存命中计数 | CacheHelper.get() |
| 缓存击穿等待时间 | 分布式锁等待时间 | CacheHelper.getWithCacheAsideLock() |
| 热点 Key 列表 | JD-hotkey Dashboard | Worker 上报 |
| 缓存容量 | used_memory_rss | Redis Exporter |

---

## 维度四：分布式事务补偿体系

### 4.1 当前状态

| 能力 | 状态 | 说明 |
|------|:---:|------|
| RocketMQ 事务消息（下单） | ✅ 成熟 | 半消息+executeLocalTransaction+checkLocalTransaction |
| 本地消息表 retry (LocalMessageRetryJob) | ✅ | 定时扫描补发 |
| 支付补偿 (PaymentNotifyCompensateJob) | ✅ | 每 2 分钟，最多 10 次 |
| 退款补偿 (RefundNotifyCompensateJob) | ✅ | 每 3 分钟 |
| **ORDER_COMPENSATION_TOPIC** | 🔴 **无消费者** | 关单失败后发补偿消息，没有消费者处理 |
| **死信队列 (DLQ)** | 🔴 **零配置** | 消费 16 次失败后的消息进入死信，无人处理 |
| TCC 模式 | ❌ | 未实现 |
| Saga 模式 | ❌ | 未实现 |

### 4.2 必须修复的致命问题

#### 4.2.1 ORDER_COMPENSATION_TOPIC 消费者

```java
@Component
@RocketMQMessageListener(
    topic = "ORDER_COMPENSATION_TOPIC",
    consumerGroup = "order-compensation-group",
    consumeThreadMax = 2,
    maxReconsumeTimes = 3
)
public class OrderCompensationConsumer implements RocketMQListener<MessageExt> {
    
    private final OrderService orderService;
    private final RedissonClient redissonClient;
    
    @Override
    public void onMessage(MessageExt msg) {
        CompensationMessage cm = JSON.parseObject(
            new String(msg.getBody()), CompensationMessage.class);
        
        // 幂等保护
        String idempotentKey = "compensation:" + cm.getOrderId();
        RBucket<String> bucket = redissonClient.getBucket(idempotentKey);
        if (!bucket.setIfAbsent("1", Duration.ofMinutes(10))) {
            log.info("补偿消息已处理: orderId={}", cm.getOrderId());
            return;
        }
        
        try {
            // 重试关单
            orderService.closeTimeoutOrder(cm.getOrderId());
            log.info("补偿关单成功: orderId={}", cm.getOrderId());
        } catch (Exception e) {
            log.error("补偿关单失败: orderId={}", cm.getOrderId(), e);
            
            // 超过 3 次失败 → 写入人工处理表
            if (msg.getReconsumeTimes() >= 3) {
                saveToManualProcess(cm);
            }
            throw e;  // 让 RocketMQ 重试
        }
    }
}
```

#### 4.2.2 RocketMQ 死信队列消费者

```java
/**
 * 死信队列消费者
 * 消息路径：原始 Topic → 16 次消费失败 → %DLQ%消费者组名
 */
@Component
@RocketMQMessageListener(
    topic = "%DLQ%order-compensation-group",  // DLQ 前缀
    consumerGroup = "dlq-consumer-group",
    consumeThreadMax = 1  // 单线程，避免并发写
)
public class DeadLetterQueueConsumer implements RocketMQListener<MessageExt> {
    
    @Override
    public void onMessage(MessageExt msg) {
        log.error("死信消息: topic={}, msgId={}, keys={}, body={}",
            msg.getTopic(), msg.getMsgId(), msg.getKeys(),
            new String(msg.getBody()));
        
        // 1. 入库（人工处理表）
        DeadLetterRecord record = new DeadLetterRecord();
        record.setOriginalTopic(msg.getTopic());
        record.setMsgId(msg.getMsgId());
        record.setBody(new String(msg.getBody()));
        record.setReconsumeTimes(msg.getReconsumeTimes());
        record.setCreatedAt(LocalDateTime.now());
        deadLetterRepository.save(record);
        
        // 2. 告警（Prometheus PushGateway or 企业微信通知）
        alertService.sendAlert("死信消息告警", 
            String.format("topic=%s, msgId=%s", msg.getTopic(), msg.getMsgId()));
    }
}
```

#### 4.2.3 退款流程 Saga 模式设计

```
退款 Saga 正向流程：
  Step1: freezeInventory(orderId, skus) → freezePayment(orderNo, amount) → releaseCoupon(orderId)
         └── 成功 → Step2                           └── 成功 → Step3
         
Saga 补偿流程（任一 Step 失败时反向补偿）：
  Step3 失败 → compensateStep2(orderNo) → compensateStep1(orderId, skus)
  Step2 失败 → compensateStep1(orderId, skus)
  Step1 失败 → 无补偿（事务尚未发生）

Saga 协调器职责：
  1. 记录每个 Step 的状态（Redis Hash: saga:refund:{orderId}）
  2. Step 成功 → 标记该 step 完成 → 执行下一步
  3. Step 失败 → 从失败的 step 开始反向补偿
  4. 补偿也失败 → 标记为"需人工处理"
  5. 定时扫描超时的 Saga → 触发重试
```

---

## 维度五：消息队列高级特性

### 5.1 当前状态

21 个 RocketMQ Consumer，但全是 `CONCURRENTLY` 模式，无 `ORDERLY`。

| 特性 | 状态 | 关键发现 |
|------|:---:|------|
| 普通消息 | ✅ 使用 | 21 个消费者 |
| 事务消息 | ✅ 订单创建 | OrderTransactionListener |
| 延时消息 | ✅ 关单 | delayLevel=16 (30min) |
| **顺序消息** | ❌ | 评论排序用 MySQL ORDER BY，大流量下性能差 |
| **批量消息** | ❌ | 计数服务逐条写 Redis，无攒批 |
| 消息过滤 (Tag) | ⚠️ 部分 | SOCIAL_TOPIC 用 Tag(如 `LIKE`、`UNLIKE`)，但无 SQL92 过滤 |
| **死信处理** | ❌ | DLQ 进入后无人处理 |
| **消息轨迹** | ❌ | 未开启 `msgTraceDispatcher` |
| Exactly Once | ❌ | 靠业务幂等兜底 |

### 5.2 顺序消息实现（评论排序）

```java
/**
 * 评论消息生产者 —— 按 noteId 路由到同一队列保证顺序
 */
@Service
public class CommentMessageProducer {
    
    private final RocketMQTemplate rocketMQTemplate;
    
    public void sendCommentEvent(Comment comment) {
        // 关键：按 noteId 选择队列 → 同一笔记的评论消息进入同一队列
        rocketMQTemplate.syncSendOrderly(
            "CONTENT_TOPIC:COMMENT",
            MessageBuilder.withPayload(comment).build(),
            comment.getNoteId().toString(),  // hashKey → 按 noteId 路由
            3000  // 超时 3s
        );
    }
}

/**
 * 评论消息消费者 —— 单线程顺序消费（保证同一队列内消息的顺序性）
 */
@Component
@RocketMQMessageListener(
    topic = "CONTENT_TOPIC",
    selectorExpression = "COMMENT",
    consumerGroup = "comment-consumer-group",
    consumeMode = ConsumeMode.ORDERLY,  // <-- 顺序消费模式
    consumeThreadMax = 1,               // <-- 单线程
    maxReconsumeTimes = 3
)
public class CommentMessageConsumer implements RocketMQListener<Comment> {
    
    @Override
    public void onMessage(Comment comment) {
        // 按顺序投递到 Redis ZSet
        // score = 时间戳，保证 Redis 中的排序也是正确的
        redisTemplate.opsForZSet().add(
            "myxhs:comment:zset:" + comment.getNoteId(),
            JSON.toJSONString(comment),
            comment.getCreatedAt().toEpochSecond(ZoneOffset.UTC)
        );
    }
}
```

**与当前方案对比**：

| 方案 | 当前 (MySQL ORDER BY) | 建议 (顺序消息) |
|------|-----|------|
| 排序方式 | `SELECT ... ORDER BY id DESC` | Redis ZSet 按时间戳排序 |
| 扩展性 | 单库单表有瓶颈 | Redis ZSet 支持千万级 |
| 一致性 | 天然强一致 | 有短暂延迟，最终一致 |
| 适用场景 | 笔记评论量 < 1000 | 热门笔记评论量 > 10000 |

### 5.3 计数服务批量攒批

```java
/**
 * 计数批量生产者
 * 攒批策略：累计 100 条事件 or 100ms 超时 → 批量发送
 */
@Component
public class CounterBatchProducer {
    
    private final BlockingQueue<CounterEvent> buffer = new LinkedBlockingQueue<>(1000);
    private final ScheduledExecutorService scheduler = 
        Executors.newSingleThreadScheduledExecutor();
    
    @PostConstruct
    public void init() {
        // 每 100ms 检查并刷出
        scheduler.scheduleAtFixedRate(this::flush, 0, 100, TimeUnit.MILLISECONDS);
    }
    
    public void add(CounterEvent event) {
        buffer.offer(event);
        // 缓冲区满了也触发刷出
        if (buffer.size() >= 100) {
            flush();
        }
    }
    
    @PreDestroy
    public void shutdown() {
        flush();  // 优雅停机时最后一次刷出
    }
    
    private void flush() {
        List<CounterEvent> batch = new ArrayList<>();
        buffer.drainTo(batch, 100);  // 最多取 100 条
        if (!batch.isEmpty()) {
            rocketMQTemplate.syncSend("COUNTER_TOPIC", batch);
        }
    }
}
```

---

## 维度六：可观测性三大支柱

### 6.1 当前状态

| 能力 | 状态 | 详情 |
|------|:---:|------|
| Prometheus + 9 告警规则 | ✅ | 完整 |
| Micrometer 指标 | ✅ | `micrometer-registry-prometheus` + `spring-boot-starter-actuator` |
| **SkyWalking Agent** | ❌ 未挂载 | OAP + UI 在 docker-compose 中运行，但微服务 `-javaagent` 参数未配置 |
| **Grafana Dashboard** | ❌ 零 JSON | 无预置仪表盘 JSON 文件 |
| **结构化日志 (JSON)** | ❌ | 所有日志为纯文本格式 |
| **ELK/EFK 日志平台** | ❌ | 未部署，日志分散在容器中 |
| TraceId 透传 (Feign + MQ) | ✅ | `TraceContextHolder` + `MqTraceHelper` + `TraceContext` |
| 业务指标 | ❌ | 计算下单成功率需手动从日志计算 |

### 6.2 P8 级别方案

#### 6.2.1 Grafana Dashboard 设计

**Dashboard 1：JVM 大盘**（直接导入 Spring Boot 2.x 官方 Dashboard，ID：12900 或 10280）

**Dashboard 2：业务大盘**

| Panel | 类型 | PromQL | 说明 |
|-------|------|--------|------|
| 下单成功率 | Stat | `rate(order_create_success[5m]) / rate(order_create_total[5m])` | 核心指标 |
| 支付转化率 | Stat | `rate(pay_success[5m]) / rate(order_create_success[5m])` | 业务转化 |
| 实时 GMV | Stat | `sum(rate(pay_amount_sum[5m])) * 60` | 每分钟预估 |
| QPS 热力图 | Heatmap | `sum(rate(http_server_requests_seconds_count[1m])) by (uri, service)` | 每个 API 的 QPS |
| P99 响应时间 | TimeSeries | `histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[5m]))` | 慢请求 |
| 限流触发次数 | TimeSeries | `rate(sentinel_block_total[1m]) by (resource)` | 限流监控 |
| 熔断打开时长 | TimeSeries | `sentinel_circuit_breaker_open_seconds` | 熔断监控 |
| 错误率 Top10 | Table | `sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) by (uri) / sum(rate(http_server_requests_seconds_count[5m])) by (uri)` | 错误定位 |

**Dashboard 3：中间件大盘**

| Panel | 数据来源 | 关键指标 |
|-------|---------|---------|
| HikariCP 连接池 | `/actuator/prometheus` | 活跃/空闲/等待/最大连接数 |
| Redis 连接池 | Lettuce metrics | 活跃连接数、等待时间、超时次数 |
| ES 查询 P99 | ES Exporter | 查询延迟、索引速率、集群状态 |
| RocketMQ 消费积压 | RocketMQ Exporter | 各 Topic 积压量、消费 TPS |

**Dashboard 4：限流/熔断大盘**

| Panel | 数据来源 |
|-------|---------|
| 各层限流触发次数 | 网关 Sentinel metrics + Redis Lua 脚本 metrics + MyBatis Interceptor 自埋 |
| 熔断打开/关闭事件 | Sentinel degrade metrics |
| 降级比例趋势 | 降级开关日志统计 |
| 当前生效的降级开关 | Nacos Config 配置快照 |

#### 6.2.2 结构化日志

```xml
<!-- logback-spring.xml 追加 JSON Encoder -->
<appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
        <customFields>{"service":"${spring.application.name}","env":"${spring.profiles.active}"}</customFields>
        <includeMdcKeyName>traceId</includeMdcKeyName>
        <includeMdcKeyName>userId</includeMdcKeyName>
    </encoder>
</appender>
```

**输出效果**：
```json
{
  "@timestamp": "2026-06-01T09:50:00.123Z",
  "service": "my-xhs-order",
  "env": "prod",
  "level": "INFO",
  "message": "订单创建成功",
  "traceId": "a1b2c3d4e5f6g7h8",
  "userId": "12345",
  "orderId": "ORD20260601001",
  "amount": 99.00
}
```

#### 6.2.3 SkyWalking Agent 验证方案

挂载 Agent 后，验证四步：

1. **Gateway 入口**：查看是否有 Trace，检查 TraceId 是否正确
2. **Feign 调用链路**：Gateway → Home → Product → Inventory 的调用链是否完整
3. **MQ 跨进程**：订单发消息 → 库存消费消息，两个 Trace 是否有关联
4. **DB 慢查询**：SkyWalking 的 Database 面板能否看到 SQL 执行耗时

---

## 维度七：配置管理与 Feature Flag

### 7.1 当前状态（极为严峻）

| 服务 | Nacos Config 状态 | 说明 |
|------|:---:|------|
| gateway | ⚠️ 仅 Sentinel 数据源用 Nacos | `spring.cloud.nacos.config.enabled` 未启用 |
| user | ❌ | `spring.cloud.nacos.config.enabled: false` |
| content | ❌ | 同上 |
| analytics | ❌ | 同上 |
| counter | ❌ | 同上 |
| product | ❌ | 同上 |
| order | ❌ | 同上 |
| inventory | ❌ | 同上 |
| coupon | ❌ | 同上 |
| payment | ❌ | 同上 |
| search | ❌ | 同上 |
| notification | ❌ | 同上 |
| home | ❌ | 同上 |
| im | ❌ | 同上 |
| cart | ❌ | 同上 |

**这意味什么**：
- 改任何一个配置（例如调整 Redis 连接池大小）→ 需要修改本地 yml → 重新编译打包 → 重启服务
- 大促时想关闭推荐功能 → 没法通过配置秒级切换 → 必须改代码重启
- 配置没有版本管理 → 不知道谁在什么时候改了什么

**全项目搜寻结果**：
- `@RefreshScope` 使用：**0 处**（仅在文档 `P8-DEEP-REVIEW-PROMPT.md` 的建议中存在）
- `EnvironmentChangeEvent` 监听：**0 处**
- `NacosConfigManager` 使用：**0 处**
- `@NacosConfigListener`：**0 处**

### 7.2 P8 级别配置管理方案

**配置分层（Nacos 中的 data-id 规划）**：

```
my-xhs-common.yml          (DEFAULT_GROUP)  ← 所有服务共享（Redis/Feign/Sentinel/加密）
my-xhs-{service}.yml       (DEFAULT_GROUP)  ← 各服务独有配置
my-xhs-degrade-switches.yml  (DEFAULT_GROUP) ← 动态降级开关
my-xhs-feature-flags.yml   (DEFAULT_GROUP)  ← Feature Flag
```

**共享配置示例**（`my-xhs-common.yml`）：
```yaml
spring:
  data:
    redis:
      host: 21.91.124.110
      port: 16379
      lettuce:
        pool:
          max-active: 32
          max-idle: 16
          min-idle: 8
  cloud:
    sentinel:
      transport:
        dashboard: 21.91.124.110:18082

feign:
  sentinel:
    enabled: true
  client:
    config:
      default:
        connect-timeout: 3000
        read-timeout: 5000
        logger-level: BASIC
```

**各服务 bootstrap.yml**（接入 Nacos Config）：
```yaml
spring:
  cloud:
    nacos:
      config:
        server-addr: 21.91.124.110:18848
        file-extension: yml
        namespace: prod
        group: DEFAULT_GROUP
        shared-configs:
          - data-id: my-xhs-common.yml
            group: DEFAULT_GROUP
            refresh: true  # 关键：允许共享配置热刷新
        extension-configs:
          - data-id: my-xhs-{service}.yml
            group: DEFAULT_GROUP
            refresh: true
          - data-id: my-xhs-degrade-switches.yml
            group: DEFAULT_GROUP
            refresh: true
```

### 7.3 Feature Flag 实现

```java
/**
 * 基于 Nacos Config 的 Feature Flag
 * 
 * 用法：
 *   @FeatureFlag("new-search-algorithm")
 *   public List<Note> search(String keyword) { ... }
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface FeatureFlag {
    String value();  // 开关名称
    boolean defaultOn() default false;  // 默认是否开启
}
```

```java
@Aspect
@Component
public class FeatureFlagAspect {
    
    private final FeatureFlagManager flagManager;
    
    @Around("@annotation(featureFlag)")
    public Object check(ProceedingJoinPoint pjp, FeatureFlag featureFlag) throws Throwable {
        if (flagManager.isEnabled(featureFlag.value())) {
            return pjp.proceed();  // 开关打开，正常执行
        }
        // 开关关闭
        if (featureFlag.defaultOn()) {
            return pjp.proceed();  // 默认开启，也执行（降级开关为 false 时）
        }
        log.info("FeatureFlag [{}] 未开启，跳过执行", featureFlag.value());
        return null;  // 或返回默认值
    }
}
```

---

## 维度八：灰度发布

### 8.1 当前状态

| 能力 | 状态 | 详情 |
|------|:---:|------|
| TrafficColoringFilter（流量染色） | ✅ | 6 个染色标记，包括 `X-Gray-Tag` (stable/canary) |
| GrayRouteFilter（灰度路由） | ⚠️ 基础 | 检查 `X-Gray-Tag`，但无真正的 Nacos 实例 metadata 路由 |
| Nacos metadata.version | ❌ | 所有服务均未配置实例版本号 |
| 金丝雀指标对比 | ❌ | 无自动对比 |
| 流量比例放大 | ❌ | 无动态调整 |
| 自动回滚 | ❌ | 无 |

### 8.2 完整灰度发布流程

```
Step 1: 部署金丝雀实例
  Docker: docker run -e NACOS_METADATA_VERSION=v2 my-xhs-order:v2
  Nacos 自动注册：实例标记 version=v2

Step 2: 染色 5% 流量
  Gateway TrafficColoringFilter：userId.hashCode() % 100 < 5 → X-Gray-Tag=canary

Step 3: 路由到金丝雀
  Spring Cloud LoadBalancer：X-Gray-Tag=canary → 选择 metadata.version=v2 的实例

Step 4: 观察 5 分钟
  对比指标：
    - 金丝雀实例：QPS=550, ErrorRate=0.2%, P99=50ms
    - 基线实例：QPS=10500, ErrorRate=0.1%, P99=48ms
    → 无明显差异，继续

Step 5: 放大到 20%
  Nacos Config 中调整 gray-flow-ratio: 5 → 20
  Gateway 监听到配置变更 → 新请求 userId.hashCode() % 100 < 20 → canary

Step 6: 放大到 50% → 100%
  每步观察 5 分钟

Step 7: 全量
  所有请求标记为 stable（即新版本已经是 stable）
  下线旧版本实例
```

**关键：Nacos 实例 metadata 配置**：

```yaml
# 金丝雀实例的 application.yml
spring:
  cloud:
    nacos:
      discovery:
        metadata:
          version: v2  # ← 标记为金丝雀版本
```

---

## 维度九：数据库深度设计

### 9.1 当前状态

| 能力 | 状态 | 详情 |
|------|:---:|------|
| ShardingSphere 分库分表 | ✅ | 订单 4 库 × 4 表，user_id 路由 |
| order_no_mapping 映射表 | ✅ | 解决非分片键查询 |
| **读写分离** | ❌ | 所有流量全部走主库 |
| **慢查询治理** | ❌ | 无采集、无分析、无自动告警 |
| **数据归档** | ❌ | 无策略，历史数据无限膨胀 |
| **索引审查** | ⚠️ | 基础索引存在但缺少复合索引优化 |

### 9.2 读写分离（ProxySQL）

```bash
# docker-compose.yml 追加
proxysql:
  image: proxysql/proxysql:2.6.3
  container_name: myxhs-proxysql
  network_mode: host
  ports:
    - "16033:6033"   # MySQL 代理端口
    - "16032:6032"   # 管理端口
  volumes:
    - ./config/proxysql/proxysql.cnf:/etc/proxysql.cnf
```

```ini
# config/proxysql/proxysql.cnf
mysql_servers:
(
    { address="127.0.0.1", port=13306, hostgroup=10, max_connections=200 },  # 写组
    { address="127.0.0.1", port=13307, hostgroup=10, max_connections=200 },  # 写组
    { address="127.0.0.1", port=13308, hostgroup=10, max_connections=200 },  # 写组
    { address="127.0.0.1", port=13309, hostgroup=10, max_connections=200 }   # 写组
)

mysql_query_rules:
(
    { rule_id=1, active=1, match_pattern="^SELECT.*FOR UPDATE$", destination_hostgroup=10, apply=1 },
    { rule_id=2, active=1, match_pattern="^SELECT", destination_hostgroup=20, apply=1 },
    { rule_id=3, active=1, match_pattern=".*", destination_hostgroup=10, apply=1 }
)
```

### 9.3 高频查询索引优化建议

**订单列表查询**（当前每秒被调用）：
```sql
-- 当前可能使用的索引
SELECT * FROM t_order WHERE user_id = ? AND status = ? ORDER BY created_at DESC LIMIT 20;

-- 建议：创建复合索引
CREATE INDEX idx_user_status_created ON t_order(user_id, status, created_at DESC);
-- 索引生效原理：user_id 等值、status 等值、created_at 排序（全部走索引）
```

**笔记热度排序**：
```sql
SELECT * FROM t_note WHERE status = 1 ORDER BY like_count DESC LIMIT 20;

-- 当前：idx_status 过滤 → filesort by like_count（慢）
-- 建议：创建联合索引
CREATE INDEX idx_status_like ON t_note(status, like_count DESC);
-- 注意：like_count 由计数服务异步更新，可能出现短时间不一致
```

### 9.4 数据归档策略

| 表 | 热数据 | 温数据 | 冷数据 | 归档方式 |
|------|------|------|------|---------|
| t_order | 近 3 个月 | 3-6 个月 | 6 个月前 | 定时任务迁移到 `t_order_archive` 表 |
| t_user_behavior | 近 7 天 | 7-30 天 | 30 天前 | 按天分表 + 超过 30 天 DELETE |
| t_local_message | 近 1 天 | — | 1 天前已处理 | 每天凌晨 DELETE status=1 的记录 |
| t_notification | 近 30 天 | — | 30 天前 | DELETE 已读且超过 30 天的通知 |

---

## 维度十：测试体系 + CI/CD

### 10.1 测试当前状态

```
my-xhs/
├── my-xhs-common/src/test/java/
│   ├── CacheHelperTest.java (12 个测试方法) ✅
│   └── SegmentIdGeneratorTest.java (7 个测试方法) ✅
├── my-xhs-user/src/test/ → ❌ 不存在
├── my-xhs-content/src/test/ → ❌ 不存在
├── my-xhs-order/src/test/ → ❌ 不存在
├── my-xhs-payment/src/test/ → ❌ 不存在
├── my-xhs-inventory/src/test/ → ❌ 不存在
├── my-xhs-gateway/src/test/ → ❌ 不存在
├── ... (其余 10 个服务全部无测试) ❌
└── Testcontainers 依赖：pom.xml 声明了 version 1.19.8 但 **0** 处实际使用
```

### 10.2 核心链路测试计划

| 优先级 | 模块 | 类 | 用例数 | 类型 |
|:---:|------|------|:---:|------|
| P0 | Order | OrderService | 8 | 单元 |
| P0 | Payment | PaymentService | 6 | 单元 |
| P0 | Inventory | InventoryService | 6 | 单元 |
| P0 | Gateway | GatewayAuthFilter | 5 | 单元 |
| P0 | Coupon | CouponService (Lua) | 4 | 单元 |
| P1 | Cart | CartService (Lua) | 4 | 单元 |
| P1 | Counter | CounterService | 4 | 单元 |
| P1 | Content | DFAFilter | 4 | 单元 |
| P1 | Order → Payment → Inventory | 全链路集成 | 3 | 集成 |

### 10.3 CI/CD

**当前**：
- ❌ 无 Jenkinsfile
- ❌ 无 GitHub Actions
- ❌ 无 Dockerfile（仅在文档中描述）
- ✅ docker-compose.yml（仅中间件）

**最小可行 Jenkins Pipeline**：

```groovy
// Jenkinsfile
pipeline {
    agent any
    tools { maven 'maven-3.9.6' }
    
    environment {
        DOCKER_REGISTRY = 'registry.myxhs.local'
        SKYWALKING_AGENT = '/opt/skywalking/agent'
    }
    
    stages {
        stage('Checkout') {
            steps { git url: 'https://git.myxhs.local/my-xhs.git' }
        }
        stage('Compile & Test') {
            steps {
                sh 'mvn clean compile test -DskipITs'
            }
            post {
                always {
                    junit '**/target/surefire-reports/*.xml'
                }
            }
        }
        stage('Package') {
            steps {
                sh 'mvn package -DskipTests -DskipITs'
            }
        }
        stage('Build Docker Image') {
            steps {
                script {
                    def modules = ['user', 'order', 'payment', 'inventory', 
                                   'gateway', 'home', 'search', 'product']
                    modules.each { module ->
                        sh """
                            docker build \\
                                --build-arg JAR_FILE=my-xhs-${module}/target/my-xhs-${module}.jar \\
                                --build-arg SKYWALKING_AGENT=${SKYWALKING_AGENT} \\
                                -t ${DOCKER_REGISTRY}/my-xhs-${module}:${BUILD_NUMBER} \\
                                -f Dockerfile .
                        """
                    }
                }
            }
        }
        stage('Deploy') {
            steps {
                sh 'docker stack deploy -c docker-compose.prod.yml my-xhs'
            }
        }
    }
}
```

**Dockerfile 模板**：

```dockerfile
FROM openjdk:17-jdk-slim

ARG JAR_FILE
ARG SKYWALKING_AGENT

RUN addgroup --system app && adduser --system --group app
USER app:app

COPY ${JAR_FILE} /app/app.jar
COPY ${SKYWALKING_AGENT} /app/skywalking-agent

ENV JAVA_OPTS="-javaagent:/app/skywalking-agent/skywalking-agent.jar \
    -Dskywalking.agent.service_name=my-xhs-{module} \
    -Dskywalking.collector.backend_service=skywalking-oap:11800 \
    -Xms512m -Xmx512m \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=200"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
```

---

## 总结：P8 级别差距汇总

```
架构设计：   ████████░░ 80%  ← 16 服务 + 5 层架构，缺 LB 策略
代码质量：   ██████░░░░ 60%  ← 核心链好，但 Fallback 大面积静默失效
安全性：     █████████░ 90%  ← JWT+HMAC+DFA 成熟，补 Token 主动吊销
分布式事务： ███████░░░ 70%  ← 事务消息成熟，缺补偿消费者+死信
消息队列：   ██████░░░░ 60%  ← 基础消息全覆盖，缺顺序/批量/轨迹
缓存设计：   ██████░░░░ 55%  ← CacheHelper 好，但 Caffeine 未推+预热缺失
熔断降级：   ██░░░░░░░░ 20%  ← FallbackFactory 大面积不生效！最致命
负载均衡：   █░░░░░░░░░ 10%  ← 零自定义策略
可观测性：   ████░░░░░░ 40%  ← Prometheus 好，缺 Agent+Dashboard+JSON日志
配置管理：   ░░░░░░░░░░  5%  ← Nacos Config 全服务未启用！
灰度发布：   ███░░░░░░░ 30%  ← 染色做好了，路由+验证+回滚全缺
测试体系：   █░░░░░░░░░  5%  ← 仅 2 个测试类
CI/CD：      ░░░░░░░░░░  0%  ← 完全空白

综合：       47/100  ← P8 标准下远低于之前 67 分的初级评审
```

**结论**：项目的基础业务代码和分布式基础能力做得不错，但在**工程化、可观测、容错、配置管理**四个维度存在系统性缺失。P8 级别的核心差距可以归纳为三句话：

1. **写了但没用**：FallbackFactory 写好了但 Sentinel 没装 → 静默失效
2. **装了但没用**：Nacos 只做注册中心 → Config 全服务闲置；SkyWalking 只装 OAP → Agent 没挂
3. **做了但没做深**：限流只有 Sentinel 单层 → 缺四层纵深；缓存只做了基础 → 缺热点/BigKey/预热
