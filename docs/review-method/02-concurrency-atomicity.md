# 02 并发与原子性

> 复审维度 02 | 每个模块必查 | 9 透镜全覆盖，并发为本维度核心透镜
>
> 你是谁：分布式系统审查员。不凭记忆，逐条执行以下指令。每个检查项标注了透镜类型，覆盖
> 业务逻辑/工程正确性/分布式正确性/生产级/可扩展性/并发/微服务/性能/盲区 全部 9 个维度。

---


**执行本维度后，必须在审查报告中输出 `[02] 02 并发与原子性：发现 N 项`。缺失此标题 = 本维度未被执行。执行每条检查项时，标注来源维度编号（如 [02]）。**
## 检查项

### 2.1 TOCTOU 五模式 | 透镜：并发/工程/盲区

**必须检查**：所有"先检查再操作"的代码路径，检查与操作之间是否存在并发窗口。

**怎么查**：
```bash
# 模式A：read-then-write——selectById/selectOne 后紧跟 updateById 同实体
grep -rn -e 'selectById' -e 'selectOne' -e 'selectList' my-xhs-<module>/src/main/java/ -A3 | grep -B1 'updateById\|update('

# 模式B：select-then-delete——selectCount 后紧跟 delete
grep -rn -A3 'selectCount' my-xhs-<module>/src/main/java/ | grep 'delete'

# 模式C：get-then-set——GET 后比较再 SET 同 key
grep -rn -A5 'opsForValue().get' my-xhs-<module>/src/main/java/ | grep -B2 'opsForValue().set'

# 模式D：check-then-act——if(exists)后直接操作无锁
grep -rn -A3 'if.*exists\b' my-xhs-<module>/src/main/java/ | grep -v 'Lock\|synchronized\|compute'

# 模式E：catch 块 TOCTOU——try 有检查 → INSERT 冲突 → catch 盲更新（-A30 覆盖较长 catch 块）
grep -rn -A30 -e 'catch.*DuplicateKeyException' -e 'catch.*DataIntegrity' my-xhs-<module>/src/main/java/
```
以上是初筛，必须结合逐个文件完整阅读——同一个方法不同区域有不同 TOCTOU 模式。

**判定**：

| 模式 | 代码形态 | 判定条件 | 修复方向 |
|------|---------|---------|---------|
| A. read-then-write | `selectById → setXxx → updateById` | 同实体，中间无锁 | `LambdaUpdateWrapper` 按需字段更新 |
| B. select-then-delete | `selectCount > 0 → delete` | 中间新插入 → 少删 | `delete()` 返回值判断，不预查 |
| C. get-then-set | `GET → if >= threshold → SET` | 非 Lua，并发双过 | Lua `GET+compare+SET` 原子化 |
| D. check-then-act | `if exists then do` | 无锁无原子 | 锁或 Lua 或 CAS 重试 |
| E. catch 块未镜像 | try 有时间戳检查 → INSERT 冲突 → catch 盲 `upsert` | catch 用 `now()` 替代 eventTime | catch 用 eventTime；或完全镜像 try 的保护条件 |

**案例**：`NoteService.updateNote` selectById+setXxx+updateById 并发互相覆盖（`NoteService.java:212` `LambdaUpdateWrapper` 修复）；`CartSyncConsumer.upsertCartItem` catch 块无时间戳检查——旧事件覆盖新行（`CartSyncConsumer.java:129` catch 用 `eventTime` 替代 `now()` 修复）。

---

### 2.2 MyBatis-Plus 写陷阱 | 透镜：工程/盲区

**必须检查**：所有 `update(null, wrapper)`、`updateById`、对账 batchUpdate 调用。

**怎么查**：
```bash
# update(null, wrapper)——检查是否显式 set updatedAt
grep -rln 'update(null' my-xhs-<module>/src/main/java/ | xargs grep -L '\.set.*[Uu]pdatedAt\|\.set.*[Uu]pdate[Tt]ime' 2>/dev/null

# updateById 全字段回写——并发风险
grep -rn 'updateById' my-xhs-<module>/src/main/java/

# batchUpdate/updateBatchById——对账盲写
grep -rn 'updateBatchById\|batchUpdate' my-xhs-<module>/src/main/java/com/myxhs/*/job/
```

**判定**：

| 陷阱 | 后果 | 判定 |
|------|------|------|
| `update(null, wrapper)` 不显式 set `updatedAt` | MetaObjectHandler 不触发 → `updatedAt` 永不变 | grep 命中且无 `.set.(updatedAt` |
| `updateById(entity)` | 全字段回写，并发覆盖其他字段修改 | grep 命中且实体非刚 select 出来的快照 |
| 对账 `updateById(selectById快照)` | 盲写全字段，覆盖并发变更（幻影锁复活） | 对账 Job 中用 `updateById` → 改目标字段 UPDATE |
| 实体缺 `extends BaseEntity` 且无 `@TableField(fill=...)` | `createdAt`/`updatedAt` 全为 null | grep 实体类对比 |

**案例**：`NoteService.updateNote` `update(null,wrapper)` 漏设 `updatedAt`（`NoteService.java:245`）；`InventoryReconcileJob` 盲写全字段覆盖并发变更（`InventoryReconcileJob.java:104` 改 `updateAvailableStockOnly`）。

---

### 2.3 Lua 脚本原子性验证 | 透镜：并发/工程/分布式

**必须检查**：每个 Lua 脚本的 key 一致性、返回值语义、历史数据兼容、读后盲写。

**怎么查**：
```bash
# 列全部 Lua 脚本及 KEYS 使用
grep -rn 'KEYS\[' my-xhs-<module>/src/main/resources/lua/

# 多 KEYS——>1 需验证同 hash tag
grep -rn 'KEYS\[2\]\|KEYS\[3\]' my-xhs-<module>/src/main/resources/lua/

# Java 侧调用——比对返回值语义（1/0/-1/-2 全部分支处理）
grep -rn 'execute(.*Script\|execute(.*lua' my-xhs-<module>/src/main/java/
```

**判定**：

| 检查点 | 问题模式 |
|--------|---------|
| 多 key 不同 hash tag | `{noteId}` vs `{userId}` → Cluster `CROSSSLOT` 错误 |
| 返回值分支不全 | Java 只处理 `1`/`0`，漏 `-1`/`-2` 等异常码 → 异常静默 |
| 新脚本上线无迁移 | 新数据结构（Set 替计数器）上线无历史迁移 → 断崖归零 |
| 读后盲写 | Java 循环 GET 各桶再 SET total → 读后 preDeduct 被覆盖 |
| 注释与代码矛盾 | 注释"Cluster 兼容"但 key 无 hash tag |

**案例**：多 key Lua 跨 slot 报错，拆为单 key 正向 + 独立反向（`LikeService.java:73`）；`LIKE_SET_SCRIPT` 上线无迁移致历史点赞归零（`CounterService.java:309` 懒迁移）；`reconcileBuckets` 曾读后盲 SET→改原子 Lua。

---

### 2.4 分布式锁 | 透镜：并发/分布式/生产级

**必须检查**：分布式锁的 owner 标识、原子释放、超时设置、重试策略。

**怎么查**：
```bash
# 找所有 setIfAbsent 锁获取
grep -rn 'setIfAbsent' my-xhs-<module>/src/main/java/
```

逐锁检查三点：获取方式、释放方式、超时设计。

**判定**：

| 检查点 | 问题模式 | 判定 |
|--------|---------|------|
| 释放非原子 | `get(key) → equals → delete(key)` 三步非原子 | 误删他人锁 |
| owner 截断 | `UUID.substring(0, 8)` 碰撞概率非零 | 用完整 UUID |
| 锁超时 < 业务时间 | 锁 25s 但批量任务 30s+ 执行 | 锁提前释放，多实例并发 |
| 加锁+业务中间 NPE | `setIfAbsent` + `try{业务}finally{解锁}` 业务重时锁过期 | 超时安全锁或用 Redisson Watchdog |

**修复标准**：释放用 Lua `GET+比较+DEL` 原子化；owner 用完整 UUID；锁超时 > 业务最大执行时间 × 1.5。

**案例**：`FeedMessageRetryJob.releaseLock` 曾非原子三步释放（`FeedMessageRetryJob.java:202` Lua 原子化修复）；锁超时 25s→55s 覆盖 fullMessageRetry 批量。

---

### 2.5 共享可变状态 | 透镜：并发/性能

**必须检查**：多线程共享的 Map/Buffer/计数器的读写原子性。

**怎么查**：
```bash
# ConcurrentHashMap 的 compute/computeIfAbsent 后续非原子操作
grep -rn 'computeIfAbsent\|ConcurrentHashMap' my-xhs-<module>/src/main/java/

# commonPool 长时间任务——ForkJoinPool.commonPool() 被长任务阻塞
grep -rn 'runAsync\|supplyAsync' my-xhs-<module>/src/main/java/ | grep -v 'Executor\|pool'
```

**判定**：

| 场景 | 问题模式 | 修复 |
|------|---------|------|
| CHM 复合操作 | `computeIfAbsent → addAndGet` 两步非原子 | `compute` 单步原子 |
| 双 Buffer 交换 | swap 后旧 buffer 仍有线程写入 → 增量丢失 | 写入后检查 `current==buffer`，不一致撤销重试 |
| commonPool 长任务 | `CompletableFuture.runAsync` 跑分钟级阻塞全 JVM | 专用有界线程池 + `@PreDestroy` |

**案例**：`CounterBuffer.add()` swap 后跨代写入丢失（`CounterBuffer.java:86` 重试循环修复）；cart `merge` 用 `runAsync` 共用 `commonPool` 阻塞（改专用单线程池）。

---

### 2.6 重构签名参数语义 | 透镜：工程/盲区

**必须检查**：重构改变方法签名后，每个调用点的每个剩余参数语义是否仍然正确。**重构是产生并发 bug 的最高危时刻**——参数语义错误会让看似并发安全的代码实际不安全。

**怎么查**：重构后列出方法内所有 Redis key/DB 字段，逐个确认传入参数的语义匹配。重点关注：同名变量在不同数据结构中含义不同。

**判定**：`member` 在正向索引 = `userId`，在反向索引 = `bizId`。删参重构后反向索引用错值 → SREM 永远删不掉 → 孤儿数据。

**案例**：`rollbackLikeLua` 重构删掉 `reverseMember` 后，反向索引 SREM 用 `userId` 替代 `bizId(noteId)` 永远匹配不了（`LikeService.java:111` 恢复参数修复）。

---

### 2.7 @RequiredArgsConstructor + final 陷阱 | 透镜：工程/盲区

**必须检查**：用 `@RequiredArgsConstructor` 的类，是否有 `final` 字段同时又手工初始化值——这会阻止 Spring 注入。

**怎么查**：
```bash
# 找同时有 @RequiredArgsConstructor 和 final = new 的类
grep -rn '@RequiredArgsConstructor' my-xhs-<module>/src/main/java/ -l | xargs grep -l 'final.*= new\|final.*=.*;' 2>/dev/null
```

**判定**：`@RequiredArgsConstructor` 类中 `final String script = "..."` → Lombok 生成的构造器用字段初始值，Spring 注入的值被忽略 → 运行时字段是**编译期常量**而非配置值。

**修复**：Lua 脚本/StringRedisTemplate/配置类字段 → **非 final**，由 Spring 注入。

**案例**：`LikeUnlikeConsumer.versionCheckScript` 为 `final` → Lua script 字段永不更新（改为非 final 修复，`LikeUnlikeConsumer.java:52`）。

---

### 2.8 锁粒度与并发度 | 透镜：性能/可扩展性

**必须检查**：分布式锁的粒度是否过粗——一个锁保护无关对象导致不必要的串行化。

**怎么查**：
```bash
# 找所有锁 key 的构成方式
grep -rn 'setIfAbsent.*myxhs\|lockKey' my-xhs-<module>/src/main/java/
```
逐锁判断：锁 key 对应的保护范围是否 > 实际需要。

**判定**：
- 锁 key 用 `user:{userId}` 保护全部用户操作 → 没必要（用户自身串行）
- 锁 key 用 `product:{spuId}` 保护 `skuId` 级别操作 → 过粗，同一 SPU 的不同 SKU 无竞争却被串行
- 全模块一个锁 → 全局串行

**案例**：（全特性面预置检查项——my-xhs 02-07 无典型案例，但 08-15 未审模块的对账/定时任务锁可能有此问题。）

---

### 2.9 操作幂等性缺失 | 透镜：业务/生产级

**必须检查**：非幂等操作（计数加减、库存扣减、创建记录）是否具备幂等防护。

**怎么查**：
```bash
# 计数增减/库存操作——是否有关联的唯一键或幂等 key
grep -rn 'increment\|decrement\|incr\|decr' my-xhs-<module>/src/main/java/
# 每条检查：是否有前置 setnx/sadd 防重。MQ 消费者通过 removeMark 控制，见 04 维度。
```

**判定**：
- 点赞计数 `INCR` 无 `SISMEMBER` 前置检查 → 双击加 2
- 库存扣减 `INCRBY` 无幂等键 → MQ 重投多扣
- 创建记录无唯一约束 → 重复消费建重复行

**案例**：点赞正向索引 `SADD` 天然幂等，但计数 `INCR` 无前置检查——消费者重试导致重复计数（对账兜底修复）。

---

### 2.10 线程池配置与拒绝策略 | 透镜：并发/生产级

**必须检查**：所有自定义线程池的大小、队列长、拒绝策略是否合理；是否会因 CallerRunsPolicy 或无限队列导致隐蔽问题。

**怎么查**：
```bash
grep -rn 'ThreadPoolExecutor\|ExecutorService\|Executors\|@Async' my-xhs-<module>/src/main/java/
```
逐线程池确认：核心/最大线程数、队列类型和大小、拒绝策略。

**判定**：

| 陷阱 | 后果 |
|------|------|
| `Executors.newCachedThreadPool()` | 无上限创建线程→OOM |
| `Executors.newFixedThreadPool(N)` 无界队列 | 任务堆积→内存溢出 |
| `CallerRunsPolicy` + 调用方是被调用方的线程 | 线程池满了把任务塞回调用方→级联阻塞 |
| `DiscardPolicy` / `DiscardOldestPolicy` | 静默丢弃任务→数据丢失（同步情况下不能用） |
| `@Async` 不指定 Executor | 默认 SimpleAsyncTaskExecutor→每个任务新建线程 |

**修复标准**：用 `new ThreadPoolExecutor(N,M,KeepAlive,LinkedBlockingQueue(cap),NamedThreadFactory,CallerRunsPolicy)` 显式构造；队列有界；caller 是异步接收方时不能用 CallerRunsPolicy；@Async 用 `@Async("poolName")` 指定 pool。

**案例**：cart `merge` 用 `CompletableFuture.runAsync` 共用 `commonPool` 阻塞→改专用单线程池。coupon 模块有 `@Async` 可能不指定 pool（08 未审，预先列入）。

```bash
mvn compile test-compile -pl <module> -am
mvn test -pl <module>

# TOCTOU read-then-write
grep -rn -A3 'selectById' my-xhs-<module>/src/main/java/ | grep -B1 'updateById'

# update(null) 缺 updatedAt
grep -rln 'update(null' my-xhs-<module>/src/main/java/ | xargs grep -L '\.set.*[Uu]pdatedAt\|\.set.*[Uu]pdate[Tt]ime' 2>/dev/null

# 多 key Lua
grep -rn 'KEYS\[2\]\|KEYS\[3\]' my-xhs-<module>/src/main/resources/lua/

# 分布式锁非原子释放
grep -rn 'setIfAbsent' my-xhs-<module>/src/main/java/ -A5 | grep 'get\|equals\|delete'

# @RequiredArgsConstructor + final = new 组合
grep -rn '@RequiredArgsConstructor' my-xhs-<module>/src/main/java/ -l | xargs grep -l 'final.*= new' 2>/dev/null

# commonPool 无专用线程池——加 -A2 看下一行是否声明专用 pool
grep -rn -e 'runAsync' -e 'supplyAsync' my-xhs-<module>/src/main/java/ -A2 | grep -B2 'runAsync\|supplyAsync' | grep -v -e 'Executor' -e 'pool' -e 'ScheduledThread'

# catch DuplicateKeyException 盲写——-A30 覆盖较长 catch 块内的 updateById
grep -rn -A30 -e 'catch.*DuplicateKeyException' my-xhs-<module>/src/main/java/ | grep -E -e 'update\(|save|insert'
```
