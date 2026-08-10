-- 分桶预扣减 Lua 脚本（原子操作）
--
-- 核心逻辑：
-- 1. 按 userId 路由到固定桶，检查库存是否充足
-- 2. 路由桶不足时，遍历其他桶尝试扣减（桶间均衡）
-- 3. 扣减成功后写预扣记录（Hash: {skuId}=quantity, {skuId}:bucket=sourceBucket, 过期）
-- 4. 同步更新总库存
--
-- 【修复M14】所有被操作的 Key 均通过 KEYS 参数传入（Cluster 兼容）
-- 建议使用 {skuId} 作为 hash tag：inventory:{skuId}:total / inventory:{skuId}:bucket:N / inventory:{skuId}:prededuct:{orderId}
--
-- KEYS[1] = inventory:{skuId}:total             (String: 总可用库存)
-- KEYS[2] = inventory:prededuct:{orderId}  (Hash: 预扣记录)
-- KEYS[3..N+2] = inventory:{skuId}:bucket:0 ~ inventory:{skuId}:bucket:(N-1) (String: 各桶库存)
-- KEYS[N+3] = inventory:prededuct:index    (ZSet: 预扣索引, member=orderId, score=过期时间戳ms)
--                                             供超时回退 Job ZRANGEBYSCORE 查询，替代全库 SCAN
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

local totalKey = KEYS[1]
local predeductKey = KEYS[2]
local indexKey = KEYS[#KEYS]  -- 最后一个 KEY 固定为索引 ZSet

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

-- 计算过期时间戳（毫秒），用于 ZSet 索引 score
local time = redis.call('TIME')
local expireAtMs = time[1] * 1000 + expireSeconds * 1000

-- 3. 计算路由桶号（桶 Key 从 KEYS[3] 开始）
local routeBucket = userId % bucketCount
local routeKeyIdx = 3 + routeBucket  -- KEYS 数组下标（Lua 从 1 开始）

-- 4. 尝试从路由桶扣减
local routeStock = tonumber(redis.call('GET', KEYS[routeKeyIdx]) or '0')
if routeStock >= quantity then
    redis.call('DECRBY', KEYS[routeKeyIdx], quantity)
    redis.call('DECRBY', totalKey, quantity)
    redis.call('HSET', predeductKey, skuId, quantity)
    redis.call('HSET', predeductKey, skuId .. ':bucket', routeBucket)
    redis.call('EXPIRE', predeductKey, expireSeconds)
    redis.call('ZADD', indexKey, expireAtMs, orderId)
    return 1  -- 成功（路由桶扣减）
end

-- 5. 路由桶不足，遍历其他桶
for offset = 1, bucketCount - 1 do
    local i = (routeBucket + offset) % bucketCount
    local otherKeyIdx = 3 + i
    local otherStock = tonumber(redis.call('GET', KEYS[otherKeyIdx]) or '0')
    if otherStock >= quantity then
        redis.call('DECRBY', KEYS[otherKeyIdx], quantity)
        redis.call('DECRBY', totalKey, quantity)
        redis.call('HSET', predeductKey, skuId, quantity)
        redis.call('HSET', predeductKey, skuId .. ':bucket', i)
        redis.call('EXPIRE', predeductKey, expireSeconds)
        redis.call('ZADD', indexKey, expireAtMs, orderId)
        return 1  -- 成功（从其他桶扣减）
    end
end

return 0  -- 全部桶库存不足
