# 生产环境配置深度 Review（config/production-env-config）

> 2026-08-12 | 基于 docker-container-review-20260811-162334 快照（22 中间件容器 + 宿主机 15 微服务）+ 实时验证。
> 覆盖：配置/安全/日志/全链路/监控/指标/复制/数据流。

---

## 一、结论速览

- **中间件全家桶部署完整**（MySQL 主从/Redis 主从+Sentinel/RocketMQ/Nacos/ES×2/Canal/XXL-Job/SW/Prometheus/VM/Grafana/日志管道）。
- **日志链路 ✅ 有数据**（ES myxhs-logs-* 每天 39万~152万条）；**全链路追踪 ✅ 有数据**（SW sw_segment 每天 69万~179万条）。
- **但监控/告警大面积失效**（规则引用不存在的指标 + 无中间件 exporter）；**Nacos 无鉴权=凭据全裸**；**MySQL 从库宕机+复制中断**；**VictoriaMetrics 空转**；**Kibana 不可用**。

---

## 二、高危（需立即处理）

### P-D1【安全】Nacos 无鉴权 → 全部凭据公开可读
- 实证：`GET /nacos/v1/cs/configs?dataId=my-xhs-common.yaml...` **未带 token** 直接返回 `spring.datasource.password: Xhs@2026#MySQL`、Redis 密码。
- Nacos 里存着所有服务的 DB/Redis 密码 + `my-xhs-gateway.yaml` 的 JWT/HMAC secret。
- **修复**：
  1. Nacos 开启鉴权（compose nacos 段加 env）：
     ```
     NACOS_AUTH_ENABLE: "true"
     NACOS_AUTH_TOKEN: <64字节以上随机串(需≥32字节, base64)>
     NACOS_AUTH_IDENTITY_KEY: serverIdentity
     NACOS_AUTH_IDENTITY_VALUE: <随机串>
     ```
  2. 修改默认账号密码：`nacos`/`nacos` → 强密码（改前先登录生成新密码 bcrypt）。
  3. 配置加密：至少将 `spring.datasource.password` / redis password 改为环境变量注入（`${MYSQL_PASSWORD}`），Nacos 配置里不再落明文。
  4. 控制台与 18848 端口收紧：仅运维 IP 可访问（防火墙）。
- **验证**：未带 token 访问 configs API 返回 403；带新账号密码登录成功。

### P-D2【安全】MySQL 从库宕机 + 主从复制曾中断（binlog 被清理）
- 实证：**3307 当前无监听（从库容器未运行）**；Aug 9 主库日志 `Cannot replicate ... server has purged required binary logs`（从库断连超过 binlog 保留期，GTID 事务集丢失）。
- 影响：读写分离失效（读全部压主库）；从库恢复后需重建复制。
- **修复**：
  1. 从库重建（不要跑 init-all.sql！）：
     ```
     # 主库
     mysqldump --all-databases --source-data=2 --single-transaction > dump.sql
     # 从库：导入 dump 后
     CHANGE REPLICATION SOURCE TO SOURCE_HOST='21.130.247.89', SOURCE_PORT=3306,
       SOURCE_USER='repl', SOURCE_PASSWORD='<新密码>', SOURCE_AUTO_POSITION=1;
     START REPLICA;
     ```
  2. 建专用复制账号 `repl`（不要用 root）：
     `CREATE USER 'repl'@'%' IDENTIFIED BY '<强密码>'; GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%';`
  3. binlog 保留：`binlog_expire_logs_seconds` 30d 已够，但需**监控从库延迟**，断连超过 30d 必须告警（见监控修复）。
  4. compose 从库去掉 `init-all.sql` 挂载（:197），只保留复制脚本；并加 `depends_on` 健康条件。

### P-D3【安全】Redis `maxmemory 256mb + allkeys-lru` 可驱逐业务数据
- 影响：内存逼近 256MB 时任意驱逐——购物车（权威数据源）/库存桶/预扣记录/分布式锁/Token 黑名单/非ce 都可能被清 → 丢车、锁失效、**库存预扣记录丢失可致超卖**。
- 当前 used 5.84MB，属未爆雷的定时炸弹；Redis 7 的 lru 逐出对业务无感知。
- **修复**：
  1. 改 `--maxmemory-policy noeviction`（业务 key 都有 TTL/明确清理路径，宁可写失败不可静默丢数据）；`maxmemory` 上调到 512m-1g。
  2. 审计无 TTL 的 key（counter 永久 key、payment status key 等），补 TTL。
  3. 加 Redis 内存告警（见监控修复，需 redis_exporter）。

### P-D4【监控】告警规则大量引用不存在的指标 → 业务/中间件告警整体失效
- 实证（对比代码与 Prometheus 实际指标）：
  | 规则引用 | 实际指标 | 结论 |
  |---|---|---|
  | `myxhs_order_create_total{status="fail"}` | `orders_created_total{status=...}` | ❌ 永不触发 |
  | `myxhs_payment_total{status="success"}` | `payment_callback_total{status=...}` | ❌ 永不触发 |
  | `myxhs_login_total` | 无登录指标 | ❌ 永不触发 |
  | `myxhs_mq_consume_lag` | 无 lag 指标（无 exporter） | ❌ 永不触发 |
  | `myxhs_mq_dlq_total` | `myxhs.mq.dlq.total`(点分) | ❌ 永不触发 |
  | `redis_memory_used_bytes` | 无 redis_exporter | ❌ 永不触发 |
  | `elasticsearch_cluster_health_status` | 无 ES exporter | ❌ 永不触发 |
  | `canal_instance_subscribe_delay` | Prometheus 无 canal job | ❌ 永不触发 |
  | `rocketmq_broker_disk_ratio` | 无 RocketMQ exporter | ❌ 永不触发 |
  | `jvm_*`/`hikaricp_*`/`up{job=...}`/`myxhs_http_request_duration_seconds*{application=...}` | ✅ 存在 | ✅ 可用 |
- **修复**：
  1. **改规则指标名**对齐代码：`orders_created_total`、`payment_callback_total`、`myxhs.mq.dlq.total`(需 promql 转义或改代码指标名)。
  2. **补登录指标**：UserService 埋 `Counter login.total{result=success|fail}`（代码层）。
  3. **补中间件 exporter**（compose 新增容器）：
     - redis_exporter（监听 9121）→ prometheus job
     - elasticsearch_exporter 或 ES 8 自带 `/_prometheus/metrics`（需开 `xpack.monitoring.enabled`）
     - rocketmq-exporter（rocketmq-exporter 镜像）
     - canal 指标：Prometheus 加 canal 抓取（canal.metrics.pull.port=11112 暴露 /metrics）
  4. MQ 积压：加 rocketmq-exporter 的 `rocketmq_consumer_tps`/积压指标，或自研 consumerLag 采集任务（复用 rocketmq admin API）。

### P-D5【安全】中间件密码模式可预测 + 明文进 git
- 密码全为 `Xhs@2026#<Suffix>` 模式（MySQL/Redis/ES/ES-SW/KibanaSystem/Grafana/xxl），且 compose/sql/README/Nacos 明文落库。
- **修复**：全部改随机强密码 + 环境变量注入 + .gitignore 排除含密文件 + 密码轮换脚本。Canal admin 密码 `cd2454cad63e8a24af20c207581e5633` = `MD5("admin")` 需同步改。

---

## 三、中危

### P-D6【可用性】VictoriaMetrics 空转（totalSeries=0，Prometheus 未配 remote_write）
- 双存储架构部署了但没接线。Prometheus 是单机 tsdb，重启丢 15d 历史。
- **修复**：prometheus.yml 加
  ```yaml
  remote_write:
    - url: http://127.0.0.1:8428/api/v1/write
      queue_config: { max_samples_per_send: 5000 }
  ```

### P-D7【可用性】Kibana unavailable（ES security_exception）
- 实证：Kibana /status 000（容器可能已挂）；日志 `Unable to retrieve version information from Elasticsearch nodes. security_exception` —— kibana_system 凭据与 ES 不匹配（ES 8 首次启动随机生成 kibana_system 密码，除非 `ELASTIC_PASSWORD` 设置时也设了 kibana_system）。
- **修复**：ES 中重置 kibana_system 密码：
  ```
  curl -u elastic:Xhs@2026#Elastic -X POST 'http://127.0.0.1:19200/_security/user/kibana_system/_password' -H 'Content-Type: application/json' -d '{"password":"Xhs@2026#KibanaSystem"}'
  ```
  然后重启 kibana 容器；并确认 kibana 容器 running（`docker ps`）。

### P-D8【可用性】SkyWalking OAP healthcheck 路径 404
- compose healthcheck 用 `curl http://127.0.0.1:12800/healthCheck`，实际该路径 404（OAP 存活但 healthcheck 判定失败 → 容器恒 unhealthy）。
- **修复**：healthcheck 改为 `curl -sf http://127.0.0.1:12800/graphql -X POST -H 'Content-Type: application/json' -d '{"query":"{services:queryServices(duration:{start:\"2020-01-01 000000\",end:\"2020-01-01 000000\",step:MINUTE}){key:name}}"} '` 或改测 12800 TCP 端口连通（`bash -c '</dev/tcp/127.0.0.1/12800'`）。

### P-D9【一致性】ES 单节点 yellow（15 主分片 1 副本）
- 单节点配副本 → yellow 是结构性问题；sw_segment 5 主 0 副是 green。
- **修复**：业务 ES 索引模板 `number_of_replicas: 0`（单节点），或接受 yellow 并在告警里排除（无 exporter 时本就不告警）。

### P-D10【治理】Nacos 命名空间污染
- `sca-lab-dev` 命名空间 7 条 dubbo-lab 测试配置 + `seata_lab` 库残留（与 my-xhs 无关）。
- **修复**：清理 sca-lab-dev 命名空间与 seata_lab 库（确认无引用后 drop）。

### P-D11【可靠性】RocketMQ `autoCreateTopicEnable=true` + ASYNC_FLUSH
- 生产建议：`autoCreateTopicEnable=false`（防止 typos 静默建 topic）；`flushDiskType=SYNC_FLUSH` 或保留 ASYNC 但明确接受丢窗口（与 MySQL `sync-binlog=0` 组合，极端故障可能丢订单/支付数据）。至少对支付/订单相关 topic 单独配置。

### P-D12【可运维】Prometheus 目标 IP 双轨
- 微服务用 `21.214.97.212`、中间件/自身用 `21.130.247.89`——机器 IP 变更需双处修改；建议统一用变量/consul 服务发现，或至少注释标明。

---

## 四、日志/全链路/监控现状核查结论（已深度确认）

| 维度 | 状态 | 证据 |
|---|---|---|
| 日志采集链路 | ✅ 通 | Filebeat(/logs/*.json)→Logstash(15044/15045)→ES；myxhs-logs-* 每天 39万~152万条 |
| 日志检索 | ❌ Kibana unavailable | security_exception；/status 000 |
| 全链路追踪 | ✅ 通 | SkyWalking OAP 收 trace；sw_segment 每天 69万~179万条 |
| 微服务指标采集 | ✅ 通 | Prometheus 15 服务全部 up（10s 间隔） |
| 业务指标埋点 | ⚠️ 部分 | orders_created_total 等存在；但告警名不匹配 |
| 中间件指标 | ❌ 缺失 | 无 redis/ES/canal/rocketmq exporter |
| 告警 | ❌ 大半失效 | 19 条规则中约 9 条引用不存在指标；4 条依赖缺失 exporter |
| 双存储 | ❌ VM 空转 | Prometheus 无 remote_write；VM totalSeries=0 |
| Grafana | ⚠️ 可用但看板缺数据 | 4 看板接 Prometheus；依赖失效指标的看板为空 |
| MySQL 主从 | ❌ 从库宕机+复制曾断 | 3307 无监听；purged binlog 错误 |
| 容器错误 | ⚠️ 需关注 | Sentinel-dashboard 796 条 MetricFetcher refused（Aug10，待确认恢复）；xxl-job 168、nacos 43 条待抽看 |

---

## 五、修复批次建议

1. **第一批（安全，立即）**：P-D1 Nacos 鉴权+改密、P-D5 密码随机化（至少改 Nacos/MySQL root）、P-D3 Redis noeviction。
2. **第二批（可用性）**：P-D2 从库重建+复制账号、P-D7 Kibana 密码修复、P-D8 OAP healthcheck、P-D6 VM remote_write。
3. **第三批（监控闭环）**：P-D4 告警指标名对齐 + 补中间件 exporter + 补登录指标 + MQ 积压指标。
4. **第四批（治理）**：P-D9 ES 副本、P-D10 命名空间清理、P-D11 RocketMQ 配置、P-D12 IP 统一。
