# 对账体系运行时复核（6 个 Reconcile Job）2026-09-19

> 目的：为 xhs/18（对账体系）补运行时证据：调度是否真跑、修复是否真发生、失败是否可见。
> 关联：`xxl-job-schedule-audit-20260918.md`（调度修复）、`xhs/18`。

## 一、体系与权威源口径

| Job | 频率 | 权威源口径（代码实证） |
|---|---|---|
| counterReconcileJob | 每小时 | Redis 为准修正 DB；Redis=0 且 DB>0 → 视为 Redis 丢失，用 DB 恢复 Redis（TTL 30d） |
| inventoryReconcileJob | 每小时 | Redis 为准修正 DB + 分桶总量原子 Lua 修正 |
| couponReconcileJob | 每小时 | 模板/用户券一致性修复 |
| cartReconcileJob | 每小时 | Redis 购物车 vs MySQL 记录 |
| unreadReconcileJob | 每 10 分钟 | DB 未读 vs Redis 计数 |
| paymentReconcileJob | 每日 03:00 | 支付/订单终态一致性（09-10 起从"死代码"修复为 XxlJob） |

调度器视角（xxl_job_info）：6 个任务 `trigger_status=1`，`trigger_last_time/next_time` 正常滚动（如 counter 20:00→21:00；unread 20:30→20:40；payment 09-19 03:00→09-20 03:00）。

## 二、真实修复证据（无需注入，自然发生）

- 20:00 inventoryReconcileJob：`待对账SKU数: 17；修复 skuId=48784 mysql 995→998（以 Redis 为准）+ 分桶总量原子修正；修复 skuId=18979` → `完成 17 个 SKU, 修复 2 条, 耗时 38ms`；XXL 日志 `handle_code=200`。
- counter/coupon/cart：近期均为 `修复 0 条`（体系稳定，无持续漂移）。

## 三、注入式演练（counter，双向验证 + 已还原）

| 步骤 | 操作 | 结果 |
|---|---|---|
| ① 模拟 Redis 丢失 | `DEL myxhs:counter:1:2101284676795801601:3`（DB=11） | 手动触发后日志 `[对账修复] Redis恢复: redis=0, db=11` → Redis=11，**TTL≈30 天**；XXL `修复 1 条` |
| ② 模拟增量未落库 | `INCRBY +2`（Redis=13，DB=11） | 触发后 `[对账修复] DB修正: redis=13, db=11` → DB=13 |
| ③ 数据还原 | DB=11、Redis=11 | 已还原（校验通过） |

触发方式：XXL-Job Admin（18080）`/jobinfo/trigger`，`code=200` 且执行日志落库。

## 四、失败与边界（诚实披露）

1. **环境重启窗口 3 次触发失败**（trigger_code=500，executor 不可达）：cart 20:00、unread 19:40、payment 19:13；cart 在 20:30 重触发即成功（`修复 0 条`）→ 属环境事件，非任务缺陷。
2. **调度失败无告警**：Prometheus 无 XXL-Job 指标、40 条规则中也无"任务失败"规则 → 失败只能看 Admin 控制台/`xxl_job_log`。**建议**：加一条基于 `xxl_job_log`（handle_code!=200）或 Admin API 的巡检/告警。
3. **payment 日跑日志留存不完整**：调度器显示 09-19 03:00 已触发，但执行日志/业务日志（`[定时任务] 支付对账开始`）在留存文件中缺失（多次重启 + 日志轮转），当前仅有 09-10 成功与 09-19 19:13 失败记录。证据受限，建议后续补一次手动触发留档。

## 五、面试口径（对账三要素）
- **权威源**：不是"都信 DB"——计数/库存以 Redis 为准（高频写发生在 Redis），Redis 整键丢失才用 DB 兜底恢复；每类账本必须能说清方向。
- **修复幂等**：修复动作本身可重复执行（再次对账返回 0），注入演练后可安全还原。
- **指标诚实**：XXL 返回"修复 N 条"，巡检看 N 是否长期非 0（>0 是漂移信号，不是"修了就没事"）。

## 六、证据索引
- XXL-Job：`xxl_job.xxl_job_info` / `xxl_job_log`（job 25/27/28/34/35/36）
- 业务日志：`[对账修复]`（counter）、`[库存对账]`（inventory）
- 代码：`CounterService.reconcile():530`、`InventoryReconcileJob`、`PaymentReconcileJob`
