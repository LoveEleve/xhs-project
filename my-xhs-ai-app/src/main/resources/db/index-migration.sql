-- 指标查询性能迁移：为 t_order 分片 + t_payment 加 (created_at, deleted) 组合索引
-- 目的：支持 WHERE created_at >= ? AND created_at < ? AND deleted = 0 走索引，避免全表扫描×16
-- ⚠️ 应用时机：数据量增长后（当前数据量小，未强制）；由基建/后端 Owner 在维护窗口执行
-- 注：组合索引列序 created_at 在前（范围条件），deleted 在后（等值过滤）

CREATE INDEX idx_created_deleted ON my_xhs_order_0.t_order_0 (created_at, deleted);
CREATE INDEX idx_created_deleted ON my_xhs_order_0.t_order_1 (created_at, deleted);
CREATE INDEX idx_created_deleted ON my_xhs_order_0.t_order_2 (created_at, deleted);
CREATE INDEX idx_created_deleted ON my_xhs_order_0.t_order_3 (created_at, deleted);
CREATE INDEX idx_created_deleted ON my_xhs_order_1.t_order_0 (created_at, deleted);
CREATE INDEX idx_created_deleted ON my_xhs_order_1.t_order_1 (created_at, deleted);
CREATE INDEX idx_created_deleted ON my_xhs_order_1.t_order_2 (created_at, deleted);
CREATE INDEX idx_created_deleted ON my_xhs_order_1.t_order_3 (created_at, deleted);
CREATE INDEX idx_created_deleted ON my_xhs_order_2.t_order_0 (created_at, deleted);
CREATE INDEX idx_created_deleted ON my_xhs_order_2.t_order_1 (created_at, deleted);
CREATE INDEX idx_created_deleted ON my_xhs_order_2.t_order_2 (created_at, deleted);
CREATE INDEX idx_created_deleted ON my_xhs_order_2.t_order_3 (created_at, deleted);
CREATE INDEX idx_created_deleted ON my_xhs_order_3.t_order_0 (created_at, deleted);
CREATE INDEX idx_created_deleted ON my_xhs_order_3.t_order_1 (created_at, deleted);
CREATE INDEX idx_created_deleted ON my_xhs_order_3.t_order_2 (created_at, deleted);
CREATE INDEX idx_created_deleted ON my_xhs_order_3.t_order_3 (created_at, deleted);

-- payment 单表
CREATE INDEX idx_created_deleted ON my_xhs_payment.t_payment (created_at, deleted);
