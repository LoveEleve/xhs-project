# 64GB 单机生产增强架构规划

> 2026-08-24
> 目标: 在 **1 台 64GB 内存主机** 上同时承载中间件与后续微服务, 但仍保持尽量接近生产的稳定性、可观测性与可恢复性。
> 范围: 仅基于当前仓库、部署包、文档与静态配置分析形成规划, **未做运行态启动验证**。

## 一、先说结论

这套系统可以做成 **64GB 单机生产增强版**,
但不建议在单机内盲目追求“看起来像分布式”的全套高可用拓扑。

更合适的目标是:

1. **保留单机物理宿主**;
2. **中间件内部适度冗余**(例如 MySQL 1主1从、Redis 1主1从+Sentinel、双 ES 实例保留);
3. **避免引入会显著增加复杂度但无法真正提升单机容灾能力的伪集群**;
4. **优先把稳定性、备份恢复、资源预算、核心指标做到生产级**;
5. **开发/调试/演示友好优先**: 不为了“形式上的生产化”阻碍日常联调;
6. **只对确实能带来收益的组件做架构升级**。

一句话:

- **可以升级, 但应该做“单机增强生产版”, 不是“单机里硬塞分布式全集群版”。**

---

## 二、当前真实架构基线

以 `deploy/docker/my-xhs-deploy-zip/docker-compose.yml` 为准, 当前部署包对应的是:

- **MySQL**: 1主(3306) + 1从(3307)
- **Redis**: 1主(6379) + 1从(6380) + 1个 Sentinel(26379)
- **RocketMQ**: 1 个 NameServer + 1 个 Broker + 1 个 Dashboard
- **Elasticsearch**:
  - 业务 ES 1 实例(19200)
  - SkyWalking ES 1 实例(19201)
- **Canal**: 1 实例, 3 个 destination
- **Nacos**: 单节点
- **Sentinel Dashboard**: 单节点
- **XXL-Job Admin**: 单节点
- **SkyWalking**: OAP + UI
- **Prometheus / Alertmanager / VictoriaMetrics / Grafana**: 单节点
- **日志链路**: Logstash + Kibana
- **Exporter**:
  - redis-exporter
  - elasticsearch-exporter
  - mysqld-exporter(主)
  - mysqld-exporter(从)
  - node-exporter
  - RocketMQ 走 textfile, 非原生 exporter

当前 compose 共 **27 个服务**。

### 当前资源上限(来自 compose limit)

- 中间件 memory limit 总和约 **22.81 GiB**
- reservation 总和约 **10.22 GiB**

注意:

- 这只是中间件容器上限, **不含后续 15 个微服务 JVM**;
- 也 **不含宿主机页缓存、内核、docker/containerd、文件系统 cache、临时峰值**。

因此 64GB 虽然足够, 但不能把中间件无脑扩成“多主多副本”。

---

## 三、什么是这台 64GB 机器上合理的“生产级”

### 3.1 生产级不等于“组件越多越好”

在单机场景里, 真正有价值的目标是:

1. **故障可发现**: 核心指标、日志、必要诊断信息齐全
2. **故障可恢复**: 备份、脚本、卷、镜像、配置齐全
3. **资源可控**: 不让某个组件吃光内存把整机打挂
4. **关键链路可兜底**: MySQL 主从、消息补偿、库存/订单/券的调度链路齐全
5. **配置一致**: 微服务与中间件地址、Nacos 配置、Prometheus 目标一致
6. **开发友好**: 不把中间件安全/治理项做到影响联调效率的程度

而以下做法在单机场景里“收益不高、成本很高”:

- Redis Cluster 3主6从
- ES 三节点集群
- RocketMQ 3 节点 DLedger
- Nacos 多节点集群

这些方案在真正多机时有意义, 但在 **单机** 上更多只是:

- 占更多内存
- 占更多磁盘
- 增加更多端口/进程/故障面
- 不能解决“宿主机挂了”的根本问题

### 3.2 因此 64GB 单机推荐目标是“增强版单机HA”

推荐收敛成下面这套:

- MySQL: **1主1从保留**
- Redis: **1主1从 + Sentinel 保留**, 但建议增强为 **3 Sentinel**
- RocketMQ: **1 NS + 1 Broker 保留**, 暂不在单机内硬上主从
- ES: **业务 ES 单实例 + SW ES 单实例保留**
- Nacos: 单节点保留
- Canal: 单实例保留
- XXL-Job: 单节点保留
- 可观测性: 全保留, 但做生产级完善

这是“单机生产增强版”的上限最优解。

---

## 四、各组件是否建议升级

## 4.1 MySQL

### 当前
- 1主1从
- 对代码/配置兼容性最好
- 微服务已经按 `3306/3307` 读写分离写死了大量配置

### 是否建议升级
- **不建议改成更多主从/更多实例**
- **保留 1主1从**, 但提升质量

### 原因
1. 当前业务写入量对单主完全足够
2. 代码已经绑定主库/从库两套地址
3. 再拆分实例, 会明显增加配置与运维复杂度
4. 单机内多 MySQL 不能真正提升宿主级容灾

### 生产增强建议
1. 保留 1主1从
2. 从库恢复与复制告警必须补齐
3. 强制备份
4. slow log / deadlock / replication lag 全接入监控
5. 明确 root 只用于初始化, 运行态改专用账号

### 是否需要改代码/配置
- **中间件配置要改**, 代码基本不需要改
- 若改账号/密码/Nacos 鉴权, 微服务配置要同步

### 结论
- **MySQL 不做拓扑升级, 做运维与监控升级**

---

## 4.2 Redis

### 当前
- 1主1从 + 1个 Sentinel
- 非 Redis Cluster
- 微服务当前按 **Sentinel 模式** 使用: `master=mymaster`, `nodes=...:26379`

### 是否建议升级
- **不建议改成 Redis Cluster**
- **建议保留 Sentinel 路线**, 但升级为 **1主1从 + 3 Sentinel**

### 原因
1. 代码明确按 Sentinel 模式写了配置和行为
2. Redis Cluster 会改变客户端拓扑语义
3. 很多业务 key 设计、Lua、分布式锁、可能都默认单主语义
4. Cluster 改造会牵动代码、配置、客户端兼容, 不是纯中间件替换

### 生产增强建议
1. 保留单主写
2. 增加 Sentinel 到 3 个
3. 保持 `noeviction`
4. 补 Redis 慢查询/blocked clients/replication backlog/主从延迟告警
5. 审计所有关键 key TTL 与大 key 风险

### 是否需要改代码/配置
- **加 Sentinel 数量通常不用改代码**, 但要改 `sentinel.nodes`
- **改成 Redis Cluster 则需要代码/配置大改**, 不建议

### 结论
- **Redis 不升 Cluster, 只升 Sentinel 完整性**

---

## 4.3 RocketMQ

### 当前
- 1 个 NameServer
- 1 个 Broker
- Dashboard 单独部署
- 监控依赖 textfile collector

### 是否建议升级
- **不建议为了指标或“插件”升级 RocketMQ 版本**
- **不建议在单机里先上主从/多 broker**, 除非你明确要验证故障切换

### 原因
1. 当前业务客户端与 5.1.4 已对齐
2. 监控问题不靠升级 RocketMQ 解决
3. 单机上多 Broker/主从会明显增加磁盘、端口、内存和运维复杂度
4. 单机里即便做主从, 宿主机挂了也没意义

### 生产增强建议
1. 保持 5.1.4
2. Dashboard 固定 digest
3. textfile collector 继续保留
4. 补“生产级 RocketMQ 指标清单”
5. 严格校验 Topic / DLQ / Consumer Lag / Retry / 磁盘水位 / broker 可用性
6. `rocketmq_up` 假阳性问题要修
7. 增加采集成功分步指标与采集时间戳, 防止旧文件/半失败数据误导

### 是否需要改代码/配置
- 当前 **无需因为监控问题而改代码或升级 broker 版本**
- 若未来要上多 broker/主从, 微服务配置大概率要同步 namesrv/broker 可用性策略

### 结论
- **RocketMQ 保持单 broker + 5.1.4, 做监控质量升级**

---

## 4.4 Elasticsearch

### 当前
- 业务 ES 1 实例
- SkyWalking ES 1 实例
- 两套独立进程

### 是否建议升级
- **不建议在 64GB 单机里直接升成 3 节点业务 ES 集群**
- **建议保留双 ES 实例**, 但对业务 ES 做更强的容量和生命周期治理

### 原因
1. 单机三节点 ES 非真正 HA
2. ES 非常吃内存与 page cache
3. 后续微服务也要共机, ES 是最容易抢宿主内存的组件
4. 目前双 ES 实例已经是较重配置

### 生产增强建议
1. 保留双 ES 实例
2. 业务 ES 单节点副本设为 0
3. 补 ILM
4. 控制堆大小与宿主 page cache
5. 严格管理索引与日志保留

### 是否需要改代码/配置
- 中间件配置要改
- 业务代码基本不需要改

### 结论
- **ES 不升多节点, 做生命周期与容量治理**

---

## 4.5 Nacos

### 当前
- 单节点
- 作为注册中心 + 配置中心

### 是否建议升级
- **不建议单机内搞 Nacos 集群**

### 原因
1. 单机集群没有真正容灾价值
2. 增加数据库与端口复杂度
3. 当前主要问题根本不在节点数, 而在安全和配置治理

### 生产增强建议
1. 保持单节点
2. 明确配置导入与命名空间治理
3. 若部署环境有公网/多人共用风险, 再开启鉴权与改密
4. 当前阶段可仅依赖主机/安全组/内网隔离, 避免影响开发联调效率

### 结论
- **Nacos 保持单节点**
- **鉴权/改密属于可选生产增强项, 当前不列为首阶段阻塞**

---

## 4.6 Canal

### 当前
- 官方 `canal/canal-server:v1.1.7`
- 单实例 3 destination
- 依赖 JDK8

### 是否建议升级
- **不建议在单机里多开 Canal 实例**
- 保持当前形态即可

### 原因
1. 主要风险在 JDK 兼容和位点管理, 不在实例数
2. 过多实例只会放大排障成本

### 生产增强建议
1. 保留官方镜像
2. 固化 JDK8 路径
3. 重点监控 instance delay / 拉 binlog 状态 / MQ 发送状态

### 结论
- **Canal 保持单实例, 做运行态健康与监控增强**

---

## 五、64GB 下推荐的目标架构

## 5.1 推荐最终拓扑

### 保留现状但增强
- MySQL: 1主1从
- Redis: 1主1从 + **3 Sentinel**
- RocketMQ: 1 NS + 1 Broker + 1 Dashboard
- ES: 业务 ES 1 实例 + SW ES 1 实例
- Canal: 1 实例
- Nacos: 1 实例
- XXL-Job: 1 实例
- SkyWalking: OAP + UI
- Prometheus + Alertmanager + Grafana + VM
- Logstash + Kibana

### 不建议在当前单机场景就做的升级
- Redis Cluster 3主6从
- ES 三节点集群
- RocketMQ DLedger 三节点
- MySQL 更多副本
- Nacos 多节点

---

## 六、建议的中间件目标内存预算

> 目标: 给后续 15 个微服务留出足够空间, 同时避免中间件抢占宿主。

### 当前中间件 limit 总额
- 约 **22.81 GiB**

### 建议控制目标
- **中间件总 limit 控制在 24~28 GiB 内**
- **微服务总 JVM 预算控制在 20~24 GiB 内**
- **宿主与 page cache 预留 10~14 GiB**

### 一个较合理的预算区间
- MySQL 主: 2G
- MySQL 从: 1.5G
- Redis 主从+sentinel: 1G 以内
- RocketMQ 全套: 3G 左右
- 业务 ES: 2~3G
- SW ES: 2~3G
- Nacos + Sentinel + XXL: 2~3G
- SkyWalking OAP/UI: 2.5~3G
- Prometheus/VM/Grafana/Alertmanager/exporters: 2~3G
- Logstash + Kibana: 2.5~3G
- Canal: 1G

### 结论
- **64GB 足够**, 但只够做“增强版单机生产架构”, 不够舒服地承载“单机伪全集群”。

---

## 七、哪些升级会牵动代码/配置

## 7.1 几乎只改中间件/部署, 不太动业务代码

1. MySQL 1主1从保留, 只做备份/复制/告警增强
2. Redis Sentinel 扩到 3 Sentinel
3. RocketMQ 保持 5.1.4, 强化指标与告警
4. ES 做 ILM/副本策略/容量治理
5. Nacos 开鉴权与改密
6. Prometheus / Alertmanager / Grafana / VM 完善

## 7.2 会显著牵动代码/配置, 当前不建议

1. **Redis Cluster**
   - 微服务 Redis 配置、客户端模式、Lua/锁/Key 语义都可能受影响
2. **RocketMQ 多 broker/主从/集群大改**
   - 需要重新验证 topic、consumer、DLQ、补偿链路
3. **ES 多节点集群**
   - 中间件配置可改, 但资源成本太高
4. **MySQL 进一步分拆实例**
   - 连接串、Nacos、sharding 配置都会被牵动

---

## 八、生产级监控标准 vs 当前实现

## 8.1 MySQL

### 当前
- 主从 exporter 已有
- deadlock/slowlog 有脚本
- 复制问题文档里已有结论

### 距离生产级还差
1. 复制延迟告警闭环
2. 备份恢复演练
3. error log 采集策略
4. 慢查询落盘到日志系统或专门看板

### 判断
- **接近生产基础版, 但还不是完整生产级**

## 8.2 Redis

### 当前
- redis-exporter 已有
- noeviction 已启用

### 距离生产级还差
1. Sentinel 完整性(建议 3 Sentinel)
2. replication / failover 验证
3. 大 key / hot key / blocked clients / slowlog 体系

### 判断
- **基础版可用, 但 HA 和深度监控不足**

## 8.3 RocketMQ

### 当前
- Dashboard 有
- textfile 方案有
- Topic/DLQ/重投链路可用

### 距离生产级还差
1. `rocketmq_up` 假阳性修复
2. 更细粒度的 consumer group / topic lag 指标
3. broker 磁盘、写入失败、DLQ、retry 的统一看板与告警
4. 首次运行态验证完整闭环

### 判断
- **还不是生产级, 只是生产过渡版**

## 8.4 ES / Nacos / SkyWalking / Prometheus / Grafana

### 当前
- 组件基本齐全
- 但还有若干关键缺口

### 距离生产级还差
1. ES ILM
2. SkyWalking agent/OAP 版本一致性
3. OAP telemetry 指标接入验证
4. Kibana 可用性验证
5. Prometheus 指标质量与 Grafana 看板完整性
6. Alertmanager 通知闭环(可选)
7. Nacos 鉴权(可选)

### 判断
- **监控组件齐了, 但生产闭环还没齐**
- **其中 Alertmanager 通知闭环与 Nacos 鉴权属于可选增强项, 当前不阻塞开发联调**

---

## 九、建议的实施顺序

### 第一阶段: 不改业务拓扑, 先把“开发友好型生产底座”补齐
1. 补 MySQL 备份/恢复
2. 补 RocketMQ 指标质量
3. 补 ES ILM
4. 补 Kibana 可用性
5. 补 OAP telemetry 验证
6. 校正 Prometheus / Grafana 指标与看板质量
7. 保持 Nacos 无鉴权、Alertmanager 仅保留容器与占位配置, 不做真实通知出口接入

### 第二阶段: 做单机增强型 HA 改造
1. Redis Sentinel 扩到 3 个
2. 校验 Redis 主从切换
3. 校验 MySQL 主从恢复
4. 校验 RocketMQ / Canal / XXL / Nacos 启动顺序与恢复能力

### 第三阶段: 再评估是否需要更大拓扑改造
1. 是否真的需要 Redis Cluster
2. 是否真的需要 RocketMQ 主从
3. 是否真的需要 ES 多节点

我的判断是: **大概率不需要**。

---

## 十、最终建议

如果目标是:

- 单机 64GB
- 同机跑中间件 + 后续微服务
- 追求生产可用性与可观测性

那么最优策略是:

1. **MySQL 保持 1主1从**
2. **Redis 保持 Sentinel 模式, 但扩成 3 Sentinel**
3. **RocketMQ 保持 5.1.4 单 Broker, 强化监控而不是升级拓扑**
4. **ES 保持双实例, 不搞三节点集群**
5. **Nacos/Canal/XXL/SkyWalking 保持单实例逻辑**
6. **把监控/告警/备份/安全做满**

这才是这台 64GB 机器上更真实、也更能落地的“生产级”。
