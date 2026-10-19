-- 收藏原子 Lua 脚本
-- KEYS[1] = myxhs:favorite:{userId}  (ZSet；C-009 D7 注释对齐——原写 favorite:set:{userId} 与实现不符)
-- ARGV[1] = noteId, ARGV[2] = currentTime (score)
-- 返回 1=成功 0=已收藏(幂等)

local exists = redis.call('ZSCORE', KEYS[1], ARGV[1])
if exists then
    return 0
end
redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])
return 1
