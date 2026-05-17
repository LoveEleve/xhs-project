-- unfollow_and_count.lua
-- 取关用户 + 更新计数（原子操作）
--
-- 【原子性说明】同 follow_and_count.lua
-- 关注关系（ZREM）优先执行，计数（DECR）为衍生数据，可对账修复。
--
-- KEYS[1] = social:following:{userId}
-- KEYS[2] = social:follower:{targetUserId}
-- KEYS[3] = counter:user_following:{userId}
-- KEYS[4] = counter:user_follower:{targetUserId}
-- ARGV[1] = targetUserId（关注列表的 member）
-- ARGV[2] = userId（粉丝列表的 member）

-- 1. 检查是否已关注
local exists = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not exists then
    return 0  -- 未关注，返回 0（幂等）
end

-- 2. 移除关注关系（核心数据）
redis.call('ZREM', KEYS[1], ARGV[1])
-- 3. 移除粉丝关系
redis.call('ZREM', KEYS[2], ARGV[2])
-- 4. 更新关注数 -1（衍生数据）
redis.call('DECR', KEYS[3])
-- 5. 更新粉丝数 -1
redis.call('DECR', KEYS[4])

return 1  -- 取关成功
