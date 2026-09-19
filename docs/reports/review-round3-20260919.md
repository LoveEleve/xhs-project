# Review 第三轮（2026-09-19）：Nacos 配置对照 + 缓存 key 一致性审计

> 换面：配置中心全量对照（本地/Nacos 漂移、孤儿配置）与 Redis key 规范（重复/前缀/跨服务撞键/TTL）。

## 一、Nacos 配置全量对照

- Nacos `my-xhs` 命名空间共 4 个配置：`my-xhs-common.yaml`、`my-xhs-gateway.yaml`、`my-xhs-redis.yaml`、`my-xhs-gateway-sentinel-flow.json`。
- 前三份与仓库文件**逐字节一致**（无漂移）✓；Sentinel 规则为运行期动态制品（Nacos 为准）。
- 各服务 `spring.config.import`：gateway 导入 common+gateway，其余 14 服务导入 common ✓。

### 发现并修复：孤儿配置（假外置陷阱）
- **`my-xhs-redis.yaml` 无任何服务 import**（三个脚本/文档以外无人使用）→ 运维改它**不会生效**，且内容与 common.yaml（密码/端口）+各服务本地 yml（host）三处重复。
- 处置（清理为单一事实源）：
  1. 删除 Nacos 配置（现余 3 个）；
  2. 删除仓库两份副本（`config/nacos/` 与 `deploy/.../config/nacos/`）；
  3. 更新两份 `DEPLOY-README.md`（说明 Redis/DB 以本地 yml + common.yaml 为准）；
  4. `setup-ip.sh` 移除该文件的备份与 replact_ip 调用；
  5. `04-nacos-config-seed.sql` 移除该配置的 `config_info` 播种行（`his_config_info` 历史行为惰性数据，保留）；
  6. `my-xhs-notification` yml 注释从 `（my-xhs-redis.yaml）` 修正为 `（my-xhs-common.yaml）`。

## 二、Redis key 一致性审计

### 常量层（RedisKeyConstants）
- **52 个常量：无重复值、全部带 `myxhs:` 项目前缀** ✓（对比运行态确认无命名漂移）。

### 幂等命名空间（跨服务撞键检查）
- 构造：`msg:idempotent:{bizType}:{bizId}`（`MessageIdempotentHelper`）与 `msg:idempotent:{prefix}:{bizKey}`（`IdempotentMessageAspect`）。
- 5 个消费者 BIZ_TYPE 全唯一：`myxhs:notification:consumed` / `inventory:order:consumed` / `inventory:deduct` / `coupon:claim` / `cart:event` → **无跨服务撞键** ✓。
- 遗留命名（无 myxhs 前缀，如 `msg:idempotent:*`）为兼容现状保留（改名会丢在途幂等状态），登记为"可接受的历史命名"。

### TTL 抽样（针对遗留/关键前缀）
| 前缀 | 键数 | 无 TTL | 结论 |
|---|---:|---:|---|
| `msg:idempotent:*` | 162 | 0 | TTL 秒~小时级 ✓ |
| `inventory:event:version*` | 42 | 0 | TTL ~21h ✓ |
| `inventory:bucket:count*` | 13 | 13 | 分桶数量=**有界持久状态**（按 SKU），非泄漏 |
| `myxhs:notification:unread*` | 42 | 42 | 未读计数=**有界持久状态**（按用户），非泄漏 |
| `xhs-ai:state:*` | 546 | 546 | AI 域状态存储，已登记 ai 侧复核 |

## 三、结论
- 配置面：消除 1 个孤儿配置（假外置），配置清单收敛为 common + gateway + sentinel-rules，文档/脚本/种子 SQL 同步。
- key 面：常量规范（52/52 带前缀、无重复）、幂等无撞键、遗留前缀 TTL 完好；无新增泄漏点（上轮已修点赞集合）。
- 本轮改动均为配置/文档/注释（notification yml 仅注释），无需重发服务；`04-nacos-config-seed.sql` 下次从零部署时不再播种废弃配置。
