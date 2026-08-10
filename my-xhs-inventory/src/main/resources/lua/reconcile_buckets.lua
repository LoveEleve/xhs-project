-- 分桶完整性对账 Lua 脚本（原子求和+对比设置）
--
-- 替代 Java 侧"先读 total 再循环 GET 各桶再 SET"的非原子序列：
-- 读后发生的 preDeduct/release 会被盲 SET 覆盖（幻影回滚）。
-- Lua 单原子执行单位内完成求和+对比+设置，无并发窗口。
--
-- KEYS[1] = inventory:{skuId}:total       (String: 总库存)
-- KEYS[2..N+1] = inventory:{skuId}:bucket:0 ~ bucket:(N-1) (String: 各桶库存)
--
-- 返回值：
--   1 : 已修正（bucketSum != total，已 SET total=bucketSum）
--   0 : 一致无需修正

local sum = 0
for i = 2, #KEYS do
    sum = sum + tonumber(redis.call('GET', KEYS[i]) or '0')
end

local total = tonumber(redis.call('GET', KEYS[1]) or '0')

if sum ~= total then
    redis.call('SET', KEYS[1], sum)
    return 1
end
return 0
