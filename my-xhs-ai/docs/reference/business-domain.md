# my-xhs 业务域全景

> 这是 AI 运营 Agent 的数据基础。Agent 的每个 MCP Tool 都从本文档的业务域分析中长出来。

---

## 基础设施

| 实例 | 端口 | 库/用途 |
|------|------|---------|
| MySQL-User | 13306 | my_xhs_user, my_xhs_analytics, my_xhs_notification, my_xhs_im |
| MySQL-Content | 13307 | my_xhs_content, my_xhs_product, my_xhs_cart, my_xhs_coupon, my_xhs_counter, my_xhs_search |
| MySQL-Order | 13308 | my_xhs_order (4分片), my_xhs_payment |
| MySQL-Inventory | 13309 | my_xhs_inventory (独立实例，隔离锁竞争) |
| Redis-Cache | 16380 | allkeys-lru 缓存 |
| Redis-Business | 16381 | Session/分布式锁/Feed流/计数/社交关系 |
| Elasticsearch | 19200 | Note/Product 搜索索引 |

---

## 12 个业务域

### 1. 用户域 (my-xhs-user, 端口 19001)

**核心表**：
| 表 | 关键字段 |
|----|---------|
| t_user | id, username, password(BCrypt), nickname, avatar, phone, email, status(0禁用/1正常), created_at |
| t_user_address | user_id, receiver_name, receiver_phone, province, city, district, is_default |

**核心 API**（只读）：
| 接口 | 用途 |
|------|------|
| GET /api/user/me | 当前用户信息 |
| GET /api/user/{userId}/info | 公开用户信息（脱敏） |
| GET /api/user/address/list | 地址列表 |

**关键指标**：DAU/MAU、日注册数、登录成功率

---

### 2. 内容域 (my-xhs-content, 端口 19002)

**核心表**：
| 表 | 关键字段 |
|----|---------|
| t_note | id, user_id, title, content, images(JSON), status(0草稿/1审核/2已发布/3下架), audit_status, created_at |
| t_comment | id, note_id, user_id, parent_id(楼中楼), content, created_at |
| t_topic | id, name(UNIQUE), note_count |
| t_local_message | topic, body, status(2失败/3死信), retry_count |

**笔记生命周期**：草稿(0) → 审核中(1) → 已发布(2) → 下架(3)

**关键指标**：日发布笔记数、审核通过率、草稿转化率、DFA 拦截率

---

### 3. 订单域 (my-xhs-order, 端口 19011)

**分片**：t_order 按 user_id%4 分 4 片（my_xhs_order_0~3），全局表 t_order_no_mapping 做订单号路由。

**核心表**：
| 表 | 关键字段 |
|----|---------|
| t_order | id, order_no(UNIQUE), user_id(分片键), total_amount, status(0待付/1已付/2已发/3完成/4取消/5退款), created_at |
| t_order_item | order_id, sku_id, spu_id, quantity, price, total_amount |
| t_order_event | order_id, event_type, from_status, to_status, payload(JSON) |
| t_order_snapshot | order_id, snapshot_data(完整订单JSON) |

**状态机**：
```
0(待付款) ──支付──→ 1(已付款) ──发货──→ 2(已发货) ──确认──→ 3(已完成)
    │ 取消              │ 退款
    ↘                  ↘
  4(已取消)           5(已退款)
```

**关键指标**：日订单量、支付成功率、超时关闭数、退款率、客单价

---

### 4. 支付域 (my-xhs-payment, 端口 19012)

**核心表**：
| 表 | 关键字段 |
|----|---------|
| t_payment | order_id, payment_no, amount, pay_type(1支付宝/2微信Mock), status(0待付/1成功/2失败/3退款) |
| t_refund | payment_id, order_id, refund_amount, reason, status(0退款中/1成功/2失败) |

**关键指标**：支付成功率、退款率、渠道分布

---

### 5. 库存域 (my-xhs-inventory, 端口 19009)

**核心表**：
| 表 | 关键字段 |
|----|---------|
| t_inventory | sku_id(UNIQUE), available_stock, locked_stock, freezing_stock(TCC冻结) |
| t_tcc_fence | xid+branch_id, action_name, status(1Try/2Confirm/3Cancel) |

**关键指标**：库存水位、预扣成功率、冻结库存占比

---

### 6. 商品域 (my-xhs-product, 端口 19006)

**核心表**：
| 表 | 关键字段 |
|----|---------|
| t_category | id, name, parent_id, level(1/2/3) — 三级分类树 |
| t_spu | id, name, category_id, status(0下架/1上架) |
| t_sku | id, spu_id, name, price, stock(冗余), specs(JSON) |

**关键指标**：上架商品数、分类分布、缓存命中率

---

### 7. 社交/行为域 (my-xhs-analytics, 端口 19003)

**核心表**：
| 表 | 关键字段 | 数据结构 |
|----|---------|---------|
| t_follow | user_id, follow_user_id, created_at | Redis ZSet（时间排序） |
| t_like | user_id, biz_type(1笔记/2评论), biz_id | Redis Set（仅需存在性） |
| t_favorite | user_id, note_id, created_at | Redis ZSet（时间排序） |
| t_user_behavior | user_id, note_id, behavior_type(1浏览2点赞3收藏4评论5分享6搜索), duration | MySQL |
| t_counter | target_type(1笔记/2用户), target_id, count_type(1点赞2收藏3评论4分享5浏览6粉丝7关注), count_value | MySQL + Redis Buffer |

**关键指标**：互动率、粉丝增长、行为分布

---

### 8. 优惠券域 (my-xhs-coupon, 端口 19010)

**核心表**：
| 表 | 关键字段 |
|----|---------|
| t_coupon_template | type(1满减2折扣3无门槛), discount_value, min_amount, total_count, remain_count, valid_end |
| t_user_coupon | user_id, coupon_id, claim_no(幂等), status(0未用1已用2过期), used_order_id |

**关键指标**：领券数、用券数、核销率、到期预警

---

### 9. 通知域 (my-xhs-notification, 端口 19013)

**核心表**：
| 表 | 关键字段 |
|----|---------|
| t_notification | user_id, type(1点赞2评论3关注4系统5订单), content, is_read, created_at |
| t_push_task | target_count, success_count, fail_count, status |
| t_push_task_fail | task_id, user_id, fail_reason, retry_count |

**关键指标**：通知发送量、已读率、推送成功率

---

### 10. 搜索域 (my-xhs-search, 端口 19016)

**数据源**：ES Note 索引 + Product 索引 + MySQL t_hot_search

**关键指标**：日搜索量、热搜 Top 20、无结果率、推荐 CTR

---

### 11. 购物车域 (my-xhs-cart, 端口 19008)

**架构**：Redis 权威数据源 + MQ 异步持久化 MySQL t_cart_item

---

### 12. 聚合层 (my-xhs-home, 端口 19015, BFF)

**职责**：聚合下游服务数据（Feed流、笔记/商品详情、用户主页），所有接口有降级策略。

---

## Top 20 运营问题（AI Agent 要能回答）

### 订单与交易
1. "今天订单量多少？比昨天/上周同期如何？"
2. "哪个商品卖得最好？"（按 SKU/SPU 聚合）
3. "支付��功率是多少？失败原因分布？"
4. "有多少订单超时未支付被关闭？"
5. "退款率最高的商品是哪些？"

### 内容与社交
6. "今天发布多少新笔记？审核通过率？"
7. "哪些话题最热门？"
8. "互动率最高的笔记有哪些？"
9. "哪些用户粉丝增长最快？"
10. "草稿超过 7 天未发布的占比？"

### 优惠券
11. "今天领券/用券量？核销率？"
12. "哪些券即将过期但还有大量剩余？"
13. "用券订单占比多少？"

### 搜索与推荐
14. "今天热搜 Top 20？"
15. "搜索无结果率是多少？"
16. "推荐 Feed 点击率趋势？"

### 基础设施与异常
17. "最近有哪些异常？"（死信/推送失败/事件异常/MQ DLQ）
18. "库存预扣失败率异常？"
19. "哪些 SKU 库存不足？"

### 用户与增长
20. "今天注册用户数？活跃度趋势？"

---

## AI Agent 查询能力

| 数据源 | 权限 | 内容 |
|--------|:---:|------|
| MySQL (13306/07/08/09) | 只读 | 所有 t_* 表 SELECT |
| Redis (16380/81) | 只读 | myxhs:* 前缀 Key |
| ES (19200) | 只读 | Note/Product 索引 |
| Prometheus | 只读 | /actuator/prometheus 指标 |
