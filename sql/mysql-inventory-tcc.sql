-- =============================================
-- MySQL-Inventory TCC 库存预扣迁移
-- =============================================
-- 为 t_inventory 表新增 freezing_stock 列（TCC 冻结库存）
-- 并创建 t_tcc_fence 防悬挂表
-- =============================================

USE my_xhs_inventory;

-- Step 1: 新增 TCC 冻结库存字段
ALTER TABLE t_inventory ADD COLUMN freezing_stock INT NOT NULL DEFAULT 0 COMMENT 'TCC冻结库存' AFTER locked_stock;

-- Step 2: 创建 TCC Fence 防悬挂表
-- 设计参考 Alibaba Seata TCC Fence 机制：
-- - 幂等：INSERT fence 记录时用主键冲突保证 Try 只执行一次
-- - 空回滚：Cancel 时先 INSERT fence 记录（状态=CANCELLED），Try 还没执行则插入成功但什么都不做
-- - 悬挂：Try 时检查 fence 表是否有 Cancel 记录，有则拒绝执行
CREATE TABLE IF NOT EXISTS t_tcc_fence (
    xid VARCHAR(128) NOT NULL COMMENT '全局事务ID',
    branch_id BIGINT NOT NULL COMMENT '分支事务ID',
    action_name VARCHAR(64) NOT NULL COMMENT 'TCC方法名',
    status TINYINT NOT NULL COMMENT '1-已Try 2-已Confirm 3-已Cancel',
    gmt_create DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    gmt_modified DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (xid, branch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='TCC防悬挂表';
