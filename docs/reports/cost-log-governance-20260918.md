# 成本与日志治理报告（E1+H1，2026-09-18）

## 一、执行摘要
| 项 | 治理前 | 治理后 | 释放 |
|---|---|---|---|
| 内存占用 | 45Gi used | 28Gi used | **~17GB** |
| 根盘 / | 83%（39G/50G） | **76%（36G/50G）** | ~3GB |
| /data2 | 50% | **45%（22G/49G）** | ~2.4GB |
| /data2/logs | 3.7G | 2.6G（+每日 cron 保留 3 天） | ~1.1G |
| /data2/releases | 8.1G | 6.5G（版本保留 5→3） | ~1.6G |
| /root/.npm | 3.6G | 591M | ~3GB |
| /var/cache/apt | 416M | 60K | ~0.4G |

## 二、最大发现：19 个残留 JVM（16.4GB 内存 + 消息竞争）
- **现象**：19 个从 `my-xhs-*/target/*.jar` 启动的 JVM（09:20 批量启动，非 releases 版本），合计 RSS 16.4GB，且连接到 RocketMQ（9876）。
- **风险**：与线上实例**同消费组竞争消息**、重复写同一份 JSON 日志文件、占用 16GB 内存。
- **处置**：确认均不持有任何主端口（19000-19030）后全部清理；随后 15 服务健康 15/15、Nacos 实例数正常。
- **根因**：早期手工/脚本启动的服务未纳入 release 管理；release 脚本早期"假成功"问题（已修复）未覆盖此类进程。

## 三、日志治理（E1）
1. **保留策略落地**：`scripts/log-cleanup.sh`（JSON 文件保留 3 天 + ES 日志索引保留 7 天），已配置每日 04:00 cron。
2. **ES 索引清理**：删除 10 个过期 `myxhs-logs-*` 索引。
3. **应用侧上限**：15 个服务 `logback-spring.xml` 的 JSON appender 升级为 `SizeAndTimeBasedRollingPolicy`（单文件 100MB / 保留 7 天 / 总量 2GB），已随全量发布生效（15/15 服务验证）。
4. 业务日志（`logs/*/info.log|error.log`）原有 100MB/30天/3GB 上限不变。

## 四、成本盘点（H1）
| 组件 | 资源 | 说明 |
|---|---|---|
| 15 个 JVM 服务 | 堆 512MB~1GB（Xmx），实际 RSS 合计 ~8-10GB | 发布脚本已按模块设 Xmx（inventory/order/search 1024m） |
| Docker 容器 29 个 | ES 1.07G、MySQL 1.05G、xxl-job 1.0G、Nacos 0.9G、Gitea 0.9G、SkyWalking 0.86G、Kibana 0.79G、ES-Sky 0.73G、MySQL-Slave 0.71G、Logstash 0.69G、MQ 0.68G… | 具备缩容空间（SkyWalking/Kibana/Logstash 可选停用） |
| Docker 镜像 | 26 个 / 22.5GB，实际未使用仅 maven（CI 需要）与 act_runner（保留） | `/var/lib/containerd` 21G（snapshotter 活跃数据，未动） |
| 磁盘其它 | /root/.local 2G（opencode 数据）、/var/lib/containerd 21G | 不建议盲删 |

## 五、遗留建议
1. **containerd 数据在根盘（21G）**：如需进一步释放根盘，可在维护窗口将 containerd root 迁至 /data2（需重启 Docker，风险中）。
2. **SkyWalking/Kibana/Logstash**：若无实际使用，停用可再省 ~2.3GB 内存。
3. **残留进程防复发**：新 release 流程已带 PID 校验 + 端口占用清理；建议定期执行"监听 PID vs pid 文件"审计（本次审计脚本口径）。

---

## 七、磁盘复核（2026-09-18 晚）

- 根盘 `/`：**37G/50G（78%）**；下钻定位：`/var/lib/containerd` **22G**
- 原因：Docker 使用 **containerd snapshotter（overlayfs）** 存储驱动，镜像层落在 `/var/lib/containerd`（而非 `Docker Root Dir=/data2/docker`）：
  - `snapshotter.v1.overlayfs` 16G + `content.v1.content` 5.7G
- 安全回收评估：`docker system df` — 悬空镜像 0、停止容器 0、构建缓存 0 → **标准 prune 可回收 ≈0**（22G 为 32 个运行容器的真实镜像占用）
- 建议（未执行，需窗口）：将 containerd 根迁移至 `/data2/containerd`（改 `config.toml` + 重启运行时）或扩容根盘；`/data2` 尚余 22G
