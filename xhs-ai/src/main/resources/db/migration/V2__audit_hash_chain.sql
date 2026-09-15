-- 审计防篡改：哈希链（prev_hash 指向前一条 entry_hash）
ALTER TABLE ai_audit
    ADD COLUMN prev_hash VARCHAR(64) DEFAULT NULL AFTER result,
    ADD COLUMN entry_hash VARCHAR(64) DEFAULT NULL AFTER prev_hash;

CREATE TABLE IF NOT EXISTS ai_audit_chain (
    id         TINYINT     NOT NULL,
    last_hash  VARCHAR(64) NOT NULL,
    last_id    BIGINT      NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '审计哈希链头';

INSERT INTO ai_audit_chain(id, last_hash, last_id) VALUES (1, 'GENESIS', 0)
    ON DUPLICATE KEY UPDATE last_hash = last_hash;
