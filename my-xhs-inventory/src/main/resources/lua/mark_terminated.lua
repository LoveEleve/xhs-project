-- 订单终结墓碑标记 Lua 脚本（04B）
--
-- 语义：原子执行「检查预扣记录存在性 + 无条件写终结墓碑」。
-- 「总是写墓碑」是二次 Review（S1）结论：若仅在记录存在时写，会出现
-- "先释放完（记录已删）→ 迟到预扣到达（无墓碑拦截）→ 预扣成功"的泄漏窗口；
-- 墓碑写入与存在性检查必须在同一脚本内原子完成，否则"检查通过后、写墓碑前"
-- 仍存在并发窗口。
--
-- 配套：
-- - releaseStock 入口调用本脚本（释放 + 写墓碑一步完成，重复投递安全）
-- - prededuct.lua 入口检查墓碑（命中返回 -3，拒绝预扣）
--
-- KEYS[1] = inventory:prededuct:{orderId}   (Hash: 预扣记录)
-- KEYS[2] = inventory:terminated:{orderId}  (String: 终结墓碑)
-- ARGV[1] = tombstoneTtlSeconds (墓碑 TTL；须 ≥ 预扣消息可能迟到的最晚时间)
--
-- 返回值：
--   1 : 预扣记录存在（调用方继续释放流程）
--   0 : 无预扣记录（已释放/已确认/从未预扣；仅写墓碑后结束）

local exists = redis.call('EXISTS', KEYS[1])
redis.call('SET', KEYS[2], '1', 'EX', tonumber(ARGV[1]))
return exists
