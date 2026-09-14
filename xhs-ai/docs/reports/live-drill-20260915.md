# AI 实测：真实流量 + 构造数据 + 5 项端到端（2026-09-15）

> 方式：对运行中的 15 微服务平台构造流量与异常数据，再由 xhs-ai Agent 完成真实诊断/检索/变更闭环
> 结论：**5/5 场景完成**；过程中发现并修复 2 个问题（MCP 自愈误判、ES 检索参数），余 1 项转 backlog

## 1. 流量与数据构造

| 项 | 方式 | 结果 |
|----|------|------|
| 真实流量 | `scripts/traffic-gen.sh`（登录→11 个读接口 + 点赞/收藏/发笔记/评论写接口） | 300 轮 ≈3.4k 请求，42s；200×3084，404×300（1 个未对上的 notify 路径） |
| 数据写入 | 发笔记/评论/点赞/收藏 | 新增笔记/评论/互动数据，触发搜索索引同步等 MQ 链路 |
| 异常数据 | Spring Boot PropertiesLauncher 注入器（无 mqadmin 时的替代路径）向 CART_TOPIC 投 2 条非 JSON 毒丸消息 | 重试耗尽后进入 `%DLQ%cart-sync-consumer-group` |

## 2. AI 实测结果（Agent 端到端）

| # | 场景 | 耗时 | 结果 |
|---|------|------:|------|
| 1 | DLQ 清单与积压 | 33s | ✅ 列 2 组共 12 条：cart-event-sink-group 10（历史）、cart-sync-consumer-group 2（本次注入） |
| 2 | 死信根因分析 | 98s | ✅ 准确判定毒丸消息（非 JSON 反序列化失败），明确"重投仍会失败、不能靠重投恢复" |
| 3 | ES 日志检索（ERROR Top 服务） | 107s | ✅ 41 条 ERROR 全来自 xhs-ai（重启风暴期间），附时间/logger/摘要证据 |
| 4 | Prometheus 实例健康 | 92s | ✅ `up==0` 为空；24 个 target 全 up（list_targets 交叉验证） |
| 5 | HITL 重投闭环 | 115s+121s | ✅ 审批 21：pending→approved→重投（SEND_OK，队列坐标入库）→ 核验器判定 **reentered_dlq**（毒丸二次入死信，无误报成功） |

## 3. 过程中发现与修复

| 级别 | 问题 | 根因 | 处置 |
|------|------|------|------|
| P0 | MCP 自愈误判引发**重启风暴**（NRestarts=24） | 存活探测只看直接子进程且 2 连击即处置，进程树/命令行抖动误伤 | 改为全进程扫描 + 3 连击；**默认仅告警不重启**（`self-restart-enabled=false`），需显式开启；已部署验证 NRestarts=0 |
| P1 | ES `search` 工具连续 3 次参数校验失败（缺 index/queryBody） | qwen3.8-flash 不会填嵌套 DSL schema | 系统提示补 12.1 显式示例；修复后同一问题成功（并在提问中加"只查一次"约束稳定耗时） |
| P2 | 日志类问题耗时波动（107s~240s+） | 模型多轮试错 | Backlog：自建简化参数 ES 查询工具（index/关键词/时间窗），替代裸 DSL |

## 4. 证据

- 原始响应：`/tmp/opencode/drill-dlq-{1,2}.json`、`drill-log-{1,2,3}.json`、`drill-prom-1.json`、`drill-redeliver-1.json`、`approval-21-reply.json`（本机临时目录，关键结论已摘录本报告）
- 审批链：`ai_approval id=21`（approved→executed→verification reentered_dlq）；审计与 settlement 全量入库
- 复现：`bash xhs-ai/scripts/traffic-gen.sh 300 25`；注入器源码 `/tmp/opencode/Inject.java`（PropertiesLauncher 方案）
