# my-xhs 项目系统性 Code Review 报告

> 审查时间：2026-06-02  
> 审查范围：全部 17 个模块、347 个 Java 源文件、12 个 Lua 脚本、docker-compose.yml 及相关配置  
> 审查基准：review-prompt-v2.md

---

## 维度 1：架构设计与模块划分 — 评分 8/10

### 优秀设计

1. **数据库按业务争用隔离拆分（4 实例）**  
   库存单独占用一个 MySQL 实例（mysql-inventory:13309），避免高写争用影响其他业务。订单独立实例支撑分片。用户/社交共享一个实例（读多写少）。这是成本与隔离度的合理平衡。

2. **通信模式选择恰当**  
   - 关键路径（库存预扣）用同步 Feign，保证下单原子性
   - 非关键路径（Feed推送/通知/购物车持久化）用异步 MQ，解耦提速
   - IM 跨实例用广播 MQ（简单直接，N 实例以 O(N) CPU 换取无路由表的实现简洁度）

3. **Feed 推拉混合策略**  
   粉丝<10万用推模式（实时性好）、>=10万用拉模式（避免写放大）。阈值虽硬编码但逻辑正确，是业界标准方案。

### Critical

无（架构层面无数据正确性/安全问题）

### Major

- **[M4] IM 广播 N 实例线性浪费**  
  位置：`my-xhs-im/src/main/java/com/myxhs/im/consumer/ImRouteConsumer.java`  
  10 个实例部署时，每条消息产生 10 次消费仅 1 次有效，9 次 CPU 浪费  
  建议：改为 Redis Pub/Sub 定向转发或 RocketMQ 消息过滤（消息 tag 为目标 serverId）

- **CosId 雪花 worker-id 冲突风险**  
  位置：`my-xhs-common` CosId 配置  
  `worker-id = (ip[2]*256+ip[3])%1024`，同子网多 Pod(如 K8s overlay 网络)可能碰撞  
  建议：使用 Redis/DB 分配 worker-id 或通过 Nacos 注册获取唯一编号

### Minor

- Counter 模块仅 1 个 Service + 1 个 Job + 1 个 Consumer，拆分为独立微服务略显过重
- Home(BFF) 模块承载 Feed + 5 种聚合 + 双线程池，建议后续考虑拆分 Feed 为独立服务

---

## 维度 2：公共模块设计（my-xhs-common） — 评分 9/10

### 优秀设计

1. **IdempotentAspect 异常分类策略**  
   位置：`my-xhs-common/.../aspect/IdempotentAspect.java`  
   - `BizException / IllegalArgumentException` → 删除幂等标记（业务未执行，允许重试）
   - `TimeoutException / SocketTimeoutException` → 保留标记（业务可能已执行，防重复）
   - 递归检查 cause 链，覆盖深层异常
   - Redis 不可用时降级放行（@Order(100) 在最外层）  
   这是非常成熟的幂等设计，超过大多数开源实现。

2. **CacheHelper 三重一致性保障**  
   位置：`my-xhs-common/.../cache/CacheHelper.java`  
   - Cache Aside + 延迟双删(500ms) + MQ 兜底(重试 16 次)
   - 防穿透(空值缓存 `\u0000__CACHE_NULL__\u0000`) + 防雪崩(TTL±1/6随机) + 防击穿(Redisson tryLock Singleflight)
   - Singleflight 失败时 sleep(100ms) 重试一次再降级

3. **AOP 切面统一降级策略矩阵**  
   - RateLimitAspect(@Order 10) → Redis 不可用放行
   - DistributedLockAspect(@Order 50) → 连接失败/超时放行
   - IdempotentAspect(@Order 100) → Redis 不可用放行
   - Token 黑名单(Gateway) → **拒绝**（安全优先）  
   业务切面降级放行、安全组件 Fail-Closed，策略分明。

### Minor

- **CacheHelper 延迟双删单线程 ScheduledExecutor**  
  位置：`CacheHelper.java` 中 `ScheduledExecutorService(1)`  
  高并发写场景下单线程可能积压（但 500ms 延迟任务执行极快，实际风险低）

- **号段 ID 生成器 DB 不可用时无降级**  
  位置：`SegmentIdGenerator.java` `loadSegmentFromDb()` L94  
  Buffer 耗尽 + DB 不可用 → 直接抛异常，无 fallback（可考虑临时降级到本地雪花）

---

## 维度 3：网关安全设计 — 评分 7.5/10

### 优秀设计

1. **JWT 鉴权 Fail-Closed 策略**  
   位置：`my-xhs-gateway/.../filter/GatewayAuthFilter.java`  
   Redis 黑名单不可用时拒绝请求而非放行，安全优先。白名单使用 AntPathMatcher 精确匹配。

2. **HMAC 时间恒定比较**  
   位置：`my-xhs-gateway/.../filter/HmacSignatureFilter.java`  
   使用 `MessageDigest.isEqual()` 防时序攻击，Nonce 通过 Redis SETNX 防重放。

3. **流量染色 AB 分组位运算**  
   位置：`my-xhs-gateway/.../filter/TrafficColoringFilter.java`  
   `(userId.hashCode() & 0x7FFFFFFF) % 3` 正确避免 hashCode 负数溢出。

### Critical

- **[C3] 压测标记 IP 校验缺陷**  
  位置：`my-xhs-gateway/.../filter/TrafficColoringFilter.java`  
  使用 `remoteAddress` 获取客户端 IP，经反向代理后拿到的是代理 IP  
  修复：优先从 `X-Forwarded-For` / `X-Real-IP` 获取真实 IP，并校验 X-Forwarded-For 链可信性

### Major

- **[M11] GrayRouteFilter `Math.abs()` 溢出**  
  位置：`my-xhs-gateway/.../filter/GrayRouteFilter.java` **第 67 行**
  ```java
  int hash = Math.abs(userId.hashCode()); // Integer.MIN_VALUE 时仍为负！
  ```
  `Math.abs(Integer.MIN_VALUE) == Integer.MIN_VALUE`，负数 `% 100 < 10` 恒成立，该用户永远命中灰度。  
  修复：
  ```java
  int hash = (userId.hashCode() & 0x7FFFFFFF) % 100;
  ```

### Minor

- HMAC 时间窗口 300s 过宽（标准推荐 60~120s）
- HMAC 不校验 Body 内容，仅校验 Header，body 篡改无法检测
- HMAC Redis 异常时 Fail-Open（放行），与 Auth 的 Fail-Closed 策略不一致

---

## 维度 4：数据一致性 — 评分 8/10

### 优秀设计

1. **库存三级扣减**  
   位置：`my-xhs-inventory/.../service/InventoryService.java` + `prededuct.lua`  
   - L1: Lua 原子预扣(userId%N 路由桶，不足遍历其他桶)，返回码语义清晰(1/0/-1/-2)
   - L2: MQ 异步扣 MySQL(乐观锁 `WHERE available >= qty`)
   - L3: 对账(每天3点)以 Redis 为权威  
   分桶分散热点 + 超时释放(release.lua HGET+HDEL 原子) + 与 confirm 互斥保证只有一方成功。

2. **订单事务消息 + 本地消息表**  
   位置：`my-xhs-order/.../service/OrderService.java`  
   - RocketMQ 事务半消息 → executeLocalTransaction(INSERT order+item+localMessage)
   - checkLocalTransaction 查 localMessage 状态：有=COMMIT / 无=ROLLBACK
   - 补偿：每 30s 扫描(60s保护窗口) + 3次死信 + Prometheus 指标  
   这是分布式事务的教科书实现。

3. **购物车 Lua 保证三结构原子**  
   位置：`my-xhs-cart/.../resources/lua/cart_add.lua`  
   Hash(商品) + Set(选中) + ZSet(排序) 在一个 Lua 脚本中原子操作，避免中间态。

### Critical

- **[C1] NoteService MQ 在事务提交前发送**  
  位置：`my-xhs-content/.../service/NoteService.java` **第 104 行**
  ```java
  @Transactional(rollbackFor = Exception.class)
  public Long publishNote(...) {
      noteMapper.insert(note);  // 第85行
      // ... afterCommit 注册清缓存 (正确)
      rocketMQTemplate.syncSend("FEED_TOPIC", event, 3000); // 第104行：仍在事务内！
  }
  ```
  **问题**：syncSend 在 @Transactional 方法内执行，此时事务未提交。若 MQ 发送成功但事务后续回滚，粉丝 Feed 会出现幽灵笔记。同时 syncSend 阻塞最多 3s 持有 DB 连接，RocketMQ 抖动时导致连接池耗尽。  
  **修复**：移到 `afterCommit()` 回调中：
  ```java
  TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
          cacheHelper.delayDoubleDelete(...);
          try {
              rocketMQTemplate.asyncSend("FEED_TOPIC", event, new SendCallback() {...});
          } catch (Exception e) {
              log.error("Feed推送失败", e);
          }
      }
  });
  ```

- **[C6] 优惠券唯一索引 vs perUserLimit 冲突**  
  位置：`my-xhs-coupon/.../consumer/CouponClaimConsumer.java`  
  DB 唯一索引 `uk_user_coupon(user_id, coupon_id)` 限制每个用户只能有 1 条记录，但 Lua 脚本中 `perUserLimit` 允许多次领取。Redis 已扣库存但 DB INSERT 因唯一索引报错，库存"蒸发"。  
  修复：改唯一索引为 `uk_user_coupon(user_id, template_id, coupon_no)` 或领券时生成唯一券号。

### Major

- **[M1] 购物车 Redis 三 Key 无 Hash Tag**  
  位置：`my-xhs-cart/.../service/CartService.java`  
  三个 Key：`cart:items:123` / `cart:checked:123` / `cart:sort:123`，无 `{userId}` 花括号包裹，迁移 Cluster 时 Lua 报 CROSSSLOT。  
  修复：Key 改为 `cart:{123}:items` / `cart:{123}:checked` / `cart:{123}:sort`

- **[M6] 关注 Lua 4 KEYS 跨 slot**  
  位置：`my-xhs-analytics/.../resources/lua/follow_and_count.lua`  
  KEYS[1-4] 涉及两个不同 userId（关注者和被关注者），Redis Cluster 必报 CROSSSLOT。  
  修复：拆分为两个独立操作（分别操作自己的 Key），通过 MQ 保证最终一致性。

- **[M14] Inventory prededuct.lua 内部动态拼接 Key**  
  位置：`my-xhs-inventory/.../resources/lua/prededuct.lua` 第 58-61 行  
  Lua 脚本声明 2 个 KEYS，但内部动态拼接了 `inventory:bucket:{skuId}:{bucketNo}` 并直接操作。  
  Cluster 模式下未通过 KEYS 参数传入的 Key 无法保证同 slot。  
  修复：所有被操作的 Key 必须通过 KEYS 参数传入，或使用 Hash Tag 统一 slot。

---

## 维度 5：高可用与容错 — 评分 7.5/10

### 优秀设计

1. **Feign 降级策略分级设计**  
   - 展示类（商品/用户/计数查询）→ 返回 null/默认值（优雅降级）
   - 交易类（库存预扣/优惠券核销）→ 抛 RemoteException（不允许静默降级）
   - 关单恢复类（释放库存/退券）→ 抛异常 → 兜底 Job 重试

2. **库存/点赞 MQ 失败回滚补偿**  
   Redis Lua 操作成功后 MQ 失败时，立即执行反向 Lua 脚本回滚，保证 Redis 状态正确。

### Major

- **[M5] 优雅停机不完整**  
  位置：`my-xhs-common/.../shutdown/GracefulShutdownListener.java`  
  `onApplicationEvent()` 方法**仅打印日志**，未执行任何实际清理：  
  1. 未通过 Nacos API 注销实例（30s 内新请求仍可路由过来）
  2. 未停止 MQ Consumer（处理中的消息可能被打断）
  3. 未关闭 WebSocket 连接（IM 模块客户端无感知）
  4. 未 flush Counter Buffer（丢失最多 5s/100 条数据）

- **[M3] Counter Buffer 崩溃丢数据**  
  位置：`my-xhs-counter/.../buffer/CounterBuffer.java`  
  JVM 被 kill -9 时 ConcurrentHashMap 中未刷盘数据全部丢失，对账间隔 24h。  
  建议：增加 @PreDestroy 刷盘 + 缩短对账间隔至 1h + WAL 预写日志。

- **[M7] 库存 L2 MySQL 乐观锁失败无重试**  
  位置：`my-xhs-inventory/.../consumer/InventoryDeductConsumer.java`  
  乐观锁 `UPDATE WHERE available >= qty` 失败后仅 log，等 24h 对账修复。  
  建议：失败后重试 3 次（退避策略），或重新入队延迟消费。

---

## 维度 6：性能 — 评分 7/10

### 优秀设计

1. **Counter Buffer 攒批合并**  
   10000 QPS INCR → 攒 5s/100条后 batch upsert → 实际 SQL 降低 80%+。双 Buffer 交换不阻塞写入。

2. **评论子评论批量 IN + 内存分组**  
   避免 N+1 查询，一次 IN 查所有子评论再按父评论 ID 分组。

3. **SSE 心跳使用 Pipeline 批量续期**  
   位置：`SseEmitterManager.java` 第 240-248 行  
   `executePipelined` 批量 SET，避免 N 次网络往返。

### Major

- **Feed 推送逐个粉丝 EVALSHA**  
  位置：`my-xhs-home/.../consumer/FeedPushConsumer.java`  
  10 万粉丝需 10 万次 Redis 往返（即使分批 500，仍是 200 次 Pipeline）。  
  建议：改为 Pipeline 内批量 ZADD（去掉裁剪逻辑，用定时 Job 裁剪）。

- **共同关注内存交集（各 5000 条）**  
  位置：`my-xhs-analytics/.../service/FollowService.java`  
  两个 ZRANGEBYSCORE 各取 5000 条到 Java 内存求交集，大 V 场景消耗大。  
  建议：使用 `ZINTERSTORE` 服务端计算或 Lua 脚本。

### Minor

- **[m11] CounterService.decrement() 每次 new RedisScript**  
  位置：`my-xhs-counter/.../service/CounterService.java`  
  每次 DECR 操作都 new DefaultRedisScript<>()，应提为 static final 常量。

- **[m12] SpuService 异步刷新用 ForkJoinPool(commonPool)**  
  位置：`my-xhs-product/.../service/SpuService.java` 第 477 行  
  `CompletableFuture.runAsync(() -> {...})` 使用共享 ForkJoinPool，高并发时可能耗尽线程。  
  建议：指定有界线程池。

- **[m13] IM Hash 环每次请求重建**  
  位置：`my-xhs-im/.../loadbalancer/ImConsistentHashLoadBalancer.java` 第 77 行  
  每次 `choose()` 重建 150×N 虚拟节点的 TreeMap，高频调用下开销显著。  
  建议：缓存 Hash 环，仅在实例列表变化时重建（对比 instanceList hash）。

- **Counter 对账逐条查 Redis（无 Pipeline）**  
  位置：`my-xhs-counter/.../service/CounterService.java` reconcile()  
  每条记录一次 GET，百万记录耗时极长。应改 Pipeline 批量查询。

---

## 维度 7：安全性 — 评分 6/10

### 优秀设计

1. **登录锁定机制**  
   位置：`my-xhs-user/.../service/UserService.java`  
   5 次失败/30min 锁定 + SecureRandom 验证码 + 排除易混淆字符 + 一次性消费。

2. **越权防护完整**  
   笔记/评论/订单/地址均有 userId 校验，X-User-Id 由 Gateway 注入不可篡改。

### Critical

- **[C4] docker-compose.yml 硬编码全部密码**  
  位置：`docker-compose.yml`  
  MySQL(`Xhs@2026#MySQL`)/Redis(`Xhs@2026#Redis`)/Grafana/Canal/XXL-Job 密码明文提交代码仓库。  
  修复：使用 `.env` 文件 + `.gitignore`，或 Docker Secrets / Vault。

- **[C5] 15 个 yml 硬编码密码 + JWT Secret**  
  位置：所有服务 `application.yml`  
  DB/Redis 密码 + JWT 签名密钥(`MyXhs@2026#JwtSecretKey!ForTokenSign`) + HMAC 密钥明文。  
  泄露 = 所有 Token 可伪造 + 数据库全部裸奔。  
  修复：使用环境变量 `${DB_PASSWORD}` + Nacos Config(加密) + Vault。

- **[C8] ES 未启用安全 (xpack.security=false)**  
  位置：`docker-compose.yml` ES 配置  
  19200 端口任何人可读写索引数据/删除索引。  
  修复：启用 xpack.security + 设置密码 + 限制网络访问。

### Major

- **[M13] 修改密码后未注销现有 Token**  
  位置：`my-xhs-user/.../service/UserService.java` `changePassword()` **第 263-282 行**  
  仅更新 DB 密码，未将旧 access token(30min) 和 refresh token(7d) 加入黑名单。  
  修复：changePassword 成功后，将当前 userId 的所有活跃 token 加入 Redis 黑名单。

- **[M19] IM ConversationId 长溢出**  
  位置：`my-xhs-im/.../service/ChatService.java` 第 398-402 行  
  ```java
  return (min << 32) | max; // userId > 2^31 时 long 溢出导致碰撞
  ```
  修复：使用字符串 `min + "_" + max` 或 128-bit UUID 生成。

### Minor

- NotePublishRequest content 无 @Size 限制（可传入超大内容）
- UpdateUserRequest gender 无 @Min/@Max 范围校验
- UserInfoResponse 手机号/邮箱未脱敏（地址模块做了，用户模块没做）
- WebSocket `allowed-origins: "*"` 允许任何域跨域连接
- 管理接口（对账/索引重建）无权限保护
- 全部 MySQL 使用 root 账号（违反最小权限原则）

---

## 维度 8：代码质量 — 评分 8/10

### 优秀设计

1. **设计模式运用到位**  
   - 策略模式：支付(Mock/Alipay/WeChat) + 文件存储(Local/OSS) + 推荐召回(5种)
   - 责任链：优惠券校验(3个Validator按@Order)
   - 状态机：笔记(枚举+canTransitTo) + 订单(枚举+乐观锁)
   - 观察者：Redis Pub/Sub(敏感词刷新/SSE跨实例)
   - 模板方法：AbstractSearchService

2. **分层清晰**  
   Controller 仅参数校验+路由、Service 业务逻辑、Mapper 数据访问。跨服务全走 Feign，无跨层调用。

3. **异常处理统一**  
   全局 Handler 覆盖 BizException/RemoteException/ValidationException/Exception，5xx 不暴露堆栈。

### Minor

- MQ 发送无统一封装（各服务重复构建 Message + 设置 keys/tags/timeout）
- SkuDTO vs SkuVO 命名不一致
- `wrapper.last("LIMIT " + pageSize)` 虽安全但不符最佳实践

---

## 维度 9：可观测性 — 评分 7.5/10

### 优秀设计

1. **Prometheus 4 组 18 条告警规则**  
   覆盖应用级(5xx/P99/JVM/GC/HikariCP) + 业务级(下单/支付/MQ积压) + 中间件(Redis/ES/Canal/RocketMQ) + 核心链路黄金信号。

2. **全链路 TraceId 传播**  
   TransmittableThreadLocal + Feign 拦截器 + MQ Header + MDC 包装线程池，跨服务完整串联。

3. **死信消息 Prometheus 指标上报**  
   订单本地消息表 retry>=3 后标为死信，自增 `deadLetterCount` Counter，可触发告警。

### Minor

- Alertmanager 未配置通知渠道（告警规则存在但无法发送通知）
- 缺少数据库连接耗尽告警
- 缺少死信队列积压告警
- 多 Redis 端口(16380/16381)配置存在但实际未使用/监控
- XXL-Job 线程是否被 TTL 包装未确认（可能丢失 TraceId）

---

## 维度 10：可测试性 — 评分 4/10

### 存在的基础

- POM 声明了 Testcontainers 依赖
- my-xhs-test 模块存在
- 代码结构支持单元测试（依赖注入、接口抽象）

### 缺失

- 核心算法无独立单测（DFA敏感词/号段ID/一致性Hash/分桶Lua）
- 无 API 端到端测试
- 无 Contract Test（Feign 接口一致性）
- 无性能基准测试（Counter Buffer吞吐/Feed推送延迟/Lua脚本性能）
- 无集成测试（MQ 消费/Canal 同步/Redis Lua）

### 建议

1. 优先为 Lua 脚本编写 Redis 嵌入式测试（使用 embedded-redis）
2. 为核心链路（下单/支付/库存）编写端到端集成测试（Testcontainers）
3. 为 Feign 接口编写 Consumer Contract Test（Spring Cloud Contract）

---

## 维度 11：运维与部署 — 评分 5.5/10

### 存在的优点

- docker-compose 涵盖全部基础设施，一键启动
- MySQL/Redis/ES/Broker/Canal 配置了健康检查
- Prometheus + Grafana + SkyWalking 全链路可观测

### Major

- **无 mem_limit/cpus 资源限制**  
  内存泄漏可拖垮宿主机

- **部分服务无日志限制**  
  磁盘撑满风险

- **全部中间件单点无 HA**  
  Nacos/RocketMQ/Redis/Canal 均为单实例部署

- **15 个服务硬编码 IP `21.91.124.110`**  
  应配置化（环境变量 / Nacos 配置中心 / Docker DNS）

### Minor

- 无 CI/CD 脚本
- SQL 版本管理缺 Flyway/Liquibase
- Canal 3 个 instance 共享一个 server 进程，故障隔离度低
- Sentinel/Nacos/XXL-Job/Grafana/SkyWalking 缺健康检查

---

## 维度 12：业务逻辑完备性 — 评分 7.5/10

### 优秀设计

1. **关单 vs 支付竞态处理**  
   两者都用乐观锁 `UPDATE SET status=x WHERE status=0`，保证只有一方成功。关单成功后支付回调失败的极端场景已实现自动退款。

2. **热搜反作弊机制**  
   IP 限频(10次/分钟) + 用户限频(同词300秒1次) + 指数衰减分数，有效防刷。

3. **通知 5 分钟聚合窗口**  
   大V单条动态引发的百万通知聚合为"XX等999+人点赞了你的笔记"，节省推送资源。

### Critical

- **[C7] Notification SSE 旧连接回调删新连接**  
  位置：`my-xhs-notification/.../sse/SseEmitterManager.java` 第 76-85 行  
  ```java
  SseEmitter oldEmitter = emitters.put(userId, emitter);  // 新emitter已放入
  if (oldEmitter != null) {
      oldEmitter.complete();  // 触发旧emitter的onCompletion
  }
  // onCompletion: emitters.remove(userId) ← 删掉了刚放入的新emitter！
  ```
  修复：onCompletion 中添加身份检查：
  ```java
  emitter.onCompletion(() -> {
      emitters.remove(userId, emitter); // ConcurrentHashMap.remove(key, value)
      // ...
  });
  ```

### Major

- **[M20] NotificationAggregator incrementAggregateCount 返回值误用**  
  返回 affected rows(0/1) 而非新 count 值，导致聚合标题显示 "1人点赞" 而非实际聚合人数。

- **收藏 ZSCORE + ZADD 非 Lua 原子**  
  并发可能重复发 MQ，导致多次 INSERT（唯一索引兜底不丢数据但浪费资源）。

### Minor

- 评论通知未排除自己评论自己的场景
- 离线消息 ZCARD + ZREMRANGEBYRANK 非原子（可能略超 1000 上限，影响极小）
- 关注计数 DECR 可能变负数（对账修复，但负数展示不友好）

---

## Top 25 优先改进项

按 **影响面 × 严重程度 × 修复成本** 综合排序：

| 排名 | ID | 问题 | 严重度 | 修复成本 | 影响面 |
|------|-----|------|--------|---------|--------|
| 1 | C5 | 15个yml硬编码密码+JWT Secret | Critical | 低 | 全系统安全 |
| 2 | C4 | docker-compose硬编码全部密码 | Critical | 低 | 基础设施安全 |
| 3 | C1 | NoteService MQ事务内发送 | Critical | 低 | Feed数据正确性 |
| 4 | C7 | SSE旧连接回调误删新连接 | Critical | 低 | 通知推送功能 |
| 5 | C6 | 优惠券唯一索引vs perUserLimit | Critical | 中 | 优惠券库存 |
| 6 | C8 | ES未启用安全 | Critical | 低 | 搜索数据安全 |
| 7 | M11 | GrayRouteFilter Math.abs溢出 | Major | 低 | 灰度发布正确性 |
| 8 | M13 | 修改密码后未注销Token | Major | 低 | 账户安全 |
| 9 | M5 | 优雅停机不完整 | Major | 中 | 数据丢失/可用性 |
| 10 | M1 | 购物车三Key无hashtag | Major | 低 | Cluster兼容性 |
| 11 | M6 | 关注Lua 4 KEYS跨slot | Major | 中 | Cluster兼容性 |
| 12 | M14 | Inventory Lua动态拼接Key | Major | 中 | Cluster兼容性 |
| 13 | M15 | Coupon Lua 2 KEYS跨slot | Major | 低 | Cluster兼容性 |
| 14 | C3 | 压测标记IP校验用remoteAddress | Critical | 低 | 压测隔离安全 |
| 15 | M3 | Counter Buffer崩溃丢数据 | Major | 中 | 计数准确性 |
| 16 | M7 | 库存L2失败无重试 | Major | 低 | 库存一致性 |
| 17 | M19 | IM ConversationId溢出 | Major | 低 | IM数据正确性 |
| 18 | M12 | PreDeductTimeoutJob遍历bucket字段 | Major | 低 | 库存回退正确性 |
| 19 | M20 | Notification聚合计数返回值误用 | Major | 低 | 通知展示 |
| 20 | m18 | 管理接口无权限保护 | Minor | 低 | 运维安全 |
| 21 | m13 | IM Hash环每次重建 | Minor | 低 | IM性能 |
| 22 | m11 | CounterService每次new RedisScript | Minor | 极低 | 内存/GC |
| 23 | m12 | SpuService用ForkJoinPool | Minor | 低 | 缓存刷新性能 |
| 24 | m23 | 关注计数DECR可能变负数 | Minor | 低 | 展示正确性 |
| 25 | m14 | NotePublishRequest content无@Size | Minor | 极低 | 存储/安全 |

---

## 综合评分总览

| 维度 | 评分 | 关键评语 |
|------|------|---------|
| 1. 架构设计 | 8/10 | 拆分合理，通信模式选择恰当，DB隔离策略正确 |
| 2. 公共模块 | 9/10 | 幂等/缓存/ID生成设计成熟，超过多数开源实现 |
| 3. 网关安全 | 7.5/10 | JWT+HMAC体系完整，但灰度hash溢出+策略不一致 |
| 4. 数据一致性 | 8/10 | 库存三级/事务消息/对账兜底优秀，MQ时序问题需修 |
| 5. 高可用容错 | 7.5/10 | 降级分级合理，停机逻辑空实现是最大短板 |
| 6. 性能 | 7/10 | Buffer攒批/批量IN优秀，Redis热Key/大Key需治理 |
| 7. 安全性 | 6/10 | 密码明文是致命伤，其余越权防护做得好 |
| 8. 代码质量 | 8/10 | 设计模式/分层/异常处理规范，少数代码气味 |
| 9. 可观测性 | 7.5/10 | 监控告警覆盖全面，缺通知渠道配置 |
| 10. 可测试性 | 4/10 | 几乎无自动化测试覆盖 |
| 11. 运维部署 | 5.5/10 | 单点/无资源限制/硬编码IP |
| 12. 业务逻辑 | 7.5/10 | 核心链路设计完善，边界场景有遗漏 |

**综合加权评分：7.1/10**

---

## 总结

### 项目整体评价

my-xhs 是一个**设计水准相当高**的全栈微服务学习项目，其核心亮点在于：

1. **数据一致性方案设计精良** — 事务消息+本地消息表、库存三级扣减+对账、CacheHelper三重保障，这些都是生产级实现
2. **公共模块抽象能力强** — IdempotentAspect 的异常分类策略、CacheHelper 的多层防护、AOP降级矩阵体现了深厚的架构功底
3. **业务理解深入** — Feed推拉混合、IM离线兜底、通知聚合窗口、热搜反作弊等都是经过深思的业务方案

### 最需要优先修复的 3 件事

1. **密钥外部化**（C4+C5）— 影响全系统安全，修复成本极低
2. **MQ 发送移到事务后**（C1）— 影响 Feed 数据正确性，改动 3 行代码
3. **SSE onCompletion 身份检查**（C7）— 影响通知功能，改动 1 行代码

### 走向生产前必须完成的工作

1. 所有密码/密钥外部化（环境变量 + Vault/KMS）
2. Redis Cluster 兼容性改造（6 个 Lua 脚本需要 Hash Tag 或拆分）
3. 补充核心链路自动化测试（至少下单/支付/库存全链路）
4. 中间件 HA 部署（至少 Redis Sentinel/Cluster + RocketMQ 多Master）
5. 优雅停机逻辑实现（Nacos 注销 + MQ 停消费 + Buffer 刷盘）
