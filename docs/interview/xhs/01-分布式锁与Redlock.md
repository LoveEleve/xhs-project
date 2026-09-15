# 第01题 | 分布式锁选型与 Redlock 之争

> 难度：★★★★☆｜频率：★★★★★｜区分度：高
> 关键词：Java、Redis、Redisson、Redlock、fencing token、看门狗、fail-open、Sentinel 切主、条件 UPDATE

## 问题
问题：你们为什么不用 Redlock？Redis 锁在哨兵切主时可能丢锁，凭什么保证不超卖、不重复执行？

## 30 秒简洁回答
结论：我们把锁分成两类——**效率锁**和**正确性锁**。Redis/Redisson 锁只用来降低并发冲突（效率），哨兵切主窗口内丢锁是我们接受的代价；真正的正确性不依赖锁，靠 DB 唯一键、条件 UPDATE（乐观锁/状态机）、TCC fence 和对账兜住。Redlock 我们评估后没用：它对时钟与网络假设苛刻、运维复杂度高，而 Kleppmann 的质疑（缺少 fencing token 时 Redlock 同样不能保证正确性）成立——既然正确性最终落在数据库上，就没必要为锁引入 Redlock。项目里锁用 Redisson RLock（看门狗 15s 续期、重试 5 次/间隔 1s），解锁用 Lua 比对 value，防止误删别人的锁。

## 展开回答（高级开发级）
**1）锁到底解决什么问题。** 先区分诉求：① 防止同一资源被并发修改造成数据错乱（正确性）；② 减少无谓的并发计算/惊群/重复拉取（效率）。正确的架构是：锁负责效率，数据库约束负责正确性。任何"只靠锁"保证正确性的方案，都必须在锁之外留一条兜底。

**2）我们的锁实现。** Redisson RLock，`lock-watchdog-timeout=15000`（默认 30s 缩短到 15s，保证崩溃后锁更快释放）、`retryAttempts=5`、`retryInterval=1000ms`；业务侧用 `@DistributedLock` 注解 + SpEL 生成 key（如 `order:create:lock:{userId}` 10s、`inventory:init:lock:{skuId}` 30s、`cart:reconcile` 600s）；解锁一律用"GET==ARGV 才 DEL"的 Lua，避免锁过期后被误删；对账类任务 RV30 起改成随机 token + CAS 删除。

**3）切主丢锁窗口与后果。** 哨兵切主实测 2.3s 完成。主从异步复制意味着锁可能没同步到新主，出现"两个客户端同时持锁"的窗口。我们的应对是"锁丢了也不会错"：库存扣减是 `WHERE available>=qty` 的条件 UPDATE + 预扣幂等表；订单状态用状态机条件流转（`WHERE status=期望值`）；券限领在一个 Lua 里原子完成；TCC 用 fence 表拒绝悬挂的 Cancel/Confirm；最后还有对账 Job 收敛差异。并发压测：20 并发预扣不超卖（149→129），超量整单拒绝。

**4）为什么不用 Redlock。** Kleppmann 的论证：Redlock 依赖各节点时钟与网络延迟假设，进程暂停（GC/虚拟机挂起）后仍可能持有一个"实际已过期"的锁；正确做法是 fencing token——下游拒绝旧 token 的写。antirez 的回应是效率视角：Redlock 已比单实例好，且多数场景不需要 fencing。我们的结论：既然正确性靠 DB 约束，Redlock 的额外复杂度（5 个独立实例、时钟敏感、争议未决）换不来实际收益；即便要用 fencing，也应该是数据库版本号而不是锁服务。

**5）什么时候必须更严格的锁。** 当临界区操作无法用幂等/CAS 收敛（如外部系统不可幂等、资源分配必须严格串行）时：优先 ZK/etcd（CP，会话+临时节点），或直接数据库行锁 `SELECT ... FOR UPDATE`（单库场景最稳），并配版本号做 fencing。宁可牺牲可用性和延迟，也不要假装 Redis 锁是 CP 的。

## 进一步回答（架构师层级）
**统一锁治理**：注解 + AOP 统一入口（key 规范=业务前缀+粒度，禁止业务自己拼 key）；明确锁粒度（用户/SKU/订单维度）与超时策略（业务耗时×2 且必须有看门狗）；AOP 顺序：消息幂等→限流→分布式锁→业务幂等，避免"先拿锁再被限流"浪费锁资源。
**锁与幂等的边界**：能设计成幂等写（唯一键/状态机）的，不要加锁；锁只在"幂等无法表达"时使用。缓存击穿用"单飞"（tryLock + double check），抢不到锁的线程读旧值而不是阻塞，把锁失败当降级路径而不是异常。
**多活/跨机房**：Redlock 解决不了网络分区——分区时两个机房各自持锁是必然的。要么业务按单元化切分（同单元内单写），要么用全局单调序列（DB/发号器）+ 下游 fencing 拒绝旧序列。
**可观测与演练**：锁等待时长、获取失败率、持锁时长分布、锁自动释放（看门狗续期失败）都要有指标；演练要覆盖"kill 持锁进程""切主丢锁""GC 长暂停"三个场景，验证锁丢失后的正确性兜底真的生效。

## 理解与复述提示（学习用，面试不要照念）
问题本质：面试官在验证你是否理解"锁的能力边界"，以及你有没有为"锁失效"设计兜底。
回答顺序：① 先分效率/正确性 → ② 讲实现参数 → ③ 承认切主丢锁窗口 → ④ 讲 DB 侧兜底（唯一键/条件 UPDATE/fence/对账）→ ⑤ 解释为什么 Redlock 不划算 → ⑥ 讲什么时候该上 CP 锁/fencing。
记忆钩子："锁管效率，DB 管正确；丢锁不丢数据，靠的是条件更新和对账。"

## 必记关键词
效率锁 / 正确性锁、fencing token、看门狗（watchdog）、2MSL 无关（那是 TCP）、fail-open、条件 UPDATE、唯一键幂等、TCC fence、Sentinel 切主窗口、单飞（singleflight）。

## 必须明确的边界
- Redis 锁**不是**正确性保证，尤其在主从/Sentinel 架构下；不要宣称"有锁就不会超卖"。
- Redlock 不是银弹：无 fencing 时同样存在时钟/暂停假设问题；引入它需要 5 个独立 master，运维成本与收益要算清。
- 看门狗只能续期"进程活着"的锁；STW/进程被 kill 后锁仍会按 TTL 释放，靠的不是锁，是下游约束。
- fail-open（Redis 不可用时放行）是有意选择：锁是效率设施，不能因它让整个交易不可用；但必须保证下游幂等/条件更新在位。

## 常见错误
- 一上来"把 TTL 调短/加 Redlock"而不排查临界区为什么需要锁。
- `SETNX` 没有过期时间（死锁）或没有 value 校验（误删他人锁）。
- 锁粒度太大（锁全表/锁用户所有操作）导致吞吐骤降，或嵌套加锁导致死锁。
- 解锁不做 owner 校验；锁续期依赖业务 while 循环手写。
- 把锁失败当异常抛出，不做降级（应该读旧值/排队/快速失败）。

## 本项目真实证据（面试可引）
- `common/config/RedissonConfig.java:104,131`：看门狗 15s、retryAttempts=5/1000ms、主从连接池 16。
- `common/aspect/DistributedLockAspect`：SpEL key、Redis 异常 fail-open（:70），日志与指标留痕。
- 解锁安全：order 用 Lua 比对后 DEL（`OrderService:101`）；cart 对账锁 RV30 改为随机 token + CAS 删除。
- 切主演练：RV19 D2 实测 Sentinel 主 6379→6380 切换 **2.3s**，458 个状态 key 无损、Agent 会话无错乱。
- 正确性兜底：库存 20 并发不超卖（条件 UPDATE+幂等表）；券 10 并发严格限领 2 张（单 Lua）；TCC 11 场景全过（fence 拒悬挂）；8 个对账 Job 收敛差异。
- 不选 Redlock 的理由记录在 D02/RV09（技术必要性：触发条件、替代方案、止损规则）。

## 自测要求
- 30 秒能说清：效率/正确性二分 + 丢锁后的兜底。
- 3 分钟能说明：看门狗机制、切主窗口、fencing token、为什么 Redlock 不划算。
- 能回答：如果下游完全不能幂等，你怎么设计这个临界区？

## 追问与参考回答
**追问1：Redlock 到底错在哪？**
回答：两个层面。① 理论：它假设多个独立 Redis 的时钟速率近似、网络延迟远小于锁 TTL；进程获得锁后若发生长 GC 或虚拟机暂停，锁在服务端已过期但进程不自知，仍会写下游——这一点单实例和多实例都存在，Redlock 没解决，fencing token 才能解决。② 工程：5 个独立 master 的部署与时钟/监控成本，换来的"更强"在多数字节场景并不兑现。所以 Kleppmann 说"不要在正确性场景用"，antirez 说"效率场景足够好"——两边其实不矛盾，关键看你把锁放在哪一类。

**追问2：锁丢了为什么不超卖？**
回答：因为扣减不是"读-改-写"，而是条件更新：`UPDATE t_inventory SET available=available-? WHERE sku_id=? AND available>=?`，影响行数为 0 就是失败；Redis 分桶预扣用 Lua 在同一脚本里校验并扣减；再叠加预扣幂等表（同订单同 SKU 只扣一次）。锁只是把并发冲突降下来减少失败重试，不是正确性来源。

**追问3：看门狗怎么工作？会不会续出问题？**
回答：Redisson 加锁成功后启动定时任务，每 `lockWatchdogTimeout/3`（我们 15s/3=5s）续期一次，直到显式 unlock 或进程死亡；锁的 TTL 与续期绑定，进程被杀后最多 15s 锁自动释放。风险是续期线程被 STW 饿死导致锁提前过期，所以我们从不假设"持锁期间绝对安全"，下游兜底永远在位。

**追问4：解锁为什么一定要比对 value？**
回答：如果锁已过期并被别人重新获取，无条件 DEL 会删掉别人的锁，造成"两个线程同时认为自己持锁"。Lua 里 `GET==ARGV 才 DEL` 是原子比较删除；同理，对账任务 RV30 用随机 token 也是这个道理。

**追问5：什么场景你会用 ZooKeeper/etcd？**
回答：临界区操作无法幂等、且必须严格串行时，例如"全局唯一资源分配、选主、分布式任务只跑一次"。ZK 临时顺序节点天然解决会话失效释放；etcd 有 lease + revision。代价是运维与延迟，所以只在真正需要 CP 的地方用，不要全站上。

**追问6：fail-open 会不会让并发失控？**
回答：会短暂增加并发冲突，但不会造成数据错误（条件更新会拦住）。我们选择 fail-open 是因为：Redis 不可用时业务（购物车、券）本身就不可用，锁再 fail-closed 只会把可用性问题扩大；而正确性边界由 DB 守。监控上对"降级放行"计数告警，事后看是否有异常并发。

## 示例与使用说明
**加锁（Redisson，看门狗自动续期）**
```java
RLock lock = redisson.getLock("order:create:lock:" + userId);
boolean got = lock.tryLock(0, 10, TimeUnit.SECONDS); // 不等待、10s 租约
if (!got) { return "请勿重复提交"; }
try {
    // 条件更新 + 唯一键幂等，业务不依赖锁的正确性
} finally {
    lock.unlock();
}
```
**安全解锁（Lua 比对 value）**
```lua
if redis.call('get', KEYS[1]) == ARGV[1] then
  return redis.call('del', KEYS[1])
else
  return 0
end
```
**正确性兜底（条件 UPDATE + 唯一键）**
```sql
UPDATE t_inventory SET available = available - #{qty}
 WHERE sku_id = #{skuId} AND available >= #{qty};
INSERT IGNORE INTO t_inventory_prededuct_idem(order_id, sku_id) VALUES(#{orderId}, #{skuId});
```

## 面试官评分点
**高级开发级通过标准**
- 能分层：区分效率锁/正确性锁，主动指出切主丢锁窗口。
- 能权衡：解释 Redlock 的收益/成本与 Kleppmann 争议，不盲目排斥也不神化。
- 能验证：用条件更新、幂等表、对账、切主演练说明"丢锁不丢数据"。

**架构师加分项**
- 能构建：统一锁治理（注解/key 规范/粒度/超时）、锁失败降级路径、fencing 设计（DB 版本号）。
- 能定义边界：锁 vs 幂等 vs 事务的职责分工；fail-open/fail-closed 的选择标准。
- 能兜底：锁等待/失败/持有时长指标 + kill/切主/长 GC 三类演练。

**危险信号**
- 一上来调短 TTL 或上 Redlock，不讨论临界区正确性来源。
- 认为"有锁就一定不超卖/不重复"。
- 解锁不校验 owner、无过期时间、锁粒度覆盖整张表。

## 实战练习
1. 在隔离环境 kill 一个持锁进程，验证锁在 15s 内释放、业务条件更新是否拦住重复写。
2. 用 Sentinel 手动 failover，复现"双持锁"窗口，观察唯一键/条件 UPDATE 是否仍然正确。
3. 压测 same-key 并发（如 20 并发同用户下单），对比"有锁无兜底"与"有锁+条件更新"两种实现的最终一致性。

## 版本与来源
- Redisson 文档（lock watchdog、retryAttempts/retryInterval 语义）。
- Kleppmann《How to do distributed locking》与 antirez 的回应（Redlock 之争原文）。
- Redis 官方文档：Sentinel 复制模型与 failover 语义；`SET NX PX` 原子性。
- RFC 9293（TCP，用于理解协议级超时与连接生命周期，与锁无关）。
- 本项目：`RedissonConfig.java`、`DistributedLockAspect.java`、`OrderService.java`、RV19 演练记录、D02/RV09 设计文档。

## 真实性说明
本项目确实使用 Redisson 锁并做过切主演练（2.3s、状态无损）；"不用 Redlock"是设计决策而非事故复盘；所有参数（15s/5 次/1s）来自代码。示例代码为讲解用简化版，真实实现在 `common/aspect` 与各服务 Service 中。
