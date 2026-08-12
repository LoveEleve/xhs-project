# 生产配置修复方案（FIX-PLAN-PRODUCTION-CONFIG）

> 2026-08-12 | 基于 review-production-config.md（P-D1~P-D12 发现），**按"影响运转 > 安全 hygiene"重排优先级**。
> 原则：先修零代码/低风险项（功能/数据/监控），安全项（鉴权/密码）留到统一窗口做（改动面大需全链路同步）。
> **本文件为方案，未实施。** 每项含：根因确认、具体改动、代码是否涉及、影响、风险、验证。

---

## 第一批（零代码 · 功能/数据/监控，建议立即做）

### P-D2 MySQL 从库宕机 + 复制中断（运维）
- **根因确认**：3307 无监听（从库容器未运行）；Aug 9 主库日志 `Cannot replicate ... purged required binary logs`（从库断连超 binlog 保留期，GTID 事务集丢失）。
- **代码涉及**：无（读写分离已在代码层）。
- **改动**：
  1. 从库容器恢复运行（`docker start my-xhs-mysql-slave`，若损坏则重建卷）。
  2. 主库全量 dump（`--source-data=2 --single-transaction`）导入从库。
  3. 从库执行：
     ```sql
     CHANGE REPLICATION SOURCE TO SOURCE_HOST='21.130.247.89', SOURCE_PORT=3306,
       SOURCE_USER='repl', SOURCE_PASSWORD='<新密码>', SOURCE_AUTO_POSITION=1;
     START REPLICA;
     ```
  4. 建专用复制账号 `repl`（替代 root）：`CREATE USER 'repl'@'%' IDENTIFIED BY '<强密码>'; GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%';`
  5. compose 从库去掉 `init-all.sql` 挂载（:197），仅保留复制脚本；从库卷重建不再跑建库 SQL。
- **影响**：读写分离恢复；读流量从主库移回从库。
- **风险**：低（纯运维；dump 期间主库只读窗口需评估）。
- **验证**：`SHOW REPLICA STATUS` → IO/SQL Running=Yes、Seconds_Behind=0；从库读延迟=0。

### P-D4 告警规则引用不存在的指标（监控 · 只改规则文件）
- **根因确认**：19 条规则中约 9 条引用不存在指标（`myxhs_order_create_total` vs 实际 `orders_created_total` 等），4 条依赖缺失的中间件 exporter → 业务/中间件告警整体失效。
- **代码涉及**：无（仅改 prometheus 规则文件；"登录失败指标"为可选项，需埋点则另列）。
- **改动**（`config/prometheus/alert_rules/myxhs_rules.yml`）：
  1. `myxhs_order_create_total` → `orders_created_total`
  2. `myxhs_payment_total` → `payment_callback_total`
  3. `myxhs_login_total` → 暂删该规则（无指标），待补埋点后再加
  4. `myxhs_mq_consume_lag` / `myxhs_mq_dlq_total` / `redis_memory_*` / `elasticsearch_cluster_health_status` / `canal_instance_subscribe_delay` / `rocketmq_broker_disk_ratio` → 标记为"依赖 exporter，未部署前禁用"（注释掉），避免"永静默"误导
  5. `myxhs_mq_dlq_total` 若保留 → 改 `rocketmq_dlq_backlog`（已存在的指标，但语义=按组积压量）
- **影响**：告警从"虚假静默"变为"真实可用"。
- **风险**：低（配置文件，reload 即可）。
- **验证**：`/api/v1/rules` 各规则 health=ok 且能取到样本；构造 5xx 验证 HighErrorRate 触发。

### P-D7 Kibana unavailable（日志检索）
- **根因确认**：Kibana `/status` 000（容器可能已挂）；日志 `security_exception`（kibana_system 凭据与 ES 不匹配）。
- **代码涉及**：无。
- **改动**：
  1. ES 重置 kibana_system 密码：
     ```
     curl -u elastic:Xhs@2026#Elastic -X POST 'http://127.0.0.1:19200/_security/user/kibana_system/_password' \
       -H 'Content-Type: application/json' -d '{"password":"Xhs@2026#KibanaSystem"}'
     ```
  2. 重启 kibana 容器；确认 `docker ps` running。
- **影响**：日志检索恢复。
- **风险**：低。
- **验证**：`curl :15601/status` 200；Kibana 能看到 myxhs-logs-* 索引。

### P-D8 SkyWalking OAP healthcheck 路径 404（容器恒 unhealthy）
- **根因确认**：compose healthcheck 用 `curl :12800/healthCheck`，实际该路径 404（OAP 存活、路径不对）。
- **代码涉及**：无。
- **改动**：compose healthcheck 改为：
  ```
  test: ["CMD-SHELL", "bash -c '</dev/tcp/127.0.0.1/12800' || exit 1"]
  ```
  （或改为 POST /graphql 探测）。
- **影响**：容器健康状态真实化。
- **风险**：低。
- **验证**：`docker ps` 显示 healthy。

### P-D6 VictoriaMetrics 空转（监控数据持久化）
- **根因确认**：VM totalSeries=0；Prometheus 未配 remote_write → 双存储未接线，Prometheus 重启丢 15d 数据。
- **代码涉及**：无。
- **改动**：prometheus.yml 加：
  ```yaml
  remote_write:
    - url: http://127.0.0.1:8428/api/v1/write
      queue_config: { max_samples_per_send: 5000 }
  ```
- **影响**：指标双写 VM，历史可查可持久。
- **风险**：低。
- **验证**：VM `totalSeries > 0`；VM UI 能查到 myxhs_* 指标。

### P-D3 Redis `allkeys-lru` 可驱逐业务数据（数据安全）
- **根因确认**：`maxmemory 256mb + allkeys-lru` → 内存逼近上限时任意驱逐（购物车权威数据/库存预扣/分布式锁/token 黑名单），可致丢车/锁失效/超卖。
- **代码涉及**：无（业务 key 均有 TTL/明确清理路径，不依赖逐出）。
- **改动**（compose redis/redis-slave 段）：
  - `--maxmemory-policy noeviction`
  - `--maxmemory 512mb`（上调）
- **影响**：内存写满时写失败（可感知）而非静默丢数据。
- **风险**：低（当前 used 5.84MB 远低于上限）。
- **验证**：`CONFIG GET maxmemory-policy` → noeviction；业务读写正常。

---

## 第二批（安全 · 改动面大需联动，统一窗口做）

### P-D1 Nacos 无鉴权（配置中心=公开凭据库）
- **根因确认**：未带 token 直接 `GET /nacos/v1/cs/configs` 返回 MySQL/Redis 明文密码 + JWT/HMAC secret。
- **代码涉及**：**是**。所有微服务 Nacos 客户端需加认证配置（每个服务 yml 或公共 Nacos 客户端配置）：
  ```yaml
  spring:
    cloud:
      nacos:
        username: nacos
        password: <新密码>
  ```
  网关同理；且 `nacos.core.auth.plugin.nacos.token.secret.key` 需与客户端对齐。
- **改动**：
  1. compose nacos 段加 env：`NACOS_AUTH_ENABLE=true`、`NACOS_AUTH_TOKEN`（≥64 字节随机串）、`NACOS_AUTH_IDENTITY_KEY/VALUE`。
  2. 改默认账号密码（nacos/nacos → 强密码）。
  3. 所有服务 yml 加 Nacos 账号密码（或 Nacos 客户端统一配置类）。
  4. 敏感配置从 Nacos 明文改为环境变量注入（至少 datasource/redis 密码）。
- **影响**：全服务需重启（Nacos 客户端配置变更）。
- **风险**：**高**。Nacos 认证开启后，未同步配置的服务将无法连接 Nacos（注册/配置全断）。必须：先改配置→再开鉴权→再逐个服务重启验证。
- **验证**：未带 token 访问配置 API → 403；服务启动注册正常。

### P-D5 中间件密码可预测 + 明文入库（含 Canal admin=MD5("admin")）
- **根因确认**：密码全 `Xhs@2026#<Suffix>` 模式且明文落 compose/sql/Nacos/README；Canal admin 密码=`MD5("admin")`。
- **代码涉及**：无（仅配置值），但**联动面广**：所有服务 yml/Nacos 的 datasource/redis 密码、ES/SW/Canal/xxl-job 连接串全要同步。
- **改动**：
  1. 生成随机强密码（每组件独立）。
  2. 全链路同步：各服务 `spring.datasource.password`、redis password、ES 密码、SW_ES_PASSWORD、Canal instance.properties、xxl-job PARAMS。
  3. 改用环境变量注入（yml 里 `${MYSQL_PASSWORD}` 等），本地不落明文。
  4. 改 Canal admin 密码（canal.properties 的 canal.admin.passwd，重新生成 bcrypt 风格哈希）。
  5. .gitignore 排除含密文件（compose 建议改环境变量注入）。
- **影响**：全服务+中间件需重启。
- **风险**：**高**（联动面大，一半改一半没改=中间态故障）。必须一次性全链路切换并逐服务验证。
- **验证**：各服务健康检查 UP、连接正常；未授权访问被拒。

---

## 第三批（治理 · 低优先级）

### P-D9 ES 单节点 yellow
- **改动**：业务 ES 索引模板 `number_of_replicas: 0`（单节点无副本可分配）。零代码。
- **验证**：集群 health green（单节点）。

### P-D10 Nacos 命名空间/库污染
- **改动**：清理 `sca-lab-dev` 命名空间（7 条 dubbo-lab 配置）与 `seata_lab` 库（确认无引用后 drop）。零代码。

### P-D11 RocketMQ autoCreateTopic + ASYNC_FLUSH
- **改动**：broker.conf `autoCreateTopicEnable=false`；评估 `flushDiskType=SYNC_FLUSH`（与 MySQL `sync-binlog=0` 组合的丢数据窗口）。零代码。

### P-D12 Prometheus 目标 IP 双轨
- **改动**：统一 IP 用变量或环境注入（compose 与 prometheus.yml 用 `${HOST_IP}` 之类）。零代码。

---

## 执行批次
- **第一批（零代码/低风险，运维+配置）**：P-D2 → P-D4 → P-D7 → P-D8 → P-D6 → P-D3
- **第二批（安全，需联动+全量重启）**：P-D1、P-D5
- **第三批（治理）**：P-D9、P-D10、P-D11、P-D12
- 每项完成后更新 pitfalls.md 并标注修复状态。
