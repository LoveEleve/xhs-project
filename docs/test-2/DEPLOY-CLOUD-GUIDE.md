# 中间件容器按量计费部署操作清单（21.130.247.89 中间件机）

> 2026-08-12 | 场景：按量计费云服务器，不开发时关机、开机后中间件容器全自动恢复。
> 微服务（Windows 本地跑）不在本机范围；本清单只针对**中间件 docker 容器**。

## 前提确认（本机=开发容器内，无 docker 权限，以下操作需在中间件机 21.130.247.89 执行）

## 一、必须做的 3 件事

### 1. docker-compose restart: always（已生成修正版）
- 修正版文件：`/data/workspace/my-xhs/config/deploy-cloud/docker-compose.yml`（22 个服务全部 `restart: always`，已生成）。
- **中间件机上执行**：
  ```bash
  cd /data/workspace/my-xhs-deploy-zip        # compose 实际目录
  cp docker-compose.yml docker-compose.yml.bak
  # 将修正版内容替换 docker-compose.yml（或直接：
  sed -i 's/restart: unless-stopped/restart: always/' docker-compose.yml
  docker compose up -d                          # 滚动生效（restart 策略变更无需重建）
  ```
- **说明**：`always` 保证"手动 stop 过/崩溃/整机重启"后都自动拉起；`unless-stopped` 对手动 stop 过的容器不恢复。按量计费"关机→开机"场景用 `always` 最稳。
- **可选**：MySQL/ES 若担心崩溃无限重启，可改为 `restart: on-failure:5`（数据恢复期防刷屏）。

### 2. Docker daemon 开机自启
- **中间件机上执行**：
  ```bash
  systemctl enable docker
  systemctl enable containerd
  systemctl is-enabled docker     # 应输出 enabled
  ```
- 若系统非 systemd（同本机情况），改用 rc.local 兜底：
  ```bash
  chmod +x /etc/rc.d/rc.local
  echo 'systemctl start docker' >> /etc/rc.d/rc.local
  ```

### 3. IP 稳定性（按量计费最大坑）
- **问题**：按量计费关机后公网 IP 可能释放重分配；配置硬编码 `21.130.247.89`（Nacos NACOS_SERVER_IP、sentinel monitor/announce、brokerIP1、canal master.address、xxl-job、微服务连接串等）。
- **解决（选一）**：
  1. **绑定弹性公网 IP（EIP）**——最推荐，关机重启 IP 不变，配置零改动。
  2. 同 VPC 用内网 IP（内网 IP 关机不变）。
  3. 变量化 `${HOST_IP}`（compose/sentinel/broker/prometheus/start-all + 微服务连接串）——改动面大，仅当 IP 无法固定时。
- **验证**：控制台记录当前 IP → 关机→开机 → IP 不变（EIP）；或变后按清单改配置。

## 二、建议做的

### 4. 数据持久化与备份
- named volumes（mysql-data/es-data 等）在持久云盘 ✅（确认挂载盘为持久云盘）。
- **补备份**：云盘快照（控制台定时快照，最简单）+ MySQL 每日 mysqldump（脚本参考）：
  ```bash
  # /data/backup/mysql-backup.sh（cron 每日 3:00）
  mkdir -p /data/backup/mysql
  mysqldump -h127.0.0.1 -uroot -p'Xhs@2026#MySQL' --all-databases --single-transaction \
    | gzip > /data/backup/mysql/mysql-$(date +%F).sql.gz
  find /data/backup/mysql -name '*.gz' -mtime +7 -delete
  ```
  `crontab -e` 加 `0 3 * * * /data/backup/mysql-backup.sh`
- **关机流程**：云控制台**正常关机**（ACPI），避免强制关机导致 MySQL/ES 未优雅关闭。

### 5. 开机后自动恢复验证
- 中间件机加 cron（开机后 3 分钟自动检查并可选告警）：
  ```bash
  @reboot sleep 180 && docker ps --format '{{.Names}} {{.Status}}' | grep -c Up
  ```
- 手动验证：关机→开机→等 2-3 分钟 → `docker ps` 应 22 个 Up。

## 三、当前问题关联（中间件机侧待办，非本次部署范围）
- P-D1 Nacos 无鉴权、P-D5 密码、P-D14 ES 无 ILM、P-D15 内存濒危（mysql-slave 97.5%）等——见 FIX-PLAN-PRODUCTION-CONFIG.md，建议与本次部署改造一并处理。
