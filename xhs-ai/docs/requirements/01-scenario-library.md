# RQ01 · 业务场景库（R04）

> 目的：把 xhs-ai v1 的需求锚定到 **xhs 真实链路/表/中间件/历史案例**上；每个场景给 REQ 编号、输入、期望证据形态、数据源与优先级
> 编号规则：`REQ-DIAG-xx`（诊断）/ `REQ-KB-xx`（知识）/ `REQ-OPS-xx`（动作+审批）/ `REQ-PLAT-xx`（平台）

## 1. 诊断场景（REQ-DIAG）

| REQ | 场景 | 输入 | 期望输出（证据形态） | 数据源 | 优先级 |
|-----|------|------|---------------------|--------|--------|
| DIAG-01 | 服务健康巡检 | 无 / 服务名 | 15 服务状态汇总 + 异常清单 + 时间戳 | actuator health | P0 |
| DIAG-02 | traceId 全链路定位 | traceId | 命中服务时间线（含错误栈）→ 主类/方法 → 最近提交/blame → 方法源码片段 | ES `myxhs-logs-*`（traceId/APP_NAME/stack_trace）+ Git | P0 |
| DIAG-03 | 下单失败（500）定位 | 现象 | gateway→order→inventory 证据链：错误栈 + 错误率指标 + 依赖健康 + 涉及表/topic | ES + PromQL + actuator | P0 |
| DIAG-04 | 订单状态卡住 | orderNo/orderId | 状态机轨迹（t_order_event）+ 事务消息/回查记录 + 补偿 Job 状态 | MySQL(分片) + ES + XXL-Job | P0 |
| DIAG-05 | 库存超卖风险巡检 | skuId | Redis 分桶/总库存 vs DB 可用/冻结/locked 差异 + 近期预扣幂等/回滚记录 | Redis `inventory:{sku}:*` + t_inventory/t_inventory_prededuct_idem | P0 |
| DIAG-06 | 支付成功未收敛 | orderId | t_payment 状态 + 通知补偿计数（Redis）+ 订单回调记录 + Job 执行 | MySQL + Redis + XXL-Job | P0 |
| DIAG-07 | 退款异常 | orderId | t_refund 状态/金额边界（部分/全额）+ t_payment 状态 + 库存回补与退券记录 | MySQL + Redis | P1 |
| DIAG-08 | DLQ 积压诊断 | consumerGroup | DLQ 积压量 + 消息详情（key/body/tag）+ 首次失败原因（按 msgId 关联日志） | rocketmq-tools + 指标 `rocketmq_dlq_backlog` + ES | P0 |
| DIAG-09 | MQ 事务回查异常 | orderNo | 半消息/回查时间线 + 本地消息表状态 + 最终投递结果 | ES（回查日志）+ t_local_message | P1 |
| DIAG-10 | 接口性能劣化 | 接口路径/traceId | PromQL 延迟/错误率时间线 + ES 慢日志 + 依赖负载（ES/DB/Redis） | VictoriaMetrics + ES | P0 |
| DIAG-11 | 限流触发诊断 | 现象（40202/429） | 触发层（网关路由/服务 AOP）+ 阈值 + 命中用户/路径统计 | 网关日志 + 指标 | P1 |
| DIAG-12 | 缓存一致性诊断 | 实体/key | Redis vs DB 差异 + canal 失效日志 + 对账 Job 状态与修复记录 | Redis + MySQL + ES + XXL-Job | P1 |
| DIAG-13 | 定时任务异常 | job 名 | xxl_job_log 执行历史（handle_code/msg/耗时）+ 失败原因 + 补偿/对账任务状态 | XXL-Job DB/API | P1 |
| DIAG-14 | 服务不可用根因 | 服务名 | 进程/端口/健康 + 容器状态 + 最近重启/日志异常窗口 + 依赖健康 | 主机 + Docker + ES | P0 |
| DIAG-15 | 通用 5xx 入口 | 现象描述 | 自动编排工具链（health→metric→log→trace→代码）并输出证据链与下一步建议 | 编排以上 | P0 |

## 2. 知识场景（REQ-KB）

| REQ | 场景 | 期望输出 | 数据源 | 优先级 |
|-----|------|---------|--------|--------|
| KB-01 | 架构总览 | 15 服务/中间件/同步异步边界，引用真实文档与目录 | 知识库（架构层）+ 代码 | P0 |
| KB-02 | 业务链路 | 下单/支付/退款/关单/券生命周期，引用 topic/consumer/表 | 知识库（业务层）+ 代码 | P0 |
| KB-03 | 三级扣减与 TCC | Bucket/Redis/DB 三级 + TCC Try/Confirm/Cancel 对比与适用场景 | 代码（InventoryService/InventoryTccService）+ 知识卡 | P0 |
| KB-04 | 事务消息为何存在 | 半消息/回查/本地消息表兜底，引用 OrderTransactionListener | 代码 + 知识卡 | P1 |
| KB-05 | 代码定位 | 类/方法位置、职责、调用方 | LSP（jdtls）+ 代码索引 | P0 |
| KB-06 | 变更历史/blame | 最近提交、作者、改动原因（方法级） | Git（JGit/LSP） | P1 |
| KB-07 | 变更影响分析 | 给定类/方法 → Feign/MQ/表影响面与风险点 | 代码图谱 + 知识库 | P1 |
| KB-08 | 历史故障检索 | 相似故障卡/复盘与当时修复（如 BodyCacheFilter、TransactionConfig 时序） | 知识库（故障卡） | P2 |

## 3. 运维动作场景（REQ-OPS，全部走审批）

| REQ | 场景 | 输入 | 期望 | 数据源 | 优先级 |
|-----|------|------|------|--------|--------|
| OPS-01 | DLQ 重投（单条） | group + msgId | 审批（once/always/reject）→ 执行 → 结果核验（是否再入 DLQ）→ 审计 | rocketmq-tools | P0 |
| OPS-02 | DLQ 批量重投 | group + 过滤条件 | 预览清单 → 审批 → 分批执行 → 报告 | rocketmq-tools | P1 |
| OPS-03 | XXL-Job 触发 | jobId | 审批 → 触发 → 拉取执行结果 → 审计 | XXL-Job API | P1 |
| OPS-04 | 对账 Job 触发 | 领域（inventory/counter/coupon/cart/payment/follow） | 审批 → 触发 → 修复数量报告 → 审计 | XXL-Job + MySQL | P1 |

> 约束：动作类必须实现 intent→effect→settlement（幂等键 + 执行后核验，DELTA-4）；默认策略：读 allow / 动作 ask / 高危 deny。

## 4. 平台场景（REQ-PLAT）

| REQ | 场景 | 期望 | 优先级 |
|-----|------|------|--------|
| PLAT-01 | 多租户会话/记忆 | `(userId, sessionId)` 隔离；跨进程恢复；负向用例 403/404 | P0 |
| PLAT-02 | 审批中心 | 列表/批准/拒绝/超时 fail-closed；同会话拒绝级联；always 授权可恢复 | P0 |
| PLAT-03 | 审计查询 | 按 traceId/用户/时间查"谁在何时做了什么"，含参数与结果（脱敏） | P0 |
| PLAT-04 | 观测 | 对话 trace（OTel→Langfuse，捕获模式 metadata/sanitized/full）+ token 成本 + 工具成功率 | P0 |
| PLAT-05 | 评测回归 | ≥50 用例；知识问答准确率/引用有效性/拒答正确率；防退化门禁 | P1 |
| PLAT-06 | 反馈回流 | 会话备注 + 消息评分（永不注入模型）→ 沉淀评测集 | P2 |
| PLAT-07 | 数据合规 | 出网字段级脱敏 + 捕获模式 + 可切私有模型 + 审计留存 | P0 |

## 5. P0 场景验收要点（AC 草案，进入 P2 细化）

- **DIAG-02（traceId）**：输入真实历史 traceId（如 `5304dc5a8afb4741b8bc74cee49c3980` 类），输出必须包含：≥1 个命中服务、≥1 个主类/方法、最近一次相关提交；所有引用可回链（文件/日志行存在）
- **DIAG-03（下单失败）**：结论必须附 ≥3 类证据（日志错误栈 + 错误率曲线 + 依赖健康），缺证据时明确"证据不足"而非编造
- **DIAG-05（库存风险）**：对指定 SKU 输出 Redis({total}/bucket*/prededuct 索引) 与 DB(available/freezing/locked/幂等表) 对照，差异 >0 时给出可能原因与对账入口
- **DIAG-08（DLQ）**：输出积压量与 ≥1 条消息详情（msgId/key/重试次数），并能按 msgId 关联到首次失败日志
- **OPS-01（DLQ 重投）**：未审批时不得执行；审批通过后重投且**执行后核验**（消息离开 DLQ / 被消费）；全程审计可查
- **KB-03（三级扣减）**：回答必须引用真实文件/方法（如 `InventoryService.doPreDeduct`、`InventoryTccService.tryDeductStock`），引用校验通过
- **PLAT-02（审批）**：无应答超时→fail-closed；"always" 后同规则免审；进程重启后授权恢复
- **PLAT-07（合规）**：默认 `sanitized` 捕获：日志/参数中密钥（sk-/ark-/Bearer/JWT）被脱敏后才出网；可切 `metadata` 模式

## 6. 历史案例对照（直接作为知识库 expected 内容）

用我们已闭环的真实案例做知识/评测基线（节选）：
- 交易链路：下单→事务消息→预扣→支付→确认→退款→回补（117 项矩阵实测）
- 21 个运行态修复：XXL-Job 全缺失、RocketMQ topic 全缺失、TransactionConfig 类级条件时序、BodyCacheFilter 空 body、gateway 504 映射、MQ Long→String、t_user.role、4 张事件表、DLQ 积压监控盲区…
- 专项：TCC 11 场景、counter resetOffset 去重、Buffer 刷盘失败回写、INV outbox 失败回滚、ES CPU 配额瓶颈（0.5→2 核 3.9x）、SSE 跨实例、事务回查 75s 延迟验证

> 这些案例同时是 **KB-08 的知识源** 与 **PLAT-05 评测集的 ground truth 种子**。
