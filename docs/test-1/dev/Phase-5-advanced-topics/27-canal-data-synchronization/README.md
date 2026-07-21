# Canal 数据同步

> 跨服务专题 | 开发阶段：Phase-5 | 状态：⏳ 待开发

---

## 🎯 一、为什么需要 Canal

```
问题：MySQL 中的笔记数据需要同步到 ES 供搜索使用
方案：Canal 监听 MySQL Binlog → RocketMQ → 消费写入 ES

优势：
- 零侵入：不改业务代码，直接监听 Binlog
- 准实时：延迟 < 5 秒
- 可靠：MQ 重试保证不丢
```

---

## 🏗️ 二、同步架构

```
增量同步（实时）：
MySQL(t_note/t_spu) → Canal Server → RocketMQ → SearchConsumer → ES

全量重建（定时/手动）：
@Scheduled → 分页查DB(lastId游标) → 每批500条 → BulkRequest → ES
                                    ↓
                    记录进度: search:index:rebuild:status (Hash)
```

### 2.1 同步链路

| 数据源 | Canal 实例 | MQ Topic | 消费者 | 目标 |
|--------|-----------|----------|--------|------|
| t_note | canal-note | NOTE_SYNC_TOPIC | NoteIndexSyncConsumer | ES note_index |
| t_spu | canal-product | PRODUCT_SYNC_TOPIC | ProductIndexSyncConsumer | ES product_index |
| t_order | canal-order | ORDER_SYNC_TOPIC | OrderIndexSyncConsumer | ES order_index |

### 2.2 Canal 消息格式

```json
{
  "database": "my_xhs_content",
  "table": "t_note",
  "type": "UPDATE",
  "data": [{"id": 123, "title": "新标题", "status": 1}],
  "old": [{"title": "旧标题"}],
  "ts": 1715510539000
}
```

---

## 💻 三、核心代码实现

### 3.1 Canal 消费者

```java
@RocketMQMessageListener(topic = "NOTE_SYNC_TOPIC")
public class NoteIndexSyncConsumer implements RocketMQListener<CanalMessage> {

    @Override
    public void onMessage(CanalMessage msg) {
        switch (msg.getType()) {
            case "INSERT", "UPDATE" -> {
                // 构建 ES 文档并写入
                NoteDocument doc = buildDocument(msg.getData());
                IndexRequest request = new IndexRequest("note_index")
                    .id(String.valueOf(doc.getNoteId()))
                    .source(JSON.toJSONString(doc), XContentType.JSON);
                restHighLevelClient.index(request, RequestOptions.DEFAULT);
            }
            case "DELETE" -> {
                DeleteRequest request = new DeleteRequest("note_index",
                    String.valueOf(msg.getData().get(0).get("id")));
                restHighLevelClient.delete(request, RequestOptions.DEFAULT);
            }
        }
    }
}
```

### 3.2 全量重建（断点续传）

```java
@Scheduled(cron = "0 0 3 ? * SUN") // 每周日凌晨3点
public void fullRebuildIndex() {
    String statusKey = "search:index:rebuild:status";
    Long lastId = getLastId(statusKey); // 断点续传

    while (true) {
        List<Note> batch = noteMapper.selectBatchAfter(lastId, 500);
        if (batch.isEmpty()) break;

        BulkRequest bulkRequest = new BulkRequest();
        for (Note note : batch) {
            bulkRequest.add(new IndexRequest("note_index")
                .id(String.valueOf(note.getId()))
                .source(JSON.toJSONString(buildDocument(note)), XContentType.JSON));
        }
        restHighLevelClient.bulk(bulkRequest, RequestOptions.DEFAULT);

        lastId = batch.get(batch.size() - 1).getId();
        saveProgress(statusKey, lastId); // 记录进度
    }
}
```

---

## ⚖️ 四、方案对比

| 维度 | Canal 增量（✅ 选定） | 应用层双写 | 定时全量 |
|------|---------------------|-----------|---------|
| 实时性 | 准实时（< 5s） | 实时 | 差（分钟级） |
| 侵入性 | 零侵入 | 高 | 零侵入 |
| 可靠性 | 高（MQ 重试） | 中（双写失败难处理） | 高 |

---

## 🐛 五、踩坑记录

### 5.1 Canal 位点丢失

- **现象**：Canal 重启后重复消费大量历史数据
- **解决**：Canal 位点持久化到 ZooKeeper/Redis，重启后从断点继续

### 5.2 Binlog 格式不对

- **现象**：Canal 收到的消息没有具体字段值
- **原因**：MySQL Binlog 格式是 STATEMENT 而非 ROW
- **解决**：`binlog_format = ROW`（Canal 要求 ROW 格式）

---

## 🎤 六、面试考察点

### Q1: 数据库和 ES 怎么保持同步？

> 1. "增量同步：Canal 监听 MySQL Binlog → RocketMQ → 消费写入 ES"
> 2. "零侵入：不改业务代码，Canal 直接监听 Binlog"
> 3. "全量重建：@Scheduled 分页扫描 DB + 断点续传，用于索引重建"
> 4. "对账：每天 MySQL COUNT vs ES COUNT，差异告警"

### Q2: Canal 同步延迟怎么处理？

> 1. "正常延迟 < 5 秒，可接受"
> 2. "延迟过大时：检查 MQ 消费积压 → 扩容消费者"
> 3. "极端情况：手动触发全量重建"

---

## 📚 参考资料

| 资料 | 参考内容 |
|------|----------|
| 📄 phase-5/README.md §3.27 | Canal 数据同步完整设计 |
| 📄 15-search-and-suggestion | 搜索服务 Canal 同步实现 |
