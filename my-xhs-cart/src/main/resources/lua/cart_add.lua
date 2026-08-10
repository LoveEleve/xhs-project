-- 加入购物车 Lua 脚本
-- 原子操作：检查上限 + HINCRBY 累加 + 截断上限 + SADD 选中 + ZADD 排序
--
-- KEYS[1] = myxhs:cart:{userId}:items    (Hash)
-- KEYS[2] = myxhs:cart:{userId}:checked  (Set)
-- KEYS[3] = myxhs:cart:{userId}:sort     (ZSet)
--
-- ARGV[1] = skuId
-- ARGV[2] = quantity (增加的数量)
-- ARGV[3] = maxCartSize (购物车上限，如 50)
-- ARGV[4] = maxItemQuantity (单品上限，如 99)
-- ARGV[5] = timestamp (加购时间戳)
--
-- 返回值：
--   > 0 : 操作成功，返回当前数量
--   -1  : 购物车已满（新商品才检查）
--

local itemsKey = KEYS[1]
local checkedKey = KEYS[2]
local sortKey = KEYS[3]

local skuId = ARGV[1]
local quantity = tonumber(ARGV[2])
local maxCartSize = tonumber(ARGV[3])
local maxItemQuantity = tonumber(ARGV[4])
local timestamp = tonumber(ARGV[5])

-- 1. 检查商品是否已存在
local exists = redis.call('HEXISTS', itemsKey, skuId)

-- 2. 新商品才检查购物车上限
if exists == 0 then
    local currentSize = redis.call('HLEN', itemsKey)
    if currentSize >= maxCartSize then
        return -1  -- 购物车已满
    end
end

-- 3. HINCRBY 累加数量（原子操作）
local newQuantity = redis.call('HINCRBY', itemsKey, skuId, quantity)

-- 4. 单品上限截断
if newQuantity > maxItemQuantity then
    redis.call('HSET', itemsKey, skuId, maxItemQuantity)
    newQuantity = maxItemQuantity
end

-- 5. 新商品默认选中（已存在商品保持原有勾选状态，与 merge 语义对齐）
if exists == 0 then
    redis.call('SADD', checkedKey, skuId)
end

-- 6. 记录加购时间（仅新商品记录，NX 语义）
if exists == 0 then
    redis.call('ZADD', sortKey, 'NX', timestamp, skuId)
end

return newQuantity
