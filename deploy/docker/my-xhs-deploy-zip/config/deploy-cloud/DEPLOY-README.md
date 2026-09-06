# 云主机部署说明（DEPLOY-README）

> 2026-08-12 | 本项目即部署源：上传仓库到云主机后直接部署中间件（25 容器）。
> 微服务（15 个）不在本仓库容器化范围内（后续跑 Windows/另行部署），本说明只覆盖中间件。
> compose 已按运行版基线更新 + `restart: always`（按量计费关机开机自动恢复）。

## 一、上传内容（本项目内，均已就绪 ✅）

| 路径 | 说明 | 状态 |
|---|---|---|
| **`docker-compose.yml`（zip 根目录）** | 25 容器编排（**restart: always**，运行版基线）——已随 `my-xhs-deploy-package.zip` 打包，解压即用 | ✅ |
| `config/canal/` `config/redis/` `config/rocketmq/` `config/prometheus/` `config/grafana/` `config/skywalking/` | 中间件原生配置（与中间件机运行版一致）| ✅ |
| `config/mysql-connector.jar` | Nacos 挂载用 MySQL 驱动 | ✅ |
| `config/nacos/*.yaml` | **Nacos my-xhs 命名空间 3 个配置（my-xhs-common/gateway/redis.yaml，部署后导入）** | ✅ |
| `sql/init-all.sql` `sql/init-replication.sql` | MySQL 初始化 + 主从复制 | ✅ |
| `start-all.sh` | 部署/运维脚本（微服务侧，暂不用）| — |

## 二、云主机前置准备

1. **Docker + Compose**：`curl -fsSL https://get.docker.com | sh`；`systemctl enable docker`
2. **JDK**：当前 compose 仅要求 **Canal** 挂载宿主机 JDK 8，约定路径 `/opt/openjdk8`：
   ```bash
   ls /opt/openjdk8   # 必须存在，否则 Canal 起不来
   ```
3. **Canal 官方镜像**：当前使用 `canal/canal-server:v1.1.7`，无需再准备自定义 squashed 镜像。
4. **EIP（弹性公网 IP）**：**必须**（见第四节）。绑定到云主机，确保关机重启后公网 IP 不变。
5. 内存建议 ≥24GB（容器峰值 ~9GB + 预留）；磁盘 ≥100GB（日志/ES/MySQL）。

## 三、部署步骤

```bash
# 1. 上传 my-xhs-deploy-package.zip 到部署机，解压并进入部署根目录（docker-compose.yml 所在目录）
#    ⚠️ compose 相对路径 ./config ./sql 均相对本目录：
#    试验机: mkdir -p /data/workspace/my-xhs-deploy-zip && unzip -o my-xhs-deploy-package.zip -d /data/workspace/my-xhs-deploy-zip
#    云主机: unzip -o my-xhs-deploy-package.zip -d /opt/my-xhs-deploy
cd /data/workspace/my-xhs-deploy-zip

# 2. 可选：按部署机实际 IP 调整（若未用 EIP，需改以下硬编码）
grep -rn "21.130.247.89\|21.214.97.212" docker-compose.yml config/ | grep -v "\.bak"

# 3. 启动全部中间件（25 容器）
docker compose up -d

# 4. 等待健康（MySQL 首次初始化 ~1-2min，ES ~1min）
docker compose ps          # 应全部 Up (healthy)

# 5. 验证
docker ps | wc -l          # 22
curl -s -u elastic:'Xhs@2026#Elastic' http://127.0.0.1:19200/_cluster/health
redis-cli -p 6379 -a 'Xhs@2026#Redis' ping
curl -s http://127.0.0.1:18848/nacos/v1/ns/namespace/list
```

## 四、按量计费：EIP 与关机行为

- **为什么必须 EIP**：按量计费云主机（尤其"关机不收费"模式）**关机后公网 IP 会被释放**，开机重新分配随机 IP → 配置里硬编码的 `21.130.247.89`（Nacos NACOS_SERVER_IP/sentinel monitor/brokerIP1/canal master/微服务连接串）全部失效。
- **做法**：控制台购买 EIP → 绑定到云主机 → 公网 IP 恒定，配置无需改。
- **费用提醒**：EIP 绑定中、云主机停机时，EIP 可能仍计带宽/闲置费（腾讯云：EIP 闲置计费，绑定且主机停机可能按带宽计费）——按需评估；若无法接受 EIP 费用，可改用"普通关机（保留资源）"（IP 保留，但关机仍计主机费）。
- **开机自动恢复**：`restart: always` 已配 → 开机后 docker daemon 拉起全部 25 容器；无需人工干预（前提 `systemctl enable docker`）。

## 五、已知注意点（部署时对照）

1. **从库不执行 init-all.sql**（已修正 compose）：从库仅挂 `init-replication.sql`（CHANGE MASTER + START SLAVE），**建库建表/初始化数据全部由主库 binlog 通过 GTID 同步**——若从库误跑 init-all 会与复制 GTID 冲突（错误 1236/重复执行）。部署后验证：`SHOW REPLICA STATUS` 确认 IO/SQL Running=Yes、Seconds_Behind=0。
2. **Redis announce-ip**：compose 已配 `--replica-announce-ip 21.130.247.89`（8/11 修复，勿回退）——若云主机 IP 不同且未用 EIP，需同步改。
3. **Nacos**：默认 `nacos/nacos` + **无鉴权**（P-D1 待办）——部署后建议立即开启鉴权或限制安全组。
4. **中间件密码**：全部 `Xhs@2026#*` 明文（P-D5 待办）——生产建议随机化。
5. **SkyWalking 版本**：agent 9.6.0 vs OAP 9.7.0 不匹配（P-T2）——若后续微服务接 SW，需对齐。
6. **Sentinel Dashboard 默认口令 sentinel/sentinel（P-D37）**：部署后需改——挂载自定义 application.properties（改 auth.username/auth.password）或启动参数 `--auth.username=<新> --auth.password=<新>`；当前仅靠 iptables 白名单兜底。
7. **日志链路（2026-08-12 已实证）**：微服务 logback 已内置 **LogstashTcpSocketAppender 直推 21.130.247.89:15044**（logback-spring.xml），跨机部署（Windows/云主机）天然支持，无需改造；filebeat（15045）为冗余通道不参与微服务日志。
8. **my_xhs_search 库已清理（P-D36，2026-08-12）**：search 模块数据源实为 my_xhs_content（快照表在 content 库）；废弃的 search 库已从生产 DROP 并从 init-all.sql 删除，无需再处理。

## 五·附、网络与安全（云主机无公司白名单限制，但公网暴露面更大）

- **背景**：原中间件机(21.130.247.89)为公司机，iptables 白名单(MYXHS 链)是公司风险检查的合规要求；**云主机无此限制**——但云主机为公网 IP，所有端口默认可达公网，防护不可省略。
- **建议两层防护**：
  1. **腾讯云安全组（控制台，首选）**：只放行必要端口（SSH 22、微服务接入端口如 19000）给**指定来源 IP**（Ubuntu VM 的公网 IP + 运维 IP），其余全部拒绝。
  2. **主机 iptables（可选纵深）**：项目根 `setup-firewall.sh`（2026-08-12 已对齐当前端口、参数化 ALLOW_IP、含持久化说明）——云主机 root 可执行。
- **必做底线**：Nacos(18848)/Redis(6379)/MySQL(3306) **不允许公网直连**——即使暂不装 Nacos 鉴权（P-D1），安全组也必须把这三个端口限制到微服务 VM IP。

## 六、部署包增强（2026-08-12 已并入 compose）

1. **healthcheck 全覆盖**：25 个容器全部有 healthcheck（新增 nacos/rocketmq-namesrv/xxl-job-admin/prometheus/kibana/grafana/filebeat/victoria-metrics/sentinel-dashboard/skywalking-ui）。
2. **依赖时序**：关键 `depends_on` 加 `condition: service_healthy`（mysql-slave→mysql、canal→mysql、nacos→mysql、xxl-job→mysql、broker/dashboard→namesrv、logstash/kibana→es、grafana→prometheus、filebeat→logstash）——首次部署并行启动时按序就绪。
3. **Sentinel 限流规则**：`config/sentinel/*.json`（16 服务 flow/degrade 规则）**compose 不自动加载**——部署后需在 Sentinel Dashboard(http://IP:8858) 手动导入，否则限流不生效。gateway 的限流另受 Nacos 数据源/本地 metadata 影响（见应用层说明）。

## 七、从零部署初始化补充（2026-08-12）

1. **nacos_config/xxl_job 表已补全**：原 init-all.sql 只建库未建表（全新部署 Nacos/xxl-job 连空库会失败）；已把 xxl_job 8 表（含 schedule_lock/admin 初始数据）+ nacos 12 表并入 `sql/init-all.sql` 末尾，**临时库实测通过（20 表）**。
2. **Nacos 配置需导入**：从零部署后 Nacos 的 `my-xhs` 命名空间配置为空——**已固化在 `config/nacos/` 下（my-xhs-common.yaml / my-xhs-gateway.yaml / my-xhs-redis.yaml）**，部署后导入即可（控制台或 `curl -X POST http://127.0.0.1:18848/nacos/v1/cs/configs -d 'dataId=my-xhs-common.yaml&group=DEFAULT_GROUP&tenant=my-xhs&content=...'`），否则微服务用本地 yml 默认值（含 DB/Redis 密码一致，但 redis 端口/sentinel 等以 Nacos 为准）。**若云主机 IP 与 21.130.247.89 不同，先改 my-xhs-redis.yaml 的 host 再导入。**
3. **ES IK 插件需外网**：ES 首次启动从 `get.infini.cloud` 下载 IK 插件——云主机需能访问该域名，否则 ES 启动卡死。
4. **docker 镜像加速**：云主机 docker 配置镜像加速（国内拉 elasticsearch/kibana 8.x 大镜像）：`/etc/docker/daemon.json` 配 `registry-mirrors`（腾讯云/阿里云加速），然后 `systemctl restart docker`。
5. **rocketmq-dashboard:latest**：compose 用 latest 标签（不可复现）——建议 `docker tag` 固定当前版本或改用具体 tag。

## 九、部署后脚本清单（2026-08-12 补包，P-D30 后统一）

> compose 已并入以下修复（部署即生效）：**P-D3 Redis noeviction+512m、P-D15 从库/logstash 内存上调、P-D16 Kibana encryptionKey、P-D26 从库 relay-log 固化、P-D8 OAP 真实健康探测、P-T4 trace 10% 采样、时区 TZ×23 统一、P-D13 Alertmanager 容器、P-D6 Prometheus remote_write、P-T3 OAP 采集、P-D4 告警规则指标名对齐+无效规则隔离**。

| 脚本/文件 | 用途 | 执行时机 |
|---|---|---|
| `config/deploy-cloud/apply-ilm.sh` | **P-D14** ES 日志索引 ILM（30d 删除）| 部署成功后执行一次 |
| `config/deploy-cloud/mysql-backup.sh` | **P-D19** 每日 mysqldump 备份（cron 挂）| 部署后配置 crontab |
| `config/deploy-cloud/init-xxljob.sql` | **P-D20** xxl-job 补建 5 组 + 修正任务组 + 补建 6 任务 | 部署后执行一次（执行器注册后） |
| `config/deploy-cloud/ops-fixes.sh` | **P-D7** Kibana 密码 / **P-D22** 补偿表 / **P-D10** 清理 | 按需 |
| `config/deploy-cloud/remote-upgrade.sh` | 试验部署（restart 应用 + 一致性校验）| 远程机改造时 |

> **P-D1 Nacos 鉴权 / P-D5 密码随机化**（第二批安全）需微服务联动，**未**纳入本包——部署时至少收紧安全组 + Nacos 控制台改默认密码。

## 八、试验验证清单

完整版见 docs/test-2/HANDOFF-TASK4.md §七（A 前置/B 启动/C 中间件验证/D 重启与关机演练/E 部署后配置/F 判定）。核心：25 容器全 healthy + restart=always + 关机开机自动恢复 + 从库复制 Yes + Nacos 配置与 Sentinel 规则导入。
