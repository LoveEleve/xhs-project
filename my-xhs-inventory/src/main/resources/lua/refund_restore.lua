-- 退款回补 Lua 脚本（原子操作；C-008 新增）
--
-- 累计幂等 + 库存回补**单脚本原子**（同 {skuId} slot）：
-- 修复旧实现"先推进累计键、再加库存"的崩溃窗口（kill -9 → 累计键=target 但库存未加
-- → 重试 delta=0 静默跳过 → 永久漏补）。
--
-- 语义：累计键记录"已受理的应回补数量"（target），仅回补正增量——
-- - 重复调用 / Feign 重试：target 不变 → delta=0 → 跳过（幂等）
-- - 分次售后：先退 1 补 1，再退剩余 2 补 2（不漏补）
-- - 售后与全额退款路径叠加：任一先到，后到者 delta=0（不重复补）
--
-- KEYS[1] = inventory:{skuId}:refund:{orderId}  (String: 累计已受理数量)
-- KEYS[2] = inventory:{skuId}:stock             (String: 可用库存)
--
-- ARGV[1] = target (本次请求的累计应回补数量)
-- ARGV[2] = ttlSeconds (累计键 TTL)
--
-- 返回值：
--   > 0 : 本次回补增量（库存已 INCRBY）
--   0   : 幂等跳过（target <= 已受理）
--   -2  : 库存键缺失（不动累计键，调用方重建/补偿）

local cur = tonumber(redis.call('GET', KEYS[1]) or '0')
local target = tonumber(ARGV[1])
if target <= cur then
    return 0
end
if redis.call('EXISTS', KEYS[2]) == 0 then
    return -2
end
local delta = target - cur
redis.call('SET', KEYS[1], tostring(target), 'EX', tonumber(ARGV[2]))
redis.call('INCRBY', KEYS[2], delta)
return delta
