# review-1 上下文交接

> 更新时间：2026-08-18
> 目的：交给下一位 AI 继续执行，不重复探索，不覆盖现有未提交改动。

## 一、用户当前要求

用户要求：

1. 对 `my-xhs` 做深度 review，重点关注业务逻辑、工程问题、分布式问题、微服务问题。
2. 安全、凭据、网络、纯运维细节不作为主线修复对象，保留在附录即可。
3. review 完整后再规划修复；当前已经进入修复阶段。
4. 现在执行 `Batch 0 + Batch 1`，但不要修改 AI 团队代码。

## 二、项目位置

- 项目：`/data/workspace/my-xhs`
- Review 文档：`/data/workspace/my-xhs/docs/review-1`
- Review findings：`/data/workspace/my-xhs/docs/review-1/02-findings`
- 修复计划：`/data/workspace/my-xhs/docs/review-1/02-findings/REPAIR-PLAN.md`
- 最终主线清单：`/data/workspace/my-xhs/docs/review-1/02-findings/FINAL-VERIFIED-MAIN-BUGS.md`
- 运行态结果：`/data/workspace/my-xhs/docs/review-1/02-findings/RUNTIME-VERIFICATION-RESULTS.md`
- 测试缺口：`/data/workspace/my-xhs/docs/review-1/02-findings/TEST-COVERAGE-AUDIT-ROUND2.md`

## 三、Review 当前结论

### 已运行态确认

#### F-007：多 SKU 退款只回补一个 SKU

隔离测试订单：`9000000000000001001`

- user：`2088474205017374721`
- SKU：`990000001`、`990000002`
- 调用真实 `POST /api/order/refund-success`
- order 返回 HTTP 200
- inventory 日志显示 `inventory:refund:{orderId}` 订单级幂等键导致 SKU 回补跳过
- 测试订单、明细、mapping、库存、outbox、Redis 测试键已清理

#### F-010：退款成功回调无条件成功

隔离测试订单：`9000000000000002001`

- 订单 status=0（待付款）
- 写入 mapping
- 调用真实 refund-success
- 返回 HTTP 200、`success=true`
- 订单状态仍为 0
- 测试订单和 mapping 已清理

#### F-016：无库存仍可生成用户券

隔离模板：`9000000000000003001`

- MySQL `remain_count=0`
- Redis stock 临时设为 1
- 真实领券接口返回成功
- MQ 消费后 `t_user_coupon` 成功插入
- 模板 remain_count 仍为 0
- 模板、用户券、Redis key 已清理

#### F-039：BFF 把下游不可用伪装为业务 404

- 暂停 product 服务 PID `3746491`
- 调用 `http://127.0.0.1:19015/api/home/product/1`
- 外层 HTTP=200
- body 业务 code=404，message=商品不存在
- product 已通过 `restart-service.sh product` 恢复

#### F-009：退款补偿重复处理

`my-xhs-payment.log` 已观察到退款补偿任务连续多个 3 分钟周期都处理 5 条记录：12:06、12:09、12:12、12:15、12:18、12:21。

## 四、当前已经修改的业务代码

本轮 Batch 1 已开始实施，修改内容如下：

### 1. F-007

文件：

`my-xhs-inventory/src/main/java/com/myxhs/inventory/service/InventoryService.java`

已将退款回补幂等键从：

```text
inventory:refund:{orderId}
```

改为：

```text
inventory:refund:{orderId}:{skuId}
```

注意：后续还要继续检查 Outbox/consumer 的 action 粒度，不能只停在 Redis key。

### 2. F-010

文件：

- `my-xhs-order/src/main/java/com/myxhs/order/service/OrderService.java`
- `my-xhs-order/src/main/java/com/myxhs/order/controller/OrderController.java`

已做：

- `onRefundSuccess(Long orderId)` 从 `void` 改为 `boolean`
- mapping 缺失返回 false
- order 不存在返回 false
- status=5（已退款）作为幂等成功返回 true
- status 不是 1 且不是 5 返回 false
- 正常处理完成返回 true
- controller 根据 boolean 返回成功或 `R.fail(ORDER_STATUS_ERROR, "退款状态未收敛")`

注意：这是签名变更，必须继续检查所有调用方/测试。

### 3. F-016

文件：

`my-xhs-coupon/src/main/java/com/myxhs/coupon/consumer/CouponClaimConsumer.java`

已做：

```java
int affected = templateMapper.decrementRemainCount(event.templateId());
if (affected != 1) {
    throw new IllegalStateException(...);
}
```

这样库存扣减失败会触发事务回滚和 MQ 重试。

## 五、当前测试阻塞

执行过：

```bash
mvn -q -pl my-xhs-order -am test \
  -Dtest=OrderControllerTest,OrderServiceTest,OrderTransactionServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

当前失败分两类：

### A. 测试签名已过时，已部分适配

已适配：

- `OrderServiceTest` 增加 ProductFeignClient/UserFeignClient mock 和构造参数
- `OrderTransactionServiceTest` 多处调用增加 `addressSnapshot` 和 `skuMap`
- `OrderControllerTest.notifyRefundSuccess()` 从 `doNothing()` 改为 `thenReturn(true)`
- `OrderServiceTest.onRefundSuccess_*` 改为断言 boolean

### B. 仍然失败的现有测试基线

最近一次测试结果：

- Tests run: 51
- Failures: 10
- Errors: 1

主要失败：

1. `OrderControllerTest` 多个内部回调测试没有设置 `X-Internal-Call: test-token`，因此得到 403；这是测试本身已落后于 controller 鉴权约定。
2. `OrderControllerTest.getOrderByOrderNo_*` 等测试没有设置必需的 `X-User-Id` header，得到 400。
3. `OrderServiceTest.getOrderByOrderNo_success` mock 的 mapping userId 与请求 userId 不一致，触发真实归属校验失败。
4. `OrderServiceTest.createOrder_transactionMessageFailed` 没有 mock 新增的 product Feign 返回值，先在 SKU 校验处得到 NPE，未走到预期的事务消息失败断言。
5. `OrderServiceTest` 日志中还出现 `ObjectMapper` 未注册 JavaTimeModule 导致 snapshot 序列化异常；测试允许该异常被业务吞掉，但应明确是否要补测试 ObjectMapper 配置。

不要直接忽略这些失败；先修测试基线，再判断业务测试结果。

## 六、下一步明确操作

### Step 1：修测试基线

只修改测试，不改变生产语义：

1. `OrderControllerTest` 所有内部回调请求补：

```java
.header("X-Internal-Call", "test-token")
```

2. 需要用户身份的请求补：

```java
.header("X-User-Id", USER_ID)
```

3. `getOrderByOrderNo_success` 的 mapping userId 设置为 `USER_ID`。
4. `createOrder_transactionMessageFailed` mock `productFeignClient` 返回有效 SKU 数据，确保测试真正走到事务消息发送失败分支。
5. 重新运行 order 相关测试，先让测试能编译、再处理断言失败。

### Step 2：补 Batch 1 测试

必须新增/改造：

1. 多 SKU refundRestore：验证每个 SKU 都调用且库存回补 key 独立。
2. refund-success：
   - mapping 缺失 → 非成功
   - order 不存在 → 非成功
   - status=0 → 非成功
   - status=5 → 幂等成功
   - status=1 → 成功
3. CouponClaimConsumer：`decrementRemainCount()` 返回 0 时必须抛异常且不提交用户券。
4. F-039 BFF：product/user/cart Feign 失败语义测试。

### Step 3：编译/测试命令

先执行模块测试：

```bash
mvn -q -pl my-xhs-order -am test \
  -Dtest=OrderControllerTest,OrderServiceTest,OrderTransactionServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

然后：

```bash
mvn -q -pl my-xhs-coupon -am test \
  -Dtest=CouponServiceTest,CouponClaimConsumerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

```bash
mvn -q -pl my-xhs-inventory -am test \
  -Dtest=InventoryServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

最后按项目实际脚本运行 lint/typecheck/build 验证；这是 Java Maven 项目，至少执行相关模块 compile/package。

## 七、重要边界

1. 不要修改 `my-xhs-ai-app`、`my-xhs-ai-tools`、`my-xhs-ai-mcp` 的业务代码；AI 团队负责。
2. 不要回滚其他未提交改动；工作区本来就有大量修改。
3. 不要运行 `git reset --hard` 或批量 checkout。
4. 不要提交 commit，除非用户明确要求。
5. 不要执行停 MQ、断 Redis、删生产数据等破坏性操作；隔离测试数据必须执行后清理。
6. Batch 1 完成并通过测试后再进入 Batch 2，不要跨批次大范围修改。

## 八、当前关键文件

### Review

- `docs/review-1/02-findings/FINAL-REVIEW-CONCLUSION.md`
- `docs/review-1/02-findings/FINAL-VERIFIED-MAIN-BUGS.md`
- `docs/review-1/02-findings/REPAIR-PLAN.md`
- `docs/review-1/02-findings/RUNTIME-VERIFICATION-RESULTS.md`
- `docs/review-1/02-findings/TEST-COVERAGE-AUDIT-ROUND2.md`
- `docs/review-1/02-findings/CONSOLIDATION.md`

### Batch 1 代码

- `my-xhs-inventory/src/main/java/com/myxhs/inventory/service/InventoryService.java`
- `my-xhs-order/src/main/java/com/myxhs/order/service/OrderService.java`
- `my-xhs-order/src/main/java/com/myxhs/order/controller/OrderController.java`
- `my-xhs-coupon/src/main/java/com/myxhs/coupon/consumer/CouponClaimConsumer.java`

### Batch 1 测试

- `my-xhs-order/src/test/java/com/myxhs/order/controller/OrderControllerTest.java`
- `my-xhs-order/src/test/java/com/myxhs/order/service/OrderServiceTest.java`
- `my-xhs-order/src/test/java/com/myxhs/order/service/OrderTransactionServiceTest.java`

## 九、当前一句话状态

Review 已完成并收口；Batch 1 修复代码已开始写入，但测试基线尚未修平，下一位 AI 应先修测试签名/请求头/mock，再继续 Batch 1 验证。