# 全项目直接 REVIEW（本人亲自，非子代理）

> 2026-08-10 | 由主模型**直接读码**独立核验（非批量委派子代理），逐文件确认。目的：保证 Review 质量与结论可信度。
> 方法：环境健康核验 + 声明修复真实性核验 + 安全/一致性关键路径逐文件直读。

---

## 一、环境与声明修复核验

### 1.1 服务健康
15 服务全部 UP（gateway→search 各端口 /actuator/health 200）。

### 1.2 声明修复真实性（直接 grep 源码确认）
| # | 声明修复 | 源码证据 | 结论 |
|:--:|------|------|:--:|
| 39 | HMAC 白名单 | gateway yml 含 `/api/im/ws`(3处) | ✅ |
| 46 | spu-detail image | SpuService `vo.setImage`(3处) | ✅ |
| 48 | 通知消费修复 | PushTemplateMapper `LOWER(type)` | ✅ |
| 52 | DlqMetrics 缓存 | DlqMetrics `backlogCache`(5处) | ✅ |
| 44 | 模板列表接口 | CouponController `template/list` | ✅ |

---

## 二、安全关键路径直读（独立确认）

### 2.1 GatewayAuthFilter（X-User-Id 伪造）
- **鉴权路径** `h.set(X-User-Id, uid)`（:131）覆盖 → 无法伪造 ✅（C-07 正确）
- **白名单路径** `chain.filter(exchange)`（:86-88）**不清理入站 X-User-Id** → 客户端可在公开路径自造 X-User-Id 透传下游 → **P0-7a 确认**

### 2.2 HmacSignatureFilter（HMAC）
- 签名串 `method+path+timestamp+nonce`（:183）**不含 body/query** → **P0-6a 确认**（防篡改失效）
- per-user secret `opsForValue().get`（:169）**未 try/catch**，而 nonce 段有 → Redis 故障 500 → **P0-6b 确认**
- 用 `MessageDigest.isEqual` 防时序 ✅

### 2.3 下游信任边界
- 各 controller 直接 `@RequestHeader("X-User-Id")` 无二次校验；依赖 gateway set() 覆盖。服务端口直连（Nacos/内网）可伪造 → 残余风险（靠部署隔离）。

---

## 三、一致性/分布式关键路径直读（独立确认）

### 3.1 PaymentService.pay（P0-2）
- 只查自身 Redis `payment:status:{orderId}`（:142,161），**未回查订单状态**（OrderFeignClient 存在未用于此）→ 已取消/关单订单可支付 → **P0-2 确认**

### 3.2 OrderTransactionService 双投递（P0-8）
- 本地消息 `setStatus(0)` 后 insert（:100）→ LocalMessageRetryJob 扫 status∈(0,2) 重发到同 ORDER_TRANSACTION_TOPIC → 与事务消息**双投递** → **P0-8 确认**（回查只查存在性，故标 status=1 即可修）

### 3.3 InventoryCompensationJob release.lua（P0-3）
- `execute(releaseScript, List.of(totalKey, predeductKey, bucketKey(skuId,0)))`（:75-77）只传 **3 KEYS 缺 KEYS[4](indexKey) + ARGV(skuId,orderId)**，且**写死桶 0** → release.lua 报错，补偿必失败 → **P0-3 确认**

### 3.4 OrderTransactionConsumer 预扣幂等（P0-8 崩溃窗口）
- msgId isFirstProcess + pseudoOrderId 折叠哈希(碰撞风险) + 失败 removeMark
- **崩溃窗口**：isFirstProcess=true 后进程崩溃 → removeMark 未执行 → msgId 标记残留 → rebalance 后部分 SKU 永久不预扣 → **超卖** → 确认（低概率但安全敏感）

### 3.5 Coupon 领券 Lua
- 单一 Lua 原子防超卖（claim_coupon.lua，hash tag 同 slot）→ **无问题** ✅

---

## 四、运维三件套直读结论（承接 REVIEW-V2 十一）

- 监控端点/日志/MQ/HTTP/Feign TraceId 传播**主体完善**（21 消费者全 restore）
- 缺口：**O1** MDC userId 恒空（TraceIdConfig 只写 traceId）、**O2** product SPU_ASYNC_EXECUTOR 裸池、**O3** cart 裸池、**O4** 自定义指标少

---

## 五、直接 REVIEW 结论

- **独立确认**了 REVIEW-V2 中 P0-1/2/3/4/5/6a/6b/7a/8 等核心问题（非仅采信子代理）。
- 未发现子代理漏报的重大新问题（直接读码交叉验证）。
- **最需优先**：P0-6a(安全)、P0-2(资金)、P0-3(补偿必挂)、P0-8(超卖/双投递)、P0-7a(X-User-Id)。

> 本直接 REVIEW 与 REVIEW-V2/FIX-PLAN-V2-FULL 结论一致，可信度经本人逐文件核实。修复仍按 FIX-PLAN-V2-FULL 批次执行。
