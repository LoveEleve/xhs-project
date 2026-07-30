# Search 推荐 4 层 Pipeline — 深度技术分析

> 关联源码：`RecommendService.java` / `RecallStrategy.java` / `ItemCFRecallStrategy.java` / `ContentRecallStrategy.java` / `HotRecallStrategy.java` / `FollowingRecallStrategy.java` / `GeoRecallStrategy.java`

---

## 业务背景

推荐系统的核心矛盾：**全量召回不可能精确，精确排序不可能全量**。

```
全量 × 精确 = 不可行（100 万笔记 × 深度学习 = 分钟级）
少量 × 精确 = 可行（500 条候选 × 规则排序 = 毫秒级）
```

四层 Pipeline 解耦方案：

```
召回（500 条）→ 粗排（100 条）→ 精排（50 条）→ 重排（20 条）
```

每层缩小候选集规模，同时增加计算复杂度。

---

## 架构图

```
                           ┌─────────────┐
                           │ 冷启动判断    │
                           │ EXISTS 行为表 │
                           └──────┬──────┘
                                  │
              ┌───────────────────┼───────────────────┐
              ▼                   ▼                    ▼
       ┌──────────────┐   ┌──────────────────┐
       │ 冷启动用户     │   │ 正常用户          │
       │ Hot(60%)      │   │ 5 路并行召回      │
       │ Geo(30%)      │   │ ItemCF/Content/  │
       │               │   │ Hot/Following/Geo│
       └──────┬───────┘   │ 各 100 条         │
              │           └────────┬─────────┘
              └──────────────────┬─┘
                                 │
                    ┌────────────▼────────────┐
                    │    粗排（Top 100）       │
                    │  score = recallScore ×  │
                    │  sourceWeight           │
                    │  ItemCF(1.0) > Following │
                    │  (0.9) > Content(0.8)   │
                    │  > Hot(0.6) > Geo(0.5)  │
                    └────────────┬────────────┘
                                 │
                    ┌────────────▼────────────┐
                    │    精排（Top 50）        │
                    │  score = source(25%)    │
                    │  + preference(25%)      │
                    │  + quality(30%)         │
                    │  + timeDecay(20%)       │
                    └────────────┬────────────┘
                                 │
                    ┌────────────▼────────────┐
                    │    重排（Final 20）      │
                    │  Seen 已读过滤           │
                    │  Category 品类打散       │
                    │  Redis Set 记录曝光      │
                    └────────────┬────────────┘
                                 │
                    ┌────────────▼────────────┐
                    │  RecommendFeedVO[]      │
                    │  + reason 推荐理由       │
                    └─────────────────────────┘
```

---

## 召回层

### 5 路并行

所有召回策略实现 `RecallStrategy` 接口：

```java
public interface RecallStrategy {
    String name();
    List<RecallItem> recall(Long userId, int size);
}
```

通过 Spring DI 自动注入所有实现类，CompletableFuture 并行执行：

```java
List<CompletableFuture<List<RecallItem>>> futures = recallStrategies.stream()
    .map(strategy -> CompletableFuture
        .supplyAsync(() -> strategy.recall(userId, recallSizePerStrategy), recallExecutor)
        .exceptionally(ex -> { log.warn("[推荐] {}召回异常", strategy.name()); return List.of(); }))
    .collect(Collectors.toList());

CompletableFuture.allOf(futures.toArray()).get(2000, TimeUnit.MILLISECONDS);
```

**超时控制**：2 秒超时，超时的路返回空列表，不影响其他路。

### 5 种召回策略

| 策略 | 数据源 | 原理 | 适用场景 |
|---|---|---|---|
| ItemCF | Redis ZSet | 用户正反馈笔记的相似矩阵 | 个性化推荐 |
| Content | MySQL 标签 LIKE | 用户兴趣标签匹配物品标签 | 新笔记冷启动 |
| Hot | Redis ZSet | 全局热门池 | 非个性化保底 |
| Following | Redis ZSet | 关注者最新发布 | 社交关系推荐 |
| Geo | MySQL GeoHash | 同城内容 | LBS 推荐 |

### 合并去重

```java
Map<Long, RecallItem> mergedMap = new LinkedHashMap<>();
for (RecallItem item : items) {
    mergedMap.merge(item.getNoteId(), item, (existing, incoming) ->
            incoming.getRecallScore() > existing.getRecallScore() ? incoming : existing);
}
```

同一篇笔记可能被多路召回（如 ItemCF 和 Hot 都召回同一篇），按 recallScore 取最高分。

---

## 粗排

```java
for (RecallItem item : candidates) {
    double sourceWeight = SOURCE_WEIGHT.getOrDefault(item.getSource(), 0.5);
    item.setRankScore(item.getRecallScore() * sourceWeight);
}
return candidates.stream()
        .sorted(Comparator.comparingDouble(RecallItem::getRankScore).reversed())
        .limit(100)
        .collect(Collectors.toList());
```

**权重设计**：

| 来源 | 权重 | 理由 |
|---|---|---|
| ItemCF | 1.0 | 协同过滤最个性化，优先展示 |
| Following | 0.9 | 社交关系强信号 |
| Content | 0.8 | 基于兴趣标签，信号中等 |
| Hot | 0.6 | 热门保底，但缺少个性化 |
| Geo | 0.5 | 地理位置弱信号 |

---

## 精排

### 4 维加权

```java
item.setRankScore(
    0.25 * sourceWeight +     // 来源权重
    0.25 * userPreference +   // 用户偏好
    0.30 * quality +          // 内容质量
    0.20 * timeDecay          // 时效衰减
);
```

**内容质量分**：从 `t_item_feature.quality_score` 读取，归一化到 0~1。包含点赞/收藏/评论等指标的融合。

**时效衰减**：

```java
score = e^(-hours / 24)
```

| 发布时间 | 衰减系数 |
|---|---|
| 刚刚 | 1.0 |
| 24 小时 | 0.37 |
| 48 小时 | 0.14 |
| 7 天 | ~0.001 |

### 预留 ML 接口

当前精排使用规则加权，代码预留了 ML 模型接口。后续可接入。排序模型替换为 DNN（如 DIN/DIEN），只需修改 `fineRank()` 方法。

---

## 重排

### 已读过滤

```java
String seenKey = "recommend:seen:" + userId;
// 过滤：SISMEMBER 判断是否已曝光
// 记录：SADD 将本次推荐笔记加入 Set
// TTL: 7 天过期
```

为什么用 Set 而不是 HyperLogLog：
- HyperLogLog 的 PFADD 会将检测元素加入集合，导致"检测即曝光"的副作用
- Set 支持 `SISMEMBER` 精确判断
- 单用户 7 天曝光记录约 1000~5000 条，Set 占 ~50KB，可接受

### 品类打散

```java
// 同品类不超过 2 个连续
if (category.equals(lastCategory)) {
    consecutiveCount++;
    if (consecutiveCount >= 2) continue; // 跳过
} else {
    consecutiveCount = 1;
    lastCategory = category;
}
```

防止连续推荐同品类内容（如连续 5 篇美食笔记），提升浏览体验。

---

## 冷启动

```java
private boolean isColdStartUser(Long userId) {
    return !EXISTS(SELECT 1 FROM t_user_behavior WHERE user_id = ?);
}
```

冷启动用户没有行为数据，ItemCF 无法工作。降级为：
- 热门 60%（HotRecall）
- 地理 30%（GeoRecall）

共 90 条候选，不补齐到 100 条——冷启动用户对推荐多样性要求低，90 条够用。

---

## 行为上报

```
POST /api/recommend/behavior
    ↓（MQ 异步）
RocketMQ RECOMMEND_BEHAVIOR_TOPIC
    ↓
BehaviorReportConsumer → INSERT t_user_behavior
    ↓
正向行为（点赞/收藏/评论/分享/停留>10s）
    ↓
updateUserInterestTags → HSET recommend:user_tags:{userId}
```

**MQ 降级**：MQ 发送失败时同步写 MySQL，保证行为不丢失。

**行为类型**：

| code | 类型 | 正向行为 |
|---|---|---|
| 1 | 曝光 | 否 |
| 2 | 点击 | 否 |
| 3 | 点赞 | ✅ |
| 4 | 收藏 | ✅ |
| 5 | 评论 | ✅ |
| 6 | 分享 | ✅ |
| 7 | 停留 | ✅ (>10s) |

---

## 面试 Q&A

**Q: 4 层流程为什么要分这么多层？**
A: 每层解决不同的问题。召回保证覆盖面（500 条），粗排快速过滤（100 条），精排精确排序（50 条），重排保证体验（20 条）。如果把精排直接应用于 500 条，查询 ES 质量分的开销增加 5 倍。

**Q: Item-CF 相似矩阵怎么计算的？**
A: XXL-Job 定时任务每 2 小时运行一次 `computeItemCFMatrix()`。遍历用户行为表，统计笔记间的共现次数（用户 A 同时喜欢笔记 X 和 Y → X-Y 相似度 +1），用 Jaccard 系数归一化后写入 Redis ZSet `recommend:itemcf:{noteId}`。

**Q: 冷启动用户的推荐怎么做？**
A: 冷启动用户无行为数据，ItemCF/Content 召回无效。降级为 Hot(60%) + Geo(30%) + 随机(10%)。一旦用户产生正向行为，立即增量更新兴趣标签。

**Q: 已读过滤为什么用 Redis Set 不用 HyperLogLog？**
A: HyperLogLog 的 PFADD 会把检测的元素也加入集合——如果用 PFADD 判断"这篇笔记是否已曝光"，判断本身就把笔记标记为已曝光了。Set 的 SISMEMBER 没有这个副作用。

---

## 生产实验

当前无用户行为数据，推荐系统处于冷启动状态：

| 场景 | 结果 | 说明 |
|---|---|---|
| 推荐 Feed | 返回 0 条 | 所有召回策略返回空（无行为/无热门/无关注/无 Geo） |
| 相似笔记 | 返回 0 条 | Item-CF 矩阵未计算 |
| 行为上报 | 已写入 MQ | t_user_behavior 表待验证 |

---

## 发散

### ML 模型精排

当前精排用规则加权，精度有限。后续可替换为：
- **XGBoost/LightGBM**：离线训练，线上加载模型预测 CTR
- **DIN (Deep Interest Network)**：淘宝提出的深度兴趣网络，建模用户兴趣演化
- **Embedding 召回**：用双塔模型替代 Item-CF，解决冷启动

### 多样性优化

当前品类打散只限制同品类连续数（max 2）。更通用的做法是 **MMR (Maximal Marginal Relevance)**：在相关性和多样性之间做平衡。
