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

-- Canal 同步账号
CREATE USER IF NOT EXISTS 'canal'@'%' IDENTIFIED BY 'Canal@2026#Sync';
GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'canal'@'%';
FLUSH PRIVILEGES;
