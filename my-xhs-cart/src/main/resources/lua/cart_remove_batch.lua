-- 批量删除购物车商品 Lua 脚本（C-006：下单清车）
-- 原子操作：按多个 skuId 批量删除三结构（Hash + Set + ZSet）
--
-- KEYS[1] = myxhs:cart:{userId}:items    (Hash)
-- KEYS[2] = myxhs:cart:{userId}:checked  (Set)
-- KEYS[3] = myxhs:cart:{userId}:sort     (ZSet)
--
-- ARGV[1..N] = skuId 列表
--
-- 返回值：实际删除的商品数（不存在的忽略——幂等）
--

local itemsKey = KEYS[1]
local checkedKey = KEYS[2]
local sortKey = KEYS[3]

local removed = 0
for i = 1, #ARGV do
    local skuId = ARGV[i]
    if redis.call('HEXISTS', itemsKey, skuId) == 1 then
        redis.call('HDEL', itemsKey, skuId)
        redis.call('SREM', checkedKey, skuId)
        redis.call('ZREM', sortKey, skuId)
        removed = removed + 1
    end
end
return removed
