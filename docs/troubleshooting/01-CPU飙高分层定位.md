# 第1题 | CPU 飙高：从监控大盘到命令行，分层定位

> 难度：★★★★☆｜频率：★★★★★｜区分度：高
> 关键词：Prometheus、node_exporter、Grafana、RED/USE、top -H、arthas、GC 线程、监控优先

## 问题
线上告警"某节点 CPU 飙高、load 冲高"，业务反馈接口变慢。你**从哪一步开始**排查？如何用监控缩小范围，再用命令定位到进程、线程、代码？

## 30 秒简洁回答
结论：**先看监控，再上命令**。第一步打开 Grafana/Prometheus：告警触发的是哪个节点、哪个服务、哪个指标（`node_cpu_seconds_total`/`node_load1`），趋势是突变还是渐变——先确认是"宿主机整体高"还是"某个服务高"。第二步用 SkyWalking trace + ELK 日志定位到具体服务/接口。第三步才是命令：`top` 定位进程（排除外部干扰）、`top -H -p <pid>` 定位线程（区分 GC 线程 vs 业务线程）、tid 转 16 进制查栈或 arthas 看热点。修完回监控大盘复测。

## 展开回答（高级开发级）
**排查不是从 `top` 开始，是从监控开始的**——命令是"验证监控假设"的手段，监控才是入口。

**第 0 步 · 看监控（可观测层）**
- 告警：项目有 13 类规则（`HighGcPause`/`HighJvmMemoryUsage`/`HighHikariPoolUsage`/`HighErrorRate`/`HighResponseTime`/`ServiceDown`/`RedisHighMemoryUsage`…），先看**哪个告警、哪个节点、什么时间**触发；
- 大盘（RED/USE）：
  - **USE（资源）**：`node_cpu_seconds_total`（各核/整体）、`node_load1`（运行队列）、`node_memory_*`、`node_filesystem_*`、`node_disk_io_*`——确认是 CPU/内存/磁盘/IO 哪一类；
  - **RED（业务）**：QPS、错误率、延迟，看业务是否同步劣化；
  - **趋势**：突变（发布/流量尖峰/故障注入）vs 渐变（泄漏/慢增长）——决定排查方向。
- 结论：先回答三个问题——**哪个节点、哪个服务、CPU 高是整体还是局部**。

**第 1 步 · trace + log 缩小范围**
- SkyWalking：找该时间段的慢链路/慢 span，锁定到具体服务与接口；
- ELK：查该服务关键时间点的 ERROR/WARN 与耗时日志。

**第 2 步 · 命令验证（监控指向哪层，才上哪层命令）**
- 若监控指向"宿主机整体 CPU 高"：`top` 看是哪个进程——**CPU 高 ≠ 你的服务高**，可能是外部进程（压测/备份/其他租户）；`top -H -p <pid>` 进一步看线程；
- 若监控指向"某服务 GC 高"：`jvm_gc_pause_seconds_sum` 上升 → `jstat -gcutil` 看 YGC/FGC → 堆 dump；
- 若指向"业务热点"：arthas `thread -n 3` + `profiler start --event cpu` 火焰图；
- **判据**：`top -H` 里 GC 线程（`GC Thread`/`G1 Main`/`G1 Conc`）烧 → 内存/GC 问题；业务线程（`http-nio-*`）烧 → 代码热点/锁竞争。

## 进一步回答（架构师层级）
1. **监控是排查的前提，不是事后**：节点（node_exporter）、JVM（micrometer：GC/线程/连接池）、业务（RED）、中间件（Redis/ES/Canal exporter）四层指标 + 告警分级 + 大盘，才能让"CPU 高"在 1 分钟内定位到层；
2. **告警要能区分"症状"与"根因"**：`HighGcPause` 与 `HighCpu` 是两回事，别让告警风暴淹没根因（SLO 错误预算 + Burn Ledger 收敛告警噪音）；
3. **容量模型**：CPU 是共享资源，要有单核/整体利用率基线 + 容器 cgroup 配额，核心服务独立节点池或 CPU 亲和；
4. **变更顺序**：先止血（扩容/限流/摘流），再定位（火焰图/GC 分析），最后根治，避免"优化"阻塞"止血"；
5. **复盘**：把根因与证据链沉淀成 runbook，下次同类告警直接复用（本项目的 `docs/troubleshooting/` 即此目的）。

## 理解与复述提示（学习使用，面试时不要直接念）
- **问题本质**：CPU 高是"分层定位"问题，但**分层的第一层是监控，不是命令**。
- **回答顺序**：看监控（哪个节点/服务/指标、趋势）→ trace/log 缩小范围 → 命令验证（top→top -H→jstack/arthas）→ 修复 → 回大盘复测。
- **必记关键词**：监控优先、RED/USE、node_exporter、node_load1、node_cpu_seconds_total、GC 线程 vs 业务线程、tid 转 16 进制、火焰图。
- **必须明确的边界**：load 高 ≠ CPU 高（可能 IO 阻塞）；GC 线程烧 ≠ 业务热点；容器里要看本命名空间指标；监控趋势（突变/渐变）决定方向。
- **常见错误**：**一上来就 `top`/`jstack`（盲查，不知道查哪个节点/哪个进程）**；把 GC 线程高当业务代码慢；混淆 load 与 CPU；只看瞬时值不看趋势。
- **个人信息（真实故障需补充）**：告警名与节点、监控趋势（突变/渐变）、最终热点方法、修复前后 CPU/load/P99 对比。
- **自测要求**：30 秒能说清"监控→trace→命令"三步；3 分钟能说明 RED/USE 与 GC/业务线程的分支；能回答"监控大盘上先看哪几个指标"。

## 追问与参考回答
**追问1：为什么不能一上来就 top/jstack？** → 因为不知道查哪个节点、哪个进程、哪一类问题。16 核宿主机上跑十几个进程，盲查可能盯错进程；监控先给出"哪个节点 load 高、是 CPU 还是 IO、哪个服务指标异常"，命令才有目标。命令是验证假设的，不是找假设的。
**追问2：load 高但 CPU 空闲（%id 高）说明什么？** → load 是运行队列长度，含 D 状态（不可中断 IO）；CPU 空闲但 load 高，监控先看 `node_disk_io_*`/`node_filesystem_avail_bytes`，命令再上 `iostat -x 1`/`ps -eLo stat,wchan`，别去 jstack。
**追问3：top -H 里 GC 线程 CPU 高怎么往下查？** → 监控侧 `jvm_gc_pause_seconds_sum` 会同步上升；命令侧 `jstat -gcutil <pid> 1000` 看 YGC/FGC 频率，`jcmd GC.heap_info` 看堆；频繁 YGC=新生代小/分配速率高，频繁 FGC=泄漏/大对象，再 `jmap` 定位。

## 示例与使用说明
| 项 | 内容 |
|---|---|
| 示例序号 | 示例1 |
| 形式 | 监控查询 → 命令验证（本项目真实输出） |
| 验证要求 | 完成"监控缩小范围 → 命令定位进程/线程"并给结论 |
| 使用边界 | 基于本项目真实环境（Prometheus 19090、node_exporter 9101、counter pid=526388、product pid=422169） |

```bash
# 【第0步 · 监控】先问"哪个节点、哪一类"
curl -s 'http://127.0.0.1:19090/api/v1/query?query=node_load1'
#  → node_load1{instance="192.168.0.142:9101"} = 1.1（16 核，正常）
curl -s 'http://127.0.0.1:19090/api/v1/query?query=node_cpu_seconds_total'   # 各核 CPU 时间
curl -s 'http://127.0.0.1:19090/api/v1/query?query=jvm_gc_pause_seconds_sum'  # GC 是否异常
# 告警规则: config/prometheus/alert_rules/myxhs_rules.yml（HighGcPause/HighHikariPoolUsage/...）

# 注入场景：外部进程抢 CPU（模拟"宿主机整体高"）
stress-ng --cpu 8 --timeout 40s &
#  → 监控 node_load1 从 1.1 冲高到 ~4.5（趋势：突变）

# 【第2步 · 命令验证】监控指向"宿主机整体 CPU 高" → top 定位进程
top -bn1 | head              # stress-ng 线程一排 100.0% CPU
top -bn1 -H -p 526388 | head # counter 服务所有 java 线程 S(leeping) 0.0%
                             #  含 "GC Thre+"/"G1 Main"/"G1 Conc" 也全是 0.0%
# 结论：CPU 高是外部 stress-ng 进程，counter 服务自身无热点、无 GC 压力
# 佐证：product 接口 /api/product/category/tree 在干扰期间仍 23,029 RPS

# 【第2步 · 命令验证】若监控指向"Java 业务热点"，用 arthas
java -jar /opt/arthas/arthas-boot.jar 526388 -c "thread -n 8"
#  → 最热线程 "System Clock" cpuUsage=0.87%，其余 RocketMQ/lettuce 线程 TIMED_WAITING
```

## 面试官评分点
**高级开发级通过标准**：能分层（监控→trace→命令）；能权衡（GC vs 业务线程、load vs CPU、趋势突变/渐变）；能验证（监控指标 + 命令输出互相印证）。
**架构师加分项**：能构建（四层监控 + 告警分级 + 大盘，1 分钟定位到层）；能定义边界（单核/整体、cgroup、load 语义）；能兜底（先止血后根治、runbook 沉淀）。
**危险信号**：**一上来就 top/jstack 盲查**；不看告警与趋势；把 GC 线程高当业务热点；容器里看宿主机指标误判。

## 实战练习
1. 打开 Grafana/Prometheus，先列出"CPU 飙高"你会查的 5 个指标（node_cpu/node_load1/jvm_gc/tomcat_threads/hikaricp），再复现 stress-ng 注入观察趋势。
2. 用 `wrk` 压一个业务服务，监控侧看 QPS/latency/GC，命令侧 arthas `thread -n 3` + `profiler` 火焰图，两边对得上才算闭环。
3. 模拟 GC 线程高（小堆+高频分配），走"监控 jvm_gc_pause → jstat → jcmd → jmap"完整路径。

## 版本与来源
- Prometheus/Node Exporter（node_load1、node_cpu_seconds_total 等指标语义）
- arthas 4.3.5（https://arthas.aliyun.com/doc）
- OpenJDK 17（jstat/jstack/jmap/jcmd）
- stress-ng 0.17.06；Linux top/uptime（man top）

## 真实性说明
技术方法可直接学习；示例命令与输出基于**本项目真实演练**（Prometheus 19090 查 node_load1=1.1、stress-ng 注入 load→4.5、top -H 显示 counter 全线程 0%、arthas 附着 pid=526388 实测），非虚构；接口 RPS（16,124 基线/23,029 干扰中）为实测。
