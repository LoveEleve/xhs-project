-- 释放库存 Lua 脚本（原子操作）
--
-- 取消订单或预扣超时时，将预扣的库存回退到对应桶。
-- 由于预扣记录中没有记录"从哪个桶扣的"，回退时统一回退到桶0。
-- 这不影响正确性（总库存一致），只是桶间分布可能略有偏差，
-- 对账任务会定期重新均衡。
--
-- KEYS[1] = inventory:total:{skuId}          (String: 总可用库存)
-- KEYS[2] = inventory:prededuct:{orderId}    (Hash: 预扣记录)
--
-- ARGV[1] = skuId
-- ARGV[2] = orderId
--
-- 返回值：
--   > 0 : 释放成功，返回释放的数量
--   0   : 预扣记录不存在（已释放或已确认）
--

local totalKey = KEYS[1]
local predeductKey = KEYS[2]

local skuId = ARGV[1]
local orderId = ARGV[2]

-- 1. 获取预扣数量
local quantity = redis.call('HGET', predeductKey, skuId)
if not quantity then
    return 0  -- 预扣记录不存在
end

local qty = tonumber(quantity)

-- 2. 回退库存到桶0（简化处理，对账任务会重新均衡）
local bucket0Key = 'inventory:bucket:' .. skuId .. ':0'
redis.call('INCRBY', bucket0Key, qty)

-- 3. 回退总库存
redis.call('INCRBY', totalKey, qty)

-- 4. 删除预扣记录
redis.call('HDEL', predeductKey, skuId)

-- 5. 如果预扣记录 Hash 为空，删除整个 Key
local remaining = redis.call('HLEN', predeductKey)
if remaining == 0 then
    redis.call('DEL', predeductKey)
end

return qty
