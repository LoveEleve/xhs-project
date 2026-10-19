-- 订单终结墓碑标记 Lua 脚本（04B；C-008 单键家族）
--
-- 语义：原子执行「检查预扣记录存在性 + 无条件写终结墓碑」。
-- 「总是写墓碑」是二次 Review（S1）结论：若仅在记录存在时写，会出现
-- "先释放完（记录已删）→ 迟到预扣到达（无墓碑拦截）→ 预扣成功"的泄漏窗口。
-- 全部 KEYS 同 {skuId} hash tag → Cluster 单 slot。
--
-- KEYS[1] = inventory:{skuId}:prededuct:{orderId} (String: 预扣数量)
-- KEYS[2] = inventory:{skuId}:terminated:{orderId} (String: 终结墓碑)
-- ARGV[1] = tombstoneTtlSeconds
--
-- 返回值：
--   1 : 预扣记录存在（调用方继续释放流程）
--   0 : 无预扣记录（已释放/已确认/从未预扣；仅写墓碑后结束）

local exists = redis.call('EXISTS', KEYS[1])
redis.call('SET', KEYS[2], '1', 'EX', tonumber(ARGV[1]))
return exists
