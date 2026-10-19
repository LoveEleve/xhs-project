-- 释放库存 Lua 脚本（原子操作；C-008 单键家族）
--
-- 取消订单或预扣超时时，将预扣数量回退到库存键。
-- 全部 KEYS 同 {skuId} hash tag → Cluster 单 slot。
--
-- KEYS[1] = inventory:{skuId}:stock               (String: 可用库存)
-- KEYS[2] = inventory:{skuId}:prededuct:{orderId} (String: 预扣数量)
-- KEYS[3] = inventory:{skuId}:prededuct:index     (ZSet: 移除 member)
--
-- ARGV[1] = orderId
--
-- 返回值：
--   > 0 : 释放成功，返回释放的数量
--   0   : 预扣记录不存在（已释放或已确认）

local quantity = redis.call('GET', KEYS[2])
if not quantity then
    return 0
end
local qty = tonumber(quantity)
redis.call('INCRBY', KEYS[1], qty)
redis.call('DEL', KEYS[2])
redis.call('ZREM', KEYS[3], ARGV[1])
return qty
