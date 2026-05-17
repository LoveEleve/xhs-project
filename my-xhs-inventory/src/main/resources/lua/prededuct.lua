-- 分桶预扣减 Lua 脚本（原子操作）
--
-- 核心逻辑：
-- 1. 按 userId 路由到固定桶，检查库存是否充足
-- 2. 路由桶不足时，遍历其他桶尝试扣减（桶间均衡）
-- 3. 扣减成功后写预扣记录（Hash，30分钟过期）
-- 4. 同步更新总库存
--
-- KEYS[1] = inventory:total:{skuId}          (String: 总可用库存)
-- KEYS[2] = inventory:prededuct:{orderId}    (Hash: 预扣记录)
--
-- ARGV[1] = skuId
-- ARGV[2] = orderId
-- ARGV[3] = quantity (扣减数量)
-- ARGV[4] = bucketCount (分桶数)
-- ARGV[5] = userId (用于路由)
-- ARGV[6] = expireSeconds (预扣记录过期时间)
--
-- 返回值：
--   1  : 扣减成功
--   0  : 库存不足
--   -1 : 重复预扣（orderId 已存在预扣记录）
--   -2 : 库存未初始化
--

local totalKey = KEYS[1]
local predeductKey = KEYS[2]

local skuId = ARGV[1]
local orderId = ARGV[2]
local quantity = tonumber(ARGV[3])
local bucketCount = tonumber(ARGV[4])
local userId = tonumber(ARGV[5])
local expireSeconds = tonumber(ARGV[6])

-- 0. 幂等检查：同一订单不能重复预扣
local existingQty = redis.call('HGET', predeductKey, skuId)
if existingQty then
    return -1  -- 重复预扣
end

-- 1. 检查总库存是否初始化
local totalStock = redis.call('GET', totalKey)
if not totalStock then
    return -2  -- 库存未初始化
end

-- 2. 快速检查总库存是否充足（避免无意义的桶遍历）
if tonumber(totalStock) < quantity then
    return 0  -- 库存不足
end

-- 3. 计算路由桶号
local routeBucket = userId % bucketCount
local bucketKeyPrefix = 'inventory:bucket:' .. skuId .. ':'

-- 4. 尝试从路由桶扣减
local routeKey = bucketKeyPrefix .. routeBucket
local routeStock = tonumber(redis.call('GET', routeKey) or '0')
if routeStock >= quantity then
    redis.call('DECRBY', routeKey, quantity)
    redis.call('DECRBY', totalKey, quantity)
    redis.call('HSET', predeductKey, skuId, quantity)
    redis.call('EXPIRE', predeductKey, expireSeconds)
    return 1  -- 成功（路由桶扣减）
end

-- 5. 路由桶不足，遍历其他桶（桶间均衡）
-- 遍历顺序：从 (routeBucket+1) 开始，避免所有请求都涌向桶0
for offset = 1, bucketCount - 1 do
    local i = (routeBucket + offset) % bucketCount
    local otherKey = bucketKeyPrefix .. i
    local otherStock = tonumber(redis.call('GET', otherKey) or '0')
    if otherStock >= quantity then
        redis.call('DECRBY', otherKey, quantity)
        redis.call('DECRBY', totalKey, quantity)
        redis.call('HSET', predeductKey, skuId, quantity)
        redis.call('EXPIRE', predeductKey, expireSeconds)
        return 1  -- 成功（从其他桶扣减）
    end
end

return 0  -- 全部桶库存不足
