# D3-9/10 动态 JDBC 与动态 Spring 试点报告（2026-09-18）

## 一、D3-9 动态 JDBC 组件多活
### 实现
- `DynamicZoneDataSourceConfig`（common，默认关闭）：将 master/slave 按 zone 组装为 `DynamicDataSource`：
  - zone-a → master（本地主库）；zone-b → 独立只读副本 Hikari（`initializationFailTimeout=-1`，从库故障不阻塞启动）
  - 开启时 `routingDataSource` 自动退让（`@ConditionalOnProperty`），避免双 `@Primary`
- `DynamicDataSource`（已有 402 行实现）：监听 `ZoneContext` 属性变更热切换代理目标；**切换前等待活跃连接归零（最长 30s）**，保护进行中 TCC 事务；超时强制切换并告警（TCC Cancel 兜底）。

### 实测（content 试点，100 次评论列表请求）
| 状态 | master Com_select 增量 | slave Com_select 增量 |
|---|---|---|
| zone-a | **+207** | +6（噪声） |
| 运行时热切到 zone-b | +73（噪声） | **+206** |

- 切换日志：`DataSource switched: zone 'zone-b' ...`；**无需重启**，切换即时生效。
- 单测 4 项（切换生效/未知 zone 回退/活跃连接计数/defaultZone 别名）。

## 二、D3-10 动态 Spring 组件多活
### 实现
- `ZoneAdminController`（common，默认关闭 + 内部令牌保护）：
  - `GET /internal/zone`：当前 zone / 开关状态
  - `POST /internal/zone/switch?zone=x`：运行时热切 zone，触发 `ZoneContext` PropertyChange → DynamicDataSource 热切换 + LB zone 优先立即生效
- 未认证请求 401 拒绝；空白 zone 拒绝；单测 3 项。

### 实测（cart 试点，product 双 zone 实例）
| 状态 | 命中 zone-a product | 命中 zone-b product |
|---|---|---|
| zone-a（初始） | **+40** | +0 |
| 运行时热切 zone-b | +0 | **+40** |
| 再切回 zone-a | 正常回切 | — |

- **不重启、不改配置**，LB 路由随 zone 热切换；切换即时生效。

## 三、边界
- 单机仿真；DynamicDataSource 的 zone-b 指向只读副本（真多活应为各 zone 独立主库）；
- TCC 等待逻辑有单测覆盖，未做真实长事务并发切换压测；
- 管理端点默认关闭，开启需内部令牌。
