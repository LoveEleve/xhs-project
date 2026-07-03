-- follow_self.lua
-- 关注用户（当前用户侧）：写入关注列表 + 更新关注数
--
-- 【修复M6】拆分为 self/target 两个脚本，每个脚本的 KEYS 属于同一用户，
-- 保证 Redis Cluster 下所有 KEYS 落在同一个 slot（通过 {userId} hash tag）。
--
-- 原子性保证：当前用户的关注列表和关注数仍然是原子操作。
-- 跨用户一致性：通过调用方保证两个脚本都执行，极端失败由对账修复。
--
-- KEYS[1] = social:following:{userId}        (ZSet: 当前用户的关注列表)
-- KEYS[2] = counter:{userId}:following       (String: 当前用户的关注数)
-- ARGV[1] = targetUserId (关注列表的 member)
-- ARGV[2] = currentTime (score，关注时间戳)
--
-- 返回值：
--   1  : 关注成功
--   0  : 已关注（幂等）

-- 1. 检查是否已关注（防重复）
local exists = redis.call('ZSCORE', KEYS[1], ARGV[1])
if exists then
    return 0  -- 已关注，返回 0（幂等）
end

-- 2. 写入关注列表
redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])
-- 3. 更新关注数 +1
redis.call('INCR', KEYS[2])

return 1  -- 关注成功
