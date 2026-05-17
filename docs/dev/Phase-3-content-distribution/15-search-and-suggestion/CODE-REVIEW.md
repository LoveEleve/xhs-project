# 15-搜索与搜索建议 Code Review

> 模块：my-xhs-search | 端口：9011 | 审查时间：2026-05-14

---

## 📊 一、P8 评分表

| 维度 | 满分 | 得分 | 评价 |
|------|:----:|:----:|------|
| 架构设计 | 20 | 18 | ES 8.x Java Client + Completion Suggester + Canal 增量同步，技术选型正确 |
| 并发安全 | 20 | 17 | Lua 脚本原子记录搜索历史、ES upsert 幂等、MQ 消费可重试/不可重试分类处理 |
| 性能优化 | 15 | 14 | Search After 深分页、Completion Suggester FST 加速、搜索建议 Redis 缓存 |
| 容错降级 | 15 | 12 | ES 查询失败返回空结果、索引初始化容忍 ES 未启动；但缺少 ES 集群健康检查和降级策略 |
| 代码质量 | 10 | 9 | 注释详尽、Builder 模式构建查询、泛型 SearchResultVO；少量重复代码可抽取 |
| 可观测性 | 10 | 8 | 关键路径日志完整、ES 查询耗时（took）返回给前端；缺少慢查询告警 |
| 分布式考量 | 10 | 9 | Canal Binlog 同步保证最终一致性、MQ 重试机制、搜索历史 Lua 原子操作 |
| **总分** | **100** | **87** | **优秀（P7+ 水平）** |

---

## 🔍 二、问题发现与修复建议

### 问题 1：NoteSearchService 和 ProductSearchService 大量重复代码 ⚠️

**位置**：`NoteSearchService.java` 和 `ProductSearchService.java`

**问题**：两个搜索服务有大量相似逻辑：
- `parseSearchAfter()` 方法完全相同
- `normalizeSize()` 方法完全相同
- `toLong()` 方法完全相同
- 构建结果的模式相同（遍历 hits → 构建 VO → 记录 searchAfter → 返回 SearchResultVO）

**修复建议**：抽取 `AbstractSearchService` 基类：
```java
public abstract class AbstractSearchService {
    protected List<FieldValue> parseSearchAfter(String searchAfter) { ... }
    protected int normalizeSize(Integer size, int defaultSize, int maxSize) { ... }
    protected Long toLong(Object obj) { ... }
}
```

**严重程度**：🟢 低（不影响功能，但违反 DRY 原则）

---

### 问题 2：搜索建议缓存 Key 使用 MD5 存在哈希碰撞风险

**位置**：`SuggestService.java` L75

**问题**：`DigestUtils.md5DigestAsHex(prefix.getBytes())` 作为缓存 Key 后缀。MD5 碰撞概率极低（2^128），但从安全角度看 MD5 已被破解。更重要的是，碰撞时会返回错误的搜索建议。

**评价**：✅ 可接受。搜索建议场景对安全性无要求，MD5 碰撞概率在实际业务中可忽略。使用 MD5 的目的是防止特殊字符和过长 Key，这是合理的。

---

### 问题 3：IndexInitializer 启动时创建索引可能与生产运维冲突 ⚠️

**位置**：`IndexInitializer.java`

**问题**：应用启动时自动创建索引，如果生产环境已有索引且 Mapping 需要变更，自动创建不会更新已有索引的 Mapping（ES 不允许修改已有字段类型）。

**当前处理**：代码中已有注释"生产环境建议通过运维脚本管理索引"，且 `createIndexIfNotExists` 只在索引不存在时创建。

**评价**：✅ 可接受（开发环境便利性设计，生产环境应禁用）。建议增加配置开关：
```yaml
search:
  index:
    auto-create: true  # 生产环境设为 false
```

---

### 问题 4：NoteIndexSyncConsumer 缺少版本号/时间戳防乱序 ⚠️

**位置**：`NoteIndexSyncConsumer.java` L100-120

**问题**：Canal 发送的 Binlog 消息可能因 MQ 重试、网络延迟等原因乱序到达。如果先到达 UPDATE（新数据），后到达旧的 UPDATE（旧数据），ES 中会被旧数据覆盖。

**分析**：
- ES `IndexRequest` 使用 noteId 作为文档 ID，每次都是全量覆盖
- 如果消息 A（时间 T1）和消息 B（时间 T2，T2 > T1）乱序，B 先到 A 后到，最终 ES 中是 A 的旧数据

**修复建议**：使用 ES 的 `version_type=external` + Binlog position/timestamp 作为版本号：
```java
esClient.index(IndexRequest.of(i -> i
    .index(noteIndexName)
    .id(String.valueOf(noteId))
    .versionType(VersionType.External)
    .version(binlogTimestamp)  // 使用 Binlog 时间戳作为版本号
    .withJson(new StringReader(jsonDoc))));
```

**严重程度**：🟡 中（乱序概率低，但一旦发生会导致搜索结果与实际数据不一致）

---

### 问题 5：搜索历史与搜索建议的 Key 前缀不统一

**位置**：`NoteSearchService.java` L200 vs `SearchHistoryService.java` L30

**问题**：
- `NoteSearchService` 中搜索历史 Key：`"myxhs:search:history:" + userId`
- `SearchHistoryService` 中搜索历史 Key：`HISTORY_KEY_PREFIX + userId`（值为 `"myxhs:search:history:"`）

两处使用相同的 Key 前缀，但 `NoteSearchService` 硬编码了字符串而非引用常量。

**修复建议**：统一使用 `RedisKeyConstants` 中的常量。

**严重程度**：🟢 低（功能正确，但维护性差）

---

### 问题 6：ES 客户端缺少连接池预热和健康检查

**位置**：`ElasticsearchConfig.java`

**问题**：RestClient 配置了连接池参数（maxConnTotal=100, maxConnPerRoute=50），但没有：
1. 启动时预热连接（首次请求会有连接建立延迟）
2. 定期健康检查（ES 节点故障时无法快速感知）

**修复建议**：
```java
// 添加 keep-alive 策略
httpClientBuilder.setKeepAliveStrategy((response, context) -> 30000); // 30s

// 启动时预热：发送一个简单的 cluster health 请求
@PostConstruct
public void warmUp() {
    try { esClient.cluster().health(); } catch (Exception ignored) {}
}
```

**严重程度**：🟢 低（不影响功能，影响冷启动性能）

---

## ✅ 三、技术亮点

### 亮点 1：ES 8.x Java Client 类型安全 API

使用 `co.elastic.clients` 新版客户端（非已废弃的 RestHighLevelClient），Builder 模式构建查询，编译期类型检查：
```java
boolBuilder.must(q -> q.multiMatch(mm -> mm
    .query(request.getKeyword())
    .fields("title^3", "content")
    .analyzer("ik_smart")));
```

### 亮点 2：Search After 深分页（替代 from+size）

ES 的 `from + size` 分页在深翻页时性能急剧下降（需要协调所有分片排序前 from+size 条数据）。Search After 基于上一页最后一条的排序值定位，性能恒定：
```java
searchBuilder.searchAfter(sortValues);  // 基于上一页最后一条的 sort values
```

### 亮点 3：Completion Suggester + Redis 缓存双层加速

- **ES Completion Suggester**：基于 FST 数据结构，前缀匹配性能比普通 prefix 查询高 10 倍+
- **Redis 缓存**：热门前缀命中率高（"小红"、"穿搭"等），1 小时 TTL
- **空结果短缓存**：5 分钟 TTL，防止缓存穿透

### 亮点 4：Canal Binlog 增量同步 + 可重试/不可重试异常分类

```java
// 可重试异常（ES 连接失败）→ 抛出触发 MQ 重试（最多 3 次）
catch (ElasticsearchException | IOException e) {
    throw new RuntimeException("可重试", e);
}
// 不可重试异常（数据格式错误）→ 记录日志，跳过，不阻塞消费进度
catch (Exception e) {
    log.error("不可重试，跳过", e);
}
```

### 亮点 5：搜索历史 Lua 脚本原子操作

```lua
redis.call('LREM', KEYS[1], 0, ARGV[1])   -- 先删除已存在的相同关键词
redis.call('LPUSH', KEYS[1], ARGV[1])      -- 插入头部
redis.call('LTRIM', KEYS[1], 0, 19)        -- 保留最新 20 条
redis.call('EXPIRE', KEYS[1], 2592000)     -- 30 天过期
```
四步操作原子执行，避免分布式多实例并发下的重复/超长问题。

### 亮点 6：IK 分词器双模式配置

- **索引时**：`ik_max_word`（最细粒度分词，"中华人民共和国" → "中华人民共和国/中华人民/中华/华人/人民共和国/人民/共和国/共和/国"）
- **搜索时**：`ik_smart`（智能分词，"中华人民共和国" → "中华人民共和国"）
- 索引时细分保证召回率，搜索时粗分保证精确度

---

## 🎤 四、面试话术

### Q1：搜索系统的整体架构是怎样的？

**A**：我们的搜索系统基于 Elasticsearch 8.x 构建，整体架构分为三层：

1. **数据同步层**：通过 Canal 监听 MySQL Binlog，将笔记/商品变更事件发送到 RocketMQ，搜索服务消费后同步到 ES。延迟目标 < 5 秒。
2. **搜索服务层**：基于 ES 8.x Java Client 构建查询，支持 multi_match 全文搜索（标题权重 3x）、filter 过滤、多维排序、Search After 深分页、高亮。
3. **搜索建议层**：基于 ES Completion Suggester（FST 数据结构）实现输入联想，热门前缀结果缓存到 Redis（1 小时 TTL）。

### Q2：为什么用 Search After 而不是 from+size 分页？

**A**：ES 的 `from + size` 分页有深翻页性能问题。例如 `from=10000, size=20`，ES 需要在每个分片上取前 10020 条，协调节点合并所有分片的 10020 × N 条数据后取第 10000-10020 条。分片越多、翻页越深，性能越差。

Search After 基于上一页最后一条文档的排序值（sort values）定位，每个分片只需从该位置开始取 size 条，性能恒定，与翻页深度无关。代价是不支持跳页（只能顺序翻页），但 Feed 流和搜索结果列表天然是顺序浏览的。

### Q3：搜索建议（自动补全）是怎么实现的？性能如何保证？

**A**：两层加速：

1. **ES Completion Suggester**：底层使用 FST（有限状态转换器）数据结构，将所有候选词构建为一个紧凑的有向无环图，前缀匹配时间复杂度 O(前缀长度)，比普通 prefix 查询快 10 倍+。
2. **Redis 缓存**：搜索建议 QPS 极高（每输入一个字符触发一次），热门前缀（"小红"、"穿搭"）命中率高。缓存 1 小时 TTL，空结果缓存 5 分钟防穿透。

### Q4：数据同步如何保证一致性？消息乱序怎么处理？

**A**：
- **最终一致性**：Canal 监听 MySQL Binlog → RocketMQ → 搜索服务消费写入 ES。正常延迟 < 5 秒。
- **幂等性**：ES IndexRequest 使用业务 ID（noteId）作为文档 ID，重复写入为 upsert 语义。
- **异常分类**：ES 通信异常（可重试）→ 抛出触发 MQ 重试（最多 3 次）；数据格式异常（不可重试）→ 记录日志跳过，不阻塞消费进度。
- **乱序防护**：可通过 ES 的 `version_type=external` + Binlog timestamp 作为版本号，旧版本写入会被 ES 拒绝。

### Q5：搜索历史如何保证分布式环境下的原子性？

**A**：使用 Redis Lua 脚本将 LREM + LPUSH + LTRIM + EXPIRE 四步操作原子执行。在分布式多实例环境下，如果不用 Lua 脚本，可能出现：
- 实例 A 执行 LREM 后、LPUSH 前，实例 B 也执行了 LREM + LPUSH，导致同一关键词出现两次
- LTRIM 和 LPUSH 之间有其他实例插入，导致列表超过 20 条上限

Lua 脚本在 Redis 单线程中原子执行，彻底避免这些竞态条件。

---

## 📁 五、文件清单

| 文件 | 行数 | 职责 |
|------|:----:|------|
| NoteSearchService.java | 269 | 笔记全文搜索：multi_match + filter + 排序 + Search After + 高亮 |
| ProductSearchService.java | 239 | 商品搜索：关键词 + 分类 + 价格区间 + 多维排序 |
| SuggestService.java | 140 | 搜索建议：Completion Suggester + Redis 缓存 |
| SearchHistoryService.java | 57 | 搜索历史：Redis List + Lua 原子操作 |
| HotSearchService.java | 18.27KB | 热搜排行榜（已在 16 模块 Review） |
| NoteIndexSyncConsumer.java | 132 | Canal 笔记变更 → ES 索引同步 |
| ProductIndexSyncConsumer.java | ~130 | Canal 商品变更 → ES 索引同步 |
| SearchController.java | 178 | REST 接口：搜索 + 建议 + 历史 + 热搜 |
| ElasticsearchConfig.java | 77 | ES 客户端配置：连接池 + 超时 |
| IndexInitializer.java | 144 | 启动时自动创建索引 + Mapping 定义 |
| application.yml | 120 | 配置：ES 地址 + 搜索参数 + 热搜参数 |

---

## 🏆 六、总结

**15-搜索与搜索建议**模块完整实现了内容平台的搜索能力，涵盖：
- ES 8.x 全文搜索（multi_match + IK 分词 + 高亮）
- Search After 深分页（替代 from+size）
- Completion Suggester 搜索建议（FST + Redis 缓存）
- Canal Binlog 增量同步（可重试/不可重试异常分类）
- 搜索历史 Lua 原子操作

主要改进方向：
1. 抽取搜索服务基类消除重复代码
2. 增加 ES 版本号防乱序
3. 增加索引自动创建的配置开关
4. 增加 ES 慢查询告警和健康检查
