-- IM：修正 t_chat_message.conversation_id 的列注释
-- 背景：P0-B 修复前会话ID用 min(A,B)<<32|max(A,B) 哈希（存在确定性碰撞 → 跨用户串台），
-- 现改为"首条消息分配雪花ID并写入 t_chat_user_relation，双方复用"。列注释未同步，易误导排障。
ALTER TABLE t_chat_message
    MODIFY COLUMN conversation_id BIGINT NOT NULL
    COMMENT '会话ID（首条消息分配雪花ID并写入 t_chat_user_relation，双方复用；勿用哈希拼接）';
