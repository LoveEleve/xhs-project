# 17-推荐系统 Code Review

> 审查时间：2026-05-14 | 审查人：AI Reviewer | 服务：my-xhs-search (9011)

---

## 📊 一、P8 评分表

| 维度 | 满分 | 得分 | 说明 |
|------|:----:|:----:|------|
| 架构设计 | 20 | 18 | 经典四层架构（召回→粗排→精排→重排），5路并行召回，策略模式解耦 |
| 代码质量 | 15 | 13 | 异常处理完善，日志规范，但有几处需修复的问题 |
| 分布式安全 | 15 | 14 | 所有定时任务加 Redisson 分布式锁，但 ItemCF 矩阵更新有原子性窗口 |
| 性能优化 | 15 | 12 | CompletableFuture 并行召回+独立线程池，但冷启动判断 SQL 有性能问题 |
| 数据一致性 | 10 | 8 | HyperLogLog 已读检测有严重副作用（PFADD 会污染数据） |
| 可扩展性 | 10 | 9 | 策略模式易扩展新召回策略，精排预留 ML 接口 |
| 面试价值 | 10 | 9 | 推荐系统四层架构、Item-CF、冷启动策略都是高频面试题 |
| 生产就绪度 | 5 | 3 | 行为上报同步写 MySQL，缺少限流和降级 |
| **总分** | **100** | **86** | **优秀（修复后 92 分）** |

---

## 🐛 二、发现的问题及修复

### 问题 1：🔴 HyperLogLog 已读检测有严重副作用

**严重程度**：P0（数据污染）

**问题描述**：
`isAlreadySeen()` 方法使用 `PFADD` 来检测元素是否已存在。但 `PFADD` 是一个**写操作**——它会把检测的元素也加入 HyperLogLog。这意味着：
- 在过滤阶段，所有候选笔记（包括最终未推荐的）都被标记为"已读"
- 品类打散跳过的笔记也被标记为"已读"，下次永远不会被推荐
- 这是一个**静默数据污染**，不会报错但会导致推荐池快速枯竭

**修复前**：
```java
private boolean isAlreadySeen(String seenKey, Long noteId) {
    Long added = stringRedisTemplate.opsForHyperLogLog()
            .add(seenKey, String.valueOf(noteId));
    if (added != null && added == 0) {
        return true; // 可能已曝光
    }
    return false; // 但此时元素已被加入 HyperLogLog！
}
```

**修复后**：
```java
// 改用 Redis Set + SISMEMBER（纯读操作，无副作用）
private boolean isAlreadySeen(String seenKey, Long noteId) {
    return Boolean.TRUE.equals(
            stringRedisTemplate.opsForSet().isMember(seenKey, String.valueOf(noteId)));
}

// 曝光记录也改为 Set
stringRedisTemplate.opsForSet().add(seenKey, noteIds);
```

**技术分析**：
- HyperLogLog 本质是**基数统计**数据结构，不支持成员判断（PFCOUNT 返回的是估计基数）
- PFADD 返回 0 只表示"估计基数未变"，不等于"元素已存在"
- Redis Set 的 SISMEMBER 是 O(1) 的精确成员判断，无副作用
- 内存代价：每用户约 50KB（7天 × 每天推荐 20条 × 多次刷新 ≈ 1000~5000 条 Long ID）
- 如果用户量极大，可升级为 Redis Bloom Filter 模块（BF.EXISTS 也是纯读操作）

---

### 问题 2：🔴 冷启动判断 SQL 性能问题

**严重程度**：P1（性能）

**问题描述**：
```sql
SELECT COUNT(*) FROM t_user_behavior WHERE user_id = ? LIMIT 1
```
`LIMIT 1` 对 `COUNT(*)` 无效！MySQL 会扫描该用户的所有行为记录来计算 COUNT。活跃用户可能有数万条行为记录，每次推荐请求都要全表扫描。

**修复后**：
```sql
SELECT EXISTS(SELECT 1 FROM t_user_behavior WHERE user_id = ? LIMIT 1)
```
`EXISTS` + `LIMIT 1` 找到第一条即返回，O(1) 性能。

---

### 问题 3：🟡 Item-CF 相似矩阵更新非原子

**严重程度**：P2（数据一致性）

**问题描述**：
```java
stringRedisTemplate.delete(key);  // 删除旧数据
// ← 此处有窗口期：其他线程读到空数据
for (...) {
    stringRedisTemplate.opsForZSet().add(key, ...);  // 逐个写入
}
```
在 DELETE 和 ADD 之间，其他请求读取该 Key 会得到空结果，导致 Item-CF 召回短暂失效。

**修复后**：
```java
String tmpKey = key + ":tmp";
stringRedisTemplate.delete(tmpKey);
for (...) {
    stringRedisTemplate.opsForZSet().add(tmpKey, ...);  // 写入临时 Key
}
stringRedisTemplate.rename(tmpKey, key);  // RENAME 原子替换
```
与热门池更新使用相同的 RENAME 原子替换模式。

---

### 问题 4：🟡 ThreadFactory 线程安全问题

**严重程度**：P2（并发安全）

**问题描述**：
```java
private int count = 0;  // 非线程安全！
public Thread newThread(Runnable r) {
    Thread t = new Thread(r, "recall-pool-" + (++count));  // 竞态条件
}
```
`ThreadFactory.newThread()` 可能被多个线程并发调用，`++count` 不是原子操作。

**修复后**：
```java
private final AtomicInteger count = new AtomicInteger(0);
public Thread newThread(Runnable r) {
    Thread t = new Thread(r, "recall-pool-" + count.incrementAndGet());
}
```

---

### 问题 5：🟡 BehaviorRequest 缺少参数校验

**严重程度**：P2（安全性）

**问题描述**：
`BehaviorRequest` 没有任何校验注解，恶意请求可以传入 `noteId=null` 或 `behaviorType=999`。

**修复后**：
```java
@NotNull(message = "笔记ID不能为空")
private Long noteId;

@NotNull(message = "行为类型不能为空")
@Min(value = 1, message = "行为类型最小为1")
@Max(value = 7, message = "行为类型最大为7")
private Integer behaviorType;
```
Controller 添加 `@Valid` 注解。

---

### 问题 6：🟡 ContentRecallStrategy LIKE 注入风险

**严重程度**：P2（安全性）

**问题描述**：
```java
"WHERE tags LIKE ?" , "%" + tag + "%"
```
如果 tag 中包含 `%` 或 `_`（SQL LIKE 通配符），会导致意外的模糊匹配。

**修复后**：
```java
String escapedTag = tag.replace("%", "\\%").replace("_", "\\_");
"WHERE tags LIKE ? ESCAPE '\\\\'" , "%" + escapedTag + "%"
```

---

### 问题 7：🟢 粗排注释与实现不一致

**严重程度**：P3（可读性）

**问题描述**：
注释说"热度 0.3 + 相似度 0.3 + 时效 0.2 + 距离 0.2"，实际实现是"召回分 × 来源权重"。

**修复后**：更新注释为准确描述。

---

### 问题 8：🟢 未使用的 import

**严重程度**：P3（代码整洁）

**问题描述**：`import java.time.LocalDateTime` 未使用。

**修复后**：删除。

---

## ⭐ 三、技术亮点

### 3.1 经典四层推荐架构

```
5路并行召回(≈300条) → 粗排(Top100) → 精排(Top50) → 重排(Top20)
```

这是业界标准的推荐系统架构，每层职责清晰：
- **召回层**：扩大候选集覆盖率（多路互补）
- **粗排层**：快速筛选（轻量级打分）
- **精排层**：精确排序（预留 ML 模型）
- **重排层**：业务规则（去重 + 多样性）

### 3.2 策略模式 + Spring 自动注入

```java
private final List<RecallStrategy> recallStrategies;  // Spring 自动注入所有实现
```

新增召回策略只需实现 `RecallStrategy` 接口并加 `@Component`，零改动接入。

### 3.3 CompletableFuture 并行召回 + 超时降级

```java
CompletableFuture.allOf(futures).get(recallTimeoutMs, TimeUnit.MILLISECONDS);
// 超时后使用 getNow() 获取已完成的结果
```

5 路召回并行执行，总耗时 = max(各路耗时)。超时后不等待慢的策略，使用已完成的结果降级。

### 3.4 RENAME 原子更新（热门池 + 相似矩阵）

```java
stringRedisTemplate.rename(tmpKey, key);  // 原子替换，无空窗期
```

避免 DELETE + ADD 之间的数据空窗期。

### 3.5 冷启动分层策略

新用户无行为数据时，自动降级为热门(60%) + 地理(30%) 推荐，保证任何用户都有内容可看。

---

## 🎤 四、面试话术

### Q1: 推荐系统的整体架构是怎样的？

> **A**: 我们采用经典的四层推荐架构：
> 1. **召回层**：5 路并行召回（Item-CF 协同过滤、内容标签匹配、热门、关注、地理位置），每路返回 100 条候选，合并去重约 300 条。使用 CompletableFuture + 独立线程池并行执行，设置 2 秒超时降级。
> 2. **粗排层**：加权打分（召回分 × 来源权重），Item-CF 权重最高(1.0)，热门最低(0.6)，取 Top 100。
> 3. **精排层**：当前用规则排序，预留了 ML 模型接口（TensorFlow Serving / ONNX Runtime）。
> 4. **重排层**：已读过滤（Redis Set 精确判断）+ 品类打散（同品类不超过 2 个连续），最终返回 20 条。

### Q2: 协同过滤怎么实现的？为什么选 Item-CF 而不是 User-CF？

> **A**: 
> - **实现**：离线定时任务（每 2 小时）计算物品共现矩阵 → 余弦相似度，存入 Redis ZSet。在线召回时，取用户最近 20 个正向交互笔记，对每个笔记查 Top 10 相似笔记，累计相似度排序。
> - **选择 Item-CF 的原因**：内容平台物品（笔记）相对稳定，用户行为变化快。Item-CF 的相似矩阵可以离线计算、Redis 缓存，实时性好。而 User-CF 需要实时计算用户相似度，计算量大且不稳定。
> - **优化**：只计算热门笔记（交互数 > 5）的相似矩阵，控制计算规模。每个物品只保留 Top 20 相似物品。

### Q3: 已读去重怎么做的？为什么不用 HyperLogLog？

> **A**: 
> - 使用 **Redis Set + SISMEMBER** 做已读判断。
> - **不用 HyperLogLog 的原因**：HyperLogLog 的 PFADD 是写操作，检测时会把元素也加入。这意味着在过滤阶段，所有候选笔记（包括被品类打散跳过的）都会被标记为"已读"，导致推荐池快速枯竭。这是一个**静默数据污染**问题。
> - **Set 的代价**：每用户约 50KB（7 天 × 每天推荐 20 条 × 多次刷新），可接受。如果用户量极大，可升级为 Redis Bloom Filter（BF.EXISTS 是纯读操作）。

### Q4: 冷启动问题怎么解决？

> **A**: 
> - **新用户冷启动**：自动检测（EXISTS 子查询，O(1)），降级为热门 60% + 地理 30% 推荐。
> - **新内容冷启动**：基于内容特征（标签/分类）推荐给匹配用户，不依赖行为数据。
> - **渐进式过渡**：随着用户行为积累，Item-CF 和内容召回逐步生效，热门权重自然降低。

### Q5: 分布式部署时怎么保证定时任务不重复执行？

> **A**: 所有定时任务（Item-CF 矩阵计算、特征提取、热门池刷新）都使用 **Redisson 分布式锁**。`tryLock(0, leaseTime)` 非阻塞获取，获取失败直接跳过。leaseTime 设置为任务最大执行时间的 1.5 倍，防止死锁。

---

## 📋 五、修复清单

| # | 严重程度 | 问题 | 状态 |
|---|:--------:|------|:----:|
| 1 | 🔴 P0 | HyperLogLog 已读检测副作用（PFADD 污染数据） | ✅ 已修复 → Redis Set |
| 2 | 🔴 P1 | 冷启动判断 COUNT(*) 全表扫描 | ✅ 已修复 → EXISTS |
| 3 | 🟡 P2 | ItemCF 矩阵更新非原子（DELETE+ADD 空窗期） | ✅ 已修复 → RENAME |
| 4 | 🟡 P2 | ThreadFactory count 非线程安全 | ✅ 已修复 → AtomicInteger |
| 5 | 🟡 P2 | BehaviorRequest 缺少参数校验 | ✅ 已修复 → @Valid |
| 6 | 🟡 P2 | ContentRecall LIKE 通配符注入 | ✅ 已修复 → ESCAPE |
| 7 | 🟢 P3 | 粗排注释与实现不一致 | ✅ 已修复 |
| 8 | 🟢 P3 | 未使用的 import | ✅ 已修复 |

---

## 📊 六、修复前后评分对比

| 维度 | 修复前 | 修复后 | 提升 |
|------|:------:|:------:|:----:|
| 数据一致性 | 8 | 10 | +2 |
| 性能优化 | 12 | 14 | +2 |
| 代码质量 | 13 | 15 | +2 |
| **总分** | **86** | **92** | **+6** |
