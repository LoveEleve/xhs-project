从今天之后的一段时间内，华仔会带着大家一起从零开始搭建并研发一套高并发的电商实战项目，这里会涉及到很多互联网大厂开发过程中所使用的核心技术和架构设计模式，希望大家学完之后可以用到自己的简历中。

<dependencies>

<dependency>

<groupId>net.huazai</groupId>

<artifactId>huazai-common</artifactId>

<version>1.0\-SNAPSHOT</version>

</dependency>

<dependency>

<groupId>org.apache.rocketmq</groupId>

<artifactId>rocketmq-client</artifactId>

<version>5.3.0</version>

<scope>compile</scope>

</dependency>

</dependencies>

/\*\*

\* 从数据库中获取购物车数据

\* @param userId

\* @return

\*/

private CartListVo queryCartFromDB(Long userId) {

// 1、判断是否存在空缓存

String emptyKey \= RedisKeyConstant.SHOPPING\_CART\_EMPTY\_PREFIX + userId;

if (redisCache.hasKey(emptyKey)) {

log.warn("购物车存在空缓存，直接返回, key: {}", emptyKey);

return CartListVo.builder().build();

}

// 2、从数据库中查询到购物车的商品集合

// 构造查询条件

LambdaQueryWrapper<CartDO> queryWrapper = Wrappers.lambdaQuery();

queryWrapper.eq(CartDO::getUserId, userId);

queryWrapper.orderByDesc(CartDO::getUpdatetime);

// 查询用户购物车数据

List<CartDO> cartDOs = cartMapper.selectList(queryWrapper);

log.info("从数据库中查询购物车数据, cartDOs: {}", JsonUtil.object2Json(cartDOs));

// 将商品 DO 转 商品 VO

List<CartSkuInfoVo> cartSkuInfoVos = cartDOs.stream().map(cartDO -> {

// 获取商品信息

SkuInfoEntity skuInfo \= getSkuInfo(cartDO.getSkuId());

return CartSkuInfoVo.builder()

.skuId(cartDO.getSkuId())

.buyCount(cartDO.getSkuCount())

.updateTime(cartDO.getUpdatetime())

.price(cartDO.getSkuPrice())

.title(skuInfo.getSkuName())

.image(skuInfo.getSkuImage())

.build();

}).collect(Collectors.toList());

// 3、更新 Redis 缓存中的购物车商品

updateCartCacheFromDB(userId, cartSkuInfoVos);

// 构造购物车返回值

return buildCartList(userId, cartSkuInfoVos);

}

/\*\*

\* 更新 Redis 缓存

\* @param userId

\* @param cartSkuInfoVos

\*/

private void updateCartCacheFromDB(Long userId, List<CartSkuInfoVo> cartSkuInfoVos) {

// 1、如果数据库中也没有查到

if (Objects.isNull(cartSkuInfoVos) || CollectionUtils.isEmpty(cartSkuInfoVos)) {

// 空缓存 key

String shoppingEmptyKey \= RedisKeyConstant.SHOPPING\_CART\_EMPTY\_PREFIX + userId;

// 空缓存 value

String shoppingEmptyValue \= CartConstant.EMPTY\_CACHE\_IDENTIFY;

// 随机过期时间 30-100秒

int expireTime \= CommonUtil.genRandomInt(30, 100);

// 写入空缓存到 Redis 中

redisCache.set(shoppingEmptyKey, shoppingEmptyValue, expireTime);

log.warn("购物车和缓存中都没有查到请求的购物车数据, 写入空缓存, key: {}, value: {}, expire: {}秒",

shoppingEmptyKey, shoppingEmptyValue, expireTime);

return;

}

// 数量信息 hash

Map<String, String> cartNumsMap = new HashMap<>();

// 扩展信息hash

Map<String, String> cartExtrasMap = new HashMap<>();

// 遍历购物车商品

for (CartSkuInfoVo cartSkuInfoVo : cartSkuInfoVos) {

// 添加 sku 数量信息

String skuId \= String.valueOf(cartSkuInfoVo.getSkuId());

cartNumsMap.put(skuId, String.valueOf(cartSkuInfoVo.getBuyCount()));

// 添加 sku 扩展信息

cartExtrasMap.put(skuId, JsonUtil.object2Json(cartSkuInfoVo));

// 添加购物车 sku 操作时间缓存

String orderKey \= RedisKeyConstant.SHOPPING\_CART\_SORT\_PREFIX + userId;

redisCache.zadd(orderKey, skuId, cartSkuInfoVo.getUpdateTime().getTime());

log.info("从数据库中查到购物车数据, 写入 zset 排序缓存, key: {}", orderKey);

}

// 添加购物车 sku 数量缓存

String numKey \= RedisKeyConstant.SHOPPING\_CART\_COUNT\_PREFIX + userId;

redisCache.hPutAll(numKey, cartNumsMap);

log.info("从数据库中查到购物车数据, 写入购物车 sku 数量缓存, key: {}, value: {}", numKey, JsonUtil.object2Json(cartNumsMap));

// 添加购物车 sku 扩展信息缓存

String extraKey \= RedisKeyConstant.SHOPPING\_CART\_EXTRA\_PREFIX + userId;

redisCache.hPutAll(extraKey, cartExtrasMap);

log.info("从数据库中查到购物车数据, 写入购物车 sku 扩展信息缓存, key: {}, value: {}", numKey, JsonUtil.object2Json(cartExtrasMap));

}

/\*\*

\* 构造购物车返回值

\* @param userId

\* @param cartInfoDTOs

\* @return

\*/

private CartListVo buildCartList(Long userId, List<CartSkuInfoVo> cartInfoDTOs) {

// 未失效的商品列表

List<CartSkuInfoVo> skuList = new ArrayList<>();

// 失效的商品列表

List<CartSkuInfoVo> disabledSkuList = new ArrayList<>();

// 拆分为未失效的和失效的商品列表

splitCartSkuList(cartInfoDTOs, skuList, disabledSkuList);

// 计算价格数据

BillingInfoVo billingInfoVo \= calculateCartTotalPriceByCoupon(userId, skuList);

// 返回购物车 Vo

return CartListVo.builder()

.cartSkuList(skuList)

.disabledCartSkuList(disabledSkuList)

.billing(billingInfoVo)

.build();

}