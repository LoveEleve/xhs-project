-- 修改购物车数量 Lua 脚本
-- 原子化 HEXISTS + HSET，替代原来非原子的 hasKey+HSET 两步操作
--
-- KEYS[1] = myxhs:cart:{userId}:items  (Hash)
-- ARGV[1] = skuId
-- ARGV[2] = newQuantity
--
-- 返回: 1=执行成功, 0=商品不存在（已被并发删除）
if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 0 then return 0 end
redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
return 1
