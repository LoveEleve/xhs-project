# engineering-docs — 工程实践文档（⚠️ 历史参考，部分过时）

> 2026-08-12 标注 | 本目录为 8/8~8/10 期间工程实践文档（9 文件）。

## ⚠️ 过时声明

以下文档与**当前部署架构（2026-08-12）不符**，仅供历史理解：

| 文件 | 过时原因 |
|---|---|
| `deployment-guide.md` | 部署形态已变：25 容器 compose（云主机）+ 微服务 Ubuntu VM；以 `config/deploy-cloud/DEPLOY-README.md` 与 `../DEPLOY-CLOUD-GUIDE.md` 为准 |
| `middleware-topology.md` | 中间件拓扑已更新（26→25 容器、exporter 补全、端口调整）|
| `monitoring-pipeline.md` | 监控链路已补全（exporter×3/canal/OAP/告警规则 25 条）|
| `fix-plan.md` | 已被 `../FIX-PLAN-PRODUCTION-CONFIG.md` 取代 |
| `security-model.md` | P-B4 后令牌模型已变（随机化 fail-closed）|

其余（cache-strategy / distributed-transactions / failover-scenarios / service-dependency-map）作为架构理解参考仍可用，结论以 `../review-fresh/` 为准。
