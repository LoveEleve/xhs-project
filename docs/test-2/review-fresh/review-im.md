# IM 模块 Review

## 业务逻辑（重点）
1. **[高] generateConversationId = min*31 + max 存在确定性碰撞 → 会话数据串台**
   (ChatService.java:446-450)
   `min*31+max` 非单射：不同用户对可算出同一 conversationId（已计算验证：
   (1,34)→65 与 (2,3)→65 同值；海量真实 ID 下同样存在大量碰撞）。
   后果：conversationId 既作 DB 分片/持久化键，又用于 getMessageHistory 查询
   (ChatService.java:382-389)，碰撞的两对用户会**共享同一会话历史/未读数**，聊天内容串台。
   建议改为不碰撞的编码：`min << 20 | max` 之类（需保证 max<2^20）或存显式会话表+唯一ID，
   不要用线性哈希。

## 一致性
2. **[中] 已读/未读 DB 与 Redis 非原子** (handleRead:204-219, markAllRead:394-407)
   DB unread update + Redis hash 清零分步，极端下漂移；且 Redis 为权威、DB 降级源，语义需明确。

## 分布式 / 可靠性
3. **跨实例投递用 Redis Pub/Sub 定向**：本实例直推 / 跨实例 PubSub / 离线暂存三分支 —— 设计合理。
   离线消息存 msgId 于 SortedSet + 上线批量拉取 + 客户端 ACK ZREM —— 高效。
4. **[低] 心跳 TTL = 3× 心跳间隔，网络抖动易误判离线**（可接受，靠心跳续期）。
5. **WS 握手用短期 JWT ticket(ws_ticket, 5min) 验签** —— 安全，不暴露长期 token。好。
6. 在线路由注销用 Lua 原子比较 serverId —— 防误删，好。

## 工程
7. setter + @Lazy 注入打破 ChatService↔Handler 循环依赖 —— 可接受但略绕。
8. 消息超长截断 2000、SeqNo Redis INCR 保序 —— 合理。
