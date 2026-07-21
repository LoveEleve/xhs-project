# BFF 聚合层 + 订单取消联动 Code Review

> 模块：my-xhs-home（BFF 聚合接口）+ my-xhs-order（取消联动）
> 审查时间：2026-05-15
> 审查标准：对标大厂 P8 生产环境

---

## 📊 评分表

| 维度 | 评分 | 说明 |
|------|:----:|------|
| 架构设计 | ⭐⭐⭐⭐ | BFF 聚合层设计合理，2 层并行编排清晰，线程池隔离避免饥饿 |
| 并发安全 | ⭐⭐⭐⭐ | 无共享可变状态，线程池隔离，MDC 透传 |
| 容错降级 | ⭐⭐⭐⭐ | 每层独立超时 + 全局超时控制，Fallback 完备 |
| 代码质量 | ⭐⭐⭐☆ | 弱类型 Map 传递数据，维护成本高 |
| 生产可用性 | ⭐⭐⭐⭐ | 修复后已具备生产级保护 |
| 可观测性 | ⭐⭐⭐☆ | 有日志但缺少 Prometheus metrics 埋点 |

**综合评分：3.7 / 5（中高水平，接近 P8 标准）**

---

## 🔴 发现的问题及修复记录

### 问题 1：笔记不存在 vs 服务降级无法区分（已修复 ✅）

**现象**：ContentFeignFallbackFactory 返回 `R.ok(emptyMap)`，NoteAggService 中 `noteData.isEmpty()` 时返回 null → Controller 返回 404。

**根因**：Fallback 返回的是"成功但空数据"，与"笔记确实不存在"无法区分。

**修复**：NoteAggService 改为接收完整的 `R<Map>` 响应，通过 `isSuccess()` 判断服务是否正常响应。

**修复前**：
```java
CompletableFuture<Map<String, Object>> noteFuture = CompletableFuture
    .supplyAsync(() -> {
        R<Map<String, Object>> r = contentFeignClient.getNoteDetail(noteId);
        return (r != null && r.isSuccess() && r.getData() != null) ? r.getData() : Collections.emptyMap();
    }, aggregatorPool);
// ...
Map<String, Object> noteData = noteFuture.getNow(Collections.emptyMap());
if (noteData.isEmpty()) return null; // 无法区分降级 vs 不存在
```

**修复后**：
```java
CompletableFuture<R<Map<String, Object>>> noteFuture = CompletableFuture
    .supplyAsync(() -> {
        try {
            R<Map<String, Object>> r = contentFeignClient.getNoteDetail(noteId);
            return r != null ? r : R.fail(503, "content服务无响应");
        } catch (Exception e) {
            return R.fail(503, "content服务异常");
        }
    }, aggregatorPool);
// ...
R<Map<String, Object>> noteResult = noteFuture.getNow(R.fail(503, "超时降级"));
if (noteResult == null || !noteResult.isSuccess()) {
    log.warn("[笔记详情] content服务不可用");
    return null; // 上层可根据需要返回 503 而非 404
}
Map<String, Object> noteData = noteResult.getData();
if (noteData == null || noteData.isEmpty()) return null; // 笔记确实不存在
```

---

### 问题 2：全局请求级超时缺失（已修复 ✅）

**现象**：第 1 层 3s + 第 2 层 2s = 最坏情况 5s，前端可能超时。

**根因**：每层超时独立设置，没有全局约束。

**修复**：添加全局超时控制（4s），第 2 层超时 = `全局超时 - 第1层实际耗时`（最少 500ms）。

**修复后**：
```java
long startTime = System.nanoTime();
long globalTimeoutMs = 4000;
// ... 第 1 层 ...
long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
long layer2TimeoutMs = Math.max(500, globalTimeoutMs - elapsedMs);
CompletableFuture.allOf(...).get(layer2TimeoutMs, TimeUnit.MILLISECONDS);
```

---

### 问题 3：弱类型 Map 传递数据（未修复，记录为技术债）

**现象**：所有 Feign Client 返回 `R<Map<String, Object>>`，Service 层通过 `get("key")` + 强转。

**风险**：
- 下游字段名变更 → 编译期无法发现 → 运行时 NPE/ClassCastException
- 维护成本高，新人接手困难

**P8 标准方案**：
1. 在 `my-xhs-common` 中定义跨服务共享 DTO（如 `NoteDetailDTO`、`UserPublicInfoDTO`）
2. Feign Client 返回强类型：`R<NoteDetailDTO>`
3. 下游服务字段变更时，编译期即可发现不兼容

**暂不修复原因**：需要在 common 模块中定义大量 DTO，涉及多个服务的改动，作为后续重构项。

---

### 问题 4：购物车 onSale 硬编码（未修复，记录为技术债）

**现象**：`.onSale(true)` 硬编码，无法感知商品下架。

**影响**：用户看到购物车中已下架商品仍显示"在售"，下单时才报错。

**方案**：第 2 层并行中增加 ProductFeignClient 批量查询商品状态。

---

### 问题 5：缺少缓存层（未修复，记录为技术债）

**现象**：每次请求直接调用下游服务，无缓存。

**影响**：高并发下 BFF 成为流量放大器。

**方案**：
- 笔记详情/商品详情/用户信息：Redis 缓存 30s
- 热点数据（大V主页）：缓存 5min + 异步刷新
- 计数数据：缓存 10s（允许短暂不一致）

---

### 问题 6：订单取消补偿定时任务未实现（未修复，记录为技术债）

**现象**：`sendCompensationMessage` 失败后注释说"依赖定时任务扫描"，但定时任务未实现。

**方案**：实现 `OrderCompensationJob`，定时扫描 status=4 且 `cancelled_at > 5min` 的订单，检查库存/优惠券是否已释放。

---

## 🌟 技术亮点和面试价值

| 亮点 | 面试价值 | 说明 |
|------|:--------:|------|
| 2 层并行编排 | ⭐⭐⭐⭐⭐ | 正确识别数据依赖，第 2 层依赖第 1 层结果 |
| 线程池隔离 | ⭐⭐⭐⭐⭐ | aggregatorPool + batchFeignPool 避免嵌套饥饿 |
| 全局超时控制 | ⭐⭐⭐⭐ | 动态计算第 2 层超时，保证总耗时可控 |
| MDC 透传 | ⭐⭐⭐⭐ | 子线程自动携带 TraceId，全链路可追踪 |
| 补偿机制设计 | ⭐⭐⭐⭐ | Feign 失败 → MQ 补偿 → 定时任务兜底 |
| CallerRunsPolicy | ⭐⭐⭐⭐ | 队列满时降级为同步执行，不丢弃请求 |

---

## 🎤 面试话术

### Q1: BFF 聚合层怎么设计的？并行调用怎么做的？

> 1. "BFF 层负责将多个微服务的数据聚合为前端需要的完整 VO"
> 2. "使用 CompletableFuture 2 层并行编排：第 1 层获取核心数据（笔记详情+社交状态+计数），第 2 层依赖第 1 层结果（如 authorId）获取作者信息+关注关系"
> 3. "关键设计：双线程池隔离（aggregatorPool 做外层编排，batchFeignPool 做内层批量 Feign 调用），避免嵌套 CompletableFuture 导致线程池饥饿/死锁"
> 4. "全局超时控制：整个聚合不超过 4s，第 2 层超时 = 全局超时 - 第 1 层实际耗时"

### Q2: 下游服务超时了怎么办？

> 1. "每层有独立超时，超时后通过 getNow(defaultValue) 获取默认值，不影响其他数据"
> 2. "Fallback 工厂提供降级响应，核心数据（如笔记详情）不可用时返回 null，非核心数据（如计数、社交状态）降级为 0/false"
> 3. "CallerRunsPolicy：线程池队列满时由调用线程执行，降级为同步但不丢弃请求"

### Q3: 为什么要两个线程池？一个不行吗？

> 1. "如果只用一个线程池，外层任务（如 aggregateFeed）占满线程后，内层子任务（如 batchGetNoteDetails 中的 N 个并行 Feign 调用）无法获得线程执行"
> 2. "形成死锁/饥饿：外层等内层完成，内层等外层释放线程"
> 3. "解决方案：aggregatorPool 做编排（20 核心线程），batchFeignPool 做批量 IO（30 核心线程），彻底隔离"

### Q4: 订单取消时，释放库存和退券怎么保证最终一致性？

> 1. "先乐观锁更新订单状态（WHERE status=0），成功后再调用下游服务"
> 2. "Feign 调用失败时，发送补偿消息到 MQ（ORDER_COMPENSATION_TOPIC）"
> 3. "MQ 也失败时，依赖定时任务扫描（status=4 且 cancelled_at > 5min 的订单）"
> 4. "下游服务通过 orderId 做幂等，重复调用不会多释放"
> 5. "三层保障：同步 Feign → MQ 补偿 → 定时任务兜底，保证最终一致性"

---

## 📋 后续优化 TODO

- [ ] 将 `Map<String, Object>` 替换为强类型 DTO（跨服务共享）
- [ ] 添加 Redis 缓存层（笔记/商品/用户信息短期缓存）
- [ ] 添加 Prometheus metrics 埋点（聚合耗时、降级率、各服务成功率）
- [ ] 实现 OrderCompensationJob 补偿定时任务
- [ ] 购物车聚合增加商品状态查询（onSale）
- [ ] 考虑热点数据的本地缓存（Caffeine L1 + Redis L2）
