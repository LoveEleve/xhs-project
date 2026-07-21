# my-xhs curl 业务验证测试记录

> 逐条 curl 测试 + 业务逻辑讲解，每条测试对应一个文档。
> 测试计划来源：`09-curl-test-plan.md` v4.0

## 测试分组

| 编号 | 分组 | 模块 | 优先级 | 进度 |
|------|------|------|--------|------|
| 01 | [网关基础](01-gateway/) | Gateway | P0 | 0/3 |
| 02 | [用户认证](02-user-auth/) | User | P0 | 0/9 |
| 03 | [内容发布](03-content/) | Content | P0 | 0/9 |
| 04 | [社交互动](04-social/) | Analytics + Counter | P1 | 0/4 |
| 05 | [首页 Feed](05-feed/) | Home (BFF) | P1 | 0/4 |
| 06 | [商品管理](06-product/) | Product | P1 | 0/7 |
| 07 | [购物车](07-cart/) | Cart | P1 | 0/6 |
| 08 | [交易流程](08-trade/) | Order + Payment + Inventory + Coupon | P0 | 0/13 |
| 09 | [搜索推荐](09-search/) | Search | P1 | 0/7 |
| 10 | [消息通知](10-notification/) | Notification | P2 | 0/3 |
| 11 | [即时通讯](11-im/) | IM | P2 | 0/3 |
| 12 | [边界异常](12-boundary/) | 全模块 | P1 | 0/5 |
| 13 | [补充-用户服务](13-supplement-user/) | User | P1 | 0/4 |
| 14 | [补充-内容服务](14-supplement-content/) | Content | P1 | 0/4 |
| 15 | [补充-社交服务](15-supplement-social/) | Analytics | P1 | 0/3 |
| 16 | [补充-首页BFF](16-supplement-home/) | Home | P2 | 0/1 |
| 17 | [补充-购物车](17-supplement-cart/) | Cart | P2 | 0/1 |
| 18 | [补充-优惠券](18-supplement-coupon/) | Coupon | P1 | 0/4 |
| 19 | [补充-库存](19-supplement-inventory/) | Inventory | P1 | 0/5 |
| 20 | [补充-支付退款](20-supplement-payment/) | Payment | P0 | 0/3 |
| 21 | [补充-搜索](21-supplement-search/) | Search | P2 | 0/8 |
| 22 | [补充-通知](22-supplement-notification/) | Notification | P2 | 0/4 |
| 23 | [补充-IM](23-supplement-im/) | IM | P2 | 0/3 |
| 24 | [并发安全](24-concurrency/) | Inventory + Coupon | P0 | 0/2 |
| 25 | [内部接口](25-internal/) | Counter + Order | P2 | 0/5 |

**总计：101 个测试点**

## 文档格式

每篇测试文档包含：
1. **接口信息** — 方法、路径、服务
2. **业务背景** — 接口做什么，为什么存在
3. **curl 命令** — 可复制执行
4. **实际返回** — 粘贴 JSON
5. **关键字段说明** — 解读数据含义
6. **涉及的技术点** — AOP/缓存/锁/MQ 等

## 测试环境

- Gateway: `http://localhost:19000`
- 15 个微服务在线
- 响应格式：`{ "code": 200, "message": "...", "data": {...} }`
