# 第1题 | CPU 飙高：先定位进程与线程，再谈优化

> 难度：★★★★☆｜频率：★★★★★｜区分度：高
> 关键词：Linux、top -H、load、arthas、thread、CPU 分层定位、GC 线程、stress-ng

## 问题
线上突然告警"主机 CPU 飙高、load 冲高"，业务反馈接口开始变慢，你如何分层定位到底是哪个进程、哪个线程、哪段代码在烧 CPU？

## 30 秒简洁回答
结论：先分层定位"进程 → 线程 → 代码"，再谈优化。第一步 `top` 看是哪个进程（先排除外部干扰进程）；第二步 `top -H -p <pid>` 定位到具体线程；第三步把线程号（10 进制）转 16 进制去 `jstack` 找栈，或用 arthas `thread -n 3` 直接看热点线程和栈。如果是 GC 线程在烧，改看 `jstat -gcutil` 与堆；是业务线程在烧，上 `profiler` 火焰图定位热点方法。修完复测 CPU 与 load 回落。

## 展开回答（高级开发级）
CPU 飙高不能只看"CPU 高"三个字就上 jstack，要**先分层**：

1. **进程层**：`top` 按 %CPU 排序，先确认是**哪个进程**在烧。关键心法——**CPU 高 ≠ 你的服务高**，可能是宿主机其他进程（备份、病毒、压测、其他租户），也可能是你的服务；
2. **线程层**：锁定进程后用 `top -H -p <pid>`，看是哪个**线程**在烧。Java 进程里线程名会带 `GC Thread`/`G1 Main`/`G1 Conc` 等，**如果烧的是 GC 线程 → 问题在内存/GC**（转 jstat/堆）；如果烧的是 `http-nio-xxx-exec`/业务线程 → 问题在代码热点或锁竞争（转 jstack/arthas）；
3. **代码层**：业务线程热点，用 arthas `thread -n 3`（热点线程+栈）或 `profiler start --event cpu`（火焰图）定位热点方法；锁竞争用 `thread -b` 找 BLOCKED；
4. **判据**：tid 转 16 进制（`printf "%x\n" <tid>`）后去 `jstack <pid> | grep -i 0x<tid>` 找对应线程栈，验证假设。

负载（load）与 CPU 的区别要分清：load 是**运行队列长度**（含不可中断 D 状态的 IO 等待），CPU 高不一定 load 高；load 高但 CPU 空闲常是**IO 阻塞/磁盘满/D 状态线程**——这时要看 `iostat`/`ps -eLo stat,wchan`，而不是 jstack。

## 进一步回答（架构师层级）
1. **统一入口**：宿主机/容器层要有 CPU/load 的基线告警（阈值 + 同比），出问题第一时间能拿到"哪个节点、哪个进程、哪个时间段"；
2. **可观测**：进程级（`top`/`pidstat`）+ JVM 级（arthas profiler 定时火焰图/APM CPU profile）+ 应用级（接口 CPU 耗时）三层打通，让"CPU 高"能一路下钻到方法；
3. **容量与隔离**：CPU 是共享资源，要评估**单核/整体利用率 + 容器 CPU 配额**（cgroup），避免一个服务吃满整机；核心服务设 CPU 亲和/优先级或独立节点池；
4. **变更顺序**：先止血（扩容/限流/摘流），再定位（火焰图），最后根治（优化热点/修 GC/改算法），别让"优化"阻塞"止血"；
5. **GC 专项**：GC 线程高频是另一类问题（堆过小/泄漏/大对象），要有独立的 GC 告警与预案，不与 CPU 业务热点混谈。

## 理解与复述提示（学习使用，面试时不要直接念）
- **问题本质**：CPU 是分层定位问题——进程 → 线程 → 代码，不能一步跳到 jstack 或调参。
- **回答顺序**：先 top 定位进程（排除外部）→ top -H 定位线程（区分 GC vs 业务）→ tid 转 hex 查栈 / arthas 热点 → 修复 → 复测。
- **必记关键词**：进程层、线程层、代码层、GC 线程、tid 转 16 进制、load vs CPU、火焰图。
- **必须明确的边界**：load 高 ≠ CPU 高（可能是 IO 阻塞）；GC 线程烧 ≠ 业务热点；单机多进程时先确认归属。
- **常见错误**：一上来 jstack 全量 dump 找不到重点；把 GC 线程高当业务代码慢去改；混淆 load 与 CPU；在容器里用宿主机 top 看错命名空间。
- **个人信息（真实故障需补充）**：哪个服务/节点、CPU 基线、触发时间段、最终热点方法、修复前后 CPU/load/P99。
- **自测要求**：30 秒能说清"进程→线程→代码"三步；3 分钟能说明 GC 线程与业务线程的不同排查路径；能回答"load 高但 CPU 空闲查什么"。

## 追问与参考回答
**追问1：load 高但 CPU 空闲（%id 很高）说明什么？** → load 是运行队列长度，含 D 状态（不可中断 IO）线程；CPU 空闲但 load 高，优先查 IO 阻塞：`iostat -x 1`（磁盘 util/await）、`ps -eLo stat,wchan`（D 状态线程在等什么）、`df -h`（磁盘满导致 IO 卡死）。别去 jstack。
**追问2：top -H 里 GC 线程 CPU 高怎么往下查？** → 先 `jstat -gcutil <pid> 1000 10` 看 YGC/FGC 频率与耗时，`jcmd <pid> GC.heap_info` 看堆使用；频繁 YGC=新生代太小/对象分配速率高，频繁 FGC=内存泄漏/大对象/晋升失败，再 `jmap -histo:live` 或 `jmap -dump` + MAT 定位对象。
**追问3：怎么证明"CPU 高不是我的服务"？** → `top -H -p <我的pid>` 看所有线程 %CPU：若业务线程、GC 线程都接近 0（sleeping），而 `top` 里是别的进程 100%，即可排除自身——这正是"先定位进程再定位线程"的价值，避免误优化自己的代码。

## 示例与使用说明
| 项 | 内容 |
|---|---|
| 示例序号 | 示例1 |
| 形式 | 诊断命令 + 本项目真实输出 |
| 验证要求 | 完成"进程→线程→代码"的分层定位并给出结论 |
| 使用边界 | 命令基于本项目真实环境（16 核宿主机、counter 服务 pid=526388、product 服务 pid=422169）执行 |

```bash
# 1) 基线
uptime                        # load average: 1.71, 1.74, 1.92（16 核）
# 2) 注入外部 CPU 干扰（模拟"别的进程吃 CPU"）
stress-ng --cpu 8 --timeout 40s &
# 3) 进程层：谁在烧 CPU
top -bn1 | head                # stress-ng 线程 100.0% CPU 一排
uptime                        # load average: 4.55（明显冲高）
# 4) 线程层：我的 Java 服务在不在烧
top -bn1 -H -p 526388 | head   # counter 服务所有 java 线程 S(leeping) 0.0%，
                               # 含 "GC Thre+"/"G1 Main"/"G1 Conc" 也全是 0.0%
# 5) 代码层：arthas 确认最热线程
java -jar /opt/arthas/arthas-boot.jar 526388 -c "thread -n 8"
#   最热线程 "System Clock" cpuUsage=0.87%，其余 RocketMQ/lettuce 线程 TIMED_WAITING
# 结论：CPU 飙高来自外部 stress-ng 进程，counter 服务自身无热点、无 GC 压力
# 业务佐证：product 接口 /api/product/category/tree 在干扰期间仍 23,029 RPS（8 个空闲核承接）
```

## 面试官评分点
**高级开发级通过标准**：能分层（进程→线程→代码）；能权衡（区分 GC 线程 vs 业务线程、load vs CPU）；能验证（tid 转 hex 对栈、arthas 热点）。
**架构师加分项**：能构建（CPU 基线告警 + 进程/JVM/应用三层下钻）；能定义边界（单核 vs 整体、cgroup 配额、load 与 CPU 语义）；能兜底（先止血后优化、GC 独立预案）。
**危险信号**：一上来就 jstack 全量 dump 没重点；把 GC 线程高当业务热点改；容器里看宿主机 top 误判；只调 JVM 参数掩盖外部干扰。

## 实战练习
1. 在本机 `stress-ng --cpu 4` 后，用 `top` + `top -H -p <任意java服务pid>` 复现"外部 CPU 高但 Java 线程全 0%"的定位过程。
2. 给一个业务服务压测（`wrk`），用 arthas `thread -n 3` + `profiler start --event cpu` 找真实热点方法，生成火焰图。
3. 模拟 GC 线程高（小堆 + 高频分配），走 `jstat -gcutil` → `jcmd GC.heap_info` → `jmap` 的完整路径。

## 版本与来源
- arthas 4.3.5（https://arthas.aliyun.com/doc）
- OpenJDK 17（jstack/jstat/jmap/jcmd）
- stress-ng 0.17.06（CPU 压测注入）
- Linux top/uptime/ss（man top）

## 真实性说明
技术方法可直接学习；示例命令与输出基于**本项目真实演练**（16 核宿主机、counter/product 服务、stress-ng 注入、arthas 附着 pid=526388 实测），非虚构；"stress-ng 干扰期间 product 仍 23,029 RPS"为实测数据。
