# 06 — 跨模块 Feign 编排

> **前置阅读**：[架构文档 §5.1 (Feign)](01-order-module.md) · [05-补偿机制](05-compensation-mechanism.md)
> **源码**：`InventoryFeignClient` · `CouponFeignClient` · `PaymentFeignClient` · `OrderService.releaseInventory()` · `OrderService.returnCouponIfUsed()`

## order 的 Feign 调用全景

order 是唯一一个同时调用 3 个服务的模块：

```
order ──Feign──> my-xhs-inventory  (POST /api/inventory/release)
order ──Feign──> my-xhs-coupon      (POST /api/coupon/return)
order ──Feign──> my-xhs-payment     (POST /api/payment/pay, GET /status, POST /refund)
```

三个 Feign 接口的定义：

```java
@FeignClient(name = "my-xhs-inventory", fallbackFactory = InventoryFeignFallbackFactory.class)
public interface InventoryFeignClient {
    @PostMapping("/api/inventory/release")
    R<Void> releaseStock(@RequestBody Map<String, Object> request);
}

@FeignClient(name = "my-xhs-coupon", fallbackFactory = CouponFeignFallbackFactory.class)
public interface CouponFeignClient {
    @PostMapping("/api/coupon/return")
    R<Void> returnCoupon(@RequestHeader("X-User-Id") Long userId,
                         @RequestBody Map<String, Object> request);
}

@FeignClient(name = "my-xhs-payment", fallbackFactory = PaymentFeignFallbackFactory.class)
public interface PaymentFeignClient {
    @PostMapping("/api/payment/pay")
    R<Object> pay(@RequestBody PayCreateRequest request, @RequestHeader("X-User-Id") Long userId);
}
```

**触发场景**：

| 场景 | 调 inventory | 调 coupon | 调 payment |
|------|:--:|:--:|:--:|
| 取消订单 | releaseStock | returnCoupon | — |
| 支付失败（自动取消） | releaseStock | returnCoupon | — |
| 退款成功 | releaseStock | returnCoupon | refund |
| 下单（mock 支付） | — | — | createPayment |

---

## Spring Cloud LoadBalancer 的 hashCode NPE 修复

order 和其他模块一样遇到了 Spring Cloud LoadBalancer + Nacos 的 `hashCode` NPE bug。表现：Feign 调用返回 503 `库存服务不可用`。

**根因**：Spring Cloud 2023.0.1 + Nacos 2.3.0 的 LoadBalancer 在解析 Nacos `ServiceInstance` 时，`instance.getKey()` 为 null → `Cannot invoke "Object.hashCode()" because "key" is null`。

**修复**：在 `application.yml` 中为两个被调服务指定直连 URL——绕过 Nacos→LoadBalancer 路径：

```yaml
spring:
  cloud:
    openfeign:
      client:
        config:
          my-xhs-inventory:
            url: http://localhost:19009
          my-xhs-coupon:
            url: http://localhost:19010
          my-xhs-payment:
            url: http://localhost:19012
```

修复前：`releaseStock` 全部返回 503 → 触发补偿 MQ。修复后：Feign 直连成功，`释放库存成功`。

**为什么同机部署不需要 Nacos 服务发现？** order、inventory、coupon 都在同一台机器（`21.214.97.212`），localhost 直连比 Nacos→LoadBalancer→Feign 更快更可靠。Nacos 服务发现的价值在跨机器多实例部署时才体现——当服务有多个实例时，需要 Nacos 做负载均衡和健康检查。

---

## CompletableFuture 并行调用模式

源码：`OrderService.cancelOrder()` / `OrderService.onPaymentFailed()`

```java
// 释放库存和退还优惠券互不依赖 — 并行执行
CompletableFuture<Void> releaseFuture = CompletableFuture.runAsync(
    () -> releaseInventory(orderId, userId));
CompletableFuture<Void> returnFuture = CompletableFuture.runAsync(
    () -> returnCouponIfUsed(order));

try {
    CompletableFuture.allOf(releaseFuture, returnFuture).get(3, TimeUnit.SECONDS);
} catch (Exception e) {
    log.warn("[订单] 取消订单Feign并行调用超时/异常，降级依赖补偿机制: orderId={}", orderId, e);
}
```

### 为什么并行而非串行？

```
串行：releaseInventory(500ms) + returnCoupon(500ms) = 1000ms
并行：max(releaseInventory(500ms), returnCoupon(500ms)) = 500ms
```

两个 Feign 调用操作不同的服务，没有数据依赖——天然适合并行。`CompletableFuture.runAsync()` 使用 ForkJoinPool 的 commonPool 线程——每个异步任务提交到不同线程，两个 Feign 调用同时发出。

### 3 秒超时的三重含义

1. **用户体验**：取消订单的 HTTP 响应在 3 秒内返回——即使 Feign 调用因网络故障挂起。
2. **降级触发**：超时后 catch 块记录 `"降级依赖补偿机制"`——由 Layer 2（MQ 补偿消息）和 Layer 3（本地消息表）兜底。
3. **线程释放**：`get(3, TimeUnit.SECONDS)` 超时后抛 TimeoutException→主线程不再等待 ForkJoinPool 线程——线程池线程继续执行 Feign 调用（可能最终超时失败），但不影响主流程。

### FallbackFactory 的降级处理

```java
@Component
public class InventoryFeignFallbackFactory implements FallbackFactory<InventoryFeignClient> {
    @Override
    public InventoryFeignClient create(Throwable cause) {
        log.error("[Feign降级] 库存服务调用失败: {}", cause.getMessage());
        return request -> R.fail(503, "库存服务不可用");
    }
}
```

Fallback 返回 `R(code=503)`——调用方 `releaseInventory` 检查 `result.isSuccess()` → false → `log.error("释放库存失败，需补偿")` → `sendCompensationMessage()`。Fallback 不是"静默失败"——它是一级降级，触发二级补偿。

---

## Feign 调用失败后的补偿链

```
cancelOrder
  │
  ├── releaseInventory(orderId)
  │     └── inventoryFeignClient.releaseStock(request)
  │           │
  │           ├── 成功 → log.info("释放库存成功") ✓
  │           └── 失败/超时/Fallback
  │                 └── sendCompensationMessage("RELEASE_STOCK", orderId, userId, reason)
  │                       └── syncSend ORDER_COMPENSATION_TOPIC
  │                             └── OrderCompensationConsumer(maxReconsumeTimes=3)
  │                                   └── retry Feign → 成功/死信
  │
  └── returnCouponIfUsed(order)
        └── couponFeignClient.returnCoupon(userId, request)
              └── (同上补偿链)
```

**为什么不在 cancelOrder 中重试 Feign？**

如果 `releaseInventory` 第一次失败——可能是库存服务正在重启（瞬时故障），也可能是库存服务所在机器宕机（持久故障）。在 cancelOrder 中重试——阻塞用户的取消操作 3 秒等待未知结果——不如立即返回"订单已取消"，让补偿机制异步重试。

用户看到"订单已取消"——这就够了。库存释放的细节不影响用户。

---

## 订单取消时的 Redis 缓存清理

```java
// cancelOrder 的最后一步
stringRedisTemplate.delete("order:info:" + orderId);
```

取消订单后删除 Redis 缓存——下一次用户查询订单详情时，从 MySQL 重新加载最新状态（status=4，已取消）。

**为什么不是更新缓存而是删除？** 删除比更新简单——不需要构造新的缓存值。Cache-Aside 模式——下一次查询自然从 MySQL 加载并回写 Redis。删除操作幂等——删一个不存在的 key 也不报错。

---

## 面试 Q&A

### Q1：Feign 调用失败后，为什么不直接抛异常给用户，而是走补偿？

**答案**：取消订单的核心操作是 Event Sourcing 记录取消事件 + 乐观锁更新 Order 状态——这两步在本地完成，不依赖外部服务。用户关心的是"订单是否取消了"——Feign 调用是"取消后的善后工作"，不应该影响主流程的结果。

**追问**：如果库存释放一直失败（超过 5 次重试全部失败），订单已经是"已取消"状态——会不会出现"订单取消了但库存还在冻结"的永久不一致？

→ 会。补偿重试耗尽后进入死信——此时需要人工介入或依赖 inventory 自己的 L3 对账修复。cancelOrder 的 `releaseInventory` 调用的是 inventory 的 `/release` 端点——inventory 内部通过 Lua 原子回退 Redis 库存。如果 inventory 长时间不可用——Redis 和 MySQL 都保留了预扣记录——inventory 的 PreDeductTimeoutJob（每 5 分钟）和 L3 对账（凌晨）提供最终一致性保证。

### Q2：为什么 order 的 Feign 调用都用 Map<String, Object> 而不是强类型 DTO？

**答案**：`inventory.releaseStock` 和 `coupon.returnCoupon` 的参数很简单——只需要 `orderId` 一个字段。用 Map 比定义专门的 DTO 更灵活——如果 inventory 将来增加参数（如 `cancelReason`），order 只需要在 Map 中加一个 key，不需要改 DTO 类。

**追问**：Map 失去了编译期类型检查——如果 key 打错了（如 `orderId` 写成 `orderid`），编译器发现不了怎么办？

→ 运行时表现为 inventory 收到错误的参数——inventory 的 `@Valid @RequestBody ReleaseStockRequest` 会校验 `orderId != null` → 抛 `MethodArgumentNotValidException` → order 的 Fallback 捕获 → 返回 503 → 触发补偿。不会静默失败。

### Q3：order 的 Feign 调用有没有考虑过换成 gRPC 或异步消息？

**答案**：gRPC 的优势是长连接 + protobuf 序列化——延迟更低（~1ms vs Feign HTTP ~50ms）。但 Feign 的 HTTP 调用已经足够好——取消订单不是性能敏感路径（用户不会频繁取消订单）。

如果换成异步消息——取消订单发一条 MQ 消息，由 inventory 和 coupon 各自消费——取消操作变为异步——用户点"取消"后不能立即看到"已取消"状态。同步 Feign + 异步补偿的组合保证了"用户看到即时结果"的同时"后台持续重试"。

---

## 发散：Feign vs gRPC vs MQ 编排

| 方案 | order 的使用 | 延迟 | 可靠性 |
|------|------|:--:|------|
| Feign (HTTP) | releaseStock + returnCoupon | ~50ms | 低（瞬时故障触发补偿） |
| gRPC | — | ~1ms | 低（长连接但同样可能断） |
| MQ (异步) | 下单事务消息 (ORDER_TRANSACTION_TOPIC) | ~13s | 高（Broker 持久化 + 重试） |

order 同时用了 Feign（取消/退款的同步调用）和 MQ（下单的异步通知）：
- **取消/退款用 Feign**：用户需要立即看到结果（"订单已取消"），同步调用保证操作完成后再返回。
- **下单用 MQ**：下单后的库存预扣不需要同步等待——inventory Consumer 在 ~13s 内异步完成，用户不需要知道。

---

## 生产故障实验

### 实验：验证 Feign Fallback → 补偿消息链路

```bash
# 1. 停止 inventory 服务（模拟库存不可用）
kill $(ps aux | grep my-xhs-inventory | grep java | awk '{print $2}')

# 2. 创建并取消一个订单
CID=$(curl -s -X POST /api/order/create ...)
curl -s -X POST "/api/order/cancel?orderId=$CID"

# 3. 验证释放库存触发 Fallback 和补偿
grep "$CID" /data/workspace/my-xhs/logs/my-xhs-order/info.log | grep -E '释放库存失败|补偿消息已发送'
# → [订单] 释放库存失败，需补偿: orderId=$CID, result=R(code=503)
# → [订单] 补偿消息已发送: action=RELEASE_STOCK, orderId=$CID

# 4. 重启 inventory 并等待 Consumer 重试
# → OrderCompensationConsumer 重试 inventory.releaseStock → 成功

# 5. 恢复 inventory
cd /data/workspace/my-xhs && nohup java ... -jar my-xhs-inventory/target/my-xhs-inventory-1.0-SNAPSHOT.jar &
```
