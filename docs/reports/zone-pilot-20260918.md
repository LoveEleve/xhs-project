# D1 双 Zone 试点报告（2026-09-18，修正版）

> 说明：本报告初版结论（“路由已生效 / RTO≈0.9s”）经复查**不成立**。本版记录错误原因、修正过程与最终实测数据。

## 一、目标与拓扑
- 目标：验证 zone 优先路由（同 zone 优先、zone 故障自动切走）与切换 RTO。
- 拓扑（单机仿真）：product 双实例 zone-a(19006)/zone-b(19026)；cart(19008) 消费者（zone-a）；Nacos 实例 metadata `zone` 打标。
- 关键配置：`myxhs.availability.zone.preference.enabled=true`、`...upstream.same-zone-min-available=1`（默认 5 对单实例/zone 不适用）、`myxhs.current.availability.zone`。

## 二、首轮结论为何错误（复盘）
1. **接线错误（根因）**：自定义 `ServiceInstanceListSupplier` 放在 default context（AutoConfiguration），LB 子 context 不采用 → 过滤器从未执行；DEBUG 日志与指标“双零”暴露了这一点。
2. **resolver 键不一致**：resolver 读 `myxhs.availability.zone`，实例 metadata 用标准键 `zone`。
3. **实例 zone 参数未生效**：product 19006 实际 metadata=`defaultZone`；早前 Nacos 看到的 zone-a 是旧注册残留。
4. **假 RTO**：`kill`（SIGTERM）触发优雅关闭，0.55s “恢复”实为垂死实例仍在服务；换 `kill -9` 后暴露真相（>45s 未恢复）。
5. **排障工具缺失**：当时无路由指标，只能靠对端计数反推。

## 三、修正与验证
### 3.1 接线修复
- 新增 `common/config/ZoneLoadBalancerConfig`：在 default context 用 `@LoadBalancerClients(defaultConfiguration=ZoneLoadBalancerConfiguration.class)` 注册（对齐既有 `LeastConnectionsLoadBalancerConfig` 模式），仅 preference.enabled=true 时生效。
- `ZoneLoadBalancerConfiguration` 从 `AutoConfiguration.imports` 移出，作为 **LB 子 context** 配置（`withBlockingDiscoveryClient` + `withCaching` + zone 过滤）。
- `ServiceInstanceZoneResolver`：标准键 `zone` 优先、兼容 `myxhs.availability.zone`（新增 4 个单测）。

### 3.2 路由可观测（新增）
- `ZoneRouteMetrics`：`myxhs_zone_route_total{zone,decision,reason}`（覆盖 9 类决策分支）与 `myxhs_zone_instances{zone,kind=total|same}`；3 个单测。

### 3.3 实测结果（双实例，cart zone-a）
| 场景 | 结果 |
|---|---|
| 同 zone 优先 | 日志 `Same zone 'zone-a' entities found: 1/2`；指标 `same_zone{reason=ok}=24`；24 次请求全部命中 zone-a（SKU 计数 48:0） |
| 无匹配 zone（cart=zone-x） | 日志 `No same zone 'zone-x' ...`；指标 `all{reason=no_same_zone}`；跨 zone 正常服务（zone-b 计数 48；平局让 LeastConnections 全落单边） |
| 故障切换（kill -9 zone-a，cache.ttl=5s） | 首次失败 0.56s；**Nacos 摘除 3.28s**；失败请求 10 次；**恢复 6.16s**（列表剔除死实例后 pass_through 走 zone-b，数据完整 price=199） |

### 3.4 失败尝试（避坑）
- `withHealthChecks()` 加入链后 LB 子 context 初始化异常（`AnnotationConfigApplicationContext` 异常，全部请求失败）→ 已回滚。健康检查/推送式 supplier 需另行方案。

## 四、发现
1. Nacos 2.x 对 kill -9 的实例摘除约 3.3s（gRPC 断连感知）；**主导 RTO 的是 LB 列表缓存 TTL**（默认 35s → RTO≈35s；5s → 6.16s）。zone 启用服务应显式设置 `spring.cloud.loadbalancer.cache.ttl=5s`。
2. `release-service.sh` 发布按模块杀进程：同模块多实例会被一并杀掉 → 双实例演练必须先发布、后起第二实例。
3. `LeastConnectionsLoadBalancer` 在 active=0 平局时选择倾斜（回退/无过滤场景会“全打一台”，不利于跨 zone 摊平流量）。
4. SIGTERM 优雅关闭会掩盖切换耗时，演练必须 `kill -9` 或确认端口关闭。

## 五、边界与后续
- 边界：单机仿真、无双 zone 网络延迟/分区；数据面（动态数据源/Redis zone 事件）未接入；RTO 为单次采样。
- 后续（D4）：重复演练取分布 + iptables 分区场景；健康检查/推送式 supplier；同 zone 命中率与跨 zone 流量告警。

## 六、问题修复（同日收口，全部实测）
1. **健康检查链修复**：根因 `withBlockingHealthChecks()` 需要 `RestTemplate` Bean（LB 子 context 没有）→ 在子配置内提供 2s 超时的 RestTemplate 并显式传入。随后发现默认聚合 `/actuator/health` 会因依赖抖动 flapping → 改用 `/actuator/health/liveness`。实测（health-check.interval=3s）：首次失败 0.35s、失败 8 次、**RTO 3.06s**，恢复后 20/20 请求稳定无抖动。
2. **LeastConnections 平局修复**：原严格 `<` 比较导致恒选列表首个实例（回退/无过滤场景“全打一台”）；改为并列最优中随机选择，并补 2 个单测（200 次采样覆盖双实例、活跃数高者必落选）。
3. **release 脚本多实例支持**：进程号落 `pids/<module>[-<instance>].pid`，停止只杀本实例；新增 `INSTANCE_ID`/`PORT_OVERRIDE` 启动第二实例（复用 current 版本，不动主实例软链/进程）。实测：发布 product 主实例时 zone-b 第二实例不受影响。
4. **RTO 口径合并**：默认 LB 缓存 35s → RTO≈35s；短 TTL(5s) → 6.16s；**health-check(liveness, 3s) → 3.06s**（推荐配置）。
