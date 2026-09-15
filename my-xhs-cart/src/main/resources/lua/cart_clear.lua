-- 清空购物车（原子）：三结构 + 清空标记一次完成
-- KEYS[1]=items KEYS[2]=checked KEYS[3]=sort KEYS[4]=clearedMarker
-- ARGV[1]=marker TTL(秒)
redis.call('DEL', KEYS[1], KEYS[2], KEYS[3])
redis.call('SET', KEYS[4], '1', 'EX', tonumber(ARGV[1]))
return 1
