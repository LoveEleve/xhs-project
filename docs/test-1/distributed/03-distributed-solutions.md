# my-xhs 分布式解决方案

> 每个方案都必须能回答三个问题：1. 生产环境遇到什么问题？ 2. 为什么用这个方案？ 3. 方案失败怎么办？

---

## 1. MySQL 与 Redis 数据一致性

> 📖 **知识来源**：《分布式缓存：原理、架构及Go语言实现》第5章
> - 核心观点："缓存一致性三层保障——同步删缓存(先更新DB后删缓存)、延迟双删(500ms后再删一次)、异步监听binlog(Canal兜底)"
> - 关键问题："先删缓存后更新DB会导致旧值回填，线程A删缓存→线程B读DB旧值写缓存→线程A更新DB→缓存是旧值"
> - my-xhs对照：Cache Aside + 延迟双删 + Canal三层保障

### 1.1 方案对比

| 方案 | 原理 | 优缺点 | 适用场景 | my-xhs采用 |
|------|------|--------|----------|-------------|
| **Cache Aside（旁路缓存）** | 读：先缓存→无则读DB→写缓存；写：先更新DB→删缓存 | 最通用，但有极短不一致窗口 | 大多数场景 | ✅ 商品详情、用户信息 |
| **延迟双删** | 写DB→删缓存→延迟N毫秒→再删缓存 | 解决并发读写导致旧数据回填 | 一致性要求高 | ✅ 订单状态变更 |
| **Canal监听binlog** | Canal监听MySQL变更→MQ→消费删缓存/同步ES | 强一致性，但引入Canal | 强一致要求 | ✅ 商品数据同步 |
| **读写锁** | 读读共享、读写互斥 | 精确控制但增加复杂度 | 热点Key频繁更新 | ✅ 库存扣减 |

### 1.2 Cache Aside 的一致性问题

> 📖 《分布式缓存：原理、架构及Go语言实现》第5章
>
> **为什么"先删缓存后更新DB"是错的？**
> ```
> 线程A：删除缓存
> 线程B：读缓存未命中 → 读DB(旧值) → 写缓存(旧值)
> 线程A：更新DB(新值)
> → 结果：缓存是旧值，DB是新值，不一致！且会一直持续到缓存过期
> ```
>
> **为什么"先更新DB后删缓存"也有问题？**
> ```
> 1. 缓存刚好过期，请求A读DB得到旧值
> 2. 请求B更新DB为新值
> 3. 请求B删除缓存
> 4. 请求A将旧值写入缓存 ← 数据不一致！
>
> 但这个概率很低：步骤3必须在步骤4之前完成，
> 即"写DB+删缓存"比"读DB+写缓存"快，通常读比写快，所以出现概率极低
> ```
>
> **延迟双删的原理**（书中核心方案）：
> "延迟500ms再删一次缓存。为什么是500ms？
>  因为一次'读DB+写缓存'的耗时通常<500ms，延迟500ms后旧值已被覆盖，
>  第二次删除保证缓存最终是最新值。延迟时间 = 读DB+写缓存的最大耗时"
>
> **Canal兜底的原理**：
> "延迟双删仍有极小概率失败(延迟时间内又有新的并发写)。Canal监听binlog是
>  最终一致性的最后一道防线——无论前面哪种方案失败，binlog一定会记录DB变更，
>  Canal消费binlog后删缓存/同步ES，保证最终一致"

```
问题场景：
1. 缓存过期，请求A读DB得到旧值
2. 请求B更新DB为新值
3. 请求B删除缓存
4. 请求A将旧值写入缓存 ← 数据不一致！

解决：延迟双删
1. 请求B更新DB
2. 请求B删除缓存
3. 延迟500ms（大于一次读DB+写缓存的时间）
4. 再次删除缓存 ← 防止步骤4的旧值回填
```

### 1.3 生产环境一致性保障

> "我们主要用 Cache Aside，写操作更新 DB 后删缓存。但有个问题：并发场景下删缓存后、新缓存写入前，旧数据可能被回填。所以对一致性要求高的场景用延迟双删 + Canal 监听 binlog 兜底，三层保证。"
>
> **故障兜底**：Canal 不可用时降级为延迟双删；延迟双删也失败时，依赖 TTL 自然过期 + XXL-Job 对账修复。

---

## 2. 缓存三大问题

> 📖 《亿级流量系统架构设计与实战》第2章 + 《超大流量》第4章
>
> 书中的缓存问题分类和解决方案是面试必考题，也是生产环境必解问题。
> 三个问题的本质区别：
> - 穿透：数据**根本不存在**，请求绕过缓存直达DB
> - 击穿：数据**存在但过期了**，热点Key过期瞬间大量请求打到DB
> - 雪崩：**大量Key同时过期**或Redis整体不可用，请求全部打到DB

### 2.1 缓存穿透

**问题**：查询不存在的数据，缓存没有，DB也没有，每次都打到DB

| 解决方案 | 实现 | my-xhs落地 |
|----------|------|-------------|
| 布隆过滤器 | 查询前先过布隆过滤器，不存在直接返回 | ✅ 商品查询用BloomFilter |
| 缓存空值 | 查不到也缓存(null, TTL=5分钟) | ✅ 笔记查询 |

> **生产考量**：查询不存在的商品ID，布隆过滤器直接拦住，不存在的数据不可能过布隆过滤器。偶尔有误判(存在但实际不存在)，这种概率极低，加缓存空值兜底。
>
> **故障兜底**：布隆过滤器误判 + 缓存空值 TTL 过期 → DB查询，但QPS极低可接受。

### 2.2 缓存击穿

**问题**：热点Key过期瞬间，大量请求同时打到DB

| 解决方案 | 实现 | my-xhs落地 |
|----------|------|-------------|
| 互斥锁 | 只让1个请求查DB重建缓存，其他等 | ✅ 商品详情(分布式锁) |
| 逻辑过期 | 不设TTL，数据中存逻辑过期时间，过期后异步更新 | ✅ 笔记详情(不锁等待) |

> **生产考量**：商品详情用互斥锁——热点Key过期时只让1个请求查DB，其他等。笔记详情用逻辑过期——不设TTL，数据里存过期时间，过期后返回旧数据+异步更新，用户感知不到延迟。
>
> **故障兜底**：互斥锁获取超时 → 返回旧缓存或降级数据；异步更新失败 → 下次访问触发重试。

### 2.3 缓存雪崩

**问题**：大量Key同时过期，或Redis宕机

| 解决方案 | 实现 | my-xhs落地 |
|----------|------|-------------|
| TTL加随机值 | 基准TTL ± 30%随机偏移 | ✅ 所有缓存Key |
| Redis Sentinel | 主从+自动故障转移 | ✅ 基础设施 |
| Sentinel降级 | Redis不可用时降级走DB+限流 | ✅ Gateway层 |

> **生产考量**：TTL加随机值防止同时过期。Redis用Sentinel哨兵模式，主节点挂了自动故障转移。极端情况下Redis整体不可用，Gateway层Sentinel降级走DB查询+限流，保证系统不挂。
>
> **故障兜底**：Redis主从切换期间(约30秒)数据不可用 → 降级走DB+限流；Sentinel误判主节点下线 → 保护机制(至少2个Sentinel同意才切换)。

---

## 3. 分布式事务

> 📖 **知识来源**：《深入理解分布式事务》第6-7章 + 《凤凰架构》第3章
> - 《分布式事务》："2PC太重、TCC太复杂、Seata侵入性强。事务消息+本地消息表是电商场景最佳实践——轻量且可靠"
> - 《凤凰架构》："本地消息表是最后的兜底——本地事务中同时写业务表和消息表，定时任务扫描投递MQ，投递成功后标记已处理"
> - my-xhs对照：RocketMQ事务消息(主)+本地消息表(兜底)双保险

### 3.1 方案对比

> 📖 《深入理解分布式事务》第6-7章 — 四种分布式事务方案对比
>
> **书中核心对比**：
>
> | 方案 | 一致性 | 性能 | 复杂度 | 适用场景 | 代表实现 |
> |------|--------|------|--------|----------|----------|
> | XA/2PC | 强一致 | 低(同步阻塞) | 低 | 金融核心 | Atomikos/Narayana |
> | TCC | 最终一致 | 高 | 高(3个接口) | 电商交易 | Hmily |
> | 可靠消息 | 最终一致 | 高 | 中 | 异步业务 | RocketMQ事务消息 |
> | 最大努力通知 | 最终一致 | 高 | 低 | 跨系统通知 | 支付回调 |
>
> **为什么my-xhs选事务消息而非TCC？**
> "TCC需要为每个服务写3个接口(Try/Confirm/Cancel)，业务侵入性极强。
>  事务消息只需要1个本地事务+1个消息发送，业务侵入性低。
>  对于电商下单场景——'创建订单+扣库存+扣券'，事务消息足够：
>  订单创建成功则消息提交，库存/优惠券异步消费。失败则回滚。"
>
> **为什么不选Seata AT？**
> "Seata AT虽然侵入性低(自动解析SQL生成回滚日志)，但性能不如事务消息。
>  Seata的一阶段需要获取全局锁，并发高时锁竞争严重。事务消息无锁，并发性能好。"

| 方案 | 一致性 | 性能 | 复杂度 | 业务侵入 | my-xhs采用 |
|------|--------|------|--------|----------|-------------|
| 2PC | 强一致 | 低(同步阻塞) | 中 | 低 | ❌ 性能太差 |
| TCC | 最终一致 | 高 | 高(3个接口) | 高 | ❌ 业务侵入大 |
| Seata AT | 最终一致 | 中 | 低(自动回滚) | 低 | ✅ 非核心链路(优惠券扣减) |
| RocketMQ事务消息 | 最终一致 | 高 | 中 | 中 | ✅ 核心链路(下单) |
| 本地消息表 | 最终一致 | 高 | 中 | 中 | ✅ 兜底方案 |

### 3.2 下单事务消息流程

```
                订单服务                          库存服务                    优惠券服务
                   │                                │                          │
1.发送半消息 ─────→│                                │                          │
2.执行本地事务     │                                │                          │
  (创建订单)       │                                │                          │
3.提交/回滚 ─────→│                                │                          │
                   │                                │                          │
4.消费消息 ───────────────────────────────────────→│                          │
  (扣减库存)       │                                │                          │
                   │                                │                          │
5.消费消息 ─────────────────────────────────────────────────────────────────→│
  (扣减优惠券)     │                                │                          │
```

### 3.3 本地消息表兜底

> 📖 《凤凰架构》第3章 — 本地消息表原理
>
> **为什么事务消息还需要本地消息表？**
> "事务消息依赖Broker正常运行。极端情况：
>  1. Broker磁盘故障 → 半消息丢失 → 回查机制失效
>  2. Broker网络分区 → 无法发送Commit/Rollback → 订单创建成功但库存未扣
>  3. Broker整体宕机 → 所有事务消息中断
>  本地消息表是最后的兜底——它和业务表在同一个MySQL事务中，
>  只要MySQL不挂，消息就不会丢。Broker恢复后定时任务扫描补发。"
>
> **本地消息表设计要点**（书中关键）：
> 1. 消息表和业务表**必须在同一个数据库**，才能用本地事务保证原子性
> 2. 消息状态：0=待发送 1=已发送 2=已消费 3=消费失败
> 3. 定时扫描条件：status=0 AND create_time < NOW()-60秒
> 4. 防重复：消费端根据bizId做幂等校验

```
为什么需要本地消息表？
- RocketMQ可能丢消息(磁盘故障/网络分区)
- 半消息可能超时未提交

实现：
1. 下单时同时写入order表和local_message表(同一事务)
2. 发送MQ消息
3. XXL-Job每分钟扫描local_message表(status=未发送/超时)
4. 重新发送消息
5. 消费成功后更新local_message.status=已发送

幂等保证：
- 消费端通过@Idempotent注解保证重复消费不会重复扣减
```

### 3.4 生产环境事务保障

> "下单核心链路用 RocketMQ 事务消息，保证订单创建 + 库存扣减 + 券扣减最终一致。但 MQ 可能丢消息，所以加本地消息表定时扫描补偿。非核心链路如优惠券扣减用 Seata AT 模式，代码侵入小。"
>
> **故障兜底**：MQ Broker故障 → 本地消息表补偿；消费者处理失败 → 重试16次+死信队列；对账发现不一致 → 告警+人工介入。

---

## 4. 高并发库存扣减

> 📖 **知识来源**：《超大流量分布式系统架构解决方案》第7章 — 抢购技术
> - 核心观点："热点Key分桶，本质是把N个请求对1个Key的竞争，分散成对N个Key的竞争"
> - 方案演进："DB直接扣(QPS 500就扛不住) → Redis预扣(1个Key热点) → 分桶(不均匀) → 分桶+自动均衡(最终方案)"
> - my-xhs对照：4版演进到分桶+自动均衡+Lua原子扣减

### 4.1 方案演进

```
第1版：DB直接扣
  UPDATE stock SET count=count-1 WHERE id=? AND count>0
  问题：QPS 500就扛不住
  ↓

第2版：Redis预扣 + 异步落DB
  Lua: if count>0 then count=count-1 return 1 else return 0 end
  问题：热门商品1个Key是热点(10万人抢1个Key)
  ↓

第3版：Redis预扣 + 分桶
  1个商品库存拆10个桶，按userId % bucketCount路由
  问题：分桶不均匀导致某些桶先卖完
  ↓

第4版：Redis预扣 + 分桶 + 自动均衡 + Buffer-Trigger
  - 桶卖完自动路由到其他桶
  - Buffer-Trigger批量写DB
  → 最终方案
```

### 4.2 分桶策略

```
商品A库存1000件，分10个桶，每桶100件

桶1: stock:A:1 = 100
桶2: stock:A:2 = 100
...
桶10: stock:A:10 = 100

用户请求 → userId % 10 = 3 → 扣桶3
桶3卖完 → 自动路由到相邻桶(4,5...) → 再卖完 → 下一个桶

生产考量：
- 分桶数不是固定10，热门SKU分8桶，普通SKU分2桶
- 同一用户路由到同一桶(防超卖)，但桶卖完后可跨桶
- 桶间均衡不是实时均衡，而是扣减失败时触发路由
```

### 4.3 库存一致性三级保障

| 层级 | 实现 | 保障 |
|------|------|------|
| L1 实时性 | Redis Lua原子扣减 | 高并发下不超卖 |
| L2 最终性 | MQ异步扣DB | Redis和DB最终一致 |
| L3 修复性 | XXL-Job对账 | L1/L2失败时自动修复 |

---

## 5. 分布式ID

### 5.1 方案对比

| 方案 | 趋势递增 | 性能 | 依赖 | 缺点 | my-xhs采用 |
|------|----------|------|------|------|-------------|
| UUID | ❌ | 高 | 无 | 无序，B+树性能差 | ❌ |
| DB自增 | ✅ | 低 | MySQL | 分库分表后冲突 | ❌ |
| DB号段模式 | ✅ | 高 | MySQL | 号段用完需要取新段 | ✅ 用户ID |
| 雪花ID | ✅ | 高 | 时钟 | 时钟回拨问题 | ✅ 订单ID |
| Redis自增 | ✅ | 高 | Redis | Redis不可用则不可用 | ✅ 流水号 |

### 5.2 雪花ID + Redis分配WorkerId

```
雪花ID结构(64bit)：
0 | 00000000000000000000000000000000000000000 | 00000 | 00000 | 000000000000
符号 | 41bit时间戳(69年)                    | 5bit数据中心 | 5bit机器ID | 12bit序列号

问题：多实例部署，WorkerId不能重复
解决：Redis INCR 分配WorkerId(0-31)，实例启动时申请，关闭时释放

时钟回拨检测：
- 当前时间 < 上次时间 → 拒绝生成ID → 告警
- 回拨 < 5ms → 等待回拨时间后继续
- 回拨 ≥ 5ms → 报错，人工介入

故障兜底：
- Redis不可用 → 使用本地缓存的WorkerId(启动时已分配)
- 时钟回拨告警 → 运维介入检查NTP配置
```

### 5.3 生产环境ID策略

> "用户ID用号段模式——一次取1000个ID的段，用完再取，减少DB交互。订单ID用雪花ID——趋势递增适合B+树索引，WorkerId通过Redis分配保证唯一。时钟回拨做了检测，小回拨等待，大回拨报错告警。"
>
> **故障兜底**：Redis不可用 → 号段模式本地缓存足够支撑；雪花ID时钟回拨 → 告警+NTP校准。

---

## 6. 消息可靠性（全链路）

> 📖 **知识来源**：《深入理解分布式事务》第7章 — 消息可靠性保证
> - 核心观点："消息可靠性=生产端不丢+消费端不丢+不重复消费。三者缺一不可"
> - 生产端："事务消息保证'本地事务成功则消息一定发出'，回查机制兜底"
> - 消费端："幂等消费(业务唯一键去重) + 死信队列(消费失败3次进DLQ) + 人工兜底"
> - my-xhs对照：事务消息+本地消息表(生产端) + 幂等Key+死信队列(消费端)

### 6.1 六大环节

| 环节 | 问题 | 解决方案 | my-xhs落地 |
|------|------|----------|-------------|
| **生产端发送** | 发到MQ失败 | 本地消息表 + 定时重发 | ✅ |
| **MQ存储** | MQ宕机丢消息 | 同步刷盘 + 主从复制 | ✅ RocketMQ配置 |
| **MQ投递** | 网络问题未投递 | 消费者确认机制 | ✅ |
| **消费端处理** | 业务处理失败 | 重试(16次阶梯间隔) + 死信队列 | ✅ 封装在common |
| **消费端幂等** | 重复消费 | @Idempotent + Redis去重 | ✅ |
| **消息顺序** | 同一业务消息乱序 | HashQueue选择(同一key走同一queue) | ✅ 评论场景 |

### 6.2 死信队列处理

```
消费者失败 → 重试1次(10s) → 重试2次(30s) → ... → 重试16次(2h)
                                                            ↓
                                                      进入死信队列
                                                            ↓
                                                    DeadLetterHandler
                                                            ↓
                                              ┌────────────┼────────────┐
                                              ↓            ↓            ↓
                                          记录日志     告警通知    XXL-Job定时
                                          (ES/DB)     (钉钉/企微)   人工处理
```

### 6.3 生产环境消息保障

> "消息可靠性分6个环节保证：发送端用本地消息表保证发出去；MQ存储用同步刷盘+主从保证不丢；消费端用重试+死信队列保证最终处理；幂等消费用@Idempotent保证重复消费不出错；顺序消费用Hash队列保证评论有序。"
>
> **故障兜底**：MQ整体不可用 → 本地消息表暂存+定时重发；消费端死信 → 告警+人工介入+XXL-Job补偿。

---

## 7. 限流算法

> 📖 **知识来源**：《高并发系统：设计原理与实践》第5章 — 限流设计
> - 核心观点："限流不是简单地拒绝请求，而是保护系统在可承受范围内运行。固定窗口有临界突刺问题，滑动窗口更平滑"
> - 算法选择："固定窗口(简单但临界突刺)→滑动窗口(平滑)→令牌桶(允许突发)→漏桶(严格匀速)"
> - 分布式限流："单机限流不够，网关+业务服务双层限流。Redis Lua脚本保证原子性"
> - my-xhs对照：Gateway Sentinel限流(集群) + 业务@RateLimit注解(单机Redis Lua滑动窗口)

### 7.1 算法对比

| 算法 | 原理 | 优点 | 缺点 | my-xhs采用 |
|------|------|------|------|-------------|
| 固定窗口 | 1分钟内不超过N次 | 简单 | 临界点突发流量(0:59和1:01各N次) | ❌ |
| 滑动窗口 | 窗口平滑滑动 | 解决临界问题 | 实现复杂 | ✅ Redis Lua实现 |
| 漏桶 | 固定速率流出 | 流量平滑 | 无法应对突发 | ❌ |
| 令牌桶 | 固定速率生成令牌 | 允许适度突发 | — | ✅ Sentinel采用 |

### 7.2 my-xhs限流分层

| 层级 | 限流方式 | 场景 | 实现 |
|------|----------|------|------|
| 网关层 | IP级滑动窗口 | 同一IP 1秒100次 | Redis Lua |
| 服务层 | 接口级令牌桶 | 下单接口QPS限5000 | Sentinel |
| 参数级 | 热点参数限流 | 商品详情按SKU ID限流 | Sentinel热点参数 |
| 注解级 | @RateLimit | 同一用户1分钟点赞10次 | AOP + Redis Lua |

### 7.3 Redis Lua滑动窗口实现

```lua
-- 滑动窗口限流Lua脚本
-- KEYS[1] = 限流Key
-- ARGV[1] = 窗口大小(毫秒)
-- ARGV[2] = 最大请求数
-- ARGV[3] = 当前时间戳
-- ARGV[4] = 唯一请求ID

-- 移除窗口外的请求
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[3] - ARGV[1])

-- 获取当前窗口请求数
local count = redis.call('ZCARD', KEYS[1])

if count < tonumber(ARGV[2]) then
    -- 未超限，记录本次请求
    redis.call('ZADD', KEYS[1], ARGV[3], ARGV[4])
    redis.call('PEXPIRE', KEYS[1], ARGV[1])
    return 1  -- 允许
else
    return 0  -- 拒绝
end
```

---

## 8. 高可用

> 📖 **知识来源**：《大型网站技术架构：核心原理与案例分析》（李智慧）第2章
> - 核心观点："大型网站5大核心要素——性能、可用性、可伸缩、可扩展、安全"
> - 可用性计算："99.9%=年宕机8.76小时，99.99%=年宕机52.56分钟，99.999%=年宕机5.26分钟"
> - 核心策略："冗余(多实例多副本) + 隔离(熔断降级限流) + 监控(日志指标链路) + 容灾(主从切换)"
> - my-xhs对照：99.95%可用性目标 + Redis哨兵+MySQL主从+Sentinel熔断+全链路监控

### 8.1 各组件高可用方案

| 组件 | 故障场景 | 高可用方案 | my-xhs落地 | 故障恢复时间 |
|------|----------|-----------|-------------|-------------|
| MySQL | 主库挂 | ShardingSphere主从切换 + 从库升主 | ✅ | < 30秒 |
| Redis | 主节点挂 | Sentinel哨兵自动故障转移 | ✅ | < 30秒 |
| RocketMQ | Broker挂 | 主从同步复制 + DLedger自动切换 | ✅ | < 60秒 |
| Nacos | 节点挂 | 集群部署(3节点) + 本地缓存兜底 | ✅ | 无感知 |
| ES | 节点挂 | 副本分片自动提升为主分片 | ✅ | < 30秒 |
| Gateway | 实例挂 | K8s自动重启 + Nacos摘除 | ✅ | < 60秒 |
| 业务服务 | 实例挂 | K8s重启 + Nacos摘除 + Sentinel熔断 | ✅ | < 60秒 |

### 8.2 服务容错

| 场景 | 容错策略 | my-xhs落地 |
|------|----------|-------------|
| 服务雪崩 | Sentinel熔断降级 | ✅ 库存服务挂了→订单服务熔断→降级返回 |
| 流量突增 | 限流+排队 | ✅ 秒杀场景Gateway限流+库存预扣 |
| 调用超时 | Feign超时配置+重试 | ✅ 连接5s+读取10s+重试2次(幂等接口) |
| 缓存故障 | 降级+限流 | ✅ Redis不可用→走DB+限流降级 |
| 消息故障 | 死信队列+重试 | ✅ 消费失败16次重试→死信→人工处理 |
| 部署故障 | 优雅停机 | ✅ graceful shutdown + PreStop |

### 8.3 优雅停机

```
K8s滚动更新流程：
1. K8s发送SIGTERM信号
2. PreStop钩子执行：从Nacos注销 → 等待15秒(已有请求处理完)
3. Spring Boot graceful shutdown：等待现有请求完成(最多30秒)
4. 30秒后强制关闭

为什么需要？
- 不注销Nacos：新请求还会路由到即将关闭的实例
- 不等请求完成：正在处理的订单请求被中断，数据不一致
```

---

## 9. 优雅停机与数据安全

> 📖 **知识来源**：《分布式系统应用设计》(Brendan Burns, K8s创始人) 第3章
> - 核心观点："容器化应用必须处理SIGTERM信号——先从注册中心注销，再等待进行中的请求完成，最后退出"
> - K8s设计模式："PreStop钩子做延迟(Nacos注销+等流量切走)，terminationGracePeriodSeconds给足时间"
> - 数据安全："写操作必须确认落盘再返回，Buffer-Trigger中的数据停机前必须flush"
> - my-xhs对照：PreStop从Nacos注销 + 30秒等请求完成 + Buffer-Trigger shutdown时flush

| 场景 | 解决方案 | my-xhs落地 |
|------|----------|-------------|
| K8s滚动更新杀Pod | Spring Boot graceful shutdown + PreStop钩子 | ✅ |
| 正在处理的请求 | 等待现有请求完成(默认30s) | ✅ |
| RocketMQ消费中 | 消费线程优雅退出 + 消息重新入队 | ✅ |
| Redis连接池 | 关闭前归还所有连接 | ✅ |
| 定时任务 | XXL-Job调度中心感知下线，不再调度 | ✅ |
| 本地缓存 | 请求处理完再清理，不强制清 | ✅ |
| Buffer-Trigger | 等待Buffer中数据刷完再停机 | ✅ |

---

## 10. 数据库设计要点

### 10.1 分库分表策略

| 业务 | 分片键 | 分库数 | 分表数/库 | 分片算法 | 选型理由 |
|------|--------|--------|-----------|----------|----------|
| 订单 | buyer_id | 4 | 8 | 一致性哈希 | 按买家ID分片，同一买家订单在同一库，查询不跨库 |
| 优惠券 | buyer_id | 4 | 8 | 范围分片 | 按买家ID范围，历史券可归档到冷库 |

### 10.2 读写分离

```
写入 → 主库
读取 → 从库(ShardingSphere路由)

注意：
- 写后立即读：主从同步有延迟(通常<1ms)，写后读走主库
- ShardingSphere HintManager 强制走主库的场景：
  1. 下单后立即查订单详情
  2. 支付后立即查支付状态
```

### 10.3 索引设计原则

| 原则 | 说明 | my-xhs落地 |
|------|------|-------------|
| 联合索引最左前缀 | 查询条件按索引顺序 | ✅ 订单表(buyer_id, create_time) |
| 覆盖索引 | 查询字段都在索引中 | ✅ 订单列表只查索引字段 |
| 避免索引失效 | 不在索引列做函数运算 | ✅ 代码Review保证 |
| 分页优化 | 深分页用游标而非offset | ✅ 订单列表游标分页 |

---

## 11. 灾备与故障恢复

### 11.1 故障等级定义

| 等级 | 定义 | 响应时间 | 通知方式 |
|------|------|----------|----------|
| P0 | 核心链路不可用(下单/登录) | 5分钟内响应 | 电话+企微 |
| P1 | 非核心功能不可用(搜索/通知) | 15分钟内响应 | 企微 |
| P2 | 性能降级(RT升高/错误率升高) | 30分钟内响应 | 企微 |
| P3 | 告警但未影响用户 | 次日处理 | 邮件 |

### 11.2 核心场景故障预案

| 场景 | 影响 | 预案 | 恢复时间目标 |
|------|------|------|-------------|
| Redis主节点宕机 | 缓存不可用 | Sentinel自动故障转移 | < 30秒 |
| MySQL主库宕机 | 写入不可用 | ShardingSphere主从切换 | < 30秒 |
| RocketMQ Broker宕机 | 消息不可用 | 主从切换+本地消息表兜底 | < 60秒 |
| ES节点宕机 | 搜索不可用 | 降级走DB LIKE查询 | < 30秒 |
| 全链路不可用 | 用户无法使用 | K8s自动重启+降级 | RTO < 5分钟 |

### 11.3 数据恢复

| 场景 | 方案 | RPO |
|------|------|-----|
| MySQL数据误删 | 主从延迟窗口内从从库恢复 | < 1分钟 |
| Redis数据丢失 | 从MySQL全量重建+业务补偿 | < 5分钟 |
| ES索引损坏 | XXL-Job全量重建 | < 30分钟 |
| 订单数据不一致 | 对账修复 | < 1小时 |

> 详细备份策略与恢复脚本见 `04-基础设施与部署.md §12 数据备份与恢复`

---

## 12. 分布式锁最佳实践

> 📖 **知识来源**：《Redis深度历险》第5章 + Redisson官方文档
> - 核心观点："分布式锁的本质是互斥——同一时刻只有一个客户端能持有锁。Redisson的看门狗机制解决了锁续期问题"
> - 关键问题："SET NX EX 有三个坑：1.锁过期但业务没完成 2.主从切换锁丢失 3.不可重入导致死锁"
> - my-xhs对照：Redisson看门狗(主) + RedLock(关键场景) + 降级(锁不可用时)

### 12.1 方案对比

| 方案 | 原理 | 优缺点 | my-xhs采用 |
|------|------|--------|-------------|
| SET NX EX | Redis单节点互斥 | 简单，但主从切换锁丢失 | ❌ |
| Redisson看门狗 | 后台线程续期(默认30s/10s续一次) | 解决锁过期但业务没完成 | ✅ 默认方案 |
| RedLock | 多节点(N/2+1)加锁成功才算 | 解决主从切换锁丢失 | ✅ 关键场景(库存扣减) |
| ZK锁 | 临时顺序节点 | 强一致，但性能差 | ❌ |

### 12.2 @DistributedLock 注解设计

```java
// 使用示例
@DistributedLock(key = "'order:cancel:' + #orderId", waitTime = 3000, leaseTime = 10000)
public void cancelOrder(Long orderId) { ... }

// 注解定义
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {
    String key();           // SpEL表达式，支持方法参数
    long waitTime() default 3000;   // 等待获取锁的最大时间(ms)
    long leaseTime() default -1;    // 锁持有时间(ms)，-1=看门狗续期
    TimeUnit timeUnit() default TimeUnit.MILLISECONDS;
}
```

### 12.3 锁降级策略

```
Redisson获取锁超时 → 降级策略：
1. 可降级业务（如点赞）：跳过锁 → 直接操作（短暂超卖可接受） → 异步补偿
2. 不可降级业务（如库存扣减）：快速失败 → 返回"系统繁忙请重试"
3. Redis不可用 → 本地锁(ReentrantLock) → 单机互斥 → 集群超卖由对账修复
```

### 12.4 生产考量

> "Redisson看门狗解决锁续期问题——业务没完成锁不会过期。RedLock解决主从切换锁丢失——多节点加锁，半数以上成功才算获取锁。关键场景（库存扣减）用RedLock，普通场景用单节点Redisson锁。锁不可用时按业务重要性选择降级或快速失败。"
>
> **故障兜底**：Redis不可用 → 本地锁降级；锁续期失败 → 业务方法执行完自动释放；对账发现超卖 → 告警+人工补偿。

---

## 13. 接口幂等设计

> 📖 **知识来源**：《分布式系统设计原则》第4章 + HTTP幂等性规范
> - 核心观点："幂等性=同一操作执行一次和多次效果相同。PUT/DELETE天然幂等，POST需要设计"
> - 实现思路："服务端为每个请求分配唯一ID，处理前检查是否已处理过"
> - my-xhs对照：@Idempotent注解 + Redis去重 + 本地消息表bizId幂等

### 13.1 幂等场景分类

| 场景 | 幂等方式 | my-xhs落地 |
|------|----------|-------------|
| 下单 | 请求Token(提交前申请，提交时验证) | ✅ 订单提交 |
| 支付回调 | bizId去重（订单号唯一） | ✅ MockPayService |
| 库存扣减 | 消息消费幂等（bizId+状态判断） | ✅ 库存消费 |
| 点赞/收藏 | 状态幂等（已点赞再点赞=无操作） | ✅ 社交服务 |
| MQ消费 | Redis去重 + DB唯一键 | ✅ 所有消费者 |

### 13.2 @Idempotent 注解设计

```java
// 使用示例
@Idempotent(key = "'order:create:' + #request.userId + ':' + #request.orderToken", ttl = 10)
public OrderVO createOrder(CreateOrderRequest request) { ... }

// 注解定义
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
    String key();           // SpEL表达式，支持方法参数
    int ttl() default 10;   // 幂等Key过期时间(秒)，防止永久阻塞
}
```

### 13.3 请求Token方案（防重复提交）

```
下单流程：
1. 用户进入下单页 → GET /order/token → 服务端生成Token存Redis(key=token, TTL=10分钟)
2. 用户提交订单 → POST /order/create + Header: X-Order-Token=xxx
3. 网关校验Token → Redis DEL token → 成功则放行，失败则"请勿重复提交"
4. 订单服务创建订单（Token保证不重复提交 + @Idempotent保证业务幂等）

关键：Token必须是"一次性"的——用DEL而非GET+DEL，保证原子性
```

### 13.4 消费端幂等（MQ消息去重）

```
消费端幂等三层保障：
1. Redis SETNX幂等Key（快速去重）—— Key = consume:topic:group:msgId，TTL=7天
2. DB唯一键（兜底去重）—— 如t_user_coupon(buyer_id, template_id)唯一约束
3. 状态判断（业务幂等）—— 如订单已是"已支付"状态，再收到支付成功消息直接返回成功
```

### 13.5 生产考量

> "接口幂等分三类：请求Token防重复提交、bizId去重防重复消费、状态幂等防业务重复操作。三层缺一不可——Token拦截前端重复点击，bizId拦截MQ重复投递，状态判断兜底极端场景。"
>
> **故障兜底**：Redis不可用 → DB唯一键兜底；唯一键冲突 → 返回"请勿重复操作"；状态已变更 → 直接返回成功（幂等）。

---

## 14. 延时消息方案

> 📖 **知识来源**：RocketMQ官方文档 — 延时消息
> - 核心观点："延时消息是定时任务的轻量替代——不需要XXL-Job调度，消息本身带延时，到期自动投递"
> - 关键限制："RocketMQ开源版仅支持18个固定延时级别(1s~2h)，5.x支持任意延时"
> - my-xhs对照：RocketMQ 5.x任意延时(主) + XXL-Job定时扫描(兜底)

### 14.1 延时场景

| 场景 | 延时时间 | 方案 | my-xhs落地 |
|------|----------|------|-------------|
| 订单超时关单 | 30分钟 | 延时消息 | ✅ 下单时发送30分钟延时消息 |
| 优惠券即将过期提醒 | 到期前1天 | 延时消息 | ✅ 领券时计算延时时间 |
| 支付超时取消 | 15分钟 | 延时消息 | ✅ 支付创建时发送 |
| 自动收货 | 7天 | 延时消息 | ✅ 发货时发送7天延时消息 |
| 好友请求过期 | 7天 | 延时消息 | ✅ 发送请求时发送 |

### 14.2 RocketMQ 5.x 任意延时

```java
// RocketMQ 5.x 任意延时消息发送
Message message = MessageBuilder
    .withPayload(orderClosePayload)
    .setDelayTimeSec(30 * 60)  // 任意秒数，5.x新特性
    .build();

rocketMQTemplate.syncSend("order-close-topic", message);
```

### 14.3 XXL-Job 兜底方案

```
为什么延时消息还需要XXL-Job兜底？
1. 延时消息可能丢失（Broker故障/网络分区）
2. 消费端处理失败（业务异常/服务重启）
3. 延时消息投递有秒级误差，不保证精确时间

XXL-Job兜底策略：
- 每分钟扫描：SELECT * FROM t_order WHERE status=1 AND create_time < NOW()-35分钟
- 比延时消息多5分钟，避免正常关单和Job冲突
- 扫描到的订单执行关单逻辑（释放库存+退券+通知）
- 幂等保证：关单操作本身是幂等的（状态判断status=1才关）
```

### 14.4 生产考量

> "延时消息是关单的主方案——精确到分钟级，比XXL-Job扫描更及时。但延时消息不是100%可靠，所以XXL-Job每分钟扫描兜底。两者配合：延时消息及时关单，XXL-Job补偿漏网之鱼。关单操作本身是幂等的——只有'已创建'状态的订单才会被关，重复执行不会出问题。"
>
> **故障兜底**：延时消息丢失 → XXL-Job兜底扫描；XXL-Job不可用 → 延时消息正常关单；两者都不可用 → 人工对账（极低概率）。

---

## 15. 请求级超时预算（Timeout Budget）

> 📖 **知识来源**：Google SRE — Chapter 22: Addressing Cascading Failures
> - 核心观点："每个请求都有一个总预算，子调用的超时不能超过剩余预算。否则6个服务串行调用，每个3秒超时，用户最坏等18秒"
> - 关键原则："超时预算通过Header透传，子调用超时 = min(自身超时, 剩余预算)"
> - my-xhs对照：Gateway注入总预算 → Feign Interceptor透传 → 各服务动态计算子调用超时

### 15.1 问题场景

```
传统方式（华仔的做法）：
  每个Feign调用超时3秒，下单链路调用6个服务
  最坏情况：3s × 6 = 18秒 → 用户等18秒才返回超时

超时预算方式（my-xhs的做法）：
  用户可接受最大等待 = 3秒
  Gateway注入 X-Timeout-Budget-Ms: 3000
  每个子调用消耗预算，剩余不足时跳过非关键调用
```

### 15.2 下单链路预算分配

```
Gateway → Order: 总预算 3000ms
  │
  ├─ Order → User(查地址): 预算 300ms（并行）
  ├─ Order → Product(快照): 预算 300ms（并行）
  │  并行调用耗时 = max(300, 300) = 300ms，剩余 2700ms
  │
  ├─ Order → Inventory(扣库存): 预算 500ms（串行，必须等）
  │  剩余 2200ms
  │
  ├─ Order → Coupon(扣券): 预算 500ms（串行，必须等）
  │  剩余 1700ms
  │
  ├─ Order 本地逻辑(写DB+发MQ): 预算 400ms
  │  剩余 1300ms
  │
  └─ 预留缓冲: 1300ms（网络延迟+GC停顿+重试）

规则：
  - 子调用超时 = min(自身超时, 剩余预算)
  - 剩余预算 < 200ms 时，跳过非关键调用（如通知）
  - 超时预算通过 Header 透传（X-Timeout-Budget-Ms）
```

### 15.3 实现方案

```java
// 1. Gateway注入总预算
public class TimeoutBudgetFilter implements GlobalFilter {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long budget = getTimeoutBudget(exchange.getRequest().getPath());
        exchange.getRequest().mutate()
            .header("X-Timeout-Budget-Ms", String.valueOf(budget))
            .header("X-Request-Start-Ms", String.valueOf(System.currentTimeMillis()));
        return chain.filter(exchange);
    }
}

// 2. Feign Interceptor透传并计算剩余预算
public class TimeoutBudgetInterceptor implements RequestInterceptor {
    @Override
    public void apply(RequestTemplate template) {
        long startMs = Long.parseLong(RequestContextHolder.getHeader("X-Request-Start-Ms"));
        long totalBudget = Long.parseLong(RequestContextHolder.getHeader("X-Timeout-Budget-Ms"));
        long elapsed = System.currentTimeMillis() - startMs;
        long remaining = totalBudget - elapsed;
        
        template.header("X-Timeout-Budget-Ms", String.valueOf(remaining));
        template.header("X-Request-Start-Ms", String.valueOf(System.currentTimeMillis()));
        
        // 动态设置Feign超时 = min(默认超时, 剩余预算)
        Options options = new Options(
            1000, TimeUnit.MILLISECONDS,  // 连接超时
            Math.min(3000, remaining), TimeUnit.MILLISECONDS  // 读超时
        );
    }
}
```

### 15.4 生产考量

> "每个请求都有超时预算，Gateway注入总预算3秒，通过Header透传到每个子调用。子调用的超时 = min(自身超时, 剩余预算)。剩余预算不足时跳过非关键调用（如通知推送），保证核心链路在预算内完成。这样用户最多等3秒，不会出现'6个服务串行超时等18秒'的情况。"
>
> **故障兜底**：Header丢失 → 使用默认超时配置；预算耗尽 → 快速失败返回"系统繁忙"；非关键调用被跳过 → MQ异步补偿。

---

## 16. 自动降级决策引擎

> 📖 **知识来源**：Netflix Hystrix设计理念 + Sentinel自适应保护
> - 核心观点："降级不应该依赖人工开关，而是基于实时指标自动决策。人工开关的问题：凌晨3点故障没人值班"
> - 关键区别："Sentinel熔断是'单服务自保'，自动降级引擎是'全局决策'——根据多个指标综合判断降级级别"
> - my-xhs对照：Sentinel熔断(单服务) + DegradeDecisionEngine(全局) + Nacos配置(开关)

### 16.1 降级级别定义

| 级别 | 触发条件 | 降级策略 | 恢复条件 |
|------|----------|----------|----------|
| L0 正常 | 所有指标正常 | 全功能可用 | — |
| L1 轻度降级 | 错误率>5%持续30秒 或 RT P99>3秒持续1分钟 | 关闭推荐/热搜/搜索建议 | 错误率<1%持续5分钟 |
| L2 中度降级 | 错误率>20%持续10秒 或 Redis不可用 | L1 + 关闭通知/SSE推送 + 缓存降级走DB | 错误率<5%持续5分钟 |
| L3 重度降级 | 核心链路不可用 或 MySQL主库不可用 | L2 + 关闭非核心接口 + 只保留下单/支付 | 核心链路恢复持续10分钟 |

### 16.2 决策引擎架构

```
┌─────────────────────────────────────────────────────────┐
│                  DegradeDecisionEngine                   │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  ┌──────────┐   ┌──────────┐   ┌──────────┐            │
│  │Prometheus│   │ Sentinel │   │  Health   │            │
│  │  指标     │   │  指标     │   │  Check   │            │
│  └────┬─────┘   └────┬─────┘   └────┬─────┘            │
│       │              │              │                    │
│       └──────────────┼──────────────┘                    │
│                      ▼                                   │
│              ┌───────────────┐                           │
│              │  规则引擎       │                           │
│              │  (多指标综合)   │                           │
│              └───────┬───────┘                           │
│                      ▼                                   │
│              ┌───────────────┐                           │
│              │  决策输出       │                           │
│              │  L0/L1/L2/L3  │                           │
│              └───────┬───────┘                           │
│                      ▼                                   │
│              ┌───────────────┐                           │
│              │  Nacos配置更新  │  ← 自动更新降级开关       │
│              └───────────────┘                           │
│                                                          │
│  恢复策略：半开模式 → 先放10%流量验证 → 逐步恢复          │
└─────────────────────────────────────────────────────────┘
```

### 16.3 恢复策略（半开模式）

```
降级恢复不是"一刀切恢复"，而是渐进式：

L2 → L1 恢复流程：
  1. 错误率<5%持续5分钟 → 进入"半开"状态
  2. 半开状态：恢复10%的降级功能（如开启搜索建议）
  3. 观察2分钟：错误率仍<5% → 恢复50%
  4. 再观察2分钟：错误率仍<5% → 完全恢复到L1
  5. 任何阶段错误率回升 → 立即回退到L2

L1 → L0 恢复流程：同上，但观察时间更长（5分钟）
```

### 16.4 生产考量

> "降级不靠人工开关，靠自动决策引擎。引擎综合Prometheus指标（错误率/RT）、Sentinel指标（熔断状态）、健康检查（中间件可用性）三个维度，自动判断降级级别并更新Nacos配置。恢复采用半开模式——先放10%流量验证，逐步恢复，避免'恢复后又崩'的二次故障。"
>
> **故障兜底**：决策引擎本身故障 → 保持当前降级级别不变（安全优先）；Nacos不可用 → 本地缓存的降级配置生效；误判降级 → 人工可通过Nacos覆盖自动决策。

---

## 17. 数据倾斜检测与自动再均衡

> 📖 **知识来源**：《高性能MySQL》第7章 — 分区与分片 + ShardingSphere官方文档
> - 核心观点："分库分表只解决了'怎么分'的问题，没有解决'分完之后不均匀怎么办'"
> - 关键场景："按buyer_id % 4分库，如果某些用户是大买家，某个分片数据量可能是其他分片的10倍"
> - my-xhs对照：XXL-Job定时检测 + Prometheus告警 + 在线扩分片方案

### 17.1 倾斜类型

| 倾斜类型 | 表现 | 检测方式 | 影响 |
|----------|------|----------|------|
| 数据量倾斜 | 某分片行数远超其他分片 | 定时统计各分片行数 | 磁盘/内存不均匀 |
| QPS倾斜 | 某分片请求量远超其他分片 | Prometheus监控各分片QPS | 热点分片成为瓶颈 |
| 热点Key倾斜 | 大V/大买家集中在某分片 | 慢查询分析+QPS监控 | 单分片性能劣化 |

### 17.2 检测方案

```sql
-- XXL-Job定时任务：每天凌晨统计各分片数据量
-- 订单分片数据量检测
SELECT 
    'order_db_0' AS shard, COUNT(*) AS row_count FROM my_xhs_order_0.t_order
UNION ALL
SELECT 
    'order_db_1' AS shard, COUNT(*) AS row_count FROM my_xhs_order_1.t_order
UNION ALL
SELECT 
    'order_db_2' AS shard, COUNT(*) AS row_count FROM my_xhs_order_2.t_order
UNION ALL
SELECT 
    'order_db_3' AS shard, COUNT(*) AS row_count FROM my_xhs_order_3.t_order;

-- 告警规则：某分片数据量 > 平均值 × 2 → 触发告警
-- Prometheus指标：shard_row_count{db="order", shard="0"} = 25000000
```

### 17.3 再均衡方案（在线扩分片 4→8）

```
在线扩分片流程（零停机）：

Phase 1: 准备期
  1. 新建4个分片库(order_db_4 ~ order_db_7)
  2. 配置ShardingSphere新分片规则(buyer_id % 8)，但暂不生效

Phase 2: 双写期
  3. 开启双写：写操作同时写旧分片和新分片
  4. 全量数据迁移：XXL-Job分批迁移旧分片数据到新分片
  5. 增量对账：Canal监听旧分片binlog，补偿迁移期间的增量

Phase 3: 切换期
  6. 停止双写，切换读写到新分片规则(buyer_id % 8)
  7. 验证数据一致性（全量对账）
  8. 清理旧分片中已迁移的数据

关键保障：
  - 双写期间幂等：通过订单号唯一键保证不重复写入
  - 切换瞬间：Gateway短暂限流(1秒)，等待进行中的写操作完成
  - 回滚方案：切换失败 → 回退到旧分片规则，双写数据不影响
```

### 17.4 生产考量

> "分库分表后必须监控数据倾斜。XXL-Job每天凌晨统计各分片行数和QPS，某分片超过平均值2倍触发告警。扩分片采用双写+全量迁移+增量对账的零停机方案，切换瞬间Gateway短暂限流保证数据一致。"
>
> **故障兜底**：倾斜未及时发现 → Prometheus告警兜底；扩分片失败 → 回退旧规则；数据不一致 → 全量对账修复。

---

## 18. 幂等性增强：返回上次成功结果

> 📖 **知识来源**：HTTP幂等性规范 + 支付宝/微信支付幂等设计
> - 核心观点："生产级幂等不是'报错重复请求'，而是'返回上次成功结果'。支付宝的幂等就是这样——重复支付请求返回上次支付结果"
> - 关键区别："报错型幂等(华仔) vs 结果缓存型幂等(my-xhs)"
> - my-xhs对照：@Idempotent注解增强 + Redis结果缓存 + SpEL表达式提取幂等键

### 18.1 华仔的幂等 vs my-xhs的幂等

| 维度 | 华仔的幂等 | my-xhs的幂等（增强版） |
|------|-----------|---------------------|
| 命中行为 | 返回"重复请求"错误 | **返回上次成功结果** |
| 幂等键 | 硬编码 | **SpEL表达式动态提取** |
| 结果缓存 | 无 | **Redis缓存上次返回值** |
| 适用场景 | 防重复提交 | 防重复提交 + 网络重试 + MQ重复消费 |

### 18.2 增强版@Idempotent注解

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
    /**
     * 幂等键，支持SpEL表达式
     * 示例: "#request.orderNo + ':' + #request.userId"
     */
    String key();
    
    /**
     * 幂等键提取策略
     */
    KeyStrategy keyStrategy() default KeyStrategy.SPEL;
    
    /**
     * 过期时间(秒)
     */
    int expireSeconds() default 300;
    
    /**
     * 幂等命中时的行为
     * RETURN_CACHED: 返回上次成功结果（默认，生产级）
     * REJECT: 返回错误信息（简单场景）
     */
    IdempotentAction action() default IdempotentAction.RETURN_CACHED;
    
    /**
     * REJECT模式下的错误消息
     */
    String rejectMessage() default "请勿重复操作";
}

// 使用示例
@Idempotent(
    key = "#request.orderNo + ':' + #request.userId",
    expireSeconds = 300,
    action = IdempotentAction.RETURN_CACHED
)
public OrderResponse createOrder(CreateOrderRequest request) {
    // 首次执行：正常创建订单，结果自动缓存到Redis
    // 重复执行：直接返回上次缓存的OrderResponse，不再执行方法体
}
```

### 18.3 实现原理

```
首次请求：
  1. 计算幂等键: idempotent:order:create:ORD123:USER456
  2. Redis SETNX(key, "PROCESSING", 300s) → 成功
  3. 执行业务方法 → 得到结果 OrderResponse
  4. Redis SET(key, JSON序列化(OrderResponse), 300s) → 缓存结果
  5. 返回 OrderResponse

重复请求：
  1. 计算幂等键: idempotent:order:create:ORD123:USER456
  2. Redis GET(key) → 得到缓存的 OrderResponse JSON
  3. 反序列化 → 直接返回 OrderResponse（不执行方法体）

并发请求：
  1. 请求A: SETNX成功 → 执行业务
  2. 请求B: SETNX失败 → 等待100ms → 重试GET → 得到结果 → 返回
  3. 请求B最多等待3秒，超时返回"系统处理中，请稍后查询"
```

### 18.4 生产考量

> "生产级幂等不是报错'重复请求'，而是返回上次成功结果。用户网络抖动重试、MQ重复投递，都应该得到正确的结果而非错误。@Idempotent注解支持SpEL表达式动态提取幂等键，命中时从Redis读取上次缓存的返回值直接返回。"
>
> **故障兜底**：Redis不可用 → 降级为DB唯一键兜底（报错模式）；缓存结果过期 → 重新执行业务方法（业务本身也是幂等的）；并发请求 → 等待+重试机制。

---

## 19. 慢查询自动发现与治理闭环

> 📖 **知识来源**：《高性能MySQL》第3章 — 查询性能优化
> - 核心观点："慢查询不是'发现了就完了'，而是要形成'发现→分析→优化→验证'的闭环"
> - my-xhs对照：slow_query_log → Filebeat → ES → Grafana看板 → XXL-Job自动分析 → 索引建议

### 19.1 慢查询治理架构

```
┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐
│  MySQL   │───→│ Filebeat │───→│    ES    │───→│ Grafana  │
│slow_query│    │  采集     │    │  存储    │    │  看板    │
│  _log    │    │          │    │          │    │          │
└──────────┘    └──────────┘    └──────────┘    └──────────┘
                                                      │
                                                      ▼
                                               ┌──────────┐
                                               │ XXL-Job  │
                                               │ 自动分析  │
                                               │ 周报生成  │
                                               └──────────┘
```

### 19.2 MySQL慢查询配置

```sql
-- MySQL慢查询配置
SET GLOBAL slow_query_log = ON;
SET GLOBAL long_query_time = 0.2;  -- 200ms以上算慢查询
SET GLOBAL log_queries_not_using_indexes = ON;  -- 未使用索引的查询也记录
SET GLOBAL slow_query_log_file = '/var/log/mysql/slow.log';
```

### 19.3 自动分析与治理

```
XXL-Job定时任务（每周一凌晨3点）：

1. 从ES查询过去7天的慢查询Top20
2. 对每条慢查询执行EXPLAIN分析
3. 自动识别问题类型：
   - type=ALL → 全表扫描 → 建议添加索引
   - rows > 10万 → 扫描行数过多 → 建议优化WHERE条件
   - Using filesort → 文件排序 → 建议添加排序索引
   - Using temporary → 临时表 → 建议优化GROUP BY
4. 生成慢查询周报（Markdown格式）
5. 发送到企微/钉钉告警群

周报示例：
  | 排名 | SQL摘要 | 平均RT | 执行次数 | 问题 | 建议 |
  | 1 | SELECT * FROM t_order WHERE status=1 | 1.2s | 5000 | 全表扫描 | 添加idx_status索引 |
  | 2 | SELECT * FROM t_note ORDER BY created_at | 0.8s | 3000 | 文件排序 | 添加idx_created_at索引 |
```

### 19.4 生产考量

> "慢查询治理不是'发现了就完了'，而是闭环：MySQL慢查询日志→Filebeat采集→ES存储→Grafana实时看板→XXL-Job每周自动分析生成Top20周报→自动EXPLAIN标记问题→推送到告警群。从发现到治理全自动化。"
>
> **故障兜底**：Filebeat采集失败 → 慢查询日志本地保留7天可手动分析；ES不可用 → 降级为直接分析MySQL慢查询日志文件。

---

## 20. 数据生命周期自动化管理

> 📖 **知识来源**：《大型网站技术架构》第6章 — 数据管理
> - 核心观点："数据不是'写进去就不管了'，热数据→温数据→冷数据→归档的全生命周期必须自动化"
> - my-xhs对照：XXL-Job定时归档 + ES ILM Policy + 分批迁移防长事务

### 20.1 数据归档策略

| 数据类型 | 热数据 | 温数据 | 冷数据 | 归档/删除 | 归档方式 |
|----------|--------|--------|--------|-----------|----------|
| 订单 | 3个月（MySQL主库） | 1年（MySQL从库） | 3年（归档库） | 3年后归档 | XXL-Job分批迁移 |
| 笔记 | 6个月（MySQL+Redis） | 2年（MySQL） | 永久（冷库） | 不删除 | 标记不可见 |
| 日志 | 7天（ES热索引） | 30天（ES温索引） | 90天（ES冷索引） | 90天后删除 | ES ILM Policy |
| 通知 | 1个月（Redis+MySQL） | 6个月（MySQL） | 1年（归档库） | 1年后归档 | XXL-Job分批迁移 |
| 计数流水 | 1天（MySQL） | — | — | 每天清理 | XXL-Job每日清理 |

### 20.2 订单归档实现

```java
/**
 * XXL-Job定时任务：订单归档（每天凌晨2点）
 * 策略：扫描超过3个月的订单 → 分批迁移到归档表 → 延迟7天后删除原表数据
 */
@XxlJob("orderArchiveTask")
public void orderArchive() {
    LocalDateTime archiveDate = LocalDateTime.now().minusMonths(3);
    int batchSize = 10000;  // 每批1万条，避免长事务
    
    while (true) {
        // 1. 分批查询待归档订单
        List<Order> orders = orderMapper.selectByCreatedBefore(archiveDate, batchSize);
        if (orders.isEmpty()) break;
        
        // 2. 批量插入归档表（同库事务）
        orderArchiveMapper.batchInsert(orders);
        
        // 3. 标记原表为"已归档"（不立即删除，延迟7天防误删）
        List<Long> ids = orders.stream().map(Order::getId).collect(Collectors.toList());
        orderMapper.markArchived(ids);
        
        // 4. 每批间隔100ms，避免DB压力过大
        Thread.sleep(100);
    }
}

/**
 * XXL-Job定时任务：清理已归档订单（每天凌晨4点）
 * 策略：删除7天前标记为"已归档"的订单
 */
@XxlJob("orderArchiveCleanTask")
public void orderArchiveClean() {
    LocalDateTime cleanDate = LocalDateTime.now().minusDays(7);
    orderMapper.deleteArchivedBefore(cleanDate);
}
```

### 20.3 ES索引生命周期管理（ILM Policy）

```json
{
  "policy": {
    "phases": {
      "hot": {
        "min_age": "0ms",
        "actions": {
          "rollover": { "max_size": "50gb", "max_age": "7d" }
        }
      },
      "warm": {
        "min_age": "7d",
        "actions": {
          "shrink": { "number_of_shards": 1 },
          "forcemerge": { "max_num_segments": 1 }
        }
      },
      "cold": {
        "min_age": "30d",
        "actions": {
          "freeze": {}
        }
      },
      "delete": {
        "min_age": "90d",
        "actions": {
          "delete": {}
        }
      }
    }
  }
}
```

### 20.4 生产考量

> "数据生命周期全自动化：订单超过3个月自动归档到归档表，延迟7天后删除原表（防误删）。ES日志用ILM Policy自动管理：7天热索引→30天温索引→90天冷索引→自动删除。每批1万条分批迁移，避免长事务锁表。"
>
> **故障兜底**：归档任务失败 → 下次执行自动续传（按ID范围）；误删数据 → 7天延迟窗口内可从归档表恢复；ES ILM失败 → 手动触发索引管理。

---

## 21. JVM调优参数模板与GC日志分析

> 📖 **知识来源**：《深入理解Java虚拟机》第3-5章 + G1 GC官方调优指南
> - 核心观点："JVM参数不是'抄网上的'，而是根据应用特征和硬件配置科学计算"
> - my-xhs对照：G1 GC + 固定堆大小 + GC日志分析 + Prometheus JVM监控

### 21.1 生产JVM参数模板

```bash
# ============================================
# my-xhs 生产JVM参数模板（4C8G机器）
# ============================================

# 堆大小：固定4G，避免动态扩缩导致GC停顿
-Xms4g -Xmx4g

# G1收集器（JDK 17默认）
-XX:+UseG1GC

# 目标停顿200ms（根据SLA P99<500ms倒推，GC停顿不能超过200ms）
-XX:MaxGCPauseMillis=200

# Region大小8MB（堆4G / 2048个Region ≈ 2MB，设8MB减少Region数量）
-XX:G1HeapRegionSize=8m

# 45%堆占用时触发并发标记（默认45%，适合大多数场景）
-XX:InitiatingHeapOccupancyPercent=45

# 并行引用处理（加速Finalizer/WeakReference处理）
-XX:+ParallelRefProcEnabled

# 元空间：固定256MB（微服务类不多，256MB足够）
-XX:MetaspaceSize=256m -XX:MaxMetaspaceSize=256m

# GC日志（JDK 17统一日志框架）
-Xlog:gc*:file=/logs/gc.log:time,uptime,level,tags:filecount=10,filesize=100m

# OOM时自动dump堆（排查内存泄漏必备）
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/logs/heapdump.hprof

# 关闭偏向锁（JDK 15+已废弃，JDK 17默认关闭）
-XX:-UseBiasedLocking
```

### 21.2 不同服务的JVM参数差异

| 服务 | 堆大小 | 特殊参数 | 原因 |
|------|--------|----------|------|
| Gateway | 2G | -XX:MaxDirectMemorySize=512m | Netty使用堆外内存 |
| Order | 4G | 默认模板 | 订单对象较大 |
| Search | 4G | -XX:MaxGCPauseMillis=100 | 搜索对RT敏感 |
| Counter | 2G | -XX:G1HeapRegionSize=4m | 对象小但数量多 |
| IM | 2G | -XX:MaxDirectMemorySize=1g | WebSocket大量堆外内存 |

### 21.3 GC日志分析要点

| 指标 | 正常范围 | 异常判断 | 排查方向 |
|------|----------|----------|----------|
| Young GC频率 | < 5次/秒 | > 10次/秒 | 新生代太小，或对象创建过快 |
| Young GC停顿 | < 50ms | > 100ms | Region数量过多，或存活对象过多 |
| Full GC频率 | < 1次/天 | > 1次/小时 | 内存泄漏嫌疑，dump堆分析 |
| Mixed GC占比 | < 30% | > 50% | 老年代增长过快，检查大对象 |
| GC总停顿占比 | < 1% | > 5% | 整体GC压力过大，考虑加内存 |

### 21.4 生产考量

> "JVM参数不是抄网上的，每个参数都有计算依据。堆大小固定4G避免动态扩缩；G1目标停顿200ms是根据SLA P99<500ms倒推的；Region 8MB是根据堆大小计算的。GC日志必须开启，Young GC>10次/秒说明新生代太小，Full GC>1次/小时说明有内存泄漏。"
>
> **故障兜底**：OOM → 自动dump堆+告警；GC停顿过长 → Prometheus告警；内存泄漏 → MAT分析heapdump。

---

## 22. 连接池参数科学计算

> 📖 **知识来源**：HikariCP官方Wiki + Lettuce官方文档 + 《高性能MySQL》第11章
> - 核心观点："连接池参数不是'拍脑袋'，而是根据QPS、RT、线程数科学计算"
> - 公式来源：HikariCP作者推荐公式 — connections = ((core_count * 2) + effective_spindle_count)
> - my-xhs对照：按公式计算 + 压测验证 + 动态调整

### 22.1 MySQL连接池（HikariCP）

```yaml
# HikariCP参数计算（4核1磁盘机器）
spring:
  datasource:
    hikari:
      # 最小空闲连接 = CPU核数 = 4
      minimum-idle: 4
      
      # 最大连接数 = CPU核数 × 2 + 磁盘数 = 4 × 2 + 1 = 9
      # 实际取10（留1个余量）
      maximum-pool-size: 10
      
      # 连接超时 = 慢查询P99 × 2 = 200ms × 2 = 400ms
      # 实际取3000ms（包含网络延迟+排队等待）
      connection-timeout: 3000
      
      # 空闲超时 = 10分钟（避免MySQL wait_timeout 8小时断开）
      idle-timeout: 600000
      
      # 连接最大生命周期 = 30分钟（小于MySQL wait_timeout）
      max-lifetime: 1800000
      
      # 连接验证超时 = 5秒
      validation-timeout: 5000
      
      # 连接验证SQL
      connection-test-query: SELECT 1
```

### 22.2 Redis连接池（Lettuce）

```yaml
# Lettuce连接池参数计算
# 场景：2个服务实例，每实例200线程，3个Redis节点
spring:
  data:
    redis:
      lettuce:
        pool:
          # 最大连接数 = 服务实例线程数 / Redis节点数 × 1.5
          # = 200 / 3 × 1.5 ≈ 100
          max-active: 100
          
          # 最小空闲 = 最大连接数 × 0.1 = 10
          min-idle: 10
          
          # 最大空闲 = 最大连接数 × 0.5 = 50
          max-idle: 50
          
          # 获取连接超时 = Redis P99 RT × 10 = 1ms × 10 = 10ms
          # 实际取200ms（包含排队等待）
          max-wait: 200ms
      
      # 命令超时 = 3秒（包含网络延迟）
      timeout: 3000ms
```

### 22.3 RocketMQ连接参数

```yaml
# RocketMQ连接参数
rocketmq:
  producer:
    # Producer连接数 = 1（复用，RocketMQ Producer线程安全）
    # 发送超时 = 3秒
    send-message-timeout: 3000
    # 重试次数 = 2（总共3次）
    retry-times-when-send-failed: 2
    
  consumer:
    # 消费线程数 = CPU核数 × 2 = 8
    consume-thread-min: 8
    consume-thread-max: 8
    # 每次拉取消息数 = 32（默认值，适合大多数场景）
    pull-batch-size: 32
```

### 22.4 参数验证方法

```
压测验证连接池参数是否合理：

1. 观察HikariCP指标：
   - hikaricp_connections_active: 活跃连接数
   - hikaricp_connections_pending: 等待获取连接的线程数
   - 如果pending > 0持续出现 → maximum-pool-size太小

2. 观察Redis连接池指标：
   - lettuce_pool_active: 活跃连接数
   - lettuce_pool_idle: 空闲连接数
   - 如果active接近max-active → 连接池太小

3. 调优原则：
   - 连接池不是越大越好（MySQL连接数有上限，连接切换有开销）
   - 先按公式计算，再压测验证，最后微调
```

### 22.5 生产考量

> "连接池参数按公式计算：MySQL最大连接数=CPU核数×2+磁盘数=9，Redis最大连接数=线程数/节点数×1.5=100。不是拍脑袋，每个参数都有计算依据。压测时观察HikariCP的pending指标，如果持续>0说明连接池太小。"
>
> **故障兜底**：连接池耗尽 → 请求排队等待（connection-timeout控制最大等待时间）；MySQL连接断开 → HikariCP自动检测并重建；Redis连接超时 → Lettuce自动重连。

---

## 23. API版本兼容性矩阵

> 📖 **知识来源**：RESTful API设计最佳实践 + Stripe API版本管理
> - 核心观点："API版本不是'改了就改了'，需要兼容性矩阵管理，旧版本保留6个月"
> - my-xhs对照：Gateway Header版本路由 + 兼容性矩阵 + 废弃通知机制

### 23.1 版本路由机制

```
请求头：X-Api-Version: v2

Gateway路由规则：
  - X-Api-Version: v1 → 路由到v1处理器
  - X-Api-Version: v2 → 路由到v2处理器
  - 无版本头 → 路由到最新稳定版(v1)
```

### 23.2 兼容性矩阵

| 接口 | v1 | v2 | 变更说明 | 废弃时间 |
|------|----|----|---------|----------|
| POST /api/user/login | ✅ | ✅ | v2新增设备指纹字段 | — |
| POST /api/order/create | ✅ | ✅ | v2新增couponCode字段 | — |
| GET /api/note/{id} | ✅ | ✅ | v2返回值增加topicList | — |
| GET /api/user/info | ⚠️ 废弃 | ✅ | v2改用/api/user/profile | 2026-12-01 |
| POST /api/social/follow | ✅ | ✅ | 无变更 | — |

### 23.3 版本生命周期管理

```
版本策略：
  1. 新版本发布后，旧版本保留6个月
  2. 废弃版本返回Warning Header：
     Warning: 299 - "API v1 /api/user/info is deprecated, use v2 /api/user/profile"
  3. 废弃期结束后返回 410 Gone
  4. 紧急安全修复：所有版本同时修复

版本发布流程：
  1. 新增v2接口（不修改v1）
  2. 更新兼容性矩阵文档
  3. v1接口添加@Deprecated注解 + Warning Header
  4. 6个月后v1接口返回410 Gone
  5. 12个月后删除v1代码
```

### 23.4 生产考量

> "API版本通过Gateway Header路由，新版本发布不影响旧版本。废弃接口先返回Warning Header提醒客户端升级，6个月后返回410 Gone。兼容性矩阵文档记录每个接口的版本状态，避免'改了接口忘了通知'。"

---

## 24. Service Mesh预留设计

> 📖 **知识来源**：Istio官方文档 + 《云原生服务网格Istio》
> - 核心观点："Service Mesh是微服务的下一代架构，但不需要立即实现，只需要预留设计"
> - my-xhs对照：当前Spring Cloud全家桶 → 预留Sidecar演进路径

### 24.1 架构演进路径

```
Phase 1（当前）：Spring Cloud 全家桶
  ┌─────────┐     ┌─────────┐
  │ Service │────→│ Service │
  │    A    │     │    B    │
  │ (Feign) │     │         │
  └─────────┘     └─────────┘
  
  特点：服务发现/负载均衡/限流熔断 都在应用代码中
  优点：成熟稳定，Java生态完善
  缺点：基础设施逻辑侵入业务代码

Phase 2（演进）：Sidecar 模式
  ┌─────────┐     ┌─────────┐
  │ Service │     │ Service │
  │    A    │     │    B    │
  └────┬────┘     └────┬────┘
       │               │
  ┌────▼────┐     ┌────▼────┐
  │  Envoy  │────→│  Envoy  │
  │ Sidecar │     │ Sidecar │
  └─────────┘     └─────────┘
  
  特点：服务发现/负载均衡/限流熔断 下沉到Sidecar
  优点：多语言支持、基础设施与业务解耦
  缺点：运维复杂度增加、多一跳延迟
```

### 24.2 预留设计（当前就要做的）

| 预留点 | 当前实现 | 演进后 | 预留方式 |
|--------|---------|--------|----------|
| 服务间通信 | Feign Client | Envoy代理 | 通信抽象为接口，不直接依赖Feign |
| 限流规则 | Sentinel注解 | Istio限流策略 | 限流规则外部化到Nacos配置 |
| 健康检查 | /actuator/health | K8s Probe | 标准化健康检查端点 |
| 链路追踪 | SkyWalking Agent | Envoy + Jaeger | 标准化TraceId Header |
| 负载均衡 | Ribbon/LoadBalancer | Envoy | 不在代码中硬编码负载均衡策略 |

### 24.3 生产考量

> "当前用Spring Cloud全家桶，但预留了Service Mesh演进路径。服务间通信抽象为接口（不直接依赖Feign）、限流规则外部化到Nacos（不硬编码在代码中）、健康检查标准化（/actuator/health）。未来切换到Istio+Envoy时，业务代码改动最小。"

---

## 25. 事件溯源（Event Sourcing）在订单系统中的应用

> 📖 **知识来源**：《领域驱动设计》第10章 + Martin Fowler — Event Sourcing
> - 核心观点："传统方式只存最终状态，事件溯源存储所有状态变更事件。任何时间点的状态都可以通过重放事件得到"
> - my-xhs对照：t_order_snapshot表已有快照基础 → 增强为事件溯源

### 25.1 传统方式 vs 事件溯源

```
传统方式（华仔的做法）：
  t_order.status = 3（已支付）
  → 只知道当前状态，不知道"什么时候从什么状态变过来的"

事件溯源（my-xhs的做法）：
  t_order_event:
    | event_id | order_id | event_type     | event_data          | operator | created_at |
    | 1        | 1001     | ORDER_CREATED  | {amount:100,...}    | user     | 10:00:00   |
    | 2        | 1001     | ORDER_PAID     | {payNo:xxx,...}     | system   | 10:05:00   |
    | 3        | 1001     | ORDER_SHIPPED  | {trackNo:yyy,...}   | seller   | 11:00:00   |
    | 4        | 1001     | ORDER_RECEIVED | {receiveTime:...}   | user     | 3天后      |
  
  → 完整审计轨迹，任意时间点状态可回溯
```

### 25.2 事件表设计

```sql
-- 订单事件表（事件溯源）
CREATE TABLE t_order_event (
    id BIGINT PRIMARY KEY,
    order_id BIGINT NOT NULL COMMENT '订单ID',
    order_no VARCHAR(32) NOT NULL COMMENT '订单号',
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型:ORDER_CREATED/ORDER_PAID/ORDER_SHIPPED/...',
    event_data JSON NOT NULL COMMENT '事件数据（变更前后的差异）',
    before_status TINYINT COMMENT '变更前状态',
    after_status TINYINT NOT NULL COMMENT '变更后状态',
    operator_type TINYINT NOT NULL COMMENT '操作者类型:1用户2系统3卖家4客服',
    operator_id BIGINT COMMENT '操作者ID',
    event_version INT NOT NULL COMMENT '事件版本号（乐观锁）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_order_id (order_id),
    KEY idx_order_no (order_no),
    KEY idx_event_type (event_type),
    UNIQUE KEY uk_order_version (order_id, event_version)
) ENGINE=InnoDB COMMENT='订单事件表（事件溯源）';
```

### 25.3 事件溯源的价值

| 价值 | 说明 | 场景 |
|------|------|------|
| 完整审计轨迹 | 每次状态变更都有记录 | 金融合规、客诉排查 |
| 任意时间点回溯 | "这个订单10:03的状态是什么？" | 客服查询、问题排查 |
| 事件重放 | 重放所有事件可重建当前状态 | 数据修复、系统迁移 |
| CQRS基础 | 写入事件 → 异步投影到读模型 | 读写分离、ES同步 |
| 业务分析 | 分析状态流转路径 | "多少订单是创建后直接取消的？" |

### 25.4 与现有t_order_snapshot的关系

```
现有设计：
  t_order_snapshot — 存储订单在关键节点的完整快照
  → 快照是"某个时间点的完整状态"

增强设计：
  t_order_event — 存储每次状态变更的事件
  → 事件是"从A状态到B状态的变更记录"

两者互补：
  - 事件表：记录"发生了什么"（增量）
  - 快照表：记录"当时是什么样"（全量）
  - 事件重放 = 初始状态 + 所有事件 = 当前状态
  - 快照 = 某个时间点的状态（加速查询，不用从头重放）
```

### 25.5 生产考量

> "订单系统增加事件溯源——每次状态变更都记录到t_order_event表，包含事件类型、变更前后状态、操作者、事件数据。配合现有的t_order_snapshot快照表，实现完整的审计轨迹和任意时间点回溯。事件版本号用乐观锁保证并发安全。"
>
> **故障兜底**：事件写入失败 → 不影响主流程（事件记录是异步的）；事件与快照不一致 → 以快照为准（快照是同步写入的）；事件表数据量大 → 按月分表+定期归档。
