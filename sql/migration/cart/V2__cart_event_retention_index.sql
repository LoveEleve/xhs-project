-- 购物车事件流水：补 created_at 索引（保留策略清理按时间删除，无索引会全表扫）
ALTER TABLE t_cart_event
    ADD INDEX idx_created_at (created_at);
