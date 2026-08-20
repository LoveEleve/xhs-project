# 各服务"真实生效配置"来源验证报告

核心结论: 07-deploy-src/config/(= /data/workspace/my-xhs-deploy-zip/config)中
【挂载型】服务的配置 100% 就是容器进程实际读取的文件(已验证 inode 一致、diff 无漂移);
【非挂载型】服务的真实配置在 命令行参数/环境变量/数据库/运行期API 中, 与 config 目录无关。

## 每服务真实配置来源(依据: 进程 entrypoint/cmd + 挂载 + 运行期验证)

| 服务 | 配置来源 | 实际配置文件/参数位置 | 运行期验证证据 |
|---|---|---|---|
| prometheus | 挂载文件 | /etc/prometheus/prometheus.yml ← config/prometheus/prometheus.yml(inode 相同) | /api/v1/status/config 解析结果与文件完全一致 |
| grafana | 挂载目录+env | provisioning/ ← config/grafana/provisioning/(inode 相同); GF_* env | /api/datasources 显示 Prometheus(与 provisioning 一致), 4 个看板 |
| canal | 挂载文件 | canal.properties + 3个instance.properties ← config/canal/conf/(inode 相同) | java -Dcanal.conf=.../conf/canal.properties; 日志确认 3 instance 运行 |
| redis-sentinel | 挂载文件 | /etc/redis/sentinel.conf ← config/redis/sentinel.conf(inode 相同) | SENTINEL masters/slaves 输出与文件一致 |
| rocketmq-broker | 挂载文件 | broker.conf ← config/rocketmq/broker.conf(inode 相同) | mqbroker -c 指向该路径; brokerStatus 生效 |
| mysql / mysql-slave | 命令行参数 | entrypoint args(--port/--innodb-buffer-pool-size/--log-bin/--gtid-mode等) | SHOW VARIABLES 与参数一致; /etc/my.cnf 为镜像默认未改动 |
| redis / redis-slave | 命令行参数 | redis-server --requirepass --appendonly --maxmemory --save ... | CONFIG GET * 与参数一致 |
| elasticsearch / es-skywalking | 环境变量+启动参数 | ES_JAVA_OPTS/http.port/xpack.security 等 env; config/elasticsearch.yml 为镜像默认 | /_nodes/settings: port 19200/19201, cluster name, xpack 生效 |
| kibana | 环境变量 | SERVER_PORT=15601, ELASTICSEARCH_HOSTS/USERNAME/PASSWORD env | /api/status(当前 unavailable: ES security_exception) |
| logstash | 命令行内联 | logstash -e 'input{...} output{...}' (无配置文件) | /_node/stats pipeline 与 -e 内容一致 |
| filebeat | 命令行参数 | filebeat -E output.logstash.hosts=[127.0.0.1:15045] -E filebeat.inputs=[.../logs/*.json...] | registry 已跟踪 /logs/*.json(见 config-files/registry-log.json) |
| nacos | 环境变量 | NACOS_SERVER_IP/MYSQL_SERVICE_* env; conf/application.properties 为镜像默认 | java --spring.config.additional-location=file:/home/nacos/conf/; 配置存 MySQL nacos_config |
| skywalking-oap | 环境变量生成模板 | /skywalking/config/application.yml 为 ${SW_*} 模板, 实际值来自 env(SW_STORAGE=elasticsearch, SW_STORAGE_ES_CLUSTER_NODES=127.0.0.1:19201, SW_ES_USER/PASSWORD) | 索引 sw_* 写入 19201; 全链路 31 节点有数据 |
| skywalking-ui | 环境变量生成模板 | webapp/application.yml 为模板, SW_OAP_ADDRESS/SERVER_PORT 来自 env | UI 200 |
| xxl-job-admin | env PARAMS → DB | --spring.datasource.url=...xxl_job, --xxl.job.accessToken 等 PARAMS env | 任务/执行器来自 xxl_job DB(15+任务) |
| sentinel-dashboard | 命令行参数 | -Dserver.port=8858 -Dcsp.sentinel.api.port=8719 | 登录页 200; 规则数据在本地 derby/内存 |
| rocketmq-namesrv | env JAVA_OPT_EXT | java 参数 | clusterList 正常 |
| rocketmq-dashboard | env | SERVER_PORT=18081, rocketmq.config.namesrvAddr=127.0.0.1:9876 | 首页 200 |
| victoria-metrics | 命令行参数 | -storageDataPath -retentionPeriod=30d -httpListenAddr=:8428 | /flags 一致; 0 序列 |

## 验证方法说明

- inode 一致: bind mount 宿主文件与容器内为同一文件(stat -c %i)
- diff 无漂移: 07-deploy-src/config 与挂载源目录 diff -rq 完全一致
- 运行期API: /api/v1/status/config(Prometheus resolved)、/_nodes/settings(ES)、
  /_node/stats(Logstash)、/api/datasources(Grafana)、SENTINEL/CONFIG GET(Redis)、
  SHOW VARIABLES(MySQL) 等均已存于 08-runtime-queries/ 与 11-business/

## 结论

1. 参考 config 目录里被挂载的文件 = 真实在用的配置(已实证);
2. 未挂载的组件(MySQL/Redis/ES/Kibana/Logstash/Filebeat/Nacos/OAP/UI/XXL/Sentinel/VM/RMQ-Dashboard)
   真实配置在"命令行参数+环境变量+数据库", 包内 00-docker-info/all-container-envs.txt、
   各容器 inspect-full.json(含 Cmd/Entrypoint)、08-runtime-queries/ 已全部捕获;
3. 业务微服务配置在远端 21.214.97.212 + Nacos(配置已从 nacos_config 库导出)。
