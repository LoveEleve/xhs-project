# my-xhs 测试参考 — 端点完整清单（内部端点 / gateway 端点）

> 2026-08-10 | 目的：弥补原 test-plan 未写清的内容。所有端点按 **认证方式** 分为两大类：
> - **gateway 端点**：经 `http://localhost:19000`，默认只需 JWT（Authorization: Bearer）。
> - **内部端点**：服务间 Feign 直连，**绕过 gateway**，用 `X-Internal-Call: my-xhs-internal-token-2026` 直接请求服务端口（19001-19016）。
>
> 所有请求默认：`Content-Type: application/json`。当前阶段 **HMAC 已从默认主路径降级为可选增强**，测试口径不再把它作为普通用户接口必需条件。

---

## 零、认证速查

| 类型 | 端口 | 认证 | 示例 |
|------|:--:|------|------|
| gateway 公开读 | 19000 | 无 | GET /api/note/detail/{id} |
| gateway 认证用户 | 19000 | JWT | POST /api/order/create |
| gateway 管理 | 19000 | JWT + `X-Admin-Call: my-xhs-admin-token-2026` | POST /api/product/spu |
| 内部(Feign) | 服务端口 | `X-Internal-Call: my-xhs-internal-token-2026` | POST /api/coupon/use |
| 公开(免JWT白名单) | 19000 | 无 | /api/user/auth/login、/api/coupon/template/list |

> 通用账号：`chaintest_u1`/`Test@123456`、`chaintest_u2`/`Test@123456`
> 管理令牌：`my-xhs-admin-token-2026`，内部令牌：`my-xhs-internal-token-2026`
> HMAC 已降级为**可选增强**；除非后续显式重新开启 `gateway.auth.hmac-enabled=true`，否则测试不再默认要求计算签名。

---

# 第一部分：gateway 端点（JWT 主路径）

## 1. user（链1，端口19000）

| 端点 | 方法/路径 | 认证 | 请求格式 | 前置 |
|------|------|:--:|------|------|
| U01-captcha | GET /api/user/auth/captcha | 公开 | 无 | — |
| U14-register | POST /api/user/auth/register | 公开 | body{username,password,phone,captchaKey,captchaCode} | U01 |
| U03-login | POST /api/user/auth/login | 公开 | body同上 | U01 |
| U06-me | GET /api/user/me | JWT | 无 | 登录 |
| U07-update-me | PUT /api/user/me | JWT | body{nickname,avatar,...} | 登录 |
| U09-user-info | GET /api/user/{id}/info | 公开 | 无 | — |
| U16-change-password | PUT /api/user/me/password | JWT | body{oldPassword,newPassword} | 登录 |
| U10-add-address | POST /api/user/address | JWT | body{receiverName,receiverPhone,province,city,district,detailAddress} | 登录 |
| U11-list-address | GET /api/user/address/list | JWT | 无 | 登录 |
| U12-update-address | PUT /api/user/address/{id} | JWT | body同U10 | U10 |
| U13-delete-address | DELETE /api/user/address/{id} | JWT | 无 | U10 |
| U-add-default | GET /api/user/address/default | JWT | 无 | 有默认地址 |
| U-add-detail | GET /api/user/address/{id} | JWT | 无 | U10 |
| U-set-default | PUT /api/user/address/{id}/default | JWT | 无 | U10 |
| U04-refresh | POST /api/user/auth/refresh | 公开 | **query** `?refreshToken=` | 登录 |
| U-B1-block | POST /api/user/block/{targetUserId} | JWT | 无 | u2存在 |
| U-B2-unblock | DELETE /api/user/block/{targetUserId} | JWT | 无 | U-B1 |
| U-B3-block-list | GET /api/user/block/list | JWT | 无 | U-B1 |
| U05-logout | POST /api/user/auth/logout | 公开 | header Authorization + **query** `?refreshToken=` | 登录 |

> ⚠️ 注意：U04/U05 的 refreshToken 是 **query 参数**，U05 logout 还要 Authorization header。
> ⚠️ 改密码(U16)后会**轮换 HMAC 会话密钥**，之后需重新登录拿新 secret。

## 2. product（链2，端口19000）

| 端点 | 方法/路径 | 认证 | 请求格式 | 前置 |
|------|------|:--:|------|------|
| P09-category-tree | GET /api/product/category/tree | JWT | 无 | — |
| P01-spu-create | POST /api/product/spu | JWT + X-Admin-Call | body{name,categoryId,brandId,images,price,stock} | — |
| P03-spu-detail | GET /api/product/spu/{id} | JWT | 无 | P01 |
| P04-spu-list | GET /api/product/spu/list | JWT | query page/size | — |
| P06-sku-create | POST /api/product/sku | JWT + X-Admin-Call | body{spuId,name,price,originalPrice,specs} | P01 |
| P07-sku-detail | GET /api/product/sku/{id} | JWT | 无 | P06 |
| P-sku-by-spu | GET /api/product/sku/list/{spuId} | JWT | 无 | P01 |
| P02-spu-update | PUT /api/product/spu/{id} | JWT + X-Admin-Call | body{name,description} | P01 |
| P05-spu-status | PUT /api/product/spu/{id}/status | JWT + X-Admin-Call | query `?status=0/1` | P01 |
| P10-search-product | GET /api/search/product | JWT | query keyword/page/size | ES有索引 |

> ⚠️ test-plan 标注 P09/P03/P04 "认证:无"，但网关实际**需 JWT**（/api/product/** 未进 JWT 白名单）。
> ⚠️ 创建响应字段是 `data.spuId` / `data.skuId`（不是 id）。

## 3. cart（链3，端口19000）

| 端点 | 方法/路径 | 认证 | 请求格式 | 前置 |
|------|------|:--:|------|------|
| C01-cart-add | POST /api/cart/add | JWT | body{skuId,quantity} | SKU |
| C06-cart-list | GET /api/cart/list | JWT | 无 | C01 |
| C09-cart-count | GET /api/cart/count | JWT | 无 | C01 |
| C02-cart-update-quantity | PUT /api/cart/quantity | JWT | body{skuId,quantity} | C01 |
| C04-cart-check | PUT /api/cart/check | JWT | body{skuId,checked} | C01 |
| C05-cart-check-all | PUT /api/cart/check-all | JWT | **query** `?checked=true/false` | C01 |
| C03-cart-remove | DELETE /api/cart/{skuId} | JWT | 无 | C01 |
| C07-cart-merge | POST /api/cart/merge | JWT | body{items:[{skuId,quantity}]} | — |
| C08-cart-clear | DELETE /api/cart/clear | JWT | 无 | C01 |

## 4. coupon（链4，端口19000）

| 端点 | 方法/路径 | 认证 | 请求格式 | 前置 |
|------|------|:--:|------|------|
| N10-template-list | GET /api/coupon/template/list | 公开 | 无 | — |
| N01-template-create | POST /api/coupon/template | JWT + X-Admin-Call | body{name,type,discountValue,minAmount,totalCount,perUserLimit,validStart,validEnd} | — |
| N03-template-detail | GET /api/coupon/template/{id} | JWT | 无 | N01 |
| N04-claim | POST /api/coupon/claim | JWT | body{templateId} | N10 |
| N05-user-coupons | GET /api/coupon/user/list | JWT | 无 | N04 |
| N06-available-coupons | GET /api/coupon/user/available | JWT | 无 | N04 |
| N02-template-status | PUT /api/coupon/template/{id}/status | JWT + X-Admin-Call | **query** `?status=0/1` | N01 |

> ⚠️ N01 日期格式必须是 `yyyy-MM-dd HH:mm:ss`（空格），**不是** ISO "T"；validStart 必须未来。
> ⚠️ N04 领券是**异步MQ落库**，返回200后需等 2-5s 再查 N05 才能看到。
> ⚠️ N08 用券前模板必须上架，否则返回 30016（COUPON_NOT_AVAILABLE）。

## 5. order + payment（链5，端口19000）

| 端点 | 方法/路径 | 认证 | 请求格式 | 前置 |
|------|------|:--:|------|------|
| D01-order-create | POST /api/order/create | JWT | body{skuItems:[{skuId,quantity}],addressId,bizIdentifier} | SKU+地址+库存 |
| D02-order-detail | GET /api/order/{orderId} | JWT | 无 | D01 |
| D03-order-list | GET /api/order/list | JWT | query page/size | D01 |
| D04-order-by-no | GET /api/order/by-order-no/{orderNo} | JWT | 无 | D01 |
| D08-pay-create | POST /api/order/pay/create | JWT | body{orderId,payType} | D01 |
| D09-pay-status | GET /api/order/pay/status/{orderId} | JWT | 无 | D08 |
| D05-order-cancel | POST /api/order/cancel | JWT | **query** `?orderId=` | D01(未支付) |
| D-order-confirm | POST /api/order/confirm | JWT | query/body orderId | 已发货 |
| D-order-deliver | POST /api/order/deliver | JWT | query/body orderId | 已支付 |
| PAY-pay | POST /api/payment/pay | JWT | body{orderId,payType} | D01 |
| PAY-status | GET /api/payment/status/{orderId} | JWT | 无 | PAY-pay |
| PAY-refund | POST /api/payment/refund | JWT | body{orderId} | 已支付 |

> ⚠️ D01 创建订单走**事务消息异步落库**，返回200后需等落库再查详情。
> ⚠️ D05 参数是 query；已支付订单取消返回 30009（只能取消待付款）。
> ⚠️ 下单后订单 addressSnapshot 应为用户真实地址、skuImage 应为图片（#40/#41 已修复验证）。

## 6. content-social（链6，端口19000）

| 端点 | 方法/路径 | 认证 | 请求格式 | 前置 |
|------|------|:--:|------|------|
| NC01-publish-note | POST /api/note/publish | JWT | body{title,content,coverUrl} | 登录 |
| NC05-note-detail | GET /api/note/detail/{id} | 公开 | 无 | NC01 |
| NC07-my-notes | GET /api/note/my | JWT | 无 | NC01 |
| NC08-publish-draft | POST /api/note/draft | JWT | body{title,content} | 登录 |
| NC09-upload-image | POST /api/note/upload/image | JWT | multipart `file`(真实图片) | 登录 |
| CM01-create-comment | POST /api/comment | JWT | body{noteId,content} | NC01 |
| CM03-comment-list | GET /api/comment/list/{noteId} | 公开 | 无 | CM01 |
| CM-children | GET /api/comment/children/{parentId} | 公开 | query 分页 | 有子评论 |
| CM-count | GET /api/comment/count/{noteId} | 公开 | 无 | CM01 |
| CM-page | GET /api/comment/page/{noteId} | 公开 | query pageNum/pageSize | CM01 |
| CM02-delete-comment | DELETE /api/comment/{id} | JWT | 无 | CM01 |
| LK01-like | POST /api/social/like | JWT | body{bizType:1,bizId} | NC01 |
| LK-count | GET /api/social/like/count | 公开 | query bizType/bizId | LK01 |
| FA-fav-list | GET /api/social/favorite/list | JWT | query 分页 | FA01 |
| LK03-like-status | GET /api/social/like/status | JWT | query bizType=1&bizId= | LK01 |
| LK04-batch-status | GET /api/social/like/batch-status | JWT | query bizType=1&bizIds=1,2 | LK01 |
| FA01-favorite | POST /api/social/favorite | JWT | body{noteId} | NC01 |
| FA03-fav-status | GET /api/social/favorite/status | JWT | query noteId= | FA01 |
| FW01-follow | POST /api/social/follow/{targetUserId} | JWT | 无 | u2 |
| FW06-relation | GET /api/social/relation/{targetUserId} | JWT | 无 | FW01 |
| FW03-following | GET /api/social/following/{userId} | 公开 | 无 | FW01 |
| FW04-followers | GET /api/social/follower/{userId} | 公开 | 无 | FW01 |
| FW02-unfollow | DELETE /api/social/follow/{targetUserId} | JWT | 无 | FW01 |
| FW-common | GET /api/social/common/{targetUserId} | JWT | 无 | 双方关注 |
| FW-follow-count | GET /api/social/following/count/{userId} | 公开 | 无 | — |
| FW-follower-count | GET /api/social/follower/count/{userId} | 公开 | 无 | — |
| NC-edit-note | PUT /api/note/{id} | JWT | body{title,content} | NC01 |
| NC-delete-note | DELETE /api/note/{id} | JWT | 无 | NC01 |
| NC-user-notes | GET /api/note/user/{userId} | 公开 | query pageNum/pageSize | 有笔记 |
| NC-publish-draft2 | POST /api/note/{id}/publish | JWT | 无 | NC08 |
| NC-share | POST /api/note/{id}/share | JWT | 无 | NC01 |

> ⚠️ LK01 的 bizType 是 **int(1笔记/2评论)**；FA01 用 **noteId**（非 bizId）。

## 7. counter / home（链6，端口19000）

| 端点 | 方法/路径 | 认证 | 请求格式 | 前置 |
|------|------|:--:|------|------|
| CT01-counter-get | GET /api/counter/get | 公开 | query `targetType=1&targetId=&countType=1` | 有社交事件 |
| H01-home-feed | GET /api/home/feed | JWT | query page/size | 有feed数据 |
| H02-note-detail | GET /api/home/note/{noteId} | JWT | 无 | 笔记 |
| H03-product | GET /api/home/product/{spuId} | JWT | 无 | SPU |
| H04-user-profile | GET /api/home/user/{targetUserId} | JWT | 无 | 用户 |
| H05-cart-agg | GET /api/home/cart | JWT | 无 | 加购 |
| H06-push-inbox | POST /api/home/test/push-inbox | JWT | **query** `?userId=&noteId=&publishTime=` | 笔记 |
| H07-push-outbox | POST /api/home/test/push-outbox | JWT | **query** `?authorId=&noteId=&publishTime=` | 笔记 |

> ⚠️ counter 用 targetType/targetId/countType（非 bizType/bizId）。CT02-batch-get 是 **POST**+body `{queries:[{targetType,targetId,countTypes:[...]}]}`。
> ⚠️ H06/H07 需 dev profile；publishTime 传当前毫秒时间戳（否则 score=0 被 feed 过滤）。
> ⚠️ **H01-feed 疑似 bug**：预置 feed(user 10001) 与 u1 均返回空，`reverseRangeByScoreWithScores(minScore,0)` 参数可能有问题，待前端确认。

## 8. search + recommend（链6，端口19000）

| 端点 | 方法/路径 | 认证 | 请求格式 | 前置 |
|------|------|:--:|------|------|
| S01-search-note | GET /api/search/note | JWT | query keyword(URL编码)/page/size | ES有索引 |
| S02-search-product | GET /api/search/product | JWT | query keyword/page/size | ES有索引 |
| S03-suggest | GET /api/search/suggest | JWT | query `prefix=` | 有索引 |
| S04-history | GET /api/search/history | JWT | 无 | 有搜索历史 |
| S05-clear-history | DELETE /api/search/history | JWT | 无 | — |
| S06-delete-history | DELETE /api/search/history/{keyword} | JWT | 无(中文需注意签名) | S04 |
| S08-hot-search | GET /api/search/hot | JWT | 无 | 有搜索词 |
| S09-record-keyword | POST /api/search/hot/record | JWT | **query** `?keyword=` | — |
| S10-pin | PUT /api/search/hot/pin | JWT + X-Admin-Call | **query** `?keyword=` | — |
| S11-unpin | DELETE /api/search/hot/pin | JWT + X-Admin-Call | **query** `?keyword=` | S10 |
| S12-block | PUT /api/search/hot/block | JWT + X-Admin-Call | **query** `?keyword=` | — |
| S13-unblock | DELETE /api/search/hot/block | JWT + X-Admin-Call | **query** `?keyword=` | S12 |
| S14-snapshot | GET /api/search/hot/snapshot | JWT | query `date=yyyy-MM-dd` | — |
| R01-recommend-feed | GET /api/recommend/feed | JWT | query page/size | 有数据 |
| R02-similar | GET /api/recommend/similar/{noteId} | JWT | 无 | 笔记 |
| R03-behavior | POST /api/recommend/behavior | JWT | body{noteId,behaviorType:1,duration} | 笔记 |
| R04-compute | POST /api/recommend/compute | JWT + X-Admin-Call | 无 | — |

> ⚠️ S09/S10-S13 用 query `keyword`；S10-S13 是 **Admin**；S06 中文路径+HMAC 有工具边界问题。

## 9. notification + im（链7，端口19000）

| 端点 | 方法/路径 | 认证 | 请求格式 | 前置 |
|------|------|:--:|------|------|
| N01-sse-ticket | POST /api/notification/sse/ticket | JWT | 无 | 登录 |
| N02-sse-connect | GET /api/notification/sse?ticket= | JWT | query ticket (SSE长连接) | N01 |
| N03-list | GET /api/notification/list | JWT | query page/size | **有通知** |
| N04-unread-count | GET /api/notification/unread-count | JWT | 无 | **有通知** |
| N05-mark-read | POST /api/notification/read/{id} | JWT | 无 | **有通知** |
| N06-read-by-type | POST /api/notification/read-by-type/{type} | JWT | 无 | **有通知** |
| N07-read-all | POST /api/notification/read-all | JWT | 无 | **有通知** |
| N09-test-send | POST /api/notification/test/send | JWT | body{type,senderId,targetUserId,content} | dev profile |
| W01-ws-ticket | POST /api/im/ws/ticket | JWT | 无 | 登录 |
| W02-conversations | GET /api/im/conversations | JWT | 无 | 登录 |
| W03-messages-peer | GET /api/im/messages/{peerId} | JWT | 无 | peer存在 |
| W04-mark-read | POST /api/im/read/{peerId} | JWT | 无 | — |
| W05-unread-count | GET /api/im/unread-count | JWT | 无 | — |
| WS-websocket | ws://host/api/im/ws?ticket= | ticket | WebSocket | W01 |

> ⚠️ **通知前提必须构造**：N03-N07 需先有通知，方法=让 u2 对 u1 关注/点赞/评论，事件流生成通知（见 pitfalls#48，曾因消费者崩溃导致无通知）。N09 需 dev profile（已加入 start-all.sh）。
> ⚠️ WS 握手是 HMAC 白名单端点（浏览器 WS 无法签名），需用 WebSocket 客户端测（websocket-client/wscat）。
> ⚠️ N08-online-count / W06-online-count 需 X-Admin-Call（见内部端点）。

---

# 第二部分：内部端点（X-Internal-Call，绕过 gateway，直连服务端口）

> 认证：`X-Internal-Call: my-xhs-internal-token-2026`（部分另需 `X-User-Id`）。**不经过 gateway，不走 JWT/HMAC**。由服务间 Feign 调用，如链5 下单时 order→product/inventory/coupon 自动触发。

| 模块 | 端点 | 方法/直连端口 | 额外头/格式 | 说明 |
|------|------|:--:|------|------|
| product | /api/product/sku/batch?skuIds=1,2 | GET 19006 | X-Internal-Call | 批量SKU(含image) |
| coupon | /api/coupon/discount/{id}?orderAmount=100 | GET 19010 | X-Internal-Call+X-User-Id | 算折扣(不核销) |
| coupon | /api/coupon/use | POST 19010 | X-Internal-Call+X-User-Id | body{userCouponId,orderId,orderAmount} 用券 |
| coupon | /api/coupon/return | POST 19010 | X-Internal-Call+X-User-Id | body{userCouponId,orderId} 退券 |
| order | /api/order/pay-success?orderId=&tradeNo= | POST 19011 | X-Internal-Call | **query** 支付成功回调 |
| order | /api/order/pay-fail?orderId= | POST 19011 | X-Internal-Call | query 支付失败 |
| order | /api/order/refund-success/refund-fail | POST 19011 | X-Internal-Call | query 退款回调 |
| order | /api/order/pay-amount?orderId= | GET 19011 | X-Internal-Call | query 查支付金额 |
| cart | /api/cart/internal/reconcile | POST 19008 | X-Admin-Call | 全量对账 |
| cart | /api/cart/internal/reconcile/user | POST 19008 | X-Admin-Call | 单用户对账 |
| counter | /api/counter/reconcile | POST 19004 | X-Admin-Call | 计数对账 |
| search | /api/search/index/rebuild | POST 19016 | X-Admin-Call | 索引重建(可走gateway) |
| search | /api/recommend/compute | POST 19016 | X-Admin-Call | 推荐计算 |
| inventory | /api/inventory/* (I02-I04,I08-I10) | * 19009 | X-Internal-Call | 预扣/确认/释放/TCC |
| notification | /api/notification/sse/online-count | GET 19013 | X-Admin-Call | 在线数 |
| im | /api/im/online-count | GET 19014 | X-Admin-Call | 在线数 |
| social | /api/social/internal/repair-counter/{userId} | POST 19003 | X-Internal-Call | 修复关注计数 |
| payment | /api/payment/callback/{payType} | POST 19012 | X-Internal-Call | 支付回调 |
| payment | /api/payment/refund-callback/{payType} | POST 19012 | X-Internal-Call | 退款回调 |

> ⚠️ 内部端点**不应**当 gateway 端点测。经 gateway 访问会因无签名/无JWT被拦，或语义不同（如 coupon/use 走 gateway 需额外 HMAC）。正确做法：**直连服务端口 + X-Internal-Call**（模拟 Feign）。
> ⚠️ 管理类内部端点（reconcile/rebuild/online-count）用 `X-Admin-Call`，可走 gateway（带 JWT）。

---

# 附录：已发现问题速查（详见 execution/pitfalls.md）

- **已修复 BUG**：#39 HMAC、#40 skuImage、#41 地址、#44 缺模板列表、#46 spu-detail image、#48 通知消费者崩溃、#49 WS握手被HMAC拦、#50 dev profile
- **待前端确认**：#H01-feed 返回空（zset 查询参数问题）
- **测试数据**：预置 feed(user 10001) 引用不存在的 noteId(10001-10030)；product 公开读需 JWT 但文档标"认证:无"
