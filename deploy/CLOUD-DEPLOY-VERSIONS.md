# 云主机部署版本与配置说明

> 2026-08-24 | 目标：微服务（VM 直跑）+ 中间件（同机 Docker，host 网络）单机部署
> 本文记录已核实的版本清单、Canal 自定义镜像问题与替代方案、IP 替换要求。

## 一、微服务侧版本（Maven，pom.xml 根 BOM）

| 组件 | 版本 |
|---|---|
| Java | 17 |
| Spring Boot | 3.2.5 |
| Spring Cloud | 2023.0.3 |
| Spring Cloud Alibaba | 2023.0.1.2 |
| MyBatis Plus | 3.5.7 |
| MySQL Connector | 8.0.33 |
| HikariCP | 5.1.0 |
| Redisson | 3.27.0 |
| RocketMQ Client | 5.1.4（由 rocketmq-spring-boot-starter 2.3.0 管理） |
| RocketMQ Spring Boot | 2.3.0 |
| Elasticsearch Client | 8.12.2 |
| Jackson | 2.16.1 |
| Lombok | 1.18.30 |
| Hutool | 5.8.25 |
| Fastjson | 2.0.43 |
| Guava | 33.0.0-jre |
| Commons Lang3 | 3.14.0 |
| Commons Codec | 1.16.0 |
| MapStruct | 1.5.5.Final |
| Sentinel | 1.8.8 |
| Nacos Client | 2.3.0 |
| SkyWalking Agent | 9.6.0（skywalking-agent.jar 已实测） |
| SkyWalking APM Toolkit | 9.6.0 |
| ShardingSphere | 5.5.1 |
| CosId | 2.6.8 |
| XXL-Job Core | 2.4.2 |
| Testcontainers | 1.19.8 |
| JMH | 1.37 |
| Kaptcha | 1.1.0 |
| Aliyun OSS | 3.17.4 |
| JWT (jjwt) | 0.12.3 |
| Feign HC5 | 13.1 |
| Logstash Logback Encoder | 7.4 |
| LangChain4j | 1.0.0 |
| Temporal SDK | 1.24.1 |

## 二、中间件版本（deploy/docker/my-xhs-deploy-zip/docker-compose.yml）

| 服务 | 镜像 | 版本 |
|---|---|---|
| MySQL 主/从 | `mysql:8.0` | 8.0 |
| Redis 主/从/Sentinel | `redis:7-alpine` | 7.x |
| RocketMQ NameServer/Broker | `apache/rocketmq:5.1.4` | 5.1.4 |
| RocketMQ Dashboard | `apacherocketmq/rocketmq-dashboard` | **latest（不可复现，建议固定）** |
| Elasticsearch（业务） | `elasticsearch:8.19.19` | 8.19.19 |
| Elasticsearch（SW 存储） | `elasticsearch:8.12.2` | 8.12.2 |
| canal | 原 `my-xhs-canal-server:v1.1.7-squashed` | **建议改用官方 `canal/canal-server:v1.1.7`** |
| Nacos | `nacos/nacos-server:v2.3.2` | 2.3.2 |
| Sentinel Dashboard | `bladex/sentinel-dashboard:1.8.8` | 1.8.8 |
| XXL-Job Admin | `xuxueli/xxl-job-admin:2.4.2` | 2.4.2 |
| SkyWalking OAP | `apache/skywalking-oap-server:9.7.0` | 9.7.0 |
| SkyWalking UI | `apache/skywalking-ui:9.7.0` | 9.7.0 |
| VictoriaMetrics | `victoriametrics/victoria-metrics:v1.93.12` | 1.93.12 |
| Prometheus | `prom/prometheus:v2.48.1` | 2.48.1 |
| Alertmanager | `prom/alertmanager:v0.27.0` | 0.27.0 |
| Grafana | `grafana/grafana:10.2.3` | 10.2.3 |
| Logstash | `docker.elastic.co/logstash/logstash:8.19.19` | 8.19.19 |
| Kibana | `docker.elastic.co/kibana/kibana:8.19.19` | 8.19.19 |
| Node Exporter | `prom/node-exporter:v1.7.0` | 1.7.0 |
| Redis Exporter | `oliver006/redis_exporter:v1.58.0-alpine` | 1.58.0 |
| Elasticsearch Exporter | `prometheuscommunity/elasticsearch-exporter:v1.7.0` | 1.7.0 |
| MySQL Exporter ×2 | `prom/mysqld-exporter:v0.15.1` | 0.15.1 |

> 资源合计：compose 27 服务，memory limit 约 22.8 GiB、reservation 约 10.2 GiB；32 核以下建议先精简。

## 三、Canal 自定义镜像问题的解决

背景：`my-xhs-canal-server:v1.1.7-squashed` 为原中间件机自建的本地镜像，Docker Hub 无此镜像。原中间件机（21.130.247.89）已销毁，无法再 `docker save/load`。

结论：**该自定义镜像没有必要**。官方镜像 `canal/canal-server:v1.1.7` 自带 JDK 与启动脚本，直接替换即可，无需导出/load，也无需挂载 `/opt/kona-jdk8`。

Compose canal 段建议改为：

```yaml
canal:
    image: canal/canal-server:v1.1.7
    container_name: my-xhs-canal
    network_mode: host
    environment:
      TZ: Asia/Shanghai
      CANAL_HOME: /home/admin/canal-server
    volumes:
      - ./config/canal/conf/canal.properties:/home/admin/canal-server/conf/canal.properties
      - ./config/canal/conf/note_instance/instance.properties:/home/admin/canal-server/conf/note_instance/instance.properties
      - ./config/canal/conf/product_instance/instance.properties:/home/admin/canal-server/conf/product_instance/instance.properties
      - ./config/canal/conf/inventory_instance/instance.properties:/home/admin/canal-server/conf/inventory_instance/instance.properties
      - canal-data:/home/admin/canal-server/logs
    healthcheck:
      test: ["CMD-SHELL", "pgrep -f CanalLauncher || exit 1"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 60s
    depends_on:
      mysql:
        condition: service_healthy
      rocketmq-broker:
        condition: service_started
    restart: always
```

> 启动后如日志异常，再按实际报错微调 entrypoint/command（官方镜像默认 entrypoint 与本配置可兼容）。

## 四、单机（同 VM）部署关键点

1. **地址原则**：Docker 中间件内部用 `127.0.0.1`（host 网络）；VM 中运行的微服务通过 VM 私网 IP 连中间件，不使用 EIP。
2. **必须用「VM 私网 IP」而非 127.0.0.1 的地方**：
   - `broker.conf`: `brokerIP1`、`namesrvAddr`
   - `sentinel.conf`: `sentinel announce-ip`、`sentinel monitor`
   - `docker-compose.yml`: `NACOS_SERVER_IP`
   - `start-all.sh`: `-Dskywalking.collector.backend_service=${HOST_IP}:11800`
   - `config/nacos/my-xhs-redis.yaml`: `spring.data.redis.host`
   - `prometheus.yml`: `${HOST_IP}` / `${MICROSERVICE_IP}` 采集目标
3. **统一替换**：`setup-ip.sh <VM私网IP> [VM私网IP]` 处理部署包内占位符；但微服务源码中仍硬编码大量 `21.130.247.89`（application.yml / application-datasource.properties / logback-spring.xml 的 15044 / 各启动脚本），需额外替换或改为环境变量。
4. **RocketMQ 监控**：5.1.4 无内置 Prometheus exporter，使用 textfile 方案（`config/deploy-cloud/rocketmq-metrics.sh` + node-exporter + cron）。
5. **导入 Nacos 配置**：从零部署后需导入 `config/nacos/` 下 3 个配置；导入前将 `my-xhs-redis.yaml` 的 host 改为新私网 IP。
6. **前置资源**：`/opt/kona-jdk17`、`/opt/kona-jdk8` 需真实存在；ES 首次启动需外网下载 IK。

## 五、已知版本不匹配项

- SkyWalking Agent 9.6.0 vs OAP 9.7.0：小版本差，能跑但后续建议对齐。
- 业务 ES 8.19.19 vs SW ES 8.12.2：两个独立实例，可共存。
- rocketmq-dashboard:latest：不可复现，建议固定版本。