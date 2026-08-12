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

-- =====================================================================
-- 以下由 DEPLOY-CLOUD 补全（2026-08-12）：nacos_config/xxl_job 建表
-- 原 init-all.sql 仅建库未建表，全新部署时 Nacos/xxl-job 连空库会失败
-- =====================================================================

USE xxl_job;
CREATE TABLE `xxl_job_group` (\n  `id` int NOT NULL AUTO_INCREMENT,\n  `app_name` varchar(64) NOT NULL COMMENT '执行器AppName',\n  `title` varchar(12) NOT NULL COMMENT '执行器名称',\n  `address_type` tinyint NOT NULL DEFAULT '0' COMMENT '执行器地址类型：0=自动注册、1=手动录入',\n  `address_list` text COMMENT '执行器地址列表，多地址逗号分隔',\n  `update_time` datetime DEFAULT NULL,\n  PRIMARY KEY (`id`)\n) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
;
CREATE TABLE `xxl_job_info` (\n  `id` int NOT NULL AUTO_INCREMENT,\n  `job_group` int NOT NULL COMMENT '执行器主键ID',\n  `job_desc` varchar(255) NOT NULL,\n  `add_time` datetime DEFAULT NULL,\n  `update_time` datetime DEFAULT NULL,\n  `author` varchar(64) DEFAULT NULL COMMENT '作者',\n  `alarm_email` varchar(255) DEFAULT NULL COMMENT '报警邮件',\n  `schedule_type` varchar(50) NOT NULL DEFAULT 'NONE' COMMENT '调度类型',\n  `schedule_conf` varchar(128) DEFAULT NULL COMMENT '调度配置，值含义取决于调度类型',\n  `misfire_strategy` varchar(50) NOT NULL DEFAULT 'DO_NOTHING' COMMENT '调度过期策略',\n  `executor_route_strategy` varchar(50) DEFAULT NULL COMMENT '执行器路由策略',\n  `executor_handler` varchar(255) DEFAULT NULL COMMENT '执行器任务handler',\n  `executor_param` varchar(512) DEFAULT NULL COMMENT '执行器任务参数',\n  `executor_block_strategy` varchar(50) DEFAULT NULL COMMENT '阻塞处理策略',\n  `executor_timeout` int NOT NULL DEFAULT '0' COMMENT '任务执行超时时间，单位秒',\n  `executor_fail_retry_count` int NOT NULL DEFAULT '0' COMMENT '失败重试次数',\n  `glue_type` varchar(50) NOT NULL COMMENT 'GLUE类型',\n  `glue_source` mediumtext COMMENT 'GLUE源代码',\n  `glue_remark` varchar(128) DEFAULT NULL COMMENT 'GLUE备注',\n  `glue_updatetime` datetime DEFAULT NULL COMMENT 'GLUE更新时间',\n  `child_jobid` varchar(255) DEFAULT NULL COMMENT '子任务ID，多个逗号分隔',\n  `trigger_status` tinyint NOT NULL DEFAULT '0' COMMENT '调度状态：0-停止，1-运行',\n  `trigger_last_time` bigint NOT NULL DEFAULT '0' COMMENT '上次调度时间',\n  `trigger_next_time` bigint NOT NULL DEFAULT '0' COMMENT '下次调度时间',\n  PRIMARY KEY (`id`)\n) ENGINE=InnoDB AUTO_INCREMENT=17 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
;
CREATE TABLE `xxl_job_lock` (\n  `lock_name` varchar(50) NOT NULL COMMENT '锁名称',\n  PRIMARY KEY (`lock_name`)\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
;
CREATE TABLE `xxl_job_log` (\n  `id` bigint NOT NULL AUTO_INCREMENT,\n  `job_group` int NOT NULL COMMENT '执行器主键ID',\n  `job_id` int NOT NULL COMMENT '任务，主键ID',\n  `executor_address` varchar(255) DEFAULT NULL COMMENT '执行器地址，本次执行的地址',\n  `executor_handler` varchar(255) DEFAULT NULL COMMENT '执行器任务handler',\n  `executor_param` varchar(512) DEFAULT NULL COMMENT '执行器任务参数',\n  `executor_sharding_param` varchar(20) DEFAULT NULL COMMENT '执行器任务分片参数，格式如 1/2',\n  `executor_fail_retry_count` int NOT NULL DEFAULT '0' COMMENT '失败重试次数',\n  `trigger_time` datetime DEFAULT NULL COMMENT '调度-时间',\n  `trigger_code` int NOT NULL COMMENT '调度-结果',\n  `trigger_msg` text COMMENT '调度-日志',\n  `handle_time` datetime DEFAULT NULL COMMENT '执行-时间',\n  `handle_code` int NOT NULL COMMENT '执行-状态',\n  `handle_msg` text COMMENT '执行-日志',\n  `alarm_status` tinyint NOT NULL DEFAULT '0' COMMENT '告警状态：0-默认、1-无需告警、2-告警成功、3-告警失败',\n  PRIMARY KEY (`id`),\n  KEY `I_trigger_time` (`trigger_time`),\n  KEY `I_handle_code` (`handle_code`)\n) ENGINE=InnoDB AUTO_INCREMENT=107206 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
;
CREATE TABLE `xxl_job_log_report` (\n  `id` int NOT NULL AUTO_INCREMENT,\n  `trigger_day` datetime DEFAULT NULL COMMENT '调度-时间',\n  `running_count` int NOT NULL DEFAULT '0' COMMENT '运行中-日志数量',\n  `suc_count` int NOT NULL DEFAULT '0' COMMENT '执行成功-日志数量',\n  `fail_count` int NOT NULL DEFAULT '0' COMMENT '执行失败-日志数量',\n  `update_time` datetime DEFAULT NULL,\n  PRIMARY KEY (`id`),\n  UNIQUE KEY `i_trigger_day` (`trigger_day`) USING BTREE\n) ENGINE=InnoDB AUTO_INCREMENT=23 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
;
CREATE TABLE `xxl_job_logglue` (\n  `id` int NOT NULL AUTO_INCREMENT,\n  `job_id` int NOT NULL COMMENT '任务，主键ID',\n  `glue_type` varchar(50) DEFAULT NULL COMMENT 'GLUE类型',\n  `glue_source` mediumtext COMMENT 'GLUE源代码',\n  `glue_remark` varchar(128) NOT NULL COMMENT 'GLUE备注',\n  `add_time` datetime DEFAULT NULL,\n  `update_time` datetime DEFAULT NULL,\n  PRIMARY KEY (`id`)\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
;
CREATE TABLE `xxl_job_registry` (\n  `id` int NOT NULL AUTO_INCREMENT,\n  `registry_group` varchar(50) NOT NULL,\n  `registry_key` varchar(255) NOT NULL,\n  `registry_value` varchar(255) NOT NULL,\n  `update_time` datetime DEFAULT NULL,\n  PRIMARY KEY (`id`),\n  KEY `i_g_k_v` (`registry_group`,`registry_key`,`registry_value`)\n) ENGINE=InnoDB AUTO_INCREMENT=221 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
;
CREATE TABLE `xxl_job_user` (\n  `id` int NOT NULL AUTO_INCREMENT,\n  `username` varchar(50) NOT NULL COMMENT '账号',\n  `password` varchar(50) NOT NULL COMMENT '密码',\n  `role` tinyint NOT NULL COMMENT '角色：0-普通用户、1-管理员',\n  `permission` varchar(255) DEFAULT NULL COMMENT '权限：执行器ID列表，多个逗号分割',\n  PRIMARY KEY (`id`),\n  UNIQUE KEY `i_username` (`username`) USING BTREE\n) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
;

-- xxl-job 初始数据（锁记录 + 默认 admin 用户 admin/123456）
INSERT INTO xxl_job_lock (lock_name) VALUES ('schedule_lock')
  ON DUPLICATE KEY UPDATE lock_name='schedule_lock';
INSERT INTO xxl_job_user (id, username, password, role, permission)
VALUES (1, 'admin', 'e10adc3949ba59abbe56e057f20f883e', 1, NULL)
  ON DUPLICATE KEY UPDATE username='admin';

USE nacos_config;
/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/******************************************/
/*   表名称 = config_info                  */
/******************************************/
CREATE TABLE `config_info` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'id',
  `data_id` varchar(255) NOT NULL COMMENT 'data_id',
  `group_id` varchar(128) DEFAULT NULL COMMENT 'group_id',
  `content` longtext NOT NULL COMMENT 'content',
  `md5` varchar(32) DEFAULT NULL COMMENT 'md5',
  `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  `src_user` text COMMENT 'source user',
  `src_ip` varchar(50) DEFAULT NULL COMMENT 'source ip',
  `app_name` varchar(128) DEFAULT NULL COMMENT 'app_name',
  `tenant_id` varchar(128) DEFAULT '' COMMENT '租户字段',
  `c_desc` varchar(256) DEFAULT NULL COMMENT 'configuration description',
  `c_use` varchar(64) DEFAULT NULL COMMENT 'configuration usage',
  `effect` varchar(64) DEFAULT NULL COMMENT '配置生效的描述',
  `type` varchar(64) DEFAULT NULL COMMENT '配置的类型',
  `c_schema` text COMMENT '配置的模式',
  `encrypted_data_key` text NOT NULL COMMENT '密钥',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_configinfo_datagrouptenant` (`data_id`,`group_id`,`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_bin COMMENT='config_info';

/******************************************/
/*   表名称 = config_info_aggr             */
/******************************************/
CREATE TABLE `config_info_aggr` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'id',
  `data_id` varchar(255) NOT NULL COMMENT 'data_id',
  `group_id` varchar(128) NOT NULL COMMENT 'group_id',
  `datum_id` varchar(255) NOT NULL COMMENT 'datum_id',
  `content` longtext NOT NULL COMMENT '内容',
  `gmt_modified` datetime NOT NULL COMMENT '修改时间',
  `app_name` varchar(128) DEFAULT NULL COMMENT 'app_name',
  `tenant_id` varchar(128) DEFAULT '' COMMENT '租户字段',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_configinfoaggr_datagrouptenantdatum` (`data_id`,`group_id`,`tenant_id`,`datum_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_bin COMMENT='增加租户字段';


/******************************************/
/*   表名称 = config_info_beta             */
/******************************************/
CREATE TABLE `config_info_beta` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'id',
  `data_id` varchar(255) NOT NULL COMMENT 'data_id',
  `group_id` varchar(128) NOT NULL COMMENT 'group_id',
  `app_name` varchar(128) DEFAULT NULL COMMENT 'app_name',
  `content` longtext NOT NULL COMMENT 'content',
  `beta_ips` varchar(1024) DEFAULT NULL COMMENT 'betaIps',
  `md5` varchar(32) DEFAULT NULL COMMENT 'md5',
  `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  `src_user` text COMMENT 'source user',
  `src_ip` varchar(50) DEFAULT NULL COMMENT 'source ip',
  `tenant_id` varchar(128) DEFAULT '' COMMENT '租户字段',
  `encrypted_data_key` text NOT NULL COMMENT '密钥',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_configinfobeta_datagrouptenant` (`data_id`,`group_id`,`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_bin COMMENT='config_info_beta';

/******************************************/
/*   表名称 = config_info_tag              */
/******************************************/
CREATE TABLE `config_info_tag` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'id',
  `data_id` varchar(255) NOT NULL COMMENT 'data_id',
  `group_id` varchar(128) NOT NULL COMMENT 'group_id',
  `tenant_id` varchar(128) DEFAULT '' COMMENT 'tenant_id',
  `tag_id` varchar(128) NOT NULL COMMENT 'tag_id',
  `app_name` varchar(128) DEFAULT NULL COMMENT 'app_name',
  `content` longtext NOT NULL COMMENT 'content',
  `md5` varchar(32) DEFAULT NULL COMMENT 'md5',
  `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  `src_user` text COMMENT 'source user',
  `src_ip` varchar(50) DEFAULT NULL COMMENT 'source ip',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_configinfotag_datagrouptenanttag` (`data_id`,`group_id`,`tenant_id`,`tag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_bin COMMENT='config_info_tag';

/******************************************/
/*   表名称 = config_tags_relation         */
/******************************************/
CREATE TABLE `config_tags_relation` (
  `id` bigint(20) NOT NULL COMMENT 'id',
  `tag_name` varchar(128) NOT NULL COMMENT 'tag_name',
  `tag_type` varchar(64) DEFAULT NULL COMMENT 'tag_type',
  `data_id` varchar(255) NOT NULL COMMENT 'data_id',
  `group_id` varchar(128) NOT NULL COMMENT 'group_id',
  `tenant_id` varchar(128) DEFAULT '' COMMENT 'tenant_id',
  `nid` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'nid, 自增长标识',
  PRIMARY KEY (`nid`),
  UNIQUE KEY `uk_configtagrelation_configidtag` (`id`,`tag_name`,`tag_type`),
  KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_bin COMMENT='config_tag_relation';

/******************************************/
/*   表名称 = group_capacity               */
/******************************************/
CREATE TABLE `group_capacity` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `group_id` varchar(128) NOT NULL DEFAULT '' COMMENT 'Group ID，空字符表示整个集群',
  `quota` int(10) unsigned NOT NULL DEFAULT '0' COMMENT '配额，0表示使用默认值',
  `usage` int(10) unsigned NOT NULL DEFAULT '0' COMMENT '使用量',
  `max_size` int(10) unsigned NOT NULL DEFAULT '0' COMMENT '单个配置大小上限，单位为字节，0表示使用默认值',
  `max_aggr_count` int(10) unsigned NOT NULL DEFAULT '0' COMMENT '聚合子配置最大个数，，0表示使用默认值',
  `max_aggr_size` int(10) unsigned NOT NULL DEFAULT '0' COMMENT '单个聚合数据的子配置大小上限，单位为字节，0表示使用默认值',
  `max_history_count` int(10) unsigned NOT NULL DEFAULT '0' COMMENT '最大变更历史数量',
  `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_group_id` (`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_bin COMMENT='集群、各Group容量信息表';

/******************************************/
/*   表名称 = his_config_info              */
/******************************************/
CREATE TABLE `his_config_info` (
  `id` bigint(20) unsigned NOT NULL COMMENT 'id',
  `nid` bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'nid, 自增标识',
  `data_id` varchar(255) NOT NULL COMMENT 'data_id',
  `group_id` varchar(128) NOT NULL COMMENT 'group_id',
  `app_name` varchar(128) DEFAULT NULL COMMENT 'app_name',
  `content` longtext NOT NULL COMMENT 'content',
  `md5` varchar(32) DEFAULT NULL COMMENT 'md5',
  `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  `src_user` text COMMENT 'source user',
  `src_ip` varchar(50) DEFAULT NULL COMMENT 'source ip',
  `op_type` char(10) DEFAULT NULL COMMENT 'operation type',
  `tenant_id` varchar(128) DEFAULT '' COMMENT '租户字段',
  `encrypted_data_key` text NOT NULL COMMENT '密钥',
  PRIMARY KEY (`nid`),
  KEY `idx_gmt_create` (`gmt_create`),
  KEY `idx_gmt_modified` (`gmt_modified`),
  KEY `idx_did` (`data_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_bin COMMENT='多租户改造';


/******************************************/
/*   表名称 = tenant_capacity              */
/******************************************/
CREATE TABLE `tenant_capacity` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `tenant_id` varchar(128) NOT NULL DEFAULT '' COMMENT 'Tenant ID',
  `quota` int(10) unsigned NOT NULL DEFAULT '0' COMMENT '配额，0表示使用默认值',
  `usage` int(10) unsigned NOT NULL DEFAULT '0' COMMENT '使用量',
  `max_size` int(10) unsigned NOT NULL DEFAULT '0' COMMENT '单个配置大小上限，单位为字节，0表示使用默认值',
  `max_aggr_count` int(10) unsigned NOT NULL DEFAULT '0' COMMENT '聚合子配置最大个数',
  `max_aggr_size` int(10) unsigned NOT NULL DEFAULT '0' COMMENT '单个聚合数据的子配置大小上限，单位为字节，0表示使用默认值',
  `max_history_count` int(10) unsigned NOT NULL DEFAULT '0' COMMENT '最大变更历史数量',
  `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_bin COMMENT='租户容量信息表';


CREATE TABLE `tenant_info` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT 'id',
  `kp` varchar(128) NOT NULL COMMENT 'kp',
  `tenant_id` varchar(128) default '' COMMENT 'tenant_id',
  `tenant_name` varchar(128) default '' COMMENT 'tenant_name',
  `tenant_desc` varchar(256) DEFAULT NULL COMMENT 'tenant_desc',
  `create_source` varchar(32) DEFAULT NULL COMMENT 'create_source',
  `gmt_create` bigint(20) NOT NULL COMMENT '创建时间',
  `gmt_modified` bigint(20) NOT NULL COMMENT '修改时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_info_kptenantid` (`kp`,`tenant_id`),
  KEY `idx_tenant_id` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_bin COMMENT='tenant_info';

CREATE TABLE `users` (
	`username` varchar(50) NOT NULL PRIMARY KEY COMMENT 'username',
	`password` varchar(500) NOT NULL COMMENT 'password',
	`enabled` boolean NOT NULL COMMENT 'enabled'
);

CREATE TABLE `roles` (
	`username` varchar(50) NOT NULL COMMENT 'username',
	`role` varchar(50) NOT NULL COMMENT 'role',
	UNIQUE INDEX `idx_user_role` (`username` ASC, `role` ASC) USING BTREE
);

CREATE TABLE `permissions` (
    `role` varchar(50) NOT NULL COMMENT 'role',
    `resource` varchar(128) NOT NULL COMMENT 'resource',
    `action` varchar(8) NOT NULL COMMENT 'action',
    UNIQUE INDEX `uk_role_permission` (`role`,`resource`,`action`) USING BTREE
);

INSERT INTO users (username, password, enabled) VALUES ('nacos', '$2a$10$EuWPZHzz32dJN7jexM34MOeYirDdFAZm2kuWj7VEOJhhZkDrxfvUu', TRUE);

INSERT INTO roles (username, role) VALUES ('nacos', 'ROLE_ADMIN');

