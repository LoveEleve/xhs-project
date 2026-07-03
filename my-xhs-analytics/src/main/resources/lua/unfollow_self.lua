-- unfollow_self.lua
-- 取关用户（当前用户侧）：移除关注列表 + 更新关注数
--
-- 【修复M6】拆分为 self/target 两个脚本。
--
-- KEYS[1] = social:following:{userId}        (ZSet: 当前用户的关注列表)
-- KEYS[2] = counter:{userId}:following       (String: 当前用户的关注数)
-- ARGV[1] = targetUserId (关注列表的 member)
--
-- 返回值：
--   1  : 取关成功
--   0  : 未关注（幂等）

-- 1. 检查是否已关注
local exists = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not exists then
    return 0  -- 未关注，返回 0（幂等）
end

-- 2. 移除关注列表
redis.call('ZREM', KEYS[1], ARGV[1])
-- 3. 更新关注数 -1（防负数保护）
local count = tonumber(redis.call('GET', KEYS[2]) or '0')
if count > 0 then
    redis.call('DECR', KEYS[2])
end

return 1  -- 取关成功
