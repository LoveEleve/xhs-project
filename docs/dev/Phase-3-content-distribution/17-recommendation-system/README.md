# 推荐系统

> 所属服务：my-xhs-search (9011) | 开发阶段：Phase-3 | 状态：⏳ 待开发

---

## 🎯 一、需求分析

### 1.1 业务场景

推荐系统是"发现页"的核心引擎，采用经典的"召回 → 粗排 → 精排 → 重排"四层架构。召回层使用 5 路并行策略（协同过滤 Item-CF、内容召回 TF-IDF、热门召回、关注召回、地理位置召回 GeoHash），每路返回 100 条候选，合并去重约 300 条。粗排加权打分取 Top 100，精排预留 ML 模型接口（当前用规则排序），重排保证多样性（品类打散 + 已读过滤 + 去重）最终返回 20 条。冷启动策略：新用户基于热门 + 地理推荐。

### 1.2 功能边界

| 功能项 | 是否实现 | 说明 |
|--------|---------|------|
| 5 路召回 | ✅ | Item-CF / 内容 / 热门 / 关注 / 地理 |
| 粗排 | ✅ | 多路归并 + 加权打分 |
| 精排 | ✅ | 规则排序（预留 ML 接口） |
| 重排 | ✅ | 去重 + 多样性（品类打散）+ 已读过滤 |
| 冷启动 | ✅ | 新用户：热门 + 地理 + 注册兴趣标签 |
| 用户行为上报 | ✅ | 曝光/点击/停留时长 |
| 内容特征提取 | ✅ | @Scheduled 每小时提取标签/分类 |
| 相似笔记推荐 | ✅ | 基于内容特征的相似推荐 |

### 1.3 数据量预估

| 指标 | 预估值 | 计算依据 |
|------|--------|----------|
| 推荐请求 QPS | 10000 | 发现页是最高频页面 |
| 用户行为日志 | 100 亿/年 | 1000 万用户 × 每天 30 次行为 |
| 候选集大小 | 500 条/次 | 5 路 × 100 条 |
| 最终返回 | 20 条/次 | 经过四层筛选 |

---

## 🏗️ 二、架构设计

### 2.1 整体架构图

```
Client → Gateway → my-xhs-search(9011) [推荐引擎]
                        │
                        ├── Redis: 相似矩阵/用户特征/内容特征/已读记录
                        ├── Feign → my-xhs-content (笔记内容/标签)
                        ├── Feign → my-xhs-analytics (用户行为/关注关系)
                        ├── Feign → my-xhs-counter (热度计数)
                        └── MySQL: 用户行为日志/内容特征表
```

### 2.2 四层推荐架构

```
用户请求推荐 Feed
       │
       ▼
┌──────────────────────────────────────────────────────┐
│ 5路召回（CompletableFuture 并行）                       │
│  ┌──────────┐ ┌──────────┐ ┌────────┐ ┌────────┐ ┌────┐ │
│  │ Item-CF  │ │ 内容召回  │ │热门召回│ │关注召回│ │地理│ │
│  │ 100条    │ │ 100条    │ │ 100条  │ │ 100条  │ │100 │ │
│  └────┬─────┘ └────┬─────┘ └───┬────┘ └───┬────┘ └─┬──┘ │
│       └─────────────┴───────────┴───────────┴───────┘   │
│                        │ 合并去重 ≈300条                    │
│                        ▼                                   │
│              ┌─────────────────┐                          │
│              │   粗排（加权打分）│                          │
│              │   Top 100       │                          │
│              └────────┬────────┘                          │
│                       ▼                                   │
│              ┌─────────────────┐                          │
│              │   精排（规则/ML） │                          │
│              │   Top 50        │                          │
│              └────────┬────────┘                          │
│                       ▼                                   │
│              ┌─────────────────┐                          │
│              │   重排           │                          │
│              │ 去重+多样性+已读 │                          │
│              │   Top 20        │                          │
│              └─────────────────┘                          │
└──────────────────────────────────────────────────────┘
```

---

## 🗄️ 三、数据库设计

### 3.1 表结构

```sql
-- 用户行为日志表（预估 100 亿，按 user_id 分表）
CREATE TABLE IF NOT EXISTS t_user_behavior (
    id             BIGINT   NOT NULL COMMENT 'ID',
    user_id        BIGINT   NOT NULL COMMENT '用户ID',
    note_id        BIGINT   NOT NULL COMMENT '笔记ID',
    behavior_type  TINYINT  NOT NULL COMMENT '行为类型:1曝光2点击3点赞4收藏5评论6分享7停留',
    duration       INT      DEFAULT NULL COMMENT '停留时长(秒)',
    created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_user_id (user_id),
    INDEX idx_note_id (note_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户行为日志表';

-- 内容特征表
CREATE TABLE IF NOT EXISTS t_item_feature (
    id           BIGINT       NOT NULL COMMENT 'ID',
    note_id      BIGINT       NOT NULL COMMENT '笔记ID',
    tags         VARCHAR(512) DEFAULT NULL COMMENT '标签(JSON数组)',
    category     VARCHAR(64)  DEFAULT NULL COMMENT '分类',
    geohash      VARCHAR(12)  DEFAULT NULL COMMENT '地理位置GeoHash',
    quality_score DOUBLE      DEFAULT 0 COMMENT '内容质量分',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE INDEX uk_note_id (note_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='内容特征表';
```

---

## 🔑 四、缓存设计

### 4.1 Redis Key 规范

| Key Pattern | 类型 | 过期时间 | 说明 |
|-------------|------|----------|------|
| `recommend:itemcf:{noteId}` | ZSet | 24h | 相似物品集合（score=相似度） |
| `recommend:user:tags:{userId}` | Hash | 12h | 用户兴趣标签（field=tag, value=权重） |
| `recommend:hot:global` | ZSet | 1h | 全局热门 Top 100 |
| `recommend:following:latest:{userId}` | ZSet | 1h | 关注用户最新内容 |
| `recommend:geo:{geohash}` | ZSet | 1h | 同城内容（score=距离权重） |
| `recommend:seen:{userId}` | HyperLogLog | 7d | 用户已曝光内容（去重用） |
| `recommend:coldstart` | ZSet | 1h | 冷启动推荐池（新用户） |
| `recommend:feature:{noteId}` | Hash | 24h | 内容特征缓存（tags/category/geohash） |

---

## 📡 五、接口设计

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| GET | `/api/recommend/feed` | 个性化推荐 Feed（发现页） | ✅ |
| GET | `/api/recommend/similar/{noteId}` | 相似笔记推荐 | ❌ |
| POST | `/api/recommend/behavior` | 上报用户行为（曝光/点击/停留时长） | ✅ |
| GET | `/api/recommend/coldstart` | 冷启动推荐（新用户） | ✅ |

---

## 💻 六、核心代码实现

### 6.1 5 路召回并行执行

```java
/**
 * 5路召回并行执行，每路返回100条候选
 * 使用独立线程池，CompletableFuture 编排
 */
public List<RecallItem> multiRecall(Long userId) {
    // 5路并行召回
    CompletableFuture<List<RecallItem>> cfFuture = CompletableFuture
        .supplyAsync(() -> itemCFRecall.recall(userId, 100), recallPool);
    CompletableFuture<List<RecallItem>> contentFuture = CompletableFuture
        .supplyAsync(() -> contentRecall.recall(userId, 100), recallPool);
    CompletableFuture<List<RecallItem>> hotFuture = CompletableFuture
        .supplyAsync(() -> hotRecall.recall(userId, 100), recallPool);
    CompletableFuture<List<RecallItem>> followFuture = CompletableFuture
        .supplyAsync(() -> followingRecall.recall(userId, 100), recallPool);
    CompletableFuture<List<RecallItem>> geoFuture = CompletableFuture
        .supplyAsync(() -> geoRecall.recall(userId, 100), recallPool);

    // 等待全部完成
    CompletableFuture.allOf(cfFuture, contentFuture, hotFuture, followFuture, geoFuture).join();

    // 合并去重（按 noteId 去重，保留最高分）
    return RecallMerger.merge(
        cfFuture.join(), contentFuture.join(), hotFuture.join(),
        followFuture.join(), geoFuture.join());
}
```

### 6.2 协同过滤召回（Item-CF）

```java
/**
 * Item-CF 协同过滤召回
 * 原理：用户历史行为 → 找到相似物品 → 推荐相似物品
 * Redis 存储相似矩阵：recommend:itemcf:{noteId} → ZSet(相似noteId, 相似度)
 */
public class ItemCFRecallStrategy implements RecallStrategy {

    @Override
    public List<RecallItem> recall(Long userId, int size) {
        // 1. 获取用户最近交互的笔记（点赞/收藏/长停留）
        List<Long> recentNotes = getUserRecentInteractions(userId, 20);

        // 2. 对每个笔记，从相似矩阵中取 Top 10 相似笔记
        Set<Long> candidates = new HashSet<>();
        Map<Long, Double> scoreMap = new HashMap<>();

        for (Long noteId : recentNotes) {
            Set<ZSetOperations.TypedTuple<String>> similar = redisTemplate.opsForZSet()
                .reverseRangeWithScores("recommend:itemcf:" + noteId, 0, 9);

            if (similar != null) {
                for (ZSetOperations.TypedTuple<String> tuple : similar) {
                    Long candidateId = Long.valueOf(tuple.getValue());
                    candidates.add(candidateId);
                    scoreMap.merge(candidateId, tuple.getScore(), Double::sum);
                }
            }
        }

        // 3. 按累计相似度排序，取 Top size
        return scoreMap.entrySet().stream()
            .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
            .limit(size)
            .map(e -> new RecallItem(e.getKey(), e.getValue(), "ITEM_CF"))
            .collect(Collectors.toList());
    }
}
```

### 6.3 粗排（加权打分）

```java
/**
 * 粗排：多路归并 + 加权打分
 * 权重：热度 0.3 + 相似度 0.3 + 时效 0.2 + 距离 0.2
 */
public List<RecallItem> roughRank(List<RecallItem> candidates, Long userId) {
    for (RecallItem item : candidates) {
        double hotScore = getHotScore(item.getNoteId()) * 0.3;
        double simScore = item.getRecallScore() * 0.3;
        double freshScore = getFreshnessScore(item.getNoteId()) * 0.2;
        double geoScore = getGeoScore(item.getNoteId(), userId) * 0.2;

        item.setRankScore(hotScore + simScore + freshScore + geoScore);
    }

    return candidates.stream()
        .sorted(Comparator.comparingDouble(RecallItem::getRankScore).reversed())
        .limit(100)
        .collect(Collectors.toList());
}
```

### 6.4 重排（多样性 + 已读过滤）

```java
/**
 * 重排：去重 + 多样性（品类打散）+ 已读过滤
 * 规则：同品类不超过 2 个连续，已曝光的过滤掉
 */
public List<RecallItem> reRank(List<RecallItem> ranked, Long userId) {
    // 1. 已读过滤（HyperLogLog 判断）
    List<RecallItem> unread = ranked.stream()
        .filter(item -> !isAlreadySeen(userId, item.getNoteId()))
        .collect(Collectors.toList());

    // 2. 品类打散（同品类不超过 2 个连续）
    List<RecallItem> result = new ArrayList<>();
    Map<String, Integer> categoryCount = new HashMap<>();
    String lastCategory = null;
    int consecutiveCount = 0;

    for (RecallItem item : unread) {
        String category = getCategory(item.getNoteId());
        if (category.equals(lastCategory)) {
            consecutiveCount++;
            if (consecutiveCount >= 2) continue; // 同品类连续超过 2 个，跳过
        } else {
            consecutiveCount = 1;
            lastCategory = category;
        }
        result.add(item);
        if (result.size() >= 20) break;
    }

    // 3. 记录曝光（HyperLogLog）
    result.forEach(item ->
        redisTemplate.opsForHyperLogLog().add("recommend:seen:" + userId,
            String.valueOf(item.getNoteId())));

    return result;
}
```

### 6.5 冷启动策略

```java
/**
 * 冷启动：新用户无行为数据时的推荐策略
 * 策略：热门 60% + 同城 30% + 注册兴趣标签 10%
 */
public List<RecallItem> coldStartRecall(Long userId) {
    // 1. 热门召回 60%
    List<RecallItem> hot = hotRecall.recall(userId, 12);

    // 2. 地理位置召回 30%（同城内容）
    List<RecallItem> geo = geoRecall.recall(userId, 6);

    // 3. 注册时选择的兴趣标签召回 10%
    List<RecallItem> interest = interestTagRecall(userId, 2);

    // 合并
    List<RecallItem> result = new ArrayList<>();
    result.addAll(hot);
    result.addAll(geo);
    result.addAll(interest);
    return result;
}
```

---

## ⚖️ 七、方案对比

### 7.1 召回策略：单路 vs 多路

| 维度 | 单路召回 | 多路召回（✅ 选定） |
|------|---------|-------------------|
| 覆盖率 | 低（只覆盖一种偏好） | 高（多维度覆盖） |
| 多样性 | 差（信息茧房） | 好（不同策略互补） |
| 复杂度 | 低 | 中（需要合并去重） |
| 冷启动 | 差 | 好（热门/地理兜底） |

### 7.2 协同过滤：User-CF vs Item-CF

| 维度 | User-CF | Item-CF（✅ 选定） |
|------|---------|-------------------|
| 原理 | 找相似用户 → 推荐用户喜欢的 | 找相似物品 → 推荐物品 |
| 适用场景 | 用户少、物品多 | 物品少、用户多（电商/内容） |
| 实时性 | 差（用户行为变化快） | 好（物品相似度相对稳定） |
| 存储 | 用户×用户矩阵（大） | 物品×物品矩阵（小） |

**选择理由**：内容平台物品（笔记）相对稳定，用户行为变化快。Item-CF 相似矩阵可离线计算、Redis 缓存，实时性好。

---

## 🐛 八、踩坑记录

### 8.1 Item-CF 相似矩阵过大

- **现象**：5000 万笔记的相似矩阵存不下
- **解决**：只计算热门笔记（互动 > 100）的相似矩阵，冷门笔记走内容召回
- **教训**：相似矩阵需要控制规模，不能全量计算

### 8.2 HyperLogLog 已读判断误判

- **现象**：用户看过的笔记又被推荐
- **原因**：HyperLogLog 有 0.81% 误判率
- **解决**：可接受（推荐场景允许少量重复），严格去重用 Bloom Filter

### 8.3 冷启动效果差

- **现象**：新用户推荐的内容点击率很低
- **解决**：增加"注册时选择兴趣标签"步骤，冷启动时基于标签推荐

---

## 📊 九、测试验证

| 测试场景 | 输入 | 预期结果 | 通过 |
|----------|------|----------|------|
| 5 路召回 | 活跃用户 | 返回 ≈300 条候选（去重后） | ⬜ |
| 粗排 | 300 条候选 | 返回 Top 100 | ⬜ |
| 重排-已读过滤 | 已曝光的笔记 | 不出现在结果中 | ⬜ |
| 重排-品类打散 | 同品类连续 3 个 | 第 3 个被跳过 | ⬜ |
| 冷启动 | 新用户（无行为） | 返回热门 + 同城内容 | ⬜ |
| 行为上报 | 点击/停留 | 写入行为日志 | ⬜ |
| 相似推荐 | 指定 noteId | 返回相似笔记列表 | ⬜ |

---

## 🎤 十、面试考察点

### Q1: 推荐系统的整体架构是怎样的？

> 1. "经典四层架构：召回 → 粗排 → 精排 → 重排"
> 2. "召回层：5 路并行（Item-CF / 内容 / 热门 / 关注 / 地理），每路 100 条，合并去重 ≈300 条"
> 3. "粗排：加权打分（热度 0.3 + 相似度 0.3 + 时效 0.2 + 距离 0.2），取 Top 100"
> 4. "精排：预留 ML 模型接口，当前用规则排序"
> 5. "重排：去重 + 品类打散（同品类不超过 2 个连续）+ 已读过滤（HyperLogLog）"

### Q2: 协同过滤怎么实现的？有什么缺点？

> 1. "Item-CF：基于用户行为计算物品相似度，Redis ZSet 存储相似矩阵"
> 2. "流程：用户最近交互的 20 个笔记 → 每个笔记取 Top 10 相似 → 累计相似度排序"
> 3. "缺点：冷启动问题（新物品无行为数据）、数据稀疏（大部分用户只交互少量物品）"
> 4. "解决：冷启动走内容召回（TF-IDF 标签匹配），多路召回互补"

### Q3: 冷启动问题怎么解决？

> 1. "新用户冷启动：热门 60% + 同城 30% + 注册兴趣标签 10%"
> 2. "新内容冷启动：基于内容特征（标签/分类）推荐给匹配用户"
> 3. "渐进式：随着用户行为积累，逐步增加协同过滤权重，减少热门权重"
> 4. "分层策略：新用户纯热门 → 低活用户热门 60%+内容 30% → 活跃用户协同 40%+内容 30%"

---

## 📚 参考资料

| 资料 | 相关章节 | 参考内容 |
|------|----------|----------|
| 📄 phase-3/README.md | §3.17 | 推荐系统完整设计（5路召回/粗排精排/冷启动） |
| 📄 06-production-decision-and-expression-handbook.md | §6 | 推荐系统面试表达 |
