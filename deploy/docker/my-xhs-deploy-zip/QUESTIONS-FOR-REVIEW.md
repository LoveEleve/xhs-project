# 待确认问题清单(请对方逐条回答)

> 2026-08-14 最终状态: ✅ **全部闭环**。对方已归档 ANSWERS。
> 最终结论: 无需改任何业务代码; 同步 master 9 条(SYNC-NOTES-FOR-MASTER.md)
> 即可获得完整标签数据(common v2 重打包后 topic/result 标签自然出现)。

> 2026-08-14 更新: 对方已全部答复并处理, 状态如下。

> 2026-08-13 | 试验机全量核查发现, 需业务/部署负责人确认。
> 相关数据见 SKYWALKING-SERVICES.md(33 个服务清单)。

## A. SkyWalking 链路数据层(重点)

**A1. 微服务配置中仍引用旧架构端口?**
SkyWalking 链路中持续(最近 5 分钟窗口)出现以下虚拟节点, 对应端口在本机**已不存在**:
- MySQL 旧端口: 13306 / 13307 / 13308 / 13309 / 13310 / 13311 / 13313(旧版 4 组主从架构)
- Redis 旧端口: 16379(旧版 Redis)、26379(旧版 Sentinel)
请确认: 远端微服务的 application.yml / Nacos 配置中的 datasource、Redis 地址
**是否还有旧端口未更新**? 正确应为 MySQL 3306/3307、Redis 6379/6380。
(影响: 若业务配置仍指向旧端口, 可能存在连接失败或走到了错误实例。)

**A2. 未识别地址节点来源?**
- `localhost:-1`(VIRTUAL_DATABASE)
- `Redis-local`(VIRTUAL_CACHE)
通常是 agent 无法解析的连接地址(配置写 localhost / 服务名 / 哨兵模式)。
请确认哪些服务产生这些连接, 配置地址是否应改为 21.130.247.89:3306 等具体地址?

**A3. agent 服务名不一致, 同一业务出现两个服务?**
链路中同时存在:
- `my-xhs-payment` 与 `payment`
- `my-xhs-order` 与 `order`
请确认是否部分实例的 agent.service_name(或 agent_name)配置漏了 `my-xhs-` 前缀?
建议统一, 否则拓扑图中同一业务拆成两个节点, 指标与告警也会分裂。

**A4. 历史脏数据是否清理?**
以上旧端口/重复服务的历史 trace 已写入 SW-ES(19201)。确认配置修复后,
是否需要清理旧 sw_* 索引数据? (可提供清理脚本, 或等 TTL 自动过期。)

**A5. 业务埋点延迟桶边界异常?**
微服务上报的 myxhs_http_request_duration_seconds_bucket 桶边界是异常浮点序列
(0.001048576 / 0.447392426 / 0.536870911..., 无标准 0.5/1.0 等边界, 疑似动态/错误公式生成)。
影响: 1) 按 le="0.5" 查询永远无数据; 2) P95/P99 计算依赖这些桶, 可能偏差。
请确认微服务中该指标的桶配置(建议标准桶: 0.005,0.01,0.025,0.05,0.1,0.25,0.5,1,2.5,5,10)。

**A6. canal example 空实例噪音(与告警无关):**
canal 内置 example 空实例(无配置)的延迟指标是垃圾值(23.8h), 已在 Prometheus 侧
relabel drop(metric_relabel_configs 过滤 destination="example"), 监控指标已干净。
(注: 告警按业务要求暂不启用, 相关规则策略问题全部搁置, 等需要时再处理。)

## B. 部署配置(试验机已验证, 请同步 master)

**B1. 本次修复清单是否已同步到 master 仓库?**
1) broker.conf 注释规范(禁止行内注释, 否则配置静默失效)
2) compose: 修正 canal depends_on 空映射、redis-exporter 镜像名、
   alertmanager/OAP 监听端口、mysqld-exporter 的 my.cnf + MYSQLD_EXPORTER_PASSWORD
3) 新增 mysqld-exporter-slave(9105, 从库复制监控)
4) 新增 node-exporter textfile(RocketMQ 中间件监控方案, 见 README-METRICS.md)
5) Grafana 看板: ${DS_PROMETHEUS} 占位符替换、双列布局、JVM 32/MQ 11/
   MySQL 18/node 33 面板
6) SkyWalking OAP log4j2.xml netty 日志降噪
7) MySQL: innodb_print_all_deadlocks=ON(死锁留痕)

**B2. 监控缺口确认:**
1) RocketMQ 中间件监控采用 textfile 方案(官方镜像无 metrics 模块、
   exporter 客户端不兼容), 是否认可该方案进云主机部署?
2) 告警通知渠道(alertmanager webhook)目前是占位, 业务侧是否需要配置?
3) 备份 cron 已挂(每天 02:00, 保留 7 天), 是否需要调整?

**B2b. MQ 消费指标缺维度标签 —— ✅ 已闭环(2026-08-13):**
根因: common v1 预注册无标签序列吞标签(业务埋点本就带 topic/consumerGroup/result)。
结论: 无需改业务代码, 同步 master 9 条(SYNC-NOTES-FOR-MASTER.md) + 13 服务重打包
v2 common 重启后标签自然出现。试验机已预备 by topic/by result 面板待自动生效。

**B3. Tomcat 线程池监控缺失:**
微服务 Micrometer 1.13+ 仅暴露 tomcat_sessions_* 指标, tomcat.threads/tomcat.connections
已被裁掉, 当前无法监控 Tomcat 工作线程池(繁忙/阻塞线程)。
请确认: 微服务是否可开启 server.tomcat.mbeanregistry.enabled=true 或其他方式
暴露线程池指标? 若无法开启, 则以 http_server_requests 的 QPS/耗时 + JVM 线程作为近似监控。

## C. 环境遗留(需知悉)

**C1.** 宿主机存在旧版残留: broker-slave.conf(无引用)、旧孤儿卷(未清理, 占磁盘)。
是否纳入后续清理计划?

**C2.** 文件清单: filebeat 已按 25 容器基线移除(微服务日志经 TCP 直连 Logstash 15044),
当前 compose 为 27 容器(25 基线 + node-exporter + mysqld-exporter-slave)。

## 答复状态(对方 2026-08-13 回复)

| 编号 | 问题 | 对方答复 | 本地落实情况 |
|---|---|---|---|
| A1 | 旧端口虚拟节点 | 非当前配置(历史 trace 推断), 26379 是现行 Sentinel 端口 | 历史数据已随 A4 清理 ✓ |
| A2 | localhost/Redis-local | 历史数据, 当前零 localhost 配置 | 已清理 ✓ |
| A3 | order/payment 裸名 | 非我方产生, 请试验机自查; 裸名数据已删 | 本机无微服务进程(仅中间件容器), 远端待观察; 对方已删数据 ✓ |
| A4 | 历史脏数据 | ✅ 已清理 SW-ES 全部 23 个 sw_ 索引, 自动重建 | 本地验证: 仅剩 20260813 新索引, segment 1611 条在写 ✓ |
| A5 | 动态桶 | 标准 publishPercentileHistogram 指数桶, le="0.5" 恒空是预期, 须用 histogram_quantile | 慢请求面板已删, P95/P99 用 histogram_quantile ✓ |
| A6 | canal example | drop 规则已同步 master | 本地 relabel 已生效 ✓ |
| B1 | 修复同步 | 7 项全部就位(master 补同步 4 项) | ✓ |
| B2 | 监控缺口 | ①textfile 认可 ②告警暂不启用 ③备份维持默认 | ✓ |
| B3 | Tomcat 线程池 | ✅ 已开启 mbeanregistry(14 个 Servlet 服务, gateway 为 WebFlux) | 本地验证 tomcat_threads_* 已出现, 看板已换真指标 ✓ |
| C1 | 残留文件 | broker-slave.conf 请从 zip 删除 | ✅ 已删(运行目录+deploy-package+zip) |
| C2 | 容器构成 | filebeat 移除、27 容器一致 | ✓ |

**对方待办**: ①zip 删 broker-slave.conf(已由本地完成) ②试验机自查裸名实例(已自查: 本机无残留) ③同步最新配置(已完成)

---

## D. 本地 2026-08-24 静态复核结论(未启动服务)

> 范围: `/data/workspace/xhs-project/deploy/docker/my-xhs-deploy-zip` + `config/docker-compose.yml` + `docs/test-2/3` + `pom.xml`。
> 验证层级: **L0/L1**(静态/语义), **未做 L2 运行态启动验证**。

### D1. 已确认的版本基线(当前应采用)
- **应用依赖基线**(以根 `pom.xml` 为准): Java 17 / Spring Boot 3.2.5 / Spring Cloud 2023.0.3 /
  RocketMQ broker 5.1.4 + `rocketmq-spring-boot-starter` 2.3.0 / Nacos client 2.3.0 /
  Sentinel 1.8.8 / SkyWalking 9.7.0 / XXL-Job 2.4.2 / MySQL Connector 8.0.33。
- **中间件部署基线**(以当前 deploy compose 为准):
  - MySQL `8.0`
  - Redis `7-alpine`
  - RocketMQ `apache/rocketmq:5.1.4`
  - Nacos `nacos/nacos-server:v2.3.2`
  - Sentinel `bladex/sentinel-dashboard:1.8.8`
  - XXL-Job `xuxueli/xxl-job-admin:2.4.2`
  - SkyWalking `9.7.0`
  - 业务 ES `8.19.19`
  - SkyWalking ES `8.12.2`
  - Grafana `10.2.3`
  - Prometheus `v2.48.1`
  - Logstash/Kibana `8.19.19`
  - Canal `my-xhs-canal-server:v1.1.7-squashed`
- **拓扑基线**: 当前 compose 定义 **27 个服务**(非旧文档中的 22/25), 且全部 `network_mode: host`。

### D2. 新发现问题(需处理)
1. **当前解压目录曾缺少 `config/mysql-connector.jar`(已补齐)**。
   - 2026-08-24 已从 `deploy/docker/my-xhs-deploy-ai-package-20260821-fixed.zip` 补回当前解压目录。
   - 当前文件已存在, 大小 `2475087` bytes。
2. **`rocketmq-dashboard:latest` 不可复现, 必须固定(已处理)**。
   - 当前生产快照中已验证运行镜像 digest 为:
     `apacherocketmq/rocketmq-dashboard@sha256:ce78506bd6fe01095bf1b37c954625e363ba5afae5d1539b77f395785d445e33`。
   - 2026-08-24 本地已将当前部署目录 compose 改为固定 digest。
3. **Compose v2 之前缺失(已安装)**。
   - 2026-08-24 已安装 `docker compose` v2, 当前版本 `2.40.3`。
4. **JDK 挂载策略需要按组件重新收敛, 不是全部都必须 Kona**。
   - RocketMQ 5.1.4 官方镜像自带 JDK 8;
   - Nacos 2.3.2 镜像自带 OpenJDK 8;
   - SkyWalking OAP 9.7.0 镜像自带 JDK 11;
   - **仅 Canal 1.1.7 已有运行态证据表明与 JDK 17 不兼容**, 仍需 JDK 8。
   - 2026-08-24 本地已从 compose 中移除 RocketMQ/Nacos/Sentinel/XXL/SkyWalking 的 `kona-jdk17` 挂载与环境变量,
     当前仅保留 Canal 的 JDK 8 挂载。
   - 后续若进一步去 Kona, 只需为 Canal 单独提供通用 OpenJDK 8 路径即可。
5. **RocketMQ Prometheus 插件路线不可用, 继续保留 textfile 方案**。
   - `metricsExporterType=PROM` 在 5.1.4 官方镜像上无效;
   - `rocketmq-exporter` 与 5.1.4 兼容性不稳定/已被文档否决;
   - 当前正确方案仍是 `config/deploy-cloud/rocketmq-metrics.sh` + node-exporter textfile。
6. **运行态修正(2026-08-24)**: `mysqld-exporter-slave` 在 `prom/mysqld-exporter:v0.15.1` 下不支持 `--collect.innodb_status`,
   已从 compose 中移除该 flag, 保留 `--collect.slave_status` 以满足从库复制监控。
6. **部署文档存在历史口径, 不能直接照抄 test-1 早期示例**。
   - `docs/test-1/infrastructure/04-infrastructure-and-deployment.md` 中有 Nacos 3.0.0 / XXL-Job 3.0 /
     旧 Redis/Sentinel 拓扑等示例, 不应作为当前部署基线。
   - 当前有效入口以 `docs/test-2/README.md` 指定的 `HANDOFF-TASK4.md` / `HANDOFF-TASK5.md` /
     `review-production-config.md` / `config/deploy-cloud/DEPLOY-README.md` 为准。

### D3. RocketMQ / 监控专项结论
- Broker 与客户端版本继续保持现状对齐: Broker 5.1.4 + `rocketmq-spring-boot-starter` 2.3.0。
- Dashboard 只解决运维查看/死信重投, **不承担 Prometheus 指标采集**。
- RocketMQ 中间件指标继续采用:
  - `config/deploy-cloud/rocketmq-metrics.sh`
  - 宿主机目录 `/data/rocketmq-textfile`
  - node-exporter textfile collector
  - cron 定时采集
- 后续若做运行态增强, 需补一条 L2 校验: `rocketmq_up` 不能在 `mqadmin` 失败时仍长期为 1。

### D4. 下一步执行顺序(建议)
1. 准备 Canal 的通用 JDK 8 路径 `/opt/openjdk8`。
2. 再继续做不启动服务的 compose/config 二轮复核; 通过后才进入首次中间件启动。

### D5. 二轮静态复核补充(2026-08-24)
- **Canal 的 JDK 8 依赖仍是首启前置(已准备)**。
  - 2026-08-24 已将 compose 收敛为仅依赖 Canal 的通用 JDK8 路径: `/opt/openjdk8`;
  - 当前本机该路径已就绪, `java -version` = `1.8.0_492`。
- **Canal 自定义镜像依赖已消除**。
  - 2026-08-24 已确认官方 `canal/canal-server:v1.1.7` 的入口与当前 compose 兼容:
    `[/alidata/bin/main.sh] + [/home/admin/app.sh]`;
  - 因此当前不再依赖 `my-xhs-canal-server:v1.1.7-squashed`。
- **宿主机目录前置仍未就绪**。
  - `/data/rocketmq-textfile` 当前不存在: 会影响 node-exporter textfile 采集 RocketMQ 指标;
  - `/logs` 当前不存在: 该目录在现版 compose 中不再直接挂 filebeat, 但若后续恢复日志采集脚本/旧说明, 需避免混淆。
- **Dashboard digest 仅已固定在 compose, 本机尚未拉取镜像**。
  - 当前 `apacherocketmq/rocketmq-dashboard@sha256:ce78506bd6fe01095bf1b37c954625e363ba5afae5d1539b77f395785d445e33`
    还未存在于本机镜像缓存中, 首次启动时会拉取。
- **当前首次启动前的最小前置清单**:
  1. 准备 Canal JDK8 路径(保留 `/opt/kona-jdk8` 或改为明确的 OpenJDK8 路径);
  2. 创建 `/data/rocketmq-textfile`;
  3. 确认镜像拉取源已可用(当前 Docker 已可拉 `hello-world` 与 `canal/canal-server:v1.1.7`, 但其余大镜像仍属首次实战验证范围);
  4. 再进入首次 `docker compose up -d`。
