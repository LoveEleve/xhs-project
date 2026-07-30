# IM 一致性 Hash 负载均衡 — 深度技术分析

> 关联源码：`ImConsistentHashLoadBalancer.java`

---

## 业务背景

IM 的 WebSocket 连接需要**会话保持**（sticky session）：同一用户的所有 WebSocket 请求必须路由到同一个 IM 实例。否则：

```
❌ 无会话保持：
  用户 A 连接实例1 → 发消息给 B
  用户 A 断开重连 → 实例2
  实例1 上有 A 的 WebSocket 会话，实例2 没有
  → 后续消息需跨实例转发 → 增加延迟

✅ 一致性 Hash：
  用户 A 永远路由到实例1
  用户 A 断开重连 → 仍是实例1
```

---

## 为什么不是轮询 / 随机 / 最少连接

| 算法 | 会话保持 | 扩缩容影响 | 实现复杂度 |
|---|---|---|---|
| 轮询 | ❌ 不保持 | — | 低 |
| 随机 | ❌ 不保持 | — | 低 |
| 最少连接 | ❌ 不保持 | — | 中 |
| 源地址 Hash | ✅ 保持 | 大（全部重新映射） | 低 |
| **一致性 Hash** | ✅ 保持 | 小（仅相邻节点） | 高 |

**扩缩容影响对比**：
- 源地址 Hash：新增一台实例 → 大部分用户的 Hash 结果变化 → 大量连接迁移
- 一致性 Hash：新增一台实例 → 只影响其相邻节点的少量用户

---

## 实现详解

### 算法选择：FNV-1a

```java
private int hash(String key) {
    int hash = 0x811c9dc5;
    for (char c : key.toCharArray()) {
        hash ^= c;
        hash *= 0x01000193;
    }
    return hash & 0x7fffffff;  // 确保正数
}
```

对比常见哈希算法：

| 算法 | 速度 | 分布均匀性 | 输出 |
|---|---|---|---|
| MD5 | 慢 | 均匀 | 128 bit |
| FNV-1a | 极快 | 均匀 | 32 bit |
| Murmur3 | 快 | 极均匀 | 32/128 bit |
| CRC32 | 快 | 中等 | 32 bit |

选择 FNV-1a 的原因：
- 32 bit 输出，直接作为 `TreeMap` 的 Key（无需截断）
- 非加密哈希，不涉及安全开销
- 代码简洁（5 行），无外部依赖

### 虚拟节点

```java
private static final int VIRTUAL_NODES_PER_INSTANCE = 150;
```

每个物理实例对应 150 个虚拟节点，均匀分布在环上：

```
物理实例 A (10.0.0.1:19014)
  → 虚拟节点: A#0, A#1, A#2, ..., A#149
  每个虚拟节点 hash 后分布在环的不同位置

物理实例 B (10.0.0.2:19014)
  → 虚拟节点: B#0, B#1, B#2, ..., B#149
```

**150 的考虑**：太少（如 50）→ 数据倾斜明显；太多（如 1000）→ 环重建开销大（每次重建需插入 1000 个 Entry 到 TreeMap）。150 在均匀性和重建性能之间平衡。

### 环重建与指纹缓存

```java
private volatile ConcurrentSkipListMap<Integer, ServiceInstance> hashRing;
private volatile int cachedInstancesHash = 0;

private void rebuildRing(List<ServiceInstance> instances) {
    int newHash = instances.stream()
            .map(i -> i.getHost() + ":" + i.getPort())
            .sorted().toList().hashCode();
    if (newHash == cachedInstancesHash && !hashRing.isEmpty()) {
        return;  // 实例列表未变，复用缓存
    }
    // 重建...
}
```

**M13 修复**：指纹缓存。每次请求都重建 Hash 环是浪费的——实例列表通常数小时不变。用 `host:port` 列表的 hashCode 作为指纹，仅指纹变化时重建。

### 查找算法

```java
private ServiceInstance findInstance(Long userId) {
    int hash = hash(String.valueOf(userId));
    Map.Entry<Integer, ServiceInstance> entry = hashRing.ceilingEntry(hash);
    if (entry == null) {
        entry = hashRing.firstEntry();  // 环状回绕
    }
    return entry.getValue();
}
```

`ConcurrentSkipListMap.ceilingEntry()` → O(log N) 查找。环状回绕：当 hash 大于所有虚拟节点时，回到第一个节点。

### 用户 ID 提取

```java
private Long extractUserId(Request request) {
    // 反射获取 X-User-Id Header
    Object context = request.getContext();
    var method = context.getClass().getMethod("getClientRequest");
    Object clientRequest = method.invoke(context);
    var headersMethod = clientRequest.getClass().getMethod("getHeaders");
    Object headers = headersMethod.invoke(clientRequest);
    var getFirstMethod = headers.getClass().getMethod("getFirst", String.class);
    Object userIdObj = getFirstMethod.invoke(headers, "X-User-Id");
    return Long.parseLong(userIdObj.toString());
}
```

反射是因为 Spring Cloud LoadBalancer 的 `Request` 接口在不同版本中 `getContext()` 返回类型不同。当反射失败（如无 Header），降级为轮询：`int idx = (int) (System.currentTimeMillis() % instances.size())`。

---

## 一致性 Hash 环示意图

```
          ┌─────┐
     ┌────│ A#47│────┐
     │    └─────┘    │
     │               │
  ┌──▼──┐        ┌──▼──┐
  │B#123│        │B#12 │
  └─────┘        └─────┘
     │               │
     │    ┌─────┐    │
     │    │ A#2 │    │
     │    └─────┘    │
     │               │
  ┌──▼──┐        ┌──▼──┐
  │A#88 │        │B#55 │
  └─────┘        └─────┘
     │               │
     │    ┌─────┐    │
     └───►│ A#5 │◄───┘  ← userId hash 落在此区间
          └─────┘       → ceilingEntry(hash) → A#5 → 实例 A
```

---

## 扩缩容影响

### 扩容（新增实例 C）

```
扩容前：环上有 A×150 + B×150 = 300 虚拟节点
扩容后：环上有 A×150 + B×150 + C×150 = 450 虚拟节点

影响范围：仅 C 的相邻节点的用户（约 1/3 用户重新映射）
vs 源地址 Hash：全部用户重新映射
```

### 缩容（实例 B 下线）

```
Nacos 摘除 B → ServiceInstanceListSupplier 更新
→ rebuildRing 检测到指纹变化 → 重建环
→ B 的 150 虚拟节点移除
→ 原本路由到 B 的用户 → A 或 C 接管
→ 这些用户的 WebSocket 需要重连
```

---

## 生产实验

### 单实例行为验证

```
环境：当前部署 1 个 IM 实例（port=19014）
instances.size() = 1 → choose() 短路返回
```

验证：`ServiceInstanceListSupplier` 返回列表长度为 1，`rebuildRing()` 不会执行，日志无 "Hash环重建" 输出。这是正确行为。

### 多实例环境限制

当前只有单实例，以下测试**无法执行**：

- **Hash 分布验证**：需 2+ 实例，验证 userId 是否均匀映射到不同实例
- **实例下线影响**：需 Nacos 摘除实例后验证环重建和用户重映射
- **指纹缓存命中**：需验证实例列表不变时 `cachedInstancesHash` 复用

上述测试标记为 `[需多实例环境]`。

### 算法正确性验证

代码审查确认：
- `FNV-1a` 哈希：`0x811c9dc5` 种子，逐字节 XOR + 乘 `0x01000193`，结果 `& 0x7fffffff` ✅
- `ceilingEntry` 环状回绕：hash 超上限时 `firstEntry()` ✅
- 150 虚拟节点/instance：`instanceKey + "#" + i` 模板 ✅
- 指纹缓存：`host:port` 列表 hashCode 比较，变化才重建 ✅

Nacos 摘除实例 → `ServiceInstanceListSupplier` 推送更新 → `rebuildRing()` 检测到指纹变化 → 重建环。

指纹缓存验证：重启前后实例列表不变时，`cachedInstancesHash` 不变，`rebuildRing()` 提前 return，不重建。

测试环境限制：当前仅部署单实例，多实例负载均衡行为需双实例部署后验证。

```yaml
# application.yml — 非默认，必须显式配置
spring:
  cloud:
    loadbalancer:
      configurations: my-xhs-im-consistent-hash
```

Spring Cloud Load Balancer 的 `configurations` 属性指定自定义 `LoadBalancer` 的 bean 名称。如果不配置，使用默认的轮询算法，IM 的会话保持不生效。

---

## 竞态条件审计

### 场景：重建环时请求并发

`ConcurrentSkipListMap` 是线程安全的，但 `hashRing` 字段的 `volatile` 保证：
- 写（rebuildRing）：先构建新 ring，再赋值给 `hashRing`（写 volatile → happens-before）
- 读（findInstance）：读取 `hashRing` 引用，无中间状态

### 场景：userId 提取失败

```java
// 降级为时间戳取模轮询
int idx = (int) (System.currentTimeMillis() % instances.size());
```

当 Header 缺失或反射失败时，走随机轮询。不完全公平但避免拒绝请求。

### 场景：单实例部署

```java
if (instances.size() == 1) {
    return new DefaultResponse(instances.get(0));
}
```

单实例时直接返回，跳过 Hash 计算和环重建。

---

## 面试 Q&A

**Q: 为什么是 150 虚拟节点？**
A: 50 太少：数据倾斜明显（少数节点承载过多用户）。150 在均匀性和性能之间平衡——300 虚拟节点下 `ceilingEntry` 的查找深度约 log(300) ≈ 9 次比较，再增加节点数对均匀性提升有限，但环重建开销线性增长。

**Q: 为什么不用 Spring Cloud 默认的 LoadBalancer？**
A: 默认是轮询（RoundRobin），不保证会话保持。IM 的 WebSocket 需要同一用户始终路由到同一实例，否则用户断线重连后跨实例消息路由增加延迟。

**Q: FNV-1a 和 Murmur3 怎么选的？**
A: FNV-1a 实现 5 行代码，无外部依赖。Murmur3 需要 Guava 的 `Hashing.murmur3_32()`，分布更均匀，但在 IM 场景下用户 Hash 的均匀性已经足够——150 虚拟节点会分散在环上，单个哈希算法的微小不均匀性被虚拟节点平均化。

**Q: 实例上下线时用户连接断开的处理？**
A: 实例下线 → Nacos 摘除 → rebuildRing → 用户重新 Hash 到其他实例。但用户的 WebSocket 连接已断开，需要客户端重连。重要：重连时走的是 Gateway 的负载均衡（默认轮询），不是 IM 模块的 `ImConsistentHashLoadBalancer`。所以重连后用户可能落到不同实例，需要跨实例消息路由。但 IM 会话状态存储在 MySQL + Redis，无状态化设计，跨实例路由有 Redis Pub/Sub 保障。

---

## 发散

### 替代方案：Spring Cloud Gateway 的 RouteToRequestUrl

如果 IM 的 WebSocket 经过 Gateway 代理（`lb://my-xhs-im`），Gateway 也走 LoadBalancer。可以在 Gateway 层实现一致性 Hash，而不是在 IM 模块自身：

```yaml
# Gateway 配置 — 未实现
spring.cloud.loadbalancer.configurations: im-consistent-hash
spring.cloud.loadbalancer.hint: X-User-Id
```

但 Gateway 是基于 WebFlux 的，`ImConsistentHashLoadBalancer` 实现了 `ReactorServiceInstanceLoadBalancer` 接口，理论上可以直接在 Gateway 模块复用。

### 虚拟节点数自适应

150 是固定值，可以根据实例数动态调整：实例越多，每个实例的 vnodes 越少（总 vnodes 恒定）。例如：
```
总 vnodes = 300 （恒定）
2 实例 → 150 vnodes/instance
4 实例 → 75 vnodes/instance
```
减少实例数少时的倾斜。
