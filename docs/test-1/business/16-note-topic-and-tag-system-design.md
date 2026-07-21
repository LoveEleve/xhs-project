# my-xhs 笔记标签/话题系统设计

> 小红书的 #话题# 是核心发现路径——用户通过话题发现内容、通过话题聚合兴趣。
> 没有 #话题# 的内容平台就像没有目录的百科全书。

---

## 一、话题系统功能概述

```
话题的核心功能：
1. 笔记发布时添加话题（#美食探店# #穿搭分享#）
2. 话题页面聚合所有相关笔记
3. 话题推荐（热门话题、推荐话题）
4. 话题搜索（输入#自动联想）
5. 话题统计（笔记数、参与人数、浏览量）
6. 话题运营（置顶笔记、官方话题、话题分类）
```

---

## 二、数据库设计

### 2.1 话题表

```sql
CREATE TABLE t_topic (
    id              BIGINT PRIMARY KEY COMMENT '话题ID',
    name            VARCHAR(50) NOT NULL COMMENT '话题名称',
    cover_url       VARCHAR(500) COMMENT '话题封面图',
    description     VARCHAR(500) COMMENT '话题描述',
    category        VARCHAR(20) COMMENT '话题分类：LIFESTYLE/FOOD/TRAVEL/FASHION/BEAUTY/TECH/OTHER',
    status          TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0禁用 1正常',
    is_official     TINYINT NOT NULL DEFAULT 0 COMMENT '是否官方话题',
    sort_order      INT DEFAULT 0 COMMENT '排序权重',
    note_count      BIGINT DEFAULT 0 COMMENT '笔记数（冗余计数）',
    participant_count BIGINT DEFAULT 0 COMMENT '参与人数（冗余计数）',
    view_count      BIGINT DEFAULT 0 COMMENT '浏览量（冗余计数）',
    create_time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_name (name),
    INDEX idx_category (category, sort_order),
    INDEX idx_sort (sort_order DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='话题表';
```

### 2.2 笔记-话题关联表

```sql
CREATE TABLE t_note_topic (
    id              BIGINT PRIMARY KEY COMMENT '主键',
    note_id         BIGINT NOT NULL COMMENT '笔记ID',
    topic_id        BIGINT NOT NULL COMMENT '话题ID',
    create_time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_note_topic (note_id, topic_id),
    INDEX idx_topic (topic_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='笔记-话题关联表';
```

### 2.3 笔记表变更

```sql
-- V1_0_3__add_note_tags_and_topics.sql

-- 笔记表增加标签字段（逗号分隔）
ALTER TABLE t_note ADD COLUMN tags VARCHAR(500) COMMENT '标签，逗号分隔' AFTER content;
ALTER TABLE t_note ADD INDEX idx_tags (tags(100));  -- 前缀索引

-- 注意：topics不走t_note表，走关联表t_note_topic（多对多）
-- tags和topics的区别：
--   tags = 自由标签，用户随意输入，如"奶茶""OOTD"
--   topics = 结构化话题，从话题库选择，如"#美食探店#"
```

### 2.4 tags vs topics 的区别

| 维度 | tags（标签） | topics（话题） |
|------|------------|--------------|
| 来源 | 用户自由输入 | 从话题库选择/创建 |
| 结构 | 扁平，无层级 | 有分类，有运营 |
| 搜索 | 模糊匹配 | 精确匹配 |
| 聚合 | 同标签笔记列表 | 话题页面（有封面、描述、置顶） |
| 存储 | t_note.tags字段（逗号分隔） | t_note_topic关联表 |
| 计数 | 无独立计数 | 有独立计数（note_count等） |
| 运营 | 无运营干预 | 可置顶、可禁用、可推荐 |

---

## 三、核心功能设计

### 3.1 笔记发布 — 添加话题

```java
@Service
public class NoteTopicService {

    /**
     * 笔记发布时处理话题
     * 1. 解析笔记内容中的 #话题#
     * 2. 匹配已有话题库
     * 3. 不存在的话题自动创建
     * 4. 建立笔记-话题关联
     */
    @Transactional
    public void bindTopics(Long noteId, String content, List<String> topicNames) {
        // 1. 从内容中提取话题（#xxx#格式）
        if (CollectionUtils.isEmpty(topicNames)) {
            topicNames = extractTopics(content);
        }

        if (CollectionUtils.isEmpty(topicNames)) {
            return;
        }

        // 限制：每篇笔记最多5个话题
        if (topicNames.size() > 5) {
            topicNames = topicNames.subList(0, 5);
        }

        List<Long> topicIds = new ArrayList<>();
        for (String name : topicNames) {
            // 2. 匹配已有话题
            Topic topic = topicMapper.selectByName(name);
            if (topic == null) {
                // 3. 不存在则自动创建
                topic = new Topic();
                topic.setId(snowflakeIdWorker.nextId());
                topic.setName(name);
                topic.setCategory(inferCategory(name));  // 根据名称推断分类
                topic.setIsOfficial(0);  // 用户创建的话题非官方
                topicMapper.insert(topic);
            }
            topicIds.add(topic.getId());
        }

        // 4. 建立关联
        for (Long topicId : topicIds) {
            NoteTopic noteTopic = new NoteTopic();
            noteTopic.setId(snowflakeIdWorker.nextId());
            noteTopic.setNoteId(noteId);
            noteTopic.setTopicId(topicId);
            noteTopicMapper.insert(noteTopic);
        }

        // 5. 更新话题计数（异步）
        for (Long topicId : topicIds) {
            counterService.increment("TOPIC", topicId, "note_count", 1);
        }
    }

    /**
     * 从内容中提取 #话题#
     */
    private List<String> extractTopics(String content) {
        // 正则匹配 #话题# 格式
        // 支持：#美食探店# #穿搭# #OOTD#
        // 不支持：##（空话题）、#a#（过短）
        Pattern pattern = Pattern.compile("#([^#]{2,20})#");
        Matcher matcher = pattern.matcher(content);
        List<String> topics = new ArrayList<>();
        while (matcher.find()) {
            topics.add(matcher.group(1).trim());
        }
        return topics.stream().distinct().limit(5).toList();
    }
}
```

### 3.2 话题页面聚合

```
话题页面数据：
├── 话题信息（名称/封面/描述/分类）
├── 话题计数（笔记数/参与人数/浏览量）
├── 笔记列表（按热度/时间排序）
│   ├── 置顶笔记（1-3条，运营设置）
│   └── 普通笔记（分页）
├── 话题推荐（相关话题）
└── 参与用户（最近参与的头像列表）

数据来源：
├── 话题信息 → MySQL t_topic
├── 话题计数 → Redis Hash（counter服务）
├── 置顶笔记 → Redis ZSet（运营设置）
├── 笔记列表 → ES搜索（topic_id精确匹配 + 排序）
└── 参与用户 → Redis Set（最近参与的用户ID）
```

### 3.3 话题搜索（输入#自动联想）

```java
/**
 * 话题搜索 — 输入#后自动联想
 * 数据来源：ES t_topic索引
 */
@GetMapping("/api/topic/suggest")
public List<TopicSuggestVO> suggest(@RequestParam String keyword) {
    // ES模糊匹配话题名称
    // 按note_count降序（热门话题优先）
    // 返回top10
    return topicSearchService.suggest(keyword, 10);
}
```

### 3.4 热门话题

```
热门话题计算：
  - 近24小时笔记增量最大的话题（Redis ZSet + 滑动窗口）
  - 排除被禁用的话题
  - 每个分类取Top5

数据结构：
  Redis ZSet: topic:hot:{category}
  score = 近24h笔记增量
  member = topicId

更新策略：
  - 每次笔记发布时 ZINCRBY topic:hot:{category} 1 {topicId}
  - 定时任务每小时清理过期分数（超过24h的衰减）
```

---

## 四、ES索引设计

### 4.1 话题索引

```json
{
  "mappings": {
    "properties": {
      "id": { "type": "long" },
      "name": {
        "type": "text",
        "analyzer": "ik_max_word",
        "search_analyzer": "ik_smart",
        "fields": {
          "keyword": { "type": "keyword" },
          "suggest": { "type": "completion" }
        }
      },
      "category": { "type": "keyword" },
      "noteCount": { "type": "long" },
      "participantCount": { "type": "long" },
      "isOfficial": { "type": "boolean" },
      "status": { "type": "keyword" }
    }
  }
}
```

### 4.2 笔记索引补充

```json
// 在现有笔记索引中增加字段
{
  "topicIds": { "type": "long" },       // 话题ID数组（精确过滤）
  "topicNames": {                        // 话题名称（搜索）
    "type": "text",
    "analyzer": "ik_max_word"
  },
  "tags": {                              // 标签（搜索）
    "type": "text",
    "analyzer": "ik_max_word"
  }
}
```

---

## 五、缓存设计

### 5.1 话题缓存策略

| 数据 | 缓存Key | 类型 | TTL | 更新策略 |
|------|---------|------|-----|---------|
| 话题信息 | `topic:info:{id}` | String(JSON) | 1小时 | Canal→MQ→删缓存 |
| 话题计数 | `counter:TOPIC:{id}` | Hash | — | Buffer-Trigger实时 |
| 热门话题 | `topic:hot:{category}` | ZSet | — | 定时更新+实时增量 |
| 话题下笔记 | `topic:notes:{id}` | ZSet | 5分钟 | 笔记发布时增量更新 |
| 话题搜索建议 | `topic:suggest:*` | — | — | ES实时查询 |
| 置顶笔记 | `topic:pinned:{id}` | List | 1小时 | 运营操作时删缓存 |

---

## 六、生产决策与表达

### Q: 话题和标签有什么区别？

> "话题是结构化的、有运营管理的聚合维度——有封面、描述、分类、置顶笔记。标签是用户自由输入的扁平化关键词。话题走关联表（多对多），标签走字段存储（逗号分隔）。话题有独立计数和热门排行，标签主要用于搜索匹配。两者互补：话题做内容发现和聚合，标签做搜索和推荐特征。"

### Q: 笔记发布时用户输入了一个不存在的话题怎么办？

> "自动创建——用户输入 #新话题# 时，如果话题库不存在就自动创建，状态为'用户创建'（is_official=0）。这样降低用户参与门槛。但新创建的话题不会立即进入话题推荐，需要达到一定笔记数（>10篇）才进入热门候选，防止垃圾话题污染。"
