-- unfollow_target.lua
-- 取关用户（目标用户侧）：移除粉丝列表 + 更新粉丝数
--
-- 【修复M6】配合 unfollow_self.lua 使用。
--
-- KEYS[1] = social:follower:{targetUserId}    (ZSet: 目标用户的粉丝列表)
-- KEYS[2] = counter:{targetUserId}:follower   (String: 目标用户的粉丝数)
-- ARGV[1] = userId (粉丝列表的 member)
--
-- 返回值：
--   1  : 移除成功
--   0  : 不存在（幂等）

-- 1. 检查是否存在
local exists = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not exists then
    return 0
end

-- 2. 移除粉丝列表
redis.call('ZREM', KEYS[1], ARGV[1])
-- 3. 更新粉丝数 -1（防负数保护）
local count = tonumber(redis.call('GET', KEYS[2]) or '0')
if count > 0 then
    redis.call('DECR', KEYS[2])
end

return 1
