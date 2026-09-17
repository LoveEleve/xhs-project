# 生产化加固实录：SqlGuard 安全阻断 + Sentinel 规则托管 Nacos（2026-09-17）

## 一、SqlGuard v2：从"只判定"到"可配置安全阻断"

**改造点（`my-xhs-common`）**
| 项 | 说明 |
|---|---|
| 配置化 | `myxhs.sql-guard.{enabled,slow-ms,breaker-threshold,cooldown-ms,block-on-open,block-patterns,exempt-patterns}` |
| 安全默认 | `block-on-open=false` —— 与旧版行为一致（只判定/告警），不会误伤 |
| 白名单阻断 | 仅 `block-on-open=true` 且 SQL 命中 `block-patterns`（子串、逗号分隔）才抛 `SqlGuardBlockedException`；空名单=全部不阻断 |
| 豁免名单 | `exempt-patterns` 命中的 SQL 不计数、不阻断 |
| 冷却恢复 | 冷却到期自动移除熔断标记，重新计数 |
| 指标 | `myxhs_sql_guard_slow_total` / `open_total` / `blocked_total`（Prometheus） |
| 单测 | 6 例全过（默认不阻断/白名单阻断/非白名单不阻断/豁免/冷却恢复/总开关） |

**真机验证（cart，测试参数启动）**
1. 清空测试用户 Redis 购物车键 → 强制走 DB 恢复路径；
2. 第 1 次 `GET /api/cart/list` → 200，慢 SQL 记录，熔断打开；
3. 第 2 次同请求 → **500**，日志：`[SqlGuard] SQL阻断: fingerprint=891089606:SELECT...` + `SqlGuardBlockedException`；
4. 指标：`myxhs_sql_guard_open_total=1`（慢 SQL/阻断计数同步可见）；
5. 恢复默认参数重启 → 两次请求均 200（默认不阻断）。

**面试口径升级**：可以说"我们从观测→校准→分级强制做了演进：200ms 告警起步，生产化后提供白名单+冷却+指标的阻断开关，默认关闭由驱动级超时兜底（建议）"。

## 二、Sentinel 规则托管 Nacos（网关）

**改造点**
- `my-xhs-gateway/application.yml` 新增 `spring.cloud.sentinel.datasource.flow.nacos`（dataId=`my-xhs-gateway-sentinel-flow.json`，namespace=my-xhs，rule-type=gw-flow）；
- 规则 JSON（16 条路由 QPS）已发布至 Nacos（`configCount: 1`）；
- 网关逻辑原本已支持：Nacos 数据源优先，30s 真空期回退 yml metadata 兜底。

**真机验证**
1. 重启网关：日志 `[Gateway-Sentinel] Nacos规则已到达(规则数:16)`（非兜底）；
2. **动态推送**：Nacos 改 `user-service=3 QPS`（不重启）→ `GET /api/user/auth/captcha` 20 连发：**3×200 + 17×429**；
3. 改回 `50 QPS` → 15 连发全部 200；
4. 重启后规则仍在（持久化）。

**口径升级**：简历/手册中"Sentinel 规则为手工导入制品、metadata 兜底"更新为"**规则托管 Nacos、动态推送，metadata 30s 兜底**"。

## 三、附带修复
- `scripts/restart-service.sh` 路径失效（`/data/workspace/my-xhs` → `xhs-project`）、移除不存在的 SkyWalking agent 参数；已用该脚本成功恢复 cart（UP 15s、健康+令牌校验通过）。
- 发现并记录：覆盖运行中服务的 fat-jar 会导致懒加载类 `ClassNotFoundException`（本次 cart 500 的根因）——**改包后必须重启服务**。

## 四、运行态现状
- 网关：Nacos 规则生效（16 条）；cart 已恢复默认参数（block-on-open=false）。
- 平台 JVM 参数（供 xhs/24 用）：默认 `-Xmx512m`（inventory/order/search 1024m，gateway 512m），G1 + MaxGCPauseMillis=200 + MaxMetaspaceSize=256m。
