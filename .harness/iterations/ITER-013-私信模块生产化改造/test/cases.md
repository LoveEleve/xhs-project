---
iteration: ITER-013
status: confirmed
version: 1
created: 2026-10-19
updated: 2026-10-19
---

# 测试用例: 私信模块生产化改造

| TC | 归属 AC | 前置条件 | 输入 | 期望输出 | 自动化 |
|----|---------|---------|------|---------|--------|
| TC-1 | AC-1 | 正常发送 | handleChat | 顺序：未读+1 → 离线 ZADD → 路由 → ACK（InOrder） | 是 |
| TC-2 | AC-1 | — | 软删会话 | SQL 注解含 `unread_count = 0`（反射断言） | 是 |
| TC-3 | AC-1 | DB 有未读 3 条 | refreshUnreadCache | Redis hash 重建 + EXPIRE 30d | 是 |
| TC-4 | AC-1 | DB 无未读 | refreshUnreadCache | Redis 键删除（空则删） | 是 |
| TC-5 | AC-2 | getRoute 抛异常 | handleChat | 仍发 ACK（降级） | 是 |
| TC-6 | AC-2 | storeOffline 抛异常 | handleChat | 仍推送 + ACK | 是 |
| TC-7 | AC-2 | Redis put 抛异常 | handleRead | 对方仍收 READ_NOTIFY | 是 |
| TC-8 | AC-2 | 正常 | handleRead | DB 清零 + Redis 清零 + 通知 | 是 |
| TC-9 | AC-3 | — | registerRoute | 单 Lua 执行 + 返回旧 serverId | 是 |
| TC-10 | AC-3 | 满员 50k + 老用户 | afterConnectionEstablished | 允许（豁免） | 是 |
| TC-11 | AC-3 | 满员 50k + 新用户 | afterConnectionEstablished | 拒绝关闭 | 是 |
| TC-12 | AC-4 | clientMsgId 已存在 | handleChat | ACK 原 msgId；不落库不路由 | 是 |
| TC-13 | AC-4 | clientMsgId 首次 | handleChat | SETNX + 正常流程 | 是 |
| TC-14 | AC-4 | persist 失败 | handleChat | DEL 幂等标记（重试可重处理） | 是 |
| TC-15 | AC-4 | 未传 clientMsgId | handleChat | 跳过幂等（旧行为） | 是 |
| TC-16 | AC-1 | — | 校验路径 | 自聊/空内容/超长截断（代理对不切半）NACK | 是 |
| TC-17 | AC-1 | 锁获取失败 | handleChat | NACK"发送过于频繁" | 是 |
| TC-18 | AC-3 | — | 连接建立 | 本地踢旧 + 跨实例 kickRemote + 重建 + 补发 | 是 |
| TC-19 | AC-3 | — | 关闭事件 | 双参 remove 成功才 unregister；被替换则保留路由 | 是 |
| TC-20 | AC-2 | 跨实例推成功 | Subscriber CHAT | removeOffline 调用 | 是 |
| TC-21 | AC-2 | 跨实例推失败 | Subscriber CHAT | storeOffline 调用；98/99 不降级 | 是 |
| TC-22 | AC-2 | msgType=97 | Subscriber KICK | closeLocalSession | 是 |
| TC-23 | AC-2 | — | 握手 | 类型错/jti 已消费/subject 不一致 → false | 是 |
| TC-24 | AC-6 | — | ticket 签发 | JWT + jti 登记 5min | 是 |
| TC-25 | AC-6 | 演示密钥/空 | validateJwtSecret | 拒绝启动 | 是 |
| TC-26 | AC-1 | — | 补发 | seqNo 升序 + DB 缺失清理 | 是 |
| TC-27 | AC-1 | — | 未读汇总 | Redis 求和；hash 空 → DB fallback；脏值安全 | 是 |
| TC-28 | AC-1 | — | upsert 三路径 | insert / DuplicateKey 降级 / affected=0 补插 | 是 |
| TC-29 | AC-5 | — | grep | 注释 ×7 零漂移；SQL 4 副本一致 | 是 |
| TC-30 | AC-6 | — | grep | LB/isOnline/online 键零残留 | 是 |
| TC-31~40 | 各 AC | 既有路径回归 | 心跳续期/踢线 Lua 比较删除/离线 ACK/输入通知在线判定/长消息截断/JSON 异常容错 等 | 与现语义一致 | 是 |
