# 多活架构概述与选型

> 跨服务专题 | 开发阶段：Phase-7 | 状态：⏳ 待开发

## 专题概要

本专题从理论出发，分析 CAP/BASE 在多活场景的约束，对比同城双活、异地多活、单元化架构三种方案的优劣，最终为 my-xhs 项目选定**同城双活**方案，并定义多活架构下的流量路由策略、数据一致性级别、故障切换 SLA 等核心规范。

## 涉及服务

- 全部服务（架构约束对象：双注册、区域感知、数据双写）
- my-xhs-common（组件提供方：RegionContext、RegionConstant、RegionHelper 等）
- my-xhs-gateway（流量入口：多活路由决策、区域标记注入、故障流量切换）

## 核心内容

| 维度 | 内容 |
|------|------|
| 理论体系 | CAP 三选二 → 多活是 AP 系统；BASE 最终一致性补偿 |
| 方案选型 | 同城双活（RT<3ms，日活<1000万）✅ vs 异地多活 vs 单元化 |
| 区域标记 | `X-Region-Tag`（region-a/region-b）、`X-Region-Route`（prefer-local/force-local/force-remote） |
| 一致性级别 | MySQL 最终一致（<500ms）、Redis 最终一致（<1s）、ES 最终一致（<3s）、Nacos 配置强一致 |
| 故障切换 SLA | 单机房宕机 60s 内切换、MySQL 主库宕机 30s 内切换、Redis 30s 内故障转移 |

## 重点覆盖

- CAP/BASE 理论在多活场景的应用
- 三种多活方案详细对比（距离/延迟/成本/适用体量）
- 同城双活选型理由与演进路线
- 区域标记规范与全链路透传设计
- 数据一致性级别选型与冲突解决策略
- 故障切换 SLA 定义

## 面试高频问题

- 为什么多活架构本质是 AP 系统？请结合 CAP 理论说明。
- 同城双活 vs 异地多活，你如何选型？
- 多活架构下如何保证数据一致性？
- 什么是单元化架构？什么场景需要？

## 补充：多活架构演进路线图

> 当前文档只说了"建议渐进式演进"，但缺少具体的里程碑和验收标准。以下给出5个阶段的详细路线图，面试中可据此清晰描述多活的搭建过程。

### Phase 7.1（1-2周）：基础设施双机房部署

**目标**：两个机房的 MySQL、Redis、Nacos 基础设施全部就位

| 步骤 | 内容 | 验收标准 |
|------|------|---------|
| MySQL 主主双向复制 | 配置 `auto-increment-increment=2`、`auto-increment-offset=1/2`、`server-id` | 双向复制正常，`SHOW SLAVE STATUS` 的 `Seconds_Behind_Master < 1s` |
| Redis Sentinel 跨机房 | Master A + Slave B，6个 Sentinel 节点跨机房 | 故障转移测试通过，`INFO Replication` 正常 |
| Nacos 双集群 | 两集群各3节点 Raft，GitOps 配置同步脚本 | 两集群配置 MD5 一致，服务可双注册 |

**里程碑验收**：基础设施双机房可用，数据同步正常

### Phase 7.2（2-3周）：服务双注册 + 区域路由

**目标**：所有服务双注册，请求可同区域优先路由

| 步骤 | 内容 | 验收标准 |
|------|------|---------|
| 服务双注册 | `NacosMultiRegistry` 同时注册到两个 Nacos 集群 | 两个 Nacos 控制台均可见实例 |
| RegionContext 体系 | `RegionContext` + `RegionConstant` + ThreadLocal/SLF4j MDC | 区域标记全链路透传 |
| Gateway RegionRouteFilter | 注入 `X-Region-Tag`，路由到同区域实例 | curl 验证请求路由到正确机房 |
| RegionLoadBalancer | `PREFER_LOCAL` 同区域优先 | 同区域实例优先选择，降级到对端 |

**里程碑验收**：请求可同区域优先路由，降级正常

### Phase 7.3（2-3周）：数据层多活 + Canal 双向同步

**目标**：MySQL 双向同步 + Canal 缓存/ES 同步全部就位

| 步骤 | 内容 | 验收标准 |
|------|------|---------|
| DynamicRegionDataSource | 基于 `RegionContext` 动态切换数据源 | 写操作 force-local，读操作 prefer-local |
| Canal 双向同步搭建 | Canal A/B 各监听本机房 Binlog → MQ → 对端消费 | Canal 同步延迟 < 3s，循环消费正确过滤 |
| Redis RegionRedisTemplate | 写双写 + 读本地 + 故障降级读对端 | 缓存一致性验证通过 |
| ES CCR 跨机房同步 | Leader 索引 → Follower 索引自动跟随 | ES 搜索结果双机房一致 |

**里程碑验收**：MySQL 双向同步 < 500ms，Canal 同步 < 3s

### Phase 7.4（1-2周）：动态组件 + 流量调度 + 故障切换

**目标**：业务代码对多活无感知，故障可自动切换

| 步骤 | 内容 | 验收标准 |
|------|------|---------|
| DynamicJdbcComponent | 写操作自动 force-local，与 ShardingSphere 兼容 | 业务代码无需修改即可多活 |
| DynamicBeanComponent | `@RegionBean` 注解按区域选择实现 | 不同机房使用不同策略 |
| TrafficScheduler | Nginx 动态权重 + 灰度恢复 | 流量比例精确控制 |
| 故障切换 SOP | 检测 → 告警 → 确认 → 切换 → 检查 → 恢复 | 机房 A 故障后 60s 内切换，数据丢失 < 1s |

**里程碑验收**：机房 A 故障后 60s 内切换，数据丢失 < 1s

### Phase 7.5（持续）：数据对账 + 监控 + 混沌演练

**目标**：数据最终一致性可验证，运维可观测

| 步骤 | 内容 | 验收标准 |
|------|------|---------|
| 三层对账体系 | 实时对账（Canal 侧）+ 准实时对账（定时任务）+ 全量对账（T+1） | 对账差异可发现、可修复 |
| 多活监控面板 | Prometheus + Grafana 多活指标面板 | 关键指标可视化，告警配置完成 |
| 混沌演练 | MySQL 宕机、Redis 宕机、网络隔离注入 | 定期演练通过，SOP 持续优化 |

**里程碑验收**：对账体系运行正常，混沌演练定期执行

---

---

**相关专题**：
- ➡️ [43-注册中心与发现多活](../43-registry-and-discovery-multi-active/README.md)
- ➡️ [44-网关与负载均衡多活](../44-gateway-and-loadbalancer-multi-active/README.md)
- ➡️ [45-数据层多活](../45-data-layer-multi-active/README.md)
- ➡️ [46-动态组件与流量调度](../46-dynamic-component-and-traffic-scheduling/README.md)

> 📋 详细文档请在开发时基于 [_template.md](../../_template.md) 填写完整内容