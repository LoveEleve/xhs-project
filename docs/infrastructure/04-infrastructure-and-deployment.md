# my-xhs 基础设施与部署

> 全部基础设施 Docker 一键拉起，不依赖任何云厂商服务

---

## 1. 基础设施清单

| 组件 | 版本 | 用途 | 部署方式 |
|------|------|------|----------|
| MySQL | 8.0 | 业务数据存储 | 1主1从(Docker) |
| Redis | 7.x | 缓存+锁+限流+计数 | Sentinel模式: 1主2从3哨兵(Docker) |
| Elasticsearch | 8.x | 搜索引擎 | 单节点(Docker) |
| RocketMQ | 5.x | 消息队列 | 1主(NameServer+Broker)(Docker) |
| Nacos | 3.x | 注册+配置中心 | 单节点(Docker) |
| Sentinel | 1.8.x | 限流熔断Dashboard | 单节点(Docker) |
| SkyWalking | 9.x | 链路追踪 | OAP+UI(Docker) |
| Prometheus | 2.x | 指标采集 | 单节点(Docker) |
| Grafana | 10.x | 可视化看板 | 单节点(Docker) |
| XXL-Job | 3.0 | 分布式调度 | Admin+Executor(Docker) |
| Canal | 1.1.x | binlog监听→MQ | 单节点(Docker) |

---

## 2. Docker Compose 配置

### 2.1 基础设施编排

```yaml
# deploy/docker-compose/infrastructure.yml
# 一键启动全部基础设施

version: '3.8'

services:
  # ==================== MySQL 主从 ====================
  mysql-master:
    image: mysql:8.0
    container_name: mysql-master
    environment:
      MYSQL_ROOT_PASSWORD: root123
      MYSQL_DATABASE: my_xhs
    ports:
      - "3306:3306"
    volumes:
      - mysql-master-data:/var/lib/mysql
      - ./mysql/master/my.cnf:/etc/mysql/my.cnf
      - ./mysql/init:/docker-entrypoint-initdb.d
    command: --server-id=1 --log-bin=mysql-bin --binlog-format=ROW
    networks:
      - my-xhs-net

  mysql-slave:
    image: mysql:8.0
    container_name: mysql-slave
    environment:
      MYSQL_ROOT_PASSWORD: root123
    ports:
      - "3307:3306"
    volumes:
      - mysql-slave-data:/var/lib/mysql
      - ./mysql/slave/my.cnf:/etc/mysql/my.cnf
    command: --server-id=2 --relay-log=relay-bin --read-only=1
    depends_on:
      - mysql-master
    networks:
      - my-xhs-net

  # ==================== Redis Sentinel ====================
  redis-master:
    image: redis:7-alpine
    container_name: redis-master
    command: redis-server --requirepass redis123 --appendonly yes
    ports:
      - "6379:6379"
    volumes:
      - redis-master-data:/data
    networks:
      - my-xhs-net

  redis-slave-1:
    image: redis:7-alpine
    container_name: redis-slave-1
    command: redis-server --requirepass redis123 --replicaof redis-master 6379 --masterauth redis123
    ports:
      - "6380:6379"
    depends_on:
      - redis-master
    networks:
      - my-xhs-net

  redis-slave-2:
    image: redis:7-alpine
    container_name: redis-slave-2
    command: redis-server --requirepass redis123 --replicaof redis-master 6379 --masterauth redis123
    ports:
      - "6381:6379"
    depends_on:
      - redis-master
    networks:
      - my-xhs-net

  redis-sentinel-1:
    image: redis:7-alpine
    container_name: redis-sentinel-1
    command: redis-sentinel /etc/redis/sentinel.conf
    ports:
      - "26379:26379"
    volumes:
      - ./redis/sentinel/sentinel-1.conf:/etc/redis/sentinel.conf
    depends_on:
      - redis-master
    networks:
      - my-xhs-net

  redis-sentinel-2:
    image: redis:7-alpine
    container_name: redis-sentinel-2
    command: redis-sentinel /etc/redis/sentinel.conf
    ports:
      - "26380:26379"
    volumes:
      - ./redis/sentinel/sentinel-2.conf:/etc/redis/sentinel.conf
    depends_on:
      - redis-master
    networks:
      - my-xhs-net

  redis-sentinel-3:
    image: redis:7-alpine
    container_name: redis-sentinel-3
    command: redis-sentinel /etc/redis/sentinel.conf
    ports:
      - "26381:26379"
    volumes:
      - ./redis/sentinel/sentinel-3.conf:/etc/redis/sentinel.conf
    depends_on:
      - redis-master
    networks:
      - my-xhs-net

  # ==================== Elasticsearch ====================
  elasticsearch:
    image: elasticsearch:8.12.0
    container_name: elasticsearch
    environment:
      - discovery.type=single-node
      - xpack.security.enabled=false
      - "ES_JAVA_OPTS=-Xms512m -Xmx512m"
    ports:
      - "9200:9200"
      - "9300:9300"
    volumes:
      - es-data:/usr/share/elasticsearch/data
    networks:
      - my-xhs-net

  # ==================== RocketMQ ====================
  rocketmq-namesrv:
    image: apache/rocketmq:5.1.4
    container_name: rocketmq-namesrv
    command: sh mqnamesrv
    ports:
      - "9876:9876"
    networks:
      - my-xhs-net

  rocketmq-broker:
    image: apache/rocketmq:5.1.4
    container_name: rocketmq-broker
    command: sh mqbroker -n rocketmq-namesrv:9876 -c /home/rocketmq/broker.conf
    ports:
      - "10911:10911"
      - "10909:10909"
    volumes:
      - ./rocketmq/broker.conf:/home/rocketmq/broker.conf
      - rocketmq-broker-data:/home/rocketmq/store
    depends_on:
      - rocketmq-namesrv
    networks:
      - my-xhs-net

  # ==================== Nacos ====================
  nacos:
    image: nacos/nacos-server:v3.0.0
    container_name: nacos
    environment:
      - MODE=standalone
      - SPRING_DATASOURCE_PLATFORM=mysql
      - MYSQL_SERVICE_HOST=mysql-master
      - MYSQL_SERVICE_PORT=3306
      - MYSQL_SERVICE_DB_NAME=nacos
      - MYSQL_SERVICE_USER=root
      - MYSQL_SERVICE_PASSWORD=root123
    ports:
      - "8848:8848"
    depends_on:
      - mysql-master
    networks:
      - my-xhs-net

  # ==================== Sentinel Dashboard ====================
  sentinel-dashboard:
    image: bladex/sentinel-dashboard:1.8.7
    container_name: sentinel-dashboard
    ports:
      - "8080:8080"
    networks:
      - my-xhs-net

  # ==================== Canal ====================
  canal:
    image: canal/canal-server:v1.1.7
    container_name: canal
    environment:
      - canal.instance.master.address=mysql-master:3306
      - canal.instance.dbUsername=root
      - canal.instance.dbPassword=root123
      - canal.instance.filter.regex=my_xhs\\..*
      - canal.mq.servers=rocketmq-namesrv:9876
      - canal.mq.topic=canal-topic
    ports:
      - "11111:11111"
    depends_on:
      - mysql-master
      - rocketmq-namesrv
    networks:
      - my-xhs-net

  # ==================== XXL-Job ====================
  xxl-job-admin:
    image: xuxueli/xxl-job-admin:3.0.0
    container_name: xxl-job-admin
    environment:
      - PARAMS=--spring.datasource.url=jdbc:mysql://mysql-master:3306/xxl_job?useUnicode=true&characterEncoding=UTF-8&autoReconnect=true&serverTimezone=Asia/Shanghai --spring.datasource.username=root --spring.datasource.password=root123
    ports:
      - "9090:8080"
    depends_on:
      - mysql-master
    networks:
      - my-xhs-net

  # ==================== SkyWalking ====================
  skywalking-oap:
    image: apache/skywalking-oap-server:9.7.0
    container_name: skywalking-oap
    environment:
      - SW_STORAGE=elasticsearch
      - SW_STORAGE_ES_CLUSTER_NODES=elasticsearch:9200
    ports:
      - "11800:11800"
      - "12800:12800"
    depends_on:
      - elasticsearch
    networks:
      - my-xhs-net

  skywalking-ui:
    image: apache/skywalking-ui:9.7.0
    container_name: skywalking-ui
    environment:
      - SW_OAP_ADDRESS=http://skywalking-oap:12800
    ports:
      - "8888:8080"
    depends_on:
      - skywalking-oap
    networks:
      - my-xhs-net

  # ==================== Prometheus ====================
  prometheus:
    image: prom/prometheus:v2.48.1
    container_name: prometheus
    ports:
      - "9091:9090"
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml
      - ./prometheus/alert_rules:/etc/prometheus/alert_rules
    networks:
      - my-xhs-net

  # ==================== Grafana ====================
  grafana:
    image: grafana/grafana:10.2.3
    container_name: grafana
    ports:
      - "3000:3000"
    volumes:
      - grafana-data:/var/lib/grafana
      - ./grafana/dashboards:/etc/grafana/provisioning/dashboards
      - ./grafana/datasources:/etc/grafana/provisioning/datasources
    depends_on:
      - prometheus
    networks:
      - my-xhs-net

volumes:
  mysql-master-data:
  mysql-slave-data:
  redis-master-data:
  es-data:
  rocketmq-broker-data:
  grafana-data:

networks:
  my-xhs-net:
    driver: bridge
```

---

## 3. 各服务 Dockerfile 模板

```dockerfile
# deploy/dockerfile/Dockerfile.template
# 每个服务基于此模板

FROM openjdk:17-jdk-slim
LABEL maintainer=my-xhs

# SkyWalking Agent
ADD skywalking-agent.jar /skywalking/agent/skywalking-agent.jar

WORKDIR /app

# 先复制jar
COPY target/*.jar app.jar

# 暴露端口(每个服务不同)
EXPOSE 8080

# JVM参数
ENV JAVA_OPTS="-Xms256m -Xmx512m"

# SkyWalking参数
ENV SW_AGENT_NAME="my-xhs-service"
ENV SW_AGENT_COLLECTOR_BACKEND_SERVICES="skywalking-oap:11800"

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -javaagent:/skywalking/agent/skywalking-agent.jar -jar app.jar"]
```

---

## 4. K8s / Rancher 部署

### 4.1 服务部署YAML模板

```yaml
# deploy/k8s/service-deployment.yaml.template
apiVersion: apps/v1
kind: Deployment
metadata:
  name: my-xhs-{service}
  labels:
    app: my-xhs-{service}
    version: v1
spec:
  replicas: 2
  selector:
    matchLabels:
      app: my-xhs-{service}
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
  template:
    metadata:
      labels:
        app: my-xhs-{service}
        version: v1
      annotations:
        # Prometheus指标采集
        prometheus.io/scrape: "true"
        prometheus.io/port: "8080"
        prometheus.io/path: "/actuator/prometheus"
    spec:
      terminationGracePeriodSeconds: 45
      containers:
        - name: my-xhs-{service}
          image: my-xhs-cloud/{service}:latest
          ports:
            - containerPort: 8080
          env:
            - name: NACOS_ADDR
              value: "nacos:8848"
            - name: SPRING_PROFILES_ACTIVE
              value: "prod"
          resources:
            requests:
              memory: "512Mi"
              cpu: "250m"
            limits:
              memory: "1Gi"
              cpu: "500m"
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 60
            periodSeconds: 10
          lifecycle:
            preStop:
              exec:
                command: ["sh", "-c", "curl -X POST http://localhost:8080/actuator/nacos-deregister; sleep 15"]
---
apiVersion: v1
kind: Service
metadata:
  name: my-xhs-{service}
spec:
  selector:
    app: my-xhs-{service}
  ports:
    - port: 8080
      targetPort: 8080
  type: ClusterIP
```

### 4.2 灰度发布策略

```yaml
# deploy/k8s/canary-deployment.yaml
# 灰度发布：v2版本接收5%流量

apiVersion: apps/v1
kind: Deployment
metadata:
  name: my-xhs-order-v2
  labels:
    app: my-xhs-order
    version: v2   # 新版本标签
spec:
  replicas: 1     # 少量实例
  selector:
    matchLabels:
      app: my-xhs-order
      version: v2
  template:
    metadata:
      labels:
        app: my-xhs-order
        version: v2
      annotations:
        # Nacos元数据标记版本
        nacos-metadata-version: "v2"
    spec:
      containers:
        - name: my-xhs-order
          image: my-xhs-cloud/order:v2
          # ...同上

---
# Gateway灰度路由规则(Nacos配置)
# 只给Header中包含 x-canary: true 的请求路由到v2
spring:
  cloud:
    gateway:
      routes:
        - id: order-canary
          uri: lb://my-xhs-order
          predicates:
            - Path=/order-server/**
            - Header=x-canary, true
          filters:
            - StripPrefix=1
          metadata:
            nacos-metadata-version: v2
```

---

## 5. Jenkins 流水线

```groovy
// deploy/jenkins/Jenkinsfile
pipeline {
    agent any
    
    environment {
        DOCKER_REGISTRY = 'registry.cn-hangzhou.aliyuncs.com'
        IMAGE_PREFIX = 'my-xhs-cloud'
        VERSION = "${env.BUILD_NUMBER}"
    }
    
    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }
        
        stage('Build') {
            steps {
                sh 'mvn clean package -DskipTests'
            }
        }
        
        stage('Test') {
            steps {
                sh 'mvn test'
            }
        }
        
        stage('Build Images') {
            steps {
                script {
                    def services = ['gateway','user','note','social','product','cart','order','inventory','coupon','search','notification','counter']
                    services.each { svc ->
                        sh "docker build -t ${DOCKER_REGISTRY}/${IMAGE_PREFIX}/${svc}:${VERSION} -f my-xhs-${svc}/Dockerfile my-xhs-${svc}/"
                    }
                }
            }
        }
        
        stage('Push Images') {
            steps {
                script {
                    sh "docker login ${DOCKER_REGISTRY}"
                    def services = ['gateway','user','note','social','product','cart','order','inventory','coupon','search','notification','counter']
                    services.each { svc ->
                        sh "docker push ${DOCKER_REGISTRY}/${IMAGE_PREFIX}/${svc}:${VERSION}"
                    }
                }
            }
        }
        
        stage('Deploy') {
            steps {
                sh "kubectl set image deployment/my-xhs-order my-xhs-order=${DOCKER_REGISTRY}/${IMAGE_PREFIX}/order:${VERSION}"
                sh "kubectl rollout status deployment/my-xhs-order"
            }
        }
    }
    
    post {
        failure {
            echo 'Build failed!'
            // 钉钉/企微通知
        }
        success {
            echo 'Build succeeded!'
        }
    }
}
```

---

## 6. 可观测性

> 📖 **知识来源**：《高可用架构第1卷》第5章 — 监控体系
> - 核心观点："可观测性三支柱——Logs(日志)+Metrics(指标)+Traces(链路)，缺一不可"
> - 监控分层："基础设施(CPU/内存/磁盘) → 中间件(Redis/MySQL/MQ) → 应用(QPS/RT/错误率) → 业务(订单量/转化率)"
> - 告警原则："告警必须有actionable(可操作)，否则就是噪音。错误率>1%告警，RT>500ms告警"

### 6.1 日志方案

```xml
<!-- logback-spring.xml 统一配置 -->
<!-- 输出JSON格式 + TraceId字段 -->

<configuration>
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <includeMdcKeyName>traceId</includeMdcKeyName>
            <includeMdcKeyName>userId</includeMdcKeyName>
        </encoder>
    </appender>
</configuration>
```

### 6.2 Prometheus指标

| 指标 | 类型 | 说明 |
|------|------|------|
| `my_xhs_order_created_total` | Counter | 订单创建总数 |
| `my_xhs_coupon_claim_total` | Counter | 领券总数 |
| `my_xhs_inventory_deduct_duration` | Histogram | 库存扣减耗时 |
| `my_xhs_search_query_duration` | Histogram | 搜索查询耗时 |
| `my_xhs_mq_consume_failed_total` | Counter | MQ消费失败数 |
| `my_xhs_cache_hit_rate` | Gauge | 缓存命中率 |

### 6.3 Grafana仪表盘

```json
// deploy/grafana/dashboards/my-xhs-overview.json
// 包含以下面板：
// 1. 服务健康状态(绿/红)
// 2. QPS实时曲线
// 3. 响应时间P50/P95/P99
// 4. 错误率趋势
// 5. JVM内存/GC
// 6. 业务指标(下单量/领券量/搜索量)
```

### 6.4 告警规则

```yaml
# deploy/prometheus/alert_rules/my-xhs-rules.yml
groups:
  - name: my-xhs-alerts
    rules:
      - alert: HighErrorRate
        expr: rate(http_server_requests_seconds_count{status=~"5.."}[5m]) / rate(http_server_requests_seconds_count[5m]) > 0.01
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "服务 {{ $labels.application }} 错误率超过1%"
          
      - alert: HighResponseTime
        expr: histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m])) > 3
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "服务 {{ $labels.application }} P95响应时间超过3秒"
          
      - alert: MQConsumerFailed
        expr: increase(my_xhs_mq_consume_failed_total[10m]) > 10
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "MQ消费失败10分钟内超过10次"
```

---

## 7. 性能测试

### 7.1 JMeter 测试计划

| 测试场景 | 线程数 | 持续时间 | 目标QPS | 关注指标 |
|----------|--------|----------|---------|----------|
| 商品详情 | 500 | 5min | 5000 | RT P99 < 100ms |
| 下单 | 200 | 5min | 1000 | 成功率 > 99.9% |
| 秒杀领券 | 1000 | 1min | 10000 | 超卖=0 |
| 搜索 | 200 | 5min | 2000 | RT P99 < 200ms |

### 7.2 GoReplay 流量录制回放

```bash
# 录制线上流量
sudo gor --input-raw :8080 --output-file /tmp/gor/request-%Y%m%d.log

# 回放到测试环境
sudo gor --input-file /tmp/gor/request-*.log --output-http http://test-env:8080

# 流量放大(2倍)
sudo gor --input-file /tmp/gor/request-*.log --output-http http://test-env:8080 --output-http-speed 2

# 生产考量：
# 用GoReplay录制线上真实流量回放到测试环境，比JMeter构造请求更真实。
# 2倍速回放模拟流量突增，验证系统容量上限。
```

---

## 8. XXL-Job 定时任务

### 8.1 任务清单

| 任务 | Cron表达式 | 说明 | 选型理由 |
|------|-----------|------|----------|
| 超时关单扫描 | `0 */1 * * * ?` | 每分钟扫描超时未支付订单 | 延时消息兜底，MQ可能丢消息 |
| 优惠券过期处理 | `0 */5 * * * ?` | 每5分钟扫描过期券 | 延时消息兜底 |
| 库存对账 | `0 0 * * * ?` | 每小时Redis↔DB对账 | Buffer-Trigger可能丢数据 |
| 计数对账 | `0 0 2 * * ?` | 每天凌晨2点全量对账 | 最终一致性保证 |
| 热搜词清理 | `0 0 3 * * ?` | 每天凌晨3点清理过期词 | 热搜有时效性 |
| ES全量重建 | `0 0 4 ? * SUN` | 每周日凌晨4点全量重建 | 增量可能丢，全量保证一致 |
| Redis大Key扫描 | `0 0 1 * * ?` | 每天凌晨1点扫描大Key | 大Key影响Redis性能 |
| 缓存过期Key统计 | `0 */10 * * * ?` | 每10分钟统计即将过期的Key | 预警缓存雪崩 |

### 8.2 XXL-Job vs Spring @Scheduled

| 维度 | @Scheduled | XXL-Job |
|------|-----------|---------|
| 多实例执行 | 每个实例都执行(重复) | 只选1个实例执行 |
| 失败重试 | 无 | 自动重试(可配置次数) |
| 执行日志 | 需自己记录 | 自动记录 |
| 动态调整 | 需重启 | Dashboard实时改 |
| 分片执行 | 不支持 | 支持分片并行 |
| 告警 | 无 | 失败邮件/企微通知 |

**生产考量**：@Scheduled 在每个实例都执行，10个实例扫10遍；XXL-Job只选一个实例执行，失败自动重试，执行日志可查。分片任务如100万人发券，按用户ID分100片并行推。

---

## 9. Redis 治理

### 9.1 大Key监控与治理

| 大Key类型 | 判断标准 | 治理方案 | my-xhs落地 |
|-----------|----------|----------|-------------|
| String | >10KB | 压缩/拆分 | ✅ 笔记内容不存Redis |
| Hash | >5000字段 | 拆分为多个小Hash | ✅ 购物车按skuId拆分 |
| List | >5000元素 | 分批获取/拆分 | ✅ 关注列表分页 |
| Set | >5000元素 | 拆分为多个Set | ✅ 粉丝列表分桶 |
| Zset | >5000元素 | 拆分为多个Zset | ✅ 热搜榜按时间窗口拆分 |

### 9.2 缓存雪崩探测

```
1. XXL-Job每10分钟扫描即将过期(TTL<5分钟)的Key数量
2. 如果某1分钟内过期Key数量 > 阈值(如1000) → 告警
3. 自动触发：随机分散这些Key的TTL(±30%)

生产考量：
用XXL-Job每10分钟扫描即将过期的Key，如果某1分钟内大量Key同时过期，
自动给这些Key加随机TTL偏移，分散过期时间。
```

### 9.3 内存不足监控

```
1. Prometheus Redis Exporter采集 used_memory / maxmemory
2. 内存使用率 > 80% → 告警
3. 内存使用率 > 90% → 自动触发：淘汰策略从volatile-lru切到allkeys-lru
4. 内存使用率 > 95% → 紧急告警 + 拒绝写入(只读)

生产考量：
Redis内存不足时做了三级预警：80%告警、90%调整淘汰策略、
95%切只读保护。同时Sentinel降级让查询走DB。
```

---

## 10. 混沌工程（ChaosBlade）

> 📖 **知识来源**：《持续演进的Cloud Native》第7章 — 混沌工程实践
> - 核心观点："架构×研发流程×团队文化=Cloud Native，混沌工程是验证这三者是否真的能扛故障的关键手段"
> - 核心原则："在测试环境主动注入故障，验证系统的容错能力。不能只在文档里写'降级走DB'，要真的断开Redis验证"
> - 演练流程："制定计划→注入故障→观察监控→记录结果→修复→重新验证→输出报告"
> - my-xhs对照：ChaosBlade 7个演练场景（Redis不可用/MySQL宕机/熔断验证/CPU飙高/MQ不可用/Pod被杀）

### 10.1 为什么需要混沌工程

| 问题 | 说明 |
|------|------|
| 故障预案都是理论 | 文档里写了"Redis不可用时降级走DB"，但没有验证过 |
| 上线前必须验证 | 生产环境一定会出故障，不提前演练就是在用户身上演练 |
| 发现隐藏问题 | 熔断配置不对、超时时间太短、降级逻辑有bug——只有真正注入故障才能发现 |

### 10.2 ChaosBlade 简介

ChaosBlade 是阿里巴巴开源的混沌工程实验工具（CNCF Sandbox项目），支持丰富的故障注入场景。

### 10.3 my-xhs 混沌演练计划

| 演练场景 | ChaosBlade命令 | 验证目标 | 预期结果 |
|----------|---------------|----------|----------|
| Redis不可用 | `blade create network drop --port 6379` | 缓存降级走DB+限流 | 服务不挂，RT升高但可接受 |
| MySQL主库宕机 | `blade create process kill --process mysqld` | 主从切换 | 30秒内切换完成，写入恢复 |
| 库存服务网络隔离 | `blade create network drop --remote-port 9009` | Sentinel熔断降级 | 订单服务熔断，返回降级提示 |
| 下单接口延迟 | `blade create network delay --time 3000 --interface com...OrderService` | Feign超时+重试 | 3秒超时触发降级，不无限等待 |
| MQ Broker不可用 | `blade create process kill --process java --cmd-keyword broker` | 本地消息表补偿 | 消息暂存本地表，MQ恢复后补发 |
| CPU飙高 | `blade create cpu fullload` | 限流是否生效 | Sentinel限流，非核心接口被限 |
| Pod被杀 | `blade create process kill --process java --cmd-keyword my-xhs` | K8s自动重启+优雅停机 | 新Pod启动，请求不丢失 |

### 10.4 演练流程

```
1. 制定演练计划（哪些场景、预期结果、回滚方案）
2. 在测试环境执行ChaosBlade注入
3. 观察监控面板（Grafana+SkyWalking）
4. 记录实际结果 vs 预期结果
5. 不符合预期的 → 修复代码/配置 → 重新演练
6. 恢复故障（blade destroy/blade revoke）
7. 输出演练报告
```

> **注意**：混沌演练在测试环境执行，不在生产环境直接演练。生产环境可先在低流量时段小范围验证。

---

## 11. 安全合规

### 11.1 接口安全

| 安全项 | 实现方案 | 生产场景 |
|--------|----------|----------|
| 请求签名 | HMAC-SHA256(timestamp+nonce+body+secretKey) | 防篡改+防重放，Gateway GlobalFilter校验 |
| 认证鉴权 | JWT双Token(Access+Refresh) | 未登录请求拦截在Gateway，不打到业务服务 |
| 接口限频 | @RateLimit + Sentinel | 防暴力破解、防恶意刷接口 |
| CORS | Gateway CorsFilter | 前端跨域，限制允许的域名 |

### 11.2 数据安全

| 安全项 | 实现方案 | 生产场景 |
|--------|----------|----------|
| 密码存储 | BCrypt慢哈希 | 防彩虹表破解，即使DB泄露也无法还原明文 |
| 敏感信息脱敏 | 返回DTO中手机号/邮箱打码 | 前端展示脱敏，防信息泄露，合规要求 |
| SQL注入防护 | MyBatis-Plus参数化查询 + 代码Review | 所有SQL必须参数化，禁止拼接用户输入 |
| XSS防护 | 前端输入转义 + 后端过滤HTML标签 | 笔记内容/评论中不能注入脚本 |
| CSRF防护 | JWT Token + SameSite Cookie | 防跨站请求伪造 |
| 文件上传安全 | 文件类型白名单 + 大小限制(5MB) | 防上传恶意脚本/超大文件 |

### 11.3 管理后台安全

| 安全项 | 实现方案 | 生产场景 |
|--------|----------|----------|
| 网络隔离 | Admin服务只在内网暴露 | 外网无法直接访问管理后台 |
| RBAC权限 | 角色→菜单权限映射 | 不同角色看到不同功能，防止越权 |
| 操作审计 | 所有管理操作记录审计日志 | 操作可追溯，出问题可定位到人 |
| 二次确认 | 删除/下架等敏感操作需二次确认 | 防误操作 |

### 11.4 合规要求

| 合规项 | 说明 | my-xhs落地 |
|--------|------|-------------|
| 内容审核 | UGC内容必须审核后才能发布 | 笔记状态机(草稿→待审核→已发布) |
| 敏感词过滤 | 10万词库DFA过滤 | 评论/笔记内容发布前过滤 |
| 隐私保护 | 用户数据最小化收集、脱敏展示 | 手机号/邮箱脱敏，地址打码 |
| 数据留存 | 按法规要求留存日志和订单 | 订单3年、日志90天 |

---

## 12. 数据备份与恢复

### 12.1 备份策略

| 数据类型 | 备份方式 | 备份频率 | 保留周期 |
|----------|----------|----------|----------|
| MySQL | 全量备份(mysqldump) + binlog增量 | 全量每天凌晨 + binlog实时 | 全量7天，binlog30天 |
| Redis | RDB快照 + AOF | RDB每6小时 + AOF每秒 | RDB 7天 |
| ES | Snapshot | 每天凌晨 | 7天 |
| 代码/配置 | Git | 每次提交 | 永久 |

### 12.2 恢复方案

| 故障场景 | 恢复方案 | RTO | RPO |
|----------|----------|-----|-----|
| MySQL误删数据 | 从从库恢复 + binlog回放 | < 30分钟 | < 1分钟 |
| MySQL主库宕机 | ShardingSphere主从切换 | < 30秒 | 0(同步复制) |
| Redis数据丢失 | 从MySQL全量重建 | < 5分钟 | < 1分钟(取决于最后对账时间) |
| ES索引损坏 | XXL-Job全量重建 | < 30分钟 | 0(从MySQL源数据重建) |
| 整机故障 | K8s重新调度 + PVC数据卷 | < 5分钟 | 取决于最后一次备份 |

### 12.3 备份自动化

```bash
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

---

## 13. 全链路压测基线

> 📖 **知识来源**：《性能之道：分布式系统全栈性能优化》第3章 + 《大型网站性能优化实战》第2章
> - 《性能之道》四维模型："点(单接口优化)→线(调用链路优化)→面(单服务全面优化)→体(全系统整体优化)"
> - 《大型网站性能优化实战》公式："单线程QPS = 1000ms/RT；最佳线程数 = (RT/CPU时间)×CPU核数"
> - 压测原则："必须有性能基线，否则退化了都不知道。每次发布前对比基线"
> - my-xhs对照：性能基线(QPS/RT/成功率) + 容量水位(安全/告警/危险) + 压测报告模板

> 生产级系统上线前必须有性能基线，不是"能跑就行"。

### 13.1 性能基线

| 场景 | 目标QPS | RT P99 | 成功率 | 可接受RT上限 |
|------|---------|--------|--------|-------------|
| 商品详情 | 5000 | < 100ms | 99.99% | 500ms |
| 首页Feed | 3000 | < 200ms | 99.99% | 1s |
| 搜索 | 2000 | < 200ms | 99.99% | 500ms |
| 下单 | 1000 | < 500ms | 99.9% | 1s |
| 秒杀领券 | 10000 | < 1s | 99.9% | 3s |
| 点赞 | 5000 | < 50ms | 99.99% | 200ms |

### 13.2 容量水位

| 资源 | 安全水位 | 告警水位 | 危险水位 |
|------|----------|----------|----------|
| CPU | < 50% | > 70% | > 85% |
| 内存 | < 70% | > 80% | > 90% |
| MySQL连接数 | < 50% max | > 70% | > 85% |
| Redis内存 | < 70% maxmemory | > 80% | > 90% |
| MQ消费堆积 | < 1000 | > 5000 | > 10000 |

### 13.3 压测报告模板

```
1. 测试环境配置（实例数/规格/中间件版本）
2. 测试场景与参数
3. 结果数据（QPS/RT/成功率/错误率）
4. 资源水位（CPU/内存/网络/磁盘）
5. 瓶颈分析（哪个服务/哪个接口/哪个资源）
6. 优化建议
7. 与基线对比（是否退化）
```

---

## 14. 日志体系

> 日志是服务治理的基础。没有统一的日志规范和集中检索，生产环境排查问题效率极低。

### 14.1 日志规范

| 规范项 | 要求 | 说明 |
|--------|------|------|
| 日志格式 | JSON结构化 | 便于ELK/Loki解析，不用写复杂的Grok正则 |
| 日志级别 | ERROR/WARN/INFO/DEBUG | 生产环境默认INFO，需要排查时动态调为DEBUG |
| TraceId | 每条日志必须包含 | 关联SkyWalking链路，一个请求的所有日志能串起来 |
| 业务字段 | userId/orderId等关键ID | 便于按业务维度检索 |
| 敏感信息 | 脱敏处理 | 手机号/密码/Token不能明文打印 |

### 14.2 日志格式定义

```json
{
  "timestamp": "2025-05-09T18:00:00.123+08:00",
  "level": "INFO",
  "traceId": "abc123def456",
  "spanId": "789xyz",
  "service": "my-xhs-order",
  "instance": "order-pod-abc123",
  "thread": "http-nio-9011-exec-1",
  "logger": "com.myxhs.order.service.OrderService",
  "message": "下单成功",
  "userId": 10001,
  "orderId": "202505091800001234",
  "rt": 156,
  "extra": {}
}
```

### 14.3 Logback配置

```xml
<!-- my-xhs-common/src/main/resources/logback-spring.xml -->
<configuration>
    <!-- 控制台输出(开发环境) -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"service":"${SERVICE_NAME:-unknown}"}</customFields>
        </encoder>
    </appender>
    
    <!-- 文件输出(生产环境) -->
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>/var/log/my-xhs/${SERVICE_NAME}.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>/var/log/my-xhs/${SERVICE_NAME}.%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>7</maxHistory>
        </rollingPolicy>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"service":"${SERVICE_NAME:-unknown}"}</customFields>
        </encoder>
    </appender>
    
    <!-- SkyWalking TraceId注入 -->
    <appender name="SKYWALKING" class="org.apache.skywalking.apm.toolkit.log.logback.v1.x.TraceIdPatternLogbackLayout"/>
    
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
    </root>
</configuration>
```

### 14.4 日志采集架构

```
                                    ┌─────────────┐
┌──────────┐    ┌──────────┐       │   Grafana   │
│ Pod日志  │───→│ Promtail │──────→│    Loki     │
└──────────┘    └──────────┘       └─────────────┘
     │                                    │
     │         ┌──────────────────────────┘
     │         │
     ↓         ↓
┌──────────────────────────────────────────────┐
│  查询: {service="my-xhs-order"} |= "下单失败" │
│  查询: {traceId="abc123def456"}              │
└──────────────────────────────────────────────┘
```

| 组件 | 作用 | 部署方式 |
|------|------|----------|
| Promtail | 日志采集Agent | DaemonSet(每个Node一个) |
| Loki | 日志存储+索引 | StatefulSet |
| Grafana | 日志查询UI | 与指标监控共用 |

### 14.5 日志告警规则

```yaml
# loki-alert-rules.yml
groups:
  - name: my-xhs-log-alerts
    rules:
      # 错误日志突增
      - alert: HighErrorRate
        expr: |
          sum(rate({service=~"my-xhs-.*"} |= "ERROR" [5m])) by (service) > 10
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "服务 {{ $labels.service }} 错误日志突增"
          
      # 关键业务异常
      - alert: OrderCreateFailed
        expr: |
          count_over_time({service="my-xhs-order"} |= "下单失败" [5m]) > 5
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "下单失败数量异常"
          
      # OOM检测
      - alert: OutOfMemory
        expr: |
          count_over_time({service=~"my-xhs-.*"} |= "OutOfMemoryError" [5m]) > 0
        for: 0m
        labels:
          severity: critical
        annotations:
          summary: "服务 {{ $labels.service }} 发生OOM"
```

### 14.6 日志与链路追踪关联

```java
// 在Gateway GlobalFilter中注入TraceId到日志MDC
@Component
public class TraceIdFilter implements GlobalFilter, Ordered {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = Optional.ofNullable(TraceContext.traceId())
                .orElse(UUID.randomUUID().toString().replace("-", ""));
        
        // 注入MDC供Logback使用
        MDC.put("traceId", traceId);
        
        // 传递给下游服务
        ServerHttpRequest request = exchange.getRequest().mutate()
                .header("X-Trace-Id", traceId)
                .build();
        
        return chain.filter(exchange.mutate().request(request).build())
                .doFinally(signalType -> MDC.clear());
    }
}
```

**效果**：在Grafana中点击SkyWalking链路的某个Span → 自动跳转到Loki查询该TraceId的所有日志。

---

## 15. 配置中心规范

### 15.1 Nacos配置分组

```
nacos-config/
├── my-xhs-common.yml          # 公共配置(所有服务共享)
├── my-xhs-user-dev.yml        # 用户服务开发环境
├── my-xhs-user-test.yml       # 用户服务测试环境
├── my-xhs-user-prod.yml       # 用户服务生产环境
├── my-xhs-order-dev.yml
├── ...
└── sentinel-rules/            # Sentinel规则(单独目录)
    ├── flow-rules.json
    └── degrade-rules.json
```

| 配置项 | Group | 说明 |
|--------|-------|------|
| 公共配置 | `COMMON_GROUP` | 数据库连接池、Redis配置、MQ配置 |
| 服务配置 | `SERVICE_GROUP` | 各服务私有配置 |
| 限流规则 | `SENTINEL_GROUP` | Sentinel流控规则，独立管理 |

### 15.2 多环境隔离

```yaml
# bootstrap.yml
spring:
  application:
    name: my-xhs-order
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}
  cloud:
    nacos:
      config:
        server-addr: ${NACOS_SERVER:localhost:8848}
        namespace: ${NACOS_NAMESPACE:dev}  # dev/test/pre/prod
        group: SERVICE_GROUP
        shared-configs:
          - data-id: my-xhs-common.yml
            group: COMMON_GROUP
            refresh: true
```

| 环境 | Namespace | 用途 |
|------|-----------|------|
| dev | `dev` | 本地开发 |
| test | `test` | 测试环境 |
| pre | `pre` | 预发环境(与生产配置相同，流量隔离) |
| prod | `prod` | 生产环境 |

### 15.3 配置热更新

```java
@RefreshScope
@Configuration
public class DynamicConfig {
    
    @Value("${order.timeout:30}")
    private Integer orderTimeout;  // 支持Nacos热更新
    
    @Value("${feature.newCheckout:false}")
    private Boolean newCheckoutEnabled;  // 功能开关
}
```

---

## 16. 服务治理规范

### 16.1 健康检查

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true  # K8s探针支持
  health:
    livenessState:
      enabled: true
    readinessState:
      enabled: true
```

| 探针 | 端点 | K8s用途 |
|------|------|---------|
| Liveness | `/actuator/health/liveness` | Pod存活检测，失败则重启 |
| Readiness | `/actuator/health/readiness` | 流量就绪检测，失败则摘流量 |

### 16.2 优雅上下线

```java
// 优雅停机配置
@Configuration
public class GracefulShutdownConfig {
    
    @Bean
    public GracefulShutdown gracefulShutdown() {
        return new GracefulShutdown();
    }
    
    // 收到SIGTERM后：
    // 1. 先从Nacos注销(30秒内下游感知)
    // 2. 等待进行中的请求处理完(最多30秒)
    // 3. 关闭连接池
    // 4. 进程退出
}
```

```yaml
# K8s Deployment配置
spec:
  template:
    spec:
      terminationGracePeriodSeconds: 60  # 给60秒优雅停机
      containers:
        - lifecycle:
            preStop:
              exec:
                command: ["sh", "-c", "sleep 10"]  # 等待流量切走
```

### 16.3 异常处理规范

```java
// 统一异常处理
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    @ExceptionHandler(BizException.class)
    public R<Void> handleBizException(BizException e) {
        log.warn("业务异常: code={}, msg={}", e.getCode(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }
    
    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e) {
        log.error("系统异常: ", e);  // 打印完整堆栈
        // 返回给前端不暴露详情
        return R.fail(SysErrorCode.SYSTEM_ERROR);
    }
}
```

| 异常类型 | 日志级别 | 返回给前端 | 告警 |
|----------|----------|-----------|------|
| BizException(业务异常) | WARN | 业务错误码+提示 | 不告警 |
| Exception(系统异常) | ERROR | 通用系统错误 | 告警 |

---

## 17. 测试策略

### 17.1 测试金字塔

```
                    ┌─────────┐
                    │  E2E    │  ← 端到端测试(少量关键链路)
                   ╱│  Test   │╲
                  ╱ └─────────┘ ╲
                 ╱               ╲
                ╱  ┌───────────┐  ╲
               ╱   │Integration│   ╲  ← 集成测试(服务间调用)
              ╱    │   Test    │    ╲
             ╱     └───────────┘     ╲
            ╱                         ╲
           ╱    ┌─────────────────┐    ╲
          ╱     │   Unit Test     │     ╲  ← 单元测试(最多)
         ╱      └─────────────────┘      ╲
        ╱─────────────────────────────────╲
```

### 17.2 测试覆盖要求

| 测试类型 | 覆盖目标 | 工具 | 运行时机 |
|----------|----------|------|----------|
| 单元测试 | 核心业务逻辑 ≥ 80% | JUnit5 + Mockito | 每次提交 |
| 集成测试 | 数据库/Redis/MQ操作 | Testcontainers | 每次PR |
| 契约测试 | 服务间接口兼容性 | Spring Cloud Contract | 每次发布 |
| E2E测试 | 核心业务链路 | Playwright/Selenium | 每次发布 |

### 17.3 Testcontainers示例

```java
@SpringBootTest
@Testcontainers
class OrderServiceIntegrationTest {
    
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("my_xhs_order");
    
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7")
            .withExposedPorts(6379);
    
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.redis.host", redis::getHost);
        registry.add("spring.redis.port", () -> redis.getMappedPort(6379));
    }
    
    @Test
    void createOrder_shouldSuccess() {
        // 真实的MySQL和Redis容器
        // 测试完整的下单流程
    }
}
```
