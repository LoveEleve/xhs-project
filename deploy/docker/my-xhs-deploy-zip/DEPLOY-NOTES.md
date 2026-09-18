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
   Canal 用宿主机 JDK8(`/opt/openjdk8`)、compose v2。
5. **compose 校验坑**: `depends_on` 下若写成 `rocketmq-broker:` 空映射会报
   "must be a mapping" —— 必须带 `condition: service_started`。
6. **Nacos 配置必须真正导入（否则静默回退本地 yml）**: 服务通过
   `spring.config.import: optional:nacos:my-xhs-common.yaml` 拉取（命名空间 my-xhs）。
   导入方法：使用 `config/nacos/*.yaml` 三个文件，通过 Nacos 控制台或
   `POST /nacos/v1/cs/configs`（dataId/group=DEFAULT_GROUP/tenant=my-xhs）导入；
   仓库内脚本：`scripts/nacos-import-configs.sh`（将 HOST 指向部署机）。
   **注意**: `sql/04-nacos-config-seed.sql` 为 2026-08 历史快照，其中 gateway 键结构
   (`gateway.jwt.secret`) 与 search 数据源端口均已过期，**不要**用它替代上述文件导入；
   仅作历史留档。验证：服务启动日志出现
   `[Nacos Config] Load config[dataId=my-xhs-common.yaml, group=DEFAULT_GROUP] success`。

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

30. **Tomcat 监控注意**: 微服务 Micrometer 1.13+ 已移除 tomcat.threads/tomcat.connections 指标,
    仅剩 tomcat_sessions_*。tomcat 看板中"Tomcat 活跃连接数"面板无数据(指标不存在, 已移除);
    JVM 线程面板仅为近似参考(不等于 Tomcat 工作线程); Web 层真实指标用
    http_server_requests(QPS/耗时)。详见 QUESTIONS-FOR-REVIEW.md B3。
31. **接口监控看板(api-monitor)修复**: 原看板变量 application 无默认值且非 All
    (打开即全 no data); P95/P99/P50/P90 面板按 uri 拆分且用动态注册的
    myxhs_* 指标(gateway/im 为 WebFlux, uri=UNKNOWN, 无该指标)导致大量 no data。
    已修复: ①变量 includeAll=true 默认 All(.*); ②P 分位数/状态码/5xx 面板统一改用
    标准 http_server_requests_seconds_*(15 服务全覆盖)且 sum by (le) 聚合;
    ③5xx 错误率分母 clamp_min 防 0/0=NaN; ④新增 Gateway 专属面板
    (spring_cloud_gateway_requests by routeId, QPS/平均/最大延迟);
    ⑤无流量时段 histogram_quantile 显示 no data 属正常(数据本质)。

32. **JVM 看板(jvm-monitor)修复**: ①Heap 使用率原用 jvm_memory_used_bytes/max_bytes,
    G1 的 Eden/Survivor 区域 max=-1(动态区域无上限)导致"负比例", 改用
    jvm_memory_usage_after_gc_percent(15 服务全有, GC 后 heap 使用率); ②删除 3 个
    微服务不输出指标的无效面板(进程 RSS 内存/进程累计 CPU 时间/缓冲区池使用,
    后两者仅基础设施 canal/OAP 等有且无 application 标签, All 视图会误显示
    基础设施进程线, 选具体服务则 no data); ③Heap Committed vs Max 删除 max
    target(max 含 -1 会出负值); ④布局重排为 29 面板。

33. **A-1 t_item_feature 表(推荐系统依赖, P1)**: 原 init SQL 缺失该表 DDL, 导致
    recommendFeatureJob 每小时失败(内部报错但 handle 200 误导)、推荐 4 项功能
    静默失效(CONTENT/GEO 召回空/精排质量分降级/category=unknown)。已随
    init-all.sql 补建(my_xhs_content 库, uk_note_id/category/quality_score/geo_hash 索引)。
    存量环境执行:
    `mysql -h127.0.0.1 -uroot -p'Xhs@2026#MySQL' my_xhs_content --default-character-set=utf8mb4 < t_item_feature_ddl.sql`
    建表后触发 xxl#19(recommendFeatureJob)验证: t_item_feature 有行。
34. **A-3 RocketMQ Dashboard 登录 403**: dashboard 默认开启登录鉴权
    (inMemoryUserDetailsManager), 登录 API 403 阻塞运维/测试投递。compose 已加
    `rocketmq.config.loginRequired=false`(仅内网, 安全组已限源)。若需保留登录,
    删除该行并配置 rocketmq.config.accessKey/secretKey。
35. **A-4 canal 端口(2026-08-14 修正)**: canal `serverMode=rocketMQ` 时是 **producer**,
    不监听 11111(tcp 模式才用)——nc/telnet 11111 refused 是**预期行为**, 不是故障。
    数据流向: MySQL binlog → canal → RocketMQ topic(NOTE_INDEX_TOPIC/PRODUCT_INDEX_TOPIC/
    INVENTORY_CACHE_TOPIC) → 消费者。正确验证:
    `docker exec my-xhs-mq-broker sh mqadmin topicList -n 127.0.0.1:9876 | grep -E 'NOTE_INDEX|PRODUCT_INDEX|INVENTORY_CACHE'`
    或看 canal 日志(instance 正常拉取 binlog 即健康)。仅需放行 11110/11112(admin/metrics)。
36. **A-5 xxl-job executor_timeout 全 0**: 任务无限时不设超时, 批量对账可能悬挂。
    init-xxljob.sql 已补: 常规任务 60s、对账/ItemCF 类 300s(含存量 UPDATE)。
37. **A-6 SW 采样率**: OAP 侧 trace-sampling-policy-settings.yml 默认 rate=10000(100% 全采)。
    agent 侧采样率由微服务启动参数 SW_AGENT_SAMPLE 决定(模板默认 1/3s), 部署微服务时
    请显式设 SW_AGENT_SAMPLE=3000(100%)避免漏采; 当前 segment 41 万/天为全采量级。
38. **A-7 Grafana 数据源 127.0.0.1:19090**: grafana/prometheus 均为 host 网络同机部署,
    该地址当前正确; 若 Grafana 迁移/跨机部署, 必须改为 Prometheus 实际地址
    (如 http://21.130.247.89:19090), 否则 10 看板全 no data。
39. **A-2 SW traceId 与业务日志打通**: 业务日志 traceId 为 gateway 注入的 X-Trace-Id
    (UUID), 与 SW 自生成 traceId 两套体系。打通方案(微服务侧执行):
    ① log4j2 pattern 增加 `%X{tid}`, 并让 gateway 把 X-Trace-Id 写入 MDC;
    ② SW agent 支持通过请求头关联(sw8 propagation), 确认 gateway 版本后
    将 X-Trace-Id 作为 sw8 上游头传入; ③ 或接受两套体系(日志→SW 按
    时间+服务名+接口人工关联)。详见 QUESTIONS-FOR-REVIEW.md。
