# my-xhs 数据增量对账方案

> Canal → MQ → ES 三级串联，任何一级延迟/失败都会导致ES数据不一致。
> 光有全量重建兜底不够——全量重建耗时长、影响性能，需要增量对账做日常保障。

---

## 一、问题分析

### 1.1 数据同步链路

```
MySQL (主数据源)
  → Canal (监听Binlog)
    → RocketMQ (消息传输)
      → Consumer (消费写入ES)

每一级都可能出问题：
  Canal: 延迟、丢事件、解析错误
  MQ: 消息丢失、消费失败、顺序错乱
  Consumer: OOM重启、消费超时、写入ES失败
```

### 1.2 不一致场景

| 场景 | 原因 | 影响范围 | 严重程度 |
|------|------|---------|---------|
| 订单状态不一致 | 消费失败，ES中订单仍为"待支付" | 卖家看到错误状态 | 🔴 严重 |
| 笔记搜索不到 | Canal延迟，ES中缺少新笔记 | 用户发布后搜不到 | 🟡 中等 |
| 计数不一致 | Buffer-Trigger合并写入时丢失 | 点赞数不准 | 🟡 中等 |
| 商品信息过期 | ES中商品价格/库存未更新 | 用户看到错误价格 | 🔴 严重 |
| 用户信息不一致 | 消费延迟，ES中昵称/头像旧 | 搜索结果信息旧 | 🟢 轻微 |

---

## 二、增量对账方案设计

### 2.1 对账架构

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│   MySQL      │     │   对账引擎    │     │     ES       │
│  (主数据源)   │────▶│  (对比+修复)  │◀────│  (从数据源)   │
└──────────────┘     └──────┬───────┘     └──────────────┘
                            │
                     ┌──────▼───────┐
                     │   告警通知    │
                     └──────────────┘

对账引擎核心逻辑：
  1. 查MySQL最近N分钟变更的数据（按update_time）
  2. 查ES中相同ID的数据
  3. 逐字段对比
  4. 不一致的修复（重新从MySQL读数据写入ES）
  5. 修复失败则告警
```

### 2.2 对账策略

| 业务 | 对账频率 | 对账字段 | 修复策略 |
|------|---------|---------|---------|
| 订单 | 每5分钟 | status, amount, payTime | 即时修复 |
| 商品 | 每10分钟 | price, stock, status | 即时修复 |
| 笔记 | 每30分钟 | title, content, status | 即时修复 |
| 用户 | 每1小时 | nickname, avatar, status | 延迟修复 |
| 计数 | 每1小时 | 各种计数值 | 延迟修复 |

### 2.3 对账SQL模板

```sql
-- 查询MySQL最近5分钟变更的订单
SELECT id, buyer_id, seller_id, order_status, order_amount,
       pay_time, update_time
FROM t_order
WHERE update_time >= DATE_SUB(NOW(), INTERVAL 5 MINUTE)
ORDER BY update_time DESC
LIMIT 10000;

-- 注意：
-- 1. update_time必须有索引
-- 2. 限制LIMIT防止一次性查太多数据
-- 3. 分页处理（offset递增）
```

---

## 三、对账引擎实现

### 3.1 通用对账框架

```java
/**
 * 通用对账引擎
 * 对比MySQL和ES的数据一致性
 */
@Service
@Slf4j
public class ReconcileEngine {

    @Autowired private MongoTemplate mongoTemplate;  // 对账结果存储
    @Autowired private RestHighLevelClient esClient;
    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * 执行对账
     * @param config 对账配置
     */
    public ReconcileResult reconcile(ReconcileConfig config) {
        String bizType = config.getBizType();
        int minutes = config.getReconcileMinutes();
        List<String> fields = config.getFields();

        // 1. 从MySQL查最近变更的数据
        List<Map<String, Object>> mysqlData = queryMySQL(config, minutes);

        // 2. 从ES查相同ID的数据
        List<Map<String, Object>> esData = queryES(config, mysqlData);

        // 3. 逐条对比
        List<ReconcileDiff> diffs = new ArrayList<>();
        for (Map<String, Object> mysqlRow : mysqlData) {
            String id = String.valueOf(mysqlRow.get("id"));
            Map<String, Object> esRow = findBy(esData, id);

            if (esRow == null) {
                // ES中缺失
                diffs.add(ReconcileDiff.missing(id, bizType));
                continue;
            }

            // 对比指定字段
            for (String field : fields) {
                Object mysqlValue = mysqlRow.get(field);
                Object esValue = esRow.get(field);
                if (!Objects.equals(mysqlValue, esValue)) {
                    diffs.add(ReconcileDiff.conflict(id, bizType, field,
                        mysqlValue, esValue));
                }
            }
        }

        // 4. 自动修复
        int fixedCount = 0;
        for (ReconcileDiff diff : diffs) {
            try {
                fix(diff, config);
                fixedCount++;
            } catch (Exception e) {
                log.error("对账修复失败: {}", diff, e);
                // 告警
                alert(diff, e);
            }
        }

        // 5. 记录对账结果
        ReconcileResult result = new ReconcileResult();
        result.setBizType(bizType);
        result.setTotalChecked(mysqlData.size());
        result.setDiffCount(diffs.size());
        result.setFixedCount(fixedCount);
        result.setFailedCount(diffs.size() - fixedCount);
        mongoTemplate.save(result);

        return result;
    }

    /**
     * 修复：重新从MySQL读数据写入ES
     */
    private void fix(ReconcileDiff diff, ReconcileConfig config) {
        if (diff.isMissing()) {
            // ES缺失 → 全量重新同步
            fullSync(diff.getId(), config);
        } else {
            // 字段不一致 → 增量更新
            partialUpdate(diff.getId(), diff.getField(),
                diff.getMysqlValue(), config);
        }
    }
}
```

### 3.2 定时对账任务

```java
@Component
public class ReconcileScheduler {

    // 订单对账：每5分钟
    @Scheduled(fixedRate = 300000)
    public void reconcileOrder() {
        ReconcileConfig config = ReconcileConfig.builder()
            .bizType("ORDER")
            .reconcileMinutes(5)
            .fields(List.of("orderStatus", "orderAmount", "payTime"))
            .mysqlTable("t_order")
            .esIndex("order")
            .build();
        reconcileEngine.reconcile(config);
    }

    // 商品对账：每10分钟
    @Scheduled(fixedRate = 600000)
    public void reconcileProduct() {
        ReconcileConfig config = ReconcileConfig.builder()
            .bizType("PRODUCT")
            .reconcileMinutes(10)
            .fields(List.of("price", "stock", "status"))
            .mysqlTable("t_spu")
            .esIndex("product")
            .build();
        reconcileEngine.reconcile(config);
    }

    // 笔记对账：每30分钟
    @Scheduled(fixedRate = 1800000)
    public void reconcileNote() {
        ReconcileConfig config = ReconcileConfig.builder()
            .bizType("NOTE")
            .reconcileMinutes(30)
            .fields(List.of("title", "status"))
            .mysqlTable("t_note")
            .esIndex("note")
            .build();
        reconcileEngine.reconcile(config);
    }
}
```

---

## 四、全量重建方案（兜底）

### 4.1 全量重建触发条件

```
触发全量重建的条件：
1. 增量对账发现diff率 > 5%（严重不一致）
2. ES索引Mapping变更（新增/修改字段）
3. ES集群故障恢复后
4. 重大版本发布后
5. 手动触发
```

### 4.2 全量重建流程

```
全量重建（零停机）：
1. 创建新索引：order_v2（新Mapping）
2. 全量同步：MySQL → 新索引（分页批量读取，Bulk写入）
3. 切换别名：order别名从order_v1切到order_v2
4. 删除旧索引：order_v1（确认无流量后）
5. 验证数据量：MySQL COUNT vs ES COUNT

注意事项：
- 步骤2全量同步可能耗时数小时（5000万订单）
- 期间增量数据通过Canal持续同步到新索引
- 步骤3别名切换原子操作，无停机
- 步骤4延迟24小时删除旧索引，保留回滚能力
```

### 4.3 全量重建脚本

```bash
#!/bin/bash
# 全量重建ES索引脚本
# 用法：./rebuild_es_index.sh order

INDEX_NAME=$1
NEW_INDEX="${INDEX_NAME}_v$(date +%Y%m%d%H%M)"
ALIAS_NAME="${INDEX_NAME}"

echo "开始全量重建: ${INDEX_NAME} → ${NEW_INDEX}"

# 1. 创建新索引
curl -X PUT "http://es:9200/${NEW_INDEX}" -H 'Content-Type: application/json' -d @mapping/${INDEX_NAME}.json

# 2. 全量同步（Java程序，分页读取MySQL，Bulk写入ES）
java -jar es-rebuilder.jar --index=${NEW_INDEX} --batch=5000

# 3. 切换别名（原子操作）
curl -X POST "http://es:9200/_aliases" -H 'Content-Type: application/json' -d '{
  "actions": [
    {"remove": {"index": "*", "alias": "'${ALIAS_NAME}'"}},
    {"add": {"index": "'${NEW_INDEX}'", "alias": "'${ALIAS_NAME}'"}}
  ]
}'

echo "全量重建完成: ${NEW_INDEX}"
```

---

## 五、监控与告警

### 5.1 对账监控指标

| 指标 | 含义 | 告警阈值 |
|------|------|---------|
| `reconcile_diff_count` | 每次对账发现的不一致数 | >50 |
| `reconcile_diff_rate` | 不一致率 | >1% |
| `reconcile_fix_failed` | 修复失败数 | >0 |
| `reconcile_duration_ms` | 对账耗时 | >60000 |
| `canal_delay_seconds` | Canal延迟 | >30 |
| `mq_consumer_lag` | MQ消费堆积 | >5000 |

### 5.2 告警规则

```yaml
# Prometheus告警规则
groups:
  - name: data_consistency
    rules:
      - alert: DataInconsistencyHigh
        expr: reconcile_diff_rate > 0.01
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "数据不一致率超过1%"
          
      - alert: ReconcileFixFailed
        expr: reconcile_fix_failed > 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "对账修复失败，需人工介入"
          
      - alert: CanalDelayHigh
        expr: canal_delay_seconds > 30
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Canal延迟超过30秒"
```

---

## 六、生产决策与表达

### Q: Canal到ES的数据怎么保证一致性？

> "三层保障：第一层Canal准实时同步，秒级延迟；第二层增量对账，每5-30分钟自动对比MySQL和ES的数据差异，发现不一致自动修复；第三层全量重建兜底，每天凌晨全量重建ES索引。增量对账是我们日常数据一致性的主要保障——对比MySQL的update_time和ES的文档，差异自动重新同步。修复失败则告警，人工介入。"

### Q: 增量对账会不会影响线上性能？

> "不会。对账查询走MySQL从库，不影响主库。ES查询是普通GET请求，QPS极低。对账修复是单条文档更新，不影响批量写入。而且对账是分时段执行——订单5分钟对账一次，每次只查5分钟内变更的数据，数据量很小。全量重建走新索引+别名切换，零停机，不影响线上查询。"
