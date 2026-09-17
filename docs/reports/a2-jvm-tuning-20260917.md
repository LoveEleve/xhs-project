# A2 调优报告：线程池/GC/连接池/内核 + 平台级 25x 性能修复（2026-09-17）

## 一、头条：每请求 `Class.forName` 抢类加载锁（平台级 bug）
**现象**：product 压测 c=32 时 92/99 个 Tomcat 线程 `BLOCKED (on object monitor)`，吞吐被压在 ~1k RPS。
**根因**：`TraceContextHolder.inject/clearSkyWalkingCorrelation` 每次请求执行
`Class.forName("...TraceContext") + getMethod`；无 SkyWalking agent 时每次抛 CNFE，
类加载/失败查找走全局锁 → 全站所有服务（common 模块）被限速。

**修复**：`SkyWalkingBridge` 静态块一次性解析 Class/Method，之后仅字段判空 + 反射调用；
agent 未加载时零开销。

**前后对比（product `/api/product/spu/1`）**：

| 指标 | 修复前（c=32） | 修复后（c=32） | 修复后（c=64） |
|---|---:|---:|---:|
| RPS | **1,074** | **27,508** | **32,421** |
| P50 | 29ms | **0.93ms** | 1.47ms |
| P99 | 60ms | **22.5ms** | 13.8ms |
| 线程状态 | 92/99 BLOCKED | 正常 | 正常 |

> 提升 **~25-30 倍**；影响 15 个服务。已发布：product/home/cart；其余服务待批量发布（roadmap 记待办）。
> 注意：**A1 容量报告中的所有数字均为本 bug 存在时的下界**，新基线以 A2 为准（其余服务待复测）。

## 二、其余调优证据（未发现瓶颈）
| 维度 | 观测 | 结论 |
|---|---|---|
| GC | YGC +13/5s、YGCT 20ms、**无 FGC**；`-Xlog:gc*` 已开启（/data2/logs/gc-*.log） | 健康，非瓶颈 |
| Metaspace | 服务未设 MaxMetaspaceSize，M%≈used/committed（99%） | 正常，非风险（撤销此前误判） |
| Hikari | 压测中 active=0/pending=0（读路径走缓存） | 非瓶颈 |
| Tomcat | busy=33 / max=150 | 非瓶颈 |
| 内核 | somaxconn=4096、tcp_tw_reuse=2、max_syn_backlog=1024、ulimit=65535、file-max 无上限 | 已较优，无需调整 |
| JVM 硬化 | 统一 `-Xms=Xmx`、G1 MaxGCPauseMillis=100、HeapDumpOnOOM、GC 日志（release 脚本支持 `JVM_EXTRA`） | 已应用 |

## 三、附带修复与事故复盘
1. **home 线程池饥饿**（A3）：外层请求与内层 Feign 共用池 → 内层饿死；改独立 `batchFeignPool`，5.6→430 RPS，P99 1.6s→307ms。
2. **发布脚本三连修**：① `readlink -f` 对不存在路径返回自身 → 自引用软链（home 停机 2-3min，记入 SLO 燃烧账本）；② 优雅停机 >3s 导致新进程 `Port already in use`（product 发布失败）→ 增加"等端口释放（≤60s）"；③ 按模块 Xmx + dev profile + 版本保留 5 份。
3. ~~L1 Caffeine 本地缓存~~：**已按技术决策移除**（不做本地缓存；一致性/多实例/内存坑）。当前 product 无 L1 基线 = 4,871 RPS / P99 50ms。

## 四、待办（收敛）
- 批量发布 15 服务（携带本次 common 修复）并**重测容量基线**（A1 数字作废）；
- 复盘：类加载/反射热路径要静态化审计（全仓搜 `Class.forName`/`getMethod` 每请求调用）；
- GC 日志接入 Grafana（gc pause 面板）。
