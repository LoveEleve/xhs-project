# 云主机部署说明（DEPLOY-README）

> 2026-08-12 | 本项目即部署源：上传仓库到云主机后直接部署中间件（22 容器）。
> 微服务（15 个）不在本仓库容器化范围内（后续跑 Windows/另行部署），本说明只覆盖中间件。
> compose 已按运行版基线更新 + `restart: always`（按量计费关机开机自动恢复）。

## 一、上传内容（本项目内，均已就绪 ✅）

| 路径 | 说明 | 状态 |
|---|---|---|
| `config/docker-compose.yml` | 22 容器编排（**restart: always**，运行版基线）| ✅ |
| `config/canal/` `config/redis/` `config/rocketmq/` `config/prometheus/` `config/grafana/` `config/skywalking/` | 中间件原生配置（与中间件机运行版一致）| ✅ |
| `config/mysql-connector.jar` | Nacos 挂载用 MySQL 驱动 | ✅ |
| `sql/init-all.sql` `sql/init-replication.sql` | MySQL 初始化 + 主从复制 | ✅ |
| `start-all.sh` | 部署/运维脚本（微服务侧，暂不用）| — |

## 二、云主机前置准备

1. **Docker + Compose**：`curl -fsSL https://get.docker.com | sh`；`systemctl enable docker`
2. **JDK**：compose 挂载 `/opt/kona-jdk17` 与 `/opt/kona-jdk8`（RocketMQ/Nacos/Canal 用）——从本机或腾讯云镜像安装到 `/opt/`：
   ```bash
   ls /opt/kona-jdk17 /opt/kona-jdk8   # 必须存在，否则容器起不来
   ```
3. **Canal 自定义镜像**（不在公共仓库）：中间件机导出后随包上传：
   ```bash
   docker save my-xhs-canal-server:v1.1.7-squashed -o canal-image.tar
   # 云主机：
   docker load -i canal-image.tar
   ```
4. **EIP（弹性公网 IP）**：**必须**（见第四节）。绑定到云主机，确保关机重启后公网 IP 不变。
5. 内存建议 ≥24GB（容器峰值 ~9GB + 预留）；磁盘 ≥100GB（日志/ES/MySQL）。

## 三、部署步骤

```bash
# 1. 上传仓库到云主机（scp/git clone），进入部署根目录（compose 所在目录）
cd my-xhs/config          # compose 在 config/ 下，相对路径 ./sql ./config 均相对本目录

# 2. 可选：按云主机实际 IP 调整（若未用 EIP，需改以下硬编码）
grep -rn "21.130.247.89\|21.214.97.212" docker-compose.yml config/ | grep -v "\.bak"

# 3. 启动全部中间件
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
- **开机自动恢复**：`restart: always` 已配 → 开机后 docker daemon 拉起全部 22 容器；无需人工干预（前提 `systemctl enable docker`）。

## 五、已知注意点（部署时对照）

1. **从库不执行 init-all.sql**（已修正 compose）：从库仅挂 `init-replication.sql`（CHANGE MASTER + START SLAVE），**建库建表/初始化数据全部由主库 binlog 通过 GTID 同步**——若从库误跑 init-all 会与复制 GTID 冲突（错误 1236/重复执行）。部署后验证：`SHOW REPLICA STATUS` 确认 IO/SQL Running=Yes、Seconds_Behind=0。
2. **Redis announce-ip**：compose 已配 `--replica-announce-ip 21.130.247.89`（8/11 修复，勿回退）——若云主机 IP 不同且未用 EIP，需同步改。
3. **Nacos**：默认 `nacos/nacos` + **无鉴权**（P-D1 待办）——部署后建议立即开启鉴权或限制安全组。
4. **中间件密码**：全部 `Xhs@2026#*` 明文（P-D5 待办）——生产建议随机化。
5. **SkyWalking 版本**：agent 9.6.0 vs OAP 9.7.0 不匹配（P-T2）——若后续微服务接 SW，需对齐。

## 六、部署包增强（2026-08-12 已并入 compose）

1. **healthcheck 全覆盖**：22 个容器全部有 healthcheck（新增 nacos/rocketmq-namesrv/xxl-job-admin/prometheus/kibana/grafana/filebeat/victoria-metrics/sentinel-dashboard/skywalking-ui）。
2. **依赖时序**：关键 `depends_on` 加 `condition: service_healthy`（mysql-slave→mysql、canal→mysql、nacos→mysql、xxl-job→mysql、broker/dashboard→namesrv、logstash/kibana→es、grafana→prometheus、filebeat→logstash）——首次部署并行启动时按序就绪。
3. **Sentinel 限流规则**：`config/sentinel/*.json`（16 服务 flow/degrade 规则）**compose 不自动加载**——部署后需在 Sentinel Dashboard(http://IP:8858) 手动导入，否则限流不生效。gateway 的限流另受 Nacos 数据源/本地 metadata 影响（见应用层说明）。

## 七、从零部署初始化补充（2026-08-12）

1. **nacos_config/xxl_job 表已补全**：原 init-all.sql 只建库未建表（全新部署 Nacos/xxl-job 连空库会失败）；已把 xxl_job 8 表（含 schedule_lock/admin 初始数据）+ nacos 12 表并入 `sql/init-all.sql` 末尾，**临时库实测通过（20 表）**。
2. **Nacos 配置需导入**：从零部署后 Nacos 的 `my-xhs` 命名空间配置为空——需从现环境导出导入（my-xhs-common.yaml / my-xhs-gateway.yaml / my-xhs-redis.yaml），否则微服务用本地 yml 默认值（含 DB/Redis 密码一致，但 redis 端口/sentinel 等以 Nacos 为准）。
3. **ES IK 插件需外网**：ES 首次启动从 `get.infini.cloud` 下载 IK 插件——云主机需能访问该域名，否则 ES 启动卡死。
4. **docker 镜像加速**：云主机 docker 配置镜像加速（国内拉 elasticsearch/kibana 8.x 大镜像）：`/etc/docker/daemon.json` 配 `registry-mirrors`（腾讯云/阿里云加速），然后 `systemctl restart docker`。
5. **rocketmq-dashboard:latest**：compose 用 latest 标签（不可复现）——建议 `docker tag` 固定当前版本或改用具体 tag。
