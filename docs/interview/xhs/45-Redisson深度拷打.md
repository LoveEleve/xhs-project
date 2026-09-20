# 第45题 | 组件深度拷打：Redisson

> 难度：★★★★★｜频率：★★★★★｜区分度：高
> 关键词：RLock 可重入、显式租期 vs Watchdog、Sentinel 模式、fail-closed、锁与正确性边界

## 问题
Redisson 锁怎么用的？看门狗（Watchdog）是什么？租期怎么定？Redis 挂了加锁会怎样？

## 面试可讲版（五段式）

**① 原理层**
- **RLock 可重入**：底层 Hash（field=线程标识，value=重入次数），解锁用 Lua 校验 owner；未解锁的锁随 TTL 释放；
- **tryLock(wait, lease, unit)**：wait=最大等待时长，lease=持有租期；**只有 `leaseTime=-1` 才启用 Watchdog**（后台线程按 `lockWatchdogTimeout/3` 续期）；
- **Sentinel 模式**：客户端经哨兵发现主从、切主自动重连；本项目配置 master/slave 池 2/16、`timeout 10s`、`retryAttempts=5 / retryInterval=1000ms`、`setCheckSentinelsList(false)`；
- **本质**：Redis 锁不是正确性工具——切主异步复制可能丢锁（01 题）。

**② 项目真实用法（关键差异点：直连锁 + 显式租期）**
- **业务代码 0 个 `@DistributedLock` 注解**；`DistributedLockAspect`（SpEL key、`@Order(50)`、watchdog、Redis 异常 fail-open）是框架能力，**未接入业务**——答辩不能把它当项目事实；
- 真实用法：`redissonClient.getLock("myxhs:lock:{domain}:{bizKey}")` **直连 + 显式租期**：

| 场景 | 调用 | 租期理由 |
|---|---|---|
| 支付创建/退款 | `tryLock(3, 10s)` | 短临界区；抢不到→"系统繁忙"拒绝 |
| 用户资料/地址 | `tryLock(3, 10s)` | 防重复写；抢不到拒绝 |
| 缓存单飞刷新 | `tryLock(3或1, 10s)` | 抢不到走兜底/直接查 DB |
| 库存分桶自愈重建 | `tryLock(0, 30s)` | 重建耗时可控 |
| SPU 缓存重建 | `tryLock(0, 300s)` | 耗时较长，按上限给 |
| 热搜快照 / 索引重建 | `tryLock(0, 600s / 7200s)` | 长任务给足租期 |
| 5 个后台 Job（Outbox/补偿/超时） | `tryLock(0, 4~50s)` | 多实例互斥，抢不到跳过本轮 |

- **为什么不用 Watchdog**：显式租期可预测、按任务最坏时长给；watchdog 适合"业务时长不可控"的场景，本项目选择可控租期（**边界**：租期必须覆盖任务最坏时长，否则双执行——靠幂等/唯一键兜底）；
- **失败语义（代码事实）**：抢锁失败 → 拒绝（支付/用户）或跳过（Job）；**Redis 异常 → tryLock 抛异常 → 业务失败（fail-closed）**；解锁 23 处统一 `if (locked && isHeldByCurrentThread()) unlock()` 防误放。

**③ 事故/坑**
- **切主 2.3s（RV19）**：窗口内锁可能双持/丢失——正确性不靠锁，接受；
- 锁 key 按业务键打散（`myxhs:lock:payment:pay:{orderId}`），避免热 key；
- RV30 对账类锁改用随机 token + CAS（不靠 Redisson 锁身份判断）；
- **文档纠偏**：曾把"Watchdog 续期 + 降级放行"写成项目事实，实际是未接入的切面能力——已按代码校正（这也是"简历必须对得上代码"的实例）。

**④ 兜底（与 01 题同一张分层表）**
唯一键 / `WHERE status=期望值` 乐观锁 / TCC fence / 版本控制 / `MessageIdempotentHelper` 去重 / 对账 Job——锁只降低冲突概率，不放行任何一条正确性路径。

**⑤ 拷打追问**
1. **"Redisson 锁和 SETNX 手写锁区别？"** 可重入、自动续期、安全解锁（owner Lua）、等待唤醒（发布订阅）、公平锁/读写锁；手写锁这些都要自己实现且容易错。
2. **"`leaseTime=-1` 的坑？"** Watchdog 只在进程活着时续期；长 GC/被 kill 后锁按 TTL 释放；且续期依赖后台线程，对时钟/调度不敏感但依赖进程存活。
3. **"抢锁失败和 Redis 异常为什么处理不同？"** 本项目其实一致——**都失败**（拒绝/跳过/异常）。区别只是文案与入口：抢锁失败是预期内的竞争，Redis 异常是依赖故障。
4. **"锁粒度怎么定？"** 按业务键（orderId/userId/skuId），不用全局锁；好锁 key = 冲突范围最小化。
5. **"Sentinel 模式配置要注意什么？"** 地址要 `redis://` 前缀、`checkSentinelsList(false)` 避免启动强依赖哨兵列表、retry 5×1s 覆盖切主窗口。
6. **"为什么不用 RedLock？"** 接 01 题：效率锁不需要多数派；正确性有下游约束。运维复杂度不值。
7. **"怎么观测锁？"** 加锁失败/持有时长可埋点告警（本项目锁失败以业务异常/日志体现，未做专门指标——**主动承认的缺口**）。

**⑥ 话术**
> "Redisson 在我们项目里不是注解切面，而是关键路径直连、显式租期：支付/用户 10 秒、库存重建 30 秒、SPU 300 秒、索引重建 2 小时，后台 Job 抢不到就跳过。抢锁失败拒绝或跳过，Redis 挂了加锁直接失败——fail-closed，宁可暂时不可用也不冒险重复执行。看门狗我们没启用：显式租期更可预测，代价是必须覆盖任务最坏时长，兜底靠唯一键和幂等。切主 2.3 秒会丢锁，但正确性从来不押在锁上。"

## 发散追问地图（横向）
- Redisson 能力：可重入/公平/读写锁、信号量、CountDownLatch、布隆过滤器、限流器。
- 续期与失锁：Watchdog、fencing token、RedLock 争论。
- 连接与高可用：Sentinel/Cluster 模式配置、重试与超时、连接池。
- 工程治理：锁指标（失败率/持有时长）、超时租期设计、死锁排查。

## 面试官评分点
**高级开发级**：能讲 RLock 可重入/租期/解锁原理；知道切主丢锁。
**架构师加分**：显式租期 vs Watchdog 的取舍；fail-closed 的业务理由；锁 key 粒度与热 key；把"未接入的框架能力"与"项目事实"分开（诚信）。
**危险信号**：说项目用了 Watchdog 但答不出哪些方法（本项目的正确答案恰恰是"未用"）；把锁当正确性保证；锁不设租期。

## 本项目真实证据
- `RedissonConfig.java`（Sentinel 模式、retry 5×1000ms、池 2/16、watchdog 15s 仅 leaseTime=-1 生效）；
- 直连锁调用点：`PaymentService:163/438`、`UserService:93`、`UserAddressService:63/139/201`、`CacheHelper:179`、`InventoryService:657`、`SpuService:195/501/655`、`IndexRebuildJob:84/114`、`HotSearchService:197`、5 个 Job；23 处 `isHeldByCurrentThread()`；
- `DistributedLockAspect.java`（未接入业务）；`MessageIdempotentHelper.java`（真实消息幂等）。

## 版本与来源
Redisson 3.27.0；Redisson 官方文档；本项目源码与 RV19/RV30 记录。

## 真实性说明
"直连锁+显式租期+fail-closed"为代码事实（0 注解使用、23 处 owner 校验）；watchdog/fail-open 为未接入的切面能力并已明确标注；观测缺口（无专门锁指标）主动承认。

## 本轮补充（2026-09-20 锁语义复核）
- 直连锁**显式租期**（未启用看门狗自动续期）；**不用 RedLock**（争议大且需多主）。
- Sentinel 切主窗口锁语义受损 → 由 DB 唯一索引/条件更新兜底（"最终防线在 DB"原则，见决策台账 C1）。
