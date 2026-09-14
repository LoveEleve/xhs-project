# xhs 平台面试防御手册（18 个设计点 + 8 个高危问题）

> 用法：每个主题按 **设计 → 争议/备选 → 已知坑 → 兜底 → 话术** 五段掌握。
> 原则：**坑要主动承认，兜底要能说清边界**——面试官最反感"我们没问题"，最喜欢"我们知道这里有问题，所以这样兜"。
> 证据标注 `文件:行号`，面试前可对照源码。

---

## A. 分布式锁与并发控制

- **设计**：Redisson RLock（互斥/读写/公平），切面 `@Order(50)` 先锁后幂等；看门狗 `leaseTime=-1` 续期。业务另用 SETNX 单飞锁 + Lua 比较删除。
- **争议**：RedLock（多节点多数派）vs Redisson+DB 兜底 vs ZK+fencing。项目注释明确放弃 RedLock（运维复杂 + Kleppmann 已论证其局限）。
- **坑**：
  - Redis 异常时切面 **fail-open 降级放行**，锁保护失效；
  - 10s 锁在多 Feign 串行下可能过期，同用户出现两单；
  - 释放失败仅告警。
- **兜底**：DB 乐观锁/唯一键是最终防线；Sentinel 切主 `retryAttempts=5` + watchdog 缩到 15s；初始化锁失败直接拒绝。
- **话术**：
  > “我们把锁定位成效率工具而不是正确性保证。Redis 不可用时锁会降级放行，这是有意的——真正防重靠 DB 唯一键和状态机条件更新。Redis 单集群在 Sentinel 切主时确实有丢锁窗口，所以没上 RedLock，也没把 fencing 责任交给锁本身。”

## B. 缓存穿透/击穿/雪崩与一致性

- **设计**：CacheHelper 三件套——更新 DB 后删缓存重试 3 次、延迟双删 500ms、失败发 `CACHE_EVICT_TOPIC`；读 miss 用分布式锁 singleflight；空值占位 2 分钟；TTL 加 1/6 随机；商品侧补 Bloom + 逻辑过期异步刷新。
- **争议**：延迟双删（时间窗假设）vs 订阅 binlog/Canal 删缓存；空值缓存 vs 布隆过滤器（两层互补）。
- **坑**：
  - 500ms = 主从 200ms + 读 100ms + 余量，环境变慢就失效；
  - Bloom 启动时 DB 为空会导致后插数据被拦（运行态 #11，需重启重建）；
  - 缓存 Redis 与业务 Redis 已拆实例（allkeys-lru vs noeviction），但部分 cache 模板无业务引用。
- **兜底**：预热失败不阻塞启动；Bloom 不可用降级放行 + 空值兜底；Redis 故障直查 DB 不回填；逻辑过期防击穿。
- **话术**：
  > “延迟双删本质是赌时间窗，我们承认；所以配了三层保险：删缓存重试、MQ 广播失效、每日对账。如果重来一次，我会优先上 Canal 订阅，把时间窗假设拿掉。”

## C. 库存分桶 + Lua + DB 一致性

- **设计**：`inventory:{skuId}:total/bucket:N` 同 hash tag；`prededuct.lua` 原子做幂等检查→路由桶→不足遍历他桶→写预扣 Hash/ZSet；Java 侧 `floorMod` 路由（避免 Lua 双精度对大 ID 取模失真）；MySQL 预扣幂等表兜底；MQ 失败回滚 Redis。
- **争议**：单 Key DECR（简单、热点集中）vs 分桶（并发分散、需桶遍历）；Redis 权威 vs DB 直接扣。
- **坑**：reinit/resize 没有统一暂停屏障；幂等占位无 processing/sent 状态，崩溃会卡单；SKU 不存在时直接 ACK 可能出现“订单提交了库存没扣”。
- **兜底**：超时 Job 按 ZSet 60s 释放 + Outbox 补发 + 对账以 Redis 修 MySQL；canal 误删后自愈重建桶；20 并发/超量实测不超卖。
- **话术**：
  > “Redis 和 MySQL 之间没有事务，我们用的是‘Redis 先扣 + 幂等占位 + Outbox + 超时回补 + 对账’的最终一致。Lua 只保证单次 Redis 原子，跨存储的一致性靠补偿和对账，不装成强一致。”

## D. TCC fence（空回滚/悬挂）

- **设计**：仿 Seata TCC Fence：`t_tcc_fence` 唯一键插入 + 状态机 1(Try)→2(Confirm)/3(Cancel)；Try 遇 3 判悬挂拒绝；Confirm/Cancel 用 `UPDATE ... AND status=1` 幂等。
- **争议**：Seata AT/TCC/Saga vs 裸 TCC+Fence；Fence 独立表 vs 复用业务行状态；超时取消按 xid 明细 vs 按 SKU 聚合（旧实现已淘汰）。
- **坑**：Confirm/Cancel 对影响行数与库存状态校验不足；**TCC 与普通分桶是两本账**，对账 Job 有可能覆盖 TCC 状态。
- **兜底**：迟到 Confirm 被 fence 拒；并发双 Cancel 只有一次执行；超时 Job 先写 fence 再解冻；11 场景（幂等/空回滚/悬挂/超量/状态机）实测通过。
- **话术**：
  > “TCC 只用在专项链路，和普通分桶库存确实是两本账，文档里也标了风险。面试里我会主动说这是已知边界：混用场景必须靠对账隔离，而不是假装统一。”

## E. 事务消息 + 本地消息表 + 回查

- **设计**：半消息 → `executeLocalTransaction` 同事务写订单/明细/本地消息 → COMMIT；Broker 回查 `checkLocalTransaction` 查本地消息表决定 COMMIT/ROLLBACK/UNKNOWN。
- **争议**：事务消息 vs 纯本地消息表 vs Outbox 双写；回查查本地消息表（同事务、有索引）vs 查订单表；补发是 at-least-once 不是 exactly-once。
- **坑**：本地消息不标已投递时，30s 补发 Job 会**双投**；无 user_id 的补发走广播，分片下 LIMIT 截断可能乱序；MQ 全挂时下单 500 fail-closed。
- **兜底**：`markSuccessByTransactionId` 消除双投；补发指数退避 30s~480s×5 → 死信 status=3 + 每小时死信扫描重投；消费端幂等；75s 回查延迟注入实测库存只扣一次。
- **话术**：
  > “我们说的是最终一致，不是恰好一次。回查负责把半消息收敛，补发负责 Broker 挂了也能兜，重复投递由消费端幂等吃掉——每一层都有兜底，但每层都不是‘绝不出错’。”

## F. 幂等体系（四层）

- **设计**：① Redis SETNX（注解式 + 消息去重 Helper + 业务键）② DB 唯一键（`uk_msg_id`/`uk_claim_no`/`uk_order_event_seq`/预扣幂等表）③ 状态机条件 UPDATE ④ Set 结构天然幂等。
- **争议**：Redis 去重（快、TTL）vs DB 唯一键（强、可回滚）vs 状态机（终态收敛）；Helper 注释明说“强幂等必须 DB 唯一索引兜底”。
- **坑**：Redis 不可用时幂等 fail-open；通知表无天然唯一键只能靠 24h Redis 幂等；幂等键释放策略要对（事务提交前失败才释放）。
- **兜底**：所有关键写路径都有唯一键；状态机收敛重复事件；异常分类决定是否删标记。
- **话术**：
  > “幂等不是一把锁能解决的，是 Redis 去重 + 唯一键 + 状态机三层同时上。Redis 只挡第一波，最终防重靠数据库约束。”

## G. 消息乱序与版本控制

- **设计**：业务版本号——库存 `eventTime` Lua 原子比较；社交 `actionTime` + 24h 窗口；购物车 CLEAR 屏障 + DB `updatedAt` 比较；ES 用 Canal `ts` 作 external version + tombstone；首页已删标记 5 分钟拦晚到推送。
- **争议**：单队列顺序消费/分区有序 vs 业务时间戳版本；时间戳 vs binlog offset；最终态交对账 vs 严格因果序。
- **坑**：同毫秒版本相等会**互相跳过**；`eventTime=null` 不拦截；跨 action 共用版本 key 会让早到的 PRE_DEDUCT 被跳过；ES 同毫秒同版本写入被拒。
- **兜底**：购物车 catch 二次校验防 TOCTOU；计数用 Set 天然抗乱序；search 实测 001→000→002 拒绝低版本；相等场景靠对账收敛。
- **话术**：
  > “时间戳版本不是严格因果序，同毫秒会打架，我们承认。所以关键链路要么用天然幂等的结构（Set），要么加屏障（CLEAR），最后再加对账兜底。”

## H. Feed 推拉结合

- **设计**：普通作者写扩散（粉丝 Pipeline 批量 ZADD，500/批 + Redis 进度断点）；大 V（阈值 10w 粉，`home.feed.big-v-threshold=100000`）写发件箱走拉模式；读侧合并收件箱 + 大 V 发件箱。
- **争议**：纯推（读快写慢）vs 纯拉（大 V 友好）vs 推拉结合；阈值取多少。
- **坑**：进度 key TTL 只有 1h；已删标记只 5min，晚到推送可能写回；收件箱膨胀靠异步裁剪。
- **兜底**：ZADD 幂等可重推；content 侧 30s 本地消息补发 + 60s 推送未完成补偿（MySQL `push_status` 兜底）；断点续推实测可跳过已推粉丝。
- **话术**：
  > “推拉结合是为了控制写扩散成本。大 V 一条动态如果推给十万粉丝，写放大不可接受，所以大 V 走拉、普通用户走推，失败用本地消息和状态位兜。”

## I. IM 长连接跨实例

- **设计**：本机 session Map + Redis 路由 `im:route:{userId}`（90s TTL 心跳续期）+ 每实例专属 Pub/Sub channel；网关一致性哈希（150 虚拟节点）让同用户固定实例；离线 ZSet 补发。
- **争议**：旧方案 MQ 广播（每实例都收再过滤，N-1 无效）→ 改为 Redis Pub/Sub 精准投递；一致性哈希 vs 轮询。
- **坑**：Pub/Sub 无持久化，订阅者离线即丢；路由 TTL 依赖心跳，GC/分区时本地有 session 但路由没了；取不到 userId 退化为轮询；单机 5w 连接上限。
- **兜底**：先落库再推送，失败转离线（1000 条/7 天）；路由删除用 Lua 比对 serverId，防旧实例误删新路由；双实例实测通过。
- **话术**：
  > “有状态长连接的难点是跨实例寻址。我们用一致性哈希减少重定向，用 Redis 路由表做寻址，Pub/Sub 只负责送达，消息持久化在 DB，所以订阅者掉线不会丢消息。”

## J. SSE 跨实例

- **设计**：本机 SseEmitter map（30min 超时）+ Redis 路由 30s TTL + 10s 心跳续期 + Pub/Sub 跨实例推送；ticket 一次性两步鉴权。
- **争议**：SSE vs WebSocket vs 轮询；Pub/Sub 广播 vs 点对点 MQ；在线推送 vs 拉列表兜底。
- **坑**：用户不在线通知直接丢（靠列表 API 兜底）；序列化把 Long 变 Integer（已改 raw JSON）；路由 TTL 依赖心跳。
- **兜底**：`remove(key,value)` 双参数防旧连接回调误删新连接；心跳失败清理；未读数有 DB 对账；双实例实测通过。
- **话术**：
  > “SSE 我们只当实时通道，不当可靠通道。可靠性和已读状态在 DB 和未读对账里，连接丢了下次拉列表也能补齐。”

## K. 计数 Set / Buffer

- **设计**：写路径 Redis INCR/DECR（去重标记 + 增减同一 Lua）；点赞用 Set（SADD/SREM/SCARD）覆盖写计数；`CounterBuffer` 双缓冲 5s/500 条批量 upsert；读 Redis→MySQL 两级；对账修 DB。
- **争议**：delta 计数 vs Set 去重（乱序下 delta 会虚增，Set 天然幂等）；攒批 vs 实时写。
- **坑**：对账只以 DB 已有行为基准，“DB 无行但 Redis 有值”不覆盖，曾导致刷盘失败增量**永久丢**（运行态 #20）；dedup TTL 2h 与 count key 30 天不一致；EXPIRE 必须在 INCRBY 后。
- **兜底**：刷盘重试 3 次 → 失败回写 Buffer 下周期重刷；懒迁移从权威 Set 同步；归零保护。
- **话术**：
  > “计数是最终一致里最容易丢的一类。我们吃过刷盘失败的亏，所以失败会回写缓冲、下个周期补刷，而不是只靠对账——对账覆盖不到‘DB 还没有行’的场景。”

## L. 限流（Sentinel vs Lua 滑动窗口）

- **设计**：网关 Sentinel 路由级滑动窗口（LeapArray），Nacos 规则优先 + 本地兜底；服务内 `@RateLimit` 用 Redis ZSet + Lua 滑动窗口，支持按用户。
- **争议**：Sentinel vs 手写 Lua；令牌桶/漏桶（允许突发）vs 滑动窗口（严格）；单机 vs 集群限流（Token Server）。
- **坑**：Sentinel 是**单机**限流不是全局；Redis 挂时 Lua 限流 fail-open；Nacos 规则覆盖顺序曾踩坑（已用 ApplicationReadyEvent 规避）。
- **兜底**：统一返回 429/40202；压测后按实测容量校准（写路由保持严格）；同用户第 6 次实测精确拦截。
- **话术**：
  > “限流按容量校准，不是拍脑袋填 QPS。网关是单机维度限流，集群精确限流要 Token Server，我们评估后认为当前规模不需要，用余量 40% 来兜。”

## M. 分库分表

- **设计**：ShardingSphere 4 库 × 4 表，`user_id % 4` 定库、`(user_id/4) % 4` 定表；bindingTables 防笛卡尔积；Snowflake 主键；订单号走公共库映射表反查。
- **争议**：按 user_id 分片（用户维度聚合）vs 按 order_id（点查友好）；映射表 vs 基因法；Snowflake vs 号段。
- **坑**：非分片键查询全分片广播；补录 Job 每 5 分钟全表扫描，数据量上来会线性变慢；**固定 4×4 没有扩容 rehash 方案**；映射写失败有窗口。
- **兜底**：映射补录 Job 消除遗漏；worker-id 环境变量优先、IP 兜底防多实例冲突；补录只更新指定列。
- **话术**：
  > “分片键选 user_id 是业务查询决定的，代价是非分片键要广播。扩容目前是已知短板——4×4 没有 rehash 工具，正确做法是双写迁移或基因分片，这是我们下一步要补的。”

## N. Redis 部署

- **设计**：Sentinel 主从（Redisson/Spring 都支持自动切主）；业务 Redis（noeviction）与缓存 Redis（allkeys-lru）分实例；多 key Lua 用 hash tag 同 slot。
- **争议**：Sentinel vs Cluster vs RedLock；单实例多 DB vs 多实例（业务/缓存隔离）。
- **坑**：Sentinel 切主窗口内锁可能没同步；`prededuct.lua` 里预扣记录与索引未全加 `{skuId}` tag，**Cluster 下会 CROSSSLOT**（当前只支持 Sentinel）；主从延迟影响延迟双删。
- **兜底**：`retryAttempts=5` + watchdog 15s + DB 乐观锁兜底；T-124 修了 bucket hash tag；coupon 用 `{templateId}` 同 slot。
- **话术**：
  > “我们选 Sentinel 是够用且简单，但代码里还留着 Cluster 不兼容的点（Lua 多 key 的 hash tag 没打全），这是明确的升级前置工作，不会说‘随时可切 Cluster’。”

## O. DLQ 治理与消费者韧性

- **设计**：消费者 `maxReconsumeTimes`（18 个组 3 次 / 8 个组 5 次）→ `%DLQ%<group>`；DlqMetrics 对 22 个核心消费组采样出 Gauge；业务侧另有补偿通道（Redis 集合/本地消息/Outbox）。
- **争议**：有限重试入 DLQ vs 无限重试；自动重投 vs 人工审批；原生指标 vs 自算。
- **坑**：DLQ 指标曾**恒为 0**（`searchOffset(now)` 与 maxOffset 恒等，运行态 #21）；`-1` 是“无 DLQ/查询失败”的哨兵不是真实积压；order 补偿进 DLQ 曾没有重放通道。
- **兜底**：积压改为 `maxOffset-minOffset`；订单补偿近上限写 Redis 待处理集合、由 Job 每分钟重放；L3 死信重投需人工审批。
- **话术**：
  > “DLQ 指标我们踩过‘恒为 0’的坑，根因是指标算法自己写错了，不是没积压。修完以后按消费组可见。死信重投走审批，避免脚本误操作。”

## P. traceId 跨服务/MQ/长连接

- **设计**：网关生成 32 位 traceId → MDC + Header + SkyWalking sw8；Feign 拦截器透传；MQ header wrap/restore；IM 消息内 traceId 优先，Pub/Sub 跨实例显式携带。
- **争议**：SkyWalking 自动埋点 vs 业务可读 traceId（最后把两边映射打通）；ThreadLocal vs 显式传参。
- **坑**：消费端不 clear 会随线程池**串号**；MQ 消费无 HTTP 请求只能靠 ThreadLocal；定时任务无 Context 只能 MDC 兜底。
- **兜底**：入口必生成；Feign/MQ/WS 三条通道各自兜底；实测 order→MQ→inventory 同 trace、双 IM 实例两端同 trace。
- **话术**：
  > “traceId 的难点在跨线程和跨协议。HTTP 有 header，MQ 要自己包，WebSocket 要放在消息体里；线程池复用必须 finally clear，否则串号，这个坑我们踩过。”

## Q. 网关过滤器顺序与 HMAC 防重放

- **设计**：BodyCache(0) → 日志(+100) → 鉴权(+1000) → 染色(+1200) → HMAC(+1500) → 限流(+2500) → 灰度(+3000) → 版本(+3100)；签名串 `method|path|query|ts|nonce|bodyHash`，±5min 窗口 + nonce SETNX 5min。
- **争议**：GlobalFilter+Order vs 路由级；对称 HMAC vs 非对称签名；HMAC 默认关闭（按需增强）。
- **坑**：nonce 校验 Redis 异常时 **fail-open**（保留重放窗口），而密钥读取异常 **fail-closed**——不一致是有意权衡；body 限 1MB，multipart 跳过；时间戳依赖客户端时钟。
- **兜底**：`MessageDigest.isEqual` 防时序攻击；黑名单查询 fail-closed；实测合法 200 / 缺签 403 / 篡改 403 / 重放 403 / 过期 403。
- **话术**：
  > “过滤器顺序就是安全模型的顺序：BodyCache 必须最先（HMAC 要 bodyHash），鉴权必须在染色和限流之前，灰度和版本放最后做属性注入。fail-open/fail-closed 我们是分开设计的：nonce 缓存挂了宁可放行，密钥读不到宁可拒绝。”

## R. 对账 Job 体系与最终一致

- **设计**：库存对账（凌晨 3 点，Redis 权威修 MySQL + 分桶原子校验）、券对账（2 点，Redis 修 remain_count）、购物车对账（4 点）、未读对账（5min，DB 修 Redis）、关注对账（1h）、本地消息补发（30s）、超时关单（1min）、订单映射补录（5min）。
- **争议**：对账修复 vs 双向同步 vs 事件重放；权威源按账本选（库存/券/购物车=Redis，未读=DB，关注=ZSet），**不是全局统一**。
- **坑**：对账与在线写没有统一屏障，库存对账遇 MQ 积压可能多扣（下次自愈）；counter 对账不覆盖“DB 无行”；购物车对账在 key 不存在时跳过删除（防 Redis 故障误删）；未读对账游标曾跳切割边界。
- **兜底**：分桶校验用原子 Lua 防盲 SET 覆盖在途预扣；刷盘失败回写 Buffer；XXL-Job 单 Executor 调度所以多数 Job 不加锁。
- **话术**：
  > “对账是最后一道防线不是第一道。它的权威源按业务选，而且和在线写之间没有屏障，所以只能收敛历史差异，不能保证实时一致——这是设计边界。”

---

## 8 个最容易被问倒的问题（速答）

1. **Redis 挂了锁/幂等/限流全 fail-open，凭什么不超卖不重复？**
   → 锁/幂等只是第一道防线；最终防线是 `WHERE available>=qty`、状态机条件 UPDATE、唯一索引。鉴权是 fail-closed，MQ 丢失靠对账/重放收敛。实测 Redis 阻断下无幽灵数据。
2. **延迟双删 500ms 凭什么够？**
   → 不够，所以配了删缓存重试 3 次 + MQ 广播失效 + 对账兜底；承认 Canal 方案更优，是下一步。
3. **有事务消息为什么还要本地消息表？补发不会双投？**
   → 本地消息表是回查锚点 + Broker 全挂兜底；补发是 at-least-once，用 `markSuccessByTransactionId` + 消费端幂等消重。
4. **TCC 和分桶是不是两本账？**
   → 是。TCC 走独立冻结账本，文档明确混用有覆盖风险，当前只用于专项链路，靠对账隔离——主动承认而非掩饰。
5. **同毫秒两条事件版本相等怎么办？**
   → 会互相跳过；购物车加 CLEAR 屏障 + DB 时间戳二次校验，计数用 Set 天然幂等，最终靠对账。
6. **Sentinel 切主可能丢锁，为什么不用 RedLock/ZK？**
   → RedLock 运维复杂且 Kleppmann 质疑其安全假设；用 retryAttempts + watchdog + DB 乐观锁换简单性。fencing token 未实现，但 DB 版本列承担等效终线。
7. **对账万能吗？刷盘失败为什么差点永久丢增量？**
   → 不万能。对账只覆盖“DB 已有行”；所以刷盘失败必须回写 Buffer。教训：有兜底也要实测，不能假设。
8. **按订单号怎么查？将来扩容怎么办？**
   → 订单号走映射表反查 user_id；扩容没有 rehash 方案，是已知短板，正解是双写迁移/基因分片。

---

## 附：五条万能答法（任何设计题都能接）

1. **先讲用途边界**：“这个组件用来解决效率还是正确性”；
2. **主动说坑**：“它最大的问题是 X，我们是这样兜的”；
3. **给数字**：“实测并发/延迟/命中率是多少”；
4. **讲降级**：“故障时 fail-open 还是 fail-closed，为什么”；
5. **留改进**：“如果重来，我会换 Y，因为 Z”。

---

# 展开版写作风格（记录于 2026-09-13）

> 用途：把 18 个主题从"五段式速查"扩写成可背诵的长文，风格与"分布式锁（Redlock 之争）"一致。

## 模板（每篇五段，建议 120-200 字/段）

1. **业界背景**：这个设计对应的经典争论/论文/公司实践（谁跟谁吵过、各自理由）。
2. **项目选型与理由**：我们为什么选 A 不选 B（性能/复杂度/团队成本/当前规模）。
3. **已知坑（主动承认）**：代码注释或文档里承认的边界；一定要先于面试官说出来。
4. **兜底手段**：故障/异常时的降级路径与最终一致性保障（带数字与文件证据）。
5. **话术**：一问一答的 2-3 句版本 + 一句"如果重来会换什么"。

## 答题节奏（背熟三种长度）

- **30 秒版**：用途边界 + 一句话方案 + 一个数字；
- **2 分钟版**：五段式压缩（争议一句、坑两句、兜底两句）；
- **深挖版**：按面试官追问方向展开（选型对比 / 故障演练 / 数据一致性）。

## 已展开长文

1. **分布式锁与 Redlock 之争**（Kleppmann vs antirez；效率锁 vs 正确性；fencing token）；
2. **缓存一致性**（Cache-Aside、Facebook 延迟双删由来、Canal 方案；我们的三层兜底）；
3. **事务消息 vs 本地消息表**（RocketMQ 回查能解决什么、双投坑、markSuccessByTransactionId）；
4. **Feed 推拉模型**（Twitter/微博写扩散 vs 读扩散、大V阈值、断点续推）；
5. **TCC fence**（见附录 D′）；6. **消息乱序与版本**（附录 G′）；7. **对账体系**（附录 R′）。

## 待展开清单（按"最容易被追问"排序）

| 优先级 | 主题 | 关键争议锚点 |
|--------|------|-------------|
| ~~P0~~ | ~~TCC fence~~ ✅ 已展开（附录 D′） | Seata AT/TCC/Saga 之争；两本账与对账覆盖风险 |
| ~~P0~~ | ~~消息乱序与版本~~ ✅ 已展开（附录 G′） | 时间戳版本 vs 队列顺序；同毫秒相等互相跳过 |
| ~~P0~~ | ~~对账体系~~ ✅ 已展开（附录 R′） | 对账 vs 双向同步；权威源按账本选；与在线写无屏障 |
| P1 | 分库分表 | 分片键选择、非分片键广播、4×4 无 rehash（扩容短板） |
| P1 | IM/SSE 跨实例 | Pub/Sub 无持久化 vs 持久订阅；一致性哈希 vs 广播 |
| P1 | 计数 Buffer | delta vs Set 幂等；刷盘失败回写（#20 教训） |
| P2 | 限流 | Sentinel 单机 vs Token Server 集群；fail-open 边界 |
| P2 | 网关 HMAC | 对称 vs 非对称；nonce fail-open 与密钥 fail-closed 的取舍 |
| P2 | DLQ 治理 | 有限重试 vs 无限重试；自动重投 vs 审批；指标曾恒 0（#21） |
| P3 | traceId 跨协议 | ThreadLocal 串号；MQ/WS 显式透传 |

> 复习建议：每个 P0 主题先背"坑"再背"兜底"，最后用话术收尾；数字必须能说出处。

---

# 附录 · P0 长文展开（2026-09-14）

## D′. TCC fence：空回滚、悬挂与"两本账"的诚实边界

**① 业界背景。** TCC 的概念来自 Pat Helland 2007 年 "Life beyond Distributed Transactions"，核心是把分布式事务拆成 Try（预留）/Confirm（确认）/Cancel（回滚）三段，用业务补偿代替数据库回滚。Seata 把它工程化，并补齐了三个经典异常：**空回滚**（Try 未到 Cancel 先到）、**悬挂**（Cancel 后迟到的 Try 又执行）、**幂等**（Confirm/Cancel 重试）。对标的三种方案：Seata AT 用全局锁 + undo log，侵入小但热点行全局锁竞争大；Saga 长事务补偿无隔离，中间态对外可见；裸 TCC 业务侵入最大、但可控性最强。Fence 的本质是一张**唯一键表**，把"分支事务有没有发生过 Cancel"变成数据库可见的事实。

**② 项目选型与理由。** 库存预扣链路自研 TCC + 独立 `t_tcc_fence` 表（`TccFenceService`）：Try 插 fence（主键冲突=幂等命中，必须跳过业务，否则双冻结）；Cancel 先插 fence（状态=CANCELLED，Try 没执行过则插成功但什么都不做=空回滚）；Try 时查 fence 见 3 直接拒绝（悬挂）。为什么不用 Seata：多一个 TC 集群的部署与存储成本，且我们只有一个专项链路用 TCC（其余走事务消息/本地消息表），框架收益不抵复杂度。为什么 fence 独立表而不是复用业务行状态：Cancel 可能先于 Try 到达，"业务行还没建"这种状态业务表表达不了。超时取消也从"按 SKU 聚合 freezing_stock + updated_at"改成"按 xid 冻结明细"——旧实现有三宗罪：一刀切回退该 SKU 全部冻结（含别单刚冻的）、热 SKU 的 updated_at 被任何冻结刷新导致超时永远不可达、回退不写 fence 导致迟到 Confirm 把已回退库存再 confirm（超卖）。

**③ 已知坑（主动承认）。** 第一，**TCC 与普通分桶库存是两本账**：TCC 走独立冻结账本，普通链路走 Redis 分桶+DB，混用场景对账 Job 有可能覆盖 TCC 状态——文档里明确标了风险，靠对账隔离而不是假装统一。第二，Confirm/Cancel 如果只写 fence 不校验业务行数与库存状态，异常路径会静默漏处理。第三，这个机制没有"绝不出错"的保证：它保证的是 Try/Cancel 的**相对顺序语义**，不是跨系统的因果序。

**④ 兜底手段。** 状态机条件 UPDATE（`UPDATE ... WHERE status=1`）保证并发双 Cancel 只有一次真正执行；超时 Job（`TccTimeoutJob`，60s 一轮）扫描 status=1 明细逐条 cancelFence(1→3) 再解冻；迟到 Confirm 被 fence 拒绝（status=3）；11 个场景（幂等/空回滚/悬挂/超量/状态机非法流转）实测通过；Redisson 锁保证多实例只有一个 Job 实例执行。

**⑤ 话术（30 秒版）。** "TCC 只用在库存专项链路，因为我们没有引入 Seata，fence 表解决空回滚/悬挂/幂等三件事。它最大的边界是和普通分桶库存两本账，文档里明确标了，靠对账隔离——面试我会主动说这个，而不是等追问。如果重来，要么全线 Seata，要么把冻结账本和分桶库存做成互斥使用。"

## G′. 消息乱序与版本控制：时间戳是妥协，不是银弹

**① 业界背景。** MQ 只保证**分区内有序**：RocketMQ 顺序消息靠单队列串行（吞吐换顺序），跨 consumer group 完全不保证顺序——我们真的踩过：点赞/取消点赞拆了两个 group，取消先到、点赞后到，最终 DB 显示"已点赞"但用户实际取消了。业界解决乱序四大流派：单分区顺序消费、业务版本号（last-write-wins）、事件溯源+状态机收敛、幂等数据结构（Set/位图）。

**② 项目选型与理由。** 我们按链路特征混用：库存用 `eventTime` 在 Lua 里原子比较（脚本内比较+写入，避免 check-then-act）；社交点赞/收藏用 `actionTime` 版本号 + 24h 窗口（Redis 记录最后版本，旧事件跳过），并把 LIKE/UNLIKE 合并成一个 consumer group（`LikeUnlikeConsumer`）——这是"先消灭乱序源，再谈版本"的顺序；购物车用 CLEAR 屏障（清空动作先把操作序列"抬到"最新）+ DB `updatedAt` 二次比较；ES 搜索用 Canal 的 `ts` 做 external version + tombstone（删除标记），让 ES 自己拒绝旧版本写；Feed 的已删标记 5 分钟内拦截晚到的推送。为什么不全部顺序消费：写扩散和计数是吞吐敏感链路，单队列串行的代价远大于版本号的复杂度。

**③ 已知坑（主动承认）。** 同毫秒两条事件版本相等会**互相跳过**（时间戳精度不够）；`eventTime=null` 的事件不参与比较、直接放行；跨 action 共用版本 key 时，早到的 PRE_DEDUCT 会被当作旧事件跳过；ES 同毫秒同版本写入会被拒（版本严格递增假设）；超过 24h 窗口的迟到事件不再拦截。这些都是时间戳版本的固有边界，不是实现对错。

**④ 兜底手段。** 计数用 Redis Set 做"用户维度去重"，天然抗乱序（重复/乱序都不改变集合）；购物车在 catch 分支做二次校验防 TOCTOU；搜索侧实测 001→000→002 的低版本被拒；相等/超窗的残余场景交给对账 Job 收敛。

**⑤ 话术（30 秒版）。** "时间戳版本不是严格因果序，同毫秒会打架，我们承认。所以关键链路要么用天然幂等的结构（Set），要么加屏障（CLEAR），最后靠对账兜底。如果重来，我会考虑用 binlog offset/序列号替代时间戳，或者对强顺序链路直接做分区顺序消费。"

## R′. 对账 Job 体系：选对权威源，承认无屏障

**① 业界背景。** 对账是金融行业处理"最终一致"的标准答案（银行/支付宝 T+1 对账）。工程上有两条路线：一是"在线强一致（TCC/事务消息）+ 对账兜底"，金融系主流；二是"事件溯源/CDC 流式一致"，互联网新架构偏好。对账的核心从来不是"跑个 Job"，而是**谁是权威源**以及**差异如何有方向地修复**。

**② 项目选型与理由。** 8 个对账/修复 Job 覆盖全链路：库存（凌晨 3 点）、券（2 点）、购物车（4 点）、未读（5 分钟）、关注/粉丝（1 小时）、本地消息补发（30 秒）、超时关单（1 分钟）、订单映射补录（5 分钟）。权威源**按账本选，不统一**：库存/券/购物车以 Redis 为准（用户操作直接写 Redis，MySQL 只是异步持久化的兜底存储）；未读以 DB 为准；关注关系统以 Redis ZSet 为准（ZCARD 覆盖 counter）。调度用 XXL-Job，Admin 只调度一个 Executor 实例，所以多数 Job 不额外加分布式锁。

**③ 已知坑（主动承认）。** 第一，**对账与在线写之间没有屏障**：库存对账撞上 MQ 积压时可能"多扣"，只能靠下一次对账自愈——这是设计边界，不是 bug。第二，counter 对账只覆盖"DB 已有行"，DB 缺行时对账看不见。第三，购物车对账在 Redis key 不存在时跳过删除（防止 Redis 故障被误判为"用户删了全部购物车"）。第四，未读对账游标曾经跳切割边界。第五，最疼的教训：计数 Buffer 刷盘失败如果只依赖对账，对账覆盖不到"DB 无行"的场景，会永久丢增量——后来改成刷盘失败必须回写 Buffer（#20 复盘）。

**④ 兜底手段。** 库存对账对分桶做原子 Lua 校验，避免盲 SET 覆盖在途预扣；修复动作以权威源为准且幂等（可重复执行）；本地消息补发配合 `markSuccessByTransactionId` 消除双投；所有 Job 都保留执行日志与修复计数，便于回溯。

**⑤ 话术（30 秒版）。** "对账是最后一道防线，不是第一道。它的权威源按业务选，而且和在线写之间没有屏障，所以只能收敛历史差异，不能保证实时一致——这是我们文档里写明的边界。如果重来，我会给对账加水位线/版本屏障，或者把关键账本换成 CDC 流式同步。"
