-- 释放库存 Lua 脚本（原子操作）
--
-- 取消订单或预扣超时时，将预扣的库存回退到来源桶。
--
-- 【修复M14】桶 Key 由调用方传入 KEYS[3]，不再在脚本内动态拼接（Cluster 兼容）。
-- 调用方需要先 HGET predeductKey {skuId}:bucket 获取桶号，再构建桶 Key 传入。
--
-- KEYS[1] = inventory:{skuId}:total               (String: 总可用库存)
-- KEYS[2] = inventory:{skuId}:prededuct:{orderId} (Hash: 预扣记录)
-- KEYS[3] = inventory:{skuId}:bucket:{bucketNo}   (String: 来源桶库存)
--
-- ARGV[1] = skuId
--
-- 返回值：
--   > 0 : 释放成功，返回释放的数量
--   0   : 预扣记录不存在（已释放或已确认）

local totalKey = KEYS[1]
local predeductKey = KEYS[2]
local bucketKey = KEYS[3]

local skuId = ARGV[1]

-- 1. 获取预扣数量
local quantity = redis.call('HGET', predeductKey, skuId)
if not quantity then
    return 0  -- 预扣记录不存在
end

local qty = tonumber(quantity)

-- 2. 回退库存到来源桶
redis.call('INCRBY', bucketKey, qty)

-- 3. 回退总库存
redis.call('INCRBY', totalKey, qty)

-- 4. 删除预扣记录
redis.call('HDEL', predeductKey, skuId)
redis.call('HDEL', predeductKey, skuId .. ':bucket')

-- 5. 如果预扣记录 Hash 为空，删除整个 Key
local remaining = redis.call('HLEN', predeductKey)
if remaining == 0 then
    redis.call('DEL', predeductKey)
end

return qty
