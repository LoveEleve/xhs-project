# 分布式事务

> 跨服务专题 | 开发阶段：Phase-5 | 状态：⏳ 待开发

---

## 🎯 一、问题场景

### 1.1 为什么需要分布式事务？

```
下单链路涉及 4 个服务：
Order(创建订单) → Inventory(扣库存) → Coupon(扣券) → Payment(创支付单)

任何一步失败都需要回滚前面的操作，否则：
- 扣了库存但没创建订单 → 库存凭空减少
- 创了订单但没扣库存 → 超卖
```

### 1.2 方案选型

| 方案 | 一致性 | 性能 | 复杂度 | my-xhs 选用 |
|------|--------|------|--------|-----------|
| 2PC/XA | 强一致 | 低（全局锁） | 中 | ❌ 性能差 |
| TCC | 强一致 | 中（3个接口） | 高 | ❌ 侵入大 |
| RocketMQ 事务消息 | 最终一致 | 高（异步） | 中 | ✅ 主方案 |
| 本地消息表 | 最终一致 | 高 | 中 | ✅ 兜底方案 |

**选择理由**：下单链路对实时性要求高，事务消息异步解耦性能最好。本地消息表作为 MQ 不可用时的兜底。

---

## 🏗️ 二、事务消息 + 本地消息表双保险

### 2.1 事务消息 6 步流程

```
1. OrderService → RocketMQ: 发送半消息(Half Message)
2. RocketMQ: 存储半消息 → 返回确认
3. OrderService: 执行本地事务（同一DB事务）：
   a. INSERT t_order + t_order_item
   b. INSERT t_local_message（status=待发送）
4a. 本地事务成功 → 提交半消息(Commit) → 消费者可见
4b. 本地事务失败 → 回滚半消息(Rollback) → 消息被删除
5. 消费者消费：Inventory扣库存 / Coupon扣券
6. Broker 未收到 Commit/Rollback → 回查本地事务状态
```

### 2.2 本地消息表兜底

```
场景：MQ Broker 整体宕机，事务消息发不出去

兜底流程：
1. 本地事务中：INSERT t_order + INSERT t_local_message（同一DB事务）
2. MQ 恢复后：XXL-Job 每分钟扫描 status=0 AND created_at < NOW()-60s
3. 扫描到未发送消息 → 重新发送到 MQ → 消费者处理
4. 消费成功 → 更新 t_local_message status=1
5. 3次重试仍失败 → status=3(死信) → 告警 + 人工介入
```

---

## 💻 三、核心代码实现

### 3.1 事务消息监听器

```java
@RocketMQTransactionListener
public class OrderTransactionListener implements RocketMQLocalTransactionListener {

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        try {
            // 同一DB事务：创建订单 + 写本地消息表
            orderTransactionService.executeLocalTransaction((OrderCreateContext) arg);
            return RocketMQLocalTransactionState.COMMIT;
        } catch (Exception e) {
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        // 回查：订单存在则Commit，不存在则Rollback
        String orderNo = msg.getHeaders().get("orderNo", String.class);
        Order order = orderMapper.selectByOrderNo(orderNo);
        return order != null ? COMMIT : ROLLBACK;
    }
}
```

### 3.2 本地消息表扫描补发

```java
@Scheduled(fixedRate = 60000) // 每分钟
public void recoverLocalMessages() {
    List<LocalMessage> pending = localMessageMapper.selectPending(
        LocalDateTime.now().minusSeconds(60), 3); // status=0, retry<3

    for (LocalMessage msg : pending) {
        try {
            rocketMQTemplate.syncSend(msg.getTopic(),
                MessageBuilder.withPayload(msg.getPayload()).build());
            localMessageMapper.updateStatus(msg.getId(), 1); // 成功
        } catch (Exception e) {
            localMessageMapper.incrementRetry(msg.getId());
            if (msg.getRetryCount() >= 2) {
                localMessageMapper.updateStatus(msg.getId(), 3); // 死信
                alertService.sendAlert("本地消息3次重试失败: " + msg.getId());
            }
        }
    }
}
```

---

## ⚖️ 四、方案对比

| 维度 | 事务消息 | TCC | Seata AT |
|------|---------|-----|--------|
| 一致性 | 最终一致 | 强一致 | 最终一致 |
| 性能 | 高（异步） | 中（3接口） | 中（全局锁） |
| 侵入性 | 中（Listener） | 高（Try/Confirm/Cancel） | 低（自动回滚） |
| 适用场景 | 核心链路（下单） | 资金类（转账） | 非核心链路 |

---

## 🐛 五、踩坑记录

### 5.1 事务消息回查频率过高

- **原因**：本地事务执行太慢（> 60s），Broker 认为超时
- **解决**：本地事务只做快操作（写 order + local_message），慢操作交给消费端

### 5.2 消费端幂等

- **现象**：MQ 重试导致重复扣库存
- **解决**：消费端 @Idempotent(key = "#msg.keys") 保证幂等

---

## 🎤 六、面试考察点

### Q1: 分布式事务用什么方案？

> 1. "RocketMQ 事务消息 + 本地消息表双保险"
> 2. "事务消息 6 步：半消息→本地事务→Commit/Rollback→消费→回查"
> 3. "本地消息表兜底：MQ 不可用时，定时扫描补发"
> 4. "消费端幂等：@Idempotent 防重复消费"

### Q2: 为什么不用 TCC？

> 1. "TCC 每个操作要写 Try/Confirm/Cancel 三个接口，业务侵入太大"
> 2. "下单链路 4 个服务 × 3 个接口 = 12 个接口，开发维护成本高"
> 3. "事务消息异步解耦，性能更好，最终一致性对下单场景够用"

---

## 📚 参考资料

| 资料 | 参考内容 |
|------|----------|
| 📄 phase-5/README.md §3.22 | 分布式事务完整设计 |
| 📄 03-distributed-solutions.md §3 | 4方案对比 + 事务消息流程 |
| 📄 12-order-and-payment | 订单服务事务消息实现 |
