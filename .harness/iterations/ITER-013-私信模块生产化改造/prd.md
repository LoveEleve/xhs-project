---
iteration: ITER-013
status: confirmed
version: 1
created: 2026-10-19
updated: 2026-10-19
---

# PRD: 私信模块生产化改造

## 背景与目标

**两轮深探结论**（18 文件全读 + echo 比对 + SQL 基线核对）：

- **🔴 D1 僵尸未读**：软删会话不清 DB `unread_count` → 复活时旧未读回归（5+1=6）+ 列表（DB）与总数（Redis）口径分裂；
- **🔴 D2 双写无收敛**：未读双源（列表=DB/总数=Redis）+ 多漂移窗口（F1/读写失败/崩溃），IM 无对账 Job → 永久漂移；
- **🔴 D3 post-persist 无降级**：消息落库后 storeOffline/getRoute/未读任一 Redis 异常 → 无 ACK（发送方重发重复）+ 实时投递丢失；
- **🔴 F1 幽灵未读竞态**：未读 +1 在路由推送**之后** → 在线接收方快速 READ 清零后 +1 落地（DB=0/Redis=1）；
- **🟡 F2** registerRoute 非原子（GET+SET×2；同族 renew/unregister 均 Lua）；**F3** 连接上限缺 containsKey 豁免（SSE 同型有）；
- **🟡 F6** 发送侧无幂等键（ACK 丢失重发 → 重复消息不可去重）；
- **🟡 F4/D6 注释漂移 ×7**（ChatService"2 条消息"、conversationId 旧哈希口径、RouteMessage 缺 97、ImMessageVO msgType 两套、IM_JWT_SECRET 幽灵变量等）；**D5 SQL 副本漂移 ×2**（conversation_id 注释/idx_user_updated——migration 修了库没修基线）；
- **🟡 F5 死物**：LB 死组件（未接线）/`isOnline()` 死方法 + online 死键（零外部消费者）/`ChatMessage.isRead` 死列；**§7-5** /ws/ticket 无限流；
- 记录（讲）：多设备、真写扩散、分片、seq 键不加 TTL（重置破坏排序——演进=锁内 DB 播种）、allowed-origins 环境债、peerName/peerAvatar 未接线。

## 非目标

多设备/多端；写扩散；分片落地；对账 Job（连接重建替代）；消息编辑/撤回/已读明细。

## 需求（REQ）

| ID | 需求描述 | 优先级 |
|----|---------|--------|
| REQ-1 | **未读一致性（F1/D1/D2）**：① 未读 +1 前移（persist 后、推送前）；② 软删会话同置 `unread_count=0`；③ **连接时从 DB 重建未读缓存**（轻量收敛，替代 Job）；④ unread hash 补 30d TTL | P0 |
| REQ-2 | **投递可靠性（D3/D4）**：post-persist 各步（未读/离线副本/路由）异常降级不冒泡 + **ACK 必达**（消息已持久化）；handleRead 的 Redis 清零降级（不中断 READ_NOTIFY） | P0 |
| REQ-3 | **路由注册（F2/F3）**：registerRoute 改 Lua 原子（GET 旧值 + SET×2 单脚本）；连接上限补 containsKey 豁免（老用户满员可重连） | P0 |
| REQ-4 | **发送侧幂等（F6）**：`ImMessage` 增可选 `clientMsgId`——锁内 SETNX 映射（值=msgId，TTL 5min），重放直接 ACK 原 msgId；失败路径删标记（家族 removeMark 模式） | P1 |
| REQ-5 | **工程对齐（F4/D5/D6）**：注释漂移 ×7 修正；SQL 基线副本同步 ×2（conversation_id 注释 + idx_user_updated，4 副本）；死列留注 | P1 |
| REQ-6 | **死物与限流（F5/§7-5）**：LB 死组件移除；`isOnline()` + online 键族移除（零消费者，Lua 简化）；/ws/ticket 10/min + conversations 30/min + messages 60/min + unread-count 60/min | P1 |
| REQ-7 | **收口**：测试集从零（≥40）+ 十三模块回归 + §8 回写（10-私信）+ 面试材料同步（09-IM：online 键移除/LB 移除口径） | P0 |

## 验收标准（AC）

| ID | 归属 | 验收标准 | 来源 |
|----|------|---------|------|
| AC-1 | REQ-1 | 未读前移（increment 在 route 前断言）；软删 SQL 含 `unread_count=0`；连接重建（DB→Redis + TTL）；TTL 断言（单测） | F1/D1/D2 |
| AC-2 | REQ-2 | 路由/离线/未读失败仍发 ACK；READ_NOTIFY 在 Redis 失败时仍送达（单测） | D3/D4 |
| AC-3 | REQ-3 | register Lua 单脚本返回旧值；满员老用户重连豁免（单测） | F2/F3 |
| AC-4 | REQ-4 | clientMsgId 重放 ACK 原 msgId 不重复落库；失败删标记（单测） | F6 |
| AC-5 | REQ-5 | 注释零漂移（grep）；SQL 4 副本一致（grep）；死列零引用 | F4/D5/D6 |
| AC-6 | REQ-6 | 死物零残留（grep/编译）；限流注解落位 | F5/§7-5 |
| AC-7 | REQ-7 | 测试集 ≥40 + 十三模块全绿 + §8/材料回写 | 回归 |

## 边界情况

- 连接重建与并发新消息竞态 → 窗口内可能少计 1（下次连接收敛，文档明示）；
- clientMsgId 未传 → 跳过幂等（旧行为）；重放超过 TTL(5min) → 视为新消息；
- online 键移除后：`isOnline` 语义由 route 键存在性替代（无人调用，纯清理）；
- 踢线/重连竞态：双参 remove + Lua 比较删除保留（既有正确路径不动）。

## 非功能需求

无运行时验证（编译 + 单测）；集群基线（route/online Lua 同 {u:id} tag 保持）；无新增外部依赖（不引入 XXL）。
