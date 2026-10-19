---
iteration: ITER-013
status: confirmed
version: 1
created: 2026-10-19
updated: 2026-10-19
---

# 技术方案: 私信模块生产化改造

## 方案概述

```
可靠性收敛（核心）：
  handleChat 后段重排（F1/D3）：
    [锁内] resolveConversationId → seqNo INCR → [clientMsgId SETNX 幂等] → persist(事务)
    [锁外] ① 未读 +1（前移；异常降级 warn）
           ② storeOfflineMessage（异常降级 warn——仍继续推送）
           ③ 路由（本实例直推 / Pub/Sub 定向；异常降级 warn）
           ④ ACK 必达（消息已持久化——路由/Redis 降级也 ACK，防发送方重发重复）
    失败删标记：锁内 persist 失败 → 删 clientMsgId 标记（重试可重处理）
  未读一致性（D1/D2）：
    softDeleteConversation SQL 增 unread_count=0（僵尸未读根治）
    afterConnectionEstablished → refreshUnreadCache(userId)：
      DB（is_deleted=0 的 relation unread）→ 重建 Redis hash（空则删键）+ EXPIRE 30d
    增量路径 HINCRBY + EXPIRE 单脚本（滑动 TTL）
  handleRead 降级（D4）：Redis 清零异常仅 warn，READ_NOTIFY 继续
路由注册（F2/F3）：
  registerRoute → Lua：local old=GET route; SET route ARGV[1] EX ARGV[2]; SET online ...; return old
    （单 slot：{u:id} tag；F5 移除 online 后简化为单键）
  连接上限：sessions.size() >= MAX && !sessions.containsKey(userId) 才拒绝
发送侧幂等（F6）：
  ImMessage + clientMsgId（可选）；锁内 SETNX myxhs:im:msgdedup:{senderId}:{clientMsgId} = msgId（EX 300）
    已存在 → ACK 原 msgId（不重复落库/路由）；persist 失败 → DEL 标记

工程对齐：
  F5：删 ImConsistentHashLoadBalancer（未接线）/ isOnline() + online 键族（零消费者，Lua 去 KEYS[2]）/
      ChatMessage.isRead 实体字段（DDL 列留注）
  F4/D6：注释 ×7（ChatService 1+2、conversationId 雪花口径、RouteMessage 97、ImMessageVO msgType、
      IM_JWT_SECRET 幽灵、seq 无 TTL 说明等）
  D5：SQL 4 副本同步（conversation_id 注释 + idx_user_updated）
  限流：/ws/ticket 10/min、conversations 30/min、messages 60/min、unread-count 60/min（perUser）
```

三阶段：

| 阶段 | 内容 | 独立价值 |
|---|---|---|
| ① 可靠性收敛 | REQ-1（未读 F1/D1/D2）+ REQ-2（D3/D4）+ REQ-3（F2/F3）+ REQ-4（F6） | 未读收敛 + ACK 必达 + 注册原子 |
| ② 工程对齐 | REQ-5 注释/SQL + REQ-6 死物/限流 | 水准对齐 |
| ③ 收口 | REQ-7 测试集 + 回归 + 回写 | 闭环 |

## REQ → 任务映射

### 阶段① 可靠性收敛

| 任务 | 文件 | 要点 |
|------|------|------|
| T1.1 未读前移 + 降级 + ACK 必达 | `ChatService.handleChat` | 后段重排（①未读 ②离线 ③路由 ④ACK）；各步 try/catch 降级；锁内失败删 clientMsgId 标记 |
| T1.2 软删清零 | `ChatUserRelationMapper.softDeleteConversation` | SQL 增 `unread_count = 0` |
| T1.3 连接重建未读 | `ChatService.refreshUnreadCache` + `ImWebSocketHandler.afterConnectionEstablished` | DB→Redis 重建 + EXPIRE 30d；空则删键 |
| T1.4 未读 TTL 脚本 | `ChatService` | HINCRBY + EXPIRE 单脚本（静态） |
| T1.5 READ 降级 | `ChatService.handleRead` | Redis put try/catch 继续通知 |
| T1.6 register Lua | `OnlineRouteService.registerRoute` | 单脚本原子（含 F5 移除 online 键的简化版） |
| T1.7 上限豁免 | `ImWebSocketHandler.afterConnectionEstablished` | containsKey 豁免 |
| T1.8 clientMsgId 幂等 | `ImMessage` + `ChatService.handleChat` | SETNX 映射 + 重放 ACK + 失败删标记 |

### 阶段② 工程对齐

| 任务 | 文件 | 要点 |
|------|------|------|
| T2.1 死物移除 | `ImConsistentHashLoadBalancer`（删）/`OnlineRouteService`/`ChatMessage` | LB 删；isOnline + online 键族删（renew/unregister Lua 简化）；isRead 字段删（DDL 留注） |
| T2.2 注释 ×7 | ChatService/ChatMessage/RouteMessage/ImMessageVO/ImController/OnlineRouteService | 逐条对齐代码事实 |
| T2.3 SQL 副本同步 | `sql/migration/user/V1__init_user.sql` + `sql/mysql-user-init.sql` + deploy ×2 | conversation_id 注释 + idx_user_updated |
| T2.4 限流 | `ImController` | 4 端点 @RateLimit |

### 阶段③ 收口

测试集（从零，9 类 ~40 用例）→ 十三模块回归 → §8 回写 + 09-IM 面试材料同步 → change done。

## 关键决策与理由

1. **连接时重建替代对账 Job（D2）**：im 无 XXL 基建；连接事件是天然收敛点（每用户每次上线重算一次，一次索引查询）——"轻对账"；窗口内竞态少计 1 下次连接收敛（文档明示）；
2. **ACK 必达（D3）**：消息已持久化（DB 权威）→ 路由/离线/未读失败不该让发送方重发（无 clientMsgId 时会重复落库）——与 C-012 F3"能收敛的宁可吞"同原则；
3. **未读前移（F1）**：前移后 READ 必然晚于 +1（接收方要收到推送才可能 READ）→ 幽灵窗口消除；
4. **clientMsgId 可选（F6）**：协议向后兼容（不传=旧行为）；映射值=msgId 使重放可直接 ACK 原 msgId；失败删标记对齐家族"removeMark"契约；
5. **online 键移除（F5）**：零外部消费者（全仓 grep 验证）+ isOnline 零调用 → 死代码族；Lua 从双键简化单键；
6. **seq 键不加 TTL**：过期重置会破坏会话内排序（新消息 seq 从 1 开始，历史排序错乱）；正确演进=锁内 DB 播种（max(seq_no)+1），记录不做。
