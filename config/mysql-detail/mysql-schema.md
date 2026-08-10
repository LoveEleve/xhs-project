# MyXHS MySQL 数据库 Schema 文档

> 文档时间：2026-08-08  
> MySQL 版本：8.0  
> 密码：Xhs@2026#MySQL

---

## 一、MySQL 13306（user 库）

### 1. my_xhs_user — 用户模块

| 表名 | 行数 | 说明 |
|------|------|------|
| t_user | 73 | 用户表 |
| t_user_address | 14 | 收货地址表 |
| t_id_segment | 7 | 号段分配表 |

### 2. my_xhs_analytics — 行为分析

| 表名 | 行数 | 说明 |
|------|------|------|
| t_like | 39 | 点赞表 |
| t_favorite | 10 | 收藏表 |
| t_follow | 5 | 关注关系表 |
| t_user_behavior | 0 | 用户行为记录表 |

### 3. my_xhs_im — 即时通讯

| 表名 | 行数 | 说明 |
|------|------|------|
| t_chat_message | 12 | 聊天消息表 |
| t_chat_user_relation | 2 | 用户聊天关系表 |

### 4. my_xhs_notification — 通知模块

| 表名 | 行数 | 说明 |
|------|------|------|
| t_notification | 30 | 通知表 |
| t_push_task | 0 | 推送任务表 |
| t_push_task_fail | 0 | 推送失败记录表 |
| t_push_template | 5 | 推送模板表 |

### 5. nacos_config — Nacos 配置中心

12 张系统表，包含 `my-xhs` namespace 的微服务配置。

### 6. xxl_job — 任务调度

| 表名 | 行数 | 说明 |
|------|------|------|
| xxl_job_group | 6 | 执行器组 |
| xxl_job_info | 16 | 任务信息 |
| xxl_job_lock | 0 | 分布式锁 |
| xxl_job_log | 47,607 | 调度日志 |
| xxl_job_log_report | 17 | 日志报告 |
| xxl_job_logglue | 0 | GLUE 代码 |
| xxl_job_registry | 9 | 执行器注册 |
| xxl_job_user | 0 | 用户 |

### 7. seata_lab — Seata 测试

| 表名 | 行数 | 说明 |
|------|------|------|
| seata_account | 2 | 账户 |
| undo_log | 0 | AT 模式回滚日志 |

---

## 二、MySQL 13307（content 库）

### 1. my_xhs_content — 内容模块

| 表名 | 行数 | 说明 |
|------|------|------|
| t_note | 46 | 笔记表 |
| t_comment | 45 | 评论表 |
| t_topic | 0 | 话题表 |
| t_local_message | 45 | 本地消息表（Feed可靠性保障） |
| t_hot_search_snapshot | 479 | ⚠️ 热搜快照（新表） |

### 2. my_xhs_product — 商品模块

| 表名 | 行数 | 说明 |
|------|------|------|
| t_spu | 18 | 商品SPU表 |
| t_sku | 20 | 商品SKU表 |
| t_category | 16 | 商品分类表（三级分类树） |

### 3. my_xhs_cart — 购物车

| 表名 | 行数 | 说明 |
|------|------|------|
| t_cart_item | 8 | 购物车项表 |

### 4. my_xhs_coupon — 优惠券

| 表名 | 行数 | 说明 |
|------|------|------|
| t_coupon_template | 16 | 优惠券模板表 |
| t_user_coupon | 18 | 用户优惠券表 |
| t_coupon_outbox | 2 | ⚠️ 本地消息表（outbox兜底，新表） |

### 5. my_xhs_counter — 计数

| 表名 | 行数 | 说明 |
|------|------|------|
| t_counter | 82 | 计数表（点赞/收藏/评论计数） |

### 6. my_xhs_search — 搜索

| 表名 | 行数 | 说明 |
|------|------|------|
| t_hot_search | 5 | 热搜词表 |
| t_hot_search_snapshot | 287 | 热搜快照表 |

---

## 三、MySQL 13308（order 库）— 分库分表

### 分片策略
- **分片键**：user_id % 4 → my_xhs_order_{0..3}
- **非分片键查询**：t_order_no_mapping 路由表

### 1. my_xhs_order — 路由表

| 表名 | 行数 | 说明 |
|------|------|------|
| t_order_no_mapping | 125 | 订单号→分片映射表 |

### 2. my_xhs_order_0 — 分片 0

| 表名 | 行数 | 说明 |
|------|------|------|
| t_order_{0..3} × 4 | 6 | 订单表（按 user_id 分区） |
| t_order_item_{0..3} × 4 | 6 | 订单明细表 |
| t_order_event_{0..3} × 4 | 6 | 订单事件流（Event Sourcing） |
| t_order_snapshot_{0..3} × 4 | 12 | 订单快照表 |
| t_local_message_{0..3} × 4 | 6 | 本地消息表 |

### 3. my_xhs_order_1 — 分片 1

| 表名 | 行数 | 说明 |
|------|------|------|
| t_order_{0..3} × 4 | 84 | 订单表 |
| t_order_item_{0..3} × 4 | 84 | 订单明细表 |
| t_order_event_{0..3} × 4 | 110 | 订单事件流 |
| t_order_snapshot_{0..3} × 4 | 192 | 订单快照表 |
| t_local_message_{0..3} × 4 | 84 | 本地消息表 |

### 4. my_xhs_order_2 — 分片 2

| 表名 | 行数 | 说明 |
|------|------|------|
| t_order_{0..3} × 4 | 25 | 订单表 |
| t_order_item_{0..3} × 4 | 25 | 订单明细表 |
| t_order_event_{0..3} × 4 | 37 | 订单事件流 |
| t_order_snapshot_{0..3} × 4 | 62 | 订单快照表 |
| t_local_message_{0..3} × 4 | 25 | 本地消息表 |

### 5. my_xhs_order_3 — 分片 3

| 表名 | 行数 | 说明 |
|------|------|------|
| t_order_{0..3} × 4 | 10 | 订单表 |
| t_order_item_{0..3} × 4 | 10 | 订单明细表 |
| t_order_event_{0..3} × 4 | 12 | 订单事件流 |
| t_order_snapshot_{0..3} × 4 | 22 | 订单快照表 |
| t_local_message_{0..3} × 4 | 10 | 本地消息表 |

### 6. my_xhs_payment — 支付模块

| 表名 | 行数 | 说明 |
|------|------|------|
| t_payment | 68 | 支付记录表 |
| t_refund | 7 | 退款单表 |

---

## 四、MySQL 13309（inventory 库）

### 1. my_xhs_inventory — 库存模块

| 表名 | 行数 | 说明 |
|------|------|------|
| t_inventory | 28 | 库存表 |
| t_inventory_outbox | 17 | ⚠️ 本地消息表（outbox兜底，新表） |
| t_inventory_compensation | 0 | 库存补偿表 |
| t_tcc_fence | 12 | TCC 防悬挂表 |
| t_tcc_freeze_detail | 0 | TCC 冻结明细表 |

---

## 五、账号权限

`root@21.214.97.212` 在所有业务库上拥有权限：

| MySQL | 权限范围 |
|-------|----------|
| 13306 | `*.*` WITH GRANT OPTION（全权限） |
| 13307 | 6 个业务库 `SELECT,INSERT,UPDATE,DELETE,CREATE` |
| 13308 | 6 个业务库 `SELECT,INSERT,UPDATE,DELETE,CREATE` |
| 13309 | `my_xhs_inventory` `SELECT,INSERT,UPDATE,DELETE,CREATE` |

---

## 六、DDL 来源

表结构由以下 SQL 文件定义（首次部署时执行）：

```
sql/mysql-user-init.sql          → 13306 user 相关表
sql/mysql-content-init.sql       → 13307 content/product/cart 表
sql/mysql-order-init.sql         → 13308 order 分片表 + payment 表
sql/mysql-inventory-init.sql     → 13309 inventory 表
```

> ⚠️ 标记的表为运行期间新增，不在初始 SQL 中，需补充到建表脚本。

