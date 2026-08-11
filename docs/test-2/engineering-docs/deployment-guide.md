# my-xhs 部署指南

> 22 容器 + 15 微服务 | network_mode: host | 单机部署

---

## 一、硬件需求

| 资源 | 最低 | 推荐 |
|------|:--:|:--:|
| CPU | 4 核 | 8 核 |
| 内存 | 16 GB | 24 GB |
| 磁盘 | 50 GB (Docker + 数据) | 100 GB |
| OS | Linux x86_64 | CentOS 7+ / Ubuntu 20.04+ |

**内存分配**:
- 中间件 (22 容器): ~19GB (峰值)
- 微服务 (15 个): ~8.75GB
- 实际运行: ~14-16GB (内存复用后)

**端口检查**: 需保证中间件+微服务全部端口未被占用 (见 middleware-topology.md 端口表)

---

## 二、部署流程

### Step 1: 环境准备

```bash
# 1.1 安装 Docker + Docker Compose
docker --version    # >= 24.0
docker-compose --version  # >= 2.20

# 1.2 安装 Java 17 (编译微服务)
java -version       # OpenJDK 17

# 1.3 安装 Maven
mvn -version        # >= 3.8

# 1.4 检查端口
for p in 3306 3307 6379 6380 26379 9876 11911 18081 11111 18848 8858 18080 \
         19200 19201 19300 19301 11800 12800 8080 19090 8428 13000 15044 15045 15601 \
         8719 8721 8722 8723 8724 8725 8726 8727 8728 8729 8730 8731 8732; do
  ss -tlnp | grep ":$p " && echo "WARNING: 端口 $p 已被占用"
done

# 1.5 确保 docker-compose 中 NACOS_SERVER_IP 为宿主机实际 IP
grep NACOS_SERVER_IP /data/workspace/my-xhs/config/docker-compose.yml
# 如果不是当前机器 IP → 修改为实际 IP
```

### Step 2: 中间件启动

> ⚠️ **前置修复 (必须执行)**: docker-compose.yml 挂载 `./sql/init-all.sql` 但实际 sql 文件在 `/data/workspace/my-xhs/sql/`，直接 `docker-compose up -d` 会报 no such file 启动失败。需先建符号链接:

```bash
cd /data/workspace/my-xhs/config
ln -s ../sql sql       # 建立 sql 目录符号链接 → config/sql → ../sql
ls -la sql/init-all.sql  # 验证链接有效

# 启动全部中间件容器 (后台)
docker-compose up -d

# 2.2 等待存储层就绪
echo "等待 MySQL..."
until mysqladmin ping -h 127.0.0.1 -P 3306 -u root -p'Xhs@2026#MySQL' --silent; do sleep 2; done
echo "MySQL Master OK"

echo "等待 Redis..."
until redis-cli -h 127.0.0.1 -p 6379 -a 'Xhs@2026#Redis' ping | grep -q PONG; do sleep 2; done
echo "Redis OK"

# 2.3 等待消息/注册层就绪 (依赖存储层)
sleep 15
echo "等待 Nacos..."
until curl -s http://127.0.0.1:18848/nacos/v1/console/health | grep -q UP; do sleep 3; done
echo "Nacos OK"

echo "等待 RocketMQ..."
until curl -s http://127.0.0.1:18081 > /dev/null; do sleep 3; done
echo "RocketMQ Dashboard OK"

# 2.4 等待 ES + SkyWalking (启动慢)
echo "等待 ES(最长90s)..."
until curl -s -u elastic:Xhs@2026#Elastic http://127.0.0.1:19200/_cluster/health?local=true | grep -qE 'green|yellow'; do sleep 5; done
echo "ES OK"

# 2.5 验证全部中间件
docker ps --format "table {{.Names}}\t{{.Status}}" | grep my-xhs
# 预期: 22 行全部 healthy/running
```

### Step 3: MySQL 主从配置

```bash
# 3.1 等待从库启动
sleep 30

# 3.2 建立从库复制 (init-replication.sql 自动执行，但需验证)
mysql -h 127.0.0.1 -P 3307 -u root -p'Xhs@2026#MySQL' -e "
SHOW SLAVE STATUS\G" | grep -E "Running|Seconds_Behind|Error"

# 预期:
# Slave_IO_Running: Yes
# Slave_SQL_Running: Yes
# Seconds_Behind_Master: 0

# 3.3 如果未自动建立 → 手动执行
if ! mysql -h 127.0.0.1 -P 3307 -u root -p'Xhs@2026#MySQL' -e "SHOW SLAVE STATUS\G" | grep -q "Yes"; then
  mysql -h 127.0.0.1 -P 3307 -u root -p'Xhs@2026#MySQL' < /data/workspace/my-xhs/sql/init-replication.sql
fi
```

### Step 4: 数据初始化

```bash
# 4.1 初始化已完成 (docker-compose volume mount init-all.sql)
# 验证所有库已创建
mysql -h 127.0.0.1 -P 3306 -u root -p'Xhs@2026#MySQL' -e "
SELECT SCHEMA_NAME FROM information_schema.SCHEMATA
WHERE SCHEMA_NAME LIKE 'my_xhs_%' OR SCHEMA_NAME IN ('nacos_config','xxl_job');
"
# 预期: my_xhs_user, my_xhs_content, my_xhs_analytics, my_xhs_product,
#        my_xhs_cart, my_xhs_coupon, my_xhs_order, my_xhs_inventory,
#        my_xhs_payment, my_xhs_notification, my_xhs_im, my_xhs_counter,
#        nacos_config, xxl_job

# 4.2 初始化 Nacos 命名空间
curl -X POST "http://127.0.0.1:18848/nacos/v1/console/namespaces" \
  -d "customNamespaceId=my-xhs&namespaceName=my-xhs&namespaceDesc=my-xhs微服务"

# 4.3 导入 Sentinel 规则 (config/sentinel/*.json)
#   方式A (Nacos数据源): 服务通过 spring.cloud.sentinel.datasource 从 Nacos 拉取,
#     将 myxhs-gateway-flow-rules.json 等上传到 Nacos 对应 dataId (需先启动微服务再导入)
#   方式B (Dashboard手动): 浏览器访问 http://21.130.247.89:8858 → 流控规则 → 添加
#   验证: 各服务启动后 eager:true 自动上报规则到 Dashboard
for f in /data/workspace/my-xhs/config/sentinel/*.json; do
  echo "待导入: $f"
done
```

### Step 5: 微服务编译

```bash
cd /data/workspace/my-xhs

# 5.1 逐个编译 (建议顺序)
for svc in common user content analytics counter product cart \
            inventory coupon order payment notification im home search gateway; do
  echo "Building my-xhs-$svc..."
  mvn clean package -pl my-xhs-$svc -am -DskipTests -q && echo "OK" || echo "FAILED"
done

# 5.2 验证 JAR 是否存在
for svc in gateway user content analytics counter product cart \
            inventory coupon order payment notification im home search; do
  jar="my-xhs-$svc/target/my-xhs-$svc-1.0-SNAPSHOT.jar"
  [ -f "$jar" ] && echo "✅ $jar" || echo "❌ $jar MISSING"
done
```

### Step 6: 微服务启动

```bash
# 6.1 一键启动 (5批并行)
./start-all.sh

# 6.2 验证健康状态
sleep 30  # 等待全部就绪

for p in 19000 19001 19002 19003 19004 19006 19008 19009 19010 19011 19012 19013 19014 19015 19016; do
  STATUS=$(curl -sf "http://localhost:$p/actuator/health" 2>/dev/null | python3 -c "import json,sys;print(json.load(sys.stdin).get('status','DOWN'))" 2>/dev/null || echo "DOWN")
  echo ":$p → $STATUS"
done
```

---

## 三、启动顺序依赖

```
中间件启动顺序:
  MySQL (必须先启动)
    ├→ MySQL Slave (depends_on mysql)
    ├→ Nacos (存储 nacos_config)
    ├→ XXL-Job (存储 xxl_job)
    └→ Canal (depends_on mysql + RocketMQ)
  RocketMQ NameServer
    └→ Broker → Dashboard
  Redis → Slave → Sentinel
  ES ×2
    ├→ Logstash → Filebeat
    ├→ Kibana
    └→ SkyWalking OAP → UI
  Prometheus / VictoriaMetrics / Grafana (无依赖)

微服务启动批次:
  批次1: user, content, analytics, counter          (基础服务)
       ↓ wait + 2s
  批次2: product, cart, inventory, coupon            (业务服务)
       ↓ wait + 2s
  批次3: order, payment                              (交易链路, 依赖batch2)
       ↓ wait + 2s
  批次4: notification, im, home, search              (辅助服务)
       ↓ wait + 2s
  批次5: gateway                                     (最后, 依赖全部就绪)
```

**为什么 Gateway 最后启动**: Gateway 路由 `lb://my-xhs-xxx` 依赖 Nacos 服务发现。如果目标服务未注册 → 路由失败 → 503。Gateway 最后启动确保全部 14 个服务已在 Nacos 注册。

---

## 四、验证清单

### 4.1 中间件验证

```bash
# Nacos 服务注册
for s in gateway user content analytics counter product cart coupon inventory order payment notification im home search; do
  curl -s "http://21.130.247.89:18848/nacos/v1/ns/instance/list?serviceName=my-xhs-$s&namespaceId=my-xhs" | \
    python3 -c "import json,sys;d=json.load(sys.stdin);print(f'$s: {len(d[\"hosts\"])} instance(s)')" 2>/dev/null
done
# 预期: 每个服务 1 instance

# MySQL 主从
mysql -h 127.0.0.1 -P 3307 -u root -p'Xhs@2026#MySQL' -e "SHOW SLAVE STATUS\G" | grep -E "Running|Error"
# 预期: Slave_IO_Running: Yes / Slave_SQL_Running: Yes

# Redis Sentinel
redis-cli -h 127.0.0.1 -p 26379 -a 'Xhs@2026#Redis' sentinel masters
# 预期: name=mymaster, num-slaves=1

# ES 集群
curl -s -u elastic:Xhs@2026#Elastic http://127.0.0.1:19200/_cluster/health | python3 -m json.tool | grep status
# 预期: "status": "green" 或 "yellow"

# RocketMQ Broker
curl -s http://127.0.0.1:18081
# 预期: HTML 响应 (Dashboard)
```

### 4.2 微服务验证

```bash
# Gateway 健康 (包含 Redis 连接状态)
curl -s http://21.130.247.89:19000/actuator/health | python3 -c "
import json,sys
d=json.load(sys.stdin)
print(f'status: {d[\"status\"]}')
print(f'redis:  {d[\"components\"][\"redis\"][\"status\"]}')
"
# 预期: status: UP / redis: UP

# 端到端链路测试
# 1. 获取验证码
curl -s http://21.130.247.89:19000/api/user/auth/captcha?phone=13700001111 | python3 -c "import json,sys;d=json.load(sys.stdin);print(d.get('data',{}).get('captchaKey','FAIL'))"

# 2. 注册测试用户
# 完整测试流程见 FULL-CHAIN-RETEST-PLAN.md
```

### 4.3 监控管道验证

```bash
# SkyWalking UI
curl -s http://21.130.247.89:8080 | grep -o '<title>.*</title>'

# Prometheus
curl -s http://21.130.247.89:19090/-/healthy

# Grafana
curl -s http://21.130.247.89:13000/api/health | python3 -c "import json,sys;print(json.load(sys.stdin).get('database','DOWN'))"
# 预期: ok

# Kibana
curl -s http://21.130.247.89:15601/api/status | python3 -c "import json,sys;print(json.load(sys.stdin)['status']['overall']['level'])"
# 预期: available
```

---

## 五、常见问题

### Q1: MySQL 主库启动失败 — 端口 3306 冲突

```bash
# 检查
ss -tlnp | grep 3306
# 解决: 停止宿主机 MySQL
systemctl stop mysql  # 或
kill -9 <pid>
# 重新启动
docker restart my-xhs-mysql
```

### Q2: Canal 无法连接 MySQL

```bash
# 现象: Canal 容器重启循环, 日志显示 "Could not find first log file name in binary log index"
# 原因: MySQL binlog 文件名与 Canal meta.dat 不匹配

# 解决:
docker stop my-xhs-canal
rm -rf /var/lib/docker/volumes/*canal-data/_data/h2.mv.db
docker start my-xhs-canal
# Canal 将从当前 binlog 位置开始监听
```

### Q3: Redis Sentinel 无法选举

```bash
# 现象: sentinel masters 返回空
# 原因: Sentinel 配置中 master 地址不可达

# 检查:
redis-cli -h 127.0.0.1 -p 6379 -a 'Xhs@2026#Redis' ping  # master
redis-cli -h 127.0.0.1 -p 6380 -a 'Xhs@2026#Redis' ping  # slave
# 如果 master 不通 → 重启
docker restart my-xhs-redis my-xhs-redis-slave my-xhs-redis-sentinel
```

### Q4: Gateway 启动后一直 503

```bash
# 原因: 目标服务未在 Nacos 注册

# 检查 Nacos 服务列表:
curl -s "http://21.130.247.89:18848/nacos/v1/ns/service/list?pageNo=1&pageSize=20&namespaceId=my-xhs" | \
  python3 -c "import json,sys;print('\n'.join(json.load(sys.stdin)['doms']))"

# 如果目标服务缺失 → 重新启动该服务
# 如果全部缺失 → start-all.sh 重新执行
```

### Q5: ES 启动时 plugin 安装失败

```bash
# 现象: ES 容器启动后退出, 日志显示 IK plugin 下载失败
# 原因: 网络不可达 get.infini.cloud

# 解决: 手动安装 IK 并重启
docker exec -it my-xhs-elasticsearch bash
elasticsearch-plugin install https://get.infini.cloud/elasticsearch/analysis-ik/8.19.19
exit
docker restart my-xhs-elasticsearch
```

### Q6: 微服务 OOM / 启动慢

```bash
# 检查堆使用:
jstat -gcutil $(pgrep -f my-xhs-order) 1000
# 如果 FGC 频繁 → 增加 -Xmx

# 修改 start-all.sh:
JAVA_OPTS_HEAVY="... -Xms2048m -Xmx2048m ..."  # 从1024→2048
./start-all.sh
```

### Q7: Logstash 不采集日志

```bash
# 检查管道:
docker logs my-xhs-logstash --tail 20

# 检查 Filebeat:
docker logs my-xhs-filebeat --tail 20

# 检查日志文件是否存在:
ls -la /logs/*.json

# 手动注入测试日志:
echo '{"timestamp":"2026-08-10T00:00:00","level":"INFO","message":"test"}' > /logs/test.json
# 等待几秒 → Kibana 查询 myxhs-logs-*
```

### Q8: 服务启动时 Sentinel 端口冲突 (8719)

> ✅ 已修复 — order 显式配置 8733，payment 显式配置 8734 (避免与 gateway 的 8719 冲突)

```bash
# 现象: 多个服务启动失败, 日志显示 "Port already in use: 8719"
# 原因: gateway 显式配置 transport.port=8719, 但 order/payment 未配置 → 使用默认 8719
#       host 网络下 3 个服务争用同一端口
# 修复: my-xhs-order/src/main/resources/application.yml → port: 8733
#        my-xhs-payment/src/main/resources/application.yml → port: 8734
```

---

## 六、卸载/重置

```bash
# 完全卸载
cd /data/workspace/my-xhs/config
docker-compose down -v          # 停止并删除 volumes (数据丢失!)
rm -rf /data/workspace/my-xhs/pids/*
rm -rf /data/workspace/my-xhs/logs/*
rm -rf /logs/*

# 仅重启中间件 (保留数据)
docker-compose restart

# 仅重启微服务
./start-all.sh

# 重建特定服务 (数据丢失)
docker-compose down -v mysql    # 只删除 MySQL 数据
docker-compose up -d mysql      # 重建
```

---

## 七、部署环境说明

### 7.1 Redis Sentinel → Standalone 降级

当 Redis Sentinel 容器无法正确返回外部可达 IP（如 slave 被 Sentinel 报告为 `127.0.0.1:6380`）时，需将微服务降级为 standalone 直连模式：

```bash
# 所有微服务启动时需设置
export SPRING_DATA_REDIS_SENTINEL_ENABLED=false

# Gateway 额外需要（gateway yml 缺 host 配置）
export SPRING_DATA_REDIS_HOST=21.130.247.89
```

**根因**: RedissonConfig 默认 `sentinel.enabled=true`，Sentinel 模式下 slave 被报告为容器内部 IP(127.0.0.1)，外部微服务无法连接。Spring Data Redis 的 Lettuce 同样受影响。

**Sentinel 正常模式修复**（需要在远程 Docker 主机上执行）:
```bash
# redis-slave 容器需添加 announce-ip
--replica-announce-ip 21.130.247.89
# redis-master 容器需添加
--replica-announce-ip 21.130.247.89 --slave-announce-ip 21.130.247.89
```

### 7.2 Analytics JVM 参数

Analytics 服务需要额外的 JVM 系统属性：

```bash
-Dmanagement.admin-token=my-xhs-admin-token-2026
```

其他服务通过 Spring `${ADMIN_TOKEN:}` 读取环境变量即可。

### 7.3 推荐启动方式（noVerifySentinel + 完整 system properties）

所有服务统一使用以下 JVM 参数：

```bash
export ADMIN_TOKEN="my-xhs-admin-token-2026"
export INTERNAL_TOKEN="my-xhs-internal-token-2026"

AGENT="-javaagent:/data/workspace/my-xhs/skywalking-agent-9.6.0/skywalking-agent.jar"
AGENT="$AGENT -Dskywalking.collector.backend_service=21.130.247.89:11800"
GC="-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:MaxMetaspaceSize=256m"

# 所有服务通用
OPTS="$AGENT $GC -Dspring.data.redis.sentinel.enabled=false"

nohup java ${OPTS} -Xms512m -Xmx512m \
  -Dskywalking.agent.service_name=my-xhs-user \
  -jar /data/workspace/my-xhs/my-xhs-user/target/my-xhs-user-1.0-SNAPSHOT.jar \
  > /tmp/r_user.log 2>&1 &

# analytics 额外加:
OPTS_A="$OPTS -Dmanagement.admin-token=my-xhs-admin-token-2026"
# gateway 额外加:
OPTS_GW="$OPTS -Dspring.data.redis.host=21.130.247.89"
```
