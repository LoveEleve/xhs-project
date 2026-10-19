-- 确认扣减 Lua 脚本（原子操作；C-008 单键家族）
--
-- 支付成功后，删除预扣记录（库存已在预扣时扣减，确认只需撕欠条）。
-- 全部 KEYS 同 {skuId} hash tag → Cluster 单 slot。
--
-- KEYS[1] = inventory:{skuId}:prededuct:{orderId} (String: 预扣数量)
-- KEYS[2] = inventory:{skuId}:prededuct:index     (ZSet: 移除 member)
--
-- ARGV[1] = orderId
--
-- 返回值：
--   > 0 : 确认成功，返回确认的数量
--   0   : 预扣记录不存在

local quantity = redis.call('GET', KEYS[1])
if not quantity then
    return 0
end
redis.call('DEL', KEYS[1])
redis.call('ZREM', KEYS[2], ARGV[1])
return tonumber(quantity)
