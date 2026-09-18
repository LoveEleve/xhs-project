# 剩余工作深度规划（2026-09-18）

> 原则：一切以"可验证"为准；默认关闭、开关可控；不做为技术而技术。

## 阶段 1：D3-7/8 Redis 多活（当前阶段）
### 目标
- Redis 客户端 zone 感知：读就近（本地副本优先）、写主库、跨 zone 自动兜底；本地不可用自动降级与回切。

### 现状（已侦察）
- 单主 6379（zone-a）+ 只读副本 6380（zone-b）+ Sentinel 26379（主故障切换 2.3s 已验证）。
- zone 骨架已有：`RedisTemplateWrapper`（Proxy 包装）+ `RedisMethodInterceptor`/`RedisMethodContext`/`EventPublishingRedisCommandInterceptor`（默认关）。

### 方案（渐进）
- **方案 A（本期，推荐）**：命令级 zone 路由
  - `ZoneAwareRedisConnectionFactory`：zone-a → 主库；zone-b → 本 zone 副本（仅读命令），写命令自动跨 zone 回主库。
  - 命令分类复用 `RedisMethodInterceptor`（方法名/命令类型判断），不可判定命令按写处理（安全侧）。
  - 本地不可用：降级到主库（跨 zone 兜底）+ 定期探测回切（对齐 MySQL 数据源语义）。
- **方案 B（可选演示，不在本期）**：zone-b 起第二主库 + 事件同步（`RedisCommandEvent`）→ 真双写冲突（LWW/幂等）评估。

### 验证与交付
- cart/counter 分别以 zone-a/zone-b 启动，验证读命中本地（redis `INFO commandstats`/exporter 指标）。
- 故障演练：停本地实例 → 降级跨 zone；恢复 → 回切；记录 RTO。
- 交付：开关默认关 + 单测 + 报告 `docs/reports/redis-zone-drill-20260918.md`。

## 阶段 2：E1 日志治理 + H1 成本（合并执行，P1）
- 日志：级别治理（残余 DEBUG 清理）、结构化字段、保留/压缩策略、日志量指标。
- 成本：CPU/内存/磁盘/连接数盘点 → 成本折算 → 与 A2/A3 联动的优化清单。
- 交付：`docs/ops/log-retention.md` + 成本报告。

## 阶段 2 执行结果（2026-09-18，已完成）
- ✅ 文档：`docs/reports/cost-log-governance-20260918.md`；日志保留脚本 `scripts/log-cleanup.sh` + 每日 cron；15 服务 JSON 日志上限生效
- ✅ 清理：19 个残留 JVM（16.4GB）、日志 1.1G、releases 1.6G、npm 3G、apt 0.4G；内存 45→28Gi
- 遗留：containerd 21G 迁移（维护窗口）、SkyWalking/Kibana/Logstash 是否停用待确认

## 阶段 3：面试材料回填（Track G，最高优先级价值）——执行中/部分完成
- ✅ 未写题全部落稿：xhs 18-25 + ai 08-18（19 题）+ 新增 xhs 26（Zone 多活与容灾）
- ✅ 跨题地图升级：44 题、8 条叙事链（新增"多活与容灾"链）、简历映射表更新
- ✅ 简历口径：新增 Zone 多活专项 bullet；性能数字更新为类加载修复后基线（4,871/4,418/1,861/1,448）
- 待办：逐题自测（按"链"过一遍）、简历最终排版校对
- 把新能力写入口径：zone LB / zone 数据源 / 网关多活 / Redis 多活 / 发布链路加固（PID 校验与"假成功"教训）/ 告警闭环 / MySQL 切换 / 压测数据。
- 补齐未写题：xhs 18-25、ai 08-18（已预审）。
- 简历口径复核（只写真实做过、可追问的）。

## 阶段 4（可选，P2）
- D3-9 动态 JDBC 接线演示、D3-10 动态 Spring 组件、D3-2 REST Client 评估（使用面小，可能不做）。

## 明确不做（防范围蔓延）
- 真异地多活/GSLB、K8s 化、全量 Dubbo 迁移、CRDT 真实现、Redis 双主生产化。

## 里程碑
- M1：Redis 多活开关可切 + 演练报告（本阶段）。
- M2：日志/成本报告。
- M3：题库回填 ≥5 篇 + 简历复核。
