-- 确认扣减 Lua 脚本（原子操作）
--
-- 支付成功后，删除预扣记录（库存已经在预扣时扣减了，确认只需删除预扣记录）。
-- Redis 层面的库存不需要再变动，因为预扣时已经 DECRBY 了。
-- MySQL 层面的 locked_stock → 0 由 MQ Consumer 处理。
--
-- KEYS[1] = inventory:prededuct:{orderId}    (Hash: 预扣记录)
-- KEYS[2] = inventory:prededuct:index      (ZSet: 预扣索引, 记录全删时 ZREM orderId)
--
-- ARGV[1] = skuId
-- ARGV[2] = orderId
--
-- 返回值：
--   > 0 : 确认成功，返回确认的数量
--   0   : 预扣记录不存在
--

local predeductKey = KEYS[1]
local indexKey = KEYS[2]
local skuId = ARGV[1]
local orderId = ARGV[2]

-- 1. 获取预扣数量
local quantity = redis.call('HGET', predeductKey, skuId)
if not quantity then
    return 0  -- 预扣记录不存在
end

local qty = tonumber(quantity)

-- 2. 删除预扣记录（确认扣减 = 预扣变为正式扣减）
redis.call('HDEL', predeductKey, skuId)
redis.call('HDEL', predeductKey, skuId .. ':bucket')

-- 3. 如果预扣记录 Hash 为空，删除整个 Key 并从索引 ZSet 移除
local remaining = redis.call('HLEN', predeductKey)
if remaining == 0 then
    redis.call('DEL', predeductKey)
    redis.call('ZREM', indexKey, orderId)
end

return qty
