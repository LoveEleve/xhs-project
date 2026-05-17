-- follow_and_count.lua
-- 关注用户 + 更新计数（原子操作）
--
-- 【关于原子性的说明】
-- Redis Lua 脚本保证的是"执行隔离性"（不被其他命令打断），而非"事务回滚"。
-- 如果脚本执行到一半 Redis OOM 或被 kill，已执行的命令不会回滚。
-- 防御策略：
--   1. 关注关系（ZADD）是核心数据，优先写入
--   2. 计数（INCR）是衍生数据，即使不一致也可通过定时对账修复
--   3. 计数修复逻辑：ZCARD(following) vs counter:user_following，不一致时以 ZCARD 为准
--
-- KEYS[1] = social:following:{userId}
-- KEYS[2] = social:follower:{targetUserId}
-- KEYS[3] = counter:user_following:{userId}
-- KEYS[4] = counter:user_follower:{targetUserId}
-- ARGV[1] = targetUserId（关注列表的 member）
-- ARGV[2] = userId（粉丝列表的 member）
-- ARGV[3] = currentTime（score，关注时间戳）

-- 1. 检查是否已关注（防重复，ZSet 天然去重但提前检查可避免无效写入）
local exists = redis.call('ZSCORE', KEYS[1], ARGV[1])
if exists then
    return 0  -- 已关注，返回 0（幂等）
end

-- 2. 写入关注关系（核心数据，优先保证）
redis.call('ZADD', KEYS[1], ARGV[3], ARGV[1])
-- 3. 写入粉丝关系
redis.call('ZADD', KEYS[2], ARGV[3], ARGV[2])
-- 4. 更新关注数 +1（衍生数据，可通过对账修复）
redis.call('INCR', KEYS[3])
-- 5. 更新粉丝数 +1
redis.call('INCR', KEYS[4])

return 1  -- 关注成功