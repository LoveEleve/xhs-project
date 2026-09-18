# D3-5/6 MySQL 双 Zone 仿真 + Zone 感知数据源试点报告（2026-09-18）

## 一、目标与拓扑
- 目标：zone 感知读写路由（读就近、写主库、跨 zone 兜底）+ 从库故障降级/自动恢复。
- 拓扑（单机仿真）：master 3306 = zone-a（兼"zone-a 本地库"语义）；slave 3307 = zone-b；试点服务 content(19002)。
- 配置：`myxhs.availability.zone.datasource.enabled=true`、`...master-zone=zone-a`、`...slave-zone=zone-b`（默认关闭，不影响未开启服务）。

## 二、路由策略
| 当前 zone | 读（SELECT） | 写 |
|---|---|---|
| 未开启 / 未知 / defaultZone | 从库（原语义） | 主库 |
| zone-a（master-zone） | **主库（本地）** | 主库 |
| zone-b（slave-zone） | **从库（本地）** | 主库（跨 zone，仿真边界） |

## 三、实测结果（MySQL Com_select 口径）
| 场景 | 结果 |
|---|---|
| 基线（未开启，defaultZone） | 30 次读：slave +61 |
| zone-a + 开启 | 30 次读：**master +61** → 读本地 |
| zone-b + 开启 | 30 次读：**slave +61**，200=30/30 → 读本地 |
| 停从库（zone-b） | 15/15 请求 200，master 承接（日志 `从库不可用，降级到主库`） |
| 从库恢复 | ~40s 自动回切（30s 探测），日志 `从库已恢复`，slave Com_select 恢复增长 |
| 从库不可达时发布 | 修复后**发布成功**（health 200/13ms），读降级主库（8/10，前 2 个为探测窗口） |

## 四、修复与新增
1. **从库永不自动恢复（既有 bug）**：`tryRecoverSlave` 仅在主库连接失败时调用 → 从库恢复后不回切。修复：SLAVE 读路径按 30s 间隔主动探测；新增回归测试（故障降级→恢复回切）。
2. **从库宕机阻塞发布**：① 从库池 `initializationFailTimeout(-1)`；② 从库数据源内部化（不注册为 Bean）；③ 12 个读写分离服务统一加 `management.health.db.ignore-routing-data-sources=true`（健康检查不再枚举路由数据源目标；主库 Bean 仍单独体检）。
3. **Zone 感知数据源**：`ReadWriteRoutingDataSource` 增加 zone 优先逻辑 + 配置接线（默认关）；4 个单测。

## 五、发现与方法论
- Hikari 池指标名（HikariPool-1/2）跨进程重启不稳定（静态计数器）→ 归因务必用 MySQL 侧 `Com_select` 等外部口径。
- 从库骤然宕机时前 1-2 个请求可能失败（在途连接 + 首次探测窗口），随后进入降级；零失败需配合重试策略（未做）。

## 六、边界
- 单机仿真、无双 zone 网络延迟/分区；master 兼作 zone-a 本地读库（生产应为各 zone 独立从库）；
- zone-b 写入仍跨 zone 到主库；复制拓扑与 zone 无关；未做双写/冲突处理（真多活超出本期）。
