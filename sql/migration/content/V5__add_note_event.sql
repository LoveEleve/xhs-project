-- C-010：t_note_event 补入 content 基线
-- 背景：该表此前仅存在于 init-all.sql（content 库段），mysql-content-init / migration 均缺——
-- 按 content 基线初始化的环境，recordNoteEvent 写入失败（catch-log"不阻塞发布"）→ 笔记事件流水静默丢失。
CREATE TABLE IF NOT EXISTS t_note_event (
    id            BIGINT       NOT NULL COMMENT 'ID',
    note_id       BIGINT       NOT NULL COMMENT '笔记ID',
    user_id       BIGINT       NOT NULL COMMENT '用户ID',
    event_type    VARCHAR(32)  NOT NULL COMMENT '事件类型：PUBLISH/DELETE/UPDATE/COMMENT/LIKE/AUDIT',
    status        TINYINT      DEFAULT 0 COMMENT '笔记状态',
    audit_status  TINYINT      DEFAULT 0 COMMENT '审核状态',
    event_time    DATETIME     DEFAULT NULL COMMENT '事件时间',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_note_id (note_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='笔记事件流水(append-only)';
