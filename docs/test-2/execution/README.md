# 测试执行记录索引

> 2026-08-08 第3会话 | 106/155 | L1-L4全量验证
> 旧文件: `_archive/` | 方法论: `../methodology/TEST-METHODOLOGY.md`

## 链1 用户/认证 (15/19 L1-L4重测完成)

| 文件 | 端点 | 章节 |
|------|------|:--:|
| [user/U02-captcha.md](user/U02-captcha.md) | U02 验证码 | 8/8 |
| [user/U01-register.md](user/U01-register.md) | U01 注册 | 8/8 |
| [user/U03-login.md](user/U03-login.md) | U03 登录 | 8/8 |
| [user/U11-me.md](user/U11-me.md) | U11 用户信息 | 7/8 |
| [user/U12-update.md](user/U12-update.md) | U12 更新用户 | 6/8 |
| [user/U05-logout.md](user/U05-logout.md) | U05 登出 | 6/8 |
| [user/U06-address-create.md](user/U06-address-create.md) | U06 创建地址 | 5/8 |
| [user/U07-address-list.md](user/U07-address-list.md) | U07 地址列表 | 8/8 |
| [user/U08-address-update.md](user/U08-address-update.md) | U08 更新地址 | 5/8 |
| [user/U09-address-delete.md](user/U09-address-delete.md) | U09 删除地址 | 3/8 |
| [user/U10-address-default.md](user/U10-address-default.md) | U10 默认地址 | 3/8 |
| [user/U13-password.md](user/U13-password.md) | U13 修改密码 | 5/8 |
| [user/U14-block.md](user/U14-block.md) | U14 拉黑 | 7/8 |
| [user/U16-unblock.md](user/U16-unblock.md) | U16 取消拉黑 | 7/8 |
| [user/U17-block-list.md](user/U17-block-list.md) | U17 拉黑列表 | 5/8 |

> 未测: U04(refresh 40103), U15(public info), U18-U19(admin)

## 已完成(本会话 search/home)

| 目录 | 端点 | 状态 |
|------|------|:--:|
| _archive/search/ (11 files) | S01-S14 | ✅ |
| _archive/home/ (3 files) | H03-H05 | ✅ |

## 链2 产品 (7/10 L1-L4重测完成)

| 文件 | 端点 | 备注 |
|------|------|------|
| [product/P01-create-spu.md](product/P01-create-spu.md) | P01 创建SPU | admin, spuId=2085989545951625217 |
| [product/P06-create-sku.md](product/P06-create-sku.md) | P06 创建SKU | skuId=2085989641275572226 |
| [product/P02-update-spu.md](product/P02-update-spu.md) | P02 更新SPU | admin |
| [product/P03-spu-detail.md](product/P03-spu-detail.md) | P03 SPU详情 | Cache Aside |
| [product/P07-sku-detail.md](product/P07-sku-detail.md) | P07 SKU详情 | |
| [product/P04-spu-list.md](product/P04-spu-list.md) | P04 SPU列表 | 分页 |
| [product/P09-category-tree.md](product/P09-category-tree.md) | P09 分类树 | Redis缓存2h |

> 未测: P05(status, P01已status=1), P08(内部ES补偿), P10(分类属性)

## 链3 购物车 (7/11 L1-L4重测完成)

| 文件 | 端点 | 备注 |
|------|------|------|
| [cart/B01-cart-add.md](cart/B01-cart-add.md) | B01 加购 | Lua3key+MQ→MySQL |
| [cart/B02-cart-list.md](cart/B02-cart-list.md) | B02 列表 | Feign product取SKU |
| [cart/B04-cart-quantity.md](cart/B04-cart-quantity.md) | B04 修改数量 | Lua HEXISTS+HSET |
| [cart/B06-cart-check.md](cart/B06-cart-check.md) | B06 勾选/取消 | Lua SADD/SREM |
| [cart/B05-cart-check-all.md](cart/B05-cart-check-all.md) | B05 全选 | Lua HKEYS+SADD all |
| [cart/B03-cart-remove.md](cart/B03-cart-remove.md) | B03 移除 | Lua HDEL+SREM+ZREM |
| [cart/B08-cart-count.md](cart/B08-cart-count.md) | B08 计数 | HLEN |

> 未测: B07(合并), B09(清空), B10/B11(内部对账)

## 待重测链

| 链 | 服务子目录 | 状态 |
|:--:|------|:--:|
| 4 | coupon/ | 待开始 |
| 5 | order/ | 待开始 |
| 6 | content-social/ | 待开始 |
| 7 | notification/ im/ | 待开始 |

## 踩坑

| 文件 | 说明 |
|------|------|
| [pitfalls.md](pitfalls.md) | 22项 + 17条预防 |
