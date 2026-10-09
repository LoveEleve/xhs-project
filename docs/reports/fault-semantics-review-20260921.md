# 生产故障语义 review（2026-09-21）：依赖 × 故障形态 × 代码现状

> 口径声明：本环境为**单机仿真**（16C/62G，27 容器），**不部署** Nacos 集群 / Redis Cluster / MQ 主从 / ES 集群。
> 但**生产拓扑带来的故障语义**必须逐条对照代码——这些语义在单机可用 pause/iptables/stop 仿真，静态可审。
> 本文是"按生产语义审代码"的结论，可作为面试讲解底稿与后续加固清单。

## 1. 结论摘要
- **Cluster 语义满足**：新增代码的 Redis 用法全部为"单 key 或同 hash tag 多 key"，无 CROSSSLOT 风险。
- **本轮修 2 个真缺口**：① 跨服务回补失败无补偿（库存域整体挂时请求未到达，库存域内部补偿表不会留记录）；② 对账单到账标记写失败会让"流水已入库但对账永久跳过"。
- **4 条建议**（未改，登记）：金额校验读强制主库（口径固化）、Nacos 全挂时实例列表陈旧的主动健康检查、ES `wait_for_active_shards`、3 个新任务纳入 Watchdog。

## 2. 依赖 × 故障形态矩阵

| 依赖 | 生产故障形态 | 单机仿真口径 | 代码现状 | 结论 |
|---|---|---|---|---|
| Redis Cluster | CROSSSLOT（多 key 脚本跨 slot）/ MOVED·ASK / 主从切换窗口写失败 / 分片不可用 | 单实例 + pause/iptables；hash tag 静态审查 | 新增路径：库存回补 Lua 单 key、`total`+`bucket` 同 `{skuId}` tag、售后/结算锁与到账标记单 key、关注重建单 key ZSet | ✅ 无 CROSSSLOT；切换窗口写失败见下 |
| Redis 切换窗口 | 写失败/读旧值（Sentinel 2.3s） | 已有实测 | 库存回补失败→补偿表+restock_status；结算锁失败→"处理中"拒绝；到账标记失败→导入事务回滚（本轮修） | ✅ |
| MySQL 主从 | 切换窗口写失败；从库延迟使"金额校验读"读到旧值 | 已有 failover 演练 | 金额校验读全部走主库（order→ShardingSphere 主库、payment→单数据源）；未开读写分离 | ⚠️ 口径固化：未来开读写分离时，金额校验必须强制主库 |
| MySQL 分片 | 广播查询 / 跨片 JOIN | — | 售后、结算均在非分片库；订单侧查询（订单/明细/事件）全带 user_id 单分片路由 | ✅ |
| RocketMQ 主从 / 重平衡 | 队列所有权变更→**重复投递**；Broker 不可用→发送失败 | pause broker 已有 | 顺序消费端幂等（UPSERT + msgId 幂等 + 时间戳 CAS）；发送失败仅日志→对账兜底 | ✅ 重复投递安全 |
| Nacos 集群 | 全挂→实例列表陈旧（本地快照）、配置不推送 | 不做（单机无故障域） | 配置 `optional:nacos:` 启动不阻塞；实例陈旧→Feign fallback 降级 | ⚠️ 建议：客户端主动健康检查兜底（可复用 zone health check） |
| ES 集群 | 主分片不可用→写失败；副本提升 | — | 外部版本号 + tombstone；写失败依赖 Canal 重放 | ⚠️ 建议：评估 `wait_for_active_shards`（既有遗留） |
| XXL-Job | 调度器/执行器停摆→任务不跑 | 停容器 | 新增 3 个任务：售后恢复 / 账单生成 / 对账 | ⚠️ 建议：纳入 Watchdog 元监控 |
| 支付域 | 整体不可用 | stop 服务 | Feign 降级→售后置"退款失败"→恢复任务重试；查不到事实→保持等待不误判 | ✅ |
| 库存域 | 整体不可用（**请求未到达**） | stop 服务 | **本轮修**：售后单 `restock_status` + 恢复任务第三类扫描重试 | ✅ |
| 磁盘 / 日志 | 磁盘写满 | 已有 burn 演练 | 日志单文件 100MB/总量 2GB + ILM 30 天 | ✅ |

## 3. 本轮修复明细

### 3.1 跨服务回补失败补偿（分布式盲区）
- **问题**：退款成功后订单域 Feign 调库存域回补；若库存域整体不可用，**请求根本没到达库存域**，其内部补偿表（`t_inventory_compensation`）不会留下记录 → 永久漏补。
- **修法**：`t_aftersale` 增 `restock_status(0-待回补/1-已回补)`；回补成功即标记；`AftersaleRecoveryJob` 增第三类扫描（`status=已完成 AND restock_status=0`）重试回补，成功置 1。
- **幂等**：库存域回补按 `(orderId, skuId)` 累计正增量，重复重试安全。
- 迁移：`sql/migration/order/V2__aftersale_restock_status.sql`。

### 3.2 对账单到账标记失败
- **问题**：标记写失败时导入接口仍返回成功 → 流水在库、对账因缺标记永久跳过（静默不一致）。
- **修法**：标记写失败抛 `SERVICE_UNAVAILABLE`，`@Transactional` 导入整体回滚，调用方重试即可（导入本身幂等）。

## 4. 建议项（未改）
1. **金额校验读强制主库**：在 `AftersaleService`/`SettlementService` 的校验查询上固化"主库读"口径（当前未开读写分离，风险为零；开之前必须先做）。
2. **Nacos 全挂兜底**：实例列表陈旧时，客户端主动健康检查（复用 zone health check 机制）替代纯依赖推送。
3. **ES 写一致性**：评估 `wait_for_active_shards=1` 与写失败重试策略。
4. **任务停摆监控**：3 个新任务（aftersaleRecoveryJob / settlementBillJob / settlementReconcileJob）纳入 Watchdog 覆盖与"最近成功时间"告警。
