# Canal→ES 商品索引同步：实时消费 + 增量补偿 + 全量重建

> **源码**: ProductIndexSyncConsumer(314行) + IncrementalIndexSyncJob(355行) + IndexRebuildJob(商品段) + IndexInitializer(index mapping)  
> **数据流**: MySQL Binlog → Canal product_instance → RocketMQ `PRODUCT_INDEX_TOPIC`(3分区) → Consumer(Feign补全) → ES `product_index`(3 shard/1 replica)  
> **关键修复**: CC1 Consumer Feign补全 / CC1b 补偿Job SQL / CC1c 重建Job SQL

---

## 1. 全链路

```
MySQL t_spu INSERT/UPDATE/DELETE
  │
  ▼ (Binlog)
Canal instance: product_instance
  │ 监听: my_xhs_product.t_spu, t_sku
  │
  ▼ (RocketMQ)
PRODUCT_INDEX_TOPIC
  │ Tag: [无]
  │
  ├────────────┬────────────────┐
  ▼            ▼                ▼
Consumer   补偿 Job(5min)   重建 Job(4am)
(实时)     (Redis Set 驱动)  (全量游标)
  │            │                │
  │ Feign      │ SQL           │ SQL
  │ product    │ t_spu         │ t_spu
  │            │                │
  ▼            ▼                ▼
         ES product_index
     ExternalGte 版本控制
```

**三管齐下**：实时消费是主路径，补偿 Job 是兜底（失败的 spuId 存在 Redis Set 里），重建 Job 是灾备（每日凌晨全量重写）。

---

## 2. Canal 实例配置

### 2.1 实例定义 (`config/canal/conf/product_instance/instance.properties`)

```properties
canal.instance.master.address = 127.0.0.1:13307          # MySQL master
canal.instance.filter.regex = my_xhs_product\\.t_spu,my_xhs_product\\.t_sku  # 监听表
canal.mq.topic = PRODUCT_INDEX_TOPIC                     # 推送的 RocketMQ Topic
canal.mq.partitionsNum = 3                               # 3 分区
canal.mq.partitionHash = my_xhs_product.t_spu:id,my_xhs_product.t_sku:id  # 按 ID 哈希
```

**监听两张表**：`t_spu` 的变更触发主索引更新；`t_sku` 变更也触发（Consumer 内先忽略但保留消息——后续 SKU 维度的索引待补全）。

**3 分区按 ID 哈希**：同一 SPU 的变更总是进入同一分区 → 同一 Consumer 线程 → 天然有序。不同 SPU 的变更分布在不同分区 → 并行消费。

### 2.2 全局配置 (`config/canal/conf/canal.properties`)

```properties
canal.destinations = note_instance,product_instance,inventory_instance
canal.auto.scan.interval = 5  # 5 秒扫描新实例
```

整个项目共 3 个 Canal 实例——分别同步笔记、商品、库存的数据。

---

## 3. ES 索引映射 (`IndexInitializer.java:101-123`)

```json
{
  "settings": {
    "number_of_shards": 3,
    "number_of_replicas": 1
  },
  "mappings": {
    "properties": {
      "spuId":        { "type": "long" },
      "skuId":        { "type": "long" },
      "name":         { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
      "categoryId":   { "type": "long" },
      "categoryName": { "type": "keyword" },
      "brandName":    { "type": "keyword" },
      "price":        { "type": "scaled_float", "scaling_factor": 100 },
      "image":        { "type": "keyword", "index": false },
      "sales":        { "type": "long" },
      "status":       { "type": "integer" },
      "createdAt":    { "type": "date", "format": "yyyy-MM-dd HH:mm:ss||...||epoch_millis" }
    }
  }
}
```

**关键设计**：
- `name.analyzer=ik_max_word` — 尽可能多分词（"连衣裙" → "连衣/裙"），提高召回率
- `name.search_analyzer=ik_smart` — 搜索时用粗粒度（"连衣裙" 整体），提高精确率
- `price.scaled_float` — 存储为 `价格×100` 的整数，比 `float` 精确（无浮点误差），比 `keyword` 可排序
- `image.index=false` — 不建索引（不需要按图片 URL 搜索），节省磁盘
- `categoryName.keyword` — 精确匹配（不需要分词），用于聚合/过滤
- `brandName/sales` — ES mapping 有但实际数据为 null/0（product 无品牌表和销量系统）

**启动时自动创建**：`IndexInitializer` 实现 `ApplicationRunner`，应用启动时检查索引是否存在，不存在则自动创建。ES 不可用时只记日志不阻塞启动。

### 2.1 Consumer 配置

```java
@RocketMQMessageListener(
    topic = "PRODUCT_INDEX_TOPIC",
    consumerGroup = "product-index-sync-consumer-group",
    maxReconsumeTimes = 3
)
```

### 2.2 双格式兼容

```java
if (canalMsg.containsKey("database") && canalMsg.containsKey("data")) {
    handleCanalMessage(canalMsg, msgId);     // Canal 原始格式
} else if (canalMsg.containsKey("spuId") && canalMsg.containsKey("type")) {
    handleFlatMessage(canalMsg, msgId);       // 扁平格式
}
```

Canal 原始格式（`{"database":"my_xhs_product","table":"t_spu","type":"INSERT","data":[...]}`）和扁平格式兼容处理。只处理 t_spu 表——t_sku 忽略。

### 2.3 INSERT/UPDATE 处理：Feign 补全（修复 CC1）

```java
// indexProductFromCanal — 修复后
// 从 Canal row 获取基础字段
String name = row.getString("name");
Long categoryId = row.getLong("category_id");
// ... status, created_at ...

// Feign 补全缺失字段
R<Map<String, Object>> r = productFeignClient.getSpuDetail(spuId);
categoryName = spu.get("categoryName");      // t_spu 无此列
price = skuList.get(0).get("price");          // t_spu 无此列（在 t_sku）
image = images.get(0);                        // t_spu 存 JSON 数组
```

| 字段 | 修复前 | 修复后 |
|------|------|------|
| categoryName | Canal flatMessage → null（列不存在） | Feign product → 正确 |
| price | Canal flatMessage → null | Feign → skuList[0].price |
| image | Canal flatMessage → null（main_image 不存在） | Feign → images[0] |
| brandName | null | null（无品牌表） |
| sales | 0 | 0（无销量统计） |

### 2.4 DELETE 处理：标记删除

```java
// deleteProduct — 标记删除（非物理删除）
Map<String, Object> doc = new HashMap<>();
doc.put("spuId", spuId);
doc.put("status", -1);  // -1 = 已删除，搜索时过滤
esClient.index(...);
```

**为什么标记删除而不是物理删除？** MQ 消息可能乱序到达——DELETE 先到、INSERT 后到。如果物理删除，INSERT 消息会重新创建文档。标记删除 `status=-1` 后，后续 INSERT/UPDATE 会覆盖 status 字段，搜索时过滤 `status=-1` 即可。

### 2.5 版本控制

```java
long version = canalMsg.getLongValue("es", 0);  // Canal event sequence
if (version == 0) {
    version = canalMsg.getLongValue("ts", System.currentTimeMillis());
}

esClient.index(IndexRequest.of(idx -> idx
    .versionType(VersionType.ExternalGte)
    .version(version)  // 拒绝更低版本号
    ...));
```

`ExternalGte` 策略：允许相同版本号（同毫秒多次变更），拒绝更低版本号（防止乱序覆盖）。

---

## 3. 增量补偿 Job

### 3.1 触发机制

```java
@Scheduled(fixedRate = 300000)  // 每 5 分钟
public void compensate() {
    Set<String> failedIds = stringRedisTemplate.opsForSet()
        .distinctRandomMembers("myxhs:es:sync:failed:product", BATCH_SIZE);
    // 查询 MySQL + Bulk 写入 ES
}
```

失败记录由 Consumer 在 catch 块写入（`es 通信异常` → `Redis Set add + 1h TTL`）。

### 3.2 SQL 修复（修复 CC1b）

```sql
-- 修复前：查不存在的列 → MySQL Error
SELECT id, name, category_id, category_name, brand_name, price,
       main_image, sales, status, created_at FROM t_spu WHERE id IN (...)

-- 修复后：只查存在的列
SELECT id, name, category_id, brand_id, description, images,
       status, created_at FROM t_spu WHERE id IN (...) AND deleted = 0
```

**修复后 buildProductDocument**：`images` JSON 数组 → 提取第一张图片；`categoryName/price` 用默认值（无 product Feign 补全）。补偿路径不调 Feign——因为补偿的 spuId 可能已删除，调 product 反而返回 404。

---

## 4. 全量重建 Job

### 4.1 执行策略

```java
@Scheduled(cron = "0 0 4 * * ?")  // 每日凌晨 4 点
public void rebuild() {
    RLock lock = redissonClient.getLock("index:rebuild:product");
    if (!lock.tryLock()) return;  // 单实例执行

    // 游标分页全量扫描
    while (true) {
        List<Map<String, Object>> products = jdbcTemplate.queryForList(
            "SELECT id, name, category_id, brand_id, description, images, " +
            "status, created_at FROM t_spu " +
            "WHERE id > ? AND deleted = 0 ORDER BY id ASC LIMIT ?",
            lastSpuId, BATCH_SIZE);
        if (products.isEmpty()) break;
        // Bulk 写入 ES
    }
}
```

### 4.2 断点续建

通过 Redis Hash 记录进度：`lastSpuId` 游标 + `status` 状态。服务重启后从断点继续。

### 4.3 SQL 修复（修复 CC1c）

与补偿 Job 相同的 SQL 修复——去掉不存在的列。buildProductDocument 同样用 images JSON 提取 + 默认值。

---

## 5. 故障恢复

### 5.1 多层兜底

| 层级 | 机制 | 恢复时间 |
|:--:|------|:--:|
| L1 | Consumer onMessage → 抛异常 → MQ 重试 | < 1min（3 次） |
| L2 | Redis Set 记录 failed spuId | 5min 补偿 Job |
| L3 | 每日全量重建 | 次日凌晨 |

### 5.2 典型故障场景

| 故障 | 影响 | 恢复 |
|------|------|------|
| ES 宕机 | Consumer 重试 3 次 → Redis Set 留痕 | ES 恢复后补偿 Job 5min 内补 |
| product 宕机 | Consumer 字段补全失败 → 用默认值 | Canal 下次重试时 product 如果恢复 → 补全 |
| Canal 延迟 | 索引数据陈旧 | Canal 追赶后自愈 |
| ES 索引损坏 | 搜索不完整 | 次日凌晨重建 |

---

## 6. 面试 Q&A

### Q1: 为什么实时消费用 RocketMQ，而不是直接 Canal→ES？

Canal 直接写 ES 只有 Binlog → ES 一段链路，没有中间缓冲。ES 短时不可用 → Binlog 位点无法提交 → Canal 卡住。RocketMQ 做中间层：Canal → MQ（持久化）→ Consumer 消费（支持重试+死信），解耦了 ES 的可用性。

### Q2: 为什么 Consumer 取 categoryName 要调 product Feign 而不是直接从 t_spu 表查？

t_spu 表没有 categoryName 列——只有 categoryId。取分类名需要 JOIN t_category，但 Consumer 只拿到 Canal 推送的 t_spu Binlog 数据。Feign 调 product 是最简单的方式——product 已经在读取 SPU 时做了 Category JOIN。

### Q3: 补偿 Job 的 buildProductDocument 为什么不用 Feign 补全？

补偿的 spuId 可能是因为 ES 异常失败，也可能是因为 SPU 已删除（Consumer 也记录了）。对已删除的 SPU 调 Feign → product 返回 null。调 Feign 反而增加不确定性。补偿 Job 只用 DB 数据——images 从 JSON 提取，categoryName 留空——确保 ES 至少有基础字段。

### Q4: 全量重建凌晨 4 点跑，白天索引脏了怎么办？

全量重建是最后一道防线——日常靠实时消费 + 5min 补偿。脏索引有两种情况：（1）Consumer 逻辑 bug → 修 bug + 手动触发重建；（2）ES 数据损坏 → 等凌晨重建。如果是紧急情况，调 `/api/product/reconcile` 手动触发（如果有的话——当前无此端点，可作为改进方向）。
