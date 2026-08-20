# 真实生效配置索引:每个服务真实配置在包里的哪个文件

## A. 挂载文件型(容器进程直接读宿主文件, 已 inode 实证 = 07-deploy-src/config 里的同一份)

| 服务 | 真实配置文件 | 包内位置 |
|---|---|---|
| prometheus | /etc/prometheus/prometheus.yml + alert_rules/*.yml | 07-deploy-src/config/prometheus/prometheus.yml, alert_rules/ |
| grafana | provisioning/datasources + provisioning/dashboards/*.json | 07-deploy-src/config/grafana/provisioning/ |
| canal | canal.properties + note/product/inventory_instance/instance.properties | 07-deploy-src/config/canal/conf/ |
| redis-sentinel | /etc/redis/sentinel.conf | 07-deploy-src/config/redis/sentinel.conf |
| rocketmq-broker | broker.conf | 07-deploy-src/config/rocketmq/broker.conf |
| skywalking-oap | /skywalking/config/application.yml(模板, 值来自 SW_* env) | 04-containers/my-xhs-skywalking-oap/config-files/ + env 见 real-configs |
| skywalking-ui | webapp/application.yml(模板, 值来自 SW_* env) | 04-containers/my-xhs-skywalking-ui/config-files/ |

## B. 命令行参数型(真实配置 = Cmd 参数, 无配置文件)

| 服务 | 关键真实配置 | 包内位置 |
|---|---|---|
| mysql | --port=3306 --innodb-buffer-pool-size=1024M --log-bin --binlog-format=ROW --gtid-mode=ON ... | real-configs/my-xhs-mysql.txt + inspect-full.json |
| mysql-slave | --port=3307 --server-id ... --read-only 等 | real-configs/my-xhs-mysql-slave.txt |
| redis | --requirepass --appendonly --maxmemory 256mb --save ... | real-configs/my-xhs-redis.txt |
| redis-slave | --port=6380 --replicaof --masterauth ... | real-configs/my-xhs-redis-slave.txt |
| redis-sentinel | redis-sentinel /etc/redis/sentinel.conf(A 类) | real-configs/my-xhs-redis-sentinel.txt |
| logstash | -e 'input{tcp:15044,beats:15045} output{elasticsearch:19200, index=myxhs-logs-*}'(内联, 无文件) | real-configs/my-xhs-logstash.txt |
| filebeat | -E output.logstash.hosts=[127.0.0.1:15045] -E filebeat.inputs=[/logs/*.json] | real-configs/my-xhs-filebeat.txt |
| victoria-metrics | -retentionPeriod=30d -httpListenAddr=:8428 -storageDataPath | real-configs/my-xhs-victoria-metrics.txt |
| sentinel-dashboard | -Dserver.port=8858 -Dcsp.sentinel.api.port=8719 | real-configs/my-xhs-sentinel-dashboard.txt |

## C. 环境变量型(真实配置 = Env)

| 服务 | 关键真实配置 | 包内位置 |
|---|---|---|
| elasticsearch | http.port=19200 xpack.security.enabled=true ELASTIC_PASSWORD ES_JAVA_OPTS=-Xms256m -Xmx512m | real-configs/my-xhs-elasticsearch.txt |
| es-skywalking | http.port=19201 cluster.name=my-xhs-sw-es | real-configs/my-xhs-es-skywalking.txt |
| kibana | SERVER_PORT=15601 ELASTICSEARCH_HOSTS/USERNAME/PASSWORD | real-configs/my-xhs-kibana.txt |
| nacos | NACOS_SERVER_IP=21.130.247.89 MYSQL_SERVICE_* (配置本体存 MySQL nacos_config) | real-configs/my-xhs-nacos.txt + 08-runtime-queries/07-nacos-configs-from-db.txt |
| skywalking-oap | SW_STORAGE=elasticsearch SW_STORAGE_ES_CLUSTER_NODES=127.0.0.1:19201 SW_ES_USER/PASSWORD | real-configs/my-xhs-skywalking-oap.txt |
| skywalking-ui | SW_OAP_ADDRESS=http://127.0.0.1:12800 SERVER_PORT=8080 | real-configs/my-xhs-skywalking-ui.txt |
| xxl-job-admin | PARAMS: --spring.datasource.url=...xxl_job --xxl.job.accessToken=... (任务在 DB) | real-configs/my-xhs-xxl-job-admin.txt + 11-business/02-xxl-job-tasks.txt |
| rocketmq-namesrv | JAVA_OPT_EXT=-Xms128m -Xmx256m | real-configs/my-xhs-mq-namesrv.txt |
| rocketmq-dashboard | SERVER_PORT=18081 rocketmq.config.namesrvAddr=127.0.0.1:9876 | real-configs/my-xhs-mq-dashboard.txt |
| grafana | GF_SECURITY_ADMIN_PASSWORD GF_AUTH_ANONYMOUS_ENABLED(A类补充) | real-configs/my-xhs-grafana.txt |

## 汇总入口(全部服务一份文件)

- 12-effective-config/real-configs/       22 个文件, 每个 = entrypoint+cmd+env+资源限制+挂载, 即该服务真实配置全集
- 00-docker-info/all-container-envs.txt  全部容器 env 汇总
- 04-containers/<name>/inspect-full.json 原始 docker inspect(最权威底稿)
- 04-containers/<name>/config-files/     docker cp 的容器内实际文件(filebeat registry 等)
- 08-runtime-queries/                    运行期生效值实证(API/SHOW/CONFIG)
- 11-business/                           业务侧(XXL任务/Nacos配置/链路/遥测)
