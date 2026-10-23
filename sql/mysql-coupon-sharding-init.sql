-- ============================================================
-- C-017 优惠券分片初始化（coupon-sharding profile）
-- ============================================================
-- 前置：先执行 sql/init-all.sql（单库 my_xhs_coupon 仍保留，默认 profile 使用）。
-- 分片：2 库 × 16 表 = 32；库=user_id % 2，表=(user_id DIV 2) % 16。
-- 模板/Outbox 为全局单表，放 ds_0（my_xhs_coupon_0）。
-- 幂等：CREATE IF NOT EXISTS + INSERT IGNORE，可重复执行。
-- 生产迁移建议：停机窗口或双写迁移（SQL 直接 INSERT 仅适合演示/小数据量）。
-- ============================================================

CREATE DATABASE IF NOT EXISTS my_xhs_coupon_0 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS my_xhs_coupon_1 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE my_xhs_coupon_0;
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='优惠券模板表（全局表，ds_0）';

CREATE TABLE IF NOT EXISTS t_coupon_outbox (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID',
    template_id     BIGINT       NOT NULL COMMENT '优惠券模板ID',
    claim_no        VARCHAR(64)  NOT NULL COMMENT '幂等流水号(UUID)',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '0=待发送 1=已发送 2=已作废（遗留值）',
    retry_count     INT          NOT NULL DEFAULT 0 COMMENT '补发失败次数',
    next_retry_time DATETIME     NULL COMMENT '下次可补发时间',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_claim_no (claim_no),
    INDEX idx_status_created (status, created_at),
    INDEX idx_status_next_retry (status, next_retry_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='优惠券Outbox表（全局表，ds_0）';


CREATE TABLE IF NOT EXISTS t_user_coupon_0 (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID（分片键）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表（分片）';


CREATE TABLE IF NOT EXISTS t_user_coupon_1 (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID（分片键）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表（分片）';


CREATE TABLE IF NOT EXISTS t_user_coupon_2 (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID（分片键）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表（分片）';


CREATE TABLE IF NOT EXISTS t_user_coupon_3 (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID（分片键）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表（分片）';


CREATE TABLE IF NOT EXISTS t_user_coupon_4 (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID（分片键）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表（分片）';


CREATE TABLE IF NOT EXISTS t_user_coupon_5 (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID（分片键）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表（分片）';


CREATE TABLE IF NOT EXISTS t_user_coupon_6 (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID（分片键）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表（分片）';


CREATE TABLE IF NOT EXISTS t_user_coupon_7 (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID（分片键）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表（分片）';


CREATE TABLE IF NOT EXISTS t_user_coupon_8 (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID（分片键）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表（分片）';


CREATE TABLE IF NOT EXISTS t_user_coupon_9 (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID（分片键）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表（分片）';


CREATE TABLE IF NOT EXISTS t_user_coupon_10 (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID（分片键）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表（分片）';


CREATE TABLE IF NOT EXISTS t_user_coupon_11 (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID（分片键）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表（分片）';


CREATE TABLE IF NOT EXISTS t_user_coupon_12 (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID（分片键）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表（分片）';


CREATE TABLE IF NOT EXISTS t_user_coupon_13 (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID（分片键）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表（分片）';


CREATE TABLE IF NOT EXISTS t_user_coupon_14 (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID（分片键）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表（分片）';


CREATE TABLE IF NOT EXISTS t_user_coupon_15 (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID（分片键）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表（分片）';


USE my_xhs_coupon_1;


CREATE TABLE IF NOT EXISTS t_user_coupon_0 (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID（分片键）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表（分片）';


CREATE TABLE IF NOT EXISTS t_user_coupon_1 (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID（分片键）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表（分片）';


CREATE TABLE IF NOT EXISTS t_user_coupon_2 (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID（分片键）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表（分片）';


CREATE TABLE IF NOT EXISTS t_user_coupon_3 (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID（分片键）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表（分片）';


CREATE TABLE IF NOT EXISTS t_user_coupon_4 (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID（分片键）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表（分片）';


CREATE TABLE IF NOT EXISTS t_user_coupon_5 (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID（分片键）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表（分片）';


CREATE TABLE IF NOT EXISTS t_user_coupon_6 (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID（分片键）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表（分片）';


CREATE TABLE IF NOT EXISTS t_user_coupon_7 (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID（分片键）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表（分片）';


CREATE TABLE IF NOT EXISTS t_user_coupon_8 (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID（分片键）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表（分片）';


CREATE TABLE IF NOT EXISTS t_user_coupon_9 (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID（分片键）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表（分片）';


CREATE TABLE IF NOT EXISTS t_user_coupon_10 (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID（分片键）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表（分片）';


CREATE TABLE IF NOT EXISTS t_user_coupon_11 (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID（分片键）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表（分片）';


CREATE TABLE IF NOT EXISTS t_user_coupon_12 (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID（分片键）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表（分片）';


CREATE TABLE IF NOT EXISTS t_user_coupon_13 (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID（分片键）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表（分片）';


CREATE TABLE IF NOT EXISTS t_user_coupon_14 (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID（分片键）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表（分片）';


CREATE TABLE IF NOT EXISTS t_user_coupon_15 (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID（分片键）',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表（分片）';


-- ============================================================
-- 存量迁移（单库 my_xhs_coupon → 32 分片）
-- 路由：user_id % 2 = 库； (user_id DIV 2) % 16 = 表
-- ============================================================

INSERT IGNORE INTO my_xhs_coupon_0.t_user_coupon_0
    (id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at)
SELECT id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at
FROM my_xhs_coupon.t_user_coupon
WHERE user_id % 2 = 0 AND (user_id DIV 2) % 16 = 0;

INSERT IGNORE INTO my_xhs_coupon_0.t_user_coupon_1
    (id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at)
SELECT id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at
FROM my_xhs_coupon.t_user_coupon
WHERE user_id % 2 = 0 AND (user_id DIV 2) % 16 = 1;

INSERT IGNORE INTO my_xhs_coupon_0.t_user_coupon_2
    (id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at)
SELECT id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at
FROM my_xhs_coupon.t_user_coupon
WHERE user_id % 2 = 0 AND (user_id DIV 2) % 16 = 2;

INSERT IGNORE INTO my_xhs_coupon_0.t_user_coupon_3
    (id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at)
SELECT id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at
FROM my_xhs_coupon.t_user_coupon
WHERE user_id % 2 = 0 AND (user_id DIV 2) % 16 = 3;

INSERT IGNORE INTO my_xhs_coupon_0.t_user_coupon_4
    (id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at)
SELECT id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at
FROM my_xhs_coupon.t_user_coupon
WHERE user_id % 2 = 0 AND (user_id DIV 2) % 16 = 4;

INSERT IGNORE INTO my_xhs_coupon_0.t_user_coupon_5
    (id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at)
SELECT id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at
FROM my_xhs_coupon.t_user_coupon
WHERE user_id % 2 = 0 AND (user_id DIV 2) % 16 = 5;

INSERT IGNORE INTO my_xhs_coupon_0.t_user_coupon_6
    (id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at)
SELECT id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at
FROM my_xhs_coupon.t_user_coupon
WHERE user_id % 2 = 0 AND (user_id DIV 2) % 16 = 6;

INSERT IGNORE INTO my_xhs_coupon_0.t_user_coupon_7
    (id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at)
SELECT id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at
FROM my_xhs_coupon.t_user_coupon
WHERE user_id % 2 = 0 AND (user_id DIV 2) % 16 = 7;

INSERT IGNORE INTO my_xhs_coupon_0.t_user_coupon_8
    (id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at)
SELECT id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at
FROM my_xhs_coupon.t_user_coupon
WHERE user_id % 2 = 0 AND (user_id DIV 2) % 16 = 8;

INSERT IGNORE INTO my_xhs_coupon_0.t_user_coupon_9
    (id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at)
SELECT id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at
FROM my_xhs_coupon.t_user_coupon
WHERE user_id % 2 = 0 AND (user_id DIV 2) % 16 = 9;

INSERT IGNORE INTO my_xhs_coupon_0.t_user_coupon_10
    (id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at)
SELECT id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at
FROM my_xhs_coupon.t_user_coupon
WHERE user_id % 2 = 0 AND (user_id DIV 2) % 16 = 10;

INSERT IGNORE INTO my_xhs_coupon_0.t_user_coupon_11
    (id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at)
SELECT id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at
FROM my_xhs_coupon.t_user_coupon
WHERE user_id % 2 = 0 AND (user_id DIV 2) % 16 = 11;

INSERT IGNORE INTO my_xhs_coupon_0.t_user_coupon_12
    (id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at)
SELECT id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at
FROM my_xhs_coupon.t_user_coupon
WHERE user_id % 2 = 0 AND (user_id DIV 2) % 16 = 12;

INSERT IGNORE INTO my_xhs_coupon_0.t_user_coupon_13
    (id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at)
SELECT id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at
FROM my_xhs_coupon.t_user_coupon
WHERE user_id % 2 = 0 AND (user_id DIV 2) % 16 = 13;

INSERT IGNORE INTO my_xhs_coupon_0.t_user_coupon_14
    (id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at)
SELECT id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at
FROM my_xhs_coupon.t_user_coupon
WHERE user_id % 2 = 0 AND (user_id DIV 2) % 16 = 14;

INSERT IGNORE INTO my_xhs_coupon_0.t_user_coupon_15
    (id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at)
SELECT id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at
FROM my_xhs_coupon.t_user_coupon
WHERE user_id % 2 = 0 AND (user_id DIV 2) % 16 = 15;

INSERT IGNORE INTO my_xhs_coupon_1.t_user_coupon_0
    (id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at)
SELECT id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at
FROM my_xhs_coupon.t_user_coupon
WHERE user_id % 2 = 1 AND (user_id DIV 2) % 16 = 0;

INSERT IGNORE INTO my_xhs_coupon_1.t_user_coupon_1
    (id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at)
SELECT id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at
FROM my_xhs_coupon.t_user_coupon
WHERE user_id % 2 = 1 AND (user_id DIV 2) % 16 = 1;

INSERT IGNORE INTO my_xhs_coupon_1.t_user_coupon_2
    (id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at)
SELECT id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at
FROM my_xhs_coupon.t_user_coupon
WHERE user_id % 2 = 1 AND (user_id DIV 2) % 16 = 2;

INSERT IGNORE INTO my_xhs_coupon_1.t_user_coupon_3
    (id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at)
SELECT id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at
FROM my_xhs_coupon.t_user_coupon
WHERE user_id % 2 = 1 AND (user_id DIV 2) % 16 = 3;

INSERT IGNORE INTO my_xhs_coupon_1.t_user_coupon_4
    (id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at)
SELECT id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at
FROM my_xhs_coupon.t_user_coupon
WHERE user_id % 2 = 1 AND (user_id DIV 2) % 16 = 4;

INSERT IGNORE INTO my_xhs_coupon_1.t_user_coupon_5
    (id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at)
SELECT id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at
FROM my_xhs_coupon.t_user_coupon
WHERE user_id % 2 = 1 AND (user_id DIV 2) % 16 = 5;

INSERT IGNORE INTO my_xhs_coupon_1.t_user_coupon_6
    (id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at)
SELECT id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at
FROM my_xhs_coupon.t_user_coupon
WHERE user_id % 2 = 1 AND (user_id DIV 2) % 16 = 6;

INSERT IGNORE INTO my_xhs_coupon_1.t_user_coupon_7
    (id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at)
SELECT id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at
FROM my_xhs_coupon.t_user_coupon
WHERE user_id % 2 = 1 AND (user_id DIV 2) % 16 = 7;

INSERT IGNORE INTO my_xhs_coupon_1.t_user_coupon_8
    (id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at)
SELECT id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at
FROM my_xhs_coupon.t_user_coupon
WHERE user_id % 2 = 1 AND (user_id DIV 2) % 16 = 8;

INSERT IGNORE INTO my_xhs_coupon_1.t_user_coupon_9
    (id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at)
SELECT id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at
FROM my_xhs_coupon.t_user_coupon
WHERE user_id % 2 = 1 AND (user_id DIV 2) % 16 = 9;

INSERT IGNORE INTO my_xhs_coupon_1.t_user_coupon_10
    (id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at)
SELECT id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at
FROM my_xhs_coupon.t_user_coupon
WHERE user_id % 2 = 1 AND (user_id DIV 2) % 16 = 10;

INSERT IGNORE INTO my_xhs_coupon_1.t_user_coupon_11
    (id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at)
SELECT id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at
FROM my_xhs_coupon.t_user_coupon
WHERE user_id % 2 = 1 AND (user_id DIV 2) % 16 = 11;

INSERT IGNORE INTO my_xhs_coupon_1.t_user_coupon_12
    (id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at)
SELECT id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at
FROM my_xhs_coupon.t_user_coupon
WHERE user_id % 2 = 1 AND (user_id DIV 2) % 16 = 12;

INSERT IGNORE INTO my_xhs_coupon_1.t_user_coupon_13
    (id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at)
SELECT id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at
FROM my_xhs_coupon.t_user_coupon
WHERE user_id % 2 = 1 AND (user_id DIV 2) % 16 = 13;

INSERT IGNORE INTO my_xhs_coupon_1.t_user_coupon_14
    (id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at)
SELECT id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at
FROM my_xhs_coupon.t_user_coupon
WHERE user_id % 2 = 1 AND (user_id DIV 2) % 16 = 14;

INSERT IGNORE INTO my_xhs_coupon_1.t_user_coupon_15
    (id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at)
SELECT id, user_id, coupon_id, claim_no, status, used_order_id, received_at, used_at
FROM my_xhs_coupon.t_user_coupon
WHERE user_id % 2 = 1 AND (user_id DIV 2) % 16 = 15;


INSERT IGNORE INTO my_xhs_coupon_0.t_coupon_template
    (id, name, type, discount_value, min_amount, total_count, remain_count, per_user_limit,
     valid_start, valid_end, status, deleted, created_at, updated_at)
SELECT id, name, type, discount_value, min_amount, total_count, remain_count, per_user_limit,
       valid_start, valid_end, status, deleted, created_at, updated_at
FROM my_xhs_coupon.t_coupon_template;

INSERT IGNORE INTO my_xhs_coupon_0.t_coupon_outbox
    (id, user_id, template_id, claim_no, status, retry_count, next_retry_time, created_at)
SELECT id, user_id, template_id, claim_no, status, retry_count, next_retry_time, created_at
FROM my_xhs_coupon.t_coupon_outbox;
