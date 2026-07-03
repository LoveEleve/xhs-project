-- 点赞原子操作 Lua 脚本
--
-- 将正向索引 SADD 和反向索引 SADD 合并为一次原子操作，
-- 防止两步之间进程崩溃或 Redis 分区导致正反向索引不一致。
--
-- KEYS[1] = 正向索引 Key（如 myxhs:like:note:{noteId}）
-- KEYS[2] = 反向索引 Key（如 myxhs:like:user:{userId}:note）
--
-- ARGV[1] = member（正向索引的 userId / 反向索引的 bizId）
-- ARGV[2] = reverseMember（反向索引的 member，如 noteId）
-- ARGV[3] = hasReverseIndex（"1"=需要反向索引，"0"=不需要，如评论不接受反向索引）
--
-- 返回值：
--   1  : 新增点赞成功
--   0  : 已点赞（幂等）

local forwardKey = KEYS[1]
local reverseKey = KEYS[2]

local member = ARGV[1]
local reverseMember = ARGV[2]
local hasReverse = ARGV[3]

-- 1. 正向索引 SADD
local added = redis.call('SADD', forwardKey, member)
if added == 0 then
    return 0  -- 已点赞，幂等返回
end

-- 2. 反向索引 SADD（仅在需要时）
if hasReverse == '1' then
    redis.call('SADD', reverseKey, reverseMember)
end

return 1
