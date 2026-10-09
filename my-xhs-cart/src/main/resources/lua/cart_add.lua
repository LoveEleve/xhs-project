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
-- ARGV[6] = ttlSeconds (购物车 Key 滑动 TTL 秒数；脚本内设置，避免创建成功但 Java 侧刷新失败导致 Key 永不过期)
--
-- 返回值：
--   > 10000 : 操作成功且为新商品（实际数量 = 返回值 - 10000，调用方发 ADD 事件 checked=1）
--   1~10000 : 操作成功且为已存在商品（返回当前数量，调用方发 UPDATE 事件 checked=null 不改勾选）
--   -1      : 购物车已满（新商品才检查）
-- T-107（2026-08-15）：新增 10000 标志位——修复"已存在商品加购时 ADD 事件 checked 恒传 1
--   导致 MySQL checked 被强制改 1 而 Redis 保持原勾选态"的不一致（与 merge_item.lua 语义对齐）

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

-- 6.5 滑动 TTL：在脚本内设置（原子且不受 Java 侧刷新失败影响）
if tonumber(ARGV[6]) and tonumber(ARGV[6]) > 0 then
    redis.call('EXPIRE', itemsKey, ARGV[6])
    redis.call('EXPIRE', checkedKey, ARGV[6])
    redis.call('EXPIRE', sortKey, ARGV[6])
end

-- 7. 新商品加 10000 标志（T-107），调用方据此决定 checked 事件值
if exists == 0 then
    return newQuantity + 10000
end

return newQuantity
