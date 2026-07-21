# my-xhs 卖家维度订单查询方案

> 订单按 buyer_id 分4库，但卖家需要查看自己商品的订单——这是电商分片的经典矛盾。
> 买家维度查单快，卖家维度查单慢，必须解决。

---

## 一、问题分析

### 1.1 分片策略回顾

```
当前设计（03-distributed-solutions）：
  订单表按 buyer_id 分4库
  - db_order_0: buyer_id % 4 = 0
  - db_order_1: buyer_id % 4 = 1
  - db_order_2: buyer_id % 4 = 2
  - db_order_3: buyer_id % 4 = 3

优点：买家查单 O(1)——直接路由到目标库
缺点：卖家查单 O(N)——需要扫4个库
```

### 1.2 两种查询维度对比

| 查询维度 | SQL示例 | 频率 | 当前性能 | 问题 |
|----------|---------|------|---------|------|
| 买家查单 | `WHERE buyer_id = ?` | 高频 | O(1) | 无 |
| 卖家查单 | `WHERE seller_id = ?` | 中频 | O(N) 扫4库 | 慢！ |

### 1.3 为什么不能按 seller_id 分片？

```
如果按 seller_id 分片：
  - 卖家查单 O(1)，但买家查单 O(N) —— 一样的问题
  - 而且买家查单频率 > 卖家查单频率
  - 所以 buyer_id 分片是最优选择

核心矛盾：一个分片键只能服务一个查询维度
  → 需要额外的方案来服务第二个维度
```

---

## 二、方案对比

| 方案 | 原理 | 优点 | 缺点 | 选择 |
|------|------|------|------|------|
| 方案A：ES宽表 | 订单数据同步到ES，按seller_id查询 | 查询快、支持复杂搜索 | 数据延迟、ES成本 | ✅ 推荐 |
| 方案B：双写 | 写入时同时写buyer库和seller库 | 实时性好 | 双写一致性、存储翻倍 | 备选 |
| 方案C：全局表 | 每个库都有完整订单数据 | 查询简单 | 数据冗余大 | ❌ |
| 方案D：异构索引 | 额外表存seller_id→order_id映射 | 存储小 | 多一次查询 | ✅ 推荐 |

---

## 三、推荐方案：ES宽表 + 异构索引双保险

### 3.1 方案A：ES宽表（主方案）

```
架构：
  MySQL(t_order按buyer_id分4库)
    → Canal监听Binlog
    → RocketMQ传输
    → Logstash/自研Consumer消费
    → ES订单索引（包含seller_id字段）

查询流程：
  卖家查单 → 搜索ES → 获取order_id列表
          → 用order_id到MySQL查详情（buyer_id路由）

ES索引设计：
{
  "mappings": {
    "properties": {
      "orderId": { "type": "long" },
      "buyerId": { "type": "long" },
      "sellerId": { "type": "long" },       // ← 关键字段
      "productId": { "type": "long" },
      "productName": { "type": "text", "analyzer": "ik_max_word" },
      "skuDesc": { "type": "text", "analyzer": "ik_max_word" },
      "orderAmount": { "type": "double" },
      "orderStatus": { "type": "keyword" },
      "payTime": { "type": "date" },
      "createTime": { "type": "date" },
      "payType": { "type": "keyword" },
      "spuName": { "type": "text", "analyzer": "ik_max_word" }
    }
  }
}

ES查询：
  GET /order/_search
  {
    "query": {
      "bool": {
        "must": [
          { "term": { "sellerId": 12345 } },
          { "term": { "orderStatus": "PAID" } }    // 可按状态筛选
        ]
      }
    },
    "sort": [{ "createTime": "desc" }],
    "from": 0, "size": 20
  }
```

### 3.2 方案D：异构索引（兜底方案）

```
当ES不可用时，用异构索引表兜底

异构索引表（每个分库一张）：
CREATE TABLE t_order_seller_idx (
    id          BIGINT PRIMARY KEY,
    seller_id   BIGINT NOT NULL COMMENT '卖家ID',
    order_id    BIGINT NOT NULL COMMENT '订单ID',
    buyer_id    BIGINT NOT NULL COMMENT '买家ID（用于路由）',
    order_status VARCHAR(20) COMMENT '订单状态（冗余，用于筛选）',
    create_time DATETIME NOT NULL,
    INDEX idx_seller (seller_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='卖家维度订单索引';

写入流程：
  1. 创建订单时，同时写入 t_order 和 t_order_seller_idx
  2. 两者在同一事务中（同一个分库内）

查询流程：
  卖家查单 → 扫4个库的t_order_seller_idx → 获取order_id+buyer_id
          → 用buyer_id路由到目标库查t_order详情

4库扫描性能：
  - 每个库索引命中 → 毫秒级
  - 4个库并行查询 → 总耗时 = max(4库) ≈ 20ms
  - 可接受！
```

### 3.3 双方案配合

```
正常情况：
  卖家查单 → ES查询（快，支持复杂搜索）→ 毫秒级

ES不可用（降级）：
  卖家查单 → 4库并行扫t_order_seller_idx → 20ms级

两者互补：
  - ES：复杂搜索（按商品名/时间范围/金额区间）
  - 异构索引：简单查询（按卖家+状态），ES降级时的兜底
```

---

## 四、数据一致性保障

### 4.1 ES同步一致性

```
Canal → MQ → ES 三级串联的一致性问题：

1. Canal延迟 → ES数据落后于MySQL
   解决：可接受（卖家查单允许秒级延迟）

2. Canal丢事件 → ES缺少某些订单
   解决：全量重建（每天凌晨） + 增量对账

3. 消费失败 → ES数据不完整
   解决：死信队列 + 人工重试 + 全量对账

增量对账方案（每小时）：
  - 查MySQL最近1小时变更的order_id集合
  - 查ES最近1小时的order_id集合
  - 差集 = 不一致的order_id
  - 对差集order_id重新从MySQL读数据写入ES
```

### 4.2 异构索引一致性

```
t_order_seller_idx 和 t_order 在同一事务中写入
→ 强一致，不存在不一致问题

但注意：
  - 订单状态变更时，需要同步更新 t_order_seller_idx.order_status
  - 在同一分库内，可以用触发器或业务代码同步更新
```

---

## 五、卖家订单列表查询实现

### 5.1 优先走ES

```java
@Service
public class SellerOrderQueryService {

    @Autowired private OrderSearchService orderSearchService;  // ES
    @Autowired private OrderMapper orderMapper;
    @Autowired private OrderSellerIdxMapper sellerIdxMapper;    // 异构索引

    /**
     * 卖家查单 — 优先ES，降级走异构索引
     */
    public PageResult<OrderVO> querySellerOrders(SellerOrderQuery query) {
        try {
            // 1. 优先走ES（支持复杂搜索）
            return queryFromES(query);
        } catch (Exception e) {
            log.warn("ES查询失败，降级走异构索引: {}", e.getMessage());
            // 2. 降级走异构索引（简单查询）
            return queryFromIndex(query);
        }
    }

    private PageResult<OrderVO> queryFromES(SellerOrderQuery query) {
        // ES查询 → 获取orderId列表
        List<Long> orderIds = orderSearchService.searchBySeller(
            query.getSellerId(), query.getStatus(),
            query.getPage(), query.getSize()
        );

        // 用orderId到MySQL查详情
        List<OrderVO> orders = orderIds.stream()
            .map(orderId -> {
                // 从ES结果中获取buyerId用于路由
                Long buyerId = ...; // ES返回中包含
                return orderMapper.selectByBuyerIdAndOrderId(buyerId, orderId);
            })
            .filter(Objects::nonNull)
            .map(this::toVO)
            .toList();

        return new PageResult<>(orders, query.getPage(), query.getSize());
    }

    private PageResult<OrderVO> queryFromIndex(SellerOrderQuery query) {
        // 4库并行查询异构索引
        List<CompletableFuture<List<OrderSellerIdx>>> futures = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final int dbIndex = i;
            futures.add(CompletableFuture.supplyAsync(() ->
                sellerIdxMapper.selectBySeller(dbIndex, query.getSellerId(),
                    query.getStatus(), query.getPage(), query.getSize()),
                executor
            ));
        }

        // 合并4库结果
        List<OrderSellerIdx> allIdx = futures.stream()
            .flatMap(f -> f.join().stream())
            .sorted(Comparator.comparing(OrderSellerIdx::getCreateTime).reversed())
            .skip((long) query.getPage() * query.getSize())
            .limit(query.getSize())
            .toList();

        // 用buyerId路由查详情
        List<OrderVO> orders = allIdx.stream()
            .map(idx -> orderMapper.selectByBuyerIdAndOrderId(idx.getBuyerId(), idx.getOrderId()))
            .filter(Objects::nonNull)
            .map(this::toVO)
            .toList();

        return new PageResult<>(orders, query.getPage(), query.getSize());
    }
}
```

---

## 六、生产决策与表达

### Q: 订单按buyer_id分片，卖家怎么查单？

> "三种方案我都考虑过：ES宽表、双写、异构索引。最终选择ES宽表为主+异构索引兜底。Canal同步订单数据到ES，ES索引包含seller_id字段，卖家查单走ES毫秒级。ES不可用时降级走异构索引——每个分库有一张t_order_seller_idx表，和t_order在同一事务中写入，4库并行查询20ms内完成。ES还支持复杂搜索（按商品名/时间/金额），异构索引只做简单查询兜底。"

### Q: ES数据和MySQL不一致怎么办？

> "三层保障：第一层Canal准实时同步（秒级延迟）；第二层每小时增量对账——对比MySQL和ES最近1小时的order_id差集，差集重写ES；第三层每天凌晨全量重建ES索引——极端情况的最终兜底。卖家查单允许秒级延迟，一致性要求低于买家查单。"
