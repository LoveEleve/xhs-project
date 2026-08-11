# 优惠券模块（my-xhs-coupon）

> 来源：`my-xhs-coupon` 源码直读。

## 一、业务边界
- 券模板管理（管理端）、领券、我的券列表、可用券、下单用券/退券（内部 Feign）。
- 网关前缀 `/api/coupon/**`。领券/我的券需登录。

## 二、优惠券类型（核心）
```
type=1  满减券   满 minAmount 减 discountValue
type=2  折扣券   订单金额 × discountValue / 10 （如 8.5 → 85折）
type=3  无门槛券 直接减 discountValue
```

## 三、用户券状态
```
status: 0=未使用, 1=已使用, 2=已过期
```
- 已使用券可退回（取消订单时 1→0）。

## 四、接口

### 领券
```
POST /api/coupon/claim   (需登录) body: { templateId }
```

### 我的券列表（**不分页**）
```
GET /api/coupon/user/list?status=   (需登录) → List<UserCouponVO>
```
- `status` 可选：0/1/2；不传 = 全部。

### 可用券（下单时展示）
```
GET /api/coupon/user/available   (需登录) → List<UserCouponVO>
```

### 模板详情 / 创建 / 上下架（管理端）
```
GET  /api/coupon/template/{id}   → CouponTemplateVO
GET  /api/coupon/template/list   → List<CouponTemplateVO>   ← ✅ 后端已补：可领券模板列表（领券中心用）
POST /api/coupon/template        (管理端)
PUT  /api/coupon/template/{id}/status (管理端)
```

### 数据模型
```
CouponTemplateVO: id, name, type, discountValue, minAmount, totalCount,
                  remainCount, perUserLimit, validStart, validEnd, status
UserCouponVO:     id, couponId, name, type, discountValue, minAmount,
                  status, validEnd, receivedAt
```

## 五、前端接入注意汇总
1. `getUserCouponList` 返回**数组**，不是分页对象 → 修正 `api/coupon.ts`。
2. 前端估算券折扣：满减/无门槛 = 直接减 `discountValue`（满减需满足 `minAmount`）；
   折扣券 = `金额×discountValue/10`。**最终以订单 `payAmount` 为准**。
3. 领券中心（CouponCenterPage）用 `GET /coupon/template/list`（后端已补）拉取可领券模板，`POST /coupon/claim {templateId}` 领取。
