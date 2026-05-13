# 热搜榜

> 所属服务：my-xhs-search (9011) | 开发阶段：Phase-3 | 状态：⏳ 待开发

---

## 🎯 一、需求分析

### 1.1 业务场景

热搜榜实时统计最近 1 小时的搜索词频，使用 Redis ZSet + 滑动窗口计算热度。热度公式采用时间衰减算法：`Score = Σ(count × e^(-λ×Δt))`，近期搜索权重更高。支持反作弊策略（同 IP/同用户频率限制、异常波动检测），人工置顶/屏蔽热搜词，定时快照保存历史数据。

### 1.2 功能边界

| 功能项 | 是否实现 | 说明 |
|--------|---------|------|
| 实时热搜榜 Top 50 | ✅ | ZSet ZREVRANGE 实时排行 |
| 滑动窗口统计 | ✅ | Redis 分钟桶，统计最近 60 分钟 |
| 时间衰减算法 | ✅ | `e^(-λ×Δt)`，近期搜索权重更高 |
| 反作弊 | ✅ | 同用户/IP 频率限制 + 异常波动检测 |
| 人工置顶 | ✅ | 运营手动置顶热搜词 |
| 人工屏蔽 | ✅ | 屏蔽敏感/违规热搜词 |
| 快照持久化 | ✅ | 每 5 分钟快照到 MySQL |
| 历史热搜查询 | ✅ | 按日期查询历史快照 |
| 窗口自动清理 | ✅ | 过期分钟桶 TTL=2h 自然淘汰 |

### 1.3 数据量预估

| 指标 | 预估值 | 计算依据 |
|------|--------|----------|
| 搜索词记录 QPS | 8000 | 每次搜索触发一次记录 |
| 热搜词总量 | 10 万/天 | 去重后的搜索词 |
| 热搜榜查询 QPS | 5000 | 首页/搜索页展示 |
| 快照数据量 | 10 万/月 | 每 5 分钟 50 条 × 288 次/天 |

---

## 🏗️ 二、架构设计

### 2.1 整体架构图

```
Client → Gateway → my-xhs-search(9011)
                        │
                        ├── Redis: 滑动窗口分钟桶 + ZSet排行榜 + 反作弊限频
                        ├── MySQL: 热搜快照表（历史查询）
                        └── @Scheduled: 每5分钟重算热度 + 快照持久化
```

### 2.2 热度计算流程

```
用户搜索 → 记录搜索词
┌────────┐   ┌──────────────┐   ┌──────────────┐   ┌──────────────┐
│ 搜索请求│──▶│ 反作弊过滤   │──▶│ 写入滑动窗口 │──▶│ 定时计算热度 │
│        │   │ 用户/IP限频  │   │ HINCRBY      │   │ 衰减公式     │
└────────┘   └──────────────┘   └──────────────┘   └──────┬───────┘
                                                         │
                                              ZADD search:hot:realtime keyword score
                                              （每5分钟重算热度值并更新排行榜）
```

### 2.3 滑动窗口设计

```
分钟桶结构（最近 60 分钟）：

search:window:202405181030  → Hash { "穿搭": 150, "美食": 80, ... }  TTL=2h
search:window:202405181031  → Hash { "穿搭": 120, "旅行": 90, ... }  TTL=2h
search:window:202405181032  → Hash { "穿搭": 130, "美妆": 70, ... }  TTL=2h
...

热度计算（每5分钟）：
遍历最近60个分钟桶 → 对每个词累加 count × e^(-0.1 × 分钟差)
→ ZADD search:hot:realtime keyword totalScore
```

---

## 🗄️ 三、数据库设计

### 3.1 表结构

```sql
-- 热搜快照表（每5分钟持久化一次）
CREATE TABLE IF NOT EXISTS t_hot_search (
    id           BIGINT       NOT NULL COMMENT 'ID',
    keyword      VARCHAR(128) NOT NULL COMMENT '搜索词',
    score        DOUBLE       NOT NULL COMMENT '热度分数',
    rank_no      INT          NOT NULL COMMENT '排名（1-50）',
    snapshot_time DATETIME    NOT NULL COMMENT '快照时间',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_snapshot_time (snapshot_time),
    INDEX idx_keyword (keyword)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='热搜快照表';
```

---

## 🔑 四、缓存设计

### 4.1 Redis Key 规范

| Key Pattern | 类型 | 过期时间 | 说明 |
|-------------|------|----------|------|
| `search:window:{yyyyMMddHHmm}` | Hash | 2h | 分钟桶（field=keyword, value=count） |
| `search:hot:realtime` | ZSet | 1h | 实时热搜排行（score=热度值） |
| `search:hot:pinned` | Set | 永久 | 人工置顶的热搜词 |
| `search:hot:blocked` | Set | 永久 | 人工屏蔽的热搜词 |
| `search:hot:snapshot:{date}` | String(JSON) | 7d | 热搜快照缓存 |
| `search:antispam:user:{userId}` | String | 5min | 用户搜索频率限制（同一词 5 分钟内只计 1 次） |
| `search:antispam:ip:{ip}` | String | 1min | IP 搜索频率限制（每分钟 < 10 次） |

---

## 📡 五、接口设计

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| GET | `/api/search/hot` | 热搜榜 Top 50 | ❌ |
| POST | `/api/search/hot/record` | 记录搜索词（搜索时调用） | ❌ |
| PUT | `/api/search/hot/pin` | 人工置顶热搜词 | ✅（管理员） |
| PUT | `/api/search/hot/block` | 人工屏蔽热搜词 | ✅（管理员） |
| GET | `/api/search/hot/snapshot` | 历史热搜快照（按日期） | ❌ |

---

## 💻 六、核心代码实现

### 6.1 搜索词记录（含反作弊）

```java
/**
 * 记录搜索词到滑动窗口（含反作弊过滤）
 */
public void recordSearchKeyword(String keyword, Long userId, String ip) {
    // 1. 反作弊：同一用户同一词 5 分钟内只计 1 次
    String userKey = "search:antispam:user:" + userId + ":" + keyword.hashCode();
    if (Boolean.TRUE.equals(redisTemplate.hasKey(userKey))) {
        return; // 重复搜索，不计入
    }
    redisTemplate.opsForValue().set(userKey, "1", 5, TimeUnit.MINUTES);

    // 2. 反作弊：同一 IP 每分钟搜索 < 10 次
    String ipKey = "search:antispam:ip:" + ip;
    Long ipCount = redisTemplate.opsForValue().increment(ipKey);
    if (ipCount == 1) {
        redisTemplate.expire(ipKey, 1, TimeUnit.MINUTES);
    }
    if (ipCount > 10) {
        return; // IP 频率超限
    }

    // 3. 写入当前分钟桶
    String bucketKey = "search:window:" + LocalDateTime.now().format(
        DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
    redisTemplate.opsForHash().increment(bucketKey, keyword, 1);
    redisTemplate.expire(bucketKey, 2, TimeUnit.HOURS); // 2 小时过期
}
```

### 6.2 热度计算（时间衰减）

```java
/**
 * 每 5 分钟重算热度值
 * 公式：Score = Σ(count × e^(-λ×Δt))
 * λ = 0.1，Δt = 分钟差
 * 效果：1分钟前权重≈0.9，30分钟前权重≈0.05，60分钟前权重≈0.002
 */
@Scheduled(fixedRate = 300000) // 每 5 分钟
public void calculateHotSearch() {
    Map<String, Double> scoreMap = new HashMap<>();
    LocalDateTime now = LocalDateTime.now();

    // 1. 遍历最近 60 个分钟桶
    for (int i = 0; i < 60; i++) {
        String bucketKey = "search:window:" + now.minusMinutes(i)
            .format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));

        Map<Object, Object> entries = redisTemplate.opsForHash().entries(bucketKey);
        if (entries.isEmpty()) continue;

        // 2. 时间衰减：e^(-0.1 × i)
        double decay = Math.exp(-0.1 * i);

        entries.forEach((keyword, count) -> {
            double score = Integer.parseInt(count.toString()) * decay;
            scoreMap.merge(keyword.toString(), score, Double::sum);
        });
    }

    // 3. 过滤屏蔽词
    Set<Object> blocked = redisTemplate.opsForSet().members("search:hot:blocked");
    if (blocked != null) {
        blocked.forEach(word -> scoreMap.remove(word.toString()));
    }

    // 4. 写入 ZSet 排行榜
    String hotKey = "search:hot:realtime";
    redisTemplate.delete(hotKey);
    scoreMap.forEach((keyword, score) ->
        redisTemplate.opsForZSet().add(hotKey, keyword, score));
    redisTemplate.expire(hotKey, 1, TimeUnit.HOURS);

    // 5. 快照持久化到 MySQL
    snapshotToDatabase(scoreMap);
}
```

### 6.3 获取热搜榜（含置顶）

```java
/**
 * 获取热搜榜 Top 50（置顶词优先展示）
 */
public List<HotSearchVO> getHotSearchList() {
    // 1. 获取置顶词
    Set<Object> pinned = redisTemplate.opsForSet().members("search:hot:pinned");

    // 2. 获取实时排行 Top 50
    Set<ZSetOperations.TypedTuple<String>> hotSet = redisTemplate.opsForZSet()
        .reverseRangeWithScores("search:hot:realtime", 0, 49);

    // 3. 置顶词排在最前面，其余按热度排序
    List<HotSearchVO> result = new ArrayList<>();
    int rank = 1;

    // 置顶词
    if (pinned != null) {
        for (Object word : pinned) {
            result.add(new HotSearchVO(rank++, word.toString(), 0.0, true));
        }
    }

    // 实时热搜（排除已置顶的）
    if (hotSet != null) {
        for (ZSetOperations.TypedTuple<String> tuple : hotSet) {
            if (pinned != null && pinned.contains(tuple.getValue())) continue;
            if (rank > 50) break;
            result.add(new HotSearchVO(rank++, tuple.getValue(), tuple.getScore(), false));
        }
    }

    return result;
}
```

---

## ⚖️ 七、方案对比

### 7.1 热度计算：滑动窗口 vs 固定窗口 vs 实时计算

| 维度 | 滑动窗口（✅ 选定） | 固定窗口 | 实时计算 |
|------|-------------------|---------|---------|
| 平滑性 | 高（无突变） | 低（窗口切换时突变） | 高 |
| 性能 | 中（定时批量计算） | 高 | 低（每次搜索都计算） |
| 复杂度 | 中 | 低 | 高 |
| 时效性 | 5 分钟延迟 | 窗口周期延迟 | 实时 |

**选择理由**：滑动窗口平滑无突变，5 分钟延迟对热搜场景可接受。固定窗口在窗口切换时热搜会突变（如整点清零），体验差。

### 7.2 时间衰减：指数衰减 vs 线性衰减

| 维度 | 指数衰减 `e^(-λt)`（✅ 选定） | 线性衰减 `1 - t/T` |
|------|------------------------------|-------------------|
| 近期敏感度 | 高（近期权重变化快） | 低（均匀下降） |
| 远期影响 | 趋近于 0（自然淘汰） | 到 T 时刻突然归零 |
| 物理意义 | 符合"热度自然冷却"直觉 | 人为设定截止时间 |

---

## 🐛 八、踩坑记录

### 8.1 热搜被刷（水军攻击）

- **现象**：某个词突然冲上热搜第一，但实际搜索量很低
- **原因**：同一 IP 大量请求，绕过了用户限频（未登录用户无 userId）
- **解决**：增加 IP 维度限频（每分钟 < 10 次）+ 环比增长 > 500% 标记异常

### 8.2 分钟桶 Key 过多导致 Redis 内存暴涨

- **现象**：Redis 内存持续增长
- **原因**：分钟桶 TTL 设置过长（24h），积累了大量过期桶
- **解决**：TTL 缩短为 2h（只需最近 60 分钟数据），过期桶自然淘汰

---

## 📊 九、测试验证

| 测试场景 | 输入 | 预期结果 | 通过 |
|----------|------|----------|------|
| 搜索词记录 | 正常搜索 | 写入分钟桶 | ⬜ |
| 反作弊-用户限频 | 同一用户同一词 5 分钟内搜 2 次 | 只计 1 次 | ⬜ |
| 反作弊-IP 限频 | 同一 IP 1 分钟内搜 11 次 | 第 11 次不计入 | ⬜ |
| 热度计算 | 等待 5 分钟 | ZSet 更新热度值 | ⬜ |
| 时间衰减 | 30 分钟前的搜索 | 权重 ≈ 0.05 | ⬜ |
| 人工置顶 | 置顶"活动" | 排在第 1 位 | ⬜ |
| 人工屏蔽 | 屏蔽"违规词" | 从排行榜消失 | ⬜ |
| 快照持久化 | 等待 5 分钟 | MySQL 写入 Top 50 | ⬜ |

---

## 🎤 十、面试考察点

### Q1: 热搜榜的热度怎么计算的？

> 1. "滑动窗口 + 时间衰减：Redis 分钟桶记录每分钟每个词的搜索次数"
> 2. "每 5 分钟定时任务遍历最近 60 个分钟桶，累加 `count × e^(-0.1×Δt)`"
> 3. "效果：1 分钟前权重 0.9，30 分钟前权重 0.05，60 分钟前权重 0.002"
> 4. "结果写入 ZSet，ZREVRANGE 0 49 取 Top 50"

### Q2: 怎么防止热搜被刷？

> 1. "用户维度：同一用户同一词 5 分钟内只计 1 次（Redis SET NX 5min）"
> 2. "IP 维度：同一 IP 每分钟搜索 < 10 次（Redis INCR + EXPIRE 1min）"
> 3. "异常检测：环比增长 > 500% 标记异常，人工审核"
> 4. "人工干预：运营可置顶/屏蔽热搜词"

### Q3: 时间衰减为什么用指数衰减？

> 1. "指数衰减 `e^(-λt)` 符合'热度自然冷却'的物理直觉"
> 2. "近期变化快（1 分钟前 0.9 → 10 分钟前 0.37），远期趋近于 0"
> 3. "vs 线性衰减：线性衰减到截止时间突然归零，不自然"
> 4. "λ=0.1 是经验值，可根据业务调整（λ 越大衰减越快）"

---

## 📚 参考资料

| 资料 | 相关章节 | 参考内容 |
|------|----------|----------|
| 📄 phase-3/README.md | §3.16 | 热搜榜完整设计（滑动窗口/衰减/反作弊） |
| 📄 06-production-decision-and-expression-handbook.md | §7 | 热搜榜面试表达 |
