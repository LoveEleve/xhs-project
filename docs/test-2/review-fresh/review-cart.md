# Cart 模块 Review

## 数据可靠性（核心关注）
1. **[高] 声称"Redis 丢失后以 MySQL 为准恢复"但未实现** (CartReconcileJob.java:34-37 注释 vs 实际)
   - 读路径 getCartList 只读 Redis (CartService.java:293-440)，从不读 MySQL。
   - CartReconcileJob 只做 Redis→MySQL 单向修复（INSERT/UPDATE/DELETE MySQL），方向相反。
   - 若 Redis 被 flush/主从切换无 AOF 丢失 → 用户购物车显示为空，MySQL 兜底数据永远读不到。
   - **结论**：MySQL 是"只写备份"，Redis 丢失后无法恢复。要么在 Redis miss 时回退读 MySQL 重建 Redis，
     要么接受"Redis 为唯一真相，丢失即丢车"。当前注释与实际行为矛盾，需澄清或补齐恢复路径。

2. **[中] 对账以 MySQL userId 集为枚举源** (CartReconcileJob.java:86)
   只遍历 MySQL 中已有的 userId；"Redis 有+MySQL 无"的场景 1 对"从未进过 MySQL 的用户"无效
   （其 Redis 购物车永远不会被补录到 MySQL）。读取以 Redis 为准故功能正常，但 MySQL 备份不完整。

3. **[中] MQ 同步失败静默吞掉** (CartService.java:599-634)
   写操作后 sendCartSyncEvent 仅记日志，MQ 长期不可用 → MySQL 长期陈旧，依赖每日对账兜底（非实时）。

## 正确性 / 竞态
4. **[低] clearCart 与并发 addToCart 竞态** (CartService.java:527-539)
   `delete(keys)` 与发送 CLEAR 事件非原子；delete 后、CLEAR 消息消费前若用户重新加购，
   CLEAR 消费可能误删新行。Consumer 有 updatedAt 时间戳保护 (CartSyncConsumer.java:177-186)，
   但 Redis 侧清空是立即的。属轻微窗口。

5. **[低] refreshTTL 3 次独立 expire** (CartService.java:584-588)
   可合并为 pipeline；非集群场景 3 次 RTT。

6. **addToCart 不校验 SKU 存在/上架**，无效/下架 SKU 直接入 Redis，读时标记 invalid。可接受但会积累脏数据。

## 分布式设计（做得好的）
- Lua 脚本原子化三结构操作（add/remove/check/update/merge）—— TOCTOU 防护到位。
- 事件时间戳单调递增（EVENT_SEQ）+ Consumer 侧时间戳乱序保护（C-05）—— MQ 乱序/重试安全。
- 对账防"双份全丢"：itemsKey 缺失时跳过删除场景 (CartReconcileJob.java:125-130)—— 好。
- Redis key 用 {userId} hash tag 保证 cluster 同 slot —— 好。
