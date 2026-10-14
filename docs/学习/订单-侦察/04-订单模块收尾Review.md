# 订单模块收尾 Review（2026-10-14）

> 范围：`my-xhs-order` 全模块（主代码 28 文件 / 7451 行 + 测试 7 文件）。
> 方法：先结构性静态检查（调用链/死代码/状态机全路径/消费者与 Job 清单），再逐条走关键流程（下单/终结×5/关单/支付/库存失败/退款/补偿），只修真问题，观察项明确记录。
> 结论：**全模块收尾通过**——终结路径全集（5 条）全部收敛到统一入口；补偿链路无自循环/无消息放大；事件源写入点唯一且与状态迁移同事务；编译 + 单测（192）全绿。运行时用例留待真实环境（§五）。

---

## 一、全模块结构盘点（收尾基线）

**终结路径全集（5 条，全部走 `terminateOrder` 统一入口）**

| 原因 | 触发 | 来源 | 备注 |
|---|---|---|---|
| `USER_CANCELLED` | 用户取消 API | APP | |
| `TIMEOUT` | 延时消息 + 兜底扫描 Job | TIMEOUT_JOB | |
| `INVENTORY_FAILED` | 库存失败收口（MQ） | SYSTEM | |
| `COUPON_FAILED` | 下单链路券核销失败 | SYSTEM | 宽容调用（超时关单兜底） |
| `PAY_FAILED` | 支付失败回调（Feign + MQ 双通道） | PAY_CALLBACK | **本轮收尾补录**（原第五条路径绕过统一入口） |

**投递与补偿机制矩阵**

- 创建：事务消息（`order-producer-group`）+ 本地消息表；终结：事务消息（`order-termination-producer-group`，独立模板/监听器）+ 本地消息表兜底——两侧同构；
- 消费者（5）：`OrderCloseConsumer` / `PayResultConsumer` / `InventoryFailureConsumer` / `RefundResultConsumer` / `OrderCompensationConsumer`；
- 补偿 action（4）：`RELEASE_STOCK` / `RETURN_COUPON` / `CONFIRM_STOCK` / `CLOSE_ORDER` + 兜底集合重放（`OrderCloseJob` 每分钟）；
- Job（4）：`OrderCloseJob`（兜底关单+补偿重放）/ `LocalMessageRetryJob`（本地消息补发）/ `OrderMappingRepairJob`（映射补录）/ `OrderCalibrationJob`（事件流校准干跑）；
- 状态迁移点（唯一性核验）：`OrderTransactionService`（创建）、`OrderTerminationService`（终结 0→4）、`appendEvent`（1/2/3/5 与 4 的白名单收敛）——**全模块无绕过**（`setCancelledAt`/`updateStatusWithLock` 直接调用仅存在于上述两处事务内核与 EventService 内部）。

---

## 二、本轮发现与处置

| # | 级别 | 问题 | 处置 |
|---|---|---|---|
| R-1 | **高** | **第五条终结路径绕过统一入口**：`onPaymentFailed`（支付失败回调）仍用旧模式——直接 `appendEvent(EVENT_CANCELLED)` + 同步 Feign 并行释放/退券 + 无用户通知；未走 `terminateOrder` → 无终结事件（inventory/coupon/notification 订阅方收不到）、无归因（"支付失败"混入通用取消） | **已收敛**：新增 `TerminationReason.PAY_FAILED` + `EVENT_PAY_FAILED_CANCELLED`（to=4，白名单 {0}）+ 通知文案"因支付失败已自动取消"；`onPaymentFailed` 改为 `terminateOrder` 入口（outcome 驱动，回调幂等语义保留）；顺带删除死代码 `releaseInventory` 包装（仅此路径调用） |
| R-2 | 中 | **confirm 补偿消息放大**：`compensateConfirmStock` 复用带"失败即发补偿消息"的 `confirmInventoryDeductSync`——补偿消费者失败会抛异常走 MQ 退避重试，执行体又发新补偿消息 → 每次重试放大一条（与 2026-10-11 RELEASE_STOCK 同类问题） | **已拆纯执行体**：`doConfirmInventoryDeduct`（返回真实成败、不发消息）；业务路径（支付成功/退款前）走带补偿的包装；补偿消费者/兜底重放走纯执行体 |
| R-3 | 低 | **`EVENT_CREATED` 死补记**：创建事务内核已写 CREATED 事件（payload=null）；`createOrder` 步骤 7 再"补记"（带 payload）→ 因重复事件恒被幂等跳过 → payload 永不落库（死行为；且有"极快后续事件先落，CREATED 后插导致事件流乱序"的理论风险） | **已修**：载荷并入创建事务内核的唯一写入点（`OrderTransactionService`，与订单插入同事务）；移除步骤 7 |
| R-4 | 低（观察） | `onRefundSuccess`：退款前 `confirmInventoryDeductSync` 失败**不阻断**后续 `restoreStockOnRefund`（注释声明的"先确认再回补"在失败分支不成立）→ 双通道叠加窗口依赖 `CONFIRM_STOCK` 补偿及时收敛，极端情况（补偿超 30min 未成 + 预扣超时释放）存在库存虚增窗口 | **记录不改**：改为"confirm 失败则不回补"会使退款订单欠补（更差）；现设计以"补偿 + 对账"兜底，属既定权衡 |
| R-5 | 低（观察） | `confirmReceive` / `deliverOrder` 的 `appendEvent` 竞态（IllegalStateException）直接冒泡 500，未做业务语义转换（与 cancelOrder 对齐的处理缺失） | **记录不改**：低频运营/用户操作 + 前置状态守卫，竞态窗口极小；如需可后续对齐（非收尾阻塞项） |

**上轮（04B §十一）已修项复述**：C1 投递形态（事务消息+本地消息表兜底）、C2 回查 operation_type 过滤（资损级）、H3 发送结果口径对齐创建侧、H4 cancelOrder 竞态 outcome 驱动、M6 幂等键=KEYS(orderNo)、M7 `PseudoOrderIdUtil` 单点化。

---

## 三、逐流程核验结论（收尾清单）

| 流程 | 核验点 | 结论 |
|---|---|---|
| 下单 | 幂等键（userId+bizIdentifier）/ 分布式锁 / SKU 双状态校验 / 同 SKU 拒绝 / 前置库存 / 事务消息（发送状态三分支）/ 券失败终结 / 延时消息 / 快照 / 映射 | 通过（步骤 7 死代码已移除） |
| 终结×5 | 唯一入口 / 状态校验四态 / 回读收敛 / Outbox+事务消息 / 快照含 reason+source / 缓存失效 / 本地消息标记 | 通过（PAY_FAILED 本轮补录） |
| 关单 | 延时消息 + Job 兜底 / 幂等跳过 / 失败补偿 | 通过 |
| 支付 | 成功：appendEvent+乐观锁+竞态 false（支付侧自动退款闭环）/ 确认扣减（异步+CONFIRM_STOCK 补偿）；失败：终结（R-1 收敛后） | 通过 |
| 库存失败收口 | status=0 终结 / status=1 人工指标 / 其他跳过 | 通过 |
| 退款 | 1/3→5 / 5 幂等 / 竞态双通道收敛 / confirm→回补→退券→通知 / 售后防二次退款核对 | 通过（R-4 观察项） |
| 补偿 | 4 action 分发 / 纯执行体（不自循环）/ 失败抛出走 MQ 退避 / 兜底集合重放 / 消费者按 action 分发 | 通过（R-2 修复后无消息放大） |
| 事件源 | 写入点唯一性 / 白名单 / T-110/T-123 收敛语义 / 回放校准 Job | 通过 |
| 售后子域 | 申请幂等/额度复核/审核复核/退款执行/卡单恢复/回补重试 | 通过（结构良好，未发现新问题） |

---

## 四、验证

- 强制全量 `test-compile`（删除 target/test-classes 后重编，防增量编译漏检）→ 通过；
- 单元测试（纯 Mockito）：**192 全绿**——common 93 / coupon 11 / **order 71**（新增 onPaymentFailed 2 用例）/ inventory 17；
- 运行时用例（04B §九）：待真实环境（Redis / MySQL / RocketMQ）执行；上线顺序按 04B §五。

## 五、收尾状态与遗留

**本模块（订单域）代码层面收尾完成**。遗留观察项（非阻塞，均有兜底）：

1. 售后退补口径 `max(a,b)` 漏补（03-修复记录 §二，待专门设计：并发售后下"多补"方向更危险）；
2. `onRefundSuccess` 双通道叠加窗口（R-4，对账兜底）；
3. `deliverOrder`/`confirmReceive` 竞态 500（R-5）；
4. 归档 Job / 自动确认收货（功能立项，非 BUG）；
5. 详情缓存坡脚（9 删 0 写，死动作清理项）。
