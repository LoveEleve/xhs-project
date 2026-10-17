---
name: scheduler-toolkit
stage: 组件封装
description: 分布式调度工具封装——分布式锁调度、任务幂等、失败重试、执行记录、手动触发/暂停/恢复
---

# 分布式调度工具封装（scheduler-toolkit）

## 1. 职责

为项目生成分布式任务调度基础设施代码，包括分布式锁调度（防止重复执行）、任务幂等（去重表）、失败重试（最大次数+补偿）、执行记录（开始/结束/耗时/结果）、手动触发/暂停/恢复 API。**禁止在无分布式锁保护的情况下执行定时任务**。

## 2. 触发方式

| 方式 | 说明 |
|------|------|
| 独立命令 | `/scheduler-toolkit [task\|lock\|retry\|console\|all]` |
| 自动加载 | coding-skill 阶段检测到 `@Scheduled` / `@SchedulerLock` / `cron` / `CronJob` / `robfig/cron` 时 |

## 3. 前置条件

- 项目已识别技术栈+调度框架（Spring @Scheduled / Quartz / XXL-Job / Python APScheduler / Go robfig/cron）
- 已确定目标包路径（按语言规范）
- 已确定任务列表（至少一个任务名称+执行逻辑）
- 已确定分布式锁实现（Redis / 数据库 / Zookeeper）
- 用户未指定 → 生成全部组件（`all`）

## 4. 工作流程

### Step 1: 确定生成目标

| 子命令 | 组件 | 检测条件 |
|--------|------|---------|
| `task` | 任务基类 | 存在 `@Scheduled` 或 cron 表达式 |
| `lock` | 分布式锁调度 | 定时任务无锁保护 |
| `retry` | 失败重试+补偿 | 任务无失败处理 |
| `console` | 调度控制台 API | 无法手动触发/暂停任务 |
| `all`（默认） | 全部组件 | 首次引入调度框架 |

### Step 2: 生成代码文件

```
# Java 示例
{basePackage}/scheduler/
├── core/
│   ├── AbstractTask.java              — 任务基类（模板方法：锁→执行→记录→异常处理）
│   ├── TaskContext.java                — 任务上下文（任务ID/参数/开始时间/重试次数）
│   └── TaskResult.java                 — 执行结果（成功/失败/跳过/耗时）
├── lock/
│   ├── DistributedTaskLock.java        — 任务分布式锁接口
│   ├── RedisTaskLock.java              — Redis 任务锁实现（SETNX + TTL）
│   └── DatabaseTaskLock.java           — 数据库任务锁实现（行锁）
├── store/
│   ├── TaskExecutionRecord.java        — 执行记录实体（任务名/开始/结束/耗时/结果/异常信息）
│   ├── TaskExecutionRepository.java    — 执行记录存储
│   └── TaskIdempotentChecker.java      — 任务幂等检查（去重表）
├── retry/
│   ├── TaskRetryStrategy.java          — 重试策略（最大次数/间隔/补偿窗口）
│   └── CompensationTask.java           — 补偿任务（扫描失败记录重新执行）
├── console/
│   ├── TaskController.java             — 调度控制台 API
│   └── TaskManageService.java          — 任务管理服务
└── config/
    └── SchedulerConfig.java            — 调度配置（线程池大小/锁超时/重试次数）

# 其他语言按对应命名规范生成
```

**每个组件的关键实现约束**：

- `AbstractTask`：模板方法——获取锁（失败则跳过/记录"被跳过"）→ 执行前检查幂等（已执行则跳过）→ 记录开始时间 → 执行业务逻辑 → 记录结束时间/耗时/结果 → 释放锁，子类只实现 `execute(TaskContext ctx)` 方法
- `RedisTaskLock`：SETNX key task:{taskName} value:{instanceId} PX 30000，锁超时自动释放，防止任务挂掉锁不释放
- 执行记录：持久化到数据库（task_execution_record 表），字段：task_name / start_time / end_time / duration_ms / result / error_message / instance_id
- 重试策略：最大重试次数 3，间隔 30s，补偿任务每 5 分钟扫描失败记录重新执行，补偿次数上限 5
- 手动触发 API：`POST /api/scheduler/{taskName}/trigger` 忽略锁和 cron 表达式立即执行，`POST /api/scheduler/{taskName}/pause` 暂停（不执行但标记为暂停），`POST /api/scheduler/{taskName}/resume` 恢复

### Step 3: 生成测试

```
{basePackage}/scheduler/
├── core/AbstractTaskTest.java
├── lock/RedisTaskLockTest.java
├── store/TaskIdempotentCheckerTest.java
├── retry/CompensationTaskTest.java
└── console/TaskControllerTest.java
```

测试要求：
- 任务基类：验证模板方法流程（锁检查→幂等检查→执行→记录→释放锁）
- 分布式锁：验证加锁/释放/超时自动释放/并发加锁失败跳过
- 执行记录：验证记录完整性、耗时统计
- 补偿任务：验证扫描失败记录、重新执行、补偿次数上限

### Step 4: 验证

1. 编译通过 + 测试通过
2. 检查清单：
   - [ ] 所有定时任务经过 `AbstractTask` 封装（非裸 `@Scheduled`）
   - [ ] 分布式锁已实现（非仅依赖单机锁）
   - [ ] 锁超时自动释放（防任务挂掉锁不释放）
   - [ ] 执行记录持久化到数据库
   - [ ] 失败任务有补偿机制（非手动重跑）
   - [ ] 手动触发 API 可用

## 5. 输出文件清单

```
{targetPackage}/scheduler/
├── core/AbstractTask.{java|py|go}
├── core/TaskContext.{java|py|go}
├── core/TaskResult.{java|py|go}
├── lock/DistributedTaskLock.{java|py|go}
├── lock/RedisTaskLock.{java|py|go}
├── lock/DatabaseTaskLock.{java|py|go}
├── store/TaskExecutionRecord.{java|py|go}
├── store/TaskExecutionRepository.{java|py|go}
├── store/TaskIdempotentChecker.{java|py|go}
├── retry/TaskRetryStrategy.{java|py|go}
├── retry/CompensationTask.{java|py|go}
├── console/TaskController.{java|py|go}
├── console/TaskManageService.{java|py|go}
├── config/SchedulerConfig.{java|py|go}
├── core/AbstractTaskTest.{java|py|go}
├── lock/RedisTaskLockTest.{java|py|go}
├── store/TaskIdempotentCheckerTest.{java|py|go}
├── retry/CompensationTaskTest.{java|py|go}
└── console/TaskControllerTest.{java|py|go}
```

## 6. 质量门禁

- ✅ 编译通过 + 测试通过
- ✅ 所有定时任务经过封装层
- ✅ 分布式锁已实现
- ✅ 锁超时自动释放
- ✅ 执行记录持久化
- ✅ 失败任务有补偿机制
- ✅ 手动触发 API 可用
- ❌ 禁止裸 `@Scheduled` 无锁保护
- ❌ 禁止任务锁无超时时间（会永久锁死）
- ❌ 禁止任务失败无补偿机制（会丢失数据）
- ❌ 禁止执行记录无持久化（无法排查问题）

## 7. 约束

- ❌ 不生成 XXL-Job 管理台配置（由调度中心管理）
- ❌ 不修改已有的调度代码（只新增调度基础设施，建议用户逐步迁移）
- ❌ 不引入未确认的调度框架版本
- ❌ 不生成 Cron 表达式的 WEB 界面（由前端团队实现）
---

> **来源 & 作者**
> - 公众号：华仔聊技术
> - 知识星球：华仔·AI高并发全栈训练营
> - 作者：王江华@huazai
