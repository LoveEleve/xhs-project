# 云服务器按量计费部署配置清单（DEPLOY-CLOUD-GUIDE）

> 2026-08-12 | 场景：按量计费云服务器，不开发时关机、开机后需**全自动恢复**（容器+微服务）。
> 适用：当前架构（中间件 21.130.247.89 + 微服务 21.214.97.212 两台机，或合并单机）。

---

## 一、现状（已核实）

| 项 | 现状 | 问题 |
|---|---|---|
| Docker restart 策略 | 22 个容器全 `restart: unless-stopped` | 手动 stop 过的容器开机后**不恢复** |
| Docker daemon 自启 | 未确认 enable（systemctl 查不到单元） | 关机开机后 docker 可能不自启 |
| 微服务自启 | **无任何机制**（无 systemd/cron @reboot/rc.local） | 关机开机后 15 个微服务**不会自动起** ← 最大缺口 |
| 环境变量 | start-all.sh 用 `${ADMIN_TOKEN:}` 等，靠交互 shell export | 开机自启时无 env → 内部 token 落默认值（安全风险） |
| IP 硬编码 | sentinel.conf/broker.conf/prometheus.yml/docker-compose/start-all.sh + 微服务 yml/Nacos 大量 `21.130.247.89`/`21.214.97.212` | **按量计费关机后公网 IP 可能变化 → 全部配置失效** |
| 数据持久化 | named volumes 在云盘（持久）✅ | 需确认云盘是持久盘；**无备份（P-D19）** |

---

## 二、必须修改项

### 1. Docker restart 策略：unless-stopped → always
- **原因**：`always` 保证任何停止（手动 stop/崩溃/主机重启）后都自动拉起；`unless-stopped` 对**手动 stop 过的容器**不恢复。按量计费场景要求"开机即全恢复"。
- **修改**（docker-compose.yml 全部 22 个服务）：
  ```yaml
  restart: always
  ```
- **注意**：
  - `always` 对"启动即崩溃"的容器会无限重启（刷日志）——MySQL/ES 在数据恢复期可能反复重启，建议对这俩用 `restart: on-failure:5` 或保留 unless-stopped 且**保证不手动 stop**。
  - 配合 healthcheck：compose 已有 healthcheck，`always` + healthcheck 是标准组合。
- **验证**：`docker restart <容器>` 后自动恢复；`docker stop <容器>` 后 daemon 重启自动拉起。

### 2. Docker daemon 开机自启
- **修改**：
  ```bash
  systemctl enable docker
  systemctl enable containerd
  systemctl is-enabled docker   # 应输出 enabled
  ```
- 若腾讯云镜像 docker 非 systemd 管理（`systemctl` 查不到），改用：
  ```bash
  # 确认 docker 由 systemd 管理；否则用 rc.local 兜底
  echo 'systemctl start docker' >> /etc/rc.local  # 需 chmod +x /etc/rc.local
  ```

### 3. 微服务开机自启（最大缺口）
- **推荐 systemd unit**（可控依赖/环境变量/失败重启）：
  ```ini
  # /etc/systemd/system/myxhs-microservices.service
  [Unit]
  Description=MyXHS Microservices (15)
  After=docker.service network-online.target
  Wants=network-online.target

  [Service]
  Type=oneshot
  RemainAfterExit=yes
  EnvironmentFile=/data/workspace/my-xhs/config/my-xhs.env
  ExecStart=/data/workspace/my-xhs/start-all.sh
  ExecStop=/data/workspace/my-xhs/stop-all.sh
  TimeoutStartSec=600

  [Install]
  WantedBy=multi-user.target
  ```
  ```bash
  systemctl daemon-reload && systemctl enable myxhs-microservices
  ```
- **或 crontab @reboot（简单版）**：
  ```
  @reboot /bin/bash /data/workspace/my-xhs/start-all.sh >> /var/log/myxhs-boot.log 2>&1
  ```
- **启动顺序保障**：start-all.sh 已分批启动+healthcheck 等待，但开机时中间件容器可能未就绪。建议 start-all.sh 开头加"等待中间件就绪"（MySQL 3306/Nacos 18848/Redis 6379 端口可连后再启微服务，最多等 180s）：
  ```bash
  for i in $(seq 1 60); do
    mysqladmin ping -h 127.0.0.1 -P 3306 -u root -p"$MYSQL_PASSWORD" --silent && break; sleep 3
  done
  ```
- **验证**：重启主机 → `systemctl status myxhs-microservices`、15 服务健康。

### 4. 环境变量注入（开机自启时生效）
- 创建 `/data/workspace/my-xhs/config/my-xhs.env`（供 systemd EnvironmentFile / 或 start-all.sh source）：
  ```bash
  ADMIN_TOKEN=my-xhs-admin-token-2026
  INTERNAL_TOKEN=my-xhs-internal-token-2026
  MYSQL_PASSWORD=Xhs@2026#MySQL
  REDIS_PASSWORD=Xhs@2026#Redis
  JWT_SECRET=MyXhs@2026#JwtSecretKey!ForTokenSign
  ```
- start-all.sh 顶部 `set -a; source config/my-xhs.env; set +a`，并删除 yml 里明文默认值（对齐 P-D5）。
- **验证**：开机自启后各服务 internal token 非默认（`/actuator/env` 核对）。

### 5. IP 稳定性（按量计费最大坑）
- **问题**：按量计费关机后公网 IP 可能释放重分配；配置硬编码 `21.130.247.89`/`21.214.97.212` 于 5+ 处（compose/sentinel/broker/prometheus/start-all + 微服务 yml + Nacos）→ IP 一变全挂。
- **修改（按优先级）**：
  1. **绑定弹性公网 IP（EIP）**，确保关机重启 IP 不变（最推荐，零配置改动）。
  2. 若同 VPC：全部改用**内网 IP**（内网 IP 关机不变）。
  3. **配置变量化**：compose/sentinel/broker/prometheus/start-all 里 IP 改 `${HOST_IP}`，统一从 my-xhs.env 注入；微服务 yml 的中间件地址改环境变量（Nacos 配置里改 `${MYSQL_HOST}` 等）。
- **验证**：模拟 IP 变更（改 my-xhs.env）→ 重启服务全通。

### 6. 数据持久化与备份
- named volumes 在持久云盘 ✅（确认挂载点 `/data/docker/lib/` 在持久盘）。
- **补备份（P-D19）**：每日 mysqldump + 云盘快照（按量计费支持快照）双保险。
- 关机前建议**正常关机**（云控制台 ACPI），避免强制关机导致 MySQL/ES 未优雅关闭（启动恢复慢/需手工处理）。

### 7. 资源规划（若合并单机/换小规格）
- 当前实测：容器峰值 ~9GB（mysql-slave 749M/logstash 721M/OAP 1.2G/ES 808M+1.1G）+ 微服务 ~10GB（-Xmx 512m~1g × 15）。
- **单机建议 ≥ 24-32GB**；若规格小（16G）需减配：ES 两节点降堆、微服务 -Xmx 降 256-512m、关 OAP/VM 等可选组件。
- 内存紧张场景优先保证：MySQL/Redis/RocketMQ/Nacos（业务依赖）+ 微服务；可关：Grafana/VM/SW-OAP/ES-SW（演示组件）。

### 8. 关机后恢复验证脚本
- 开机后执行（cron 或手动）：
  ```bash
  # 1. 容器
  docker ps --filter status=running | wc -l          # 应=22
  # 2. 微服务
  for p in 19000 19001 19002 19003 19004 19006 19008 19009 19010 19011 19012 19013 19014 19015 19016; do
    curl -sf http://localhost:$p/actuator/health >/dev/null || echo "DOWN: $p"
  done
  # 3. 数据
  mysql -h 127.0.0.1 -P 3306 -e "SHOW REPLICA STATUS\G" | grep Running   # 从库应 Running=Yes
  ```
- 可挂 cron @reboot 后 5 分钟自动跑并告警。

---

## 三、修改清单汇总

| # | 文件 | 修改 |
|---|---|---|
| 1 | docker-compose.yml | 22 个服务 `restart: unless-stopped` → `always`（MySQL/ES 可 on-failure:5） |
| 2 | systemd | `systemctl enable docker containerd`；新增 `myxhs-microservices.service`（enable） |
| 3 | start-all.sh | 顶部 source config/my-xhs.env；开头加中间件就绪等待（MySQL/Nacos/Redis 端口探测） |
| 4 | config/my-xhs.env | 新增（ADMIN/INTERNAL_TOKEN、DB/Redis 密码、JWT secret、HOST_IP） |
| 5 | 5 个部署配置文件 + 微服务 yml + Nacos | IP 硬编码 → `${HOST_IP}`/环境变量（配合 EIP 或内网） |
| 6 | 备份 | 每日 mysqldump + 云盘快照（P-D19） |
| 7 | 资源 | 单机 ≥24-32GB；小规格按 8 节减配 |

## 四、验证步骤（一次完整关机→开机演练）
1. 云控制台正常关机（ACPI）。
2. 开机。
3. 等 3-5 分钟 → 自动检查：22 容器 running、15 微服务健康、从库复制 Yes、业务接口可访问（登录/下单各一次）。
4. 确认 IP 未变（EIP）→ 配置无需改。
