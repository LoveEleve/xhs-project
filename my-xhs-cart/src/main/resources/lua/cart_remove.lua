-- 删除购物车商品 Lua 脚本
-- 原子操作：三结构同时删除（Hash + Set + ZSet）
--
-- KEYS[1] = cart:items:{userId}    (Hash)
-- KEYS[2] = cart:checked:{userId}  (Set)
-- KEYS[3] = cart:sort:{userId}     (ZSet)
--
-- ARGV[1] = skuId
--
-- 返回值：1=删除成功，0=商品不存在
--

local itemsKey = KEYS[1]
local checkedKey = KEYS[2]
local sortKey = KEYS[3]
local skuId = ARGV[1]

-- 检查商品是否存在
local exists = redis.call('HEXISTS', itemsKey, skuId)
if exists == 0 then
    return 0
end

-- 三结构原子删除
redis.call('HDEL', itemsKey, skuId)
redis.call('SREM', checkedKey, skuId)
redis.call('ZREM', sortKey, skuId)

return 1
