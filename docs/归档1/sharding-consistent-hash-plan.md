# ShardingSphere 一致性 Hash 迁移方案

> 日期：2026-06-02 | 状态：方案阶段

## 背景

当前订单服务使用 MOD 算法分库分表：
- `ds${user_id % 4}` + `t_order_${(user_id / 4) % 4}`

问题：扩容时必须全量数据搬迁，无法在线扩容。

目标：MOD → 一致性 Hash，支持在线扩容（4→8→16 节点无感知）。

## 代码改动清单

### 1. sharding-config.yaml

```yaml
# 当前 (MOD)
algorithm-expression: ds${user_id % 4}

# 目标 (一致性 Hash)
type: COSID
props:
  logic-name-prefix: ds
```

### 2. 新增 ShardingAlgorithm

```java
// ConsistentHashShardingAlgorithm.java
public class ConsistentHashShardingAlgorithm implements StandardShardingAlgorithm<Long> {
    private final TreeMap<Integer, String> ring = new TreeMap<>();
    private static final int VIRTUAL_NODES = 150;
    
    public String doSharding(Collection<String> targets, PreciseShardingValue<Long> value) {
        int hash = fnv1a(value.getValue());
        Map.Entry<Integer, String> entry = ring.ceilingEntry(hash);
        return entry != null ? entry.getValue() : ring.firstEntry().getValue();
    }
}
```

## 迁移步骤

### 阶段一：双写过渡（7天）

1. 保持 MOD 路由为读写
2. 新增一致性 Hash 路由，**只写不读**
3. 同时写入两个路由，对比数据一致性

### 阶段二：历史数据迁移（1天）

```sql
-- 按 MOD 分片逐个扫描，插入一致性 Hash 分片
SELECT * FROM ds0.t_order_0;  -- → Hash 路由写入
SELECT * FROM ds0.t_order_1;  -- → Hash 路由写入
...
```

### 阶段三：全量验证（1天）

```bash
# 对比双路由数据一致性
for db in ds0 ds1 ds2 ds3; do
  mysql -e "SELECT COUNT(*) FROM $db.t_order_0"  # MOD
  mysql -e "SELECT COUNT(*) FROM $db.t_order_0"  # Hash
done
```

### 阶段四：切换只读一致性 Hash（灰度）

1. 10% 流量 → Hash 读
2. 50% 流量 → Hash 读
3. 100% 流量 → Hash 读
4. 关闭 MOD 写入

### 回滚步骤

每步保留 MOD 写入能力，如发现问题立即切回 MOD 读写。

### 扩容模拟测试脚本

```bash
# 4→8 节点模拟
# 1. 新建 ds4~ds7 分片
# 2. 修改 ring 配置为 8 节点
# 3. 只有约 50% 数据需要迁移（一致性 Hash 特性）
# 4. 验证：环比 4 节点时数据正确性
```
