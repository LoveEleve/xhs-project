# P0 盘点：自研 Zone 多活骨架审计（2026-09-17）

> 结论：**路由层已完整实现并接线**（microsphere 同款设计），缺口集中在"启用、观测、演练、数据面"。
> 参考代码已克隆至 `/data2/reference/microsphere-multiactive`（仅作对照，不作为依赖）。

## 一、存量资产（my-xhs-common/zone，17 类 / 1736 行）

| 层 | 类 | 状态 |
|---|---|---|
| 上下文 | `ZoneContext`（单例 + PropertyChange 监听）、`ZoneProperties`、`ZoneConstants` | ✅ 可用；zone 解析：`-Dmyxhs.current.availability.zone` → `spring.cloud.nacos.discovery.metadata.zone` → defaultZone |
| 路由算法 | `ZonePreferenceFilter`（10 步：禁用 zone → 上游就绪% → 同 zone 最小可用 → 同 zone 优先 → 全量兜底） | ✅ 可用 |
| LB 接线 | `ZoneLoadBalancerConfiguration`：Reactive/Blocking 两套 `ServiceInstanceListSupplier`（discovery+caching+zone 过滤） | ✅ 已接线，**需显式开关** `myxhs.availability.zone.preference.enabled=true` |
| 数据面 | `DynamicDataSource`（zone 热切换代理，活跃连接计数 + TCC 事务安全 P1-10） | ⚠️ 类完整，**未接入任何服务** |
| Redis | `RedisTemplateWrapper` + `InterceptingRedisConnectionInvocationHandler` + `EventPublishingRedisCommandInterceptor`（写命令成功后发 `RedisCommandEvent`，预留跨 zone 同步/审计） | ⚠️ 默认关闭（`myxhs.redis.interceptor.enabled=false`），**事件消费端未实现** |
| 注册 | 各服务 Nacos metadata `zone: ${MYXHS_ZONE:defaultZone}` | ✅ 已内置（当前全部 defaultZone） |

## 二、缺口清单（P1 目标）

1. **启用**：服务未开 zone preference（默认 false）；实例未打 zone 标签（全部 defaultZone）。
2. **观测**：路由命中（同 zone）与兜底（跨 zone）无日志/指标 → 无法证明"路由真的生效"。
3. **演练工具**：缺"双 zone 实例启动脚本 + zone 故障注入（iptables/kill）+ RTO 采集"。
4. **数据面（P2）**：`DynamicDataSource` 未接线（需按 zone 定义多数据源 + 注册）；Redis `RedisCommandEvent` 消费端未实现（跨 zone 同步/回放）。
5. **locator**：`myxhs.availability.zone.locator.fast-fail/timeout` 常量存在，实现未见到（待补或删除口径）。

## 三、P1 试点方案（product-service 双实例 + cart 消费）

| 步 | 动作 | 验收 |
|---|---|---|
| 1 | 补观测：`ZonePreferenceServiceInstanceListSupplier` 加同 zone/兜底计数（Micrometer `myxhs_zone_route_total{caller,target,hit}`）+ DEBUG 日志 | 指标可查 |
| 2 | 起第二实例：product-service@19026，`MYXHS_ZONE=zone-b`；原 19006 打 `MYXHS_ZONE=zone-a`（重启） | Nacos 元数据可见双 zone |
| 3 | cart 开 preference + `MYXHS_ZONE=zone-a` 重启 | 日志/指标显示命中 zone-a |
| 4 | 注入故障：kill zone-a 实例（或 iptables 隔离） | 自动切 zone-b，业务 200；记录 RTO |
| 5 | 恢复 zone-a → 回切验证 | 流量回到 zone-a；记录回切行为 |

## 四、与 microsphere 的关系（决策）

- 我们的常量/算法与其 `microsphere-multiactive-*` 高度同构（`*.availability.zone`、upstream ready %、same-zone min available、disabled zone）——**说明自研骨架方向正确，继续自研**。
- 不引入其依赖的理由：① 能力已覆盖；② 避免版本绑定与"黑盒化"；③ 面试叙事"自研 + 对照开源"强于"引了个库"。
- 借鉴项：locator fast-fail/timeout 语义、zone 就绪探针实现方式（按需抄设计，不抄依赖）。
