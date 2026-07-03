-- follow_target.lua
-- 关注用户（目标用户侧）：写入粉丝列表 + 更新粉丝数
--
-- 【修复M6】配合 follow_self.lua 使用。
-- 操作目标用户的 Key，通过 {targetUserId} hash tag 保证同 slot。
--
-- KEYS[1] = social:follower:{targetUserId}    (ZSet: 目标用户的粉丝列表)
-- KEYS[2] = counter:{targetUserId}:follower   (String: 目标用户的粉丝数)
-- ARGV[1] = userId (粉丝列表的 member)
-- ARGV[2] = currentTime (score，关注时间戳)
--
-- 返回值：
--   1  : 写入成功
--   0  : 已存在（幂等）

-- 1. 检查是否已存在（幂等）
local exists = redis.call('ZSCORE', KEYS[1], ARGV[1])
if exists then
    return 0
end

-- 2. 写入粉丝列表
redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])
-- 3. 更新粉丝数 +1
redis.call('INCR', KEYS[2])

return 1
