# JVM / 服务 / 链路排障（OOM / GC / 5xx / 全链路追踪）

> SkyWalking 提供**全链路追踪**，是跨服务排障的利器；Prometheus 提供 JVM 层指标。

## 业务问题（AI 能回答）
- **OOM**：堆转储、内存泄漏、服务重启。
- **GC 暂停**：GC 频率/暂停时长、是否触发告警。
- **服务 5xx**：错误率、出错端点、根因。
- **慢端点**：P99 超时、慢在哪一跳（DB/Redis/MQ/Feign）。
- **跨服务链路**：一次请求经 Gateway→Feign→DB 的耗时分解。

## 可用的数据资产与就绪度
| 排障目标 | 数据来源 | 就绪度 |
|---------|---------|:---:|
| JVM 内存/GC | Prometheus `jvm_memory_used`、`jvm_gc_pause_seconds` | ✅ |
| 线程池 | `tomcat_threads_current` | ✅ |
| 5xx/延迟 | `http_server_requests_seconds`(status_group/p99) | ✅ |
| 跨服务链路 | SkyWalking OAP(11800) 拓扑+调用链+慢查询 | ✅ |
| OOM 堆转储 | logs/ + java_pid*.hprof | ⚠️ 需配置转储 |

## 关键诊断点
1. **告警阈值**（已在 alert_rules）：
   - 5xx 错误率 >1%(2min) → P1
   - P99 >1s(5min) → P2
   - JVM 堆 >85%(5min) → P1
   - GC 平均暂停 >500ms → P2
   - HikariCP >90% → P2
2. **链路分析**：SkyWalking 自动生成拓扑 + 端点级 QPS/延迟百分位；慢端点 >1s 自动标记。
3. **OOM**：G1GC MaxGCPauseMillis=200；order/inventory/search 用 1g 堆。内存泄漏 vs 流量增长需区分（重启缓解 vs 修复泄露点）。
4. **服务重启**：Nacos 心跳 15s 超时 → 30s 摘除 → 重启注册；摘除窗口内 Feign 超时降级。

## 关联
- 慢端点/5xx 是**业务异常**的技术根因 → 关联各业务支柱诊断。
- 中间件故障(MySQL/Redis/MQ)最终都表现为服务 5xx/延迟 → 从 trace 反查。
