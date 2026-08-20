# MyXHS Docker 环境详细审查包

导出时间: 2026-08-11
环境: 单机 Docker 部署, 22 个服务容器, 全部 host 网络模式
生成方式: docker inspect / docker exec / docker cp / 官方 docker-compose.yml 全量提取

## 目录结构

- 00-docker-info/    docker info / version / system df / stats(资源占用快照)
- 01-networks/       网络列表 + 全量 inspect
- 02-volumes/        卷列表 + 全量 inspect
- 03-images/         镜像列表 + 每镜像 inspect(含 Entrypoint/Cmd/Env/Healthcheck)
- 04-containers/     每容器独立目录:
    - inspect-full.json        完整容器配置(Env/挂载/资源限制/健康检查/重启策略/标签)
    - top.txt                  进程快照
    - ports.txt                端口映射
    - diff-vs-image.txt        相对镜像的变更(自定义文件/新增日志目录)
    - config-files/            docker cp 提取的容器内配置文件
    - etc-hosts / resolv.conf / hostname
- 05-logs/           每容器最近 1000 行日志(带时间戳)
- 06-daemon/         daemon.json + compose 文件位置
- 07-deploy-src/     官方部署源码目录(权威配置源头):
    - docker-compose.yml      全部 22 服务完整定义
    - config/canal|prometheus|redis|rocketmq|sentinel|skywalking|grafana  各组件的原生配置文件
    - sql/init-all.sql + init-replication.sql(MySQL 初始化/主从复制)
    - start-all.sh 部署脚本
- service-map.txt    服务/镜像/容器名/环境变量/资源限制对照表

## 监控 / 日志 / 全链路重点配置速览

- 指标: Prometheus(v2.48.1) + VictoriaMetrics(v1.93.12) 双存储; Grafana 10.2.3
  - prometheus.yml + alert_rules/myxhs_rules.yml 见 04-containers/my-xhs-prometheus/
  - Grafana provisioning(数据源+4个看板: api/jvm/biz/tomcat) 见 04-containers/my-xhs-grafana/config-files/provisioning/
- 日志链路: Filebeat(/logs/*.json 微服务 logback JSON) -> Logstash(tcp:15044 + beats:15045) -> ES(8.19.19 :19200) -> Kibana(:15601)
  - Filebeat 采集配置为命令行参数注入, 见 docker-compose.yml filebeat 段
  - Logstash 管道为 -e 内联, 输出 index: myxhs-logs-%{+YYYY.MM.dd}, 见 docker-compose.yml logstash 段
- 全链路追踪: SkyWalking OAP 9.7.0(存储为独立 ES 8.12.2 my-xhs-es-skywalking) + UI
  - OAP application.yml 见 04-containers/my-xhs-skywalking-oap/config-files/
- 注册/配置中心: Nacos v2.3.2(数据库用 MySQL, 见 nacos 段环境变量)
- 消息队列: RocketMQ 5.1.4(单 namesrv + master broker :11911, broker 配置见 config/rocketmq/)
- 限流: Sentinel Dashboard 1.8.8(:8858)
- 调度: XXL-Job Admin 2.4.2(:18080, 参数见 PARAMS 环境变量)
- 数据: MySQL 8.0 主从(GTID, :3306/:3307, 复制脚本见 sql/init-replication.sql); Redis 7 主从+Sentinel(:26379)
- 变更同步: Canal 1.1.7(binlog -> RocketMQ, 实例配置见 config/canal/conf/)

## 注意

- 配置中含明文密码/密钥(ES、Kibana、Grafana、MySQL、Nacos 等), 仅供内部 review, 注意保管
- 所有容器均为 host 网络模式, 无网络隔离; 资源限制通过 deploy.limits 软限制
- 所有容器 restart: unless-stopped

============================================================
第二轮深度采集(2026-08-11 补采) 新增内容
============================================================

## 新增目录

- 08-runtime-queries/     各组件运行时真实状态(非配置,是"实际在跑的东西"):
    - 01-mysql-master.txt    变量(binlog=ROW/GTID/expire 30天)、库表清单、MASTER STATUS、binlog 列表
- 12-effective-config/     每服务"真实生效配置"来源验证报告(挂载文件 inode 实证 + 环境变量/命令行参数/DB 来源清单):
    - 02-mysql-slave.txt     SHOW SLAVE STATUS(IO/SQL 双 Yes, 延迟 0s)
    - 03~05-redis*.txt       INFO all + CONFIG GET *(主 6379/从 6380/Sentinel 26379 全量)
    - 06-rocketmq.txt        clusterList/topicList/consumerGroupList/brokerStatus/store 大小
    - 07-nacos*.txt          命名空间(public/my-xhs 3配置/sca-lab-dev 7配置) + MySQL 库内全量配置明细(含 JWT/HMAC 密钥、库密)
    - 08-prometheus.txt      实际抓取目标(18个微服务全部 UP, 抓取间隔10s) + 全量告警规则状态 + 启动 flags
    - 09-grafana.txt         org/datasource/4个看板/用户(仅admin一人)/admin stats
    - 10~11-es*.txt          两个 ES 集群 health/indices: 日志索引 myxhs-logs-2026.08.08~11(约1.9GB, 单副本yellow属正常); SkyWalking sw_* 索引
    - 12-kibana.txt          /api/status(当前 unavailable, 待确认)
    - 13-logstash.txt        node info(9600端口, pipeline workers/batch 参数)
    - 14-skywalking.txt      OAP 端口探测 + UI 200
    - 15-victoriametrics.txt health OK, 但 0 序列(Prometheus 未 remote-write 到 VM)
    - 16-web-console.txt     各控制台 HTTP 状态
- 09-host-system/          宿主机视角: OS/内核/内存/磁盘/监听端口全表/iptables/系统服务/docker journal
- 10-container-runtime/    每容器运行时环境: date/ulimit/cgroup(cpu.max/memory.max)/os-release

## 新增分析报告

- 00-docker-info/drift-report.txt           compose 定义 vs 容器实际 漂移对比(22服务全部一致, 无漂移)
- 00-docker-info/container-config-summary.txt  每容器: 特权/只读/CapAdd/SecOpt/用户/重启策略/内存CPU限制/网络PID模式/日志驱动/健康状态
- 00-docker-info/all-container-envs.txt    每容器完整环境变量(含密码)
- 00-docker-info/docker-events-7d.txt      近7天容器事件(零重启零异常, 全部为采集探针产生的 exec)
- 02-volumes/volume-sizes.txt              在用卷实际占用空间(仅当前部署使用的 14 个卷)
- 09-host-system/port-container-map.txt    全部监听端口 -> 容器映射(host网络下 40+ 端口)

## 关键发现(供业务 review 重点看)

1. 监控链路实际运行: Prometheus 正采集 18 个微服务(/actuator/prometheus, 10s间隔)全部 UP,
   4 组告警规则(应用/业务/黄金指标/中间件, 见 08-prometheus.txt 全量规则+当前状态)
2. 日志链路: 微服务 logback JSON 写宿主 /logs -> Filebeat(逐文件配置经命令行注入)
   -> Logstash(tcp:15044 + beats:15045) -> ES myxhs-logs-*(已有4天数据约1.9GB) -> Kibana(15601)
3. 全链路: SkyWalking 9.7.0 OAP+UI, 数据存独立 ES(19201), sw_* 索引已在写
4. 中间件状态: MySQL 主从复制正常(延迟0); Redis 主从+Sentinel 正常(94连接/5.76MB/256MB上限);
   RocketMQ 业务 Topic 存在(NOTIFICATION_TOPIC/COUPON_CLAIM_TOPIC等); Nacos 3命名空间10条配置
5. 资源: 全部容器 host 网络 + journald 日志驱动 + unless-stopped; 无特权容器、无 cap-add
6. 隐患点:
   - VictoriaMetrics 0 序列, Prometheus 未 remote-write, VM 目前空转(除非另有 remote-write 配置)
    - Kibana /api/status unavailable, 需确认健康状态
    - Nacos/ES/Redis/MySQL 密码明文分散于 compose/配置/DB, 无密钥管理

============================================================
第三轮深度采集(2026-08-11) 新增内容
============================================================

## 新增

- 11-business/  业务侧深度数据:
    - 01-remote-microservices.txt  微服务实际运行在另一台机器 21.214.97.212(网关19000),
      Nacos 注册 15 个业务服务(gateway/user/content/product/order/payment/cart/im/analytics/
      counter/coupon/inventory/notification/home/search)
    - 02-xxl-job-tasks.txt          全部调度任务: 执行器地址(21.214.97.212:9990~9999)、
      cron、handler、启停状态、最近/下次触发时间(支付超时/退款/对账/计数器修复/死信扫描等 15+ 任务)
    - 03-mysql-deep.txt             用户清单(root/canal)、连接数、活跃会话、业务表行数与容量
    - 04-redis-deep.txt             db0 345 keys(126 带过期)、key类型分布、SLOWLOG、客户端详情
    - 05-rocketmq-deep.txt          consumerProgress(消费积压为 0)、topicStatus、statsAll
    - 06-nacos-security.txt         用户/角色/权限 + 配置发布历史(his_config_info)
    - 07-canal-status.txt           canal.properties、3 个 instance(note/product/inventory)
    - 08-container-log-errors.txt   全容器日志错误/告警计数 + 每容器错误样例
    - 09-logstash-pipeline.txt      pipeline 事件计数/队列详情
    - 10-elasticsearch-deep.txt     index templates、节点 JVM/磁盘、IK 分词插件确认
    - 11-skywalking-deep.txt        OAP 自监控指标(1234端口) + GraphQL: 链路中 31 个节点
      (15个业务服务 + MySQL/Redis/MQ 依赖), 全链路追踪真实有数据
    - 12-prometheus-deep.txt        TSDB(5803 序列) + up 计数
    - 13-victoriametrics-deep.txt   VM flags(retention 30d, 无 remoteWrite 接收)
    - 14-canal-image-history.txt    自定义 canal 镜像构建历史
    - 15-kibana-debug.txt           Kibana 日志尾部(找 unavailable 根因)
    - 16-host-misc.txt              宿主机 crontab + 部署包清单

## 第三轮关键发现

1. 业务微服务不在本机: 运行于 21.214.97.212(网关19000, XXL-Job执行器 9990~9999,
   Sentinel 8721等); 本机 /logs 为空 -> Filebeat 当前实际无输入文件, 日志管道空转
   (采集目标在远端, 但 Filebeat 挂载的是本机 /logs)
2. Kibana unavailable 根因: [ERROR][elasticsearch-service] Unable to retrieve version
   information from Elasticsearch nodes. security_exception (Kibana->ES 认证/连接问题),
   另有 encryptedSavedObjects.encryptionKey 未配置告警
3. 历史事故记录:
   - 2026-08-09 02:49~03:00 MySQL 主库 binlog 被清理, 从库报错误 1236 复制中断
     (purged required binary logs), 现主从已恢复(IO/SQL Yes, 延迟0)
   - 2026-08-08 XXL-Job 报 xxl_job_registry 表不存在(后已建表, 现 registry 10行正常)
   - 2026-08-08 Nacos 启动时 No DataSource set(dumpservice 构建失败, 后正常)
   - 2026-08-08 Prometheus 启动配置告警(global scrape timeout > interval, 已修正)
   - 2026-08-10 Sentinel Dashboard 796 条 MetricFetcher 连接失败(21.214.97.212:8721/8725
     Connection refused, 现端口已通, 属当时远端实例未就绪)
4. XXL-Job 定时任务体系: 执行器在远端 21.214.97.212, 任务含支付超时检查(30s)、
   支付/退款通知补偿(2~3分钟)、对账/修复类(每5分钟~每小时)等 15+ 条
5. SkyWalking 链路已覆盖全部 15 个业务服务 + 中间件(MySQL 3306/3307、Redis 6379/6380、
   MQ 9876), 全链路真实有数据
6. VictoriaMetrics 仍为空(0 序列, 无 remote-write 流入), 需确认设计意图
