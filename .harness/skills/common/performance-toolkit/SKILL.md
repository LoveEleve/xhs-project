---
name: performance-toolkit
stage: 组件封装
description: 性能诊断工具封装——诊断脚本、火焰图分析、GC 分析、慢 SQL 分析、线程 dump 分析模板
---

# 性能诊断工具封装（performance-toolkit）

## 1. 职责

为项目生成性能诊断工具集，包括一键诊断脚本、火焰图采集脚本、GC 分析工具、慢 SQL 分析模板、线程 dump 分析模板。**禁止在无数据支撑的情况下给出性能优化建议**。

## 2. 触发方式

| 方式 | 说明 |
|------|------|
| 独立命令 | `/performance-toolkit [diagnose\|flame\|gc\|slow-sql\|thread\|all]` |
| 自动加载 | diagnosing-bugs 阶段检测到性能相关异常（超时/OOM/CPU 高）时 |

## 3. 前置条件

- 已确定目标进程 PID 或服务名
- 已确定应用的运行时（JVM / Python / Go / Node.js）
- 已确定诊断目标（CPU 高 / 内存泄漏 / 高延迟 / 低吞吐）
- 用户未指定 → 生成全部组件（`all`）

## 4. 工作流程

### Step 1: 确定诊断目标

| 子命令 | 工具 | 对应症状 |
|--------|------|---------|
| `diagnose` | 一键诊断脚本 | 通用性能问题 |
| `flame` | 火焰图采集脚本 | CPU 高、分配热点、锁竞争 |
| `gc` | GC 分析工具 | 内存泄漏、GC 频繁、STW 时间长 |
| `slow-sql` | 慢 SQL 分析模板 | 接口响应慢、DB 负载高 |
| `thread` | 线程 dump 分析模板 | 线程 hang、死锁、连接池耗尽 |
| `all`（默认） | 全部工具 | 首次引入性能诊断能力 |

### Step 2: 生成诊断脚本

```
scripts/
├── diagnose.sh                  — 一键诊断入口（采集所有数据）
├── flame-graph/
│   ├── collect-flame-java.sh    — Java async-profiler 采集
│   ├── collect-flame-python.sh  — Python py-spy 采集
│   ├── collect-flame-go.sh      — Go pprof 采集
│   └── analyze-flame.sh         — 火焰图分析指南
├── gc/
│   ├── collect-gc-logs.sh       — GC 日志采集
│   ├── analyze-gc.sh            — GC 分析脚本（提取关键指标）
│   └── gc-params.sh             — GC 参数模板（按堆大小生成推荐配置）
├── slow-sql/
│   ├── config-slow-query.sh     — 慢查询日志配置
│   ├── analyze-slow-sql.sh      — 慢 SQL 分析（提取执行计划）
│   └── index-finder.sh          — 索引建议生成
├── thread/
│   ├── dump-thread.sh           — 线程 dump 采集（3 次，间隔 5s）
│   └── analyze-thread.sh        — 线程 dump 分析（死锁检测/锁竞争/线程状态分布）
└── report/
    └── template.md              — 诊断报告模板
```

**每个脚本的约束**：

- `diagnose.sh`：检测目标语言自动选择对应工具，采集所有数据到 `{timestamp}/` 目录，输出采集摘要
- `collect-flame-*.sh`：支持 CPU/alloc/lock 三种事件，采样时长可配置（默认 60s），输出 SVG 文件
- `analyze-gc.sh`：提取 Young GC 频率、Full GC 频率、STW 时间、堆使用率、分配速率，与健康阈值对比
- `analyze-slow-sql.sh`：对每条慢 SQL 执行 EXPLAIN ANALYZE，标记 type=ALL（全表扫描）、Extra=Using filesort/Using temporary
- `analyze-thread.sh`：检测死锁、统计线程状态分布（RUNNABLE/BLOCKED/WAITING/TIMED_WAITING）、标记锁竞争热点

### Step 3: 验证

1. 脚本语法正确：`bash -n scripts/diagnose.sh`
2. 检查清单：
   - [ ] 诊断脚本覆盖目标环境的运行时（JVM/Python/Go/Node.js）
   - [ ] 火焰图脚本支持 CPU/alloc/lock 三种事件
   - [ ] GC 分析脚本输出了关键指标（频率/停顿/吞吐量/分配速率）
   - [ ] 慢 SQL 分析脚本关联了执行计划
   - [ ] 线程 dump 分析脚本包含死锁检测

## 5. 输出文件清单

```
scripts/
├── diagnose.sh
├── flame-graph/collect-flame-java.sh
├── flame-graph/collect-flame-python.sh
├── flame-graph/collect-flame-go.sh
├── flame-graph/analyze-flame.sh
├── gc/collect-gc-logs.sh
├── gc/analyze-gc.sh
├── gc/gc-params.sh
├── slow-sql/config-slow-query.sh
├── slow-sql/analyze-slow-sql.sh
├── slow-sql/index-finder.sh
├── thread/dump-thread.sh
├── thread/analyze-thread.sh
└── report/template.md
```

## 6. 质量门禁

- ✅ 所有脚本 bash -n 语法校验通过
- ✅ 诊断脚本覆盖目标语言
- ✅ 火焰图支持 CPU/alloc/lock 三种事件
- ✅ GC 分析输出关键指标对比健康阈值
- ✅ 慢 SQL 分析关联执行计划
- ✅ 线程 dump 分析包含死锁检测
- ❌ 禁止无数据采集直接给出优化建议
- ❌ 禁止在诊断脚本中写入硬编码路径（使用参数传递）

## 7. 约束

- ❌ 不修改生产环境配置（只生成诊断脚本，不自动执行）
- ❌ 不生成特定框架的调优参数（如 Spring 线程池配置、Tomcat 连接数等，这些由架构师决定）
- ❌ 不擅自安装系统级工具（如 `async-profiler` 需用户自行安装，脚本只提供链接和安装说明）
- ❌ 不生成性能基准测试代码（只生成诊断工具，基准测试由压测团队负责）
---

> **来源 & 作者**
> - 公众号：华仔聊技术
> - 知识星球：华仔·AI高并发全栈训练营
> - 作者：王江华@huazai
