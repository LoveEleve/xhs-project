# Zone 多活能力启用清单（2026-09-18）

> 默认全关。按需开启，开启前逐项核对；配套演练见 `docs/reports/zone-pilot-20260918.md` 与 `docs/reports/mysql-zone-datasource-20260918.md`。

## 一、LB Zone 优先（消费者侧）
| 配置 | 值 | 说明 |
|---|---|---|
| `myxhs.availability.zone.preference.enabled` | true | 总开关 |
| `myxhs.availability.zone.preference.upstream.same-zone-min-available` | 1（试点）/按副本数 | 默认 5，单实例/zone 时必须调小否则优先逻辑失效 |
| `myxhs.current.availability.zone` | 本机 zone | 数据源路由同样读取 |
| `spring.cloud.loadbalancer.health-check.interval` | 3s | **必需**：死实例摘除与回切速度 |
| `spring.cloud.loadbalancer.health-check.path.default` | `/actuator/health/liveness` | **必需**：聚合 health 会因依赖抖动 flapping |
| 实例注册 metadata `zone` | `-Dspring.cloud.nacos.discovery.metadata.zone=<zone>` | 未打标则走 legacy |

验证：指标 `myxhs_zone_route_total{decision,reason}`、`myxhs_zone_instances{kind}`；kill -9 首选 zone 实例期望 RTO≈3-6s。

## 二、数据源 Zone 感知（服务侧）
| 配置 | 值 |
|---|---|
| `myxhs.availability.zone.datasource.enabled` | true |
| `myxhs.availability.zone.datasource.master-zone` | 主库所在 zone |
| `myxhs.availability.zone.datasource.slave-zone` | 从库所在 zone |

- 读就近：master-zone 读主库（本地）、slave-zone 读从库（本地）；写始终主库；本地不可用自动跨 zone 降级。
- 从库恢复：读路径 30s 间隔探测，恢复后自动回切（日志 `从库已恢复`）。
- `management.health.db.ignore-routing-data-sources=true` 已在 12 个读写分离服务配置（**待重启生效**），保证从库宕机不阻塞发布。

## 三、演练检查
1. kill -9 首选 zone 实例：验证 LB 摘除+跨 zone 切换（RTO）。
2. `docker stop my-xhs-mysql-slave`：验证读降级主库 + 恢复回切（≤40s）。
3. 从库不可达时发布：验证健康检查通过（routing 目标不再枚举）。

## 四、残余风险
- 11 个服务尚未重启加载 ignore-routing 属性（重启后生效，属性本身只放宽健康判定，无行为风险）。
- 从库恢复探测依赖读请求触发（无读流量则不回切）。
- master-zone 强制读主库是单机仿真语义；生产应为各 zone 独立从库。
