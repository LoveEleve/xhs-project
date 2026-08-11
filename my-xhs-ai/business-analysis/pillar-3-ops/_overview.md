# 支柱三：技术运维与排障（O&M）— 总览

> 业务定位：**业务诊断的另一半**。my-xhs 是高度复杂的分布式系统（16 服务 + 分库分表 + TCC + MQ + Redis + ES + Sentinel + XXL-Job），技术排障需求巨大。
> 本支柱回答"系统/中间件/数据为什么异常"，与 pillar-1/2 的"业务为什么异常"互补。
> 覆盖数据资产：SkyWalking(链路)、Prometheus(指标)、ELK(日志)、XXL-Job(任务)、MySQL/Redis/ES/RocketMQ。

## O&M 排障场景清单（AI 应能回答）

| 域 | 典型场景 | 就绪度 |
|----|---------|:---:|
| MySQL | 死锁 / 慢查询 / 负载 / 主从延迟 / 连接池耗尽 / 分片路由 | ⚠️ |
| Redis | 内存淘汰 / OOM / Sentinel 切换 / 热点大key / 缓存脏读 | ⚠️ |
| RocketMQ | 消费积压 / 死信 DLQ / Outbox 积压 / 事务消息失败 | ✅ |
| ES | 集群健康 / 同步延迟 / 索引重建失败 / 查询 | ✅ |
| JVM/服务 | OOM / GC 暂停 / 5xx / 慢端点 / 跨服务链路 | ✅ |
| 定时任务 | 执行器离线 / 任务失败 / 补偿积压 | ✅ |
| 分布式事务 | TCC 悬挂/空回滚 / 本地消息表积压 / 对账差异 | ✅ |
| 平台横切 | 死信/推送失败/降级/中间件不可用连锁 | ✅ |

## 四层观测资产（O&M 工具的数据基础）

```
L1 应用链路   SkyWalking(11800/12800/8080)  拓扑+调用链+慢端点+SQL/Redis耗时
L2 指标层     Prometheus(19090)+VictoriaMetrics(8428)  JVM/GC/线程/连接池/MQ位点
L3 日志层     ELK(Logstash 15044/45 → ES 19200 → Kibana 15601)  myxhs-logs-*
L4 业务任务   XXL-Job(18080)  9服务定时补偿/对账/重建
```

## 就绪度汇总（O&M 补全优先级）

| 缺口 | 影响 | 优先级 |
|------|------|:--:|
| 无 mysql-exporter | MySQL 死锁/慢查询/锁等待不可采集 | 🔴 高 |
| 无 slow_query_log 管道 | 慢查询无法进入 Kibana 分析 | 🔴 高 |
| 无 redis-exporter | Redis 内存/命中/淘汰不可采集 | 🟡 中 |
| 无 DLQ 消费者 | 死信消息无法自动排查 | 🟡 中 |
| VM 未接线 | 长期趋势/容量分析缺失 | 🟢 低 |
| 无 AlertManager | 告警不通知，仅停留规则文件 | 🟢 低 |

## 对 AI 项目设计的影响（重要）
1. **O&M 是独立的能力面**：需要访问 Prometheus/ES 日志/MySQL 诊断/SkyWalking 等**内部观测工具**，权限比业务只读工具更高、更敏感。
2. **工具分层**：业务工具(只读领域 API) vs 观测工具(PromQL/ES DSL/诊断 SQL) vs 运维动作(重启/重投/回滚) 必须**分级授权**。V1 建议只做**观测/诊断只读**，运维写动作(重启/重投)默认拒绝、需人工审批。
3. **O&M 与业务诊断联动**：业务异常(订单降)的技术根因(慢查询/死信/中间件)要靠 trace+指标交叉定位——Agent 的"证据链"需跨两支柱取证。
4. **审计**：O&M 工具访问更敏感，审计要求更高，与业务查询分开记录。

## 关联文档
- [mysql-ops](mysql-ops.md) / [redis-ops](redis-ops.md) / [mq-ops](mq-ops.md) / [es-ops](es-ops.md)
- [jvm-service-ops](jvm-service-ops.md) / [jobs-tx-ops](jobs-tx-ops.md)
- [identity](identity.md) 用户身份与安全
- 恢复 SOP：`../../docs/test-2/engineering-docs/failover-scenarios.md`
- 观测管道：`../../docs/test-2/engineering-docs/monitoring-pipeline.md`
