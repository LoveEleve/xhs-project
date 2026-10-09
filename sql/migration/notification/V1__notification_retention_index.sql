-- 通知表保留策略（180 天）需要纯 created_at 索引：
-- 既有 (user_id, created_at) 无法服务 WHERE created_at < ? 的清理删除（会全表扫）
ALTER TABLE t_notification
    ADD INDEX idx_created_at (created_at);
