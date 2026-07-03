-- =============================================
-- MySQL-User :13306 — 用户 + 社交 + 基础设施
-- =============================================

-- 基础设施
CREATE DATABASE IF NOT EXISTS nacos_config DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS xxl_job DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

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

-- Canal 同步账号
CREATE USER IF NOT EXISTS 'canal'@'%' IDENTIFIED BY 'Canal@2026#Sync';
GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'canal'@'%';
FLUSH PRIVILEGES;
