-- 原子领券 Lua 脚本
--
-- 核心逻辑（一步到位）：
-- 1. 检查券库存是否充足
-- 2. 检查用户是否已达限领上限
-- 3. 扣减库存
-- 4. 记录领取次数
--
-- 【修复M15】Key 格式使用 {templateId} 作为 hash tag，保证两个 KEYS 在 Cluster 下同 slot
-- KEYS[1] = myxhs:coupon:{templateId}:stock             (String: 券库存)
-- KEYS[2] = myxhs:coupon:{templateId}:claimed:userId    (String: 用户已领次数)
--
-- ARGV[1] = perUserLimit (每人限领数)
--
-- 返回值：
--   1  : 领取成功
--   -1 : 券已领完（库存不足）
--   -2 : 已达限领上限
--   -3 : 券库存未初始化

local stockKey = KEYS[1]
local claimedKey = KEYS[2]
local perUserLimit = tonumber(ARGV[1])

-- 1. 检查券库存是否初始化
local stock = redis.call('GET', stockKey)
if not stock then
    return -3  -- 券库存未初始化
end

-- 2. 检查库存是否充足
if tonumber(stock) <= 0 then
    return -1  -- 券已领完
end

-- 3. 检查用户是否已达限领上限
local claimed = tonumber(redis.call('GET', claimedKey) or '0')
if claimed >= perUserLimit then
    return -2  -- 已达限领上限
end

-- 4. 扣减库存（原子操作）
redis.call('DECR', stockKey)

-- 5. 记录用户领取次数（+1）
redis.call('INCR', claimedKey)

return 1  -- 领取成功
