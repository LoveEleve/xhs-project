# 第24题 | JVM GC / 内存与线程池隔离

> 难度：★★★☆☆｜频率：★★★★☆｜区分度：中
> 关键词：G1、MaxGCPauseMillis、Metaspace、线程池隔离、MdcAwareExecutorService、类加载锁

## 问题
JVM 参数怎么配？线程池怎么划分和隔离？踩过什么 GC/内存问题？

## 面试可讲版（五段式）

**① 业界背景**
JVM 调优先看"目标"：延迟敏感（低 P99）用 G1 设暂停目标；吞吐敏感可 G1 调 region/并行度或 Parallel。堆不是越大越好（大堆 = 长 GC 停顿）；容器里要注意 **MaxRAMPercentage**（CPU/内存配额）而不是写死 Xmx。线程池的核心是**隔离**（不同业务不共享池）+ **有界队列 + 拒绝策略**。

**② 项目选择**
- **JVM 参数（运行进程实测）**：服务默认 `-Xms512m -Xmx512m`（inventory/order/search 1024m），**G1** + `MaxGCPauseMillis=200` + `MaxMetaspaceSize=256m`；基础设施容器（MQ/ES）用 `MaxRAMPercentage=75`；
- **线程池体系**：
  - `MdcAwareExecutorService`：包装线程池透传 traceId（MDC），避免异步链路日志断链；
  - **池隔离**：home 聚合外层 `aggregatorPool` 与内层 `batchFeignPool`（30/80/500）分离——修过"同池饥饿"（内层任务提交到被外层占满的池，队列不扩容 → 任务饿死到超时）；
  - 推荐池（recommend）、搜索池、IM 推送池各自独立；
  - Tomcat maxThreads=150 / minSpare=15 / maxConn=8192（自定义 Customizer）；
- **类加载锁事故（重点）**：`TraceContextHolder` 每请求 `Class.forName` 触发 JVM 类加载锁竞争 → product 详情压测只有 1,074 RPS；改成静态桥接（启动时一次性解析）后 **4,871 RPS（4.5x）**——profile 才发现的"非典型瓶颈"。

**③ 坑**
- **Metaspace 泄漏**：动态类加载（脚本引擎/CGLIB 代理）会撑爆 Metaspace——设 MaxMetaspaceSize 暴露问题而不是无限涨；
- **线程池无界队列**：`LinkedBlockingQueue` 无界时任务堆积 OOM；要有界 + 拒绝策略 + 监控（active/queue/rejected）；
- **MDC 断链**：异步/线程池切换时 ThreadLocal 不传递，日志串不上——必须包装 Runnable/Callable；
- **堆大小与容器**：容器内存 1G 而 Xmx 512m，剩下给 Metaspace/栈/直接内存；写死 Xmx 忽略容器配额会 OOMKill。

**④ 兜底**
- GC 目标：MaxGCPauseMillis=200（延迟优先）+ G1 自适应；
- 池隔离 + 有界队列 + 拒绝策略；指标监控（线程数/队列/拒绝）；
- 类加载/代理类相关的热点路径避免动态加载；
- 观测：GC 日志、jstat/jcmd、arthas/async-profiler。

**⑤ 话术**
> "JVM 我们是 G1 + 200ms 暂停目标，服务 512m 堆、Metaspace 256m 上限。线程池强调隔离：聚合服务外层和内层是两个池，踩过同池饥饿的坑；MDC 透传用包装线程池保证 traceId 不断链。最大的教训是一个看起来不像瓶颈的地方——每请求 Class.forName 触发类加载锁，product 只有 1,074 RPS，改成静态桥接后 4,871，4.5 倍。"

## 追问与参考回答
**追问1：为什么用 G1 不用 ZGC？** 堆小（512m~1G）G1 足够且稳定；ZGC 适合大堆低延迟（16G+），小堆收益不明显。
**追问2：MaxGCPauseMillis 是硬保证吗？** 不是——是目标值，G1 尽力达成；设置过小会导致更频繁 GC、吞吐下降。
**追问3：怎么定位类加载锁？** 线程 dump 看类加载相关锁等待 / async-profiler 火焰图；JFR 也可。修复=启动时预加载/静态 holder。
**追问4：线程池大小怎么定？** CPU 密集 ≈ 核数+1；IO 密集按耗时/CPU 比（可用 Little's Law 估算）；用有界队列+拒绝策略兜底。
**追问5：优雅停机怎么做？** 先摘流量（注册中心反注册）→ 等在途请求 → 缓冲刷盘 → 停池；本项目有优雅停机与缓冲刷盘记录。

## 发散追问地图（横向）
- GC：G1/ZGC/Shenandoah、GC 日志分析、停顿目标与吞吐取舍。
- 内存：堆外（DirectByteBuffer/Netty）、Metaspace、栈、容器 OOMKill。
- 线程模型：Reactor/虚拟线程（Loom）、线程数与上下文切换。
- 诊断：arthas、async-profiler、JFR、jmap/jstack。
- 工具链：容器内存配额（cgroup）、MaxRAMPercentage。

## 面试官评分点
**高级开发级**：能讲 GC 选择与暂停目标；知道线程池隔离与有界队列。
**架构师加分**：类加载锁这类非典型瓶颈（profile 发现）；MDC 透传与池包装；容器内存配额视角；池饥饿的真实修复。
**危险信号**：堆越大越好；线程池无界；忽略容器限制；只答"调参"不说定位方法。

## 本项目真实证据
- 运行进程实测：`-Xms512m -Xmx512m`（cart 等）、G1 + MaxGCPauseMillis=200 + MaxMetaspaceSize=256m、Tomcat mbean registry（`MyXhsTomcatCustomizer`）；
- `docs/reports/a2-jvm-tuning-20260917.md` + `batch-release-baseline-20260917.md:13`：类加载锁修复 1,074→4,871（无本地缓存口径）；
- home 池隔离注释（`NoteAggService` batchFeignPool 30/80/500）；`MdcAwareExecutorService` 实现。

## 版本与来源
Oracle G1 调优指南；HikariCP/线程池实践；本项目 A2 报告与代码。

## 真实性说明
JVM 参数来自运行进程实测；性能数字来自仓库报告（口径已标注）；类加载锁为 profile 结论。
