-- 分桶预扣减 Lua 脚本（原子操作）
--
-- 核心逻辑：
-- 0. 终结墓碑检查（04B）：订单已终结 → 拒绝预扣（拦截迟到/重投消息，防"释放后泄漏"）
-- 1. 按 userId 路由到固定桶，检查库存是否充足
-- 2. 路由桶不足时，遍历其他桶尝试扣减（桶间均衡）
-- 3. 扣减成功后写预扣记录（Hash: {skuId}=quantity, {skuId}:bucket=sourceBucket, 过期）
-- 4. 同步更新总库存
--
-- 【修复M14】所有被操作的 Key 均通过 KEYS 参数传入（Cluster 兼容）
-- 建议使用 {skuId} 作为 hash tag：inventory:{skuId}:total / inventory:{skuId}:bucket:N / inventory:{skuId}:prededuct:{orderId}
--
-- 【04B】KEYS[1] 新增终结墓碑（前置检查）；其余 KEYS 顺延一位。索引 ZSet 仍为最后一个 KEY。
--
-- KEYS[1] = inventory:terminated:{orderId}  (String: 终结墓碑；订单终结后写入，拦截迟到预扣)
-- KEYS[2] = inventory:{skuId}:total             (String: 总可用库存)
-- KEYS[3] = inventory:prededuct:{orderId}  (Hash: 预扣记录)
-- KEYS[4..N+3] = inventory:{skuId}:bucket:0 ~ inventory:{skuId}:bucket:(N-1) (String: 各桶库存)
-- KEYS[#KEYS] = inventory:prededuct:index    (ZSet: 预扣索引, member=orderId, score=过期时间戳ms)
--                                             供超时回退 Job ZRANGEBYSCORE 查询，替代全库 SCAN
--
-- ARGV[1] = skuId
-- ARGV[2] = orderId
-- ARGV[3] = quantity (扣减数量)
-- ARGV[4] = bucketCount (分桶数)
-- ARGV[5] = userId (备用)
-- ARGV[6] = routeBucket (T-073: Java 侧精确取模桶号; Lua 双精度对 >2^53 ID 取模失真恒偏)
-- ARGV[7] = expireSeconds (预扣记录过期时间)
-- ARGV[8] = recordTtlSeconds (预扣记录物理 TTL；= expireSeconds + 缓冲，
--            保证超时任务"到期后"仍能读到记录做回退——原先 TTL==score，
--            任务只能提前 60s 释放，窗口内支付确认会找不到记录)
--
-- 返回值：
--   1  : 扣减成功
--   0  : 库存不足
--   -1 : 重复预扣（orderId 已存在预扣记录）
--   -2 : 库存未初始化
--   -3 : 订单已终结（终结墓碑命中，拒绝预扣）

local terminatedKey = KEYS[1]
local totalKey = KEYS[2]
local predeductKey = KEYS[3]
local indexKey = KEYS[#KEYS]  -- 最后一个 KEY 固定为索引 ZSet

local skuId = ARGV[1]
local orderId = ARGV[2]
local quantity = tonumber(ARGV[3])
local bucketCount = tonumber(ARGV[4])
local expireSeconds = tonumber(ARGV[7])
local recordTtlSeconds = tonumber(ARGV[8] or ARGV[7])
local routeBucket = tonumber(ARGV[6])

-- 0. 终结墓碑检查（04B）：订单已终结（取消/超时/库存失败/券失败）→ 拒绝迟到/重投的预扣。
--    与 releaseStock 的"总是写墓碑"配对：释放完成后墓碑仍在，拦截随后到达的预扣消息。
local terminated = redis.call('EXISTS', terminatedKey)
if terminated == 1 then
    return -3  -- 订单已终结
end

-- 1. 幂等检查：同一订单不能重复预扣
local existingQty = redis.call('HGET', predeductKey, skuId)
if existingQty then
    return -1  -- 重复预扣
end

-- 2. 检查总库存是否初始化
local totalStock = redis.call('GET', totalKey)
if not totalStock then
    return -2  -- 库存未初始化
end

-- 3. 快速检查总库存是否充足（避免无意义的桶遍历）
if tonumber(totalStock) < quantity then
    return 0  -- 库存不足
end

-- 计算过期时间戳（毫秒），用于 ZSet 索引 score
local time = redis.call('TIME')
local expireAtMs = time[1] * 1000 + expireSeconds * 1000

-- 4. 路由桶号（T-073：Java 侧精确取模传入，Lua 双精度对 >2^53 ID 取模失真）
local routeKeyIdx = 4 + routeBucket  -- KEYS 数组下标（Lua 从 1 开始；04B 起 KEYS 前移一位）

-- 5. 尝试从路由桶扣减
local routeStock = tonumber(redis.call('GET', KEYS[routeKeyIdx]) or '0')
if routeStock >= quantity then
    redis.call('DECRBY', KEYS[routeKeyIdx], quantity)
    redis.call('DECRBY', totalKey, quantity)
    redis.call('HSET', predeductKey, skuId, quantity)
    redis.call('HSET', predeductKey, skuId .. ':bucket', routeBucket)
    redis.call('EXPIRE', predeductKey, recordTtlSeconds)
    redis.call('ZADD', indexKey, expireAtMs, orderId)
    return 1  -- 成功（路由桶扣减）
end

-- 6. 路由桶不足，遍历其他桶
for offset = 1, bucketCount - 1 do
    local i = (routeBucket + offset) % bucketCount
    local otherKeyIdx = 4 + i
    local otherStock = tonumber(redis.call('GET', KEYS[otherKeyIdx]) or '0')
    if otherStock >= quantity then
        redis.call('DECRBY', KEYS[otherKeyIdx], quantity)
        redis.call('DECRBY', totalKey, quantity)
        redis.call('HSET', predeductKey, skuId, quantity)
        redis.call('HSET', predeductKey, skuId .. ':bucket', i)
        redis.call('EXPIRE', predeductKey, recordTtlSeconds)
        redis.call('ZADD', indexKey, expireAtMs, orderId)
        return 1  -- 成功（从其他桶扣减）
    end
end

return 0  -- 全部桶库存不足
