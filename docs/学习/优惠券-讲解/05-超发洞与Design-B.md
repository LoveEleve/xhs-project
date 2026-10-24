# 05 · 超发洞与 Design B

> **口径**：先讲**生产环境/行业常见做法**（至少一次 + Outbox + 幂等消费），再对照本项目；
> 代码不代表应然标准。
>
> **一句话**：发送链的"部分失败"**不能靠回滚掩盖**——回滚 + 消息在途 = **超发**；
> Design B = **名额锁定即成功 + Outbox 至少一次 + 消费端幂等**。
>
> **本篇带走**：① 两个真实超发场景 ② 回滚掩码为什么错 ③ Design B 三分支 + 消费幂等 + 边界。
> 前置：02 领券。证据：C-007 PRD、`sendClaimEventSync`、`CouponClaimConsumer`、`CouponOutboxSenderJob`。

---

## 0. 两个真实超发场景（C-007 之前的旧实现）

**场景 A：SEND_OK 之后，markSent 失败**

```
Lua 扣库存/限领 → 发 MQ（SEND_OK，消息已进 Broker）
→ markOutboxSent 抛异常
→ 旧实现：作废 Outbox + 回滚 Redis（stock+1 / claimed-1）
→ 消费者随后收到消息 → 发券
结果：库存回来了，券也发了 → 超发
```

**场景 B：syncSend 超时 / 非 SEND_OK**

```
Lua 扣库存/限领 → syncSend 超时（消息可能已写入 Broker）
→ 旧实现：当作发送失败 → 作废 Outbox + 回滚 Redis
→ 消息后来被投递 → 发券
结果：同样超发
```

共同点：**"发送结果不确定"时做了确定性动作（回滚）**。
你以为在"补偿失败"，其实在"给一条可能已成功的消息再发一次券"。

## 1. 生产环境怎么做（淘宝/行业常见）

| 原则 | 做法 |
|---|---|
| **投递语义** | 至少一次（at-least-once），不追求端到端 exactly-once |
| **可靠发送** | 本地消息表 / Outbox / 事务消息；先落库，再发；失败可补发 |
| **发送超时** | **超时 ≠ 失败**（消息可能已写入）；保留事实，交给补发/去重 |
| **消费幂等** | 业务唯一键 + 唯一索引/条件更新；重复消息无害 |
| **兜底** | 补发任务 + DLQ + 对账；记录不丢 |
| **回滚边界** | 设计上只在 Outbox 双次写入失败时回滚；但"写超时"可能已提交，严格说仍需按 `claimNo` 回查（残余窗口） |

> 淘宝具体实现不公开；上面是分布式消息的通用做法。本项目的 Design B 就是这套。

## 2. 本项目的 Design B（代码证据）

### 2.1 三分支：Outbox 先落，发送结果不确定一律保留

```java
private String sendClaimEventSync(Long userId, Long templateId) {
    String claimNo = UUID.randomUUID().toString().replace("-", "");

    // ① Outbox 先落：同 claimNo 幂等重试一次（写入超时≠失败；DuplicateKey=实际已写）
    try {
        outboxMapper.insertOutboxEvent(idGeneratorUtil.nextId(), userId, templateId, claimNo);
    } catch (DuplicateKeyException dup) {
        // 已存在 → 按成功继续
    } catch (Exception first) {
        try {
            outboxMapper.insertOutboxEvent(idGeneratorUtil.nextId(), userId, templateId, claimNo);
        } catch (DuplicateKeyException dup) {
            // 已存在 → 按成功继续
        } catch (Exception second) {
            return null;   // 设计上唯一回滚路径：双次写入失败（写超时可能已提交，残余窗口）
        }
    }

    // ② 同步发送：任何结果都保留 Outbox（补发必达）
    try {
        SendResult sendResult = rocketMQTemplate.syncSend(COUPON_CLAIM_TOPIC, message, 3000);
        if (sendResult != null && sendResult.getSendStatus() == SendStatus.SEND_OK) {
            try {
                outboxMapper.markOutboxSent(claimNo);   // 尽力标记；失败只告警
            } catch (Exception e) {
                // 行保留 → 补发重复由消费端幂等兜底
            }
        } else {
            // 非 SEND_OK：消息可能已写入 → 保留 Outbox
        }
    } catch (Exception e) {
        // 发送异常/超时：结果未知 → 保留 Outbox
    }
    return claimNo;   // 闸门放行后恒成功
}
```

| 分支 | 动作 | 为什么 |
|---|---|---|
| Outbox 写入成功 / DuplicateKey | 继续发送 | 事实已落库，可补发 |
| SEND_OK | `markSent` 尽力 | 标记失败不影响"已持久化" |
| 非 SEND_OK / 超时 / 异常 | **保留 Outbox，返回成功** | 消息可能已在途；回滚会超发 |
| **Outbox 双次写入失败** | 回滚 Redis + 报错 | 设计上唯一的回滚路径；但"写超时"可能已提交，**严格说仍需按 claimNo 回查**（当前未做，残余窗口） |

> ⚠️ **"双次失败"不等于"绝对无消息"**：数据库写超时可能发生在 commit 之后；
> 两次都超时，行仍可能已存在。更稳的做法是回滚前按 `claimNo` 回查 Outbox（有则按成功），
> 当前实现没做这一步——这是 Design B 的**残余窗口**（概率极低，但别把它说成"可证"）。

### 2.2 消费端：至少一次 + 幂等

```java
// 1) 完成态标记（只读；处理成功后才置位）
if (idempotentHelper.isProcessed(BIZ_TYPE, msgId)) {
    return;
}

// 2) G35 投递时限领守卫：DB 已发券数 >= perUserLimit → 跳过（DLQ 重放 × 重领交叉）
if (template != null && template.getPerUserLimit() != null
        && issued >= template.getPerUserLimit()) {
    idempotentHelper.markProcessed(BIZ_TYPE, msgId, TTL);
    return;
}

// 3) 插券：uk_claim_no 兜底（Outbox 补发会换新 msgId，靠 claimNo 幂等）
try {
    userCouponMapper.insert(userCoupon);
} catch (DuplicateKeyException e) {
    idempotentHelper.markProcessed(BIZ_TYPE, msgId, TTL);
    return;
}

// 4) 扣模板 remain（派生账本；失败不阻断，对账兜底）
try {
    int affected = templateMapper.decrementRemainCount(templateId);
    if (affected != 1) {
        businessMetrics.recordCouponAction("claim_stock_lag", false);
    }
} catch (Exception e) {
    businessMetrics.recordCouponAction("claim_stock_lag", false);
}

// 5) 处理成功后才置完成态
idempotentHelper.markProcessed(BIZ_TYPE, msgId, TTL);
```

**两层幂等**：

- `markProcessed` 按 `msgId`：挡"同一条消息重投"；
- `uk_claim_no` 按 `claimNo`：挡"Outbox 补发（新 msgId、同 claimNo）"。

### 2.3 Outbox 补发：记录不丢，退避重试

`CouponOutboxSenderJob`（`@Scheduled(5s)` + Redisson 锁）：

```
扫 status=0（cutoff=now-3s，批 200）
→ syncSend
→ SEND_OK：markSent
→ 非 OK/异常：scheduleRetry（30→480s 指数退避 + ±20% 抖动）
```

失败记录**不删除**；`CouponReconcileJob` 对 `status=0` 超 5 分钟的卡单告警。

## 3. 对照表：生产 vs 本项目

| 能力 | 生产环境 | 本项目 | 差异 |
|---|---|---|---|
| 投递语义 | 至少一次 | ✅ Design B | 对齐 |
| 可靠发送 | Outbox/事务消息 | ✅ Outbox + 补发 | 对齐 |
| 发送超时 | 不判死 | ✅ 保留 + 补发 | 对齐 |
| 消费幂等 | 唯一键 + 条件更新 | ✅ `uk_claim_no` + 完成态 | 对齐 |
| 回滚边界 | 可证无消息在途 | ⚠️ 仅 Outbox 双败（残余窗口） | 近似对齐 |
| DLQ 重放 | 有 | ✅ DLQ 重放 + G35 限领守卫（09） | 对齐 |
| 崩溃窗口 | 意图/事务消息 | ⚠️ Lua→Outbox 窗口（C-018 规划） | 已知 |
| 名额重建 | 受控重建 | ⚠️ 仍用 `remain_count` 补（C-018） | 已知 |

## 4. 十一个坑

1. **发送超时当失败**：回滚 + 补投 = 超发。
2. **SEND_OK 后 markSent 失败**：不能回滚/作废，行保留即可。
3. **Outbox 写入超时**：同 claimNo 重试；DuplicateKey=实际已写，按成功。
4. **回滚只在 Outbox 双写失败时**（设计上）；但写超时可能已提交，严格说需按 `claimNo` 回查。
5. **消费幂等不能只靠 msgId**：Outbox 补发换新 msgId，必须有 `uk_claim_no`。
6. **完成态标记要后置**：处理成功再置位；前置标记 + 崩溃会丢券。
7. **DLQ 重放可能再发券**：靠 G35 限领守卫 + `uk_claim_no`（09）。
8. **remain 扣减失败不要回滚发券**：派生账本，对账兜底；回滚会制造"领了却没券"。
9. **Lua→Outbox 崩溃窗口**：少卖方向，C-018 规划"领取意图"修复。
10. **`-3` 重建可能补高**：C-018 规划受控重建；当前是已知风险。
11. **双次写失败 ≠ 绝对无消息**：写超时可能已提交；回滚前应回查 `claimNo`（当前未做）。

## 5. 值不值得深挖 / 一句话

- **值得**：两个超发场景、回滚掩码、三分支、两层幂等——这是分布式消息最经典的题；
- **一句话带过**：Outbox 退避参数、Job cron；
- **一句话**：**发送结果不确定时，保留事实（Outbox）+ 至少一次 + 消费幂等；只有 Outbox 双写失败才回滚（设计上；残余窗口需回查）。**
- 分片/延时/推送见 **06**；DLQ/G35 见 **09**。

**面试口径**："领券发送链用 Design B：Outbox 先落，发送超时/非 OK 一律保留并补发，只有 Outbox 双写失败才回滚；消费端用完成态标记 + uk_claim_no 幂等。旧实现'发送失败就回滚 Redis'会在消息已入 Broker 时造成超发。"
