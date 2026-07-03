-- 取消点赞原子操作 Lua 脚本
--
-- 将正向索引 SREM 和反向索引 SREM 合并为一次原子操作，
-- 防止两步之间进程崩溃导致正反向索引不一致。
--
-- KEYS[1] = 正向索引 Key（如 myxhs:like:note:{noteId}）
-- KEYS[2] = 反向索引 Key（如 myxhs:like:user:{userId}:note）
--
-- ARGV[1] = member（正向索引的 userId）
-- ARGV[2] = reverseMember（反向索引的 member，如 noteId）
-- ARGV[3] = hasReverseIndex（"1"=需要反向索引，"0"=不需要）
--
-- 返回值：
--   1  : 取消点赞成功
--   0  : 未点赞（幂等）

local forwardKey = KEYS[1]
local reverseKey = KEYS[2]

local member = ARGV[1]
local reverseMember = ARGV[2]
local hasReverse = ARGV[3]

-- 1. 正向索引 SREM
local removed = redis.call('SREM', forwardKey, member)
if removed == 0 then
    return 0  -- 未点赞，幂等返回
end

-- 2. 反向索引 SREM（仅在需要时）
if hasReverse == '1' then
    redis.call('SREM', reverseKey, reverseMember)
end

return 1
