# XXL-Job 调度审计与修复（2026-09-18）

> 范围：xxl-job-admin 2.4.2（18080）、10 个执行器组、20 个 @XxlJob 任务。
> 结论：发现 1 个"从未运行"的失效任务（非法 cron 被调度器自动禁用）与 3 个"错过窗口静默丢弃"的每日任务；均已修复并验证。

## 一、发现 1：refundTimeoutCheckJob 从未执行（非法 cron）

**现象**：`trigger_status=0` 被禁用、`xxl_job_log` 零记录；文档口径应为 ON、cron `0 * * * * ?`。

**排查**：
1. DB 现值 `schedule_conf='0/60 * * * * ?'`（与文档不一致）；
2. 手动启用后数秒被**回写为 0**（复现 2 次）；
3. admin 日志命中 `CronExpression.checkIncrementRange` 异常栈——
   `0/60` 的增量 60 **越界**（秒字段增量必须 <60），调度器生成下次触发时间抛异常后按设计把任务置为停用；
4. 因此该任务自创建起从未被排期（零日志、last/next 均为 0）。

**修复**：`schedule_conf='0 * * * * ?'` + `trigger_status=1`。

**验证**：修复后连续执行 15:53 / 15:59 / 16:00 / 16:01，全部 `trigger_code=200 / handle_code=200`（任务体显式 `XxlJobHelper.handleSuccess`）。

## 二、发现 2：每日任务错过窗口被静默丢弃（misfire=DO_NOTHING）

**现象**（环境存在每日关机/重启，凌晨 2-3 点常不在线）：
| 任务 | cron | 日志 | 判断 |
|---|---|---|---|
| feedCleanupJob | `0 0 3 * * ?` | **0 条** | 从未执行 |
| recommendItemCFJob | `0 0 2 * * ?` | 1 条（09-11 23:44 手动） | 定时从未执行 |
| paymentReconcileJob | `0 0 3 * * ?` | 1 条（09-10 23:31 手动） | 定时从未执行 |

**根因**：`misfire_strategy=DO_NOTHING`——admin 停机期间到点的触发被丢弃（不补跑、仅 admin 日志 WARN，业务侧无感）。

**修复**：三个任务改为 `misfire_strategy='FIRE_ONCE_NOW'`（错过窗口在恢复后补跑一次）；分钟/小时级任务保持 DO_NOTHING（天然追平，不需补跑）。

## 三、发现 3：日报"失败数"的口径陷阱

`xxl_job_log_report` 显示 09-18 失败 633 条，拆解后：
- 绝大多数是 `trigger_code=500`（调度失败）——服务重启窗口执行器不在线（当日全量发布 15 服务）；
- 其余为 `handle_code=0`（任务体未显式上报结果，非失败）；
- **真实业务失败仅 2 条**（handle_code=500）。
口径提醒：日报 fail_count 混合了"调度失败/未上报/业务失败"，看板需按 trigger_code/handle_code 拆分解读。

## 四、任务矩阵（修复后，20/20 可调度）

对账类（小时级）：cartReconcile / counterReconcile / couponReconcile / inventoryReconcile / deadLetterScan / recommendFeature
高频类：orderClose（1min）/ localMessageRetry（30s）/ paymentTimeoutCheck（30s）/ paymentNotifyCompensate（2min）/ refundNotifyCompensate（3min）/ refundTimeoutCheck（1min，本次修复）
每日类：feedCleanup（3:00）/ recommendItemCF（2:00）/ paymentReconcile（3:00，均改为 FIRE_ONCE_NOW）
分钟级：couponExpire（5min）/ orderMappingRepair（5min）/ followCounterRepair（10min）/ unreadReconcile（10min）/ recommendHotPool（10min）

历史事故参照：P-D20（2026-08）19 任务仅 ~8 可调度（挂 sample 组 NULL 地址），已由 init-xxljob.sql 修复。

## 五、边界（主动披露）
- admin 单实例（无 HA）；任务失败无独立告警（依赖 admin 邮件/人工），未接告警通道；
- 所有修复均直接写 admin DB（等价控制台操作），未走 UI 复核流程。
