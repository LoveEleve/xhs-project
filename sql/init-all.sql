-- =====================================================================
-- MyXHS 完整初始化 SQL（精简部署版 - 单 MySQL :3306）
-- 合并自: mysql-user-init.sql + mysql-content-init.sql + mysql-order-init.sql + mysql-inventory-init.sql
-- 补充: t_hot_search_snapshot (运行时新增，原 init SQL 缺失)
-- 生成时间: 2026-08-08
-- 用途: docker-compose 精简部署（8 MySQL → 1 MySQL）首次初始化
-- =====================================================================

-- =====================================================================
-- 一、基础设施数据库
-- =====================================================================
CREATE DATABASE IF NOT EXISTS nacos_config DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS xxl_job DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- =====================================================================
-- 二、用户 + 社交 + 通知 + IM（原 MySQL-User :13306）
-- =====================================================================

-- 用户服务
CREATE DATABASE IF NOT EXISTS my_xhs_user DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE my_xhs_user;

CREATE TABLE IF NOT EXISTS t_user (
    id           BIGINT       NOT NULL COMMENT 'ID',
    username     VARCHAR(64)  NOT NULL COMMENT '用户名',
    password     VARCHAR(128) NOT NULL COMMENT '密码',
    nickname     VARCHAR(64)  DEFAULT NULL COMMENT '昵称',
    avatar       VARCHAR(512) DEFAULT NULL COMMENT '头像URL',
    gender       TINYINT      DEFAULT 0 COMMENT '性别：0-未知 1-男 2-女',
    birthday     DATE         DEFAULT NULL COMMENT '生日',
    phone        VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    email        VARCHAR(128) DEFAULT NULL COMMENT '邮箱',
    signature    VARCHAR(256) DEFAULT NULL COMMENT '个性签名',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0-禁用 1-正常',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_username (username),
    UNIQUE INDEX uk_phone (phone),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

CREATE TABLE IF NOT EXISTS t_user_address (
    id           BIGINT       NOT NULL COMMENT 'ID',
    user_id      BIGINT       NOT NULL COMMENT '用户ID',
    receiver_name VARCHAR(64) NOT NULL COMMENT '收货人',
    receiver_phone VARCHAR(20) NOT NULL COMMENT '联系电话',
    province     VARCHAR(64)  NOT NULL COMMENT '省',
    city         VARCHAR(64)  NOT NULL COMMENT '市',
    district     VARCHAR(64)  NOT NULL COMMENT '区',
    detail_address VARCHAR(256) NOT NULL COMMENT '详细地址',
    is_default   TINYINT      NOT NULL DEFAULT 0 COMMENT '是否默认：0-否 1-是',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='收货地址表';

-- 号段分配表（ID 生成器）
CREATE TABLE IF NOT EXISTS t_id_segment (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增ID',
    biz_tag     VARCHAR(64)  NOT NULL COMMENT '业务标签(如user/order)',
    max_id      BIGINT       NOT NULL DEFAULT 0 COMMENT '当前最大ID',
    step        INT          NOT NULL DEFAULT 1000 COMMENT '号段步长',
    version     INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    description VARCHAR(256) DEFAULT NULL COMMENT '描述',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_biz_tag (biz_tag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='号段分配表';

INSERT INTO t_id_segment (biz_tag, max_id, step, description) VALUES
('user', 10000, 1000, '用户ID号段，起始10001'),
('order', 0, 5000, '订单ID号段'),
('note', 0, 2000, '笔记ID号段'),
('comment', 0, 3000, '评论ID号段'),
('coupon', 0, 1000, '优惠券ID号段'),
('address', 0, 1000, '收货地址ID号段'),
('payment', 0, 2000, '支付流水ID号段')
ON DUPLICATE KEY UPDATE description = VALUES(description);

-- 互动分析服务
CREATE DATABASE IF NOT EXISTS my_xhs_analytics DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE my_xhs_analytics;

CREATE TABLE IF NOT EXISTS t_user_behavior (
    id            BIGINT   NOT NULL COMMENT 'ID',
    user_id       BIGINT   NOT NULL COMMENT '用户ID',
    note_id       BIGINT   NOT NULL COMMENT '笔记ID',
    behavior_type TINYINT  NOT NULL COMMENT '行为类型：1-浏览 2-点赞 3-收藏 4-评论 5-分享 6-搜索',
    duration      INT      DEFAULT NULL COMMENT '停留时长（秒），仅浏览行为',
    deleted       TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_user_id (user_id),
    INDEX idx_note_id (note_id),
    INDEX idx_behavior_type (behavior_type),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户行为记录表';

CREATE TABLE IF NOT EXISTS t_follow (
    id              BIGINT   NOT NULL COMMENT 'ID',
    user_id         BIGINT   NOT NULL COMMENT '用户ID',
    follow_user_id  BIGINT   NOT NULL COMMENT '被关注用户ID',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '关注时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_user_follow (user_id, follow_user_id),
    INDEX idx_follow_user_id (follow_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='关注关系表';

CREATE TABLE IF NOT EXISTS t_like (
    id         BIGINT   NOT NULL COMMENT 'ID',
    user_id    BIGINT   NOT NULL COMMENT '用户ID',
    biz_type   TINYINT  NOT NULL COMMENT '业务类型：1-笔记 2-评论',
    biz_id     BIGINT   NOT NULL COMMENT '业务ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_user_biz (user_id, biz_type, biz_id),
    INDEX idx_biz (biz_type, biz_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='点赞表';

CREATE TABLE IF NOT EXISTS t_favorite (
    id         BIGINT   NOT NULL COMMENT 'ID',
    user_id    BIGINT   NOT NULL COMMENT '用户ID',
    note_id    BIGINT   NOT NULL COMMENT '笔记ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_user_note (user_id, note_id),
    INDEX idx_note_id (note_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='收藏表';

-- 通知服务
CREATE DATABASE IF NOT EXISTS my_xhs_notification DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE my_xhs_notification;

CREATE TABLE IF NOT EXISTS t_notification (
    id              BIGINT       NOT NULL COMMENT 'ID（雪花算法）',
    user_id         BIGINT       NOT NULL COMMENT '接收用户ID',
    type            TINYINT      NOT NULL COMMENT '通知类型：1-点赞 2-评论 3-关注 4-系统通知 5-订单通知',
    title           VARCHAR(128) NOT NULL COMMENT '通知标题',
    content         VARCHAR(512) DEFAULT NULL COMMENT '通知内容',
    sender_id       BIGINT       DEFAULT NULL COMMENT '发送者ID',
    sender_name     VARCHAR(32)  DEFAULT NULL COMMENT '发送者昵称(冗余，避免查用户表)',
    target_id       BIGINT       DEFAULT NULL COMMENT '关联目标ID(笔记/商品/订单)',
    target_type     TINYINT      DEFAULT NULL COMMENT '目标类型：1-笔记 2-商品 3-订单',
    sender_avatar   VARCHAR(512) DEFAULT NULL COMMENT '发送者头像URL（冗余）',
    is_read         TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已读：0-未读 1-已读',
    extra_data      TEXT         DEFAULT NULL COMMENT '扩展数据JSON',
    is_aggregated   TINYINT      NOT NULL DEFAULT 0 COMMENT '是否被聚合',
    aggregate_id    BIGINT       DEFAULT NULL COMMENT '聚合目标通知ID',
    aggregate_count INT          DEFAULT 1 COMMENT '聚合数量',
    notify_date     DATE         GENERATED ALWAYS AS (DATE(created_at)) STORED COMMENT '通知日期',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_user_id_created (user_id, created_at DESC),
    INDEX idx_user_type_read (user_id, type, is_read),
    UNIQUE INDEX uk_aggregate (user_id, type, target_id, notify_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知表';

CREATE TABLE IF NOT EXISTS t_push_template (
    id                       BIGINT       NOT NULL COMMENT 'ID',
    type                     VARCHAR(32)  NOT NULL COMMENT '通知类型',
    title_template           VARCHAR(128) NOT NULL COMMENT '标题模板',
    content_template         VARCHAR(512) DEFAULT NULL COMMENT '内容模板',
    aggregate_title_template VARCHAR(128) DEFAULT NULL COMMENT '聚合标题模板',
    status                   TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0-禁用 1-启用',
    created_at               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_type (type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='推送模板表';

CREATE TABLE IF NOT EXISTS t_push_task (
    id               BIGINT       NOT NULL COMMENT 'ID',
    task_name        VARCHAR(64)  NOT NULL COMMENT '任务名称',
    task_type        TINYINT      NOT NULL COMMENT '任务类型',
    template_id      BIGINT       DEFAULT NULL COMMENT '关联推送模板ID',
    biz_id           BIGINT       DEFAULT NULL COMMENT '业务ID',
    target_type      TINYINT      NOT NULL COMMENT '目标类型',
    target_condition TEXT         DEFAULT NULL COMMENT '筛选条件JSON',
    target_count     INT          NOT NULL COMMENT '目标用户数',
    success_count    INT          NOT NULL DEFAULT 0 COMMENT '成功数',
    fail_count       INT          NOT NULL DEFAULT 0 COMMENT '失败数',
    status           TINYINT      NOT NULL DEFAULT 0 COMMENT '状态',
    execute_time     DATETIME     DEFAULT NULL COMMENT '计划执行时间',
    start_time       DATETIME     DEFAULT NULL COMMENT '实际开始时间',
    end_time         DATETIME     DEFAULT NULL COMMENT '实际结束时间',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_status (status),
    INDEX idx_execute_time (execute_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='推送任务表';

CREATE TABLE IF NOT EXISTS t_push_task_fail (
    id           BIGINT       NOT NULL COMMENT 'ID',
    task_id      BIGINT       NOT NULL COMMENT '任务ID',
    user_id      BIGINT       NOT NULL COMMENT '用户ID',
    fail_reason  VARCHAR(256) DEFAULT NULL COMMENT '失败原因',
    retry_count  INT          NOT NULL DEFAULT 0 COMMENT '重试次数',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_task_id (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='推送失败记录表';

-- IM 服务
CREATE DATABASE IF NOT EXISTS my_xhs_im DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE my_xhs_im;

CREATE TABLE IF NOT EXISTS t_chat_message (
    id              BIGINT        NOT NULL COMMENT 'ID',
    conversation_id BIGINT        NOT NULL COMMENT '会话ID = min(A,B)<<32|max(A,B)',
    sender_id       BIGINT        NOT NULL COMMENT '发送者ID',
    receiver_id     BIGINT        NOT NULL COMMENT '接收者ID',
    content         VARCHAR(2048) NOT NULL COMMENT '消息内容',
    msg_type        TINYINT       NOT NULL DEFAULT 0 COMMENT '消息类型：0-文本 1-图片 2-系统消息',
    seq_no          BIGINT        DEFAULT NULL COMMENT '会话内序列号（Redis INCR生成，保证有序）',
    is_read         TINYINT       NOT NULL DEFAULT 0 COMMENT '是否已读',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_conversation (conversation_id),
    INDEX idx_conversation_seq (conversation_id, seq_no),
    INDEX idx_sender (sender_id),
    INDEX idx_receiver (receiver_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天消息表';

-- 用户聊天关系表（会话列表、未读数）
CREATE TABLE IF NOT EXISTS t_chat_user_relation (
    id              BIGINT   NOT NULL COMMENT 'ID',
    user_id         BIGINT   NOT NULL COMMENT '用户ID',
    peer_id         BIGINT   NOT NULL COMMENT '对方用户ID',
    conversation_id BIGINT   NOT NULL COMMENT '会话ID',
    last_message_id BIGINT   DEFAULT NULL COMMENT '最后一条消息ID',
    last_content    VARCHAR(2048) DEFAULT NULL COMMENT '最后一条消息内容',
    last_msg_type   TINYINT  DEFAULT NULL COMMENT '最后一条消息类型',
    unread_count    INT      NOT NULL DEFAULT 0 COMMENT '未读数',
    is_deleted      TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_user_peer (user_id, peer_id),
    INDEX idx_user_id (user_id),
    INDEX idx_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户聊天关系表';

-- =====================================================================
-- 三、内容 + 商品 + 购物车 + 优惠券（原 MySQL-Content :13307）
-- =====================================================================

-- 内容服务
CREATE DATABASE IF NOT EXISTS my_xhs_content DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE my_xhs_content;

CREATE TABLE IF NOT EXISTS t_note (
    id            BIGINT       NOT NULL COMMENT 'ID',
    user_id       BIGINT       NOT NULL COMMENT '作者ID',
    title         VARCHAR(128) DEFAULT NULL COMMENT '标题',
    content       TEXT         DEFAULT NULL COMMENT '正文',
    images        TEXT         DEFAULT NULL COMMENT '图片URL列表(JSON)',
    video_url     VARCHAR(512) DEFAULT NULL COMMENT '视频URL',
    cover_url     VARCHAR(512) DEFAULT NULL COMMENT '封面图URL',
    topic_ids     VARCHAR(512) DEFAULT NULL COMMENT '话题ID列表(JSON)',
    tags          VARCHAR(512) DEFAULT NULL COMMENT '标签列表(JSON)',
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0-草稿 1-审核中 2-已发布 3-已下架',
    audit_status  TINYINT      NOT NULL DEFAULT 0 COMMENT '审核状态：0-待审核 1-通过 2-拒绝',
    reject_reason VARCHAR(256) DEFAULT NULL COMMENT '审核拒绝原因',
    note_type     TINYINT      NOT NULL DEFAULT 0 COMMENT '笔记类型：0-图文 1-视频',
    deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_user_id (user_id),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='笔记表';

CREATE TABLE IF NOT EXISTS t_comment (
    id          BIGINT       NOT NULL COMMENT 'ID',
    note_id     BIGINT       NOT NULL COMMENT '笔记ID',
    user_id     BIGINT       NOT NULL COMMENT '评论用户ID',
    parent_id   BIGINT       DEFAULT 0 COMMENT '父评论ID(0为一级评论)',
    reply_to_id BIGINT       DEFAULT NULL COMMENT '回复的评论ID',
    content     VARCHAR(1024) NOT NULL COMMENT '评论内容',
    like_count  INT          NOT NULL DEFAULT 0 COMMENT '点赞数',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_note_id (note_id),
    INDEX idx_user_id (user_id),
    INDEX idx_parent_id (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评论表';

CREATE TABLE IF NOT EXISTS t_topic (
    id          BIGINT       NOT NULL COMMENT 'ID',
    name        VARCHAR(64)  NOT NULL COMMENT '话题名称',
    icon        VARCHAR(512) DEFAULT NULL COMMENT '话题图标',
    description VARCHAR(256) DEFAULT NULL COMMENT '话题描述',
    note_count  INT          NOT NULL DEFAULT 0 COMMENT '笔记数',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0-禁用 1-正常',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='话题表';

-- ⚠️ t_hot_search_snapshot — 运行时新增，原 init SQL 缺失
CREATE TABLE IF NOT EXISTS t_hot_search_snapshot (
    id           BIGINT       NOT NULL COMMENT 'ID',
    keyword      VARCHAR(100) NOT NULL COMMENT '热搜关键词',
    score        DOUBLE       NOT NULL DEFAULT 0 COMMENT '热度分数',
    rank_no      INT          NOT NULL DEFAULT 0 COMMENT '排名',
    search_count BIGINT       NOT NULL DEFAULT 0 COMMENT '搜索次数',
    snapshot_time DATETIME    NOT NULL COMMENT '快照时间',
    PRIMARY KEY (id),
    KEY idx_snapshot_time (snapshot_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='热搜快照表';

-- 计数服务
CREATE DATABASE IF NOT EXISTS my_xhs_counter DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE my_xhs_counter;

CREATE TABLE IF NOT EXISTS t_counter (
    id           BIGINT   NOT NULL COMMENT 'ID',
    target_type  TINYINT  NOT NULL COMMENT '目标类型：1-笔记 2-用户',
    target_id    BIGINT   NOT NULL COMMENT '目标ID',
    count_type   TINYINT  NOT NULL COMMENT '计数类型：1-点赞 2-收藏 3-评论 4-分享 5-浏览 6-粉丝 7-关注',
    count_value  BIGINT   NOT NULL DEFAULT 0 COMMENT '计数值',
    deleted      TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_target_count (target_type, target_id, count_type),
    INDEX idx_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='计数表';

-- 搜索服务
CREATE DATABASE IF NOT EXISTS my_xhs_search DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE my_xhs_search;

CREATE TABLE IF NOT EXISTS t_hot_search (
    id           BIGINT       NOT NULL COMMENT 'ID',
    keyword      VARCHAR(128) NOT NULL COMMENT '搜索关键词',
    search_count BIGINT       NOT NULL DEFAULT 0 COMMENT '搜索次数',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0-禁用 1-正常',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_keyword (keyword),
    INDEX idx_search_count (search_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='热搜词表';

-- ⚠️ t_hot_search_snapshot — 运行时新增，原 init SQL 缺失（search 库有自己的快照表）
CREATE TABLE IF NOT EXISTS t_hot_search_snapshot (
    id           BIGINT       NOT NULL COMMENT 'ID',
    keyword      VARCHAR(128) NOT NULL COMMENT '热搜关键词',
    score        DOUBLE       DEFAULT 0 COMMENT '热度分数',
    rank_no      INT          DEFAULT 0 COMMENT '排名',
    search_count BIGINT       DEFAULT 0 COMMENT '搜索次数',
    snapshot_time DATETIME    NOT NULL COMMENT '快照时间',
    PRIMARY KEY (id),
    KEY idx_snapshot_time (snapshot_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='热搜快照表';

-- 商品服务
CREATE DATABASE IF NOT EXISTS my_xhs_product DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE my_xhs_product;

CREATE TABLE IF NOT EXISTS t_category (
    id           BIGINT       NOT NULL COMMENT '分类ID',
    name         VARCHAR(64)  NOT NULL COMMENT '分类名称',
    parent_id    BIGINT       NOT NULL DEFAULT 0 COMMENT '父分类ID（0为一级分类）',
    level        TINYINT      NOT NULL COMMENT '层级：1-一级 2-二级 3-三级',
    sort         INT          NOT NULL DEFAULT 0 COMMENT '排序值（越小越靠前）',
    icon         VARCHAR(512) DEFAULT NULL COMMENT '分类图标URL',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0-禁用 1-启用',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_parent_id (parent_id),
    INDEX idx_level (level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品分类表（三级分类树）';

CREATE TABLE IF NOT EXISTS t_spu (
    id           BIGINT       NOT NULL COMMENT 'SPU ID',
    name         VARCHAR(256) NOT NULL COMMENT '商品名称',
    category_id  BIGINT       NOT NULL COMMENT '分类ID',
    brand_id     BIGINT       DEFAULT NULL COMMENT '品牌ID',
    description  TEXT         DEFAULT NULL COMMENT '商品描述',
    images       TEXT         DEFAULT NULL COMMENT '商品图片列表(JSON)',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0-下架 1-上架',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_category_id (category_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品SPU表';

CREATE TABLE IF NOT EXISTS t_sku (
    id              BIGINT        NOT NULL COMMENT 'SKU ID',
    spu_id          BIGINT        NOT NULL COMMENT 'SPU ID',
    name            VARCHAR(256)  NOT NULL COMMENT 'SKU名称',
    price           DECIMAL(10,2) NOT NULL COMMENT '价格',
    original_price  DECIMAL(10,2) DEFAULT NULL COMMENT '原价',
    stock           INT           NOT NULL DEFAULT 0 COMMENT '库存(冗余，实际由库存服务管理)',
    specs           VARCHAR(1024) DEFAULT NULL COMMENT '规格属性(JSON)',
    status          TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：0-下架 1-上架',
    deleted         TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_spu_id (spu_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品SKU表';

-- 购物车服务
CREATE DATABASE IF NOT EXISTS my_xhs_cart DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE my_xhs_cart;

CREATE TABLE IF NOT EXISTS t_cart_item (
    id           BIGINT       NOT NULL COMMENT 'ID',
    user_id      BIGINT       NOT NULL COMMENT '用户ID',
    sku_id       BIGINT       NOT NULL COMMENT 'SKU ID',
    quantity     INT          NOT NULL DEFAULT 1 COMMENT '数量',
    checked      TINYINT      NOT NULL DEFAULT 1 COMMENT '是否选中：0-否 1-是',
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间(毫秒精度,C-05乱序保护依赖)',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_user_sku (user_id, sku_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='购物车项表';

-- 优惠券服务
CREATE DATABASE IF NOT EXISTS my_xhs_coupon DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE my_xhs_coupon;

CREATE TABLE IF NOT EXISTS t_coupon_template (
    id              BIGINT        NOT NULL COMMENT 'ID',
    name            VARCHAR(128)  NOT NULL COMMENT '优惠券名称',
    type            TINYINT       NOT NULL COMMENT '类型：1-满减 2-折扣 3-无门槛',
    discount_value  DECIMAL(10,2) NOT NULL COMMENT '优惠金额/折扣率',
    min_amount      DECIMAL(10,2) DEFAULT 0 COMMENT '最低消费金额',
    total_count     INT           NOT NULL COMMENT '发放总量',
    remain_count    INT           NOT NULL COMMENT '剩余数量',
    per_user_limit  INT           NOT NULL DEFAULT 1 COMMENT '每人限领',
    valid_start     DATETIME      NOT NULL COMMENT '有效期开始',
    valid_end       DATETIME      NOT NULL COMMENT '有效期结束',
    status          TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：0-禁用 1-启用',
    deleted         TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='优惠券模板表';

CREATE TABLE IF NOT EXISTS t_user_coupon (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID',
    coupon_id       BIGINT       NOT NULL COMMENT '优惠券模板ID',
    claim_no        VARCHAR(64)  NOT NULL COMMENT '领券流水号(MQ msgId)，用于幂等去重',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0-未使用 1-已使用 2-已过期',
    used_order_id   BIGINT       DEFAULT NULL COMMENT '使用的订单ID',
    received_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
    used_at         DATETIME     DEFAULT NULL COMMENT '使用时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_claim_no (claim_no),
    INDEX idx_user_id (user_id),
    INDEX idx_coupon_id (coupon_id),
    INDEX idx_user_coupon (user_id, coupon_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表';

-- 优惠券 Outbox 表（MQ 发送可靠性保障）
CREATE TABLE IF NOT EXISTS t_coupon_outbox (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID',
    template_id     BIGINT       NOT NULL COMMENT '优惠券模板ID',
    claim_no        VARCHAR(64)  NOT NULL COMMENT '幂等流水号(UUID)',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '0=待发送 1=已发送',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_claim_no (claim_no),
    INDEX idx_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='优惠券Outbox表';

-- 本地消息表（Feed发送端可靠性保障）— 放在 my_xhs_content 库
USE my_xhs_content;
CREATE TABLE IF NOT EXISTS t_local_message (
    id              BIGINT       NOT NULL COMMENT 'ID',
    topic           VARCHAR(64)  NOT NULL COMMENT 'MQ Topic',
    body            TEXT         NOT NULL COMMENT '消息体JSON',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '0=待发送 1=已发送 2=发送失败 3=死信',
    retry_count     INT          NOT NULL DEFAULT 0 COMMENT '重试次数',
    push_status     TINYINT      NOT NULL DEFAULT 0 COMMENT '推送状态：0=未推送 1=推送中 2=已推送 3=推送失败',
    push_cursor     INT          NOT NULL DEFAULT 0 COMMENT '推送游标（已推送到第几个粉丝）',
    push_total      INT          NOT NULL DEFAULT 0 COMMENT '总粉丝数',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_status_retry (status, retry_count, created_at),
    INDEX idx_push_status (push_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地消息表(Feed可靠性保障)';

-- =====================================================================
-- 四、订单 + 支付（原 MySQL-Order :13308）— 分库分表保留
-- =====================================================================

-- 订单公共库（不分片：订单号映射表）
CREATE DATABASE IF NOT EXISTS my_xhs_order DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE my_xhs_order;

CREATE TABLE IF NOT EXISTS t_order_no_mapping (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增ID',
    order_no        VARCHAR(64)   NOT NULL COMMENT '订单号',
    user_id         BIGINT        NOT NULL COMMENT '用户ID',
    order_id        BIGINT        NOT NULL COMMENT '订单ID',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_order_no (order_no),
    INDEX idx_order_id (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单号映射表（非分片键查询路由）';

-- 支付服务
CREATE DATABASE IF NOT EXISTS my_xhs_payment DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE my_xhs_payment;

CREATE TABLE IF NOT EXISTS t_payment (
    id              BIGINT        NOT NULL COMMENT 'ID',
    order_id        BIGINT        NOT NULL COMMENT '订单ID',
    user_id         BIGINT        NOT NULL COMMENT '用户ID',
    payment_no      VARCHAR(64)   NOT NULL COMMENT '支付流水号',
    amount          DECIMAL(10,2) NOT NULL COMMENT '支付金额',
    pay_type        TINYINT       NOT NULL COMMENT '支付方式：1-支付宝(Mock) 2-微信(Mock)',
    status          TINYINT       NOT NULL DEFAULT 0 COMMENT '状态：0-待支付 1-支付成功 2-支付失败 3-已退款',
    paid_at         DATETIME      DEFAULT NULL COMMENT '支付成功时间',
    deleted         TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_payment_no (payment_no),
    INDEX idx_order_id (order_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='支付记录表';

CREATE TABLE IF NOT EXISTS t_refund (
    id              BIGINT        NOT NULL COMMENT '退款单ID',
    payment_id      BIGINT        NOT NULL COMMENT '关联支付单ID',
    order_id        BIGINT        NOT NULL COMMENT '订单ID',
    user_id         BIGINT        NOT NULL COMMENT '用户ID',
    refund_no       VARCHAR(64)   NOT NULL COMMENT '退款单号',
    refund_amount   DECIMAL(10,2) NOT NULL COMMENT '退款金额',
    reason          VARCHAR(256)  DEFAULT NULL COMMENT '退款原因',
    status          TINYINT       NOT NULL DEFAULT 0 COMMENT '状态：0-退款中 1-退款成功 2-退款失败 3-退款关闭',
    refund_type     TINYINT       NOT NULL DEFAULT 1 COMMENT '退款类型：1-仅退款 2-退货退款',
    refund_channel  TINYINT       NOT NULL DEFAULT 1 COMMENT '退款渠道：1-原路退回 2-退到余额',
    success_at      DATETIME      DEFAULT NULL COMMENT '退款成功时间',
    deleted         TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_refund_no (refund_no),
    INDEX idx_payment_id (payment_id),
    INDEX idx_order_id (order_id),
    INDEX idx_user_id (user_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='退款单表';

-- ==================== 分片数据库 0 ====================
CREATE DATABASE IF NOT EXISTS my_xhs_order_0 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE my_xhs_order_0;

CREATE TABLE IF NOT EXISTS t_order_0 (
    id              BIGINT        NOT NULL COMMENT '订单ID',
    user_id         BIGINT        NOT NULL COMMENT '用户ID（分片键）',
    order_no        VARCHAR(64)   NOT NULL COMMENT '订单号',
    total_amount    DECIMAL(10,2) NOT NULL COMMENT '订单总金额',
    pay_amount      DECIMAL(10,2) NOT NULL COMMENT '实付金额',
    discount_amount DECIMAL(10,2) DEFAULT 0 COMMENT '优惠金额',
    coupon_id       BIGINT        DEFAULT NULL COMMENT '使用的优惠券ID',
    status          TINYINT       NOT NULL DEFAULT 0 COMMENT '状态',
    address_snapshot VARCHAR(1024) DEFAULT NULL COMMENT '收货地址快照(JSON)',
    remark          VARCHAR(256)  DEFAULT NULL COMMENT '订单备注',
    paid_at         DATETIME      DEFAULT NULL COMMENT '支付时间',
    delivered_at    DATETIME      DEFAULT NULL COMMENT '发货时间',
    completed_at    DATETIME      DEFAULT NULL COMMENT '完成时间',
    cancelled_at    DATETIME      DEFAULT NULL COMMENT '取消时间',
    deleted         TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_order_no (order_no),
    INDEX idx_user_id (user_id),
    INDEX idx_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表_0';
CREATE TABLE IF NOT EXISTS t_order_1 LIKE t_order_0;
CREATE TABLE IF NOT EXISTS t_order_2 LIKE t_order_0;
CREATE TABLE IF NOT EXISTS t_order_3 LIKE t_order_0;

CREATE TABLE IF NOT EXISTS t_order_item_0 (
    id              BIGINT        NOT NULL COMMENT 'ID',
    order_id        BIGINT        NOT NULL COMMENT '订单ID',
    user_id         BIGINT        NOT NULL COMMENT '用户ID（分片键）',
    sku_id          BIGINT        NOT NULL COMMENT 'SKU ID',
    spu_id          BIGINT        NOT NULL COMMENT 'SPU ID',
    sku_name        VARCHAR(256)  NOT NULL COMMENT 'SKU名称快照',
    sku_image       VARCHAR(512)  DEFAULT NULL COMMENT 'SKU图片快照',
    price           DECIMAL(10,2) NOT NULL COMMENT '单价快照',
    quantity        INT           NOT NULL COMMENT '数量',
    total_amount    DECIMAL(10,2) NOT NULL COMMENT '小计金额',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_order_id (order_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表_0';
CREATE TABLE IF NOT EXISTS t_order_item_1 LIKE t_order_item_0;
CREATE TABLE IF NOT EXISTS t_order_item_2 LIKE t_order_item_0;
CREATE TABLE IF NOT EXISTS t_order_item_3 LIKE t_order_item_0;

CREATE TABLE IF NOT EXISTS t_local_message_0 (
    id              BIGINT        NOT NULL COMMENT '消息ID',
    user_id         BIGINT        NOT NULL COMMENT '用户ID（分片键）',
    transaction_id  VARCHAR(64)   NOT NULL COMMENT '事务ID',
    service_name    VARCHAR(32)   NOT NULL COMMENT '服务名',
    operation_type  VARCHAR(32)   NOT NULL COMMENT '操作类型',
    payload         TEXT          NOT NULL COMMENT '操作参数JSON',
    status          TINYINT       NOT NULL DEFAULT 0 COMMENT '状态：0-待处理 1-成功 2-失败 3-死信',
    retry_count     INT           NOT NULL DEFAULT 0 COMMENT '重试次数',
    next_retry_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次重试时间',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_transaction_id (transaction_id),
    INDEX idx_user_id (user_id),
    INDEX idx_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地消息表_0';
CREATE TABLE IF NOT EXISTS t_local_message_1 LIKE t_local_message_0;
CREATE TABLE IF NOT EXISTS t_local_message_2 LIKE t_local_message_0;
CREATE TABLE IF NOT EXISTS t_local_message_3 LIKE t_local_message_0;

CREATE TABLE IF NOT EXISTS t_order_snapshot_0 (
    id              BIGINT        NOT NULL COMMENT 'ID',
    order_id        BIGINT        NOT NULL COMMENT '订单ID',
    user_id         BIGINT        NOT NULL COMMENT '用户ID（分片键）',
    event           VARCHAR(32)   NOT NULL COMMENT '触发事件',
    snapshot_data   TEXT          NOT NULL COMMENT '订单完整快照(JSON)',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_order_id (order_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单快照表_0';
CREATE TABLE IF NOT EXISTS t_order_snapshot_1 LIKE t_order_snapshot_0;
CREATE TABLE IF NOT EXISTS t_order_snapshot_2 LIKE t_order_snapshot_0;
CREATE TABLE IF NOT EXISTS t_order_snapshot_3 LIKE t_order_snapshot_0;

-- Event Sourcing 订单事件流（不可变）
CREATE TABLE IF NOT EXISTS t_order_event_0 (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '事件ID',
    order_id        BIGINT        NOT NULL COMMENT '订单ID',
    user_id         BIGINT        NOT NULL COMMENT '用户ID（分片键）',
    event_type      VARCHAR(32)   NOT NULL COMMENT '事件类型',
    from_status     TINYINT       DEFAULT NULL COMMENT '变更前状态',
    to_status       TINYINT       NOT NULL COMMENT '变更后状态',
    payload         TEXT          DEFAULT NULL COMMENT '事件载荷（JSON）',
    event_seq       INT           NOT NULL COMMENT '事件序号',
    event_time      DATETIME(3)   NOT NULL COMMENT '事件发生时间',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_order_event_seq (order_id, event_seq),
    INDEX idx_order_id (order_id),
    INDEX idx_user_id (user_id),
    INDEX idx_event_time (event_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单事件流(Event Sourcing)_0';
CREATE TABLE IF NOT EXISTS t_order_event_1 LIKE t_order_event_0;
CREATE TABLE IF NOT EXISTS t_order_event_2 LIKE t_order_event_0;
CREATE TABLE IF NOT EXISTS t_order_event_3 LIKE t_order_event_0;

-- ==================== 分片数据库 1 ====================
CREATE DATABASE IF NOT EXISTS my_xhs_order_1 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE my_xhs_order_1;

CREATE TABLE IF NOT EXISTS t_order_0 LIKE my_xhs_order_0.t_order_0;
CREATE TABLE IF NOT EXISTS t_order_1 LIKE my_xhs_order_0.t_order_0;
CREATE TABLE IF NOT EXISTS t_order_2 LIKE my_xhs_order_0.t_order_0;
CREATE TABLE IF NOT EXISTS t_order_3 LIKE my_xhs_order_0.t_order_0;
CREATE TABLE IF NOT EXISTS t_order_item_0 LIKE my_xhs_order_0.t_order_item_0;
CREATE TABLE IF NOT EXISTS t_order_item_1 LIKE my_xhs_order_0.t_order_item_0;
CREATE TABLE IF NOT EXISTS t_order_item_2 LIKE my_xhs_order_0.t_order_item_0;
CREATE TABLE IF NOT EXISTS t_order_item_3 LIKE my_xhs_order_0.t_order_item_0;
CREATE TABLE IF NOT EXISTS t_local_message_0 LIKE my_xhs_order_0.t_local_message_0;
CREATE TABLE IF NOT EXISTS t_local_message_1 LIKE my_xhs_order_0.t_local_message_0;
CREATE TABLE IF NOT EXISTS t_local_message_2 LIKE my_xhs_order_0.t_local_message_0;
CREATE TABLE IF NOT EXISTS t_local_message_3 LIKE my_xhs_order_0.t_local_message_0;
CREATE TABLE IF NOT EXISTS t_order_snapshot_0 LIKE my_xhs_order_0.t_order_snapshot_0;
CREATE TABLE IF NOT EXISTS t_order_snapshot_1 LIKE my_xhs_order_0.t_order_snapshot_0;
CREATE TABLE IF NOT EXISTS t_order_snapshot_2 LIKE my_xhs_order_0.t_order_snapshot_0;
CREATE TABLE IF NOT EXISTS t_order_snapshot_3 LIKE my_xhs_order_0.t_order_snapshot_0;
CREATE TABLE IF NOT EXISTS t_order_event_0 LIKE my_xhs_order_0.t_order_event_0;
CREATE TABLE IF NOT EXISTS t_order_event_1 LIKE my_xhs_order_0.t_order_event_0;
CREATE TABLE IF NOT EXISTS t_order_event_2 LIKE my_xhs_order_0.t_order_event_0;
CREATE TABLE IF NOT EXISTS t_order_event_3 LIKE my_xhs_order_0.t_order_event_0;

-- ==================== 分片数据库 2 ====================
CREATE DATABASE IF NOT EXISTS my_xhs_order_2 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE my_xhs_order_2;

CREATE TABLE IF NOT EXISTS t_order_0 LIKE my_xhs_order_0.t_order_0;
CREATE TABLE IF NOT EXISTS t_order_1 LIKE my_xhs_order_0.t_order_0;
CREATE TABLE IF NOT EXISTS t_order_2 LIKE my_xhs_order_0.t_order_0;
CREATE TABLE IF NOT EXISTS t_order_3 LIKE my_xhs_order_0.t_order_0;
CREATE TABLE IF NOT EXISTS t_order_item_0 LIKE my_xhs_order_0.t_order_item_0;
CREATE TABLE IF NOT EXISTS t_order_item_1 LIKE my_xhs_order_0.t_order_item_0;
CREATE TABLE IF NOT EXISTS t_order_item_2 LIKE my_xhs_order_0.t_order_item_0;
CREATE TABLE IF NOT EXISTS t_order_item_3 LIKE my_xhs_order_0.t_order_item_0;
CREATE TABLE IF NOT EXISTS t_local_message_0 LIKE my_xhs_order_0.t_local_message_0;
CREATE TABLE IF NOT EXISTS t_local_message_1 LIKE my_xhs_order_0.t_local_message_0;
CREATE TABLE IF NOT EXISTS t_local_message_2 LIKE my_xhs_order_0.t_local_message_0;
CREATE TABLE IF NOT EXISTS t_local_message_3 LIKE my_xhs_order_0.t_local_message_0;
CREATE TABLE IF NOT EXISTS t_order_snapshot_0 LIKE my_xhs_order_0.t_order_snapshot_0;
CREATE TABLE IF NOT EXISTS t_order_snapshot_1 LIKE my_xhs_order_0.t_order_snapshot_0;
CREATE TABLE IF NOT EXISTS t_order_snapshot_2 LIKE my_xhs_order_0.t_order_snapshot_0;
CREATE TABLE IF NOT EXISTS t_order_snapshot_3 LIKE my_xhs_order_0.t_order_snapshot_0;
CREATE TABLE IF NOT EXISTS t_order_event_0 LIKE my_xhs_order_0.t_order_event_0;
CREATE TABLE IF NOT EXISTS t_order_event_1 LIKE my_xhs_order_0.t_order_event_0;
CREATE TABLE IF NOT EXISTS t_order_event_2 LIKE my_xhs_order_0.t_order_event_0;
CREATE TABLE IF NOT EXISTS t_order_event_3 LIKE my_xhs_order_0.t_order_event_0;

-- ==================== 分片数据库 3 ====================
CREATE DATABASE IF NOT EXISTS my_xhs_order_3 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE my_xhs_order_3;

CREATE TABLE IF NOT EXISTS t_order_0 LIKE my_xhs_order_0.t_order_0;
CREATE TABLE IF NOT EXISTS t_order_1 LIKE my_xhs_order_0.t_order_0;
CREATE TABLE IF NOT EXISTS t_order_2 LIKE my_xhs_order_0.t_order_0;
CREATE TABLE IF NOT EXISTS t_order_3 LIKE my_xhs_order_0.t_order_0;
CREATE TABLE IF NOT EXISTS t_order_item_0 LIKE my_xhs_order_0.t_order_item_0;
CREATE TABLE IF NOT EXISTS t_order_item_1 LIKE my_xhs_order_0.t_order_item_0;
CREATE TABLE IF NOT EXISTS t_order_item_2 LIKE my_xhs_order_0.t_order_item_0;
CREATE TABLE IF NOT EXISTS t_order_item_3 LIKE my_xhs_order_0.t_order_item_0;
CREATE TABLE IF NOT EXISTS t_local_message_0 LIKE my_xhs_order_0.t_local_message_0;
CREATE TABLE IF NOT EXISTS t_local_message_1 LIKE my_xhs_order_0.t_local_message_0;
CREATE TABLE IF NOT EXISTS t_local_message_2 LIKE my_xhs_order_0.t_local_message_0;
CREATE TABLE IF NOT EXISTS t_local_message_3 LIKE my_xhs_order_0.t_local_message_0;
CREATE TABLE IF NOT EXISTS t_order_snapshot_0 LIKE my_xhs_order_0.t_order_snapshot_0;
CREATE TABLE IF NOT EXISTS t_order_snapshot_1 LIKE my_xhs_order_0.t_order_snapshot_0;
CREATE TABLE IF NOT EXISTS t_order_snapshot_2 LIKE my_xhs_order_0.t_order_snapshot_0;
CREATE TABLE IF NOT EXISTS t_order_snapshot_3 LIKE my_xhs_order_0.t_order_snapshot_0;
CREATE TABLE IF NOT EXISTS t_order_event_0 LIKE my_xhs_order_0.t_order_event_0;
CREATE TABLE IF NOT EXISTS t_order_event_1 LIKE my_xhs_order_0.t_order_event_0;
CREATE TABLE IF NOT EXISTS t_order_event_2 LIKE my_xhs_order_0.t_order_event_0;
CREATE TABLE IF NOT EXISTS t_order_event_3 LIKE my_xhs_order_0.t_order_event_0;

-- =====================================================================
-- 五、库存（原 MySQL-Inventory :13309）
-- =====================================================================

CREATE DATABASE IF NOT EXISTS my_xhs_inventory DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE my_xhs_inventory;

CREATE TABLE IF NOT EXISTS t_inventory (
    id              BIGINT  NOT NULL COMMENT 'ID',
    sku_id          BIGINT  NOT NULL COMMENT 'SKU ID',
    available_stock INT     NOT NULL DEFAULT 0 COMMENT '可用库存',
    locked_stock    INT     NOT NULL DEFAULT 0 COMMENT '锁定库存',
    freezing_stock  INT     NOT NULL DEFAULT 0 COMMENT 'TCC冻结库存',
    deleted         TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_sku_id (sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库存表';

-- TCC Fence 防悬挂表（参考 Alibaba Seata TCC Fence 机制）
CREATE TABLE IF NOT EXISTS t_tcc_fence (
    xid VARCHAR(128) NOT NULL COMMENT '全局事务ID',
    branch_id BIGINT NOT NULL COMMENT '分支事务ID',
    action_name VARCHAR(64) NOT NULL COMMENT 'TCC方法名',
    status TINYINT NOT NULL COMMENT '1-已Try 2-已Confirm 3-已Cancel',
    gmt_create DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    gmt_modified DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (xid, branch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='TCC防悬挂表';

-- TCC 冻结明细表
CREATE TABLE IF NOT EXISTS t_tcc_freeze_detail (
    xid         VARCHAR(128) NOT NULL COMMENT '全局事务ID',
    branch_id   BIGINT       NOT NULL COMMENT '分支事务ID',
    sku_id      BIGINT       NOT NULL COMMENT 'SKU ID',
    quantity    INT          NOT NULL COMMENT '冻结数量',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1-已冻结 2-已确认 3-已取消',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '冻结时间',
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (xid, branch_id, sku_id),
    INDEX idx_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='TCC冻结明细表';

-- 库存事件 Outbox 表（MQ 可靠性：先落库再发送，Job 补发失败消息）
CREATE TABLE IF NOT EXISTS t_inventory_outbox (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT 'ID',
    order_id    BIGINT      NOT NULL COMMENT '订单ID',
    sku_id      BIGINT      NOT NULL COMMENT 'SKU ID',
    quantity    INT         NOT NULL COMMENT '数量',
    action      VARCHAR(32) NOT NULL COMMENT '事件类型: PRE_DEDUCT/CONFIRM/RELEASE',
    status      TINYINT     NOT NULL DEFAULT 0 COMMENT '0-待发送 1-已发送',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_order_sku (order_id, sku_id),
    INDEX idx_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库存事件Outbox表';

-- 库存回滚失败补偿表（回滚失败时记录，Job 自动重试，超限转人工）
CREATE TABLE IF NOT EXISTS t_inventory_compensation (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'ID',
    order_id    BIGINT       NOT NULL COMMENT '订单ID',
    sku_id      BIGINT       NOT NULL COMMENT 'SKU ID',
    quantity    INT          NOT NULL COMMENT '数量',
    fail_reason VARCHAR(512) NOT NULL DEFAULT '' COMMENT '失败原因',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0-待处理 1-已处理 2-重试超限(转人工)',
    retry_count INT          NOT NULL DEFAULT 0 COMMENT '已重试次数',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_status_retry_created (status, retry_count, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库存回滚补偿表';

-- =====================================================================
-- Canal CDC 账号（只创建一次）
-- =====================================================================
CREATE USER IF NOT EXISTS 'canal'@'%' IDENTIFIED BY 'Canal@2026#Sync';
GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'canal'@'%';
FLUSH PRIVILEGES;
