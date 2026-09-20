# Redis 故障注入：降级语义矩阵 + 两处修复（2026-09-20）

> 方法：`docker pause` 主库/从库 + 直连服务（X-Internal-Call）观测语义；修复后全量发布并回归。
> 结论：**网关 401→503 语义修复**、**读路径无界挂起修复（1s commandTimeout + DB 回退）**；写路径降级原本就良好。

## 一、拓扑盘点（先厘清事实）

| 项 | 实测 |
|---|---|
| Redis 主/从 | **6380=master（可写）**；**6379=只读 replica**；6381（zone-b）未运行 |
| 容器命名 | **反了**：主库在 `my-xhs-redis-slave` 容器、从库在 `my-xhs-redis` 容器（运维陷阱） |
| Sentinel | 仅 1 个（26379），quorum=1；**配置文件仍写 `monitor ... 6379`，运行态已 failover 到 6380**（配置漂移） |
| 服务读路径 | 默认 `ReadFrom.MASTER`；部分服务的 6379 连接为 Lettuce 拓扑维护/zone 读副本用途 |

## 二、发现与修复

### ① 网关：Redis 故障被伪装成"Token 已被注销"（401→503）
- 现象：主库不可用时**全站 401「Token 已被注销」**（客户端会强制登出；与真吊销不可区分）。
- 根因：`GatewayAuthFilter` 黑名单查询异常 `onErrorResume` 返回 `blacklisted=TRUE`，与命中黑名单同文案；注释中"极小概率误拒"的风险评估也是错的。
- 修复：保持 fail-closed，但异常路径返回 **503「认证服务暂不可用，请稍后重试」**（客户端可重试）；`isBlacklisted` 不再吞异常。
- 验证：**主库暂停 → 503@3.0s；从库暂停 → 200（读主库）；恢复 → 200@0.02s** ✓

### ② 读路径无界挂起：自定义 Lettuce 工厂无 commandTimeout
- 现象：主库暂停时 product 详情/计数查询**挂起 8s+（无界）**——`CacheHelper` 虽实现了"Redis 不可用 → 查 DB"，但命令超时默认 **60s**，回退永远等不到。
- 修复：Nacos 公共配置 `spring.data.redis.timeout: 1000ms`；并在 common 的两个**自定义工厂**（业务 `defaultRedisConnectionFactory`、cache）显式 `.commandTimeout(1s)`（自动配置项对自定义工厂无效）。
- 验证（主库暂停，直连）：

| 路径 | 结果 |
|---|---|
| product 详情 | **200（DB 回退）**：首跳 11.2s（多层缓存/布隆/单飞锁各自 1s 超时叠加），**后续 0.01s** |
| counter 查询 | 200 @0.26s |
| cart 加购 | 200 @0.64s（Redis 写失败 → DB 回退） |
| notification 列表 | 200 @0.58s（走 DB） |
| comment 创建 | 200 @0.02s（限流 fail-open，DB 先写） |

- 待优化：product **首跳 11s 偏慢** → 建议 cache 读超时降到 200-300ms，或"首个 RedisUnavailableException 即本请求 fail-fast"，把降级首跳压到 1-2s。

## 三、服务级降级矩阵（主库暂停）

| 类型 | 行为 | 判定 |
|---|---|---|
| 认证（网关黑名单） | fail-closed → **503 可重试** | ✅（修复后） |
| 读缓存（product/counter） | 1s 超时 → DB 回退 | ✅（不再挂起） |
| 写缓存（cart） | 回退 DB | ✅ |
| 辅助（限流/计数缓存/通知列表） | fail-open / 走 DB | ✅ |

## 四、架构风险与建议
1. **认证链路 SPOF**：网关每请求校验黑名单（读 Redis），主库故障 = 全站不可用。建议：黑名单**本地缓存 + Redis pub/sub 变更广播**（或短期 TTL 负缓存），或 Redis HA 多副本。
2. **Sentinel 单点 + 命名颠倒 + 配置漂移**：仅 1 sentinel（自身无 HA）；容器名主从相反；sentinel.conf 与实际主库不一致。建议：3 sentinel + 统一命名规范 + 配置巡检项。
3. 读路径超时参数应纳入配置基线（本次已外置 `spring.data.redis.timeout`，可继续按服务调优）。

## 五、验证与回归
- 语义验证：见 §二表格；故障窗口外全部恢复。
- 回归（1s 超时生效后）：**E2E 30/30**、test-09 **16/16**、test-11 **12/12**、test-13 **9/9**。
- 证据：`docs/reports/e2e-business-chain-run-20260920-124959.json`

## 六、产物
- 代码：`GatewayAuthFilter`（503 语义）、`RedisConfig` / `RedisMultiSourceConfig`（commandTimeout）
- 配置：`deploy/docker/my-xhs-deploy-zip/config/nacos/my-xhs-common.yaml`（timeout 1000ms，已推送 Nacos）
