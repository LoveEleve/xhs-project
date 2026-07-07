-- =============================================
-- MySQL-Content :13307 — 内容 + 商品 + 购物车 + 优惠券
-- =============================================

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
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
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

-- Canal 同步账号
-- 本地消息表（Feed发送端可靠性保障）
CREATE TABLE IF NOT EXISTS t_local_message (
    id              BIGINT       NOT NULL COMMENT 'ID',
    topic           VARCHAR(64)  NOT NULL COMMENT 'MQ Topic',
    body            TEXT         NOT NULL COMMENT '消息体JSON',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '0=待发送 1=已发送 2=发送失败 3=死信',
    retry_count     INT          NOT NULL DEFAULT 0 COMMENT '重试次数',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_status_retry (status, retry_count, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地消息表(Feed可靠性保障)';

CREATE USER IF NOT EXISTS 'canal'@'%' IDENTIFIED BY 'Canal@2026#Sync';
GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'canal'@'%';
FLUSH PRIVILEGES;
