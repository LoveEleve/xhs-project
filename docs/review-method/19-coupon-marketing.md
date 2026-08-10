# 19 优惠券与营销

> 复审维度 19 | 覆盖模块：08-coupon | 领域专属检查项
>
> 你是谁：分布式系统审查员。不凭记忆，逐条执行以下指令。本维度覆盖优惠券的防刷/双花/并发抢券/过期/库存扣减等独有问题。
> 通用规则：并发见 02、MQ见 04、安全见 07。

---


**执行本维度后，必须在审查报告中输出 `[19] 19 优惠券与营销：发现 N 项`。缺失此标题 = 本维度未被执行。执行每条检查项时，标注来源维度编号（如 [19]）。**
## 检查项

### 19.1 防刷与防双花 | 透镜：业务/工程/盲区

**必须检查**：用户领取优惠券是否有频率/总数限制；同一券是否可以被多次使用（双花）。

**怎么查**：
```bash
grep -rn 'claim\|领取\|receive.*coupon\|grab\|抢券\|limit.*per.*user\|daily.*limit' my-xhs-coupon/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 无领取频率限制 | 用户每秒领 1000 张→库存被刷光 |
| coupon 无 useStatus | 用完不标记 used→同一 coupon 可以多次使用→双花 |
| 一人多领 | 同一用户重复领同一券→无 `uk(userId, couponTemplateId)` 唯一约束 |
| 券用完未扣总库存 | 用户领券→用户 quota -1→但全局 quota 未减→超发 |

**案例**：`receiveCoupon` 缺防刷→同一用户可无限领同一券——修复加 `uk(userId, couponTemplateId)` + Redis 领取计数 + RateLimit。

---

### 19.2 并发抢券与库存扣减 | 透镜：并发/工程

**必须检查**：高并发抢券场景下的库存扣减是否原子——是否用 Lua 或 Redis DECR 保证不超发。

**怎么查**：
```bash
grep -rn 'DECR\|decr\|deduct.*coupon\|coupon.*stock\|coupon.*remain\|seckill\|秒杀' my-xhs-coupon/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| SELECT→check→UPDATE 非原子 | 100 人同时看到库存=1→全部通过检查→100 人领到→超发 99 |
| Redis DECR 不检查下限 | `decr quota`→变成负数→超出限制 |
| 库存回滚不一致 | 领券后其他操作失败→回滚 DECR→但 DECR 已是原子操作→多回滚→库存多了 |

**案例**：抢券用 `decr quota` 不检查 `>=0`→超发负数（修复 Lua atomic: `if quota > 0 then decr+insert else return 0`）。

---

### 19.3 券过期与生效时间 | 透镜：业务/工程

**必须检查**：券的有效期判断是否准确——开始时间/结束时间的边界场景；过期券是否自动失效。

**怎么查**：
```bash
grep -rn 'expireTime\|startTime\|validTime\|expire\|过期\|valid\|已使用\|used' my-xhs-coupon/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 过期券仍可用 | `if(now > expireTime)` 但用了 `>` 而非 `>=`→恰好到期那秒仍可用 |
| 开始时间未到可用 | `if(now >= startTime)` 应全门禁→未到时间却可用 |
| 无过期清理 | 过期券数据不清理→表增长性能下降 |
| 券时间和业务时间不同源 | 用户端时间可以伪造→需用服务器时间 |

**案例**：`isValid()` 方法用 `now > expireTime` 而非 `>=`→边界秒漏判断（修复 `>=`）。

---

### 19.4 券折扣计算与退款 | 透镜：业务/工程

**必须检查**：券折扣计算在 order 侧还是 coupon 侧——两个模块的计算逻辑是否一致；退款时券是否返还。

**怎么查**：
```bash
grep -rn 'calculateDiscount\|computeDiscount\|折扣\|discount\|coupon.*price\|fullReduction|满减' my-xhs-coupon/src/main/java/ my-xhs-order/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| order 和 coupon 各算各的 | order 有本地折扣计算、coupon 也有→两处结果不同→按谁为准 |
| 部分退款券不回收 | 订单有 3 个商品→退 1 个→券折扣全保留→商家亏 |
| 券叠加规则不清 | 多张券叠加→满减顺序不定义→不同计算路径结果不同 |

**案例**：`calculateCouponDiscount` 在 order 侧直接计算而非调 coupon Feign→两处逻辑可能不一致（修复 order 侧改 Feign 调用 coupon）。

---

## 跨维度引用

| 相关概念 | 主维度 | 说明 |
|---------|:--:|------|
| RateLimit 限流 | 01.11 | 领券端点 + prefix |
| IDOR 归属 | 01.6 | 别人不能用自己的券 |
| 分布式锁 | 02.4 | 并发领券加锁 |
| Outbox 模式 | 06.4 | 发券通知 MQ 可靠性 |

---

## 验证命令汇总

```bash
mvn compile test-compile -pl my-xhs-coupon -am
mvn test -pl my-xhs-coupon

# 领券/防刷
grep -rn 'claim\|领取\|receive.*coupon\|grab\|抢券' my-xhs-coupon/src/main/java/

# 库存扣减
grep -rn 'DECR\|decr\|deduct.*coupon\|coupon.*stock\|coupon.*remain' my-xhs-coupon/src/main/java/

# 有效期
grep -rn 'expireTime\|startTime\|validTime\|expire\|过期\|valid' my-xhs-coupon/src/main/java/

# 折扣计算
grep -rn 'calculateDiscount\|computeDiscount\|折扣\|discount\|fullReduction' my-xhs-coupon/src/main/java/ my-xhs-order/src/main/java/
```
