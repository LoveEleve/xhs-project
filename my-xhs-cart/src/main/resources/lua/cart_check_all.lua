-- 购物车全选/取消全选 Lua 脚本（原子操作）
--
-- 为什么需要 Lua？
-- 非原子方案：KEYS → DEL → SADD，中间存在竞态窗口。
-- 并发 addToCart 可能在 DEL 和 SADD 之间将新商品加入 checked Set，
-- 然后被后续的 SADD 覆盖丢失。
-- Lua 脚本保证：读取所有 SKU → 重建 checked Set 是一个原子操作。
--
-- KEYS[1] = myxhs:cart:items:{userId}     (Hash: 商品+数量)
-- KEYS[2] = myxhs:cart:checked:{userId}   (Set: 选中状态)
--
-- ARGV[1] = checked ("1"=全选, "0"=取消全选)
--
-- 返回值：
--   > 0 : 全选时返回选中的商品数量，取消全选时返回 0
--

local itemsKey = KEYS[1]
local checkedKey = KEYS[2]
local checked = ARGV[1]

if checked == '0' then
    -- 取消全选：直接删除整个 Set（O(1)）
    redis.call('DEL', checkedKey)
    return 0
else
    -- 全选：获取 Hash 中所有 skuId，重建 checked Set
    local allSkuIds = redis.call('HKEYS', itemsKey)
    if #allSkuIds == 0 then
        redis.call('DEL', checkedKey)
        return 0
    end

    -- 先删除旧 Set，再 SADD 新成员
    redis.call('DEL', checkedKey)
    for i = 1, #allSkuIds do
        redis.call('SADD', checkedKey, allSkuIds[i])
    end
    return #allSkuIds
end
