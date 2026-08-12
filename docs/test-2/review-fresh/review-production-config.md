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

---

## 六、SkyWalking 全链路深度 Review（2026-08-12 追加）

> 用户提示"全链路之前说过有问题"，深度复核 SkyWalking 全链路（agent/OAP/插件/运行态）。

### P-T1【高】JDK 线程池 / ForkJoinPool 传播插件未启用 → 异步链路在 SkyWalking 断链
- **根因确认**：agent 150 个插件中，`apm-jdk-threadpool-plugin-9.6.0.jar` 与 `apm-jdk-forkjoinpool-plugin-9.6.0.jar` 位于 **bootstrap-plugins（默认禁用）**，未移至 plugins 启用。
- **影响**：项目大量异步链路（product SPU_ASYNC_EXECUTOR、inventory 扩容池、order ORDER_ASYNC_EXECUTOR、cart 对账池、home aggregatorPool/batchFeignPool，以及各模块 `CompletableFuture.runAsync`(commonPool/ForkJoinPool)）的 **sw8 trace 上下文不跨线程** → SkyWalking UI 中这些异步段（缓存刷新/补偿/联动释放/聚合）与主请求 trace 断链、成孤儿 span。
- **注意**：此前修复的 O2（MdcAwareExecutorService 传播自研 X-Trace-Id 到 MDC）解决的是**自研日志体系**；SkyWalking 是独立 sw8 体系，**O2 解决不了 SW 的异步断链**。
- **修复**（运维，零代码）：
  ```
  mv skywalking-agent-9.6.0/bootstrap-plugins/apm-jdk-threadpool-plugin-9.6.0.jar skywalking-agent-9.6.0/plugins/
  mv skywalking-agent-9.6.0/bootstrap-plugins/apm-jdk-forkjoinpool-plugin-9.6.0.jar skywalking-agent-9.6.0/plugins/
  ```
  然后重启全部微服务（start-all.sh）。
- **验证**：SkyWalking UI 中查一条含异步任务的请求（如下单→取消触发异步释放库存），确认异步 span 挂在同一 trace 下。

### P-T2【高】agent 9.6.0 与 OAP 9.7.0 版本不匹配
- **根因确认**：微服务 `-javaagent:skywalking-agent-9.6.0/...`；OAP 镜像 `apache/skywalking-oap-server:9.7.0`。SkyWalking 官方要求 agent 与 OAP **大版本一致**（9.x 内建议小版本一致），9.6→9.7 有协议演进风险（当前有数据在收，但非官方推荐组合，升级累积风险）。
- **修复**：升级 agent 至 9.7.0（下载 skywalking-java-agent-9.7.0 替换 skywalking-agent-9.6.0，start-all.sh 路径同步改），与 OAP 对齐。
- **验证**：agent 启动日志无版本告警；UI trace 正常。

### P-T3【中】OAP telemetry(1234) 未接入 Prometheus
- **根因确认**：compose 配了 `SW_TELEMETRY=prometheus` + `SW_TELEMETRY_PROMETHEUS_PORT: 1234`，但 **1234 端口未监听**（ss 无输出），且 Prometheus prometheus.yml 无 OAP job。
- **影响**：OAP 自身指标（接收吞吐/队列积压/存储延迟）不可监控 → OAP 故障、trace 积压无感知。
- **修复**：① 排查 OAP 9.7 telemetry 配置项是否正确生效（SW_TELEMETRY 取值/路径）；② Prometheus 加 OAP job（`21.130.247.89:1234/metrics`）。

### P-T4【中】trace 全采样 100% → SW-ES 存储压力
- **根因确认**：未配 `SW_TRACE_SAMPLE_RATE`，OAP 默认 100% 采样；sw_segment 每天 69万~179万条（305MB/天）。
- **影响**：SW-ES 存储增长快（30d 保留下 ~9GB/月），多数低价值 trace 占用资源。
- **修复**：compose OAP env 加 `SW_TRACE_SAMPLE_RATE: 10`（10% 采样），关键服务可用 `SW_TRACE_SAMPLE_RATE=<service>:100` 覆盖。

### P-T5【低】11800 gRPC 端口杂散 HTTP 请求（9 次 Http2Exception）
- **根因确认**：OAP 日志 9 次 `Http2Exception: Unexpected HTTP/1.x request: GET /` / `client preface missing (0a)` —— 非 gRPC 客户端（HTTP 探针）连到 11800。
- **影响**：无功能影响，仅日志噪音；可能是监控探针误连。
- **修复**：排查 11800 来源（确认无 HTTP 健康检查打到此端口）；如 OAP healthcheck 误用 11800 则改 12800。

### 结论
- **全链路"有数据但异步断链"**：HTTP/Feign/MQ 同步链路 trace 完整（sw_segment 每天百万级），但**线程池/ForkJoin 异步段全部断链**（P-T1 插件未启用）+ **agent/OAP 版本漂移**（P-T2）。修 P-T1/T2 后全链路才真正闭环。

---

## 七、第二轮深挖修正与新增（2026-08-12）

### ✅ 修正（推翻/下调此前判断）

**M-1【修正】iptables 实际是收紧的 → P-D1 公网暴露面下调**
- 之前仅看 MYXHS 链首行，误判"公网裸奔"。实际 MYXHS 链对**每个端口**均为 `ACCEPT 127.0.0.1 + ACCEPT 21.214.97.212 + DROP 其余来源 state NEW`（中间件端口全部有 DROP 兜底），INPUT policy ACCEPT 但先跳 MYXHS 链。
- **结论**：MySQL/Redis/ES/Nacos/RocketMQ 等仅本机(127.0.0.1)与微服务机(21.214.97.212)可达，公网直连被挡。P-D1（Nacos 无鉴权）仍成立但**暴露面限于本机/微服务机**——风险从"公网可读"降为"本机进程/容器可读 + 凭据明文入库"。

**M-2【修正】P-D8 OAP healthcheck 并非恒 unhealthy**
- compose healthcheck `curl -s http://127.0.0.1:12800/healthCheck || exit 1`：curl 对 HTTP 404 **exit 0**（未加 -f）→ healthcheck 实际通过，容器非恒 unhealthy。
- **结论**：P-D8 撤销为建议项——端点不存在但探测逻辑能过，建议改为真实探测（POST /graphql 或 TCP 探测）以反映真实健康。

### 🆕 新增

### P-D13【高·监控闭环】Prometheus 无 Alertmanager → 告警无出口
- **根因确认**：prometheus.yml 无 `alerting:` 段、无 alertmanager 容器；flags `alertmanager.timeout` 为空。
- **影响**：P-D4 修好后即使告警触发，**也没有任何通知接收方**（无 webhook/邮箱）→ 监控闭环最后一块缺失。
- **修复**：部署 alertmanager 容器（compose）+ prometheus.yml `alerting.alertmanagers` + 通知渠道（webhook/企业微信/邮箱），或至少 webhook 到内部通知端点。

### P-D14【高·存储】ES 业务日志索引无 ILM/生命周期 → 无限增长
- **根因确认**：ES 模板列表无 myxhs 专属模板/ILM policy；`myxhs-logs-YYYY.MM.DD` 每天一个索引（峰值 152 万条/1GB/天）**永不过期**。
- **影响**：磁盘 100G（当前 30% 用），按 1GB/天 约 70 天后满；无清理=定时炸弹。
- **修复**：
  ```
  PUT _ilm/policy/myxhs-logs-policy
  { "policy": { "phases": { "hot": {...}, "delete": { "min_age": "30d", "actions": { "delete": {} } } } } }
  # 并为 myxhs-logs-* 建索引模板挂 policy + number_of_replicas:0（单节点）
  ```
  或 Logstash 输出按天索引 + 定时删除 30 天前索引（cron/curator）。

### P-D15【高·可用性】mysql-slave 内存 97.5%、logstash 94% 濒临 OOM（宿主无 Swap）
- **根因确认**：docker-stats 显示 mysql-slave 749MiB/768MiB(97.5%)、logstash 721.6/768(94%)；宿主 Swap=0 → 内存打满直接 OOM kill（此前从库宕机/复制中断很可能与此相关）。
- **修复**：① 从库 memory 限制上调（768m→1.5g）；② logstash 上调（768m→1g）+ `LS_JAVA_OPTS` 堆限制；③ 宿主机评估加 Swap 或扩容。

### P-D16【中·安全】Kibana 随机 encryptionKey + session cookie 无 HTTPS
- **根因确认**：kibana 日志 `Generating a random key for xpack.security.encryptionKey`（重启后 session 全失效）+ `Session cookies will be transmitted over insecure connections`（HTTP 明文）。
- **修复**：kibana.yml 固定 `xpack.security.encryptionKey`；控制台访问走 HTTPS 或至少限内网。

### P-D17【低·治理】RocketMQ 测试 topic 残留 + 未使用镜像
- RocketMQ 有 `BenchmarkTest`/`SELF_TEST_TOPIC` 残留；docker 有 seata×3、旧 kibana 8.13.1、旧 logstash 8.12.2、alpine、codev-agent 等未使用镜像（约 5-6GB 可回收）。
- **修复**：删残留 topic（`autoCreateTopicEnable=false` 后）；`docker image prune` 清理。

### P-D18【低】Prometheus TSDB 404 序列 510 个
- `status=404` 序列最多（510）——大量 404 请求被采集（可能是扫描/探测打到微服务）。建议排查 404 来源（可能是公网扫描被 iptables 挡后仍打到微服务机 21.214.97.212）。

### 其他确认
- OAP prometheus 指标文件存在（process/jvm 指标），但 **1234 端口未监听**（P-T3 待排查 telemetry 实际端口）；Prometheus 无 OAP job → OAP 指标未采集。
- Prometheus TSDB：5803 序列；`rocketmq_dlq_backlog` 322 序列（DLQ 指标在采，但告警规则名不匹配仍失效）。
- Sentinel-dashboard 796 条 MetricFetcher refused 为快照时刻（Aug10）状态，当前 8719-8729 端口在监听，需观察是否恢复。

---

## 八、第三轮深挖（2026-08-12）：xxl-job 调度配置严重错配 + 备份缺失 + Redis HA 名义化

### P-D20【高】xxl-job 调度配置严重错配：19 个代码任务仅约 8 个可调度
- **根因确认**（代码 @XxlJob 清单 vs xxl_job_info/xxl_job_group 快照）：
  - 代码 19 个任务：cartReconcileJob/counterReconcileJob/couponExpireJob/couponReconcileJob/deadLetterScanJob/feedCleanupJob/followCounterRepairJob/inventoryReconcileJob/localMessageRetryJob/orderCloseJob/orderMappingRepairJob/paymentNotifyCompensateJob/paymentTimeoutCheckJob/recommendFeatureJob/recommendHotPoolJob/recommendItemCFJob/refundNotifyCompensateJob/refundTimeoutCheckJob/unreadReconcileJob
  - xxl_job_group 只有 6 组：sample(地址NULL)/analytics(9999)/counter(9998)/inventory(9996)/notification(9990)/payment(9992)。**无 order/coupon/cart/home/search 组**（order 执行器 appname=my-xhs-order:9991 在监听但无对应组）。
  - xxl_job_info 15 条中：**任务 10-15（orderCloseJob/localMessageRetryJob/deadLetterScanJob/orderMappingRepairJob/inventoryReconcileJob/couponReconcileJob）全部挂在 group 1(sample, 地址NULL)** → 永不调度。
  - **couponExpireJob/cartReconcileJob/feedCleanupJob/recommendFeatureJob/recommendHotPoolJob/recommendItemCFJob 未创建任务**。
- **影响**（失效的兜底机制）：
  - `orderCloseJob` 失效 → 超时关单仅靠 RocketMQ 延时消息；延时消息丢失则订单永不关闭、库存/券不释放。
  - `localMessageRetryJob`/`deadLetterScanJob` 失效 → 本地消息表补发/死信扫描机制失效。
  - `inventoryReconcileJob` 失效 → 库存对账兜底失效（库存漂移/泄漏无对账）。
  - `couponExpireJob`/`couponReconcileJob` 失效 → 过期券不标记过期、券账无对账。
  - `cartReconcileJob` 失效 → 购物车 Redis/MySQL 对账失效。
  - `feedCleanupJob` 失效 → feed 流清理失效。
  - `recommend*`×3 失效 → 推荐索引/热池/协同不更新。
- **修复**：
  1. 为 order/coupon/cart/home/search 建执行器组（xxl-job-admin 手动加组 + address 指向对应 executor 端口 9991/9993/9995/9997/9994 等）。
  2. 任务表修正 job_group：order 4 个→order 组；inventoryReconcileJob→inventory 组(4)；couponReconcileJob→coupon 组。
  3. 补建缺失任务：couponExpireJob/cartReconcileJob/feedCleanupJob/recommend×3（按各模块 cron 语义）。
  4. 核对各模块 executor appname/port 与组一致。
- **风险**：中（涉及调度配置与任务创建，改后需验证每个任务能触发）。
- **验证**：xxl-job-admin 手动触发每个任务 → 执行成功；orderCloseJob 触发后待付款订单被关闭。

### P-D19【高】MySQL 无任何备份
- **根因确认**：宿主机 cron 仅腾讯云 agent；无 mysqldump/备份脚本/备份容器；binlog 保留 30 天但无离线备份机制。
- **影响**：主库数据丢失（误删/故障）不可恢复（从库数据也不可靠——复制曾中断）。
- **修复**：配置每日 mysqldump 全量 + binlog 增量（或至少 `xtrabackup` 全量 + cron），备份到独立目录/对象存储；保留 7 天全量 + 30 天增量。
- **风险**：低。**验证**：手动执行备份脚本 → 恢复演练（restore 到临时实例校验）。

### P-D21【低】Redis 高可用名义化（单 Sentinel 单从）
- **根因确认**：sentinel 只有 1 个（sentinels=1, quorum=1）；master 仅 1 从；单 sentinel 单点，其自身故障即 HA 失效；且主从 announce-ip 均指公网 IP。
- **影响**：主节点故障时 failover 依赖唯一 sentinel（单点），且从库 6380 上 announce 正常；实际 HA 能力弱。
- **修复**：至少 3 个 sentinel + 2 从（compose 加容器）；或明确接受"单点演示级"HA 并在文档标注。
- **风险**：低（增强）。**验证**：停 master → sentinel 自动 failover 到从。

### 其他确认（本轮）
- order/inventory executor 均在监听（9991/9996），注册正常但**组缺失/错配**（P-D20 根因）。
- Prometheus 采集正常（16 targets up）；业务指标在采（myxhs_http_request_duration_seconds 824 序列、rocketmq_dlq_backlog 322 序列）——P-D4 只修规则名即可让部分告警复活。
- xxl-job 任务 trigger_status：demo/followCounterRepair 重复项停用（0），业务任务启用（1）。

---

## 九、第四轮深挖（2026-08-12）：schema 漂移 + 日志风暴历史 + 部署拓扑

### P-D22【高】t_inventory_compensation schema 漂移 → 库存回滚补偿机制全失效
- **根因确认**（生产 DB vs init-all.sql vs 代码三边对比）：
  - 生产表：`id,order_id,sku_id,quantity,action,status,created_at`（**有 action，无 retry_count/fail_reason**）
  - init-all.sql：`id,order_id,sku_id,quantity,fail_reason,status,retry_count,created_at`（有 retry_count/fail_reason）
  - 代码 InventoryMapper：insert 引用 `fail_reason`、select/increment 引用 `retry_count` → **写/读/重试/标记 4 个方法全部 SQL 错误**
- **实证**：当前 inventory 日志持续 `Unknown column 'retry_count' in 'where clause'`（scheduled task ERROR）。
- **影响**：库存预扣回滚失败的补偿记录**写不进去、扫不出来、重试不了** → 回滚失败=永久丢失（依赖对账也救不了已丢记录）；InventoryCompensationJob 每天空转报错。
- **修复**：按 init-all.sql/代码结构重建表（生产表是旧版）：
  ```sql
  ALTER TABLE my_xhs_inventory.t_inventory_compensation
    ADD COLUMN fail_reason VARCHAR(512) NOT NULL DEFAULT '' AFTER quantity,
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0 AFTER status,
    DROP COLUMN action,
    ADD INDEX idx_status_retry_created (status, retry_count, created_at);
  ```
  （或先备份旧数据再按 init-all.sql 重建）
- **风险**：低-中（需确认无在用 action 列）。
- **验证**：InventoryCompensationJob 执行成功；手工 insert/select 补偿记录正常。

### P-D23【中·已修复的历史故障】8/9 全服务 Redisson 日志风暴（9GB/天）
- **根因确认**：微服务(21.214.97.212)与中间件(21.130.247.89)**分机部署**；当时微服务 Redis 配置残留旧端口 16379 + Sentinel 下发 `127.0.0.1:6379`（指向微服务本机，无 Redis）→ 所有服务 Redisson 循环重连失败（`Unable to change master...16379`、`Unable to add slave 127.0.0.1:6380`），8/8-8/10 全服务 ERROR 刷屏（单服务 12 万行/天、~5KB/行堆栈，**全服务单日 ~9GB**）。
- **现状**：8/11 配置修复（Nacos my-xhs-redis.yaml port=6379、sentinel announce 21.130.247.89）+ 服务重启后风暴消失（当前 0 Redisson ERROR）✅。
- **经验**：跨机部署下 Sentinel 必须 `announce-ip 中间件机IP`，禁止下发 127.0.0.1；Redis 端口配置变更需全链路核对。
- **遗留**：无监控导致风暴持续 3 天未发现（P-D4/P-D13 失效的直接后果）。

### P-D24【中】/logs 日志无清理策略（14GB 累积）
- **根因确认**：/logs 自 8/5 累积 **14GB**（单日峰值 9GB/8-9）；logback JSON_FILE RollingFileAppender 只按天滚动**不删除**（无 maxHistory/cleanHistoryOnStart）。
- **影响**：磁盘 100G（当前 30%）会被日志持续吃满；叠加 ES myxhs-logs 双份存储。
- **修复**：各服务 logback-spring.xml JSON_FILE 加 `maxHistory=7` + `totalSizeCap=2GB`（或统一 logstash 侧 ILM）；清理历史 14GB。
- **风险**：低。**验证**：滚动后旧日志自动删除。

### P-D25【低】脏表 my_xhs_order.t_local_message（5 列残留）
- 生产 `my_xhs_order.t_local_message`（id/order_id/order_no/user_id/created_at）为旧版残留，代码实际用分片表 `t_local_message_0..3`（10 列，结构一致✅）。
- **修复**：确认无引用后 drop。

### 部署拓扑确认（本轮）
- **微服务机 = 21.214.97.212（本机）**，**中间件机 = 21.130.247.89**（跨机部署）。
- iptables（中间件机）白名单 127.0.0.1 + 21.214.97.212 放行，其余 DROP —— 微服务→中间件走白名单，安全边界合理；**但微服务机侧是否有同等防护未确认**（快照仅含中间件机 iptables）。
- **时区混用**：容器 TZ 三种（Asia/Shanghai×2、Etc/UTC、PRC）+ 微服务未显式 TZ（宿主 +08:00）→ 中间件日志 UTC 与业务日志 +08 混用，排查跨服务日志时区错乱。建议统一容器 TZ=Asia/Shanghai、微服务显式 `-Duser.timezone=Asia/Shanghai`。

---

## 十、第五轮深挖补充（2026-08-12）：容器内实际配置核对

### P-D26【低】mysql-slave 未配置固定 relay-log 文件名
- **根因确认**：slave 日志 `Neither --relay-log nor --relay-log-index were used; so replication may break when this MySQL server acts as a replica and has his hostname changed`——从库 relay log 用主机名默认命名，主机名变更即复制中断。
- **修复**：从库参数加 `--relay-log=mysql-relay-bin --relay-log-index=mysql-relay-bin.index`（或 my.cnf 固化）。
- **风险**：低。**验证**：重启从库后复制正常、主机名变更不中断。

### P-D27【低】Kibana monitoring 指向不可解析地址
- **根因确认**：kibana.yml 镜像默认 `elasticsearch.hosts: ["http://elasticsearch:9200"]` + `monitoring.ui.container.elasticsearch.enabled: true`——host 网络下 `elasticsearch` DNS 不可解析（实际 ES 在 127.0.0.1:19200，经 env ELASTICSEARCH_HOSTS 覆盖生效，但 **monitoring 组件仍走 yml 的 elasticsearch:9200**）→ Kibana 监控面板异常。
- **修复**：kibana env 补 `MONITORING_UI_CONTAINER_ELASTICSEARCH_ENABLED: "false"`（host 网络下关闭容器监控），或改 kibana.yml。
- **风险**：低。**验证**：Kibana monitoring 面板不再报连接错误。

### 其他确认（第五轮）
- **从库恢复轨迹**：8/9 03:01 曾 `CHANGE REPLICATION SOURCE TO (127.0.0.1:3306)` + START GTID 复制（尝试修复 1236），但**当前(8/12) 3307 无监听 → 从库容器仍未运行**，P-D2 需按"重建从库"执行（含 relay-log 参数）。
- **Nacos 鉴权三度确认**：application.properties `NACOS_AUTH_TOKEN:` 空、`NACOS_AUTH_IDENTITY_KEY/VALUE:` 空 → 鉴权未开启（P-D1）。
- filebeat 配置走命令行 -E 参数（yml 为默认），ES 有数据 → 采集正常。
- gateway `/actuator` 的 discoveryClient services 子端点返回 500（快照）——网关 actuator discovery 子端点异常（低优先，排查）。

---

## 十一、第六轮深挖（2026-08-12）：微服务机安全边界 + actuator 暴露

### P-D28【高·安全】微服务机无网络防护 + actuator loggers 端点可远程改日志级别
- **根因确认**：
  1. **微服务机(21.214.97.212，本机) iptables 完全为空**（无 INPUT 规则、无 MYXHS 类链）——与中间件机(21.130.247.89)的 MYXHS 白名单链**不对称**。微服务全端口 0.0.0.0 监听（19000-19016、sentinel 8721-8729、xxl-executor 9990-9999）在网络层**无任何拦截**（依赖云安全组兜底，本层未验证）。
  2. **gateway /actuator/loggers 无鉴权且可写**：实测 `POST /actuator/loggers/<logger>` 未认证即 204 成功（可远程改日志级别→刷爆磁盘/掩盖攻击日志）。`/actuator/env` 未暴露（exposure 仅 health/info/prometheus/metrics/loggers），但 loggers 已构成风险。
- **修复**：
  1. 微服务机配置与中间件机同等 iptables 白名单（仅允许 gateway 对外端口 19000 + 运维 IP；19001-19016/872x/999x 仅内网）。
  2. actuator 加固：`management.endpoints.web.exposure.include` 去掉 `loggers`（或加 `management.endpoint.loggers.enabled=false`）；生产建议仅暴露 health/prometheus，并加 `management.server.port` 独立端口 + 内网绑定。
  3. 云安全组收紧（如腾讯云安全组只开 19000/8080 等必要端口）。
- **风险**：低（配置级）。**验证**：外部 IP 访问 19001/actuator/loggers 被拒；改日志级别返回 401/404。

### P-D29【低】MySQL 慢查询 11 次
- **根因确认**：slow_query_log=ON、long_query_time=0.5s、Slow_queries=11（累计）。慢日志在 /var/lib/mysql/...-slow.log（未抓内容）。
- **修复**：拉取慢日志分析（重点 t_order 分片查询、t_user_coupon 等）；如为分片路由问题（不带分片键的全库扫描）需优化 SQL/加映射表。
- **风险**：低。**验证**：慢查询清零或确认无业务影响。

### 其他确认（第六轮）
- Nacos `my-xhs-gateway.yaml` 明文存 **JWT secret + HMAC secret**（P-D1 补充实证：无鉴权 Nacos = 签名密钥全泄露）。
- 微服务 JVM 堆：order 337MB/1GB、user 232MB/512MB（正常）；MySQL Threads_connected=100/500（正常）。
- Sentinel 客户端端口 8721-8729 公网监听（dashboard 796 错误相关，但当前可连）。

---

## 十二、业务链路 × 配置问题影响矩阵（2026-08-12，第七轮·结合业务分析）

> 前六轮是基础设施视角；本轮到业务视角：把每个配置问题落到**具体业务链路**的用户/资金/数据影响，并区分"已由进程内兜底 vs 真正失效"。

### 关键修正（结合业务后的准确判定）
- **@Scheduled（进程内）与 @XxlJob（调度中心）分布**：
  - order 模块 **0 个 @Scheduled**、3 个 @XxlJob → orderCloseJob/localMessageRetryJob/deadLetterScanJob/orderMappingRepairJob 失效 = **无任何进程内兜底**（超时关单只剩 RocketMQ 延时消息一条腿）。
  - inventory：4 @Scheduled（PreDeductTimeout/TccTimeout/Compensation/其他）+ 1 @XxlJob → **预扣 30min 超时释放、TCC 超时、补偿任务都在进程内跑**（补偿任务每 30s 执行但因 P-D22 SQL 错误失败）；仅 inventoryReconcileJob(@XxlJob) 失效。
  - coupon：1 @Scheduled + 2 @XxlJob → CouponExpireJob/CouponReconcileJob(@XxlJob) 失效。
  - cart/home：全 @XxlJob → cartReconcileJob/feedCleanupJob 失效（无兜底）。
  - search：3 @Scheduled（增量补偿/重建等）+ recommend×3(@XxlJob) 失效。
  - payment：4 @XxlJob 有效 ✅ + 1 @Scheduled。

### 业务链路影响矩阵

| 业务链路 | 相关配置问题 | 实际业务影响 | 兜底情况 |
|---|---|---|---|
| **下单→超时关单** | P-D20 orderCloseJob 失效 | 未支付订单**永不自动关闭** → 库存/券被无效订单长期占用（可用库存减少、用户买不到）；延时消息若丢失=永久 | ❌ 无（order 无 @Scheduled）|
| **下单→库存预扣** | P-D22 补偿表 SQL 错 | 预扣回滚失败的补偿记录写不进/扫不出 → 极少数回滚失败=**库存永久丢失**（超卖/少卖累积） | ⚠️ 任务在跑但 SQL 全错 |
| **库存一致性** | P-D20 inventoryReconcileJob 失效 | Redis/MySQL 库存漂移无对账 → 漂移累积不被发现（配合补偿失效放大）| ❌ 无 |
| **支付/退款** | 无（payment 4 任务有效）| 支付超时检查/通知补偿/退款补偿正常 | ✅ 健全 |
| **领券/用券** | P-D20 couponExpireJob 失效 | 过期券不标记（列表仍显示可用）；**用券时按模板 valid_end 即时校验会被拒** → 实际资损风险低，仅展示不准 | ⚠️ 业务层有即时校验保护 |
| **优惠券对账** | couponReconcileJob 失效 | 券账漂移不修（低）| ❌ |
| **购物车** | P-D20 cartReconcileJob 失效 | Redis 故障时购物车无法从 MySQL 恢复（叠加 P2-7）→ **用户购物车可能丢失** | ❌ 无 |
| **Feed 流** | feedCleanupJob 失效 | feed Redis 数据不清理 → 膨胀（读取变慢，低频）| ❌ |
| **推荐** | recommend×3 失效 | 推荐索引/热池/协同不更新 → 推荐页固定无个性化（功能降级）| ❌ |
| **搜索** | 无（ES 索引链路正常）| 搜索功能正常 | ✅ |
| **通知/社交/计数** | 无（unreadReconcile/followCounterRepair/counterReconcile 有效）| 正常 | ✅ |
| **登录/资金安全** | P-D1 Nacos 无鉴权（含 JWT/HMAC secret 明文）| 内网可读/改配置 → 可改 DB 密码/限流/白名单；签名密钥泄露=HMAC 防篡改体系可被伪造 | ❌ |
| **用户数据安全** | P-D19 无备份 | 订单/用户/券数据**不可恢复** | ❌ |
| **业务监控** | P-D4 告警失效+P-D13 无出口 | 下单失败率/支付成功率/MQ 积压/DLQ 异常**无人知**（8/9 风暴 3 天无人发现就是后果）| ❌ |
| **异步问题排查** | P-T1 SW 异步断链 | 关单/补偿/缓存刷新/聚合的异步段在 SW 断链 → 异步故障难定位 | ⚠️ |

### 业务优先级结论（结合业务影响重排）
1. **P-D20 关单/本地消息/对账任务恢复**（资金+库存，order 无兜底最痛）
2. **P-D22 库存补偿表修复**（库存准确性）
3. **P-D19 备份**（数据安全）
4. **P-D1 Nacos 鉴权 + 密钥迁移**（资金/安全）
5. **P-D4/P-D13 告警修复**（业务异常可见性）
6. 其余（购物车对账/推荐/feed/日志清理等）按资源窗口

> 说明：支付链路、搜索、通知/社交/计数链路当前**配置健全**；核心资金痛点集中在"关单兜底失效"与"库存补偿失效"。

---

## 十三、业务级走查（2026-08-12，第八轮·结合业务深挖）

### P-B1【高·业务】生产"支付宝"支付 4/5 笔卡死（支付闭环依赖 5 分钟模拟器窗口）
- **实证**（生产库）：
  - 支付单 pay_type 分布：99(mock)=4、1(支付宝)=5；状态分布：待支付=4、已支付=4、已退款=1。
  - **pay_type=1 的 5 笔中 4 笔 status=0(待支付)、paid_at=NULL 永久卡死**（PAY20260810000001~3、PAY20260811000001）；仅 1 笔(8/11 13:22)闭环成功。
  - 卡死单对应订单 3 个已取消(status=4)、1 个已付款(status=1)——**僵尸支付单挂在已取消订单下**。
- **根因**（代码级）：
  - `PaymentService.pay` 非 mock 模式 → `callbackSimulator.registerCallback(paymentNo, payType)` → Redis pending key **TTL 仅 5 分钟**（PayCallbackSimulator:set key 5min）+ 模拟器 @Scheduled 5s 扫描一次、**错过即永久 pending（无重试/补偿）**。
  - 8/9-8/10 Redisson/Redis 异常期间创建的支付单 → 5 分钟窗口内模拟器未闭环 → key 过期 → 永不再回调。
  - 真实支付宝渠道语义（回调分钟级~小时级异步）与"5 分钟模拟窗口"不匹配——**生产实际是"模拟器支付"而非真实渠道闭环**（pay.type=remote 但渠道走模拟器）。
- **影响**：支付单僵尸积累；若真实渠道收到钱但回调被模拟器漏掉/被 403 拒（P2-5）→ 用户付钱订单不变（钱货两空风险）；已取消订单的待支付单永不结算。
- **修复**：
  1. pending key 去掉 5 分钟 TTL（改 24h 或永不过期，模拟器处理成功后删除）；
  2. 模拟器处理失败/订单已取消 → 主动标记支付单失败或触发退款（对齐 P1-1）；
  3. 对存量 4 笔僵尸单清理（置失败/退款）；
  4. 真实渠道接入前明确"模拟器支付"定位（避免误以为支付宝可用）。
- **验证**：新建支付宝支付单 → 模拟器闭环成功；人为删 pending key → 补偿任务能兜底标记。

### P-B2【中·业务】已取消订单下残留僵尸支付单（脏数据）
- 实证：3 笔 status=0 支付单挂在 status=4(已取消) 订单下；当前无对账清理（PaymentService.reconcile 只对账已支付单，不对待支付僵尸单）。
- **修复**：对账任务增加"订单已取消但支付单待支付 → 置失败"规则。
- **验证**：僵尸单被清理。

### P-B3【低·业务】业务数据量现状（容量佐证）
- t_order 分片累计 ~120 单（已取消 78/已付款 20/已完成 16/已退款 9）、t_user 78、t_coupon 22、t_follow 1、t_chat_message 13——**演示/测试规模**；连接池(280)/日志量/告警阈值等容量问题在真实流量下才会暴露，但**僵尸/漂移趋势已现**（支付卡单、库存补偿失效）。

### 结合业务的修复优先级（最终版）
1. **P-B1/P-D20 支付卡单清理 + 关单兜底恢复**（资金：钱货两空/库存占用）
2. **P-D22 库存补偿表修复**（库存准确性）
3. **P-D19 备份**（数据安全）
4. **P-D1 Nacos 鉴权**（资金/签名安全）
5. **P-D4/P-D13 告警**（业务异常可见）
6. **P-B2 僵尸单对账**、P-D24 日志清理、其余治理
