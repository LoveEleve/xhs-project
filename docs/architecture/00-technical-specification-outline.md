# my-xhs 技术规格大纲

> 完整的技术设计规格，包含所有微服务、数据库表、功能清单、解决方案选型
>
> 📎 **核心版本矩阵**（本文档为权威来源，其他文档以此为准）：
>
> | 组件 | 版本 | 说明 |
> |------|------|------|
> | JDK | 17 | LTS版本 |
> | Spring Boot | 3.2.x | 配合JDK 17 |
> | Spring Cloud | 2023.0.x | 配合Spring Boot 3.2.x |
> | Spring Cloud Alibaba | 2023.0.1.0 | 配合Spring Boot 3.2.x + Nacos 2.x |
> | MySQL | 8.0 | 业务数据存储 |
> | Redis | 7.x | 缓存/分布式锁/会话 |
> | RocketMQ | 5.1.x | 异步消息/事务消息 |
> | Elasticsearch | 8.12.x | 搜索引擎（使用RestClient） |
> | Nacos | 2.3.x | 注册中心+配置中心 |
> | ShardingSphere | 5.4.1 | 分库分表+读写分离 |

---

## 一、微服务模块清单

| 序号 | 服务名 | 端口 | 职责 | 数据库 |
|------|--------|------|------|--------|
| 1 | my-xhs-gateway | 9000 | API网关、鉴权、限流、路由 | 无 |
| 2 | my-xhs-user | 9001 | 用户注册登录、收货地址 | my_xhs_user |
| 3 | my-xhs-note | 9002 | 笔记发布、评论 | my_xhs_note |
| 4 | my-xhs-social | 9003 | 关注、点赞、收藏 | my_xhs_social |
| 5 | my-xhs-counter | 9004 | 计数服务（赞/藏/评/粉） | my_xhs_counter |
| 6 | my-xhs-home | 9005 | 首页Feed流聚合 | 无(聚合服务) |
| 7 | my-xhs-product | 9006 | 商品SPU/SKU、分类 | my_xhs_product |
| 8 | my-xhs-search | 9007 | ES搜索、搜索建议、热搜 | 无（ES） |
| 9 | my-xhs-cart | 9008 | 购物车 | my_xhs_cart |
| 10 | my-xhs-inventory | 9009 | 库存管理 | my_xhs_inventory |
| 11 | my-xhs-coupon | 9010 | 优惠券 | my_xhs_coupon (分库) |
| 12 | my-xhs-order | 9011 | 订单 | my_xhs_order (分库) |
| 13 | my-xhs-push | 9012 | 消息推送(通知中心) | my_xhs_push |
| 14 | my-xhs-im | 9013 | 即时通讯(私信) | my_xhs_im |
| 15 | my-xhs-admin | 9014 | 后台管理 | my_xhs_admin |

> **说明**：前端不需要实现；支付用模拟实现（不依赖支付宝/微信SDK），订单服务内置MockPayService，生产环境切真实支付只需加1个实现类

---

## 二、数据库表设计

### 2.1 用户服务 (my_xhs_user)

| 表名 | 说明 | 预估数据量 |
|------|------|-----------|
| t_user | 用户表 | 1000万 |
| t_user_address | 收货地址表 | 5000万 |

```sql
-- 用户表
CREATE TABLE t_user (
    id BIGINT PRIMARY KEY,
    username VARCHAR(32) NOT NULL COMMENT '用户名',
    password VARCHAR(128) NOT NULL COMMENT '密码(BCrypt)',
    phone VARCHAR(20) COMMENT '手机号',
    email VARCHAR(64) COMMENT '邮箱',
    nickname VARCHAR(32) COMMENT '昵称',
    avatar VARCHAR(256) COMMENT '头像URL',
    gender TINYINT DEFAULT 0 COMMENT '性别:0未知1男2女',
    points INT DEFAULT 0 COMMENT '用户积分',
    balance DECIMAL(16,2) DEFAULT 0.00 COMMENT '用户余额',
    vip TINYINT DEFAULT 0 COMMENT '是否VIP:0否1是',
    status TINYINT DEFAULT 1 COMMENT '状态:0禁用1正常',
    last_login_time DATETIME COMMENT '最后登录时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_username (username),
    UNIQUE KEY uk_phone (phone),
    UNIQUE KEY uk_email (email)
) ENGINE=InnoDB COMMENT='用户表';

-- 收货地址表
CREATE TABLE t_user_address (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    receiver_name VARCHAR(32) NOT NULL COMMENT '收货人',
    receiver_phone VARCHAR(20) NOT NULL COMMENT '手机号',
    province VARCHAR(32) NOT NULL COMMENT '省',
    city VARCHAR(32) NOT NULL COMMENT '市',
    district VARCHAR(32) NOT NULL COMMENT '区',
    detail_address VARCHAR(256) NOT NULL COMMENT '详细地址',
    is_default TINYINT DEFAULT 0 COMMENT '是否默认:0否1是',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_user_id (user_id)
) ENGINE=InnoDB COMMENT='收货地址表';
```

---

### 2.2 笔记服务 (my_xhs_note)

| 表名 | 说明 | 预估数据量 |
|------|------|-----------|
| t_note | 笔记表 | 5000万 |
| t_note_image | 笔记图片表 | 2亿 |
| t_comment | 评论表 | 5亿 |
| t_comment_report | 评论举报表 | 1000万 |

```sql
-- 笔记表
CREATE TABLE t_note (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '作者ID',
    title VARCHAR(100) NOT NULL COMMENT '标题',
    content TEXT COMMENT '正文',
    cover_image VARCHAR(256) COMMENT '封面图',
    note_type TINYINT DEFAULT 1 COMMENT '类型:1图文2视频',
    status TINYINT DEFAULT 0 COMMENT '状态:0待审核1已发布2已下架',
    like_count INT DEFAULT 0 COMMENT '点赞数',
    collect_count INT DEFAULT 0 COMMENT '收藏数',
    comment_count INT DEFAULT 0 COMMENT '评论数',
    view_count INT DEFAULT 0 COMMENT '浏览数',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_user_id (user_id),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB COMMENT='笔记表';

-- 笔记图片表
CREATE TABLE t_note_image (
    id BIGINT PRIMARY KEY,
    note_id BIGINT NOT NULL COMMENT '笔记ID',
    image_url VARCHAR(256) NOT NULL COMMENT '图片URL',
    sort_order INT DEFAULT 0 COMMENT '排序',
    KEY idx_note_id (note_id)
) ENGINE=InnoDB COMMENT='笔记图片表';

-- 评论表 (支持楼中楼)
CREATE TABLE t_comment (
    id BIGINT PRIMARY KEY,
    note_id BIGINT NOT NULL COMMENT '笔记ID',
    user_id BIGINT NOT NULL COMMENT '评论者ID',
    parent_id BIGINT DEFAULT 0 COMMENT '父评论ID,0为一级评论',
    root_id BIGINT DEFAULT 0 COMMENT '根评论ID',
    reply_user_id BIGINT DEFAULT 0 COMMENT '被回复用户ID',
    content VARCHAR(500) NOT NULL COMMENT '评论内容',
    like_count INT DEFAULT 0 COMMENT '点赞数',
    status TINYINT DEFAULT 1 COMMENT '状态:0待审核1正常2删除',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_note_id (note_id),
    KEY idx_user_id (user_id),
    KEY idx_parent_id (parent_id)
) ENGINE=InnoDB COMMENT='评论表';

-- 评论举报表
CREATE TABLE t_comment_report (
    id BIGINT PRIMARY KEY,
    comment_id BIGINT NOT NULL COMMENT '评论ID',
    reporter_id BIGINT NOT NULL COMMENT '举报人ID',
    report_type TINYINT NOT NULL DEFAULT 0 COMMENT '举报类型:1垃圾广告2色情低俗3政治敏感4人身攻击5违法信息6恶意刷屏7诈骗信息8侵权内容9其他',
    report_reason VARCHAR(500) COMMENT '举报原因描述',
    status TINYINT DEFAULT 0 COMMENT '处理状态:0待处理1已处理2已驳回',
    handle_result VARCHAR(256) COMMENT '处理结果',
    handle_time DATETIME COMMENT '处理时间',
    handler_id BIGINT COMMENT '处理人ID',
    satisfy TINYINT DEFAULT 0 COMMENT '举报结果满意度:0未反馈1满意2不满意',
    unsatisfy_type TINYINT COMMENT '不满意类型',
    unsatisfy_reason VARCHAR(256) COMMENT '不满意原因',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_comment_id (comment_id),
    KEY idx_reporter_id (reporter_id),
    KEY idx_status (status)
) ENGINE=InnoDB COMMENT='评论举报表';
```

---

### 2.3 社交服务 (my_xhs_social)

| 表名 | 说明 | 预估数据量 |
|------|------|-----------|
| t_follow | 关注关系表 | 10亿 |
| t_like | 点赞表 | 50亿 |
| t_favorite | 收藏表 | 10亿 |

```sql
-- 关注关系表
CREATE TABLE t_follow (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    follow_user_id BIGINT NOT NULL COMMENT '被关注用户ID',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_follow (user_id, follow_user_id),
    KEY idx_follow_user_id (follow_user_id)
) ENGINE=InnoDB COMMENT='关注关系表';

-- 点赞表
CREATE TABLE t_like (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    biz_type TINYINT NOT NULL COMMENT '业务类型:1笔记2评论',
    biz_id BIGINT NOT NULL COMMENT '业务ID',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_biz (user_id, biz_type, biz_id),
    KEY idx_biz (biz_type, biz_id)
) ENGINE=InnoDB COMMENT='点赞表';

-- 收藏表
CREATE TABLE t_favorite (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    note_id BIGINT NOT NULL COMMENT '笔记ID',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_note (user_id, note_id),
    KEY idx_note_id (note_id)
) ENGINE=InnoDB COMMENT='收藏表';
```

---

### 2.4 计数服务 (my_xhs_counter)

| 表名 | 说明 | 预估数据量 |
|------|------|-----------|
| t_counter | 计数表 | 10亿 |

```sql
-- 计数表
CREATE TABLE t_counter (
    id BIGINT PRIMARY KEY,
    biz_type VARCHAR(32) NOT NULL COMMENT '业务类型:note_like/note_collect/user_follower等',
    biz_id BIGINT NOT NULL COMMENT '业务ID',
    count_value BIGINT DEFAULT 0 COMMENT '计数值',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_biz (biz_type, biz_id)
) ENGINE=InnoDB COMMENT='计数表';
```

---

### 2.5 消息推送服务 (my_xhs_push)

| 表名 | 说明 | 预估数据量 |
|------|------|-----------|
| t_push_template | 推送模板表 | 100 |
| t_push_message | 推送消息表 | 100亿 (分表) |
| t_push_task | 推送任务表 | 1000万 |
| t_push_task_fail | 推送失败记录表 | 100万 |

```sql
-- 推送模板表
CREATE TABLE t_push_template (
    id BIGINT PRIMARY KEY,
    type VARCHAR(32) NOT NULL COMMENT '类型:like/comment/follow/system/coupon',
    title_template VARCHAR(128) COMMENT '标题模板',
    content_template VARCHAR(512) COMMENT '内容模板',
    status TINYINT DEFAULT 1,
    UNIQUE KEY uk_type (type)
) ENGINE=InnoDB COMMENT='推送模板表';

-- 推送消息表 (按user_id分表)
CREATE TABLE t_push_message (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '接收用户ID',
    type VARCHAR(32) NOT NULL COMMENT '推送类型',
    sender_id BIGINT COMMENT '发送者ID',
    biz_type VARCHAR(32) COMMENT '业务类型',
    biz_id BIGINT COMMENT '业务ID',
    title VARCHAR(128) COMMENT '标题',
    content VARCHAR(512) COMMENT '内容',
    is_read TINYINT DEFAULT 0 COMMENT '是否已读',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_user_id (user_id),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB COMMENT='推送消息表';

-- 推送任务表 (用于批量推送如券推送)
CREATE TABLE t_push_task (
    id BIGINT PRIMARY KEY,
    task_name VARCHAR(64) NOT NULL COMMENT '任务名称',
    task_type TINYINT NOT NULL COMMENT '任务类型:1券推送2系统通知',
    biz_id BIGINT COMMENT '业务ID(如券模板ID)',
    target_count INT NOT NULL COMMENT '目标用户数',
    success_count INT DEFAULT 0 COMMENT '成功数',
    fail_count INT DEFAULT 0 COMMENT '失败数',
    status TINYINT DEFAULT 0 COMMENT '状态:0待执行1执行中2已完成3已取消',
    execute_time DATETIME COMMENT '执行时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_status (status)
) ENGINE=InnoDB COMMENT='推送任务表';

-- 推送失败记录表
CREATE TABLE t_push_task_fail (
    id BIGINT PRIMARY KEY,
    task_id BIGINT NOT NULL COMMENT '任务ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    fail_reason VARCHAR(256) COMMENT '失败原因',
    retry_count INT DEFAULT 0 COMMENT '重试次数',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_task_id (task_id)
) ENGINE=InnoDB COMMENT='推送失败记录表';
```

---

### 2.6 商品服务 (my_xhs_product)

| 表名 | 说明 | 预估数据量 |
|------|------|-----------|
| t_category | 分类表 | 1000 |
| t_spu | SPU表 | 100万 |
| t_sku | SKU表 | 1000万 |
| t_spu_attribute | SPU属性表 | 500万 |
| t_sku_attribute | SKU规格表 | 5000万 |

```sql
-- 分类表
CREATE TABLE t_category (
    id BIGINT PRIMARY KEY,
    parent_id BIGINT DEFAULT 0 COMMENT '父分类ID',
    name VARCHAR(64) NOT NULL COMMENT '分类名称',
    level TINYINT DEFAULT 1 COMMENT '层级:1/2/3',
    sort_order INT DEFAULT 0 COMMENT '排序',
    icon VARCHAR(256) COMMENT '图标',
    status TINYINT DEFAULT 1,
    KEY idx_parent_id (parent_id)
) ENGINE=InnoDB COMMENT='分类表';

-- SPU表
CREATE TABLE t_spu (
    id BIGINT PRIMARY KEY,
    category_id BIGINT NOT NULL COMMENT '分类ID',
    brand_id BIGINT COMMENT '品牌ID',
    name VARCHAR(128) NOT NULL COMMENT '商品名称',
    sub_title VARCHAR(256) COMMENT '副标题',
    main_image VARCHAR(256) COMMENT '主图',
    images TEXT COMMENT '图片列表JSON',
    detail TEXT COMMENT '详情(富文本)',
    status TINYINT DEFAULT 0 COMMENT '状态:0下架1上架',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_category_id (category_id)
) ENGINE=InnoDB COMMENT='SPU表';

-- SKU表
CREATE TABLE t_sku (
    id BIGINT PRIMARY KEY,
    spu_id BIGINT NOT NULL COMMENT 'SPU ID',
    sku_code VARCHAR(64) COMMENT 'SKU编码',
    name VARCHAR(256) COMMENT 'SKU名称',
    price DECIMAL(10,2) NOT NULL COMMENT '价格',
    original_price DECIMAL(10,2) COMMENT '原价',
    stock INT DEFAULT 0 COMMENT '库存(冗余)',
    image VARCHAR(256) COMMENT 'SKU图片',
    specs VARCHAR(512) COMMENT '规格JSON',
    status TINYINT DEFAULT 1,
    KEY idx_spu_id (spu_id)
) ENGINE=InnoDB COMMENT='SKU表';
```

---

### 2.7 购物车服务 (my_xhs_cart)

> 购物车采用 Redis + MySQL 双写方案：Redis 承载高频读写，MySQL 异步落库做持久化兜底（Redis 故障时从 MySQL 恢复）

| 表名 | 说明 | 预估数据量 |
|------|------|-----------|
| t_cart_item | 购物车明细表 | 5000万 |
| t_cart_snapshot | 购物车快照表 | 1000万 |

```sql
-- 购物车明细表
CREATE TABLE t_cart_item (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    sku_id BIGINT NOT NULL COMMENT 'SKU ID',
    spu_id BIGINT COMMENT 'SPU ID',
    quantity INT NOT NULL DEFAULT 1 COMMENT '数量',
    checked TINYINT NOT NULL DEFAULT 1 COMMENT '是否选中:0否1是',
    add_time DATETIME NOT NULL COMMENT '加购时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    UNIQUE KEY uk_user_sku (user_id, sku_id),
    KEY idx_user_id (user_id)
) ENGINE=InnoDB COMMENT='购物车明细表';

-- 购物车快照表（定时全量同步Redis购物车，故障恢复用）
CREATE TABLE t_cart_snapshot (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    snapshot_data JSON NOT NULL COMMENT '购物车快照数据',
    create_time DATETIME NOT NULL COMMENT '快照时间',
    UNIQUE KEY uk_user_id (user_id)
) ENGINE=InnoDB COMMENT='购物车快照表';
```

---

### 2.9 库存服务 (my_xhs_inventory)

| 表名 | 说明 | 预估数据量 |
|------|------|-----------|
| t_inventory | 库存表 | 1000万 |
| t_inventory_bucket | 库存分桶表 | 5000万 |
| t_inventory_log | 库存流水表 | 10亿 |

```sql
-- 库存表
CREATE TABLE t_inventory (
    id BIGINT PRIMARY KEY,
    sku_id BIGINT NOT NULL COMMENT 'SKU ID',
    total_stock INT NOT NULL DEFAULT 0 COMMENT '总库存',
    locked_stock INT NOT NULL DEFAULT 0 COMMENT '锁定库存',
    available_stock INT NOT NULL DEFAULT 0 COMMENT '可用库存',
    bucket_count INT DEFAULT 8 COMMENT '分桶数量',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sku_id (sku_id)
) ENGINE=InnoDB COMMENT='库存表';

-- 库存分桶表
CREATE TABLE t_inventory_bucket (
    id BIGINT PRIMARY KEY,
    sku_id BIGINT NOT NULL COMMENT 'SKU ID',
    bucket_no INT NOT NULL COMMENT '桶编号',
    available_stock INT NOT NULL DEFAULT 0 COMMENT '桶可用库存',
    UNIQUE KEY uk_sku_bucket (sku_id, bucket_no)
) ENGINE=InnoDB COMMENT='库存分桶表';

-- 库存流水表
CREATE TABLE t_inventory_log (
    id BIGINT PRIMARY KEY,
    sku_id BIGINT NOT NULL COMMENT 'SKU ID',
    order_id BIGINT COMMENT '订单ID',
    change_type TINYINT NOT NULL COMMENT '变更类型:1扣减2回退3初始化',
    change_quantity INT NOT NULL COMMENT '变更数量',
    before_stock INT COMMENT '变更前库存',
    after_stock INT COMMENT '变更后库存',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_sku_id (sku_id),
    KEY idx_order_id (order_id)
) ENGINE=InnoDB COMMENT='库存流水表';
```

---

### 2.10 优惠券服务 (my_xhs_coupon_0 ~ my_xhs_coupon_3)

| 表名 | 说明 | 预估数据量 | 分片策略 |
|------|------|-----------|---------|
| t_coupon_template | 券模板表 | 1万 | 不分片 |
| t_coupon_template_log | 券模板操作日志表 | 10万 | template_id % 16 |
| t_coupon_to_sku | 券商品关联表 | 100万 | 不分片 |
| t_coupon_push_task | 券推送任务表 | 10万 | 不分片 |
| t_coupon_push_task_fail | 券推送失败记录表 | 100万 | 不分片 |
| t_user_coupon | 用户券表 | 10亿 | buyer_id % 4 |

```sql
-- 券模板表 (不分片)
CREATE TABLE t_coupon_template (
    id BIGINT PRIMARY KEY,
    name VARCHAR(64) NOT NULL COMMENT '券名称',
    type TINYINT NOT NULL COMMENT '类型:1满减2折扣3无门槛',
    discount_amount DECIMAL(10,2) COMMENT '减免金额',
    discount_rate DECIMAL(3,2) COMMENT '折扣率',
    min_amount DECIMAL(10,2) DEFAULT 0 COMMENT '使用门槛',
    max_discount DECIMAL(10,2) COMMENT '最大优惠金额',
    total_count INT NOT NULL COMMENT '发放总量',
    claimed_count INT DEFAULT 0 COMMENT '已领取数量',
    per_limit INT DEFAULT 1 COMMENT '每人限领',
    valid_type TINYINT DEFAULT 1 COMMENT '有效期类型:1固定日期2领取后N天',
    valid_start_time DATETIME COMMENT '生效开始时间',
    valid_end_time DATETIME COMMENT '生效结束时间',
    valid_days INT COMMENT '领取后有效天数',
    status TINYINT DEFAULT 0 COMMENT '状态:0未开始1进行中2已结束',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='券模板表';

-- 券模板操作日志表 (分16表)
CREATE TABLE t_coupon_template_log (
    id BIGINT PRIMARY KEY,
    template_id BIGINT NOT NULL COMMENT '模板ID',
    operation_type TINYINT NOT NULL COMMENT '操作类型:1创建2修改3上线4下线',
    operator_id BIGINT COMMENT '操作人ID',
    operator_name VARCHAR(32) COMMENT '操作人姓名',
    remark VARCHAR(256) COMMENT '备注',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_template_id (template_id)
) ENGINE=InnoDB COMMENT='券模板操作日志表';

-- 券商品关联表 (券可用于哪些商品)
CREATE TABLE t_coupon_to_sku (
    id BIGINT PRIMARY KEY,
    template_id BIGINT NOT NULL COMMENT '券模板ID',
    sku_id BIGINT NOT NULL COMMENT 'SKU ID',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_template_sku (template_id, sku_id),
    KEY idx_sku_id (sku_id)
) ENGINE=InnoDB COMMENT='券商品关联表';

-- 券推送任务表 (用于批量发券)
CREATE TABLE t_coupon_push_task (
    id BIGINT PRIMARY KEY,
    template_id BIGINT NOT NULL COMMENT '券模板ID',
    task_name VARCHAR(64) NOT NULL COMMENT '任务名称',
    target_type TINYINT NOT NULL COMMENT '目标类型:1全部用户2指定用户3条件筛选',
    target_condition TEXT COMMENT '筛选条件JSON',
    target_count INT NOT NULL COMMENT '目标用户数',
    success_count INT DEFAULT 0 COMMENT '成功数',
    fail_count INT DEFAULT 0 COMMENT '失败数',
    status TINYINT DEFAULT 0 COMMENT '状态:0待执行1执行中2已完成3已取消',
    execute_time DATETIME COMMENT '计划执行时间',
    start_time DATETIME COMMENT '实际开始时间',
    end_time DATETIME COMMENT '实际结束时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_template_id (template_id),
    KEY idx_status (status)
) ENGINE=InnoDB COMMENT='券推送任务表';

-- 券推送失败记录表
CREATE TABLE t_coupon_push_task_fail (
    id BIGINT PRIMARY KEY,
    task_id BIGINT NOT NULL COMMENT '任务ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    fail_reason VARCHAR(256) COMMENT '失败原因',
    retry_count INT DEFAULT 0 COMMENT '重试次数',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_task_id (task_id)
) ENGINE=InnoDB COMMENT='券推送失败记录表';

-- 用户券表 (分32表)
CREATE TABLE t_user_coupon (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    buyer_id BIGINT NOT NULL COMMENT '买家ID(分片键,值同user_id)',
    template_id BIGINT NOT NULL COMMENT '模板ID',
    coupon_code VARCHAR(32) COMMENT '券码',
    status TINYINT DEFAULT 0 COMMENT '状态:0未使用1已使用2已过期',
    order_id BIGINT COMMENT '使用的订单ID',
    valid_start_time DATETIME COMMENT '生效时间',
    valid_end_time DATETIME COMMENT '失效时间',
    used_time DATETIME COMMENT '使用时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_user_id (user_id),
    KEY idx_template_id (template_id),
    KEY idx_buyer_id (buyer_id)
) ENGINE=InnoDB COMMENT='用户券表';
```

---

### 2.11 订单服务 (my_xhs_order_0 ~ my_xhs_order_3)

| 表名 | 说明 | 预估数据量 | 分片策略 |
|------|------|-----------|---------|
| t_order | 订单主表 | 10亿 | buyer_id % 4 |
| t_order_item | 订单明细表 | 30亿 | buyer_id % 4 |
| t_order_snapshot | 订单快照流水表 | 20亿 | order_id % 4 |
| t_local_message | 本地消息表 | 1亿 | 不分片 |

```sql
-- 订单主表 (分4库)
CREATE TABLE t_order (
    id BIGINT PRIMARY KEY,
    order_no VARCHAR(32) NOT NULL COMMENT '订单号',
    biz_identifier VARCHAR(128) NOT NULL COMMENT '幂等号',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    total_amount DECIMAL(10,2) NOT NULL COMMENT '订单总金额',
    pay_amount DECIMAL(10,2) NOT NULL COMMENT '实付金额',
    freight_amount DECIMAL(10,2) DEFAULT 0 COMMENT '运费',
    discount_amount DECIMAL(10,2) DEFAULT 0 COMMENT '优惠金额',
    coupon_id BIGINT COMMENT '优惠券ID',
    coupon_name VARCHAR(64) COMMENT '优惠券名称',
status TINYINT NOT NULL COMMENT '状态:1已创建2已确认3已支付4已履约5出库中6配送中7已签收8已取消9已退款',
    close_type TINYINT COMMENT '关单类型:1超时关单2用户取消',
    receiver_name VARCHAR(32) COMMENT '收货人',
    receiver_phone VARCHAR(20) COMMENT '收货电话',
    receiver_address VARCHAR(256) COMMENT '收货地址',
    pay_type TINYINT COMMENT '支付方式:0模拟1支付宝2微信',
    pay_time DATETIME COMMENT '支付时间',
    pay_trade_no VARCHAR(128) COMMENT '支付流水号(模拟支付以MOCK_开头)',
    deliver_time DATETIME COMMENT '发货时间',
    receive_time DATETIME COMMENT '收货时间',
    finish_time DATETIME COMMENT '完成时间',
    cancel_time DATETIME COMMENT '取消时间',
    remark VARCHAR(256) COMMENT '订单备注',
    lock_version INT DEFAULT 0 COMMENT '乐观锁版本号',
    snapshot_version INT DEFAULT 0 COMMENT '快照版本号',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_order_no (order_no),
    UNIQUE KEY uk_biz_identifier (biz_identifier),
    KEY idx_user_id (user_id),
    KEY idx_status (status),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB COMMENT='订单主表';

-- 订单明细表 (分4库，与主表同分片键)
CREATE TABLE t_order_item (
    id BIGINT PRIMARY KEY,
    order_id BIGINT NOT NULL COMMENT '订单ID',
    order_no VARCHAR(32) NOT NULL COMMENT '订单号',
    user_id BIGINT NOT NULL COMMENT '用户ID(分片键冗余)',
    spu_id BIGINT NOT NULL COMMENT 'SPU ID',
    sku_id BIGINT NOT NULL COMMENT 'SKU ID',
    sku_name VARCHAR(256) COMMENT 'SKU名称',
    sku_image VARCHAR(256) COMMENT 'SKU图片',
    sku_specs VARCHAR(256) COMMENT 'SKU规格',
    price DECIMAL(10,2) NOT NULL COMMENT '单价',
    quantity INT NOT NULL COMMENT '数量',
    total_amount DECIMAL(10,2) NOT NULL COMMENT '小计',
    KEY idx_order_id (order_id),
    KEY idx_user_id (user_id)
) ENGINE=InnoDB COMMENT='订单明细表';

-- 订单快照流水表 (记录订单状态变更历史)
CREATE TABLE t_order_snapshot (
    id BIGINT PRIMARY KEY,
    order_id BIGINT NOT NULL COMMENT '订单ID',
    snapshot_identifier VARCHAR(128) NOT NULL COMMENT '快照幂等号',
    snapshot_type TINYINT NOT NULL COMMENT '快照类型(对应订单状态)',
    snapshot_json LONGTEXT NOT NULL COMMENT '订单快照内容JSON',
    snapshot_version INT NOT NULL COMMENT '快照版本号',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_order_snapshot (order_id, snapshot_identifier, snapshot_type)
) ENGINE=InnoDB COMMENT='订单快照流水表';

-- 本地消息表 (用于分布式事务兜底)
CREATE TABLE t_local_message (
    id BIGINT PRIMARY KEY,
    message_id VARCHAR(64) NOT NULL COMMENT '消息ID',
    topic VARCHAR(64) NOT NULL COMMENT 'MQ Topic',
    tag VARCHAR(64) COMMENT 'MQ Tag',
    message_body TEXT NOT NULL COMMENT '消息体JSON',
    status TINYINT DEFAULT 0 COMMENT '状态:0待发送1已发送2发送失败',
    retry_count INT DEFAULT 0 COMMENT '重试次数',
    next_retry_time DATETIME COMMENT '下次重试时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_message_id (message_id),
    KEY idx_status_retry (status, next_retry_time)
) ENGINE=InnoDB COMMENT='本地消息表';
```

---

### 2.12 即时通讯服务 (my_xhs_im)

| 表名 | 说明 | 预估数据量 |
|------|------|-----------|
| t_chat | IM聊天记录表 | 50亿 (分表) |
| t_chat_user_relation | IM用户会话关系表 | 10亿 |

```sql
-- IM聊天记录表
CREATE TABLE t_chat (
    id BIGINT PRIMARY KEY,
    send_uid BIGINT NOT NULL COMMENT '发送者UID',
    accept_uid BIGINT NOT NULL COMMENT '接收者UID',
    content LONGTEXT COMMENT '消息内容',
    msg_type TINYINT DEFAULT 0 COMMENT '消息类型:0通知1文本2图片3语音4视频5自定义',
    chat_type TINYINT DEFAULT 0 COMMENT '聊天类型:0私聊1群聊',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_send_uid (send_uid),
    KEY idx_accept_uid (accept_uid)
) ENGINE=InnoDB COMMENT='IM聊天记录表';

-- IM用户会话关系表 (用于会话列表展示)
CREATE TABLE t_chat_user_relation (
    id BIGINT PRIMARY KEY,
    send_uid BIGINT NOT NULL COMMENT '发送者UID',
    accept_uid BIGINT NOT NULL COMMENT '接收者UID',
    content LONGTEXT COMMENT '最后一条消息内容',
    un_read_count INT DEFAULT 0 COMMENT '未读数',
    msg_type TINYINT DEFAULT 0 COMMENT '消息类型',
    chat_type TINYINT DEFAULT 0 COMMENT '聊天类型',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_accept_uid (accept_uid, created_at),
    KEY idx_send_accept (send_uid, accept_uid, created_at)
) ENGINE=InnoDB COMMENT='IM用户会话关系表';
```

---

### 2.13 后台管理服务 (my_xhs_admin)

| 表名 | 说明 | 预估数据量 |
|------|------|-----------|
| t_system_admin | 后台管理员表 | 1000 |
| t_system_role | 角色表 | 100 |
| t_system_menu | 菜单权限表 | 500 |
| t_system_attachment | 附件管理表 | 100万 |
| t_system_category | 分类表 | 1万 |
| t_system_city | 城市表(省市区) | 5000 |

```sql
-- 后台管理员表
CREATE TABLE t_system_admin (
    id INT PRIMARY KEY AUTO_INCREMENT,
    account VARCHAR(32) NOT NULL COMMENT '管理员账号',
    pwd VARCHAR(64) NOT NULL COMMENT '密码',
    real_name VARCHAR(16) NOT NULL COMMENT '姓名',
    roles VARCHAR(128) NOT NULL COMMENT '角色ID列表',
    last_ip VARCHAR(16) COMMENT '最后登录IP',
    login_count INT DEFAULT 0 COMMENT '登录次数',
    level TINYINT DEFAULT 1 COMMENT '管理员级别',
    status TINYINT DEFAULT 1 COMMENT '状态:0禁用1正常',
    phone VARCHAR(15) COMMENT '手机号',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_account (account)
) ENGINE=InnoDB COMMENT='后台管理员表';

-- 角色表
CREATE TABLE t_system_role (
    id INT PRIMARY KEY AUTO_INCREMENT,
    role_name VARCHAR(32) NOT NULL COMMENT '角色名称',
    role_desc VARCHAR(128) COMMENT '角色描述',
    menu_ids TEXT COMMENT '菜单权限ID列表',
    status TINYINT DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='角色表';

-- 菜单权限表
CREATE TABLE t_system_menu (
    id INT PRIMARY KEY AUTO_INCREMENT,
    pid INT DEFAULT 0 COMMENT '父级ID',
    path VARCHAR(255) DEFAULT '/0/' COMMENT '路径',
    name VARCHAR(50) NOT NULL COMMENT '菜单名称',
    type TINYINT DEFAULT 5 COMMENT '类型:1产品分类2附件分类3文章分类4设置分类5菜单分类',
    url VARCHAR(255) COMMENT 'URL地址',
    icon VARCHAR(64) COMMENT '图标',
    status TINYINT DEFAULT 1,
    sort INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='菜单权限表';

-- 附件管理表
CREATE TABLE t_system_attachment (
    id INT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL COMMENT '附件名称',
    att_dir VARCHAR(200) COMMENT '附件路径',
    satt_dir VARCHAR(200) COMMENT '压缩图片路径',
    att_size VARCHAR(30) COMMENT '附件大小',
    att_type VARCHAR(30) COMMENT '附件类型',
    pid INT DEFAULT 0 COMMENT '分类ID',
    image_type TINYINT DEFAULT 1 COMMENT '存储类型:1本地2七牛3OSS4COS',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='附件管理表';

-- 分类表 (通用分类)
CREATE TABLE t_system_category (
    id INT PRIMARY KEY AUTO_INCREMENT,
    pid INT DEFAULT 0 COMMENT '父级ID',
    path VARCHAR(255) DEFAULT '/0/' COMMENT '路径',
    name VARCHAR(50) NOT NULL COMMENT '分类名称',
    type TINYINT DEFAULT 1 COMMENT '类型',
    url VARCHAR(255) COMMENT 'URL',
    extra TEXT COMMENT '扩展字段JSON',
    status TINYINT DEFAULT 1,
    sort INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='分类表';

-- 城市表 (省市区数据)
CREATE TABLE t_system_city (
    id INT PRIMARY KEY,
    city_id INT NOT NULL DEFAULT 0 COMMENT '城市ID',
    level INT NOT NULL DEFAULT 0 COMMENT '级别:1省2市3区县',
    parent_id INT NOT NULL DEFAULT 0 COMMENT '父级ID',
    area_code VARCHAR(30) COMMENT '区号',
    name VARCHAR(100) NOT NULL COMMENT '名称',
    merger_name VARCHAR(255) COMMENT '合并名称(如:中国,广东省,深圳市)',
    lng VARCHAR(50) COMMENT '经度',
    lat VARCHAR(50) COMMENT '纬度',
    is_show TINYINT DEFAULT 1 COMMENT '是否展示:0否1是',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_parent_id (parent_id),
    KEY idx_city_id (city_id)
) ENGINE=InnoDB COMMENT='城市表';
```

---

## 三、Redis Key 设计规范

### 3.1 命名规范

```
{服务}:{业务}:{子业务}:{id}
```

### 3.2 完整 Key 清单

| 服务 | Key | 数据结构 | TTL | 说明 |
|------|-----|---------|-----|------|
| **用户服务** | | | | |
| | user:info:{userId} | Hash | 30min | 用户基本信息 |
| | user:token:access:{userId} | String | 30min | Access Token |
| | user:token:refresh:{userId} | String | 7d | Refresh Token |
| | user:address:default:{userId} | String | 30min | 默认地址ID |
| | user:captcha:{key} | String | 5min | 图形验证码 |
| | user:email:code:{email} | String | 5min | 邮箱验证码 |
| | user:register:lock:{phone} | String | 10s | 注册分布式锁 |
| **笔记服务** | | | | |
| | note:info:{noteId} | Hash | 30min | 笔记详情 |
| | note:comment:time:{noteId} | ZSet | 30min | 评论(时间序) score=timestamp |
| | note:comment:hot:{noteId} | ZSet | 30min | 评论(热度序) score=likeCount |
| **社交服务** | | | | |
| | social:following:{userId} | ZSet | 永久 | 关注列表 score=关注时间 |
| | social:follower:{userId} | ZSet | 永久 | 粉丝列表 score=关注时间 |
| | social:like:note:{noteId} | Set | 永久 | 笔记点赞用户集合 |
| | social:like:user:{userId}:note | Set | 永久 | 用户点赞的笔记集合 |
| | social:favorite:{userId} | ZSet | 永久 | 用户收藏列表 |
| | social:feed:{userId} | ZSet | 7d | 关注Feed流 score=发布时间 |
| **计数服务** | | | | |
| | counter:{bizType}:{bizId} | String | 永久 | 计数值 |
| | counter:buffer | Hash | - | 计数缓冲区(内存) |
| **通知服务** | | | | |
| | notify:unread:{userId} | String | 永久 | 未读通知数 |
| | notify:read:bitmap:{userId}:{yyyyMM} | Bitmap | 90d | 已读状态位图 |
| **商品服务** | | | | |
| | product:spu:{spuId} | Hash | 30min | SPU详情 |
| | product:sku:{skuId} | Hash | 30min | SKU详情 |
| | product:category:tree | String | 1h | 分类树JSON |
| | product:hot:{skuId} | String | 5min | 热点商品标记 |
| **购物车服务** | | | | |
| | cart:items:{userId} | Hash | 30d | 购物车商品 field=skuId value=quantity |
| | cart:checked:{userId} | Set | 30d | 选中的商品ID |
| | cart:sort:{userId} | ZSet | 30d | 购物车排序 score=加购时间 |
| **库存服务** | | | | |
| | inventory:bucket:{skuId}:{bucketNo} | String | 永久 | 分桶库存 |
| | inventory:prededuct:{orderId} | Hash | 30min | 预扣记录 field=skuId value=quantity |
| **优惠券服务** | | | | |
| | coupon:template:{templateId} | Hash | 30min | 券模板 |
| | coupon:stock:{templateId} | String | 永久 | 券库存 |
| | coupon:claimed:{templateId}:{userId} | String | 永久 | 用户领取记录 |
| **订单服务** | | | | |
| | order:info:{orderId} | Hash | 30min | 订单详情缓存 |
| | order:create:lock:{userId} | String | 10s | 下单分布式锁 |
| **网关** | | | | |
| | gateway:ratelimit:{api}:{userId} | String | 滑动窗口 | 接口限流计数 |
| | gateway:nonce:{nonce} | String | 5min | 请求防重放 |

---

## 四、功能清单与解决方案

### 4.1 用户服务

| 功能 | 描述 | 技术方案 | 方案对比与选型理由 |
|------|------|---------|-------------------|
| **图形验证码** | 登录/注册前校验 | Kaptcha生成 + Redis存储 | Kaptcha轻量；Google reCAPTCHA需外网 |
| **邮箱验证码** | 注册验证 | JavaMail + Redis存储5分钟 | 可Mock实现，不依赖真实邮件服务 |
| **用户注册** | 手机号/邮箱唯一性 | Redisson分布式锁 + DB唯一索引 | 分布式锁防并发；DB索引兜底 |
| **用户登录** | 账号密码/手机验证码 | BCrypt密码加密 + JWT Token | BCrypt慢哈希防暴力破解 |
| **Token机制** | 认证凭证 | 双Token(Access 30min + Refresh 7d) | **vs 单Token**：单Token过期需重登；双Token静默续期更好体验 |
| **登录鉴权** | 接口权限校验 | Gateway统一鉴权 | **vs 各服务鉴权**：统一管理，不侵入业务代码 |
| **收货地址** | CRUD + 默认地址 | 数量限制20 + 默认地址缓存 | Redis缓存默认地址ID，减少DB查询 |
| **用户缓存** | 用户信息缓存 | Cache Aside + 延迟双删 + Canal | **vs Read Through**：更简单；Canal异步删缓存保证最终一致 |
| **防刷限频** | 接口调用频率限制 | @RateLimit注解 + Redis滑动窗口 | **vs Sentinel**：细粒度到接口+用户维度 |

---

### 4.2 笔记服务

| 功能 | 描述 | 技术方案 | 方案对比与选型理由 |
|------|------|---------|-------------------|
| **笔记发布** | 图文/视频笔记 | 先入库待审核 → 审核通过 → 发布 | 内容安全合规 |
| **笔记详情** | 获取笔记内容 | 多级缓存(Caffeine→Redis→DB) | 热点笔记本地缓存，减少Redis压力 |
| **评论发布** | 发表评论 | RocketMQ顺序消息 | **vs 直接写DB**：顺序消息保证评论顺序不乱 |
| **评论审核** | 敏感词过滤 | DFA算法(Trie树) | **vs AC自动机**：DFA更简单，10万词库毫秒级匹配够用 |
| **评论列表-时间序** | 按时间排序 | Redis ZSet score=timestamp | 天然排序+分页 |
| **评论列表-热度序** | 按热度排序 | Redis ZSet score=likeCount | 双ZSet，按需切换 |
| **楼中楼评论** | 回复评论 | parent_id + root_id 设计 | **vs 仅一级评论**：更符合现代社交产品 |
| **评论计数** | 评论数统计 | 异步MQ更新counter服务 | 解耦，不阻塞主流程 |
| **评论举报** | 用户举报违规评论 | 举报入库 + 审核队列 + 满意度反馈 | 支持9种举报类型；处理结果可反馈满意度 |

---

### 4.3 社交服务

| 功能 | 描述 | 技术方案 | 方案对比与选型理由 |
|------|------|---------|-------------------|
| **关注** | 关注用户 | Lua原子操作(关注+计数) + MQ异步落库 | Lua保证原子性；MQ解耦 |
| **取关** | 取消关注 | 同上 | |
| **关注列表** | 我关注的人 | Redis ZSet score=关注时间 | **vs List**：ZSet天然排序+去重+分页 |
| **粉丝列表** | 关注我的人 | Redis ZSet | 同上 |
| **点赞** | 笔记/评论点赞 | Redis Set + MQ异步落库 | Set判断是否已赞O(1) |
| **收藏** | 收藏笔记 | Redis ZSet + MQ异步落库 | 按收藏时间排序 |
| **Feed流-关注流** | 关注的人发的笔记 | 推模式(写扩散) | 发布时推送到粉丝Feed |
| **Feed流-发现流** | 推荐笔记 | 拉模式(读扩散) | 实时拉取，不预推 |
| **大V热点** | 千万粉丝推送 | 推拉结合 + 延迟推送 | 大V粉丝拉取，普通用户推送 |

---

### 4.4 计数服务

| 功能 | 描述 | 技术方案 | 方案对比与选型理由 |
|------|------|---------|-------------------|
| **计数存储** | 点赞/收藏/评论/粉丝数 | Redis String | 简单高效 |
| **高频写入** | 10万+QPS写入 | Buffer-Trigger批量写DB | **vs 直接写**：攒批100条或1秒写一次，减少DB压力99% |
| **计数读取** | 读取计数 | Caffeine本地缓存(热点) + Redis | 热点计数本地缓存 |
| **模糊计数** | 10万+显示 | 前端格式化 | 允许1分钟延迟，不强一致 |
| **对账修复** | Redis和DB不一致 | XXL-Job定时扫描 + 差异修复 | 定时对账，发现差异自动修复 |

---

### 4.5 通知中心

| 功能 | 描述 | 技术方案 | 方案对比与选型理由 |
|------|------|---------|-------------------|
| **通知触发** | 点赞/评论/关注触发 | RocketMQ异步 | 解耦，不阻塞主流程 |
| **通知聚合** | "张三等10人赞了你" | 时间窗口聚合(5分钟) | 减少通知数量，提升体验 |
| **实时推送** | SSE推送(通知)+WebSocket(IM) | SSE: Spring MVC原生; IM: Netty | 通知是单向推送用SSE更轻量，IM需要全双工用WebSocket |
| **未读数** | 未读通知计数 | Redis String incr/decr | 原子操作 |
| **已读状态** | 标记已读 | Redis Bitmap | **vs 逐条记录**：空间节省99%，1亿通知只需12MB |
| **通知列表** | 拉取通知 | MySQL分页 + 缓存 | 分库分表(user_id) |

---

### 4.6 商品服务

| 功能 | 描述 | 技术方案 | 方案对比与选型理由 |
|------|------|---------|-------------------|
| **商品详情** | SPU+SKU聚合 | 多级缓存(Caffeine→Redis→DB) | 详情页QPS高，本地缓存扛热点 |
| **分类树** | 三级分类 | Redis缓存 + 1小时过期 | 分类变化少，长缓存 |
| **热点商品** | 秒杀/爆款 | JD-HotKey探测 + Caffeine升级 | **vs 纯Redis**：热点Key打爆Redis，本地缓存分摊 |
| **缓存击穿** | 热点Key过期 | 逻辑过期 + 后台异步更新 | **vs 互斥锁**：不阻塞请求，返回旧数据 |
| **缓存穿透** | 查询不存在的商品 | 布隆过滤器 + 空值缓存 | 双重防护 |
| **数据同步** | 商品变更同步ES/缓存 | Canal监听binlog → MQ → 消费处理 | 解耦，准实时同步 |

---

### 4.7 搜索服务

| 功能 | 描述 | 技术方案 | 方案对比与选型理由 |
|------|------|---------|-------------------|
| **商品搜索** | 关键词搜索 | ES match + filter | 全文检索 |
| **笔记搜索** | 笔记内容搜索 | ES match | 同上 |
| **搜索建议** | 输入联想 | ES Completion Suggester | **vs 前缀匹配**：Suggester专为此场景优化 |
| **热搜榜** | 实时热搜 | Redis ZSet + 滑动窗口统计 | 最近1小时搜索词频统计 |
| **增量同步** | 商品/笔记变更同步 | Canal + MQ | 准实时 |
| **全量重建** | 索引重建 | XXL-Job分页扫描 + 批量写入 + 断点续传 | 大数据量分批处理 |

---

### 4.8 购物车服务

| 功能 | 描述 | 技术方案 | 方案对比与选型理由 |
|------|------|---------|-------------------|
| **加购** | 添加商品 | Redis Hash HINCRBY | 同一商品累加数量 |
| **防重复** | 并发加购去重 | HSETNX | 原子操作 |
| **修改数量** | 调整商品数量 | Redis Hash HSET | |
| **删除商品** | 移除购物车 | Redis Hash HDEL | |
| **选中状态** | 勾选/取消勾选 | Redis Set | |
| **购物车排序** | 按加购时间 | Redis ZSet | 最新加购在前 |
| **匿名购物车** | 未登录加购 | 设备ID作为Key → 登录后合并 | **vs 不支持**：提升转化率 |
| **商品失效** | 下架/无库存标记 | 查询时校验 + 标记 | 前端展示失效状态 |

---

### 4.9 库存服务

| 功能 | 描述 | 技术方案 | 方案对比与选型理由 |
|------|------|---------|-------------------|
| **库存初始化** | SKU库存入Redis | 分桶初始化 | 分散热点 |
| **库存扣减** | 下单扣库存 | Redis Lua原子扣减(分桶) | **vs 直接decr**：分桶分散单Key压力 |
| **分桶策略** | 热点分散 | 按SKU分N桶，userId路由 | **桶数动态调整**：秒杀品8桶，普通品2桶 |
| **桶间均衡** | 单桶耗尽迁移 | 扣减失败 → 尝试其他桶 | **vs 固定桶**：提升成功率 |
| **预扣超时** | 占用库存超时 | 30分钟未支付 → 自动回退 | XXL-Job定时扫描 |
| **库存回退** | 取消订单/支付失败 | Lua原子回退 | |
| **三级扣减** | 可靠性保证 | L1 Redis预扣 → L2 MQ异步扣DB → L3 对账 | 最终一致性 |

---

### 4.10 优惠券服务

| 功能 | 描述 | 技术方案 | 方案对比与选型理由 |
|------|------|---------|-------------------|
| **创建券模板** | 新建优惠券 | 责任链校验 | 解耦校验逻辑 |
| **领券** | 用户领取 | Redis Lua原子操作(扣库存+记录领取) | **vs 分两步**：避免超领 |
| **用券** | 下单使用 | 订单服务调用 | |
| **退券** | 取消订单退回 | 状态更新 | |
| **领取限制** | 每人限领N张 | Redis记录已领数量 | |
| **券推送** | 批量发券 | XXL-Job任务分片 + 消息合并 | 千万用户分片并行发送 |
| **分库分表** | 用户券海量存储 | ShardingSphere buyer_id % 4 | 10亿数据4库 |

---

### 4.11 订单服务

| 功能 | 描述 | 技术方案 | 方案对比与选型理由 |
|------|------|---------|-------------------|
| **创建订单** | 下单 | 事务消息 + 本地消息表兜底 | **vs 普通MQ**：事务消息保证一致性；本地消息表兜底 |
| **订单状态** | 状态流转 | Spring StateMachine | **vs if-else**：配置化，状态流转清晰 |
| **超时关单** | 30分钟未支付 | RocketMQ延时消息 + XXL-Job兜底 | 延时消息主力；定时任务兜底 |
| **取消订单** | 用户主动取消 | 事务消息(释放库存+退券) | |
| **订单搜索** | 多条件查询 | ES | MySQL不适合复杂查询 |
| **数据同步** | 订单同步ES | Canal + MQ | |
| **分库分表** | 海量订单 | ShardingSphere buyer_id % 4 | 同库同表(主表+明细表同分片键) |
| **幂等消费** | MQ重复消费 | @Idempotent + Redis SET NX | |
| **死信处理** | 消费失败 | 死信队列 → 告警 → 人工处理 | |

---

### 4.12 网关服务

| 功能 | 描述 | 技术方案 | 方案对比与选型理由 |
|------|------|---------|-------------------|
| **路由转发** | 请求路由 | Spring Cloud Gateway | |
| **JWT鉴权** | Token校验 | GlobalFilter | 统一入口校验 |
| **HMAC签名** | 接口防篡改 | HMAC-SHA256 + 时间戳 + nonce | **vs 无签名**：防篡改+防重放 |
| **接口限流** | QPS限制 | Sentinel | |
| **熔断降级** | 服务保护 | Sentinel | |
| **灰度路由** | 新版本灰度 | Nacos元数据 + 请求头路由 | **vs 直接发布**：灰度验证，降低风险 |
| **API版本** | 多版本共存 | Header: X-Api-Version | **vs URL /v1/**：更灵活 |
| **跨域CORS** | 前端跨域 | CorsFilter | |
| **TraceId** | 链路追踪 | SkyWalking + Header注入 | |

---

## 五、中间件部署清单

| 中间件 | 版本 | 部署方式 | 集群模式 |
|--------|------|---------|---------|
| MySQL | 8.0 | Docker | 1主1从 |
| Redis | 7.2 | Docker | 1主2从3哨兵 |
| RocketMQ | 5.3 | Docker | 1 NameServer + 1 Broker |
| Elasticsearch | 8.x | Docker | 单节点(开发) |
| Nacos | 2.x | Docker | 单节点(开发) |
| XXL-Job | 3.0 | Docker | 单节点 |
| SkyWalking | 10.x | Docker | OAP + UI |
| Prometheus | 2.x | Docker | 单节点 |
| Grafana | 10.x | Docker | 单节点 |
| Canal | 1.1.7 | Docker | 单节点 |

---

## 六、ES 索引设计

### 6.1 商品索引 (product_index)

```json
{
  "mappings": {
    "properties": {
      "spuId": { "type": "long" },
      "skuId": { "type": "long" },
      "name": { "type": "text", "analyzer": "ik_max_word" },
      "subTitle": { "type": "text", "analyzer": "ik_smart" },
      "categoryId": { "type": "long" },
      "categoryName": { "type": "keyword" },
      "brandId": { "type": "long" },
      "brandName": { "type": "keyword" },
      "price": { "type": "scaled_float", "scaling_factor": 100 },
      "image": { "type": "keyword", "index": false },
      "sales": { "type": "long" },
      "status": { "type": "integer" },
      "createdAt": { "type": "date" }
    }
  }
}
```

### 6.2 笔记索引 (note_index)

```json
{
  "mappings": {
    "properties": {
      "noteId": { "type": "long" },
      "userId": { "type": "long" },
      "title": { "type": "text", "analyzer": "ik_max_word" },
      "content": { "type": "text", "analyzer": "ik_smart" },
      "coverImage": { "type": "keyword", "index": false },
      "likeCount": { "type": "long" },
      "collectCount": { "type": "long" },
      "commentCount": { "type": "long" },
      "status": { "type": "integer" },
      "createdAt": { "type": "date" }
    }
  }
}
```

### 6.3 订单索引 (order_index)

```json
{
  "mappings": {
    "properties": {
      "orderId": { "type": "long" },
      "orderNo": { "type": "keyword" },
      "userId": { "type": "long" },
      "status": { "type": "integer" },
      "totalAmount": { "type": "scaled_float", "scaling_factor": 100 },
      "payAmount": { "type": "scaled_float", "scaling_factor": 100 },
      "receiverName": { "type": "keyword" },
      "receiverPhone": { "type": "keyword" },
      "createdAt": { "type": "date" },
      "items": {
        "type": "nested",
        "properties": {
          "skuId": { "type": "long" },
          "skuName": { "type": "text", "analyzer": "ik_smart" }
        }
      }
    }
  }
}
```

### 6.4 搜索建议索引 (suggest_index)

```json
{
  "mappings": {
    "properties": {
      "keyword": {
        "type": "completion",
        "analyzer": "ik_max_word",
        "contexts": [
          { "name": "type", "type": "category" }
        ]
      },
      "weight": { "type": "long" }
    }
  }
}
```

---

## 七、XXL-Job 定时任务清单

| 任务名 | Cron | 所属服务 | 功能 |
|--------|------|---------|------|
| CounterFlushTask | 0/10 * * * * ? | counter | 计数缓冲区刷DB |
| CounterReconcileTask | 0 0 3 * * ? | counter | 计数对账修复 |
| OrderCloseTask | 0 * * * * ? | order | 超时订单关闭(兜底) |
| LocalMessageRetryTask | 0/30 * * * * ? | order | 本地消息重试 |
| InventoryRecoverTask | 0 */5 * * * ? | inventory | 预扣超时回退 |
| CouponExpireTask | 0 0 0 * * ? | coupon | 过期券状态更新 |
| EsFullSyncTask | 手动触发 | search | ES全量重建 |
| HotSearchStatTask | 0 */10 * * * ? | search | 热搜统计 |

---

## 八、MQ Topic 清单

| Topic | Tag | 生产者 | 消费者 | 消息类型 | 说明 |
|-------|-----|-------|--------|---------|------|
| SOCIAL_TOPIC | FOLLOW | social | social, counter, notification | 普通 | 关注事件 |
| SOCIAL_TOPIC | LIKE | social | counter, notification | 普通 | 点赞事件 |
| SOCIAL_TOPIC | FAVORITE | social | counter | 普通 | 收藏事件 |
| NOTE_TOPIC | COMMENT | note | note, counter, notification | 顺序 | 评论事件 |
| NOTE_TOPIC | PUBLISH | note | search, social(Feed) | 普通 | 笔记发布 |
| ORDER_TOPIC | CREATE | order | inventory, coupon | 事务 | 订单创建 |
| ORDER_TOPIC | CANCEL | order | inventory, coupon | 事务 | 订单取消 |
| ORDER_TOPIC | CLOSE | order | - | 延时(30min) | 订单超时关闭 |
| PRODUCT_TOPIC | CHANGE | canal | search, product | 普通 | 商品变更 |
| COUPON_TOPIC | PUSH | coupon | coupon | 普通 | 券推送 |

---

## 九、Sentinel 限流规则

| 资源名 | 限流阈值 | 策略 | 说明 |
|--------|---------|------|------|
| /api/user/login | 100 QPS | 滑动窗口 | 登录接口 |
| /api/user/register | 50 QPS | 滑动窗口 | 注册接口 |
| /api/note/publish | 20 QPS/用户 | 热点参数 | 发布限频 |
| /api/social/follow | 10 QPS/用户 | 热点参数 | 关注限频 |
| /api/coupon/claim | 1000 QPS | 滑动窗口 | 领券接口 |
| /api/order/create | 500 QPS | 滑动窗口 | 下单接口 |
| /api/product/detail | 5000 QPS | 滑动窗口 | 商品详情 |

---

## 十、监控告警规则

| 告警名 | PromQL | 阈值 | 说明 |
|--------|--------|------|------|
| 接口错误率 | rate(http_server_requests_seconds_count{status=~"5.."}[1m]) / rate(http_server_requests_seconds_count[1m]) | > 1% | 5xx错误率 |
| 接口RT | histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[1m])) | > 1s | P99响应时间 |
| JVM内存 | jvm_memory_used_bytes / jvm_memory_max_bytes | > 80% | 堆内存使用率 |
| Redis内存 | redis_memory_used_bytes / redis_memory_max_bytes | > 70% | Redis内存 |
| MQ消费堆积 | rocketmq_consumer_offset_diff | > 10000 | 消息堆积 |
| MySQL连接数 | mysql_global_status_threads_connected / mysql_global_variables_max_connections | > 80% | 连接数 |

---

## 十一、文档编写计划

以上内容需要落地到以下文档：

| 文档 | 内容 | 优先级 |
|------|------|--------|
| design/user-service.md | 用户服务完整设计 | P0 |
| design/note-service.md | 笔记服务完整设计 | P0 |
| design/social-service.md | 社交服务完整设计 | P0 |
| design/counter-service.md | 计数服务完整设计 | P0 |
| design/notification-service.md | 通知中心完整设计 | P1 |
| design/product-service.md | 商品服务完整设计 | P1 |
| design/search-service.md | 搜索服务完整设计 | P1 |
| design/cart-service.md | 购物车服务完整设计 | P1 |
| design/inventory-service.md | 库存服务完整设计 | P1 |
| design/coupon-service.md | 优惠券服务完整设计 | P1 |
| design/order-service.md | 订单服务完整设计 | P1 |
| design/gateway-service.md | 网关服务完整设计 | P1 |
| architecture/*.md | 8个架构方案文档 | P2 |
| infra/*.md | 6个基础设施文档 | P3 |
