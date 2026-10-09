-- 库存补偿表扩展：支持"退款回补"补偿（原表只服务预扣回滚）
-- 背景：退款回补（refund-restore）在 Redis 不可用等异常下会失败，原实现只打日志、无自动重试；
--       本迁移新增 type（1-预扣回滚 2-退款回补）与 user_id（回补按用户路由桶），
--       由 InventoryCompensationJob 按类型分流重试。
-- 说明：一次性迁移；全新环境由 init-all.sql 直接建出带列版本。
ALTER TABLE t_inventory_compensation
    ADD COLUMN type TINYINT NOT NULL DEFAULT 1 COMMENT '补偿类型：1-预扣回滚 2-退款回补' AFTER quantity,
    ADD COLUMN user_id BIGINT DEFAULT NULL COMMENT '用户ID（退款回补按用户路由库存桶；预扣回滚可空）' AFTER type;
