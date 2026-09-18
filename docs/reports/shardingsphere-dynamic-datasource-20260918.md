# ShardingSphere × 动态数据源融合 Demo（2026-09-18）

> 目标：证明「ShardingSphere 5.x 分片」与「动态 JDBC 数据源」可在同一应用并存/隔离，
> 支持运行时切换（同 area 优先 / 故障转移 的 JDBC 侧能力）。

## 一、实现

- 应用：order 服务（既有 ShardingSphere 4 库×4 表 + 映射表/支付表独立数据源）
- 新增 `ZoneAwareMappingDataSourceConfig`（开关 `order.mapping.zone-routing.enabled=true`）：
  - `mappingMasterDataSource`（3306）/ `mappingSlaveDataSource`（3307，URL 由 master 派生）
  - `mappingDataSource` = `AbstractRoutingDataSource`（运行时可切换 master/slave）
  - 写路径仍走 master（原 `MappingDataSourceConfig` 在开关开启时自动退让，避免同名 Bean 冲突）
- 新增内部端点 `/api/order/internal/mapping-zone`（内部令牌保护）：
  - `GET`：查看当前目标 + `SELECT @@server_id`（1=master / 2=slave）+ 映射表行数
  - `POST /switch?target=master|slave`：运行时切换

## 二、验证结果

| 步骤 | 结果 |
|---|---|
| 默认（master） | `target=master, server_id=1, mappingRows=74` |
| 切换 slave | `target=slave, server_id=2, mappingRows=74`（从库数据一致性 ✓） |
| 切回 master | `target=master, server_id=1` |
| **分片路径不受影响** | 全链路下单+支付 `order-flow.sh 1` → 1/1 成功（ShardingSphere 正常路由） |

## 三、结论与边界

- **并存验证通过**：同一 order 应用内，分片表走 ShardingSphere、非分片映射表走可动态切换的数据源；
- 切换为**只读场景**设计（slave 只读）；写路径应保持 master（Demo 中切回后验证写入）；
- 状态为**进程内内存态**（演示用）；生产可接配置中心/管理面下发（同 Zone 热切机制）；
- 与内容服务的 `DynamicZoneDataSourceConfig` 机制同源（前者管主数据源、本 Demo 管独立数据源）。

## 四、复现（若环境重启）

```bash
JVM_EXTRA="-Dorder.mapping.zone-routing.enabled=true" bash scripts/release-service.sh order
curl -H "X-Internal-Call: $INTERNAL_TOKEN" http://localhost:19011/api/order/internal/mapping-zone
curl -X POST -H "X-Internal-Call: $INTERNAL_TOKEN" "http://localhost:19011/api/order/internal/mapping-zone/switch?target=slave"
```
