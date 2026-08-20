# 端点ID别名映射表 — test-plan基准 → 执行产物

> 2026-08-10 | 矛盾3(b) 解决方案：**不改名文件**，建立 test-plan 端点ID(基准) → 实际执行文件 的映射。
> 决策: **全量重跑** → 重跑时执行文件统一按 test-plan 端点ID命名（与 HANDOFF 一致），旧文件为 legacy 别名。

---

## 命名规则

- **基准(权威)**: 各模块 `test-plan.md` 执行顺序表里的端点ID + 产出文件路径。
- **legacy**: 旧会话生成的、与基准命名不一致的文件，仅作参考，重跑覆盖时不再沿用。
- 表中 `状态`: ✅已有 | ⬜需新建 | 🔁ID一致仅后缀异 | 🧹legacy多余

---

## user（链1，16端点）

基准产出: `execution/user/{端点}.md`

| 基准ID | 业务 | legacy文件 | 状态 |
|------|------|------|:--:|
| U01-captcha | 验证码 | U02-captcha.md | 🔁 |
| U14-register | 注册 | U01-register.md | 🔁 |
| U03-login | 登录 | U03-login.md | ✅ |
| U06-me | 当前用户 | U11-me.md | 🔁 |
| U07-update-me | 更新资料 | U12-update.md | 🔁 |
| U09-user-info | 公开资料 | — | ⬜ |
| U16-change-password | 改密码 | U13-password.md | 🔁 |
| U10-add-address | 添加地址 | U06-address-create.md | 🔁 |
| U11-list-address | 地址列表 | U07-address-list.md | 🔁 |
| U12-update-address | 更新地址 | U08-address-update.md | 🔁 |
| U13-delete-address | 删除地址 | U09-address-delete.md | 🔁 |
| U04-refresh | 刷新Token | — | ⬜ |
| U-B1-block | 屏蔽 | U14-block.md | 🔁 |
| U-B2-unblock | 取消屏蔽 | U16-unblock.md | 🔁 |
| U-B3-block-list | 屏蔽列表 | U17-block-list.md | 🔁 |
| U05-logout | 登出 | U05-logout.md | ✅ |
| *(legacy多余)* | 设置默认地址 | U10-address-default.md | 🧹 |

---

## product（链2，10端点）

基准产出: `execution/product/{端点}.md`

| 基准ID | 业务 | legacy文件 | 状态 |
|------|------|------|:--:|
| P09-category-tree | 分类树 | P09-category-tree.md | ✅ |
| P01-spu-create | 创建SPU | P01-create-spu.md | 🔁 |
| P03-spu-detail | SPU详情 | P03-spu-detail.md | ✅ |
| P06-sku-create | 创建SKU | P06-create-sku.md | 🔁 |
| P04-spu-list | SPU列表 | P04-spu-list.md | ✅ |
| P02-spu-update | 更新SPU | P02-update-spu.md | 🔁 |
| P05-spu-status | SPU状态 | — | ⬜ |
| P07-sku-detail | SKU详情 | P07-sku-detail.md | ✅ |
| P08-sku-batch | 批量SKU | — | ⬜ |
| P10-search-product | 商品搜索 | — | ⬜ |

---

## cart（链3，10端点）

基准产出: `execution/cart/C{XX}.md`（test-plan 用简写 C01.md）

| 基准ID | 业务 | legacy文件 | 状态 |
|------|------|------|:--:|
| C01-cart-add | 加购 | B01-cart-add.md | 🔁 |
| C04-cart-check | 勾选 | B06-cart-check.md | 🔁 |
| C06-cart-list | 购物车列表 | B02-cart-list.md | 🔁 |
| C09-cart-count | 数量 | B08-cart-count.md | 🔁 |
| C02-cart-update-quantity | 改数量 | B04-cart-quantity.md | 🔁 |
| C05-cart-check-all | 全选 | B05-cart-check-all.md | 🔁 |
| C03-cart-remove | 删除 | B03-cart-remove.md | 🔁 |
| C07-cart-merge | 合并 | — | ⬜ |
| C08-cart-clear | 清空 | — | ⬜ |
| C10-cart-reconcile | 对账 | — | ⬜ |

---

## coupon（链4，9端点）

基准产出: `execution/coupon/{端点}.md`

| 基准ID | 业务 | legacy文件 | 状态 |
|------|------|------|:--:|
| N01-template-create | 建模板 | N01-template-create.md | ✅ |
| N03-template-detail | 模板详情 | — | ⬜ |
| N04-claim | 领券 | N04-coupon-claim.md | 🔁 |
| N05-user-coupons | 我的券 | N05-coupon-list.md | 🔁 |
| N06-available-coupons | 可用券 | — | ⬜ |
| N02-template-status | 模板状态 | — | ⬜ |
| N07-coupon-discount | 折扣计算 | — | ⬜ |
| N08-use-coupon | 用券 | — | ⬜ |
| N09-return-coupon | 退券 | — | ⬜ |

---

## order（链5，8端点）

基准产出: `execution/order/{端点}.md`

| 基准ID | 业务 | legacy文件 | 状态 |
|------|------|------|:--:|
| D01-order-create | 创建订单 | D01-order-create.md | ✅ |
| D02-order-detail | 订单详情 | — | ⬜ |
| D03-order-list | 订单列表 | — | ⬜ |
| D08-pay-create | 发起支付 | — | ⬜ |
| D09-pay-status | 支付状态 | — | ⬜ |
| D10-pay-success | 支付成功 | — | ⬜ |
| D05-order-cancel | 取消订单 | — | ⬜ |
| D04-order-by-no | 按单号查 | — | ⬜ |
| *(legacy综合)* | 整链生命周期 | CHAIN5-order-lifecycle.md | 🧹 |

---

## content-social（链6，18端点）

基准产出: `execution/content-social/{端点}.md`

| 基准ID | 业务 | legacy文件 | 状态 |
|------|------|------|:--:|
| NC01-publish-note | 发笔记 | CHAIN6-social.md(综合) | ⬜ |
| NC09-upload-image | 传图 | — | ⬜ |
| NC05-note-detail | 笔记详情 | — | ⬜ |
| NC07-my-notes | 我的笔记 | — | ⬜ |
| CM01-create-comment | 发评论 | — | ⬜ |
| CM03-comment-list | 评论列表 | — | ⬜ |
| CM02-delete-comment | 删评论 | — | ⬜ |
| NC08-publish-draft | 发草稿 | — | ⬜ |
| FA01-favorite | 收藏 | — | ⬜ |
| FA03-fav-status | 收藏状态 | — | ⬜ |
| LK01-like | 点赞 | — | ⬜ |
| LK03-like-status | 点赞状态 | — | ⬜ |
| LK04-batch-status | 批量点赞状态 | — | ⬜ |
| FW01-follow | 关注 | — | ⬜ |
| FW06-relation | 关系 | — | ⬜ |
| FW03-following | 关注列表 | — | ⬜ |
| FW04-followers | 粉丝列表 | — | ⬜ |
| FW02-unfollow | 取关 | — | ⬜ |

---

## counter / home / search / inventory（链6/7依赖，全新建）

> 这三+一模块 execution 目录为空，test-plan 已有基准ID，重跑时全部新建:
> - counter: CT01-counter-get / CT02-batch-get / CT03-reconcile
> - home: H01-feed / H02-note-detail / H03-product / H04-user-profile / H05-cart-agg / H06-push-inbox / H07-push-outbox
> - search: S01~S14 + R01~R04（见 search/test-plan.md）
> - inventory: I01~I10（见 inventory/test-plan.md）

---

## notification（链7，9端点）

基准产出: `execution/notification/{端点}.md`

| 基准ID | 业务 | legacy文件 | 状态 |
|------|------|------|:--:|
| N01-sse-ticket | SSE ticket | — | ⬜ |
| N02-sse-connect | SSE长连 | — | ⬜ |
| N03-list | 通知列表 | T03-list.md | 🔁 |
| N04-unread-count | 未读数 | T04-unread-count.md | 🔁 |
| N05-mark-read | 已读 | T05-read.md | 🔁 |
| N06-read-by-type | 按类型已读 | T06-read-by-type.md | 🔁 |
| N07-read-all | 全部已读 | T07-read-all.md | 🔁 |
| N08-online-count | 在线数 | — | ⬜ |
| N09-test-send | 测试发送(dev) | T09-test-send.md | 🔁 |

---

## im（链7，7端点）

基准产出: `execution/im/{端点}.md`

| 基准ID | 业务 | legacy文件 | 状态 |
|------|------|------|:--:|
| W01-ws-ticket | WS ticket | W01-ws-ticket.md | ✅ |
| WS-websocket | WS连接 | — | ⬜ |
| W02-conversations | 会话列表 | W02-conversations.md | ✅ |
| W03-messages-peer | 消息列表 | W03-messages.md | 🔁 |
| W04-mark-read | 已读 | W05-read.md | 🔁 |
| W05-unread-count | 未读数 | W06-unread-count.md | 🔁 |
| W06-online-count | 在线数 | — | ⬜ |

---

## 重跑执行规则

1. 执行时以 **test-plan 基准端点ID** 作为输出文件名。
2. 表内 `🔁` 的 legacy 文件**不沿用**，重跑生成标准命名文件。
3. `⬜` 表示该端点此前无产物，重跑需新建。
4. `✅` 表示命名已一致，可直接复用或覆盖。
5. `🧹` legacy 文件为旧会话多余产物，建议清理或归档（不入重跑范围）。
