-- 退还券库存 Lua 脚本
--
-- 退券时回退库存 + 减少用户领取次数
--
-- 【修复M15】Key 格式使用 {templateId} 作为 hash tag，保证两个 KEYS 在 Cluster 下同 slot
-- KEYS[1] = coupon:{templateId}:stock             (String: 券库存)
-- KEYS[2] = coupon:{templateId}:claimed:userId    (String: 用户已领次数)
--
-- 返回值：
--   1  : 退还成功
--   0  : 用户领取记录不存在（异常情况）

local stockKey = KEYS[1]
local claimedKey = KEYS[2]

-- 1. 检查用户领取记录
local claimed = tonumber(redis.call('GET', claimedKey) or '0')
if claimed <= 0 then
    return 0  -- 用户领取记录不存在
end

-- 2. 回退库存
redis.call('INCR', stockKey)

-- 3. 减少用户领取次数
redis.call('DECR', claimedKey)

return 1  -- 退还成功
