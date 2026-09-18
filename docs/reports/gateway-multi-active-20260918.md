# D3-4 网关多活试点报告（2026-09-18）

## 一、背景与实现
- 网关是独立 WebFlux 实现（**不依赖 common**），因此 zone LB 采用网关内精简实现：
  - `GatewayZoneLoadBalancerRegistrar`：`@LoadBalancerClients(defaultConfiguration=...)`，仅 `myxhs.availability.zone.preference.enabled=true` 时注册
  - `GatewayZoneLoadBalancerConfiguration`：LB 子 context 内创建（`loadbalancer.client.name` 条件），避免 default context 孤儿 Bean
  - `ZonePreferenceFilter`：同 zone 优先（按实例 metadata `zone`），同 zone 不足/不存在回退全部
  - `ZonePreferenceServiceInstanceListSupplier`：反应式包装（`withDiscoveryClient+withCaching`）
  - 指标：`myxhs_zone_route_total{zone,decision,reason}`、`myxhs_zone_instances{kind}`

## 二、实测结果（content 双实例：19002 zone-a / 19027 zone-b；网关 zone-a）
| 场景 | 结果 |
|---|---|
| 同 zone 路由 | 经网关 12 次评论列表：**zone-a +24 命中 / zone-b 0**；网关指标 `same_zone=12`、`instances{total=2,same=1}` |
| 故障切换（kill -9 zone-a） | 首次失败 0.35s；失败 15 次；**恢复 5.54s**；zone-b 承接（缓存 TTL=5s 为主要影响项） |

## 三、过程中的坑
1. **重复实现冲突**：先建 `gateway.lb`、后建 `gateway.zone` 两套同名配置 → 组件扫描 bean 名冲突，网关启动失败；已删除重复包（保留 zone 版）。
2. **鉴权白名单误区**：`/api/product/sku/**` 只在 **HMAC 白名单**，JWT 仍拦截（401）；改用 JWT 白名单内的 `/api/comment/list/**` 做无损验证。
3. 演练曾留下 19022/19026/19027 残留实例：已全部清理，基线恢复为单实例、无 zone 参数。

## 四、边界
- 单机仿真，无双 zone 网络延迟；未做网关按"请求来源 zone"路由（当前按网关实例所在 zone 决策）；
- 反应式侧未接健康检查，摘除依赖 Nacos + 缓存 TTL。
