-- =============================================
-- MySQL-Inventory :13309 — 库存（独立部署，锁竞争隔离）
-- =============================================

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

-- TCC 冻结明细表（xid 维度追踪每个冻结，替代 SKU 级聚合 freezing_stock 的超时判断）
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
-- 唯一键包含 action：同一 order_id+sku_id 会依次产生 PRE_DEDUCT/CONFIRM/RELEASE，
-- 若只按 (order_id, sku_id) 去重，后续事件会覆盖前一事件（已发送的 PRE_DEDUCT 被重置为待发送），
-- 造成重复扣减或事件丢失。
CREATE TABLE IF NOT EXISTS t_inventory_outbox (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT 'ID',
    order_id    BIGINT      NOT NULL COMMENT '订单ID',
    sku_id      BIGINT      NOT NULL COMMENT 'SKU ID',
    quantity    INT         NOT NULL COMMENT '数量',
    action      VARCHAR(32) NOT NULL COMMENT '事件类型: PRE_DEDUCT/CONFIRM/RELEASE',
    status      TINYINT     NOT NULL DEFAULT 0 COMMENT '0-待发送 1-已发送',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_order_sku_action (order_id, sku_id, action),
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

-- 预扣幂等表（MySQL 兜底，防 Redis key 丢失后重复扣减）
CREATE TABLE IF NOT EXISTS t_inventory_prededuct_idem (
    order_id   BIGINT   NOT NULL COMMENT '预扣orderId(pseudoOrderId)',
    sku_id     BIGINT   NOT NULL COMMENT 'SKU ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次预扣时间',
    PRIMARY KEY (order_id, sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='预扣幂等表';

-- Canal 同步账号
CREATE USER IF NOT EXISTS 'canal'@'%' IDENTIFIED BY 'Canal@2026#Sync';
GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'canal'@'%';
FLUSH PRIVILEGES;
