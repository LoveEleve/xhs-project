-- 取消点赞正向索引原子 Lua 脚本（单 Key，Redis Cluster 兼容）
--
-- 仅操作正向索引 Key，保证所有 KEYS 落在同一个 slot。
-- 反向索引由调用方在 Lua 成功后单独 SREM（非原子但幂等）。
--
-- KEYS[1] = 正向索引 Key（如 myxhs:like:note:{noteId}）
-- ARGV[1] = member（userId）
--
-- 返回值：
--   1  : 取消点赞成功
--   0  : 未点赞（幂等）

local exists = redis.call('SISMEMBER', KEYS[1], ARGV[1])
if exists == 0 then
    return 0  -- 未点赞，幂等返回
end

redis.call('SREM', KEYS[1], ARGV[1])
return 1
