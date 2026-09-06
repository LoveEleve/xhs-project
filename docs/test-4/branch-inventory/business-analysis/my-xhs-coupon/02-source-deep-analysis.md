# my-xhs-coupon 源码深度分析

## 1. 领券链路

```text
claim → 模板缓存校验(status/时间/领取窗口) → claim_coupon.lua
   -> Redis 库存 DECR + claimed INCR（同 {templateId} slot 原子）
   -> 生成 claimNo → Outbox insert → MQ syncSend
   -> 失败 rollbackRedisStock + 抛异常
消费者 CouponClaimConsumer:
   -> msgId 幂等(24h) → 校验 claimNo 唯一键 → insert t_user_coupon
   -> decrementRemainCount(WHERE remain>0) → 失败回滚重试→DLQ
```

Lua 原子性正确，`{templateId}` hash tag 保证同 slot；单脚本完成库存检查和扣减，避免超卖。消费端用消息 ID + 数据库唯一键双层防重。

## 2. 用券与退券

- 用券：`useCoupon` 校验归属、状态、有效期、金额门槛，责任链计算折扣，`markUsed` 用 `WHERE status=0` 乐观锁防并发重复核销。
- 退券：`returnCoupon` 先把 status 从已用回退为未用（幂等），再 `remain+1`，提交后执行 `return_coupon.lua` 把 Redis 库存和 claimed 计数回补。

问题：`useCoupon` 不扣 remain，退券却回补 remain 与 Redis claimed。同一张券核销后退回领取池，导致：
- `remain + 已发 > total` 超发敞口
- 用户可“领→用→退→再领”循环，绕过 perUserLimit

代码注释表明这是当前设计意图，但和 total_count/限领语义内在矛盾，需产品确认。

## 3. Redis 与 MySQL 双扣源

稳态下 MySQL `remain_count` 与 Redis 库存一致，但存在两个不一致窗口：
- 消费者异步扣减（消息延迟时）与对账“以 Redis 覆盖 MySQL”冲突
- 领券中心按 MySQL 展示，可能短暂显示提前售罄

`case -3` 原用 30 分钟陈旧缓存模板的 remainCount 初始化 Redis 库存，Redis 丢失且期间已领大量时，会按旧值重置库存，产生瞬时超发敞口；本轮已修复为从 DB 实时读 remain_count 初始化。

## 4. 幽灵券

`syncSend` 超时抛错时，Redis 已回滚、接口提示失败，但 broker 可能实际已投递，或 Outbox 5 秒后补发，消费者写库成功 → 用户实际持有券却被告知失败。路径已确认存在，出现率需运行验证。

## 5. 任务与补偿

- `CouponExpireJob`：派生表分批标记过期；模板被逻辑删除后其用户券不被过期任务处理（低）
- `CouponReconcileJob`：以 Redis 为准修复 MySQL 库存
- `CouponOutboxSenderJob`：锁无续期，200 条 × 3s 可能超出 4s 锁租约，多实例并发补发；消费者按 claimNo 去重兜底，不重复入账
- `CouponReturnRedisRepairConsumer`：退券补偿消息兜底

## 6. 性能与工程

- `getUserCoupons` / `getAvailableCoupons` 无分页，全量 selectList
- `returnCoupon` 在 afterCommit 内同步执行 Lua + 失败再同步发 MQ，阻塞提交线程
- 领券单次链路约 2ms，可接受
- Outbox 只置位不清理，表无限增长，无清理任务
- 配置：DB/Redis 密码、jwt secret 明文；redis sentinel business/cache 仅 port 无 host（疑残留）；readwrite 重复定义；JSON_FILE 写 `/logs`；xxl executor port 9995 同机冲突风险

## 7. 鉴权基础检查

- 管理接口：`X-Admin-Call` fail-closed，未配 token 拒绝启动
- 内部接口：`X-Internal-Call` + Feign 拦截器自动注入
- 用户接口：信任 `X-User-Id`，依赖网关 `GatewayAuthTrustFilter` 覆盖/剥离伪造
- 若绕过网关直连 19010 可伪造 userId 领券，内网信任边界

## 8. 分类问题

### 代码 Bug
- 退券回补导致限领绕过和超发（业务待确认）
- `case -3` 曾用陈旧缓存初始化 Redis 库存，已修复为读 DB 实时 remain_count

### 已修复（本轮）
- `CouponOutboxMapper.deleteByClaimNo` 死代码已删除，同步移除测试 verify
- `initStockFromDb` 改为按 templateId 读 DB 实时 `remain_count`，避免模板缓存旧值导致瞬时超发敞口
- coupon 13 个测试全部通过；coupon 已重新打包并重启，19010 health 为 UP

### 业务逻辑
- `remain + 已发 > total` 超发语义
- 领券成功但告知失败（幽灵券）
- 模板逻辑删除后过期任务不处理其用户券

### 分布式
- Redis 扣减与 Outbox 写非原子，JVM 崩溃窗口丢券
- 对账与异步消费双扣源冲突
- Outbox Job 锁无续期，多实例并发补发

### 微服务
- Order 用券/退券 Feign 依赖和超时
- 直连 19010 可伪造 userId

### 性能
- 用户券列表无分页
- 退券提交后同步 Lua/MQ

### 工程
- Outbox 无清理
- 明文凭据、残留配置、executor 端口冲突

### 可观测性
- 幽灵券、超卖、对账竞态、Outbox 积压缺少指标
- 无埋点证据表明券成功率、扣减失败率、重试/DLQ 可观测

## 9. 测试状态

- 本轮已从仓库根目录执行 `mvn -pl my-xhs-coupon -am test`，coupon 13 个测试全部通过，common 53 个通过
- 覆盖：CouponServiceTest 8 个、CouponClaimConsumerTest 1 个、CouponReturnRedisRepairConsumerTest 2 个、CouponReconcileJobTest 2 个
- 测试缺口：useCoupon、discount、ExpireJob、OutboxSender、validators、Lua 本身、claim -3 重试、失败回滚
- 运行级验证（并发超卖、幽灵券、对账竞态、Outbox 积压）全部待真实环境确认
- 构造器与 mock 静态核查一致，编译通过
