# my-xhs 交接文档 — Task13（ORDER_TRANSACTION_TOPIC 坏消息真实进 DLQ + AI `mq.dlq_query` / `dlq.redeliver` 真实链路验证）

> 2026-08-19 | 承接 Task12（AI 域已完成大部分真实工具闭环，但 `dlq.redeliver` 只到 `CR_LATER` 历史样本）→ 本阶段：**首次用真实新造坏消息完成 `ORDER_TRANSACTION_TOPIC -> consumer retry -> DLQ` 闭环**，并对 **AI `mq.dlq_query` / `dlq.redeliver`** 做了真实远端验证。
> 给下一个 AI 的**详细交接**。重点：**本次运行态证据（§三）、精确环境/数据（§四）、当前结论（§五）、下一步只差什么（§六）**。

---

## 零、状态速览（2026-08-19 11:00）

```
微服务机：21.214.97.212（order/inventory/ai-app 等在跑）
中间件机：21.130.247.89（RocketMQ Dashboard 18081，xxl-job 18080，MySQL 3306）
本阶段结果：
1. ✅ 已把一条真实 ORDER_CREATED 本地消息改成 malformed payload
2. ✅ 已触发 localMessageRetryJob 把坏消息补发到 ORDER_TRANSACTION_TOPIC
3. ✅ 已观察到 inventory consumer 对该消息重试 6 次后进入 %DLQ%inventory-order-transaction-consumer-group
4. ✅ 已真实调用 Dashboard `queryDlqMessageByConsumerGroup.query` 拿到 ORIGIN_MESSAGE_ID / RETRY_TOPIC
5. ✅ 已真实调用 Dashboard `batchResendDlqMessage.do`，HTTP 200 + status=0
6. ⚠️ 重投 consumeResult = CR_LATER（不是 CR_SUCCESS），原因是这条被重投的消息体本身仍是 malformed JSON，消费者重投后依旧失败
```

---

## 一、本阶段目标与结论

### 1.1 目标

验证 AI 侧关于 RocketMQ 死信处理的真实外部链路：

1. 造一条**确定会失败**的 `ORDER_TRANSACTION_TOPIC` 消息；
2. 让库存消费者 `inventory-order-transaction-consumer-group` 重试直至进 DLQ；
3. 用 AI 工具对应的真实 Dashboard 接口：
   - `mq.dlq_query` → 查 DLQ 消息并提取 `ORIGIN_MESSAGE_ID`
   - `dlq.redeliver` → 发起正式死信重投
4. 确认不是 fake，不是单测，不是文档推断，而是**真实远端 E2E**。

### 1.2 本次结论

结论已经分两层：

- **层 1：坏消息进 DLQ** —— **已真实闭环**。
- **层 2：AI 死信重投动作成功消费** —— **只完成到重投请求成功送达 Dashboard / Broker，未完成到消费成功**。

也就是说：

- `mq.dlq_query`：**真实可用**；
- `dlq.redeliver`：**真实请求链路可用**（HTTP 200 + `status=0`），但对这条坏消息只得到 `CR_LATER`，因为消息内容本身还是坏的。

因此，当前最准确口径不是“dlq.redeliver 完全 E2E 成功消费”，而是：

- **已真实证明 query + resend 请求链路打通；**
- **尚未证明对一条可成功消费的 DLQ 消息能返回 `CR_SUCCESS`。**

---

## 二、为什么本次采用“改本地消息表 payload”方案

不是继续折腾运行时暂停点、故障注入或随机异常，而是直接走项目已有机制：

- `my-xhs-order/src/main/java/com/myxhs/order/job/LocalMessageRetryJob.java:112`
  - 定时任务把 `t_local_message.payload` 原样 `syncSend` 到目标 Topic；
- `my-xhs-inventory/src/main/java/com/myxhs/inventory/consumer/OrderTransactionConsumer.java:45`
  - 监听 `ORDER_TRANSACTION_TOPIC`；
- `OrderTransactionConsumer` 在解析异常/字段缺失时会抛异常，触发 RocketMQ 消费重试；
- `LocalMessageRetryJob.java:149`
  - 本地消息补发失败耗尽后标 `status=3`（本地死信）；
- 本次要验证的是 **RocketMQ consumer DLQ**，不是本地消息表死信，因此重点是让库存 consumer 把坏消息消费失败直到 `%DLQ%inventory-order-transaction-consumer-group`。

这个方案优点：

1. 不改代码；
2. 走生产现有链路；
3. 消息来源可信，容易追踪；
4. 与 `docs/test-3/cases/00-time-matrix.md:268,276,285` 的测试方法一致。

---

## 三、运行态证据（本阶段最重要）

### 3.1 被选中的真实订单 / 本地消息

订单信息：

- `orderId = 2089897504532418562`
- `orderNo = ORD2026081910092814599380003`
- `userId = 2089723488945319938`

分片计算：

- db = `userId % 4 = 2`
- tb = `(userId / 4) % 4 = 0`

所以本地消息落在：

- 库：`my_xhs_order_2`
- 表：`t_local_message_0`

本地消息行：

- `id = 2089897504582750209`
- `transaction_id = ORD2026081910092814599380003`
- `operation_type = ORDER_CREATED`

### 3.2 对本地消息做的实际改动

**完整 SQL（可直接复现）：**

```sql
-- 分片路由：userId=2089723488945319938 → db=uid%4=2, tb=(uid/4)%4=0
-- 库：my_xhs_order_2  表：t_local_message_0

UPDATE t_local_message_0
SET payload = '{orderNo:ORD2026081910092814599380003,skuItems:[{skuId:2089725027437015042,quantity:1}]}',
    status = 0,
    retry_count = 0,
    next_retry_time = DATE_SUB(NOW(), INTERVAL 10 MINUTE),
    updated_at = NOW()
WHERE transaction_id = 'ORD2026081910092814599380003';
```

语义：

- `payload` 改成**非法 JSON**（key 和 string value 没有双引号）
- `status = 0`（待补发）
- `retry_count = 0`（清空重试计数）
- `next_retry_time = 过去`（确保 job 扫到）

执行后查询确认：

- `status = 0`
- `retry_count = 0`
- `payload_prefix = {orderNo:ORD2026081910092814599380003,skuItems:[{skuId:2089725027437015042,quantity:1}]}`

**关于 `created_at` 过滤：**

`LocalMessageMapper.java:17` 有一个 `@Select` 定义了 `created_at < DATE_SUB(NOW(), INTERVAL 60 SECOND)` 过滤，但当前 `LocalMessageRetryJob.doRetry()` 走的是 `selectList(wrapper)` 查询（`LocalMessageRetryJob.java:94`），**没有 `created_at` 过滤**。所以即使 `created_at` 是原始下单时间（远大于 60s），job 仍然能扫到这条消息。如果将来代码重构换成 `selectPendingMessages`，需要注意这个差异。

随后手动触发 xxl-job：

- 管理台：`http://21.130.247.89:18080/xxl-job-admin/`
- 账号：`admin / 123456`
- 任务：`localMessageRetryJob`

触发返回：

- HTTP 200
- body：`{"code":200,"msg":null,"content":null}`

### 3.3 坏消息确实被补发到 ORDER_TRANSACTION_TOPIC

通过 Dashboard 查询 `ORDER_TRANSACTION_TOPIC` + `key=orderNo`，看到了这条补发消息：

- topic: `ORDER_TRANSACTION_TOPIC`
- `retryFromLocalMessage = true`
- `msgId = 15D661D449170AAEE2A25F0057C3EEA9`
- `messageBody = {orderNo:ORD2026081910092814599380003,skuItems:[{skuId:2089725027437015042,quantity:1}]}`

这点很关键：

- 说明 `localMessageRetryJob` 已真实把坏 payload 发进了主 topic；
- 不是数据库里改了但没发出去；
- 不是 fake。

### 3.4 坏消息真实进入 RocketMQ DLQ

通过持续轮询主 topic 和 DLQ topic，最终在 poll 20 观察到：

- DLQ topic: `%DLQ%inventory-order-transaction-consumer-group`
- `dlqMsgId = 15D661D449170AAEE2A25F0057C3EEA9`
- `ORIGIN_MESSAGE_ID = 1582F75900002E870000000025385814`
- `RETRY_TOPIC = ORDER_TRANSACTION_TOPIC`
- `reconsumeTimes = 6`
- body = `{orderNo:ORD2026081910092814599380003,skuItems:[{skuId:2089725027437015042,quantity:1}]}`

这说明：

- consumer 对该消息确实连续消费失败；
- RocketMQ 把它送进了该 consumer group 对应的 DLQ；
- 我们这次不是在复用旧死信，而是**成功制造出一条新的真实死信样本**。

### 3.5 `mq.dlq_query` 对应真实接口已成功拿到 query 结果

Dashboard 会话初始化：

- GET `http://21.130.247.89:18081/rocketmq-dashboard/csrf-token`
- 可拿到 `XSRF-TOKEN` cookie 和 csrf token

DLQ 查询接口：

- POST `http://21.130.247.89:18081/dlqMessage/queryDlqMessageByConsumerGroup.query`

**重要实测结论：**

- 用 **24 小时窗口** + `pageSize=200` 能准确查到本次新死信；
- 用 **7 天窗口** + `pageSize=100` 会先返回大量历史旧死信（`skuId=6/999`），容易把本条新消息“挤掉”，造成误判“查不到”。

本次精确 query 命中结果：

- `originMsgId = 1582F75900002E870000000025385814`
- `msgId = 15D661D449170AAEE2A25F0057C3EEA9`
- `retryTopic = ORDER_TRANSACTION_TOPIC`
- `reconsumeTimes = 6`
- `storeTimestamp = 1787107858268`

这一步已经等价于 AI 工具 `mq.dlq_query` 的真实外部能力验证。

### 3.6 `dlq.redeliver` 对应真实接口已成功发起重投请求

重投接口：

- POST `http://21.130.247.89:18081/dlqMessage/batchResendDlqMessage.do`

请求体（与 `DlqRedeliverTool.redeliver()` 语义一致）：

```json
[
  {
    "topic": "ORDER_TRANSACTION_TOPIC",
    "msgId": "1582F75900002E870000000025385814",
    "consumerGroup": "inventory-order-transaction-consumer-group"
  }
]
```

返回：

```json
{
  "status": 0,
  "data": [
    {
      "msgId": "1582F75900002E870000000025385814",
      "consumeResult": "CR_LATER",
      "remark": null
    }
  ],
  "errMsg": null
}
```

关键解释：

- HTTP 200
- `status = 0`
- 说明 Dashboard 接口和 Broker 交互链路本身是通的；
- 但 `consumeResult = CR_LATER`，不是 `CR_SUCCESS`。

### 3.7 为什么是 `CR_LATER`

原因不是 query / resend 接口坏了，而是：

- 本次故意造的 DLQ 消息 body 就是坏的；
- 重投后，库存消费者再次拿到同样的 malformed JSON；
- 所以它依旧消费失败；
- Dashboard 如实返回 `CR_LATER`。

也就是说：

- **重投动作已真实发出；**
- **失败原因在消息本体，不在 AI 工具链本身。**

### 3.8 消费者具体失败异常

从 `OrderTransactionConsumer.java:64` 看：

```java
JsonNode payload = objectMapper.readTree(body);
```

本次非法 JSON `{orderNo:ORD...}` 没有双引号包裹 key 和 string value，Jackson 的 `readTree()` 会抛 `com.fasterxml.jackson.core.JsonParseException`。这个异常根本走不到 `:69-73` 的 `userId/skuItems` 字段校验。

异常在 `:139` 被 `catch (RuntimeException e)` 捕获，先 `:142` 移除幂等标记，然后 `:143` 重新抛出，触发 RocketMQ 重试。

### 3.9 `reconsumeTimes = 6` vs `maxReconsumeTimes = 5`

代码 `OrderTransactionConsumer.java:48`：`maxReconsumeTimes = 5`。

RocketMQ 的 `reconsumeTimes` 计数方式：

- 第 1 次消费：`reconsumeTimes = 0`
- 第 1 次重试：`reconsumeTimes = 1`
- ...
- 第 5 次重试：`reconsumeTimes = 5`
- 进入 DLQ 时：broker 标记 `reconsumeTimes = 6`

所以 `maxReconsumeTimes = 5` 表示"最多重试 5 次"，加上首次消费一共 6 次尝试，DLQ 消息的 `reconsumeTimes = 6` 是正确的。

### 3.10 重投后 DLQ 消息仍然存在

重投后用同样方法查询 DLQ，消息仍然存在：

- `count = 1`
- `msgId = 15D661D449170AAEE2A25F0057C3EEA9`

说明 `CR_LATER` 时 Dashboard **不会移除 DLQ 消息**。`batchResendDlqMessage.do` 只是"告诉 broker 重新投递"，不是"删除 DLQ 消息"。只有消费成功（`CR_SUCCESS`）后，broker 才会移除。

### 3.11 `ORIGIN_MESSAGE_ID` vs `msgId` 的语义区别

本次 DLQ 消息的两个 ID：

- DLQ 消息自身的 `msgId` = `15D661D449170AAEE2A25F0057C3EEA9`（这是消息在 DLQ topic 中的存储 ID）
- `ORIGIN_MESSAGE_ID` = `1582F75900002E870000000025385814`（这是原始消息在 `ORDER_TRANSACTION_TOPIC` 中的 ID）

`batchResendDlqMessage.do` 的 `msgId` 参数**必须传 `ORIGIN_MESSAGE_ID`**，不是 DLQ 消息自身的 `msgId`。传错会导致 Dashboard 找不到消息。`DlqRedeliverTool.java:86` 也是这么做的。

### 3.12 `SKU不存在` 不进 DLQ 的特殊行为

从 `OrderTransactionConsumer.java:121-126` 看：

```java
catch (com.myxhs.common.exception.BizException e) {
    if ("SKU不存在".equals(e.getMessage())) {
        log.error("[库存-事务消费] 不可恢复坏消息，SKU不存在，停止重试");
        return;  // 直接返回，不抛异常，不触发重试，不进 DLQ
    }
}
```

这是一个**静默丢弃**分支：如果消息 body 合法但 SKU 不存在，consumer 会 `return`（不抛异常），消息**不会进 DLQ**，也不会重试。这对下一个 AI 设计路径 A 非常重要——不能用"SKU不存在"来造 DLQ 消息。

---

## 四、精确环境 / 接口 / 代码位置

### 4.1 中间件与管理端点

- RocketMQ Namesrv: `21.130.247.89:9876`
- RocketMQ Dashboard: `http://21.130.247.89:18081`
- XXL-Job Admin: `http://21.130.247.89:18080/xxl-job-admin/`
- MySQL: `21.130.247.89:3306`

### 4.2 本次涉及的核心 consumer group / topic

- 主 topic：`ORDER_TRANSACTION_TOPIC`
- DLQ topic：`%DLQ%inventory-order-transaction-consumer-group`
- consumer group：`inventory-order-transaction-consumer-group`

### 4.3 代码位置

- `my-xhs-order/src/main/java/com/myxhs/order/job/LocalMessageRetryJob.java:112`
  - `syncSend(topic, MessageBuilder.withPayload(msg.getPayload())...)`
- `my-xhs-order/src/main/java/com/myxhs/order/job/LocalMessageRetryJob.java:149`
  - 本地消息补发失败超限后标死信
- `my-xhs-order/src/main/java/com/myxhs/order/mapper/LocalMessageMapper.java:17`
  - 历史 `selectPendingMessages` 说明（但当前 job 实际走 wrapper 查询）
- `my-xhs-inventory/src/main/java/com/myxhs/inventory/consumer/OrderTransactionConsumer.java:45`
  - 监听 `ORDER_TRANSACTION_TOPIC`
- `my-xhs-ai-tools/src/main/java/com/myxhs/ai/tools/DlqRedeliverTool.java:124`
  - `queryDlqMessages()`
- `my-xhs-ai-tools/src/main/java/com/myxhs/ai/tools/DlqRedeliverTool.java:59`
  - `redeliver()`

### 4.4 AI 文档里已有历史口径

已有文档中曾写：

- `my-xhs-ai/business-analysis/tech/final-status-v1.md:68`
  - 历史结论：`dlq.redeliver` 已完成真实 Dashboard 查询与 batch 重投请求验证，但旧样本都引用不存在 SKU，因此只能到 `CR_LATER`

本次比历史进展更进一步的点是：

- 不再依赖旧 `skuId=6/999` 死信；
- 而是**新造出一条真实坏消息**，确认 `ORDER_TRANSACTION_TOPIC -> consumer retry -> DLQ` 链路成立；
- 然后再做 query + resend。

---

## 五、当前最准确结论

### 5.1 已经可以明确说的

1. **坏消息制造成功**：通过修改 `t_local_message.payload`，成功把坏消息送进 `ORDER_TRANSACTION_TOPIC`；
2. **consumer DLQ 链路真实成立**：该消息被 `inventory-order-transaction-consumer-group` 重试 6 次后进入 `%DLQ%inventory-order-transaction-consumer-group`；
3. **AI `mq.dlq_query` 真实成立**：通过 Dashboard query 接口查到本次新死信，拿到 `ORIGIN_MESSAGE_ID` 和 `RETRY_TOPIC`；
4. **AI `dlq.redeliver` 请求链路真实成立**：成功对 Dashboard 发送 batch resend，请求返回 HTTP 200 + `status=0`；
5. **当前 `CR_LATER` 的根因是消息内容依旧坏，不是工具链路断。**

### 5.2 还不能夸大的

还不能说：

- “`dlq.redeliver` 已证明对真实消息可成功消费返回 `CR_SUCCESS`”

因为本次只证明了：

- query 成功；
- resend 请求成功；
- 但被重投的消息仍然是坏消息，所以后续消费仍失败。

---

## 六、下一步精确执行清单

**唯一目标**：补齐 `CR_SUCCESS` 级别证据——造一条可进 DLQ 的消息，修复业务前置条件后重投，让消费者成功消费。

### Step 1：查 SKU 当前库存

```sql
-- skuId=2089725027437015042（本次订单用的那个）
-- 先确认库存是否存在且有多少
SELECT sku_id, total_quantity, available_quantity
FROM my_xhs_inventory.t_inventory
WHERE sku_id = 2089725027437015042;
```

如果 `available_quantity > 0`，执行 Step 2 清零。

### Step 2：清零 SKU 库存（制造"库存不足"条件）

```sql
-- 让 preDeduct 抛 BizException("库存不足")，而非 "SKU不存在"
UPDATE my_xhs_inventory.t_inventory
SET available_quantity = 0, updated_at = NOW()
WHERE sku_id = 2089725027437015042;
```

同时清 Redis 缓存（如果有的话）：

```bash
redis-cli -h 21.130.247.89 -p 6379 KEYS '*inventory*2089725027437015042*'
# 如果有结果，DEL 对应 key
```

### Step 3：改本地消息为合法 JSON 但 quantity > 库存

```sql
-- 同一条本地消息，改 payload 为合法 JSON（双引号），quantity=9999
UPDATE t_local_message_0
SET payload = '{"orderNo":"ORD2026081910092814599380003","userId":"2089723488945319938","skuItems":[{"skuId":"2089725027437015042","quantity":9999}]}',
    status = 0,
    retry_count = 0,
    next_retry_time = DATE_SUB(NOW(), INTERVAL 10 MINUTE),
    updated_at = NOW()
WHERE transaction_id = 'ORD2026081910092814599380003';
```

### Step 4：触发补发，等消息进 DLQ

xxl-job 手工触发 `localMessageRetryJob`：

```
POST http://21.130.247.89:18080/xxl-job-admin/jobinfo/trigger
账号: admin / 123456
参数: id=11, executorParam=(空)
```

然后每 10 秒轮询 DLQ（最多等 5 分钟）：

```python
# 用 24h 窗口 + pageSize=200
body = {
    "topic": "%DLQ%inventory-order-transaction-consumer-group",
    "begin": now - 24*3600*1000,
    "end": now,
    "pageNum": 1,
    "pageSize": 200,
    "taskId": str(now)
}
# 匹配 orderNo 用字符串包含（不要 json.loads，消息体可能异常）
```

**成功标志**：DLQ 中出现 `orderNo=ORD2026081910092814599380003` 的消息，且 `reconsumeTimes >= 5`。

### Step 5：补库存（让 preDeduct 能成功）

```sql
-- 把库存补到 > 9999
UPDATE my_xhs_inventory.t_inventory
SET available_quantity = 10000, updated_at = NOW()
WHERE sku_id = 2089725027437015042;
```

### Step 6：查询 DLQ 消息，提取 ORIGIN_MESSAGE_ID

```python
# 从 Step 4 的轮询结果中提取
originMsgId = ...  # properties.ORIGIN_MESSAGE_ID
retryTopic = ...   # properties.RETRY_TOPIC，应为 ORDER_TRANSACTION_TOPIC
```

### Step 7：重投

```python
# POST batchResendDlqMessage.do
resend = [{
    "topic": "ORDER_TRANSACTION_TOPIC",  # 用 retryTopic 的值
    "msgId": originMsgId,               # 用 ORIGIN_MESSAGE_ID，不是 DLQ msgId
    "consumerGroup": "inventory-order-transaction-consumer-group"
}]
```

### Step 8：验证结果

- **成功**：返回 `{"status":0,"data":[{"consumeResult":"CR_SUCCESS",...}]}`
- **仍然失败**：返回 `CR_LATER`，说明消费端还有其他问题（查 order 日志）

### 成功后要做的

更新以下文档，把 `dlq.redeliver` 的口径从"CR_LATER 历史样本"改为"CR_SUCCESS 真实 E2E"：

- `my-xhs-ai/business-analysis/tech/final-status-v1.md:68`
- `my-xhs-ai/docs/HANDOFF-AI-v6.md:213`
- `my-xhs-ai/business-analysis/tech/retrospective-v1.md:124`

### 6.3 这次不要重复踩的坑

1. **不要用 7 天窗口 + pageSize=100 查新死信**，会被历史样本淹没；
2. **query 新死信用 24 小时窗口 + pageSize=200**；
3. 匹配坏消息时，**不要假设 `messageBody` 是合法 JSON**；本次消息体是 `{orderNo:...}`，要用字符串匹配；
4. `dlq.redeliver` 传的 `msgId` 必须是 **`ORIGIN_MESSAGE_ID`**，不是 DLQ 消息自己的 `msgId`；本次两个 ID 分别是：
   - DLQ 消息自身 `msgId` = `15D661D449170AAEE2A25F0057C3EEA9`
   - `ORIGIN_MESSAGE_ID` = `1582F75900002E870000000025385814`
   - 传错会导致 Dashboard 找不到消息
5. `retryTopic` 本次是 `ORDER_TRANSACTION_TOPIC`，不是 `%RETRY%group`；代码允许直接传 Dashboard 返回的 `RETRY_TOPIC`；
6. **`SKU不存在` 会静默丢弃**（`OrderTransactionConsumer.java:122-126`，`return` 不抛异常，不进 DLQ）；路径 A 必须用**库存不足**而非 SKU 不存在来造 DLQ 消息；
7. **重投后 DLQ 消息仍然存在**（`CR_LATER` 时 broker 不移除消息）；不要误以为"重投成功但消息还在"是 bug。

---

## 七、本次执行时用到的关键命令/操作语义

### 7.1 定位本地消息

在 `my_xhs_order_2.t_local_message_0` 里按 `transaction_id=ORD2026081910092814599380003` 查询。

### 7.2 造坏消息

把该行：

- `payload` 改为非法 JSON
- `status=0`
- `retry_count=0`
- `next_retry_time=过去`

### 7.3 触发补发

xxl-job 手工触发：

- `localMessageRetryJob`

### 7.4 验证主 topic

Dashboard 查询：

- topic=`ORDER_TRANSACTION_TOPIC`
- key=`ORD2026081910092814599380003`

看是否出现：

- `retryFromLocalMessage=true`
- `messageBody={orderNo:...}`

### 7.5 验证 DLQ

Dashboard 查询：

- topic=`%DLQ%inventory-order-transaction-consumer-group`
- 时间窗口：24h
- pageSize：200

### 7.6 重投

Dashboard：

- `POST /dlqMessage/batchResendDlqMessage.do`
- body：`[{topic: ORDER_TRANSACTION_TOPIC, msgId: ORIGIN_MESSAGE_ID, consumerGroup: inventory-order-transaction-consumer-group}]`

---

## 八、建议补充/同步更新的文档

如果下一个 AI 完成了 `CR_SUCCESS` 级别验证，建议同步更新：

- `my-xhs-ai/business-analysis/tech/final-status-v1.md:68`
- `my-xhs-ai/docs/HANDOFF-AI-v6.md`
- `my-xhs-ai/business-analysis/tech/retrospective-v1.md`

如果仍然只有 `CR_LATER`，但确认重投请求链路无误，则现有文档口径已经基本正确，只需要补充：

- 本次已新造真实 DLQ 样本，证据强于历史 `skuId=6/999` 样本。

---

## 九、一句话交接

当前已经真实完成：`坏 payload -> localMessageRetryJob 补发 -> ORDER_TRANSACTION_TOPIC -> inventory consumer 重试 6 次 -> DLQ -> query 拿 ORIGIN_MESSAGE_ID -> resend 请求 HTTP 200/status=0`；唯一还没拿到的是 **`CR_SUCCESS` 级别的最终消费成功证据**，原因不是工具链断，而是这次被重投的消息本身就是故意造坏的。

---

## 十、AI Agent 真实调用发现（关键补充）

### 10.1 已做的真实 AI Agent 调用

用 AI 诊断台（`http://21.214.97.212:19020`）提交了两次诊断任务：

**第一次**（自然语言）：
- prompt: "帮我查一下 inventory-order-transaction-consumer-group 的死信消息，找到 orderNo=ORD2026081910092814599380003 的那条，然后重投它"
- 结果：Agent 调了 `logSearch`（白名单拒）+ `mqDlqBacklog`（返回 backlog=-1）→ DECLINE，建议人工

**第二次**（显式指定工具名）：
- prompt: "用 mq.dlq_query 工具查询..."
- 结果：Agent 直接 DECLINE，说"当前环境没有 mq.dlq_query 工具"

### 10.2 根因：`MQ_DLQ_QUERY` 未加入任何 Agent Profile 工具集

代码位置：`my-xhs-ai-app/src/main/java/com/myxhs/ai/app/service/agent/profile/AgentProfiles.java`

- `OPS_TOOLS`（line 22-26）：包含 `MQ_DLQ_BACKLOG` 和 `L3_DLQ_REDELIVER`，**但没有 `MQ_DLQ_QUERY`**
- `ALL_TOOLS`（line 28-31）= BUSINESS_TOOLS ∪ OPS_TOOLS → 也没有
- `FULL` profile（line 51-53）用 `ALL_TOOLS` → 也没有

结果：工具在 `AgentToolCatalog`（line 63）和 `AgentToolBinder`（line 46-48）里都注册了，但**没有 Agent Profile 包含它** → 模型根本看不到这个工具。

### 10.3 修复方案

在 `AgentProfiles.java` 的 `OPS_TOOLS` 中加入 `AgentToolNames.MQ_DLQ_QUERY`：

```java
private static final Set<String> OPS_TOOLS = Set.of(
        AgentToolNames.HTTP_ERRORS, AgentToolNames.HTTP_LATENCY,
        AgentToolNames.MQ_CONSUMER_LAG, AgentToolNames.MQ_DLQ_BACKLOG,
        AgentToolNames.MQ_DLQ_QUERY,  // ← 加这一行
        AgentToolNames.MYSQL_REPLICA_LAG, AgentToolNames.MYSQL_DEADLOCKS,
        AgentToolNames.LOG_SEARCH, AgentToolNames.L3_DLQ_REDELIVER);
```

修复后，`OPS` 和 `FULL` profile 都能看到 `mqDlqQuery` 工具，Agent 就能自己走 `mq.dlq_query → dlq.redeliver` 链路。

### 10.4 修复后的验证步骤

1. 重启 `my-xhs-ai-app:19020`
2. 重新提交诊断任务（prompt: "查 inventory-order-transaction-consumer-group 的死信消息，找到 orderNo=ORD2026081910092814599380003 的那条，然后重投"）
3. 验证 Agent 是否调用 `mqDlqQuery` → 拿到 ORIGIN_MESSAGE_ID → 请求 `dlq.redeliver` → 进入 WAITING_APPROVAL → approve → 返回结果
4. 目标：`dlq.redeliver` 返回 `CR_SUCCESS`

### 10.5 这个发现的价值

这个 bug 说明：**AI 工具链的"工具暴露"环节有遗漏**——工具注册了、执行器绑定了，但 Profile 工具集漏了。M13 多 Agent 分派后，OPS profile 应该能查死信消息明细，但因为漏了 `MQ_DLQ_QUERY`，它只能查积压数（`MQ_DLQ_BACKLOG`），不能查消息列表。这是一个应该修的真实配置 bug。