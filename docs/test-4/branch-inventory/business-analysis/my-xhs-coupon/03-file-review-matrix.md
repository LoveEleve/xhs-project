# my-xhs-coupon 文件级 Review 矩阵

## 数量

- 顶层：2
- `src/main/java`：24
- `src/main/resources`：5（3 配置 + 2 Lua）
- `src/test/java`：4
- 非 `target` 总数：35

## 状态定义

- `深度审查`：核心 Service/Consumer/Job/Lua 结合分支与异常分析
- `契约审查`：DTO/Entity/Mapper/校验器/配置/启动类
- `测试已执行`：已进入 Surefire
- `待运行确认`：需真实 Redis/MySQL/MQ/多实例

## 逐文件

| # | 文件 | 状态 |
|---|---|---|
| 1 | `Dockerfile` | 契约审查/待运行确认 |
| 2 | `pom.xml` | 契约审查 |
| 3 | `CouponApplication.java` | 契约审查 |
| 4 | `config/RedisScriptConfig.java` | 深度审查 |
| 5 | `consumer/CouponClaimConsumer.java` | 深度审查 |
| 6 | `consumer/CouponReturnRedisRepairConsumer.java` | 深度审查 |
| 7 | `controller/CouponController.java` | 深度审查 |
| 8 | `dto/request/ClaimCouponRequest.java` | 契约审查 |
| 9 | `dto/request/CreateTemplateRequest.java` | 契约审查 |
| 10 | `dto/request/ReturnCouponRequest.java` | 契约审查 |
| 11 | `dto/request/UseCouponRequest.java` | 契约审查 |
| 12 | `dto/response/CouponTemplateVO.java` | 契约审查 |
| 13 | `dto/response/UserCouponVO.java` | 契约审查 |
| 14 | `entity/CouponTemplate.java` | 契约审查 |
| 15 | `entity/UserCoupon.java` | 契约审查 |
| 16 | `job/CouponExpireJob.java` | 深度审查 |
| 17 | `job/CouponOutboxSenderJob.java` | 深度审查 |
| 18 | `job/CouponReconcileJob.java` | 深度审查 |
| 19 | `mapper/CouponOutboxMapper.java` | 契约审查 |
| 20 | `mapper/CouponTemplateMapper.java` | 契约审查 |
| 21 | `mapper/UserCouponMapper.java` | 契约审查 |
| 22 | `service/CouponService.java` | 深度审查 |
| 23 | `validator/AmountValidator.java` | 契约审查 |
| 24 | `validator/CouponValidator.java` | 契约审查 |
| 25 | `validator/ExpireValidator.java` | 契约审查 |
| 26 | `validator/StatusValidator.java` | 契约审查 |
| 27 | `resources/application.yml` | 契约审查/待运行确认 |
| 28 | `resources/application-datasource.properties` | 契约审查/待运行确认 |
| 29 | `resources/logback-spring.xml` | 契约审查/待运行确认 |
| 30 | `resources/lua/claim_coupon.lua` | 深度审查 |
| 31 | `resources/lua/return_coupon.lua` | 深度审查 |
| 32 | `test/service/CouponServiceTest.java` | 测试已执行（8 个通过） |
| 33 | `test/consumer/CouponClaimConsumerTest.java` | 测试已执行（1 个通过） |
| 34 | `test/consumer/CouponReturnRedisRepairConsumerTest.java` | 测试已执行（2 个通过） |
| 35 | `test/job/CouponReconcileJobTest.java` | 测试已执行（2 个通过） |

## 结论

- 35/35 已覆盖
- 核心业务逻辑已完成深度审查
- 本轮 coupon 13 个测试已真实执行并通过
- Lua、并发、对账、幽灵券等运行级行为待真实环境验证
- 本轮已修复：删除死代码 deleteByClaimNo；`case -3` 陈旧缓存初始化改为 DB 实时读 remain_count；coupon 13 个测试通过并重启 UP
- 仍待确认/待修：退券回补导致限领绕过/超发（业务待确认）、Outbox 无清理、对账双扣源、幽灵券、Outbox 锁租约等
