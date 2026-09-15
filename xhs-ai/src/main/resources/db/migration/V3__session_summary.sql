-- 会话压缩摘要（长会话上下文工程）
CREATE TABLE IF NOT EXISTS ai_session_summary (
    session_id      VARCHAR(128) NOT NULL,
    user_id         BIGINT       NOT NULL,
    summary         TEXT         NOT NULL,
    upto_message_id BIGINT       NOT NULL,
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (session_id),
    KEY idx_user_updated (user_id, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '会话滚动摘要';
