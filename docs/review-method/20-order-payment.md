# 20 订单与支付

> 复审维度 20 | 覆盖模块：09-payment, 10-order | 领域专属检查项
>
> 你是谁：分布式系统审查员。不凭记忆，逐条执行以下指令。本维度覆盖订单状态机/分库分表/支付回调验签/对账等独有问题。
> 通用规则：MQ见 04、TCC见 06、安全见 07、数据一致性见 03。

---


**执行本维度后，必须在审查报告中输出 `[20] 20 订单与支付：发现 N 项`。缺失此标题 = 本维度未被执行。执行每条检查项时，标注来源维度编号（如 [20]）。**
## 检查项

### 20.1 订单状态机完整性 | 透镜：业务/工程

**必须检查**：订单状态流转是否经过状态机——CREATED→PAID→SHIPPED→FINISHED 或 CREATED→CANCELLED；并发状态变更是否原子。

**怎么查**：
```bash
grep -rn 'OrderStatus\|orderStatus\|canTransit\|PAID\|CANCELLED\|FINISHED' my-xhs-order/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 允许任意跳转 | CANCELLED→PAID（取消后支付）→状态机无门禁 |
| 状态更新非原子 | `select→if(status==PAID)→update→并发双通过` |
| 支付超时未自动取消 | 30 分钟未支付→无定时 Cancel→订单永久 PENDING |

**案例**：order 状态机缺失→CANCELLED 订单仍可支付（修复 `canTransitTo` 状态机检查 + LambdaUpdateWrapper 原子更新）。

---

### 20.2 分库分表正确性 | 透镜：工程/分布式

**必须检查**：分库分表的路由规则是否正确、跨分片查询是否有、分片数变化如何迁移。

**怎么查**：
```bash
grep -rn 'sharding\|ShardingSphere\|分库\|分表\|shard\|route\|dataSource' my-xhs-order/src/main/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 路由字段不可变 | 按 `userId` 分库→改 userId→数据移动到新分片→旧分片残留 |
| BETWEEN 跨分片 | `WHERE create_time BETWEEN` → 跨 4 个分片→每个分片全扫描 |
| 无广播表 | 配置表不放广播表→每个分片存一份→冗余不一致 |
| 分布式 ID 碰撞 | 分片自增 ID→不同分片同 ID→全局不唯一 |

**案例**：order 用 `t_order_0/1/2/3` 四库各含 `t_order_0/1/2/3` 四表→共 16 物理表→SHOW TABLES 只查一个库→"表不存在"误报（需查全部 4 库）。

---

### 20.3 支付回调验签与幂等 | 透镜：生产级/工程

**必须检查**：支付回调是否验证了签名（HMAC/RSA）；回调能否被没有验签的伪造者模拟。

**怎么查**：
```bash
grep -rn 'callback\|notify\|webhook\|onPaymentSuccess\|verify.*sign\|verifySign\|HMAC\|RSA' my-xhs-payment/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 回调无验签 | POST `/pay/notify` 无签名验证→任何人可伪造支付成功回调→余额零元购 |
| 回调重复处理无幂等 | 支付平台有重试→双扣余额→支付双花 |
| 回调未校验金额 | 验签通过但 `amount` 和订单不一致→1 元支付通知覆盖 100 元订单 |

**案例**：09-payment 支付回调验签缺失（P0 安全——08-15 未审模块优先排查）。

---

### 20.4 订单与支付对账 | 透镜：生产级/业务

**必须检查**：有轮对账机制——支付平台已支付 vs 本地未更新、本地已支付 vs 支付平台未回调。

**怎么查**：
```bash
grep -rn 'reconciliation\|对账\|reconcile\|payment.*check\|order.*reconcile' my-xhs-payment/src/main/java/ my-xhs-order/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 无对账 | 支付成功的订单 status 不更新→用户已支付但看不到→投诉 |
| 对账方向错误 | 用支付修复本地→但支付通知丢失了→本地少扣款 |
| 对账频率过低 | 每天一次→用户等 24 小时→投诉 |

**案例**：（全特性面预置检查项——10-order/09-payment 的对账机制需逐模块审计。）

---

## 跨维度引用

| 相关概念 | 主维度 | 说明 |
|---------|:--:|------|
| 状态机完整性 | 01.7 | canTransitTo 状态机校验 |
| HMAC 签名 | 07.5 | 支付回调验签 |
| Outbox/TCC | 06 | confirm/release 库存链路 |
| 分桶库存 | 18.2 | 库存分桶与扩容 |

---

## 验证命令汇总

```bash
mvn compile test-compile -pl my-xhs-payment,my-xhs-order -am
mvn test -pl my-xhs-payment,my-xhs-order

# 订单状态机
grep -rn 'OrderStatus\|canTransit\|PAID\|CANCELLED' my-xhs-order/src/main/java/

# 分库分表
grep -rn 'sharding\|ShardingSphere\|分库\|分表\|shard' my-xhs-order/src/main/

# 支付回调验签
grep -rn 'callback\|notify\|verify.*sign\|verifySign\|HMAC' my-xhs-payment/src/main/java/

# 对账
grep -rn 'reconciliation\|对账\|reconcile\|payment.*check' my-xhs-payment/src/main/java/ my-xhs-order/src/main/java/
```
