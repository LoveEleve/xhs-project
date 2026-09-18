# 第26题 | Zone 多活：路由、数据面与容灾演练

> 难度：★★★★★｜频率：★★★★☆｜区分度：高
> 关键词：同 zone 优先、健康检查摘除、RTO、双主同步 LWW、动态热切、仿真边界

## 问题
你们的多活怎么做的？zone 路由怎么实现？故障切换 RTO 多少？数据怎么同步？（可追问：Redis 双主怎么做？动态切 zone？）

## 面试可讲版（五段式）

**① 业界背景**
多活分两层：**流量面**（请求就近路由 + 故障切流）与**数据面**（每 zone 可写 + 跨 zone 同步 + 冲突解决）。流量面主流做法是"实例打 zone 标 + 消费者优先同 zone + 健康检查摘除"；数据面最难——双写必然冲突，工程上要么单写多读（简单但伪多活），要么异步双向同步 + LWW/CRDT（真多活但 RPO>0）。RTO 数据必须压出来，不能拍。

**② 项目选择**
- **流量面（Spring Cloud LB + 网关）**：
  - 实例注册打了 `zone` metadata；`ZonePreferenceFilter` 优先同 zone（min-available 阈值不足则回退全局）；
  - 接线坑：供应商 Bean 必须进 **LB 子 context**（`@LoadBalancerClients` + `defaultConfiguration`），放 default context 不生效；
  - 健康检查用 **liveness + 3s 间隔**（聚合 health 会因依赖抖动误摘）；
  - 网关（WebFlux）独立实现了一份反应式 zone LB；
- **数据面**：
  - MySQL：zone 感知数据源（读本 zone、写主库、从库故障降级 + 30s 探测恢复）；
  - Redis：客户端 `ReadFrom.REPLICA_PREFERRED`（读本 zone 副本、写主库、副本故障回主）；**服务端双主**（zone-a 6379 / zone-b 6381 双向同步：DUMP/RESTORE + LWW 时间戳 + 周期对账兜底）；
- **动态化**：`ZoneContext` 属性变更事件 → 数据源热切换 + LB 路由即时变化；提供内部令牌保护的 zone 热切端点（不重启）；
- **演练（RTO）**：进程 kill **1.31s / 3.25s**（两次采样）、iptables 网络分区 **4.79s**、回切 <5s；Redis 双主：单侧宕机不中断、恢复 ≤35s 对账追平。
- **收益（netem 跨区模拟）**：给 zone-b 注入 25ms/向（≈+100ms RTT），网关同区优先 ON vs OFF——吞吐 **+34%**（7,077 vs 5,278 RPS）、P99 **-41%**、平均延迟 **-49%**；未设网关 zone 时会静默 `invalid_zone`（路由全部实例）。

**③ 坑**
- **假切换**：进程 kill 后 0.55s"恢复"是假象——SIGTERM 优雅关闭仍在服务；必须 `kill -9` 或等端口关闭；
- **列表快照误导**：Nacos 断连检测 ~3.3s，但 LB 本地列表缓存 TTL 默认 35s → 期间流量仍打死亡实例；压测中改 TTL 5s 后 RTO 6.16s → 换 liveness 健康检查后 3.06s；
- **双主同步的删除/冲突坑**：对账 tie-break 曾把单侧存在的 key 误判为删除（已修"存在优先"）；事件通道断开无重连会静默失效（已加重连）；
- **zone 参数没进进程**：Nacos 里看到 zone-a 可能是"旧注册残留"，要核对进程参数/注册时间——一次误判就是这样造成的；
- **过度宣称**：单机仿真（同机双实例 + iptables）≠ 真跨机房；面试要主动说边界。

**④ 兜底**
- 流量面：健康检查摘除 + 跨 zone 回退（同 zone 不足自动全局）；
- 数据面：MySQL 从库故障降级/恢复；Redis 客户端副本故障回主、服务端单侧宕机不中断 + 对账追平；
- 冲突：LWW（时间戳）+ 存在优先 tie-break + 防回环（值相等跳过）；
- 演练：进程故障/网络分区两类注入，RTO/RPO 有报告；发布链路加 PID 校验（防"旧进程假成功"）。

**⑤ 话术**
> "多活我分流量面和数据面讲。流量面：实例打 zone 标，LB 优先同 zone、健康检查 3s liveness 摘除死实例，故障切换 RTO 1.3~4.8 秒、回切 <5 秒；网关是 WebFlux，独立实现了一份反应式 zone LB 验证 12/12 就近。数据面：MySQL 读本 zone、写主库、从库故障自动降级；Redis 客户端读副本写主库，服务端双主双向同步——DUMP/RESTORE 原子复制 + LWW 冲突解决 + 30 秒对账兜底，单侧宕机不中断、恢复 35 秒内追平。要坦白的是：这是单机仿真，跨机房延迟/脑裂没模拟。"

## 追问与参考回答
**追问1：为什么不用双写主库（真双主）？** 应用层双写会冲突且难回滚；我们用"写主库 + 异步双向同步"——可用性优先、RPO>0；生产级真双主需要 CRDT/Redis Enterprise（成本与复杂度不匹配）。
**追问2：zone 路由用 Ribbon 还是 SCLB？** Spring Cloud LoadBalancer（新），自定义 Supplier 做 zone 过滤；网关是反应式栈单独实现。
**追问3：RTO 怎么压出来的？** `kill -9` 杀首选 zone 实例，客户端 0.3s 粒度轮询到首次成功；网络分区用 iptables DROP 模拟（比进程崩溃更慢：连接超时 vs RST）。
**追问4：同步冲突怎么定胜负？** LWW——每 key 影子时间戳，新者覆盖；同时间戳/无时间戳用确定性 tie-break（存在优先、双侧存在 zone-a 优先）。
**追问5：切 zone 要重启吗？** 不用——`ZoneContext` 发属性变更事件，数据源热切换（等活跃连接归零防事务中断）+ LB 路由即时生效；有内部令牌保护的端点演示过。

## 发散追问地图（横向）
- 多活架构：同城双活/异地多活、单元化（Set）、GSLB/DNS 调度、Mesh 灰度。
- 数据同步：MySQL 主从/双主/MGR、Redis 复制/双主/CRDT、Kafka 跨集群（MirrorMaker）。
- 冲突解决：LWW/向量时钟/CRDT、幂等操作合并。
- 容灾指标：RTO/RPO、演练方法（故障注入/混沌）、SLO 与错误预算。
- 边界：脑裂、数据回环、同步风暴、跨机房延迟预算。

## 面试官评分点
**高级开发级**：能讲 zone 路由原理与故障切换；知道数据面要同步。
**架构师加分**：RTO 分布（进程 vs 分区）有数据；"假切换/列表快照"这类排障经历；双主 LWW + 对账兜底设计；主动说仿真边界（不夸大）。
**危险信号**：说"多活没问题"却给不出 RTO；把单写多读说成双活；同步无对账/无冲突策略。

## 本项目真实证据
- 流量面：`ZonePreferenceFilter`/`ZoneLoadBalancerConfiguration`（子 context 接线）、`ZoneRouteMetrics`（决策指标）；网关 `gateway/zone/*`；报告 `docs/reports/zone-pilot-20260918.md`（240/240、RTO 3.06/4.79s）、`gateway-zone-pilot-20260918.md`（12/12、5.5s）、`d4-zone-drills-20260918.md`（RTO 分布）；
- 数据面：`ReadWriteRoutingDataSource`（zone 感知 + 从库恢复修复）、`ZoneRedisReadFromResolver`、`scripts/zone-redis-sync.py`（双主同步 LWW+对账）、报告 `mysql-zone-datasource-20260918.md`、`redis-zone-drill-20260918.md`、`redis-server-multi-active-20260918.md`（双主/冲突/故障/恢复）；
- 动态/自动化：`ZoneAdminController`（热切）、`ZoneEnvironmentPostProcessor`（env/文件/网段自动发现）、`ZonePropagationFilter/Interceptor`（X-Zone 传播）；
- 发布加固：`release-service.sh`（PID 校验 + 端口占用清理 + 多实例）。

## 版本与来源
Spring Cloud LoadBalancer zone preference 模式；microsphere-multiactive（zone locator/attachment 思路参考，对比见 `docs/design/multi-active-vs-microsphere.md`）；本项目多活系列报告。

## 真实性说明
路由/切换/双主同步/动态热切均有实测报告与代码；全部为**单机仿真**（双实例 + iptables 模拟分区），未模拟跨机房网络与脑裂，已在报告与本题明确。
