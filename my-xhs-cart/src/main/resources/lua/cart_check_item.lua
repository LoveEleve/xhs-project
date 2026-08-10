-- 购物车勾选/取消勾选 Lua 脚本（原子化 HEXISTS + SADD/SREM）
-- 修复：替代原非原子的 hasKey→SADD/SREM 两步操作，消除 TOCTOU 窗口
-- 并发 removeFromCart 在 hasKey 和 SADD 之间删除商品 → SADD 创建幽灵条目
--
-- KEYS[1] = myxhs:cart:{userId}:items    (Hash)
-- KEYS[2] = myxhs:cart:{userId}:checked  (Set)
-- ARGV[1] = skuId
-- ARGV[2] = checked ("1"=勾选, "0"=取消)
--
-- 返回: 1=执行成功, 0=商品不在购物车中
if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 0 then return 0 end
if ARGV[2] == '1' then
    redis.call('SADD', KEYS[2], ARGV[1])
else
    redis.call('SREM', KEYS[2], ARGV[1])
end
return 1
