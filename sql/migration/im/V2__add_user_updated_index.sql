-- IM：会话列表查询（user_id + is_deleted + ORDER BY updated_at DESC）缺复合索引
-- 原有两个单列索引（idx_user_id / idx_updated_at）→ 排序走 filesort；补 (user_id, updated_at) 覆盖
ALTER TABLE t_chat_user_relation
    ADD INDEX idx_user_updated (user_id, updated_at);
