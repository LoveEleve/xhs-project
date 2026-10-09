-- =============================================
-- Cart 增量迁移 V1 — 事件流水幂等兜底（RV11）
-- 背景：CartEventSinkConsumer 注释声称 uk_msg_id 兜底，但建表缺该索引；
--       CLEAR 事件无 skuId，消费者以 0 哨兵落库（sku_id 保持 NOT NULL）。
-- 影响库：my_xhs_cart.t_cart_event
-- =============================================
USE my_xhs_cart;

-- 幂等唯一索引（msg_id 允许 NULL，多 NULL 不冲突）
ALTER TABLE t_cart_event
    ADD UNIQUE KEY uk_msg_id (msg_id);

-- 修正动作词汇表注释（实际：ADD/UPDATE/DELETE/CHECK/CHECK_ALL/CLEAR）
ALTER TABLE t_cart_event
    MODIFY COLUMN action VARCHAR(32) NOT NULL COMMENT 'ADD/UPDATE/DELETE/CHECK/CHECK_ALL/CLEAR';
