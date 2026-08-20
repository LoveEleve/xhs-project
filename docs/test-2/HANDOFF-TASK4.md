# my-xhs 交接文档 — Task 4（生产配置深挖 + 云部署改造）

> 2026-08-12 | 承接 Task3（fresh review + P0/P1 修复闭环）→ 本阶段：生产配置 7 轮深挖 + 云部署包改造。
> 给下一个 AI 的全量交接。**重点：待修复清单（§二）与部署包状态（§四）。**

---

## 零、当前状态速览

```
15 微服务 UP（本机 21.214.97.212 开发容器内） | 中间件 25 容器（远程机 21.130.247.89，无 docker/ssh 权限）
应用层 P0×4+P1×5+O1+O2 已修复闭环 | 生产配置发现 34 项（P-D1~D29+P-T1~T5+P-B1~B3）未修复
云部署包已改造（config/ 下，上传即用，含 restart:always/healthcheck/建表补全）
【2026-08-12 补包】P-D30 部署包一致性+P-D31 Nacos配置固化+P-B4 令牌fail-closed+FIX-PLAN零代码修复全量落位（详见 review-production-config.md §十四/§十五）
【用户最新指示】部署优先级最高：先在远程服务器试验成功 → 后上传云主机。不代为部署，由用户上传。
Token→/tmp/test_token.txt | 凭据: Xhs@2026#* / ADMIN_TOKEN / INTERNAL_TOKEN（已随机化，见 .secrets/tokens.env）
```

### 环境拓扑（重要）
- **微服务机 = 21.214.97.212（本机，Docker 容器内，无 systemd/docker 权限）**——微服务是我临时拉起的进程，**后续微服务将跑用户 Windows，不在本机**。
- **中间件机 = 21.130.247.89（22 docker 容器）**——仅可远程 curl/mysql/redis-cli，**无 docker/ssh 权限**（容器侧改动需给用户脚本）。
- 微服务→中间件走 iptables 白名单（中间件机 MYXHS 链：本机+21.214.97.212 放行，其余 DROP）。

---

## 一、本阶段已完成

### 1.1 应用层修复闭环（Task3 延续，均已实测验证，pitfalls #53~#67）
| 项 | 内容 | 状态 |
|---|---|---|
| P0-A | 优惠券下单核销（资损）| ✅ |
| P0-B | IM 会话 ID 碰撞（串台）| ✅ |
| P0-C | Feed 收件箱参数颠倒 | ✅ |
| P1-2 | 补偿消费者忽略 action（库存泄漏）| ✅ |
| P1-1 | 支付前回查+竞态自动退款 | ✅ |
| P1-4/5 | ES 版本域统一+跨库前缀 | ✅ |
| P1-3 | 端口信任模型（GatewayAuthTrustFilter+全服务 jwt.secret）| ✅ |
| O1/O2 | MDC userId / 异步线程池 traceId | ✅ |
| P2-1/2/9/13/14 | product N+1/下架过滤/登录DoS/死缓存键/聚合窗口注释 | ✅ |

### 1.2 生产配置深挖（7 轮，review-production-config.md）
- 快照 `config/production-env-config/docker-container-review-20260811-162334/`（933 文件）→ 已系统过 7 轮。
- **修正 2 个误判**：M-1 iptables 实为收紧（P-D1 暴露面下调）、M-2/P-D8 OAP healthcheck 实际通过（撤销）。

### 1.3 云部署包改造（config/ 下，已提交）
- `config/docker-compose.yml`：运行版基线 + **restart:always×22** + **22 healthcheck** + **关键 depends_on 加 condition:service_healthy** + **从库移除 init-all.sql（纯复制）**。
- 配置对齐运行版：canal flatMessage=true+admin、prometheus timeout 4s、skywalking 补齐（174 文件）、mysql-connector.jar 强制入库。
- `sql/init-all.sql` 补全 **nacos/xxl_job 建表（20 表+初始数据，临时库实测通过）**。
- 部署说明：`config/deploy-cloud/DEPLOY-README.md`。

---

## 二、待修复问题清单（核心交接内容）

### 2.1 生产配置高危（P-D 系列，均已在 FIX-PLAN-PRODUCTION-CONFIG.md 有修复方案）

| # | 问题 | 业务影响 | 修复要点 |
|:--:|------|------|------|
| **P-D20** | xxl-job 调度错配：19 任务仅 ~8 可调度（orderClose/localMessage/deadLetter/mapping/inventoryReconcile/couponReconcile 挂 sample 组 NULL 地址；couponExpire/cartReconcile/feedCleanup/recommend×3 未建任务）| 超时关单无兜底（order 无 @Scheduled）→ 库存/券被无效订单占用；库存/券对账失效 | xxl-job-admin 建 order/coupon/cart/home/search 组 + 修正 job_group + 补建任务；执行器已全部在线（registry 10 个） |
| **P-D22** | t_inventory_compensation schema 漂移：生产表缺 retry_count/fail_reason、多 action | **库存回滚补偿全失效**（写/读/重试 SQL 错，inventory 每天报错）| ALTER TABLE 补列删列（或按 init-all.sql 重建）|
| **P-D1** | Nacos 无鉴权（NACOS_AUTH_TOKEN/IDENTITY 空）+ 配置明文（MySQL/Redis 密码、JWT/HMAC secret）| 内网可读改配置 → 凭据/签名体系泄露 | 开鉴权+改密+密钥环境变量化；需全服务联动 |
| **P-D19** | MySQL 无任何备份 | 数据不可恢复 | 每日 mysqldump+云盘快照 |
| **P-D13** | Prometheus 无 Alertmanager | 告警无出口 | 部署 alertmanager+通知渠道 |
| **P-D4** | 告警规则引用不存在指标（myxhs_order_create_total 等 9 条）+ 无中间件 exporter | 业务/中间件告警失效 | 改规则名+补 exporter（redis/es/canal/rocketmq）|
| **P-D2** | 从库宕机+复制中断（fatal 1236）+ relay-log 未固化（P-D26）| 读写分离失效；从库数据陈旧 | 重建从库（mysqldump+CHANGE REPLICATION，勿跑 init-all）+ relay-log 参数 |
| **P-D3** | Redis maxmemory 256mb+allkeys-lru | 可驱逐购物车/库存锁/预扣（超卖）| noeviction+上调 |
| **P-D14** | ES 日志索引无 ILM（每天 1GB+ 无限增长）| 磁盘打满 | ILM 30d delete |
| **P-D15** | mysql-slave 内存 97.5%/logstash 94% 濒危（无 Swap）| OOM kill（可能就是从库宕机元凶）| 上调内存限制 |
| **P-D28** | 微服务机 iptables 空 + actuator loggers 可远程改级别（实测 204）| 端口裸露/日志可篡改 | iptables 白名单+关 loggers |
| **P-B1** | 生产支付宝支付 4/5 卡死（模拟器 5 分钟窗口无重试）| 支付僵尸单/钱货两空风险 | pending key 去 TTL+补偿+清理存量 |
| **P-D5** | 密码全 Xhs@2026#* 明文入库（含 Canal admin=MD5(admin)）| 凭据可猜 | 随机化+env 注入 |

### 2.2 生产配置中危（P-D6/7/9/10/11/12/16/17/18/21/24/27/29 + M-2 建议）
- P-D6 VM 空转（Prometheus 无 remote_write）| P-D7 Kibana 凭据 security_exception | P-D9 ES 单节点 yellow | P-D10 sca-lab-dev 命名空间/库污染 | P-D11 RocketMQ autoCreateTopic+ASYNC_FLUSH | P-D12 Prometheus 双 IP | P-D16 Kibana 随机 encryptionKey | P-D17 残留 topic/镜像 | P-D18 404 序列多 | P-D21 Redis HA 名义化（单 sentinel）| P-D24 /logs 14GB 无清理 | P-D27 Kibana monitoring 指向不可解析地址 | P-D29 MySQL 慢查询 11 次 | M-2 OAP healthcheck 改真实探测（建议）

### 2.3 SkyWalking 全链路（P-T 系列，详见 review-production-config.md §六）
| # | 问题 | 修复 |
|:--:|------|------|
| P-T1 | **agent 线程池/ForkJoin 插件在 bootstrap-plugins 未启用 → 异步链路 sw8 断链** | 移 2 个 jar 到 plugins + 重启微服务 |
| P-T2 | agent 9.6.0 vs OAP 9.7.0 版本不匹配 | agent 升 9.7 |
| P-T3 | OAP telemetry(1234) 未监听+未接入 Prometheus | 排查配置+加 job |
| P-T4 | trace 全采样 100%（179 万 segment/天）| SW_TRACE_SAMPLE_RATE |
| P-T5 | 11800 gRPC 端口杂散 HTTP 请求（9 次 Http2Exception）| 排查来源 |

### 2.4 应用层剩余 P2（review-consolidated.md §四，fresh review 发现未修复）
- P2-3 home Feed 下游放大（无批量接口无缓存）| P2-4 payment keys() 全扫+永久 key | P2-5 第三方回调 X-Internal-Call+无验签（与 P-B1 关联）| P2-6 counter key 无 TTL | P2-7/8 cart Redis 恢复注释不符/对账枚举源 | P2-10/11 gateway 压测标记 XFF 伪造/HMAC secret 无 try-catch | P2-12 order pseudoOrderId 碰撞 | P2-15 user @Transactional 长占连接

### 2.5 修复优先级（用户最新指示调整：部署第一）
**第一批【部署优先】（先把部署跑通，用户自行上传执行）**
1. 远程中间件机试验部署成功（用户上传 config/ 部署包并执行）：
   - 前置：JDK17/8、canal 镜像 docker load、systemctl enable docker、镜像加速、EIP 决策
   - 执行：docker compose up -d（restart:always + healthcheck 已内置）
   - 部署后必做：Nacos 导入 my-xhs 命名空间 3 配置、Sentinel Dashboard 导入 config/sentinel/*.json（16 服务）、从库验证（SHOW REPLICA STATUS）
   - 工具：config/deploy-cloud/remote-upgrade.sh（用户在中间件机执行，含 restart 应用/docker enable/验证）
2. 试验通过后 → 上传云主机部署（config/deploy-cloud/DEPLOY-README.md 全流程）
**第二批【部署成功后业务修复】**
3. **P-B1 支付卡单 + P-D20 关单/对账任务恢复**（资金）→ **P-D22 库存补偿表修复**（库存）
4. **P-D19 备份** → **P-D1 Nacos 鉴权** → **P-D4/P-D13 告警**
**第三批**
5. P-T1/P-T2（SW 全链路闭环）→ P2 应用层 → P-D2~D18 其余（从库重建等与部署相关项可并入第一批）

---

## 三、历史文档记录（均有，勿重复挖）

| 文档 | 内容 |
|------|------|
| docs/test-2/HANDOFF-TASK3.md | Task3 交接（含文档地图）|
| docs/test-2/HANDOFF-NEW-AI.md | 代码审查方法论（5 维度/缺陷模式）|
| docs/test-2/REVIEW-V2.md + FIX-PLAN-V2-FULL.md | 早前 review + P0-1~P0-8 修复方案（部分已被本阶段修复）|
| docs/test-2/review-fresh/review-consolidated.md | **fresh review 汇总（P0/P1/P2 + O1/O2 + 修复进度表）** |
| docs/test-2/review-fresh/review-<module>.md | 15 模块逐篇 review |
| **docs/test-2/review-fresh/review-production-config.md** | **生产配置 7 轮深挖（P-D1~D29 + P-T1~T5 + P-B1~B3 + 业务矩阵 + 修正记录）** |
| **docs/test-2/FIX-PLAN-PRODUCTION-CONFIG.md** | **生产配置修复方案（分三批，含命令）** |
| docs/test-2/execution/pitfalls.md | 踩坑记录 #53~#67（本阶段修复验证）|
| **config/deploy-cloud/DEPLOY-README.md** | **云部署说明（上传清单/前置/EIP/部署步骤）** |
| config/production-env-config/ | 生产快照（933 文件，深挖素材）|

---

## 四、云部署包状态（下一个 AI 注意）

- **部署源已就绪**：`config/`（compose+全套配置+sql+nacos 固化配置）+ `config/deploy-cloud/DEPLOY-README.md`。
- **【2026-08-12 补包】**：deploy-cloud/docker-compose.yml 已与运行版基线同步（P-D30）；FIX-PLAN 零代码修复已全量落位（P-D3/15/16/26/8/T4/时区/D13/D6/T3/D4 入 compose/prometheus，P-D14/19/20/7/22/10/2 转运维脚本）；Nacos 3 配置固化 config/nacos/（P-D31）。**待办：P-D1/P-D5 第二批未入包（需微服务联动），部署时至少改 Nacos 默认密码+收紧安全组。**
- **微服务侧已改未重启**：P-B4（INTERNAL_TOKEN fail-closed+随机化）、P-D32（sql-show:false）——需重打包重启 12+ 服务，**重启窗口与远程部署协同，等用户指示**。
- **云主机前置**：JDK17/8（/opt/kona-jdk17、/opt/kona-jdk8）、canal 自定义镜像 docker load、**EIP（必须，按量计费关机 IP 会变）**、docker 镜像加速、ES IK 外网、systemctl enable docker。
- **部署后需做**：Nacos 导入 my-xhs 命名空间 3 个配置（common/gateway/redis）、Sentinel Dashboard 手动导入 config/sentinel/*.json 规则（16 服务，compose 不自动加载）、从库 SHOW REPLICA STATUS 验证。
- 本机（开发容器）无 docker/中间件权限——**中间件侧改动只能给用户脚本，无法直接执行**。

---

## 五、执行规范（沿用）

- 前端只走 gateway(19000)；写操作 HMAC；内部端点 X-Internal-Call；服务端口已加 GatewayAuthTrustFilter（JWT 覆盖/剥离 X-User-Id，需带 JWT 或 X-Internal-Call）。
- 一 curl 一文件，L0→L4 逐层验证；改码后 `mvn package -pl {module} -am -Dmaven.test.skip=true`（**勿 clean，防删运行 jar**；target 缓存陈旧时 rm -rf target 重建）。
- 重启单服务：kill 旧进程（注意历史遗留旧 PID 占端口，需 pgrep 精确匹配）→ setsid java ... 启动 → curl /actuator/health 等待。
- 修复后更新 pitfalls.md + review-consolidated.md 修复进度表。
- Redis 操作需密码 `Xhs@2026#Redis`（redis-py 可用，redis-cli 本机无）。

---

## 六、给下一个 AI 的要求

1. **第一优先是部署（用户明确指示）**：协助用户把中间件部署先在远程机试验成功（提供/核对 config/deploy-cloud 部署包、remote-upgrade.sh、DEPLOY-README.md 的前置与验证），试验通过后作为云主机上传源。**不要代为部署（无 docker/ssh 权限），只给脚本与验证支持**。
2. **部署跑通后再按 §2.5 第二批起修复**（改码→重打包→重启→验证→记录闭环）；先读 FIX-PLAN-PRODUCTION-CONFIG.md。
2. 继续深挖生产快照时，**先读 review-production-config.md 避免重复**（已 7 轮）；新发现请追加 §八+，勿覆盖已有结论，修正旧结论时标注。
3. 中间件侧（docker 容器）只能给用户可执行脚本（本机无 docker/ssh 权限）。
4. 云部署包（config/deploy-cloud/）任何改动需保持"上传即用"一致性（compose↔配置↔sql↔说明）。
5. 业务数据实证优先：任何"配置问题"尽量落到生产库数据/日志证据（如 P-B1 支付卡单方式）。

---

## 七、远程试验部署验证清单（部署成功判定标准）

> 用户在中间件机执行部署（config/ 部署包 + remote-upgrade.sh），按此清单逐项验证；**全部通过 = 试验成功**，方可作为云主机部署源。

### A. 前置（部署前）
- [ ] `/opt/kona-jdk17`、`/opt/kona-jdk8` 存在（compose 挂载）
- [ ] canal 镜像已 load：`docker images | grep canal` → `my-xhs-canal-server:v1.1.7-squashed`
- [ ] `systemctl enable docker` 后 `systemctl is-enabled docker` = enabled
- [ ] docker 镜像加速已配（拉 ES/Kibana 大镜像不超时）
- [ ] `docker compose version` 可用（v2）

### B. 启动（docker compose up -d）
- [ ] 无 compose 语法错误（YAML 已校验）
- [ ] `docker ps | wc -l` = 22
- [ ] 等待 3-5 分钟后 `docker compose ps`：**22 个全部 healthy**（healthcheck 全覆盖，无 unhealthy）

### C. 关键中间件功能验证（预期值）
| 验证项 | 命令 | 预期 |
|---|---|---|
| MySQL 主库 | `mysql -h127.0.0.1 -P3306 -uroot -p'Xhs@2026#MySQL' -e "SELECT 1"` | 1 |
| 从库复制 | `mysql -h127.0.0.1 -P3307 ... -e "SHOW REPLICA STATUS\G"` | IO/SQL Running=Yes、Seconds_Behind=0 |
| 建表补全 | `SHOW TABLES FROM xxl_job` / `nacos_config` | xxl 8 表、nacos 12 表存在 |
| Redis | `redis-cli -p 6379 -a 'Xhs@2026#Redis' ping` | PONG |
| Redis Sentinel | `redis-cli -p 26379 sentinel get-master-addr-by-name mymaster` | 21.130.247.89 6379 |
| ES 业务 | `curl -s -u elastic:'Xhs@2026#Elastic' 127.0.0.1:19200/_cluster/health` | status=yellow 可接受（单节点副本） |
| ES-SW | 同 19201（elastic/ElasticSW）| status=yellow/green |
| Nacos | `curl -s 127.0.0.1:18848/nacos/v1/ns/namespace/list` | 200 且含 my-xhs 命名空间 |
| RocketMQ | `curl -s "127.0.0.1:18081/..."`（dashboard）| 200；namesrv 9876 TCP 通 |
| Canal | `docker exec my-xhs-canal pgrep -f CanalLauncher` | 有进程；日志无致命错误 |
| SkyWalking | `curl -s 127.0.0.1:12800/graphql`（POST）| 非连接拒绝；UI 8080 可开 |
| XXL-Job | `curl -s 127.0.0.1:18080/xxl-job-admin/login` | 200 |
| Sentinel | `curl -s 127.0.0.1:8858/` | 200 |
| Prometheus | `curl -s 127.0.0.1:19090/-/healthy` | Prometheus is Healthy |
| Kibana | `curl -s 127.0.0.1:15601/api/status` | 200（若 000 则按 P-D7 修 kibana_system 密码）|

### D. 重启策略与开机自恢复（试验核心）
- [ ] `docker inspect <容器> --format '{{.HostConfig.RestartPolicy.Name}}'` → 全部 always
- [ ] `docker restart <任一容器>` → 自动恢复（restart 策略生效）
- [ ] **关机→开机演练**（最终判定）：正常关机（ACPI）→ 开机 → 等 3-5 分钟 → `docker ps` 22 个 Up、`docker compose ps` 全 healthy、从库复制 Yes
- [ ] 若 IP 未绑 EIP 且变了：按 DEPLOY-README 第四节处理

### E. 部署后业务配置（试验成功前必做）
- [ ] Nacos 导入 my-xhs 命名空间 3 配置：my-xhs-common.yaml / my-xhs-gateway.yaml / my-xhs-redis.yaml（内容见 review-production-config.md §Nacos 或现环境导出）
- [ ] Sentinel Dashboard 导入 config/sentinel/*.json（16 服务 flow/degrade 规则，compose 不自动加载）
- [ ] 从库确认复制 Yes（若否：重建从库，勿跑 init-all.sql）

### F. 判定
- [ ] B/C/D/E 全部通过 → **试验成功**，config/ 即云主机部署源
- [ ] 任一关键项失败 → 记录原因到 pitfalls.md，修复后复测
