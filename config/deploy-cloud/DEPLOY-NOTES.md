# MyXHS 部署注意事项与踩坑记录

> 2026-08-13 | 本机(试验机)全流程验证总结。云主机部署前请通读,可避免重复踩坑。
> 每条均为实测结论, 含现象/原因/处理。

## 一、部署包与流程

1. **必须使用最新部署包**: `my-xhs-deploy-package.zip`(330 文件, 2026-08-13)。
   同目录 `.orig-buggy` 为带 bug 旧包, 勿用。
2. **zip 为根目录结构**(docker-compose.yml 在解压根目录), 直接 `docker compose up -d`。
3. **覆盖升级保留数据**: 卷名跟随 compose 项目名(目录名), 项目名不变则卷复用、
   数据保留; MySQL initdb 脚本只在空卷时执行, 不会重跑 init-all.sql。
4. **前置检查**: `systemctl enable docker`(本机原为 disabled, 开机不自启)、
   JDK(/opt/kona-jdk8、17)、canal 镜像已 load、compose v2。
5. **compose 校验坑**: `depends_on` 下若写成 `rocketmq-broker:` 空映射会报
   "must be a mapping" —— 必须带 `condition: service_started`。

## 二、RocketMQ(坑最多)

6. **官方镜像 5.1.4 无内置 Prometheus exporter**(未含 rocketmq-metrics 模块,
   配置 metricsExporterType=PROM 无效); apache/rocketmq-exporter:0.0.2 容器
   内置 4.9.4 客户端与 5.1.4 协议不兼容, 也无法使用。
   → **MQ 中间件监控只能用 textfile 方案**(见 README-METRICS.md §1):
   部署三步: 脚本装 /usr/local/bin + mkdir /data/rocketmq-textfile + cron。
7. **broker.conf 禁止行内注释**: Java Properties 解析会把 `值 # 注释` 的 `#` 后
   内容并入值, 导致配置静默失效回退默认值。实测: `flushDiskType = SYNC_FLUSH # 注释`
   解析失败回退 ASYNC_FLUSH。**所有注释必须独立成行**(对方已按此规范重写)。
8. **加固项**: SYNC_FLUSH(防断电丢消息) + autoCreateTopicEnable=false(防拼错
   topic 静默创建)。已生效, 现有 topic 不受影响, 新 topic 需显式创建。

## 三、MySQL

9. **MySQL 8.0.46 无 Innodb_deadlocks 状态变量**(SHOW GLOBAL STATUS 与
   performance_schema 均查无), mysqld-exporter 无死锁计数指标。
   → 死锁监控方案(已实测造死锁 2 次验证闭环):
   a) 行锁等待速率/当前等待/平均·最长等待 面板(死锁瞬间跳高);
   b) innodb_print_all_deadlocks=ON(compose 已持久化, 所有死锁写错误日志);
   c) mysql-deadlock-metrics.sh(需 cron 每 5 分钟)对比 LATEST DETECTED DEADLOCK
      时间戳变化 → 死锁累计次数/新事件指标。
10. **复制监控需要独立从库 exporter**: 主库 exporter 连 3306, 上面没有复制状态;
    mysqld-exporter-slave(9105, 连 3307, --collect.slave_status)负责。
    Prometheus job 'mysql-slave' 已配置。
11. **从库复制中断修复**: 容器重建后可能 relay log 损坏(报错 1236/open relay log),
    修复: RESET REPLICA ALL; CHANGE MASTER TO MASTER_HOST=..., MASTER_USER='root',
    MASTER_PASSWORD=..., MASTER_AUTO_POSITION=1; START REPLICA;(数据无需重灌,
    前提主库 binlog 未 purge)。
12. **docker exec 执行含中文的 SQL 脚本**: mysql 客户端默认 character_set_client=latin1,
    中文会按 3 字节/字符解释导致 "Data too long"。必须加 `--default-character-set=utf8mb4`。

## 四、镜像与 healthcheck(均实测)

13. `prom/redis-exporter` 镜像不存在 → `oliver006/redis_exporter:v1.58.0-alpine`;
    默认监听 9121, 若规划 9151 需加 `--web.listen-address=:9151`。
14. prom/prometheus 与 victoriametrics 镜像**无 curl**(有 wget) → healthcheck 用 wget。
15. xuxueli/xxl-job-admin 镜像无 curl/wget → healthcheck 用 `bash -c '</dev/tcp/ip/port'`。
16. alertmanager 默认 9093, 而 Prometheus 告警目标指向 19093 →
    需加 `--web.listen-address=:19093`。
17. SkyWalking-OAP telemetry 默认绑 127.0.0.1 → 必须设
    SW_TELEMETRY_PROMETHEUS_HOST=0.0.0.0, 否则 Prometheus 从外网 IP 抓不到。
18. elasticsearch-exporter v1.7.0 已移除 `--es.cluster_settings` 参数(会启动失败)。
19. mysqld-exporter v0.15.1 连接信息从 `/.my.cnf`(相对路径, CWD=/)+ 环境变量
    MYSQLD_EXPORTER_PASSWORD 读取; 密码含 `#` 时不能写进 ini 值(注释符)。
20. redis-exporter 镜像为 scratch 无 shell, healthcheck 必须用可执行文件方式
    (故用 alpine 变体)。

## 五、Grafana / Prometheus

21. 看板 JSON 中 `${DS_PROMETHEUS}` 占位符: provisioning 加载**不会替换**,
    报 "Datasource ${DS_PROMETHEUS} was not found" → 批量替换为实际数据源 uid。
22. 新看板(脚本生成)gridPos.x 全为 0 导致面板全部堆在左侧 → 需重排双列布局。
23. Prometheus 挂载配置/规则文件变更后**不会热加载**(未开 --web.enable-lifecycle)
    → 改完 docker restart my-xhs-prometheus。
24. 新增中间件 exporter 后要核对 Prometheus 目标数(当前 23 个目标全 up)。

## 六、运维遗留

25. 宿主机曾存在**手动启动的 node_exporter 孤儿进程**(工作目录已删)占 9100,
    与容器 node-exporter 冲突 → 部署前 `ss -lntp | grep 9100` 排查。
26. `config/rocketmq/broker-slave.conf` 为旧双 Broker 架构残留, 无任何引用, 可忽略。
27. filebeat 已按对方 25 容器基线从 compose 移除(微服务日志经 TCP 直连 Logstash
    15044, 不依赖 filebeat)。
28. 密码明文分散于 compose/Nacos/配置文件(P-D1 已知待办), 注意保管与安全组
    限制(仅放微服务机 IP)。

---
相关文档: README-METRICS.md(监控方案与部署步骤)、DEPLOY-README.md(对方官方部署说明)。
