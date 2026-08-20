# F-039 Home BFF 将下游不可用伪装成“对象不存在”或“空购物车”

## 严重度

Medium

## 涉及文件

- `my-xhs-home/src/main/java/com/myxhs/home/service/ProductAggService.java:61-69`
- `my-xhs-home/src/main/java/com/myxhs/home/service/ProductAggService.java:101-104`
- `my-xhs-home/src/main/java/com/myxhs/home/service/UserProfileAggService.java:56-64`
- `my-xhs-home/src/main/java/com/myxhs/home/service/UserProfileAggService.java:176-179`
- `my-xhs-home/src/main/java/com/myxhs/home/service/CartAggService.java:56-60`
- `my-xhs-home/src/main/java/com/myxhs/home/service/CartAggService.java:92-101`
- `my-xhs-home/src/main/java/com/myxhs/home/controller/HomeController.java:73-79`
- `my-xhs-home/src/main/java/com/myxhs/home/controller/HomeController.java:93-99`
- `my-xhs-home/src/main/java/com/myxhs/home/controller/HomeController.java:115-121`

## 现象

BFF 聚合层在下游服务不可用时，常把“服务失败”降级成“业务对象不存在”或“空数据”：

1. `ProductAggService` 获取商品详情异常时返回空 map，Controller 最终返回 404“商品不存在”。
2. `UserProfileAggService` 获取用户信息异常时返回空 map，Controller 最终返回 404“用户不存在”。
3. `CartAggService` 在购物车服务返回 null/非 success 时会把购物车视为空购物车并返回 200。

## 证据

1. `ProductAggService` 在 supplier 中捕获异常后返回 `Collections.emptyMap()`：`:61-69`；随后 `spuData.isEmpty()` 直接 `return null`：`:101-104`；Controller 把 null 映射成 404：`HomeController.java:93-99`。
2. `UserProfileAggService` 在 supplier 中捕获异常后返回 `Collections.emptyMap()`：`:56-64`；随后 `userData.isEmpty()` 直接 `return null`：`:176-179`；Controller 把 null 映射成 404：`HomeController.java:115-121`。
3. `CartAggService` 对 `cartFeignClient.getCartList()` 的结果仅检查 `isSuccess/getData`，失败则用空 map：`:56-60`；后续 `cartItems.isEmpty()` 直接返回空购物车 VO：`:92-101`。

## 影响

1. 前端和调用方会把下游故障误判为“资源不存在”或“购物车为空”，做出错误业务决策。
2. 监控层面看到的是 200/404 业务码，而不是 503/降级信号，问题更难定位。
3. 这种伪成功会掩盖真实依赖故障，降低告警灵敏度。

## 修复建议

1. 区分“下游不可用”和“业务对象确实不存在”。
2. 对关键主实体（用户、商品、购物车主列表）应返回明确的降级状态或 503，而不是静默空值。
3. 仅对非关键附属字段（计数、库存、优惠券数）使用空值/0 的有损降级。
4. 统一 BFF 降级语义，避免不同聚合接口各自发明错误语义。

## 是否需要补充验证

需要模拟 product/user/cart 下游返回 503 或超时，确认当前 BFF 是否分别表现为 404、空购物车或真实 5xx。