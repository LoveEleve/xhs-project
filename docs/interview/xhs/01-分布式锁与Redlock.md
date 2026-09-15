# 第01题 | 分布式锁与 Redlock 之争

> 难度：★★★★☆｜频率：★★★★★｜区分度：高
> 关键词：Redisson RLock、Sentinel、Watchdog、效率锁 vs 正确性锁、fencing token、降级放行、唯一键/乐观锁/fence

## 问题
问题：你们为什么不用 Redlock？Redis 锁在哨兵切主时可能丢锁，凭什么保证不超卖、不重复执行？

## 面试可讲版（五段式）

**① 业界背景（2016 年的 Redlock 之争）**
Martin Kleppmann（《DDIA》作者）发文《How to do distributed locking》质疑 Redlock，antirez（Redis 之父）写《Is Redlock safe?》回应。争论的核心不是"Redis 锁能不能用"，而是**用途**：
- Kleppmann 的批评：Redlock 的安全性依赖"各节点时钟漂移有界"的假设；进程 GC 停顿或网络延迟会打破时序——锁可能在持有者不知道的情况下过期，出现两个客户端同时持锁。更关键的是缺 **fencing token（单调递增令牌）**：资源端无法拒绝"上一个过期锁持有者"的迟到写入。
- antirez 的回应：合理运维下时钟假设成立，GC 问题对 ZK 锁同样存在，Redlock 在假设内安全。
- 业界共识：锁分两种用途——**效率（efficiency）和正确性（correctness）**。Redis/Redlock 只适合前者；正确性要么用共识存储（ZK 临时顺序节点/zxid、etcd revision）提供有序性做 fencing，要么让下游幂等 + 唯一约束兜底。Kleppmann 推荐 ZK 不是因为 ZK 锁"更好用"，而是因为它能天然给出单调 token。

**② 项目选择**
项目里是 **Redisson RLock（Sentinel 模式 + Watchdog 续期：`leaseTime=-1` 才启用 watchdog，15s 续期）**，锁切面 `@Order(50)` 落在"**消息幂等(1) → 限流(10) → 锁(50) → 业务幂等(100)**"的切面序里。两条失败语义**不一样**（答辩必须说准）：
- **抢锁失败**（等待超时、他人持锁）→ 抛 `LOCK_ACQUIRE_FAIL` 业务异常，**拒绝本次请求**（防重复提交，对调用方 fail-closed）；
- **Redis 连接异常** → catch 后**降级放行**（`DistributedLockAspect:68-74`），把锁让位于下游兜底——这才叫"效率锁"。
不做 Redlock：运维复杂、时钟假设苛刻，且正确性本来就不靠锁。

**③ 项目怎么兜底（这段就是答案）**
| 层 | 机制 | 证据 |
|----|------|------|
| 效率锁 | Redisson RLock + Watchdog 防重复执行；**抢锁失败拒绝请求、Redis 故障降级放行**，不假装它保证正确性 | `DistributedLockAspect:68-78,89-93` |
| 唯一约束 | uk_order_event_seq、uk_claim_no、outbox uk_order_sku_action、uk_user_sku 等，重复写直接冲突 | 各模块 Mapper/DDL |
| 乐观锁 | `UPDATE ... WHERE status=<期望值>`（订单状态机、券核销、支付单） | OrderEventService、CouponService |
| Fence 状态机 | `t_tcc_fence` status 1→2/3，`UPDATE ... AND status=1`；Cancel 先插 3 防空回滚、Try 遇 Cancel 判悬挂（等价于资源侧单调状态校验） | `TccFenceService:77-165` |
| 版本控制 | ES ExternalGte 拒绝陈旧写、社交事件 24h 版本窗口防乱序 | ProductIndexSyncConsumer、LikeUnlikeConsumer |
| 幂等兜底 | msgId 去重（2h/24h）、预扣幂等表 | counter/coupon/inventory |
| 最终一致 | 对账 Job 以 Redis 权威修 DB，或反向修复 | 各模块 reconcile |

**④ 坑（主动承认）**
- Sentinel 切主实测 2.3s，窗口内锁可能没同步到新主，出现"双持锁"——我们接受，因为锁丢了数据也不会错。
- Watchdog 只能续"进程活着"的锁；长 GC/进程被 kill 后锁会按 TTL 释放，下游兜底必须永远在位。
- 解锁防误放：Redisson 内置解锁用 Lua 校验锁 owner（线程标识），切面再包 `isHeldByCurrentThread()` 防误释放（`DistributedLockAspect:89-93`，unlock 失败由 Watchdog/TTL 兜底）；对账类锁 RV30 改成随机 token + CAS（不靠锁身份判断）。

**⑤ 话术**
> "Redis 锁在我们这儿是效率工具，不是正确性保证；正确性由 DB 唯一键、乐观锁、fence 状态机和版本控制承担，Redis 挂掉锁降级放行也不会产生脏数据。这跟 Kleppmann 的建议一致——要用多节点多数派（Redlock）才谈 fencing，我们评估后没走这条路。"

## 追问与参考回答
**追问1：Redlock 到底错在哪？**
两个层面。① 理论：它假设多节点时钟速率近似、网络延迟远小于 TTL；进程长 GC/虚拟机暂停后锁已在服务端过期而进程不自知——单实例和多实例都存在，Redlock 没解决，fencing token 才能解决。② 工程：5 个独立 master 的部署/时钟/监控成本，换来的"更强"在多数字节场景不兑现。
**追问2：锁丢了为什么不超卖？**
扣减不是"读-改-写"而是条件更新：`UPDATE ... SET available=available-? WHERE sku_id=? AND available>=?`，影响 0 行即失败；Redis 分桶预扣在同一条 Lua 里校验+扣减；再叠加预扣幂等表。锁只降低冲突概率，不是正确性来源。
**追问3：什么场景必须上 ZK/etcd？**
临界区无法幂等、必须严格串行（全局唯一资源分配、选主、任务只跑一次）。ZK 临时顺序节点天然给出单调序列，etcd 有 lease+revision。代价是运维与延迟，只在真正需要 CP 的地方用。
**追问4：fail-open 会不会让并发失控？**
会短暂增加冲突，但不会错（条件更新拦住）。Redis 不可用时业务本身就降级，锁再 fail-closed 只会扩大可用性问题。对"降级放行"计数告警，事后看异常并发。**注意区分**：抢锁失败 → 拒绝请求；Redis 故障 → 放行——两种语义答辩时要分开讲。
**追问5：如果下游完全不能幂等呢？**
那就必须给下游加 fencing：全局单调序列（DB/发号器/状态机版本号），资源端拒绝比当前版本旧的 token；或者用 DB 行锁把临界区收进单库事务。此时 Redis 锁不适合承担该职责。

## 面试官评分点
**高级开发级**：能区分效率锁/正确性锁；主动指出切主丢锁窗口；能列出下游兜底（唯一键/乐观锁/fence/版本/对账）。
**架构师加分**：fencing token 设计（单调序列 + 资源端拒绝旧 token）；锁与幂等/事务的职责边界；锁治理（注解/key 规范/失败率/持有时长指标 + kill/切主/长 GC 演练）。
**危险信号**：一上来调 TTL 或上 Redlock；认为"有锁就不会超卖"；解锁不校验 owner。

## 本项目真实证据
- `RedissonConfig`：`lock-watchdog-timeout=15000`（注释：Sentinel 切换窗口 5-30s 取 15s）、retryAttempts=5/retryInterval=1000ms、master/slave 连接池 16；文件头即"Redisson Sentinel + DB 乐观锁"的取舍论证。
- `DistributedLockAspect`：SpEL 动态 key、`@Order(50)`、`leaseTime=-1` 走 watchdog、Redis 异常 fail-open(:68-74)、抢锁失败抛 `LOCK_ACQUIRE_FAIL`(:76-78)、`isHeldByCurrentThread()` 防误放(:89-93)。
- RV19 演练：Sentinel 6379→6380 切换 2.3s、458 状态键无损、会话无错乱。
- 正确性：库存 20 并发不超卖、券 10 并发限 2 张、TCC 11 场景全过（test-4 对账报告）、8 个对账 Job 收敛差异。
- 不选 Redlock 的论证在 D02/RV09（触发/替代/止损三问）。

## 发散追问地图（横向）
- 锁实现对比：Redis（Redisson）vs ZooKeeper（临时顺序节点）vs etcd（lease+revision）vs DB（行锁/唯一键）——各自的失效语义与延迟。
- 锁模式：可重入/公平/读写/联锁（MultiLock）/RedLock 的适用与成本。
- 惊群与排队：Redisson 订阅释放事件 vs 自旋重试 vs 信号量（Semaphore）。
- 无锁替代：CAS/乐观锁、序列化队列（Actor/单线程化）、Redis 单命令/Lua 串行。
- 超卖治理套路：预扣+回补、票池、MPSC 队列；与锁的边界。
- 观测与排查：锁等待/持有时长/失败率指标；死锁排查（thread dump+锁图）。
- 等价性辨析：锁 vs 幂等 vs 事务 vs 串行化的职责分工。

## 版本与来源
Kleppmann《How to do distributed locking》与 antirez 回应；Redisson Watchdog/retry 文档；Redis Sentinel 复制语义；RFC 9293（TCP 生命周期，区分题用）；本项目代码与演练记录。

## 真实性说明
Redisson RLock + Watchdog + 降级放行为代码事实；2.3s 切主为实测；"不用 Redlock"是设计决策；示例表格为答辩归纳，非日志原文。
