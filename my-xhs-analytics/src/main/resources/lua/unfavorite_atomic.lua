-- 取消收藏原子 Lua 脚本
-- ZSCORE 检查 + ZREM + ZCARD 修正，防止并发 favorite 时回滚覆盖新score
-- 【C-009 D7 修正】原注释称含 ZCARD 修正——脚本实际无 ZCARD（仅 ZSCORE 守卫 + ZREM）
--
-- KEYS[1] = myxhs:favorite:{userId}  (ZSet)
-- ARGV[1] = noteId
-- 返回: 1=执行成功, 0=未收藏
if redis.call('ZSCORE', KEYS[1], ARGV[1]) == false then return 0 end
redis.call('ZREM', KEYS[1], ARGV[1])
return 1
