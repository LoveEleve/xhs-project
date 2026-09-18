# D3-4 网关 Zone 多活试点报告（2026-09-18）

## 一、目标与实现
- 目标：网关（WebFlux 反应式）侧 zone 就近转发 + zone 故障切流；网关**不依赖 common**，故独立实现一份精简 zone LB。
- 实现（`my-xhs-gateway/.../zone/`）：
  - `GatewayZoneLoadBalancerRegistrar`：`@LoadBalancerClients(defaultConfiguration=...)` 注册（开关 `myxhs.availability.zone.preference.enabled=true` 时生效）。
  - `GatewayZoneLoadBalancerConfiguration`：LB 子 context 内创建 filter + 反应式 Supplier（`withDiscoveryClient().withCaching()`）；Bean 以 `loadbalancer.client.name` 存在为条件，避免 default context 孤儿 Bean。
  - `ZonePreferenceFilter`：同 zone 优先、同 zone 不足/不存在回退全部；指标 `myxhs_zone_route_total{zone,decision,reason}` 与 `myxhs_zone_instances{kind}`。
- 默认关闭，不影响现状。

## 二、实测结果（content 双实例：19002 zone-a / 19027 zone-b；网关 zone-a + cache.ttl=5s）
| 场景 | 结果 |
|---|---|
| 就近路由 | 网关 `/api/comment/list/**` 12 次：**zone-a(19002) +24，zone-b(19027) +0**；网关指标 `same_zone=24`，`instances: total=2/same=1` |
| 故障切换（kill -9 zone-a content） | 首次失败 0.35s、失败 15 次、**恢复 5.54s**；恢复后由 zone-b 承接（增量 +2 起） |

## 三、过程中发现并修复
1. **网关启动失败（Config 类重复）**：两套同名 zone 配置（`gateway.lb` 与 `gateway.zone`）导致 bean 名冲突 → 删除旧版，保留带指标/子 context 防护版本。
2. **`nohup setsid java &` 的 `$!` 不可靠**（setsid 可能 fork），导致 PID 校验误判/误回滚 → 启动后以**端口实际监听者**回写 pid 文件；复测 15 服务 PID 全部吻合。
3. **冷启动窗口**：product 初始化 DLQ 监控等 >40s，健康检查窗口 40s 会误判回滚 → 放宽到 60s。
4. **白名单认知修正**：`/api/product/sku/**` 仅 HMAC 白名单（仍需 JWT）；演练改用 JWT 白名单路径 `/api/comment/list/**`。

## 四、边界
- 单机仿真；网关单实例（多网关实例 + 各自就近未做）。
- 网关侧仅缓存（无健康检查）：RTO 5.5s 由 Nacos 摘除(~3.3s) + cache TTL(5s) 决定。
- 未接网关自身的 zone 自动发现/传播（网关独立栈，后续按需）。
