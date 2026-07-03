-- =============================================
-- MySQL-Order :13308 — 订单(4分片) + 支付
-- =============================================

-- 订单公共库（不分片：订单号映射表、RocketMQ死信表）
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

-- ==================== 支付服务 ====================
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

-- Canal 同步账号
CREATE USER IF NOT EXISTS 'canal'@'%' IDENTIFIED BY 'Canal@2026#Sync';
GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'canal'@'%';
FLUSH PRIVILEGES;
