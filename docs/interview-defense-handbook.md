# xhs 平台面试防御手册（19 个主题速查 + 8 个高危问题 + 13 篇深挖长文）

> 用法：每个主题按 **设计 → 争议/备选 → 已知坑 → 兜底 → 话术** 五段掌握。
> 原则：**坑要主动承认，兜底要能说清边界**——面试官最反感"我们没问题"，最喜欢"我们知道这里有问题，所以这样兜"。
> 证据标注 `文件:行号`，面试前可对照源码。

---

## A. 分布式锁与并发控制

- **设计**：Redisson RLock 直连锁 + 显式租期（10s~7200s：支付/用户/缓存单飞 10s、库存 30s、SPU 300s、索引重建 7200s、Job 4~50s），解锁统一 `isHeldByCurrentThread()`。注解切面（@Order(50)/watchdog/fail-open）为框架件未接入；消息幂等用 helper 显式 SETNX 去重。
- **争议**：RedLock（多节点多数派）vs Redisson+DB 兜底 vs ZK+fencing。项目注释明确放弃 RedLock（运维复杂 + Kleppmann 已论证其局限）。
- **坑**：
  - Redis 异常时加锁直接失败（fail-closed，拒绝/跳过）；切主丢锁窗口只影响效率；
  - 10s 锁在多 Feign 串行下可能过期，同用户出现两单；
  - 释放失败仅告警。
- **兜底**：DB 乐观锁/唯一键是最终防线；Sentinel 切主 `retryAttempts=5×1000ms`；抢锁失败直接拒绝（支付）/跳过（Job）。
- **话术**：
  > “我们把锁定位成效率工具而不是正确性保证。加锁点都在关键路径直连 Redisson、显式租期：抢锁失败就拒绝或跳过，Redis 挂了加锁直接失败（fail-closed）——宁可暂时不可用也不冒险重复执行。真正防重靠 DB 唯一键和状态机条件更新。Sentinel 切主确实有丢锁窗口，所以没上 RedLock，也没把 fencing 责任交给锁本身。”

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
- **兜底**：迟到 Confirm 被 fence 拒；并发双 Cancel 只有一次执行；超时 Job 先写 fence 再解冻；**11 场景（幂等/空回滚/悬挂拒绝/超量拒绝/fence 状态机 1→2/3）直接调用实测通过**（test-4 运行态对账报告第六轮），另有 FINAL-HANDOFF 五场景与 test-3 G5-02-06/07 佐证。
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

- **设计**：消费者 `maxReconsumeTimes`（18 个组 3 次 / 8 个组 5 次）→ `%DLQ%<group>`；DlqMetrics 对 26 个消费组采样出 Gauge（RV18 对齐：补 8 缺失组、移除 5 陈旧组）；业务侧另有补偿通道（Redis 集合/本地消息/Outbox）。
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

## S. Zone 多活（路由/数据面/容灾）

- **设计**：流量面=实例 zone 标 + LB 同 zone 优先（不足回退全局）+ 健康检查摘除（liveness/3s）；网关 WebFlux 独立反应式 zone LB；数据面=MySQL 读本 zone/写主库/从库故障降级恢复；Redis 客户端读副本写主库 + **服务端双主**（DUMP/RESTORE + LWW + 30s 对账）；支持运行时热切 zone（ZoneContext 事件，不重启）。
- **数字**：进程 kill RTO **1.31s/3.25s**、网络分区 **4.79s**、回切 <5s；网关就近 12/12、切换 5.5s；Redis 双主单侧宕机不中断、恢复 ≤35s 追平；240/240 同 zone 命中。
- **坑**：① 供应商 Bean 放 default context 不生效（必须进 LB 子 context）；② SIGTERM 优雅关闭造成"0.55s 假恢复"；③ LB 列表缓存 35s 掩盖切换（改 liveness 后 3.06s）；④ 双主对账 tie-break 曾误删单侧数据（改"存在优先"）；⑤ zone 参数没进进程时 Nacos 会显示旧注册残留。
- **兜底**：同 zone 不足回退全局；从库故障降级 + 30s 探测恢复；双主 LWW + 防回环（值相等跳过）+ 周期对账；发布链路 PID 校验防"旧进程假成功"。
- **话术**：
  > "多活分两层：流量面优先同 zone、健康检查摘除，RTO 1.3~4.8 秒；数据面本 zone 读、主库写，Redis 还做了双主双向同步——DUMP/RESTORE 原子复制加 LWW 加对账兜底，单侧挂了不中断、35 秒内追平。边界明说：单机仿真，跨机房延迟和脑裂没有模拟。"

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

> 用途：把主题从"五段式速查"扩写成可背诵的长文，风格与"分布式锁（Redlock 之争）"一致。

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
5. **TCC fence**（附录 D′）；6. **消息乱序与版本**（附录 G′）；7. **对账体系**（附录 R′）；
8. **分库分表**（附录 M′）；9. **IM/SSE 跨实例**（附录 I′+J′）；10. **计数 Buffer**（附录 K′）；
11. **限流**（附录 L′）；12. **网关 HMAC**（附录 Q′）；13. **DLQ 治理**（附录 O′）；14. **traceId 跨协议**（附录 P′）；15. **LLM 工具设计**（附录 T′）；16. **去 MCP 化与 RBAC**（附录 U′）。

## 待展开清单（按"最容易被追问"排序）

| 优先级 | 主题 | 关键争议锚点 |
|--------|------|-------------|
| ~~P0~~ | ~~TCC fence~~ ✅ 已展开（附录 D′） | Seata AT/TCC/Saga 之争；两本账与对账覆盖风险 |
| ~~P0~~ | ~~消息乱序与版本~~ ✅ 已展开（附录 G′） | 时间戳版本 vs 队列顺序；同毫秒相等互相跳过 |
| ~~P0~~ | ~~对账体系~~ ✅ 已展开（附录 R′） | 对账 vs 双向同步；权威源按账本选；与在线写无屏障 |
| ~~P1~~ | ~~分库分表~~ ✅ 已展开（附录 M′） | 分片键选择、非分片键广播、4×4 无 rehash（扩容短板） |
| ~~P1~~ | ~~IM/SSE 跨实例~~ ✅ 已展开（附录 I′+J′） | Pub/Sub 无持久化 vs 持久订阅；一致性哈希 vs 广播 |
| ~~P1~~ | ~~计数 Buffer~~ ✅ 已展开（附录 K′） | delta vs Set 幂等；刷盘失败回写（#20 教训） |
| ~~P2~~ | ~~限流~~ ✅ 已展开（附录 L′） | Sentinel 单机 vs Token Server 集群；fail-open 边界 |
| ~~P2~~ | ~~网关 HMAC~~ ✅ 已展开（附录 Q′） | 对称 vs 非对称；nonce fail-open 与密钥 fail-closed 的取舍 |
| ~~P2~~ | ~~DLQ 治理~~ ✅ 已展开（附录 O′） | 有限重试 vs 无限重试；自动重投 vs 审批；指标曾恒 0（#21） |
| ~~P3~~ | ~~traceId 跨协议~~ ✅ 已展开（附录 P′） | ThreadLocal 串号；MQ/WS 显式透传 |

> 复习建议：每个 P0 主题先背"坑"再背"兜底"，最后用话术收尾；数字必须能说出处。

---

# 附录 · P0 长文展开（2026-09-14）

## D′. TCC fence：空回滚、悬挂与"两本账"的诚实边界

**① 业界背景。** TCC 的概念来自 Pat Helland 2007 年 "Life beyond Distributed Transactions"，核心是把分布式事务拆成 Try（预留）/Confirm（确认）/Cancel（回滚）三段，用业务补偿代替数据库回滚。Seata 把它工程化，并补齐了三个经典异常：**空回滚**（Try 未到 Cancel 先到）、**悬挂**（Cancel 后迟到的 Try 又执行）、**幂等**（Confirm/Cancel 重试）。对标的三种方案：Seata AT 用全局锁 + undo log，侵入小但热点行全局锁竞争大；Saga 长事务补偿无隔离，中间态对外可见；裸 TCC 业务侵入最大、但可控性最强。Fence 的本质是一张**唯一键表**，把"分支事务有没有发生过 Cancel"变成数据库可见的事实。

**② 项目选型与理由。** 库存预扣链路自研 TCC + 独立 `t_tcc_fence` 表（`TccFenceService`）：Try 插 fence（主键冲突=幂等命中，必须跳过业务，否则双冻结）；Cancel 先插 fence（状态=CANCELLED，Try 没执行过则插成功但什么都不做=空回滚）；Try 时查 fence 见 3 直接拒绝（悬挂）。为什么不用 Seata：多一个 TC 集群的部署与存储成本，且我们只有一个专项链路用 TCC（其余走事务消息/本地消息表），框架收益不抵复杂度。为什么 fence 独立表而不是复用业务行状态：Cancel 可能先于 Try 到达，"业务行还没建"这种状态业务表表达不了。超时取消也从"按 SKU 聚合 freezing_stock + updated_at"改成"按 xid 冻结明细"——旧实现有三宗罪：一刀切回退该 SKU 全部冻结（含别单刚冻的）、热 SKU 的 updated_at 被任何冻结刷新导致超时永远不可达、回退不写 fence 导致迟到 Confirm 把已回退库存再 confirm（超卖）。

**③ 已知坑（主动承认）。** 第一，**TCC 与普通分桶库存是两本账**：TCC 走独立冻结账本，普通链路走 Redis 分桶+DB，混用场景对账 Job 有可能覆盖 TCC 状态——文档里明确标了风险，靠对账隔离而不是假装统一。第二，Confirm/Cancel 如果只写 fence 不校验业务行数与库存状态，异常路径会静默漏处理。第三，这个机制没有"绝不出错"的保证：它保证的是 Try/Cancel 的**相对顺序语义**，不是跨系统的因果序。

**④ 兜底手段。** 状态机条件 UPDATE（`UPDATE ... WHERE status=1`）保证并发双 Cancel 只有一次真正执行；超时 Job（`TccTimeoutJob`，60s 一轮）扫描 status=1 明细逐条 cancelFence(1→3) 再解冻；迟到 Confirm 被 fence 拒绝（status=3）；**11 场景（幂等/空回滚/悬挂拒绝/超量拒绝/fence 状态机 1→2/3）直接调用实测通过**（test-4 运行态对账报告），并有 FINAL-HANDOFF §6 与 test-3 G5-02-06/07 佐证；Redisson 锁保证多实例只有一个 Job 实例执行。

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

## M′. 分库分表：分片键就是查询模型，扩容要提前设计

**① 业界背景。** 分库分表的核心矛盾是"分片键只能优化一类查询"：按 user_id 分对"我的订单列表"友好，按 order_id 分对"订单详情"友好。业界三种解法：**基因法**（把 user_id 的二进制位编码进 order_id 末位，单键路由）、**映射表**（订单号→user_id 独立存储反查）、**ES 二级索引**（查询与存储解耦）。扩容是另一个经典难题：取模分片没有 rehash 工具，主流方案是双写迁移（新老规则并行写、旧数据后台搬）或一致性哈希/基因分片从设计期规避。

**② 项目选型与理由。** ShardingSphere 4 库 × 4 表：`user_id % 4` 定库、`(user_id/4) % 4` 定表；bindingTables 绑定订单/明细/本地消息，避免 join 笛卡尔积；主键用 Snowflake（`ShardingSphereDataSourceConfig` 里 worker-id 动态计算：环境变量优先、IP 兜底，防多实例冲突）。为什么不用基因法：订单号还承担对账/回调的外部语义，改结构风险大；映射表（`t_order_no_mapping`）改动可控，配合 `MappingRepairJob` 每 5 分钟补录消除写失败窗口。查询分工：用户维度走分片键；客服/风控按订单号先查映射表拿 user_id 再路由。

**③ 已知坑（主动承认）。** 非分片键查询（订单号、时间范围、状态）只能**全分片广播**，数据量上来线性变慢；补录 Job 全表扫描同样随量线性劣化；**固定 4×4 没有 rehash 方案**，扩容是明确短板；映射写失败到补录之间有查询不到的窗口。

**④ 兜底手段。** 映射表 + 5 分钟补录 Job 收敛遗漏；Snowflake worker-id 冲突有环境变量与 IP 双保险；补录只更新指定列避免覆盖业务字段；SQL 层用 Explain/慢查询监控广播比例，超阈值就考虑把高频非分片查询投影到 ES。

**⑤ 话术（30 秒版）。** "分片键选 user_id 是业务查询决定的，代价是非分片键要广播，我们监控广播比例。扩容目前是已知短板——4×4 没有 rehash 工具，正确做法是双写迁移或基因分片，这是我们下一步要补的。"

## I′+J′. IM 与 SSE 跨实例：有状态连接的寻址与送达分离

**① 业界背景。** 长连接（WebSocket/SSE）是**有状态**的，接入层无状态化后必须解决"用户连在哪个实例"。业界路线：广播（每个实例都收再过滤，N 个实例 N-1 次无效投递）、一致性哈希路由（同用户固定实例，但要处理实例上下线和重定向）、集中式 session 注册中心（Redis 路由表/etcd）、网关前缀路由（如 MQTT broker）。送达侧又有 Pub/Sub（无持久化）vs 持久订阅/MQ（可靠但重）。

**② 项目选型与理由。** 两级配合：**入口一致性哈希**（150 个虚拟节点）让同用户尽量落同一实例，减少跨实例投递；**Redis 路由表**做精准寻址——IM 用 `im:route:{userId}`（90s TTL 心跳续期），SSE 用 30s TTL + 10s 心跳续期；每实例一个专属 Pub/Sub channel，路由查到实例后**点对点投递**。旧方案 MQ 广播被替换（延迟与无效投递不可接受）。SSE 另加 ticket 一次性两步鉴权（限制在 URL 里的 token 泄露面）。

**③ 已知坑（主动承认）。** Pub/Sub 无持久化，订阅者离线即丢——所以"实时通道"不承担可靠性；路由 TTL 依赖心跳，GC 停顿/网络分区时会出现"本地有 session 但路由已过期"；取不到 userId 时退化为轮询；单机连接数有上限（约 5w 量级）。

**④ 兜底手段。** 消息**先落库再推送**，推送失败转离线（1000 条/7 天）或靠列表 API/未读对账补齐；路由删除用 Lua 比对 serverId，防旧实例误删新实例路由；SSE 的 `remove(key,value)` 双参数防旧连接回调误删新连接；心跳失败主动清理；双实例实测通过。

**⑤ 话术（30 秒版）。** "有状态连接的难点是跨实例寻址。我们用一致性哈希减少重定向、Redis 路由表做寻址、Pub/Sub 只负责送达；可靠性放在 DB 和离线消息里，所以订阅者掉线不会丢消息——实时通道和可靠通道是两回事。"

## K′. 计数 Buffer：Set 管正确性，Buffer 管性能

**① 业界背景。** 计数系统两条路线：**delta 计数**（INCR/DECR，性能好但依赖消息不重不漏）和**去重结构**（Set/Bitmap/HyperLogLog，天然幂等但有内存成本）。点赞这类"用户-目标"关系天然适合 Set：SADD 幂等，乱序/重投都不改变集合，SCARD 就是权威值。攒批（Buffer）则是把高频 DB 写合并，代价是丢增量风险。

**② 项目选型与理由。** 写路径：Redis INCR/DECR 做实时值，**同一 Lua 里先做去重标记（`myxhs:counter:dedup:{msgId}`）再增减**，保证 MQ 重投不重复计数；点赞/收藏类用 Set（SADD/SREM/SCARD）覆盖写计数，把"最终值"交给集合而不是算术。`CounterBuffer` 双缓冲（5s 或 500 条触发）批量 upsert DB；读路径 Redis → MySQL 两级；不用 Caffeine 本地缓存——多实例下各节点会看到不同计数值，Redis 亚毫秒延迟不值得换分布式不一致。对账 Job 以 Redis 权威修 DB。

**③ 已知坑（主动承认）。** 对账只以 **DB 已有行**为基准，"DB 无行但 Redis 有值"覆盖不到——刷盘失败曾导致增量**永久丢**（运行态 #20 复盘）；dedup TTL 2h 与 count key 30 天不一致，超窗重投可能重复计数（窗口内幂等，窗口外交对账）；EXPIRE 必须在 INCRBY 之后，否则 key 会被提前重置。

**④ 兜底手段。** 刷盘失败重试 3 次 → 仍失败**回写 Buffer 下周期重刷**（而不是只靠对账）；懒迁移从权威 Set 同步到 DB；归零保护防止负数；对账 Job 每小时/每天收敛历史差异。

**⑤ 话术（30 秒版）。** "计数是最终一致里最容易丢的一类。我们吃过刷盘失败的亏：对账覆盖不到'DB 还没有行'的场景，所以失败必须回写缓冲、下个周期补刷。delta 计数只用在有去重的路径上，点赞这类关系值用 Set 兜正确性。"

## L′. 限流：按容量校准，而不是拍脑袋填 QPS

**① 业界背景。** 限流的两条轴：**算法**（计数器/滑动窗口/令牌桶/漏桶——突发流量允许度不同）与**范围**（单机 vs 集群）。单机限流便宜但总量=N×阈值；集群精确限流需要 Token Server（集中发令牌）或 Redis+Lua 全局限流（每次请求一次 Redis 往返）。Sentinel 是单机 LeapArray 滑动窗口的代表，规则可动态下发。

**② 项目选型与理由。** 网关用 Sentinel 路由级滑动窗口（Nacos 规则优先 + 本地兜底，ApplicationReadyEvent 保证规则加载顺序），服务内精细场景用 `@RateLimit`（Redis ZSet + Lua 滑动窗口，支持按用户维度）。为什么不全用 Redis：网关是流量入口，每请求一次 Lua 的网络开销换全局精确不划算；为什么保留 Lua 版：同用户第 6 次这种"按用户精确拦截"Sentinel 单机做不到。

**③ 已知坑（主动承认）。** Sentinel 是**单机**限流，集群总量是 N 倍阈值；Redis 挂时 Lua 限流 fail-open（宁放行不阻断交易）；Nacos 规则与本地规则覆盖顺序踩过坑（已规避）；阈值必须按实测容量校准，不是配置数字游戏。

**④ 兜底手段。** 压测后按容量设限（写路由保持严格、读路由留余量），统一 429/40202 响应；关键写路径在限流之外还有 DB 约束兜底；同用户第 6 次实测精确拦截。

**⑤ 话术（30 秒版）。** "限流按容量校准，不是拍脑袋。网关是单机维度，集群精确限流要 Token Server，我们认为当前规模不需要，用约 40% 余量兜；Redis 崩溃时 Lua 限流 fail-open，因为限流挂了不该阻断交易。"

## Q′. 网关 HMAC 防重放：fail-open 与 fail-closed 是分开设计的

**① 业界背景。** 防重放三件套：时间戳窗口（拒绝过期请求）、nonce 去重（拒绝重复请求）、签名（防篡改）。对称 HMAC 简单快；非对称签名（RSA/Ed25519）能分离签发与验签权限但重。典型争议是"nonce 存储挂了怎么办"——安全与可用性的直接冲突。

**② 项目选型与理由。** GlobalFilter 链固定顺序：BodyCache(0) → 日志(+100) → 鉴权(+1000) → 染色(+1200) → HMAC(+1500) → 限流(+2500) → 灰度(+3000) → 版本(+3100)。顺序即安全模型：BodyCache 必须最先（HMAC 要对 bodyHash 验签），鉴权先于染色与限流（未认证流量不进入业务计数）。签名串 `method|path|query|ts|nonce|bodyHash`，±5min 时间窗 + nonce SETNX 5min；默认按需开启（内网调用不需要额外链路开销）。

**③ 已知坑（主动承认）。** nonce 校验遇 Redis 异常 **fail-open**（保留重放窗口），而密钥读取异常 **fail-closed**——这个不一致是**有意权衡**，不是疏漏；body 限 1MB、multipart 跳过；时间戳校验依赖客户端时钟，时钟漂移大的客户端会被误拒。

**④ 兜底手段。** `MessageDigest.isEqual` 防时序攻击；下发的密钥任何异常都 fail-closed；黑名单查询 fail-closed；实测矩阵：合法 200 / 缺签 403 / 篡改 403 / 重放 403 / 过期 403。

**⑤ 话术（30 秒版）。** "过滤器顺序就是安全模型的顺序。fail-open/fail-closed 我们是分开设计的：nonce 缓存挂了宁可放行，密钥读不到宁可拒绝——因为前者只损失防重放窗口，后者损失的是信任根。"

## O′. DLQ 治理：先让指标诚实，再谈自动重投

**① 业界背景。** 死信治理的路线之争：**无限重试**（可能毒消息拖垮消费者）vs **有限重试入 DLQ**（需配套处置闭环）；**自动重投**（快但可能循环）vs **人工审批重投**（稳但慢）。无论选哪条，前提都是**指标可信**——DLQ 积压看不见，治理就是空谈。

**② 项目选型与理由。** 消费者 `maxReconsumeTimes`（18 组 3 次 / 8 组 5 次）→ `%DLQ%<group>`；监控用自算 Gauge 采样 26 个消费组；业务侧另有补偿通道（Redis 待处理集合/本地消息表/Outbox），不是所有失败都走 DLQ。重投走三级治理：L1 自动重试、L2 补偿 Job、**L3 死信重投必须人工审批**（xhs-ai 里由 Agent 发起、HITL 审批、执行后核验再入 DLQ）。

**③ 已知坑（主动承认）。** DLQ 指标曾**恒为 0**——`searchOffset(now)` 与 `maxOffset` 恒等，算法写错把真实积压藏了（运行态 #21）；`-1` 哨兵表示"无 DLQ/查询失败"不是真实积压；监控清单硬编码 22 组与实际 26 组有 8 组盲区（cart 死信事件后修复）；order 补偿进 DLQ 一度没有重放通道；跨 group 顺序不保证（Like/Unlike 拆组事故）。

**④ 兜底手段。** 积压算法改 `maxOffset-minOffset`；26 组清单与代码对齐并加维护注释；订单补偿近上限先写 Redis 待处理集合、Job 每分钟重放；死信重投审批 + 执行后**队列级位点核验**（不是"发送成功=成功"）；消费者做失败分类（可重试 vs 确定性失败）。

**⑤ 话术（30 秒版）。** "DLQ 治理第一件事不是重投，而是让指标诚实——我们踩过指标恒为 0 的坑，根因是算法自己写错。修完后按消费组可见、清单与实际对齐；重投走人工审批，执行后还要核验位点，不做'发送成功就算成功'。"

## P′. traceId 跨协议：跨线程和跨协议是两个坑

**① 业界背景。** 分布式追踪两条路线：APM 自动埋点（SkyWalking/Jaeger，框架级）与业务可读 traceId（MDC/日志串联）。两者痛点不同：APM 全但业务日志里不好查；业务 traceId 灵活但要自己跨线程、跨协议透传。

**② 项目选型与理由。** 网关生成 32 位 traceId → MDC + Header + SkyWalking sw8（双轨映射打通）；Feign 拦截器透传 HTTP；MQ 用 header wrap/restore；IM 消息体里显式携带 traceId（WebSocket 没有 header 语义）；Pub/Sub 跨实例也显式带。定时任务无请求上下文，用 MDC 兜底生成。

**③ 已知坑（主动承认）。** 消费端不 `clear` 会随线程池**串号**（A 请求的 traceId 出现在 B 请求日志里）；MQ 消费没有 HTTP 请求，ThreadLocal 只能靠消息头回填；跨实例 Pub/Sub 消息不带 trace 就断链；SkyWalking 的 sw8 与业务 traceId 不是同一个 ID，必须做映射。

**④ 兜底手段。** 入口必生成；Feign/MQ/WS 三条通道各自透传；线程池包装器 finish 时 finally clear；实测 order→MQ→inventory 同一 trace、双 IM 实例两端同 trace。

**⑤ 话术（30 秒版）。** "traceId 的难点在跨线程和跨协议：HTTP 有 header，MQ 要自己包，WebSocket 要放消息体里；线程池复用必须 finally clear，否则串号——这个坑我们踩过，现在三条通道都有兜底和实测。"

## T′. LLM 工具设计：给模型的接口不是给程序员的接口

**① 业界背景。** Function Calling / MCP 生态里，工具 schema 直接决定模型的选择准确率与调用成功率。Anthropic/OpenAI 的工具设计指南反复强调：参数少而扁平、命名语义化、描述里写"何时用/何时不用"、示例优于解释。业界另一个趋势是**渐进加载**（tool_search / 懒加载技能），因为工具数量膨胀会同时抬高 token 成本与误选率——我们 32 个工具虽未到阈值，但已在 Agent 侧设了软 32/硬 40 的预算护栏。

**② 项目选型与理由。** 我们最初把数据源原样暴露：ES 的 `search` 要 `index` + 完整 DSL，Prometheus 的 `query` 要手写 PromQL。实测 qwen3.8-flash 在嵌套 schema 上**连续 3 次缺参**（`required property 'index' not found`）、P95 聚合**答非所问**（98s）。于是把查询组装收回到服务端，向模型暴露**业务级工具**：`log_top_services(level,minutes,topN)`、`log_search(service,level,keyword,minutes,size)`、`metric_top(metric,service?,topN)`、`metric_trend(metric,service,minutes)`、`consumer_lag_top(topN)`。模型只填业务语义参数，DSL/PromQL 由 `LogQueryBuilder`/`MetricQueryBuilder` 白名单生成。同题对比：5xx 63s→52s，P95 98s(答偏)→50s，趋势从"做不到"到 33s 且能区分冷启动与平台期。

**③ 已知坑（主动承认）。** 工具**注册成功 ≠ 可用**：ES 工具因框架权限默认 ASK 挂起过（`MCP client not initialized`/空答复）；子进程被 kill 后需进程级自愈；工具数量到 32 已触及软预算，再涨必须先做 token 预算与 tool_search；业务级工具覆盖面有限（复杂聚合仍要回退裸 query）；模型偶发不按"只查一次"的约束执行，靠 prompt + 工具内部超时兜。

**④ 兜底手段。** 参数 clamp（minutes≤1440/size≤100/topN≤50）+ 服务名校验正则；白名单指标目录（非法 metric 直接报错给模型纠错）；只读工具 + 审计留痕；每个工具有单测（当前 40/40）；工具总数指标 `ai_agent_tools_total` + 软硬预算；裸查询工具保留作兜底并在 prompt 里标明适用边界。

**⑤ 话术（30 秒版）。** "给 LLM 的工具接口要按任务设计，不是按数据源。我们踩过模型填不对 ES DSL 的坑，所以把 DSL/PromQL 收回服务端，只暴露业务参数——P95 查询从 98 秒答偏变成 50 秒答对。工具不是越多越好，32 个已到软预算，下一步是 tool_search 渐进加载。如果重来，我会从第一天就用业务级工具目录 + 单测守着。"

## U′. 去 MCP 化与 RBAC：生产化收口的两条依赖治理

**① 业界背景。** Agent 生产化绕不开两类"依赖治理"：**权限依赖**（谁能调用危险能力）与**工具运行时依赖**（工具进程挂了怎么办）。前者对应 OWASP LLM Top10 的"过度授权（Excessive Agency）"——最小权限、危险操作强制人工确认；后者是 MCP 生态的现实问题：协议标准只规定"怎么连"，没规定"进程崩了怎么无感热替换"。框架（我们用的 AgentScope）支持运行时注册/摘除工具，但实测**重挂后已注册工具不重绑**（调用报 `MCP client not initialized`）。

**② 项目选型与理由。** 两条依赖分别用"接平台能力"和"砍依赖面"解决：
- **RBAC**：不重建账号体系，直接消费平台 JWT 的 `role` claim（实测带 `OPERATOR`），加管理/内部令牌视为 ADMIN，默认 VIEWER；`reindex`/评测/诊断/MCP 直连全部收敛到 ADMIN，普通用户走会话所有权校验（自己的审批自己批）。对照 OWASP 最小权限：VIEWER 只能对话与查自己的数据，危险/全局能力一步收口。
- **去 MCP 化**：既然框架不能热替换，就不把 MCP 放进 Agent 的**关键路径**——16 个工具全部自研（REST 直连 ES/Prometheus/RocketMQ Admin/MySQL），MCP 20 个工具保留给运维经管理端点直连。框架限制从"可能导致 Agent 瘫痪的缺陷"降级为"不使用的功能"。

**③ 已知坑（主动承认）。** 自研工具放弃了 MCP 生态的复用（新数据源接入要自己写）；MCP 里少用的复杂能力（如 ES 的 get_shards、Prom 的 config/flags）需要时得回管理端点；RBAC 目前只有三级且依赖平台 claim 合规（平台若给普通用户发 OPERATOR 就等同于放权）；没有细粒度资源级鉴权（如"只能重投 A 服务的死信"）。

**④ 兜底手段。** 自研工具全部带参数校验/白名单/只读标记/审计；`es_search` 索引前缀白名单防越权检索；去 MCP 后有实测演练（kill 两个 MCP 子进程，ES/Prom 诊断仍正常，19s/39s）；RBAC 实测矩阵（OPERATOR JWT→403、admin 令牌→200）；管理/内部令牌走常量时间比较防时序攻击。

**⑤ 话术（30 秒版）。** "MCP 协议没有定义进程级热替换，框架也不支持，硬修不如不依赖——我们把 16 个工具全自研，MCP 只留给运维直连，于是'工具进程挂了 Agent 就瘫'这个风险从架构上消失了。权限同理：不重建账号体系，消费平台 JWT 的角色声明，管理端点全部收口到 ADMIN，危险操作永远走人工审批。如果重来，我会在选型第一周就做这两件事，而不是等到生产化清单。"

## V′. 组件深拷打：十个真实事故叙事（MQ/Redis/ES/MySQL/Nacos/XXL-Job/Sentinel/SkyWalking/Prometheus/ELK）

**① 业界背景。** 面试官问中间件，考的不是背书，是"你有没有在同一件事上吃过亏"。十类问题最常被挖：消息可靠性（MQ）、持久化与切主（Redis）、索引延迟与调优（ES）、事务与复制（MySQL）、注册配置与失效语义（Nacos）、调度与错过窗口（XXL-Job）、规则持久化与限流时序（Sentinel）。

**② 十个"事故叙事"速记表（每题=原理一句话+事故一个+数字一个+边界一个）。**

| 组件 | 原理一句话 | 真实事故/坑 | 关键数字 | 题 |
|---|---|---|---|---|
| RocketMQ | CommitLog 顺序写 + ConsumeQueue 索引，事务消息两阶段+回查 | 重试 16 次进 DLQ、UNKNOWN 回查语义、26 组 DLQ 指标恒 0 治理 | 26 组 | 40 |
| Redis | 单线程+Lua 原子、AOF everysec、Sentinel 异步复制 | 切主演练；双主同步 LWW；noeviction 是"不静默丢"的选择 | 切主 2.3s/458 键无损 | 41 |
| ES | refresh 近实时、倒排+doc_values、分片副本 | 压测 CPU 打满 202%（配额 2 核）→ 6 核 + IO 线程 4→16；yellow 索引巡检 | 508→1,448 RPS，P99 191→61ms | 42 |
| MySQL | B+ 树+MVCC+行锁、redo/binlog 两阶段提交 | 僵尸连接风暴 318 条挂起查询；故障转移 RTO 实测 | 连接 459→106，RTO≈32s | 43 |
| Nacos | 注册 AP/Distro、配置 CP、2.x gRPC(端口+1000) | 配置外置"假生效"：shared-configs 在 SCA 2023 失效 → import → code 300 → 200 | 15/15 Load success | 44 |
| XXL-Job | 调度/执行器分离、时间轮+DB 扫描、misfire/阻塞策略 | 非法 cron（`0/60` 秒增量越界）被调度器自动禁用"从未运行"；每日任务 DO_NOTHING 错过窗口静默丢弃 | 20 任务/633 失败口径拆解 | 46 |
| Sentinel | Slot 链 + LeapArray 滑动窗口，Dashboard 只观测 | Nacos 规则异步到达被 @PostConstruct 兜底覆盖 → 改 ApplicationReadyEvent + 30s 真空期 | 16 路由规则、40 处自定义 @RateLimit | 47 |
| SkyWalking | 字节码增强 Agent → OAP → 专用 ES（与业务隔离） | 插件与框架大版本冲突（SpringMVC 3/4/5 移出换 6.x）；曾『组件在跑但没数据』→ 2026-09-18 接入修复（release 自动挂载） | 15 服务注册、gateway→home 29 span（CROSS_PROCESS） | 51 |
| Prometheus | 拉模型 + TSDB + Alertmanager 路由分组抑制 | 通知黑洞（receiver 空/占位）→ alert-sink 落盘 + e2e 演练；短命告警被 group_wait 吞 → keep_firing_for | 9 组 40 规则 | 52 |
| ELK | Filebeat 采集 → Logstash 加工 → ES 日索引 | 漏网 replicas=1 索引把集群搞 yellow → 巡检 cron；保留双口径（设计 ILM 30d vs 环境 cron 7d） | 单日 650 万条/1.8GB | 53 |

**③ 已知坑（主动承认）。** 全部单机仿真环境：Redis 双主是 Worker 同步不是 Active-Active 集群；MySQL 无自动切换（RTO 分解里检测时间没算）；ES 单节点副本 0；Nacos standalone 无鉴权；MQ 单 master。每个组件都有一句"生产要怎么做"的对照。

**④ 兜底手段。** 组件问题最终都落成工程规则：DLQ 指标诚实化+三级治理；切主演练+正确性不押缓存；ES 资源瓶颈参数化+巡检 cron；MySQL 连接/慢查询告警+发布纪律；Nacos `optional:` 保启动韧性 + 全量发布指纹校验。

**⑤ 话术（30 秒版）。** "中间件我不背文档，讲事故：MQ 重试 16 次进 DLQ、Redis 切主 2.3 秒、ES CPU 打满后调到 1,448 RPS、MySQL 僵尸连接把连接顶到 459、Nacos 配置中心写了但没生效——每个坑的排查链、根因、修复和验证我都能展开，也知道生产环境分别还差什么。"

---

**⑥ 2026-09-20 新增追问（全部实测修复，按"事故→根因→修复→验证"答）。**

| 追问 | 事故/根因 | 修复 | 验证 |
|---|---|---|---|
| 多实例验证过吗？ | 跨实例 SSE **从未生效**：`isOnline` 只看本地 + 订阅者 Jackson `TextNode` 解析失败 | `isOnlineAnywhere`（本地或 Redis 路由键）+ `toLong` 支持 `JsonNode` | 实例B处理事件→实例A SSE 收到；IM 跨实例/网关 WS PASS |
| 分片有什么坑？ | 131 单全落 2/16 片：Snowflake `Δms<<22` 恒被 16 整除 → `mod 16` 恒定 | hashCode 取模 + 迁移 682 行（dry-run/停服 2 分钟） | 16/16 片全用、最大 31%；E2E 30/30 |
| Redis 挂了会怎样？ | 网关全站 401「Token 已被注销」（误导+误登出）；自定义 Lettuce 无 commandTimeout（60s）致读路径挂起 | 401→503 可重试；自定义工厂 1s commandTimeout + DB 回退 | 主库暂停 product 200（回退）；从库暂停无感 |
| 乱序消息改坏过计数吗？ | counter 无版本门：旧 UNLIKE 把计数 1→0（analytics 已拒 → 两侧分叉） | Like/Favorite 补 `actionTime` 版本门 | 旧事件注入被拒/新事件生效/API 路径回归 |
| 备份可靠吗？ | 备份脚本是云模板（无效 host/端口+吞错）→ 20B 空产物且无调度 | 重写 `docker exec` + size/gunzip 双校验 + cron | 3.0MB 实体备份并抽样；每日 3:30 |
| 扩容先崩哪里？ | 实测网关 2,375 RPS（直连 7,636）→ 10× 首爆点；Redis 单主 ~3 万 ops | 容量模型 + 三项校准数据 | capacity-model 报告 |

**⑦ 2026-09-20 第二轮新增追问（AI 实测，按"演练→数据→边界"答）。**

| 追问 | 实测数据 | 边界/roadmap |
|---|---|---|
| 主备真的切过吗？ | 注入 404 主模型：5/5 由备用回答；3 败熔断 60s、半开探测；降级 4.5s；指标可查 | 主备同 base-url，不抗供应商故障；ARK 待接 |
| 预算会不会被绕过？ | 演练抓出 chat 路径绕过（不拒绝不计量）→ 修复；agent 429/0.2s、chat 429/0.3s | AI 接口无按用户限流（roadmap） |
| 摘要压缩有用吗？ | 修两处真 bug 后 56.6s 生成；清 Redis 后 18.1s 三事实全中 | 摘要质量无专项评测（roadmap） |
| 评测可信吗？ | blocked≠fail；过时用例修正；重跑一次；幻觉引用被抓（inventory-three-stage） | 题库 30 例偏小自出题（roadmap） |
| 业务工具凭什么？ | 分片路由同哈希 3 例对上；库存 45/4/0 与 DB 一致；只读 SELECT 授权 | 直连 DB 耦合 schema/分片（roadmap 内部接口） |
| 注入防御测了吗？ | sec 5/5：提示注入/凭据/越权写/伪造审批/ADMIN_TOKEN 全拒 | 红队仅 5 例，待扩 30-50 |

