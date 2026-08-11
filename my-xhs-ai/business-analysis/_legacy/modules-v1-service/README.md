# 业务逻辑模块索引

> 按模块梳理 my-xhs 真实业务逻辑，供 AI 项目理解业务、确定数据需求与服务边界。
> 配套：`../business-reality.md`（全平台概览）+ `../plan-review.md`（对 PLAN.md 的批判性再判断）。

## 模块清单（内容支柱 + 电商支柱）

| # | 模块 | 服务 | 端口 | 业务角色 | 文档 |
|:--:|------|------|:--:|---------|------|
| 01 | 用户 | my-xhs-user | 19001 | 身份/登录/地址/屏蔽 | [01-user.md](01-user.md) |
| 02 | 内容社交 | my-xhs-content-social | 19002 | **内容支柱核心**：笔记/评论/赞/藏/关注 | [02-content-social.md](02-content-social.md) |
| 03 | 商品 | my-xhs-product | 19006 | 商品主数据 SPU/SKU/分类 | [03-product.md](03-product.md) |
| 04 | 购物车 | my-xhs-cart | 19008 | 转化漏斗中间环节 | [04-cart.md](04-cart.md) |
| 05 | 优惠券 | my-xhs-coupon | 19007 | 营销工具 | [05-coupon.md](05-coupon.md) |
| 06 | 订单+支付 | my-xhs-order/payment | 19005/19009 | **交易核心**：全链路最复杂 | [06-order-payment.md](06-order-payment.md) |
| 07 | 库存 | my-xhs-inventory | 19010 | 履约约束 TCC | [07-inventory.md](07-inventory.md) |
| 08 | 搜索推荐 | my-xhs-search | 19016 | 内容/商品发现 | [08-search-recommend.md](08-search-recommend.md) |
| 09 | 聚合 BFF | my-xhs-home | 19015 | 数据编排（无业务事实） | [09-home-bff.md](09-home-bff.md) |
| 10 | 通知 | my-xhs-notification | 19013 | 用户触达 SSE | [10-notification.md](10-notification.md) |
| 11 | 私信 | my-xhs-im | 19014 | 实时通讯 | [11-im.md](11-im.md) |
| 12 | 计数 | my-xhs-counter | 19004 | 全平台互动计数底座 | [12-counter.md](12-counter.md) |

## 支柱归属

```
内容与社交支柱：user, content-social, search, home(BFF), notification, im, counter, analytics
电商交易支柱：  product, cart, coupon, order, payment, inventory
衔接：          content-social(种草) → home(Feed/商品聚合) → cart → order(转化)
```

## 关键数据就绪度结论（跨模块汇总，供 AI 规划）

| 数据 | 就绪度 | 所在模块 | 影响 |
|------|:---:|------|------|
| 订单量/状态/客单价 | ⚠️ | 06 | 分片需汇总投影 |
| 支付成功率 | ⚠️ | 06 | **缺失败原因字段** |
| 库存水位/TCC | ✅ | 07 | 结构清晰 |
| 互动计数 | ✅ | 12 | 聚合底座 |
| 内容发布/审核 | ⚠️ | 02 | 缺独立 published_at/audited_at |
| 粉丝净增长 | ⚠️ | 02/12 | Redis 非事件流水 |
| 转化漏斗 | ⚠️ | 04/06/08 | 缺加购→下单粒度 |
| 退款商品级明细 | ⚠️ | 06 | 缺商品明细 |
| 券核销/营销 | ✅ | 05 | 唯一索引+模板表 |
| 搜索/热搜/无结果率 | ✅ | 08 | ES+MySQL |

> 数据就绪度是 AI 项目"能否回答"的硬约束，详情见各模块及 `../business-reality.md` §5/§6。
