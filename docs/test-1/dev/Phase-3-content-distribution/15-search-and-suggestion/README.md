# 搜索与搜索建议

> 所属服务：my-xhs-search (9011) | 开发阶段：Phase-3 | 状态：⏳ 待开发

---

## 🎯 一、需求分析

### 1.1 业务场景

搜索是内容分发的核心入口。基于 Elasticsearch 8.x（RestClient）提供笔记和商品的双索引全文搜索。搜索建议使用 ES Completion Suggester 实现输入联想（前缀匹配 + 权重排序）。数据同步采用 Canal 监听 MySQL Binlog → RocketMQ → 消费写入 ES 的增量同步方案（延迟 < 5 秒）。全量索引用 @Scheduled 分页扫描 + 批量写入 + 断点续传。深分页使用 Search After 替代 from+size。

### 1.2 功能边界

| 功能项 | 是否实现 | 说明 |
|--------|---------|------|
| 笔记全文搜索 | ✅ | 标题 + 内容，IK 分词，高亮返回 |
| 商品搜索 | ✅ | 关键词 + 分类筛选 + 价格区间 + 排序 |
| 搜索建议 | ✅ | ES Completion Suggester 前缀匹配 |
| 搜索历史 | ✅ | Redis List，最近 20 条 |
| Canal 增量同步 | ✅ | Binlog → MQ → ES，延迟 < 5 秒 |
| 全量索引重建 | ✅ | 分页扫描 + 断点续传 |
| 深分页优化 | ✅ | Search After 替代 from+size |
| 搜索结果高亮 | ✅ | 关键词高亮返回 |
| 空搜索处理 | ✅ | 空关键词返回热搜推荐 |

### 1.3 数据量预估

| 指标 | 预估值 | 计算依据 |
|------|--------|----------|
| 笔记索引量 | 5000 万 | 所有已发布笔记 |
| 商品索引量 | 1000 万 | 所有上架商品 |
| 搜索 QPS | 8000 | 搜索是高频操作 |
| 搜索建议 QPS | 15000 | 每输入一个字符触发一次 |
| 增量同步延迟 | < 5 秒 | Canal → MQ → ES |

---

## 🏗️ 二、架构设计

### 2.1 整体架构图

```
Client → Gateway → my-xhs-search(9011)
                        │
                        ├── Elasticsearch 8.x: 全文搜索/搜索建议
                        ├── Redis: 搜索历史/热搜/建议缓存
                        └── Canal → RocketMQ → ES: 增量同步

数据同步链路：
MySQL(t_note/t_spu) → Canal(Binlog监听) → RocketMQ → SearchConsumer → ES
```

### 2.2 搜索查询流程

```
1. Client → SearchService: GET /api/search/note?keyword=穿搭&sort=hot&page=1
2. SearchService → Redis: 记录搜索历史 LPUSH + LTRIM 20
3. SearchService → ES: 构建查询（match + filter + sort + search_after + highlight）
4. ES → SearchService: 返回结果（带高亮）
5. SearchService → Client: 返回 SearchResultVO
```

### 2.3 数据同步架构

```
增量同步（实时）：
MySQL → Canal(Binlog) → RocketMQ(NOTE_TOPIC:PUBLISH/UPDATE/DELETE)
                              ↓
                    NoteIndexSyncConsumer → ES(note_index)

全量重建（定时/手动）：
@Scheduled → 分页查 DB(lastId游标) → 每批500条 → BulkRequest → ES
                                    ↓
                    记录进度: search:index:rebuild:status (Hash)
```

---

## 🗄️ 三、数据库设计

### 3.1 ES 索引设计

**笔记索引 (note_index)**

```json
{
  "mappings": {
    "properties": {
      "noteId": { "type": "long" },
      "userId": { "type": "long" },
      "title": { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
      "content": { "type": "text", "analyzer": "ik_smart" },
      "coverImage": { "type": "keyword", "index": false },
      "likeCount": { "type": "long" },
      "collectCount": { "type": "long" },
      "commentCount": { "type": "long" },
      "status": { "type": "integer" },
      "createdAt": { "type": "date" }
    }
  },
  "settings": {
    "number_of_shards": 3,
    "number_of_replicas": 1
  }
}
```

**商品索引 (product_index)**

```json
{
  "mappings": {
    "properties": {
      "spuId": { "type": "long" },
      "skuId": { "type": "long" },
      "name": { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
      "categoryId": { "type": "long" },
      "categoryName": { "type": "keyword" },
      "brandName": { "type": "keyword" },
      "price": { "type": "scaled_float", "scaling_factor": 100 },
      "image": { "type": "keyword", "index": false },
      "sales": { "type": "long" },
      "status": { "type": "integer" },
      "createdAt": { "type": "date" }
    }
  }
}
```

**搜索建议索引 (suggest_index)**

```json
{
  "mappings": {
    "properties": {
      "keyword": { "type": "completion", "analyzer": "ik_max_word" },
      "weight": { "type": "long" }
    }
  }
}
```

---

## 🔑 四、缓存设计

### 4.1 Redis Key 规范

| Key Pattern | 类型 | 过期时间 | 说明 |
|-------------|------|----------|------|
| `search:history:{userId}` | List | 30d | 搜索历史（LPUSH + LTRIM 20） |
| `search:suggest:cache:{prefix}` | String | 1h | 搜索建议缓存 |
| `search:index:rebuild:status` | Hash | 1d | 全量重建进度（lastId/totalCount/startTime） |

---

## 📡 五、接口设计

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| GET | `/api/search/note` | 笔记搜索（关键词+筛选+排序） | ❌ |
| GET | `/api/search/product` | 商品搜索（关键词+分类+价格区间+排序） | ❌ |
| GET | `/api/search/suggest` | 搜索建议（自动补全） | ❌ |
| GET | `/api/search/history` | 搜索历史（最近 20 条） | ✅ |
| DELETE | `/api/search/history` | 清空搜索历史 | ✅ |
| DELETE | `/api/search/history/{keyword}` | 删除单条搜索历史 | ✅ |
| POST | `/api/search/index/rebuild` | 手动触发全量索引重建 | ✅（管理员） |

---

## 💻 六、核心代码实现

### 6.1 笔记搜索（ES RestClient）

```java
/**
 * 笔记搜索：match查询 + filter过滤 + 排序 + Search After深分页 + 高亮
 */
public SearchResultVO<NoteSearchVO> searchNotes(NoteSearchRequest request) {
    SearchRequest searchRequest = new SearchRequest("note_index");
    SearchSourceBuilder source = new SearchSourceBuilder();

    // 1. 构建查询：multi_match（标题权重更高）
    BoolQueryBuilder boolQuery = QueryBuilders.boolQuery()
        .must(QueryBuilders.multiMatchQuery(request.getKeyword(), "title", "content")
            .field("title", 3.0f)  // 标题权重 3 倍
            .analyzer("ik_smart"));

    // 2. 过滤条件：只搜已发布的笔记
    boolQuery.filter(QueryBuilders.termQuery("status", 1));

    source.query(boolQuery);

    // 3. 排序（相关度/时间/热度）
    switch (request.getSort()) {
        case "time" -> source.sort("createdAt", SortOrder.DESC);
        case "hot" -> source.sort("likeCount", SortOrder.DESC);
        default -> source.sort("_score", SortOrder.DESC); // 相关度
    }

    // 4. Search After 深分页
    if (request.getSearchAfter() != null) {
        source.searchAfter(request.getSearchAfter());
    }
    source.size(request.getSize());

    // 5. 高亮
    source.highlighter(new HighlightBuilder()
        .field("title").field("content")
        .preTags("<em>").postTags("</em>"));

    searchRequest.source(source);
    SearchResponse response = restHighLevelClient.search(searchRequest, RequestOptions.DEFAULT);

    return buildSearchResult(response);
}
```

### 6.2 搜索建议（Completion Suggester）

```java
/**
 * 搜索建议：ES Completion Suggester
 * 性能比前缀匹配查询高 10 倍+（FST 数据结构）
 */
public List<String> suggest(String prefix) {
    // 1. 先查缓存
    String cacheKey = "search:suggest:cache:" + prefix;
    String cached = redisOperator.get(cacheKey);
    if (cached != null) {
        return JSON.parseArray(cached, String.class);
    }

    // 2. 查 ES Completion Suggester
    SearchRequest request = new SearchRequest("suggest_index");
    SuggestBuilder suggestBuilder = new SuggestBuilder()
        .addSuggestion("keyword_suggest",
            SuggestBuilders.completionSuggestion("keyword")
                .prefix(prefix)
                .size(10)
                .skipDuplicates(true));
    request.source(new SearchSourceBuilder().suggest(suggestBuilder));

    SearchResponse response = restHighLevelClient.search(request, RequestOptions.DEFAULT);
    List<String> suggestions = parseSuggestions(response);

    // 3. 缓存 1 小时
    redisOperator.set(cacheKey, JSON.toJSONString(suggestions), 3600);
    return suggestions;
}
```

### 6.3 Canal 增量同步消费

```java
/**
 * 消费 Canal 发送的笔记变更消息，同步到 ES
 * 延迟 < 5 秒
 */
@RocketMQMessageListener(topic = "NOTE_TOPIC", selectorExpression = "PUBLISH || UPDATE || DELETE")
public class NoteIndexSyncConsumer implements RocketMQListener<NoteChangeEvent> {

    @Override
    public void onMessage(NoteChangeEvent event) {
        switch (event.getType()) {
            case "PUBLISH", "UPDATE" -> {
                NoteDocument doc = buildDocument(event);
                IndexRequest request = new IndexRequest("note_index")
                    .id(String.valueOf(event.getNoteId()))
                    .source(JSON.toJSONString(doc), XContentType.JSON);
                restHighLevelClient.index(request, RequestOptions.DEFAULT);
            }
            case "DELETE" -> {
                DeleteRequest request = new DeleteRequest("note_index",
                    String.valueOf(event.getNoteId()));
                restHighLevelClient.delete(request, RequestOptions.DEFAULT);
            }
        }
    }
}
```

---

## ⚖️ 七、方案对比

### 7.1 数据同步：Canal vs 双写 vs 定时全量

| 维度 | Canal 增量同步（✅ 选定） | 应用层双写 | 定时全量 |
|------|------------------------|-----------|---------|
| 实时性 | 准实时（< 5 秒） | 实时 | 差（分钟级） |
| 一致性 | 最终一致 | 强一致（但有事务问题） | 最终一致 |
| 侵入性 | 零侵入（监听 Binlog） | 高（业务代码改动） | 零侵入 |
| 复杂度 | 中（需部署 Canal） | 低 | 低 |
| 可靠性 | 高（MQ 重试） | 中（双写失败难处理） | 高 |

**选择理由**：Canal 零侵入 + 准实时 + MQ 保证可靠性，是搜索同步的业界标准方案。

### 7.2 深分页：Search After vs from+size vs Scroll

| 维度 | Search After（✅ 选定） | from+size | Scroll |
|------|----------------------|-----------|--------|
| 深分页性能 | O(1) | O(N) 越深越慢 | O(1) |
| 实时性 | 实时 | 实时 | 快照（不实时） |
| 适用场景 | 用户翻页 | 前 10 页 | 全量导出 |

---

## 🐛 八、踩坑记录

### 8.1 IK 分词器版本不匹配

- **现象**：ES 启动报错 `incompatible plugin`
- **原因**：IK 分词器版本必须与 ES 版本完全一致（如 8.12.0）
- **解决**：IK 版本号与 ES 版本号保持一致

### 8.2 Canal 同步丢消息

- **现象**：部分笔记发布后搜索不到
- **原因**：Canal 消费 MQ 失败后未重试
- **解决**：MQ 消费失败重试 16 次 → 死信队列 → 告警 + 手动补偿

### 8.3 Completion Suggester 中文分词问题

- **现象**：输入"穿"搜不到"穿搭分享"
- **原因**：Completion Suggester 默认按空格分词，中文无空格
- **解决**：suggest_index 的 keyword 字段使用 `ik_max_word` 分词器

---

## 📊 九、测试验证

| 测试场景 | 输入 | 预期结果 | 通过 |
|----------|------|----------|------|
| 笔记搜索 | keyword="穿搭" | 返回相关笔记 + 高亮 | ⬜ |
| 商品搜索 | keyword="连衣裙" + 价格区间 | 返回筛选后商品 | ⬜ |
| 搜索建议 | prefix="穿" | 返回"穿搭/穿搭分享/穿搭技巧" | ⬜ |
| 搜索历史 | 搜索后查询 | 返回最近 20 条 | ⬜ |
| 增量同步 | 发布新笔记 | 5 秒内可搜到 | ⬜ |
| 深分页 | 翻到第 100 页 | Search After 正常返回 | ⬜ |
| 空搜索 | keyword="" | 返回热搜推荐 | ⬜ |

---

## 🎤 十、面试考察点

### Q1: 搜索数据怎么从 MySQL 同步到 ES？

> 1. "增量同步：Canal 监听 MySQL Binlog → RocketMQ → Search 消费写入 ES"
> 2. "零侵入：不改业务代码，Canal 直接监听 Binlog"
> 3. "延迟 < 5 秒，MQ 重试保证可靠性"
> 4. "全量重建：@Scheduled 分页扫描 DB + 断点续传，用于索引重建或数据修复"

### Q2: ES 深分页有什么问题？怎么优化？

> 1. "from+size 问题：ES 默认 max_result_window=10000，超过报错"
> 2. "即使调大限制，深分页性能也是 O(N)，每次都要跳过前 N 条"
> 3. "解决方案：Search After，基于上一页最后一条的排序值作为游标"
> 4. "Search After 性能 O(1)，不受页码深度影响"

### Q3: 搜索建议怎么实现的？

> 1. "ES Completion Suggester：基于 FST（有限状态转换器）数据结构"
> 2. "比普通 prefix 查询性能高 10 倍+，专为自动补全设计"
> 3. "中文需要配合 IK 分词器，否则无法按字分词"
> 4. "Redis 缓存热门前缀的建议结果，减少 ES 压力"

---

## 📚 参考资料

| 资料 | 相关章节 | 参考内容 |
|------|----------|----------|
| 📄 phase-3/README.md | §3.15 | 搜索服务完整设计（ES索引/Canal/建议/历史） |
| 📄 02-module-detailed-design.md | §6 | 搜索服务/ES双索引/Canal同步 |
