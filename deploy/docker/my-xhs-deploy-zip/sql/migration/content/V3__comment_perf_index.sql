-- A2 性能：评论列表两条热查询的索引支撑
-- 1) 根评论：WHERE note_id=? AND parent_id=0 [AND deleted=0] ORDER BY id DESC LIMIT n
-- 2) 子评论/计数：WHERE parent_id IN (...) AND deleted=0（窗口函数 PARTITION BY parent_id ORDER BY id）
ALTER TABLE t_comment
    ADD INDEX idx_note_parent_deleted_id (note_id, parent_id, deleted, id),
    ADD INDEX idx_parent_deleted_id (parent_id, deleted, id);
-- 说明：单列 idx_note_id / idx_parent_id 成为上述复合索引的最左前缀，后续可在观察期后评估删除（减少写放大）。
