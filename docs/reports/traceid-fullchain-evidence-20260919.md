# traceId 全链路透传：运行时证据 + 三处断链修复（2026-09-19）

> 目的：为 xhs/16（traceId 三跳）补运行时证据，并借证据排查断链。
> 方法：跑跨域 E2E（`scripts/test-e2e-business-chain.py`），按 orderId/userId 聚合 `/data2/logs/my-xhs-*.json`，比较同一 traceId 覆盖的服务与线程。

## 一、机制实现（代码）

| 环节 | 实现 | 位置 |
|---|---|---|
| HTTP 入口生成 | `X-Trace-Id` 透传或生成 32 位 UUID，写入 MDC + TraceContext | `TraceIdConfig.java:67-93` |
| Feign 透传 | 请求头携带 traceId + 染色标记 | `FeignTraceInterceptorConfig` |
| MQ 发送 | `X-Trace-Id/X-User-Id/X-Gray-Tag/X-Api-Version/X-AB-Group/X-Pressure-Test` 六个 Header | `MqTraceHelper.wrapWithTraceContext` |
| MQ 消费 | `restoreTraceContext` 恢复 + finally 清理 | `MqTraceHelper.restoreTraceContext/clearTraceContext` |
| 线程池 | MDC 快照传递（任务前恢复、任务后清理） | `MdcAwareExecutorService` |
| 后台线程 | 新增 `TraceContextHolder.startNewTrace()`（生成 traceId+MDC，finally 清理） | 本次新增 |

## 二、运行时证据（2026-09-18 23:46 / 09-19 00:24 两轮 E2E）

| 链路 | traceId | 覆盖服务/线程 |
|---|---|---|
| 下单（事务消息→库存预扣） | `b1ebe311971e4ccb98117475c2ad5d4c` | gateway→order→product→inventory→coupon→user（6 服务 19 行）；**延时关单消息 30 分钟后消费仍带同一 trace**（00:16:29） |
| 支付（回调→订单→通知） | `dccfd85fdf654d4e9903a78f37763184` | gateway→payment→order(HTTP+MQ)→notification（4 服务 16 行） |
| 发货（→通知） | `d6535cefc1724de784b5af9ffab13959` | gateway→order→notification |
| 退款（定时器→MQ→回补→通知） | `dd47a5f2f1b74c569fce999a3632934b` | payment(scheduling-1)→REFUND_RESULT_TOPIC→order(HTTP+MQ)→notification |
| 线程池 | — | `order-async` 42/42 行均带 traceId（MdcAwareExecutorService 生效） |

## 三、借证据发现并修复的三处断链

### 3.1 退款回调在定时线程，整链 trace=None（严重）
- 现象：退款链 payment(scheduling-1)、MQ、order 消费、通知消费全部 `trace=None`（支付/发货链正常）。
- 根因：`PayCallbackSimulator.simulateRefundCallback()` 是 `@Scheduled` 线程，无入站 traceId，`wrapWithTraceContext` 的 MDC 兜底也为空。
- 修复：`TraceContextHolder.startNewTrace()` + 模拟器逐单 try/finally 开链/清理（支付回调模拟同步处理）。
- 验证：修复后整链共享 `dd47a5f2…`（见上表）。

### 3.2 asyncSend 回调线程日志丢 trace（可观测缺口）
- 现象：`[订单通知] 已发送` 日志在 `NettyClientPublicExecutor` 线程输出且 `trace=None`（消息本身带 trace，消费端正常）。
- 修复：发送前捕获 traceId，回调方法 `withTrace(...)` 临时写入 MDC（不污染线程池复用）。
- 验证：修复后回调日志带 `5fe9c04a…`。

### 3.3 Feign/MQ 双通道并发退款 → 乐观锁异常 + 支付误补偿（业务缺陷）
- 现象：MQ 消费与 Feign 同步同时到达（差 3ms），后到线程 `appendEvent` 乐观锁失败抛 `IllegalStateException` → 500 → 支付侧记"Feign通知订单失败（MQ无消费端）"并触发补偿任务（日志误导）。
- 修复：`onRefundSuccess` 捕获并发异常 → 重查状态，已是 5（已退款）则按幂等成功返回 true；否则返回 false。
- 验证：修复后同一竞态场景输出 `[订单] 退款回调并发竞态(Feign/MQ 双通道): alreadyRefunded=true`（WARN），无 500、无补偿触发。

## 四、验证汇总

| 项 | 结果 |
|---|---|
| E2E 全链路 | **30/30 PASS**（`e2e-business-chain-run-20260919-002353.json`） |
| API 回归 07-14 | **94/94 PASS** |
| 单测 common+payment+order | **161/161 PASS** |
| 误补偿/500 计数 | 0（修复前后对比：1 次/轮 → 0） |

## 五、边界与后续
- 其它后台入口（XXL-Job handler、对账 Job、`@Scheduled` 扫描类）尚未统一调用 `startNewTrace()`；建议后续在通用执行器/Job 基类统一注入。
- 网关侧 traceId 生成逻辑未在本报告展开（见 xhs/16 题与 `TraceIdConfig`）。
