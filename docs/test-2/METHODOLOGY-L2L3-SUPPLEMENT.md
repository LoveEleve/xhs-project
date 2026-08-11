# 方法论 L2/L3 验证补充（数据层 + 生产级质量）

> 2026-08-10 | 诚实声明：此前多数执行文件只做了 **L1(HTTP)**，**L2(数据验证)与 L3(九透镜)严重缺失**。本文档按方法论补齐每个模块的 **L2 数据验证**（Redis key / MySQL 表 / MQ topic + 验证命令）与 **L3 九透镜**检查项。
> 适用：前端重测时，每个端点除 L1 外，必须按本表执行 L2/L3。**禁止只验 HTTP 就标通过**。

---

## 通用：L3 九透镜检查清单（每个端点适用）

| # | 透镜 | 检查项 |
|:--:|------|------|
| 1 | 性能 | RT < 500ms？@RateLimit 生效(429)？ |
| 2 | 可扩展 | 分片键？无状态？Redis 缓存命中？ |
| 3 | 微服务 | Feign 超时/降级/熔断？ |
| 4 | 分布式 | 数据源主/从/分片正确？事务消息/Outbox？ |
| 5 | 并发 | 锁粒度？幂等？Lua 原子？ |
| 6 | 安全 | JWT/HMAC/AdminToken/脱敏？ |
| 7 | 弹性 | 降级/重试/超时？Redis 故障放行？ |
| 8 | 可观测 | TraceId 传递？指标/日志？ |
| 9 | 一致性 | 缓存延迟双删？主从延迟？MQ 最终一致？ |

---

## 1. user（链1）

| 端点 | L2 数据验证（Redis/MySQL/MQ） | L3 要点 |
|------|------|------|
| U03-login | Redis `myxhs:user:token:access:{id}`(TTL1800)、`refresh`(TTL604800)；MySQL `t_user.last_login_time` 更新 | 安全:BCrypt+验证码一次性；并发:登录锁 |
| U06-me | MySQL `SELECT * FROM t_user WHERE id=?` | 性能:RT |
| U07-update-me | MySQL `t_user.nickname` 更新；Redis 用户缓存失效(延迟双删) | 一致性:更新后读新值 |
| U10-U13 地址 | MySQL `t_user_address` 增删改；列表一致 | 并发:地址上限 |
| U-B1/B2/B3 | Redis `myxhs:user:block:{userId}` set | 一致性:屏蔽后信息不可见 |
| U05-logout | Redis token 黑名单，登出后原 token 401 | 安全:黑名单生效 |

## 2. product（链2）

| 端点 | L2 数据验证 | L3 要点 |
|------|------|------|
| P01/P06 创建 | MySQL `t_spu`/`t_sku` 新增行 | 一致性:创建后立即读 |
| P03/P07 详情 | Redis `myxhs:product:spu:{id}` 回填(TTL1800)、`skuList[].image` 非空 | 一致性:缓存回填 |
| P02/P05 更新/下架 | Redis 延迟双删；下架后 P10 搜索不可见(Canal同步) | 一致性 |
| P08-sku-batch | 返回 SKU 含 image | 微服务:内部调用 |
| P10-search | ES `product_index` 有数据 | 性能:ES查询 |

## 3. cart（链3）

| 端点 | L2 数据验证 | L3 要点 |
|------|------|------|
| C01-add | Redis `myxhs:cart:{userId}` + MySQL `t_cart_item` 1行 | 一致性:加购即同步 |
| C02/C03 | 数量更新/删除后 Redis+MySQL 一致 | 并发:改量并发 |
| C06-list | 与 MySQL 一致 | 微服务:商品名Feign |
| C08-clear | Redis+MySQL 清空 | — |

## 4. coupon（链4）

| 端点 | L2 数据验证 | L3 要点 |
|------|------|------|
| N04-claim | Redis `myxhs:coupon:{templateId}:stock` DECR + `myxhs:coupon:{templateId}:claimed:{userId}` INCR；MySQL `t_user_coupon` 新增(异步MQ) | 并发:超卖(Redis Lua原子)；安全:限领 |
| N05/N06 | MySQL `t_user_coupon`(status=0) | 一致性:领后可见(等MQ) |
| N08-use | MySQL `t_user_coupon.status=1(USED)`；Redis stock INCR | 并发:并发用券一次成功 |
| N09-return | status=0；Redis stock INCR | 幂等 |
| N07-discount | 折扣计算正确(8.5折=减免非打折价) | — |

## 5. order + payment（链5）

| 端点 | L2 数据验证 | L3 要点 |
|------|------|------|
| D01-create | 分片库 `my_xhs_order_2.t_order_0`(按userId路由)新增+明细+本地消息表同事务；Redis 预扣库存 | 分布式:TCC预扣/事务消息/Outbox;一致性 |
| D08/D10 支付 | 支付单 status 流转；order status→1 | 事务消息 |
| D05-cancel | order status→4(已取消)；退库存/退券 | 幂等/补偿 |
| MQ | `ORDER_TRANSACTION_TOPIC`/`ORDER_CLOSE_TOPIC` 消费 | 积压检查 |

> ⚠️ 查订单须跨物理库：`SHOW DATABASES LIKE 'my_xhs_order%'`，定位 `my_xhs_order_{n}.t_order_{m}`（见 pitfalls#43）。

## 6. content-social（链6）

| 端点 | L2 数据验证 | L3 要点 |
|------|------|------|
| NC01-publish | MySQL `t_note`；发FEED/SOCIAL事件 | 异步:FEED_TOPIC |
| LK01/FA01 | Redis `myxhs:like:note:{noteId}`(点赞) / `myxhs:favorite:{type}:{id}`(收藏) / counter | 幂等(5s) |
| CM01-comment | MySQL `t_comment` | RateLimit 10/min |
| FW01-follow | Redis 关注关系 + counter | 双向 |
| MQ | `SOCIAL_TOPIC`/`FEED_TOPIC` 消费(供notification/counter/home) | 一致性 |

## 7. counter（链6）

| 端点 | L2 数据验证 | L3 要点 |
|------|------|------|
| CT01 | Redis `myxhs:counter:{targetType}:{targetId}:{countType}` 计数=操作数 | 幂等去重 |
| CT02 | 批量返回 like/collect/comment | — |

## 8. home（链6）

| 端点 | L2 数据验证 | L3 要点 |
|------|------|------|
| H01-feed | Redis `myxhs:feed:inbox:{userId}` → 笔记详情聚合 | 一致性;⚠️疑似bug(空) |
| H06/H07 | Redis inbox/outbox zset 写入 | — |

## 9. search（链6）

| 端点 | L2 数据验证 | L3 要点 |
|------|------|------|
| S01/S02 | ES `note_index`/`product_index` 命中 | 性能:ES |
| S08-hot | Redis 热搜榜 | 一致性:置顶优先 |
| S07-rebuild | ES 索引文档数 | — |

## 10. notification（链7）

| 端点 | L2 数据验证 | L3 要点 |
|------|------|------|
| N03/N04 | MySQL `t_notification`；Redis 未读数 | 幂等:msgId去重 |
| N05/N06 | `t_notification.is_read` 更新 | — |
| 事件 | NOTIFICATION 消费者(like/follow/comment→通知) | ⚠️曾崩溃#48已修 |

## 11. im（链7）

| 端点 | L2 数据验证 | L3 要点 |
|------|------|------|
| W03-messages | MySQL `t_chat_message` | — |
| WS | Redis `myxhs:im:route:{userId}` 在线路由 | — |

## 12. inventory（链5依赖）

| 端点 | L2 数据验证 | L3 要点 |
|------|------|------|
| I01-init | Redis `inventory:{skuId}:total` 桶 | 限流5/60s |
| I02-I04 | Redis 预扣/确认/释放 + MySQL `t_tcc_fence` | 分布式:TCC |
| MQ | `INVENTORY_TOPIC` 消费 | — |

---

## 结论（诚实）
- 此前**只覆盖了 L1（HTTP 状态码）**，L2/L3 未逐端点系统执行。
- 本文档补齐了每个模块的 **L2 数据验证映射**（Redis key / MySQL 表 / MQ topic / 验证命令）和 **L3 九透镜**检查项。
- **前端重测时必须逐端点执行 L0→L4**，尤其 L2 数据落库验证和 L3 并发/安全/一致性，禁止"HTTP 200 即通过"。

---

## 附录A：L3 并发/限流/幂等 — 真实注解参数（源码核对，2026-08-10）

> L3 并发透镜：按下列真实参数触发超限/幂等拦截验证。格式：`限流 maxRequests/windowSeconds`、`perUser`、`幂等 N 秒`。

| 端点 | 真实参数 |
|------|------|
| LK01-like | RateLimit 30/60s perUser + **Idempotent 5s** |
| FA01-favorite | RateLimit 30/60s perUser + **Idempotent 5s** |
| FW01-follow | 20/60s perUser |
| C01-add / C02 / C03 | 20/60s perUser |
| C04-check | 30/60s perUser |
| C05-check-all | 10/60s perUser |
| C06-list | 60/60s perUser |
| C07-merge | 10/60s perUser |
| C08-clear | **3/60s** perUser |
| C09-count | 120/60s perUser |
| CM01-create-comment | 10/60s perUser |
| NC01-publish-note | **5/60s** perUser |
| NC09-upload-image | 20/60s perUser |
| NC-share | 10/60s perUser |
| CT01-counter-get | **50/1s**（秒级限流） |
| CT03-reconcile | 2/60s |
| N01-template-create | 5/60s |
| N04-claim | 5/60s perUser |
| N05-read | 30/60s perUser |
| N06-read-by-type | 10/60s perUser |
| N07-read-all | 5/60s perUser |
| W04-read | 20/60s perUser |
| I01-init | 5/60s（间隔≥15s） |
| I05-reinit | 2/60s |
| D01-order-create | 5/60s perUser |
| D05-order-cancel | 10/60s perUser |
| D-deliver | 10/60s perUser |
| PAY-pay | 10/60s perUser |
| PAY-refund | 5/60s perUser |
| P01-spu-create | 5/60s perUser + **Idempotent 10s**(spu:create:name:categoryId) |

> ⚠️ 验证方法：连续调用超过 maxRequests 应返回 429"操作过于频繁"；Idempotent 窗口内重复调用应被拦截。
