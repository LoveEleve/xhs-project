# 缓存一致性实测 + 消息乱序注入（2026-09-20）

> 换面：② 缓存一致性（更新→读窗口）与消息乱序（SOCIAL_TOPIC 旧事件）。后者发现**真缺陷并修复**。

## 一、缓存一致性（商品更新 → 读）

| 步骤 | 结果 |
|---|---|
| 更新商品描述（PUT /api/product/spu/1，admin 令牌） | 200 |
| 更新后首次读（含缓存键 `myxhs:product:spu:1`） | **24ms 内读到新值**（缓存已失效并重建） |
| 缓存键内容 | 含新值 ✓ |
| 还原描述后复读 | 原值 ✓ |

机制印证：`evictSpuCache`（提交后立即删）+ **延迟二次删除（实测代码为 1s，非文档口径 500ms）** 覆盖"并发读回填旧值"窗口；Redis 故障时删除失败仅告警（依赖物理 TTL 兜底）。

## 二、消息乱序注入（SOCIAL_TOPIC）

### 踩坑记录（注入方法）
- 真实发布端发到 **`SOCIAL_TOPIC:{action}`（带 Tag）**，消费者以 `selectorExpression` 过滤；**无 Tag 的注入消息会被直接跳过**（第一次注入因此"无效果"，非消费端问题）。

### 发现：counter 侧缺版本门 → 旧事件改写计数（真缺陷）
注入序列（同 userId/bizType/bizId，actionTime 作为版本）：

| 步骤 | analytics 侧 | counter 侧 | 结论 |
|---|---|---|---|
| ① LIKE（新 ts=T2） | 版本键=T2、DB 落库 | 计数=1 | 一致 |
| ② **UNLIKE（旧 ts=T1<T2）** | 版本校验拒绝（键仍 T2） | **计数被改为 0** | **分叉**：关系集/DB 认为"已点赞"，计数却为 0 |
| ③ 清理 UNLIKE（新 ts=T3） | 版本键=T3 | 计数=0 | 恢复正常 |

根因：`CounterEventConsumer.handleLikeEvent` 原实现只依赖 **SADD/SREM 可交换性 + msgId 去重**（注释也如此假设），**没有 actionTime 版本校验**；而 analytics 消费者有 Lua 版本门。于是"后到的旧 UNLIKE"被 counter 执行，计数被旧事件改写。

### 修复：counter 消费端补 actionTime 版本门
- 新增独立版本键 `myxhs:counter:event:version:like:{userId}:{bizType}:{bizId}` + Lua `GET/compare/SET`（TTL 24h，与 analytics 同构语义）；
- `handleLikeEvent` 在应用计数前校验：**旧事件直接跳过**（日志 `跳过旧点赞事件(版本门)`）。

### 修复验证

| 步骤 | counter 计数 | analytics 版本 | counter 版本 |
|---|---|---|---|
| ① LIKE(新) | 1 | T2 | T2 |
| ② UNLIKE(旧) | **1（拒绝生效）** | T2（不变） | T2（不变） |
| ③ UNLIKE(新) | 0 | T3 | T3 |

正常路径回归：API 点赞→计数 1；取消→计数 0 ✓（版本门对正常顺序无影响）。

## 三、设计口径（面试可讲）
1. **"集合操作可交换"≠"事件语义可交换"**：SADD/SREM 让状态对顺序鲁棒，但"旧 UNLIKE 晚到"改变的是**用户最新意图**——必须有版本（actionTime/seq）门，且要在**每个下游消费端**统一，否则分支间分叉。
2. 版本门要与业务键同寿命（24h TTL 覆盖 MQ 重试窗口），并容忍 actionTime 缺失（缺省不做门，保持兼容）。
3. 注入式验证注意 **Tag/路由过滤**，否则测的是"消息未达"而不是"逻辑拒绝"。

## 四、产物
- 代码：`my-xhs-counter/.../consumer/CounterEventConsumer.java`（版本门）
- 关联：`analytics/LikeUnlikeConsumer`（既有版本门，本轮对照基准）
