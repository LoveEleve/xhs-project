-- 预扣减 Lua 脚本（原子操作；C-008 键模型收敛：单库存键 + per-(sku,order) 记录）
--
-- 全部 KEYS 同 {skuId} hash tag → Redis Cluster 单 slot（消除 CROSSSLOT）
--
-- 核心逻辑：
-- 0. 终结墓碑检查（04B）：订单已终结 → 拒绝预扣（拦截迟到/重投消息）
-- 1. 幂等检查：同一订单同一 SKU 不能重复预扣（记录存在即重复）
-- 2. 库存检查（初始化 + 充足）
-- 3. 扣减 + 记账 + 过期索引（单脚本原子）
--
-- KEYS[1] = inventory:{skuId}:terminated:{orderId}  (String: 终结墓碑)
-- KEYS[2] = inventory:{skuId}:stock                 (String: 可用库存，单键)
-- KEYS[3] = inventory:{skuId}:prededuct:{orderId}   (String: 预扣数量)
-- KEYS[4] = inventory:{skuId}:prededuct:index       (ZSet: member=orderId, score=过期时间戳ms)
--
-- ARGV[1] = orderId
-- ARGV[2] = quantity
-- ARGV[3] = expireSeconds (逻辑过期)
-- ARGV[4] = recordTtlSeconds (物理 TTL = 逻辑过期 + 缓冲；保证超时任务到期后仍能读到记录做回退)
--
-- 返回值：
--   1  : 扣减成功
--   0  : 库存不足
--   -1 : 重复预扣（记录已存在）
--   -2 : 库存未初始化
--   -3 : 订单已终结（终结墓碑命中）

local terminatedKey = KEYS[1]
local stockKey = KEYS[2]
local recordKey = KEYS[3]
local indexKey = KEYS[4]

local orderId = ARGV[1]
local quantity = tonumber(ARGV[2])
local expireSeconds = tonumber(ARGV[3])
local recordTtlSeconds = tonumber(ARGV[4] or ARGV[3])

-- 0. 终结墓碑检查（04B）：订单已终结 → 拒绝迟到/重投的预扣
if redis.call('EXISTS', terminatedKey) == 1 then
    return -3
end

-- 1. 幂等检查：同一订单同一 SKU 不能重复预扣
if redis.call('EXISTS', recordKey) == 1 then
    return -1
end

-- 2. 库存检查：未初始化 / 不足
local stock = redis.call('GET', stockKey)
if not stock then
    return -2
end
if tonumber(stock) < quantity then
    return 0
end

-- 3. 扣减 + 记账 + 索引（原子）
redis.call('DECRBY', stockKey, quantity)
redis.call('SET', recordKey, tostring(quantity), 'EX', recordTtlSeconds)
local time = redis.call('TIME')
redis.call('ZADD', indexKey, time[1] * 1000 + expireSeconds * 1000, orderId)
return 1
