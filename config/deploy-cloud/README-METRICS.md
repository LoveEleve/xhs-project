# 补充监控说明(2026-08-13 本机验证)

1. RocketMQ 中间件监控(textfile collector 方案): 官方 5.1.4 镜像无内置 metrics 模块、
   apache/rocketmq-exporter 客户端版本不兼容, 本方案零依赖可用。
   部署: install -m755 config/deploy-cloud/rocketmq-metrics.sh /usr/local/bin/
         mkdir -p /data/rocketmq-textfile
         crontab -e 加: */5 * * * * /usr/local/bin/rocketmq-metrics.sh
   node-exporter 已在 compose 挂载 /data/rocketmq-textfile 并启用 textfile 收集器。
   指标:
     - rocketmq_broker_* (运行时长/磁盘/写入读取TPS)
     - rocketmq_consumer_* (lag/tps)
     - rocketmq_topics_count / rocketmq_dlq_topics / rocketmq_retry_topics
     - rocketmq_up (三类采集全部成功才为 1)
     - rocketmq_scrape_success{step=*} / rocketmq_scrape_timestamp_seconds
   说明: `rocketmq_up` 已按分步骤成功/失败收敛, 避免 mqadmin 失败时仍误报 up=1。

2. MySQL 从库复制监控: mysqld-exporter-slave(9105, 连 3307)采集 slave_status,
   Prometheus job 'mysql-slave' 已配置。

3. MySQL 死锁/锁竞争: 实证 MySQL 8.0.46 无 Innodb_deadlocks 状态变量, exporter 无死锁计数;
   监控方案(已实测造死锁验证闭环) =
   a) 行锁等待速率/当前等待/平均·最长等待 面板(死锁瞬间跳高);
   b) innodb_print_all_deadlocks=ON(compose 已持久化, 所有死锁写错误日志);
   c) mysql-deadlock-metrics.sh v2(每5分钟, 对比 LATEST DETECTED DEADLOCK 时间戳变化)
      -> mysql_innodb_deadlock_total 累计次数 / new_events 新事件 / 最近时间戳 面板。
      已实测: 造死锁 2 次, 计数 1→2 正确递增。
   部署: install -m755 config/deploy-cloud/mysql-deadlock-metrics.sh /usr/local/bin/
         crontab -e 加: */5 * * * * /usr/local/bin/mysql-deadlock-metrics.sh

4. Grafana 9 个看板: 布局双列; ${DS_PROMETHEUS} 已替换本地 uid;
   JVM 32 / MQ 11 / MySQL 17(含复制+锁) / ES 11 / node 14 / redis 12 / api 9 / biz 5 / tomcat 8。
