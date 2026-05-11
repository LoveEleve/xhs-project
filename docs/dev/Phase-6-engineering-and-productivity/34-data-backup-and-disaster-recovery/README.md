# 数据备份与容灾

> 所属维度：数据安全 | 开发阶段：Phase-6 | 核心指标：RTO / RPO

---

## 🎯 一、备份策略

| 数据类型 | 备份方式 | 备份频率 | 保留周期 |
|----------|----------|----------|----------|
| MySQL | 全量备份(mysqldump) + binlog增量 | 全量每天凌晨 + binlog实时 | 全量7天，binlog30天 |
| Redis | RDB快照 + AOF | RDB每6小时 + AOF每秒 | RDB 7天 |
| ES | Snapshot | 每天凌晨 | 7天 |
| 代码/配置 | Git | 每次提交 | 永久 |

---

## 🏗️ 二、恢复方案

| 故障场景 | 恢复方案 | RTO | RPO |
|----------|----------|-----|-----|
| MySQL误删数据 | 从从库恢复 + binlog回放 | < 30分钟 | < 1分钟 |
| MySQL主库宕机 | ShardingSphere主从切换 | < 30秒 | 0(同步复制) |
| Redis数据丢失 | 从MySQL全量重建 | < 5分钟 | < 1分钟(取决于最后对账时间) |
| ES索引损坏 | XXL-Job全量重建 | < 30分钟 | 0(从MySQL源数据重建) |
| 整机故障 | K8s重新调度 + PVC数据卷 | < 5分钟 | 取决于最后一次备份 |

> **RTO**(Recovery Time Objective)：从故障到恢复的最大允许时间
> **RPO**(Recovery Point Objective)：允许丢失的最大数据量（用时间衡量）

---

## 💻 三、备份自动化

### 3.1 MySQL备份脚本

```bash
#!/bin/bash
# deploy/scripts/mysql-backup.sh
# 每天凌晨2点由XXL-Job触发

# 全量备份
mysqldump -h mysql-master -u root -proot123 \
  --single-transaction --flush-logs \
  --all-databases | gzip > /backup/mysql/full_$(date +%Y%m%d).sql.gz

# 清理7天前的备份
find /backup/mysql -name "*.sql.gz" -mtime +7 -delete

# 上传到对象存储（生产环境）
# ossutil cp /backup/mysql/ oss://my-xhs-backup/mysql/
```

### 3.2 Redis恢复流程

```
Redis数据丢失(主从切换/内存淘汰/误删)
    ↓
1. 从MySQL全量重建关键数据
   - 用户信息 → user:info:{userId}
   - 购物车 → cart:items:{userId}
   - 计数数据 → counter:{bizType}:{bizId}
    ↓
2. XXL-Job对账修复(Redis vs MySQL差异检测)
    ↓
3. 非关键数据靠自然回填(下次访问时从DB加载)
```

### 3.3 ES全量重建

```
ES索引损坏/数据不一致
    ↓
1. XXL-Job触发全量重建任务
2. 按ID分页查询MySQL + 断点续传
3. 批量写入ES(Bulk API, 每批1000条)
4. 完成后对账(MySQL count vs ES count)
```

---

## ⚖️ 四、方案对比

| 维度 | mysqldump | xtrabackup | MySQL复制 |
|------|-----------|-----------|-----------|
| 备份速度 | 慢(逻辑备份) | 快(物理备份) | 实时 |
| 恢复速度 | 慢(重放SQL) | 快(拷贝文件) | 即时(从库可读) |
| 锁影响 | --single-transaction无锁(InnoDB) | 无锁 | 无锁 |
| 复杂度 | 低 | 中 | 低 |

**最终选择**：mysqldump全量(简单可靠) + binlog增量(实时) + 主从复制(热备)

---

## 🎤 五、面试考察点

### Q1: 数据误删了怎么恢复？

**推荐回答思路**：

> 1. "MySQL：主从延迟窗口内从从库恢复；超过窗口用全量备份+binlog回放到指定时间点"
> 2. "Redis：从MySQL全量重建+XXL-Job对账修复"
> 3. "ES：从MySQL源数据全量重建索引"

### Q2: RTO和RPO怎么定的？

**推荐回答思路**：

> 1. "核心链路(下单/支付)RTO<30秒——靠主从自动切换"
> 2. "数据RPO<1分钟——MySQL binlog实时复制、Redis AOF每秒持久化"
> 3. "非核心功能(搜索/推荐)RTO<30分钟——可接受降级"

---

## 📚 参考资料

| 资料 | 相关章节 | 参考内容 |
|------|----------|----------|
| 📄 04-基础设施与部署.md | §12 | 数据备份与恢复完整方案 |
| 📄 03-分布式解决方案.md | §11 | 灾备与故障恢复 |
