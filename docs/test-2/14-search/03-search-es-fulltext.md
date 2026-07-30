# Search ES 全文搜索 — 深度技术分析

> 关联源码：`NoteSearchService.java` / `ProductSearchService.java` / `AbstractSearchService.java` / `ElasticsearchConfig.java`

---

## 业务背景

笔记和商品搜索是 my-xhs 的核心流量入口。用户输入关键词后，需要在毫秒级内从百万级文档中找到最相关的结果。

ES 选型 vs MySQL LIKE：

| 方案 | 中文分词 | 相关度排序 | 深分页 | 高亮 | 性能 |
|---|---|---|---|---|---|
| MySQL LIKE '%keyword%' | ❌ | ❌ | 🟡 LIMIT 大偏移性能差 | ❌ | 全表扫描 |
| ES 全文检索 | ✅ IK | ✅ BM25 | ✅ Search After | ✅ | 倒排索引 O(1) |

---

## 索引设计

### 分词器：IK

```
title → ik_max_word（索引时最大切分）
      → ik_smart（搜索时智能切分）

"春日穿搭分享"
  ik_max_word: 春日 / 穿搭 / 分享 / 春日穿搭 / 穿搭分享
  ik_smart:     春日 / 穿搭 / 分享
```

**为什么索引和搜索用不同粒度**：索引时 `ik_max_word` 尽可能多地切分，覆盖更多可能的查询词。搜索时 `ik_smart` 只保留最合理的切分，减少噪音匹配。

### 索引参数

```
note_index: 3 shards, 1 replica
product_index: 3 shards, 1 replica
suggest_index: 1 shard, 1 replica
```

3 shards 的选择：单节点集群（当前部署）无分片分布收益，但未来集群扩容到 3 节点时，每个节点承载 1 个 primary shard。

---

## 查询构建

### BoolQuery + multi_match

```java
BoolQuery.Builder boolBuilder = new BoolQuery.Builder();

// must: multi_match 全文搜索（标题权重 3 倍）
boolBuilder.must(q -> q.multiMatch(mm -> mm
        .query(keyword)
        .fields("title^3", "content")
        .analyzer("ik_smart")));

// filter: 只搜已发布的（status=1）
boolBuilder.filter(f -> f.term(t -> t.field("status").value(1)));
```

**为什么 title 权重是 3 倍**：标题匹配比正文匹配更相关。BM25 评分中，title^3 意味着标题匹配的 term 贡献的 score 是正文的 3 倍。

**为什么用 filter 不用 must**：filter 不参与评分计算（不计入 _score），只做布尔过滤。ES 会对 filter 结果做缓存，提高后续相同查询的性能。

### 空关键词兜底

```java
if (keyword为空) {
    boolBuilder.must(q -> q.matchAll(m -> m));
}
```

当用户不输入关键词时，返回全部已发布内容（按时间/热度排序）。

---

## 排序策略

笔记搜索：

```java
switch (sort) {
    case "time"  → createdAt DESC, noteId DESC
    case "hot"   → likeCount DESC, noteId DESC
    default      → _score DESC, noteId DESC
}
```

商品搜索：

```java
switch (sort) {
    case "price_asc"  → price ASC, spuId ASC
    case "price_desc" → price DESC, spuId DESC
    case "sales"      → sales DESC, spuId DESC
    default           → _score DESC, spuId DESC
}
```

**tiebreaker 机制**：当主排序字段值相同时（如两篇笔记 _score 相同），使用 `noteId DESC` 确定先后顺序。`noteId` 是雪花 ID，天然递增且唯一，确保排序结果稳定——不会出现翻页时结果顺序变化。

**修复记录**：原代码在 tiebreaker 中还使用了 `_id ASC`，但 ES 8.x 默认禁止 `_id` 字段的 fielddata 访问，导致 `search_phase_execution_exception: all shards failed`。已移除 `_id` 排序。

---

## 深分页：Search After

### 为什么不用 from/size

```
from=10000, size=20 → ES 需要从每个 shard 取 10020 条 → 协调节点排序
→ 深度翻页时性能指数级下降
→ ES 默认 max_result_window=10000
```

### Search After 工作原理

```
第一页 →
  查询并返回结果
  取最后一条的 sort values
  返回给客户端

第二页 →
  客户端传入 searchAfter=[score, noteId]
  查询时带 searchAfter 参数
  ES 从该位置之后开始返回
  O(1) 性能，不受翻页深度影响
```

```java
// 解析 Search After（JSON 数组字符串 → List<FieldValue>）
if (searchAfter != null && !searchAfter.isBlank()) {
    List<FieldValue> sortValues = parseSearchAfter(searchAfter);
    searchBuilder.searchAfter(sortValues);
}
```

**Search After vs 游标（Scroll）**：

| 对比 | Search After | Scroll |
|---|---|---|
| 场景 | 实时翻页 | 批量导出/全量遍历 |
| 一致性 | 最终一致（新数据可能出现在前一页） | 快照一致 |
| 开销 | 无额外开销 | 需要维护 Scroll Context |
| 实时性 | 实时 | 快照时间点 |

---

## 高亮

```java
searchBuilder.highlight(h -> h
    .fields("title", hf -> hf.preTags("<em>").postTags("</em>"))
    .fields("content", hf -> hf
        .preTags("<em>").postTags("</em>")
        .fragmentSize(150).numberOfFragments(1)));
```

**响应处理**：ES 返回的高亮片段通过 `highlightTitle`/`highlightContent` 字段返回（不修改原文）。如果某字段没有高亮匹配，则高亮字段为 null，客户端回退到原文字段值。

---

## 面试 Q&A

**Q: IK 分词器 ik_max_word 和 ik_smart 的区别？**
A: ik_max_word 做最细粒度切分（"春日穿搭分享"→"春日/穿搭/分享/春日穿搭/穿搭分享"），ik_smart 做最粗粒度切分（"春日/穿搭/分享"）。索引时用 max_word 覆盖更多查询可能性，搜索时用 smart 减少噪音。

**Q: 为什么排序用 noteId DESC 做 tiebreaker？**
A: 笔记 ID 是雪花算法生成，全局递增唯一。当 _score 相同时，用 noteId 确定稳定顺序。之前用 _id 导致 ES 8.x 的 fielddata 访问报错。

**Q: Search After 和传统分页有什么区别？**
A: from/size 在深度翻页时性能恶化（ES 需要取 from+size 条再丢弃前 from 条）。Search After 基于游标，每次从指定位置开始取 size 条，O(1) 性能。但 Search After 不支持跳页——无法从第 1 页直接跳到第 5 页，必须逐页翻。

---

## 生产实验

### 商品搜索验证

```
搜索关键词 "商品" → 1 条命中 → 高亮正确 (<em>商品</em>)
搜索关键词 "curl测试商品-已更新" → 1 条命中（完整匹配）
搜索关键词 "不存在的商品" → 0 条（无结果）
```

### _id 排序修复验证

修复前：所有搜索返回 500（`search_phase_execution_exception: all shards failed`）
修复后：搜索正常返回，排序稳定。

### 响应时间（单次测试）

商品搜索 `took=6~69ms`（冷/热缓存差异）。ES 查询在缓存预热后稳定在 10ms 以内。

---

## 发散

### 同义词扩展

当前搜索不支持同义词。例如搜索"连衣裙"不会匹配"裙子"。可以通过 ES 同义词过滤器实现：

```json
{
  "filter": {
    "synonym": {
      "type": "synonym",
      "synonyms": ["连衣裙,裙子,裙装", "笔记本,电脑,计算机"]
    }
  }
}
```

### 搜索推荐（Did you mean）

ES 的 `Phrase Suggester` 可以实现拼写纠正和搜索推荐。当用户搜索返回 0 条时，可以提示"您是不是想找：XXX"。
