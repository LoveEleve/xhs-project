# my-xhs-payment 文件覆盖对账

## 文件基线（非 target）

| 文件 | 类型 | 状态 |
|---|---|---|
| pom.xml | 顶层 | 已覆盖（依赖审计） |
| Dockerfile | 顶层 | 已确认（92B 简单镜像） |
| docs/CODE-REVIEW.md | 顶层 | 已读（历史评审，事实优先级最低） |
| PaymentApplication.java | 启动类 | 已读 |
| controller/PaymentController.java | 控制器 | 已读 |
| service/PaymentService.java | 核心服务 | 已读（1177 行全量） |
| consumer/PayResultConsumer.java | 消费者 | 已读（预留空实现，默认关闭） |
| consumer/RefundResultConsumer.java | 消费者 | 已读（预留空实现，默认关闭） |
| strategy/PayChannelStrategy.java | 策略接口 | 已读 |
| strategy/impl/MockPayStrategy.java | 策略 | 已读 |
| strategy/impl/AlipayPayStrategy.java | 策略 | 已读 |
| strategy/impl/WechatPayStrategy.java | 策略 | 已读 |
| config/PaymentConfig.java | 配置 | **已删除**（死代码：T-062 后无用的 Lua 脚本） |
| config/PayStrategyConfig.java | 配置 | 已读 |
| config/PaymentDataSourceConfig.java | 数据源 | 已读 |
| job/PaymentNotifyCompensateJob.java | Job | 已读 + 修复 |
| job/PaymentTimeoutCheckJob.java | Job | 已读 |
| job/RefundNotifyCompensateJob.java | Job | 已读 + 修复 |
| job/RefundTimeoutCheckJob.java | Job | 已读 |
| feign/OrderFeignClient.java | Feign | 已读 |
| feign/OrderFeignFallbackFactory.java | 降级 | 已读 |
| feign/InternalCallFeignConfig.java | Feign 拦截器 | 已读 |
| mapper/PaymentEventMapper.java | Mapper | 已读 |
| entity/Payment.java | 实体 | 已读 |
| entity/Refund.java | 实体 | 已读 |
| entity/PaymentEvent.java | 实体 | 已读 |
| dto/request/PayCreateRequest.java | DTO | 已读 |
| dto/request/RefundRequest.java | DTO | 已读 |
| dto/response/PaymentVO.java | DTO | 已读 |
| simulator/PayCallbackSimulator.java | 回调模拟器 | 已读 |
| resources/application.yml | 配置 | 已读 |
| resources/application-datasource.properties | 配置 | **残留未加载**（模板遗留，未在 application.yml import，Spring Boot 不自动加载该文件名） |
| resources/logback-spring.xml | 日志 | 统一模板（与其他模块一致，ELK JSON 采集），本轮未逐行重读 |
| resources/mapper/PaymentMapper.xml | Mapper XML | 已读（未引用实体表，实际手写 SQL） |
| resources/mapper/RefundMapper.xml | Mapper XML | 已读（同上） |
| test/service/PaymentServiceTest.java | 测试 | 已读 + 修复 |

## 覆盖结论

- 主源码 25 个 java 全部覆盖（含删除 1 个）
- 资源 6 个覆盖 5 个；`application-datasource.properties` 明确暂不深究（残留）
- `logback-spring.xml` 明确为统一模板不逐行重读
- docs/CODE-REVIEW.md 为历史评审，按事实优先级仅参考

## 测试验证

- `mvn -pl my-xhs-payment -am test`：53(common) + 5(payment) 全部通过
- 修复后服务已重启：19012 UP，executor 9992 重新注册 xxl-job
