# 分库分表实战

> 跨服务专题 | 开发阶段：Phase-5 | 状态：⏳ 待开发

---

## 🎯 一、为什么需要分库分表

```
问题：订单表 10 亿数据，单表查询 > 5 秒
原因：MySQL 单表超过 5000 万行后，B+树层数增加，IO 次数增多

解决：ShardingSphere 分库分表
  10 亿订单 ÷ 4 库 ÷ 16 表/库 = 每表 1562 万（MySQL 舒适区）
```

---

## 🏗️ 二、分片策略

### 2.1 订单服务

| 维度 | 策略 | 说明 |
|------|------|------|
| 分片键 | buyer_id | 同一用户的订单在同一库，避免跨库查询 |
| 分片算法 | buyer_id % 4（库）, buyer_id % 16（表） | 均匀分布 |
| 库数×表数 | 4 库 × 16 表/库 = 64 表 | 支撑 10 亿数据 |
| 主键 | 雪花 ID | 不依赖 MySQL auto-increment |
| 读写分离 | ShardingSphere 主从路由 | 写走主库，读走从库 |

### 2.2 优惠券服务

| 维度 | 策略 | 说明 |
|------|------|------|
| 分片键 | user_id | 同一用户的券在同一库 |
| 分片算法 | user_id % 2（库）, user_id % 8（表） | 均匀分布 |
| 库数×表数 | 2 库 × 8 表/库 = 16 表 | 支撑 10 亿用户券 |

---

## 💻 三、ShardingSphere 配置

```yaml
spring:
  shardingsphere:
    datasource:
      names: ds0,ds1,ds2,ds3
      ds0:
        url: jdbc:mysql://localhost:3306/my_xhs_order_0
      ds1:
        url: jdbc:mysql://localhost:3306/my_xhs_order_1
      ds2:
        url: jdbc:mysql://localhost:3306/my_xhs_order_2
      ds3:
        url: jdbc:mysql://localhost:3306/my_xhs_order_3
    rules:
      sharding:
        tables:
          t_order:
            actual-data-nodes: ds$->{0..3}.t_order_$->{0..15}
            database-strategy:
              standard:
                sharding-column: buyer_id
                sharding-algorithm-name: db-mod
            table-strategy:
              standard:
                sharding-column: buyer_id
                sharding-algorithm-name: table-mod
        sharding-algorithms:
          db-mod:
            type: MOD
            props:
              sharding-count: 4
          table-mod:
            type: MOD
            props:
              sharding-count: 16
```

---

## ⚖️ 四、核心问题与解决

### 4.1 跨库 JOIN

| 问题 | 解决方案 |
|------|---------|
| 按订单号查询（非分片键） | 订单号中嵌入 buyer_id 信息，或建映射表 |
| 后台管理全量查询 | 走 ES 宽表（Canal 同步），不走分库分表 |
| 跨库聚合统计 | 各库分别统计 → 应用层合并 |

### 4.2 全局排序分页

```
问题：ORDER BY created_at LIMIT 10 OFFSET 100
→ ShardingSphere 需要从每个库取 110 条 → 内存排序 → 取 10 条
→ 深分页时内存爆炸

解决：
1. 游标分页（推荐）：WHERE created_at < lastValue LIMIT 10
2. 禁止深分页：前端限制最多翻 100 页
```

### 4.3 扩容方案

```
4 库 → 8 库（倍扩）：
1. 新增 4 个库（ds4~ds7）
2. 数据迁移：ds0 的偶数表 → ds4，ds0 的奇数表留原地
3. 修改分片规则：buyer_id % 8
4. 双写验证 → 切换 → 清理旧数据
```

---

## 🐛 五、踩坑记录

### 5.1 分库分表后自增 ID 冲突

- **现象**：不同库生成了相同的自增 ID
- **解决**：全部改用雪花 ID（CosId）

### 5.2 非分片键查询全库扫描

- **现象**：按 order_no 查询，ShardingSphere 扫描所有库
- **解决**：order_no 中嵌入 buyer_id 信息，或建 order_no → buyer_id 映射表

---

## 🎤 六、面试考察点

### Q1: 分片键怎么选？

> 1. "选查询最频繁的字段：用户查自己的订单 → buyer_id"
> 2. "保证同一用户数据在同一库：避免跨库 JOIN"
> 3. "数据均匀分布：buyer_id 取模天然均匀"

### Q2: 分库分表后扩容怎么处理？

> 1. "倍扩方案：4 库 → 8 库，只需迁移 50% 数据"
> 2. "双写验证：新旧库同时写入，对比数据一致后切换"
> 3. "在线迁移：ShardingSphere 扩容工具，不停服"

---

## 📚 参考资料

| 资料 | 参考内容 |
|------|----------|
| 📄 phase-5/README.md §3.26 | 分库分表实战完整设计 |
| 📄 03-distributed-solutions.md §2 | 分库分表 4 方案对比 |
