-- 购物车合并 Lua 脚本（统一处理新商品和已有商品）
-- 原子化：HEXISTS检查 + HGET读 + HSET写 在一个原子执行单位中完成
-- 替代原来非原子的 hasKey→get→put 三步操作，消除并发复活/覆盖窗口
--
-- KEYS[1] = myxhs:cart:{userId}:items    (Hash)
-- KEYS[2] = myxhs:cart:{userId}:checked  (Set)
-- KEYS[3] = myxhs:cart:{userId}:sort     (ZSet)
-- ARGV[1] = skuId
-- ARGV[2] = quantity
-- ARGV[3] = MAX_CART_SIZE
-- ARGV[4] = MAX_ITEM_QUANTITY
-- ARGV[5] = sortScore (时间戳)
-- ARGV[6] = ttlSeconds (购物车 Key 滑动 TTL 秒数，脚本内设置)
--
-- 返回：
--   >0 且 <10000 : 新商品成功（合并后的实际数量）
--   >=10000      : 已有商品成功（实际数量 = 返回值 - 10000，供调用方区分发 UPDATE 而非 ADD）
--    0           : 购物车已满（新商品）
--   -1           : 购物车已满（合并时上限触发）
if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 1 then
    -- 已有商品：原子化 HGET + max() + HSET（不触碰 checked Set，保持用户原勾选状态）
    local currentQty = tonumber(redis.call('HGET', KEYS[1], ARGV[1]) or '0')
    local mergedQty = math.max(currentQty, tonumber(ARGV[2]))
    local maxQty = tonumber(ARGV[4])
    if mergedQty > maxQty then mergedQty = maxQty end
    redis.call('HSET', KEYS[1], ARGV[1], mergedQty)
    if tonumber(ARGV[6]) and tonumber(ARGV[6]) > 0 then
        redis.call('EXPIRE', KEYS[1], ARGV[6])
        redis.call('EXPIRE', KEYS[2], ARGV[6])
        redis.call('EXPIRE', KEYS[3], ARGV[6])
    end
    return mergedQty + 10000
else
    -- 新商品：检查品种上限 + 数量截断 + 加入
    local currentSize = redis.call('HLEN', KEYS[1])
    if currentSize >= tonumber(ARGV[3]) then return 0 end
    local qty = tonumber(ARGV[2])
    local maxQty = tonumber(ARGV[4])
    if qty > maxQty then qty = maxQty end
    redis.call('HSET', KEYS[1], ARGV[1], qty)
    redis.call('SADD', KEYS[2], ARGV[1])
    redis.call('ZADD', KEYS[3], 'NX', ARGV[5], ARGV[1])
    if tonumber(ARGV[6]) and tonumber(ARGV[6]) > 0 then
        redis.call('EXPIRE', KEYS[1], ARGV[6])
        redis.call('EXPIRE', KEYS[2], ARGV[6])
        redis.call('EXPIRE', KEYS[3], ARGV[6])
    end
    return qty
end
