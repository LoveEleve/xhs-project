# my-xhs 数据归档策略

> 日期：2026-06-02 | 状态：方案阶段

## 目标

4 张核心表冷热分离，热数据 < 3 个月，冷数据归档，确保查询性能。

| 表 | 库 | 预估日增量 | 热数据保留 | 归档目标 |
|------|------|:---:|------|------|
| t_order (分片) | 13308 | ~10000 | 3个月 | order_archive |
| t_user_behavior | 13306 | ~50000 | 1个月 | behavior_archive |
| t_local_message | 13308 | ~5000 | 7天 | 定时清理 |
| t_notification | 13306 | ~20000 | 3个月 | notification_archive |

## 实施方案

### 方案一：分区表（推荐）

```sql
ALTER TABLE t_order_0 PARTITION BY RANGE (TO_DAYS(created_at)) (
    PARTITION p202604 VALUES LESS THAN (TO_DAYS('2026-05-01')),
    PARTITION p202605 VALUES LESS THAN (TO_DAYS('2026-06-01')),
    PARTITION p202606 VALUES LESS THAN (TO_DAYS('2026-07-01')),
    PARTITION p_future VALUES LESS THAN MAXVALUE
);
```

### 方案二：定时任务归档（备选）

```sql
-- XXL-Job Handler，每天凌晨 3 点执行
INSERT INTO order_archive SELECT * FROM t_order_0 WHERE created_at < DATE_SUB(NOW(), INTERVAL 3 MONTH);
DELETE FROM t_order_0 WHERE created_at < DATE_SUB(NOW(), INTERVAL 3 MONTH) LIMIT 1000;
```

## XXL-Job 调度配置

| Handler | Cron | 说明 |
|------|------|------|
| orderArchiveJob | 0 0 3 * * ? | 每天凌晨3点归档订单 |
| behaviorArchiveJob | 0 0 4 * * ? | 每天凌晨4点归档行为数据 |
| localMessageCleanJob | 0 0 2 * * ? | 每天凌晨2点清理旧消息 |
| notificationArchiveJob | 0 0 5 * * ? | 每天凌晨5点归档通知 |

## 恢复方案

```bash
# 恢复指定日期的归档数据
mysql -e "INSERT INTO t_order_0 SELECT * FROM order_archive WHERE created_at BETWEEN '2026-01-01' AND '2026-01-31'"
```
