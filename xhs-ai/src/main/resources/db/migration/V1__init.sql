-- xhs-ai 元数据表（V1）
-- 说明：AgentScope 状态表（agentscope_*）由框架自管，不在此迁移内

CREATE TABLE IF NOT EXISTS ai_session (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    session_id  VARCHAR(128) NOT NULL,
    title       VARCHAR(256) DEFAULT NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_session (user_id, session_id),
    KEY idx_updated_at (updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'AI 会话';

CREATE TABLE IF NOT EXISTS ai_message (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    session_id  VARCHAR(128) NOT NULL,
    user_id     BIGINT       NOT NULL,
    role        VARCHAR(16)  NOT NULL,
    content     MEDIUMTEXT,
    trace_id    VARCHAR(64)  DEFAULT NULL,
    tool_calls  JSON         DEFAULT NULL,
    tokens_in   INT          NOT NULL DEFAULT 0,
    tokens_out  INT          NOT NULL DEFAULT 0,
    capture_mode VARCHAR(16) NOT NULL DEFAULT 'sanitized',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_session (session_id, created_at),
    KEY idx_trace (trace_id),
    KEY idx_user_created (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'AI 消息归档';

CREATE TABLE IF NOT EXISTS ai_approval (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    session_id     VARCHAR(128) NOT NULL,
    user_id        BIGINT       NOT NULL,
    trace_id       VARCHAR(64)  DEFAULT NULL,
    tool           VARCHAR(128) NOT NULL,
    kind           VARCHAR(32)  DEFAULT NULL,
    raw_input      JSON         DEFAULT NULL,
    raw_input_hash VARCHAR(64)  DEFAULT NULL,
    patterns       JSON         DEFAULT NULL,
    risk           VARCHAR(16)  NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'pending',
    requested_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    decided_by     BIGINT       DEFAULT NULL,
    decided_at     DATETIME(3)  DEFAULT NULL,
    decision_reason VARCHAR(512) DEFAULT NULL,
    result         JSON         DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_session_status (session_id, status),
    KEY idx_status_requested (status, requested_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'HITL 审批';

CREATE TABLE IF NOT EXISTS ai_session_grant (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    session_id  VARCHAR(128) NOT NULL,
    permission  VARCHAR(128) NOT NULL,
    pattern     VARCHAR(256) NOT NULL,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    revoked_at  DATETIME(3)  DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_grant (user_id, session_id, permission, pattern, revoked_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '会话级授权（always）';

CREATE TABLE IF NOT EXISTS ai_audit (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    trace_id           VARCHAR(64)  DEFAULT NULL,
    actor              BIGINT       DEFAULT NULL,
    action             VARCHAR(128) NOT NULL,
    target             VARCHAR(512) DEFAULT NULL,
    params             JSON         DEFAULT NULL,
    content_categories JSON         DEFAULT NULL,
    redaction_count    INT          NOT NULL DEFAULT 0,
    result             VARCHAR(1024) DEFAULT NULL,
    created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_trace (trace_id),
    KEY idx_actor_created (actor, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '审计（只追加）';

CREATE TABLE IF NOT EXISTS ai_feedback (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    session_id VARCHAR(128) NOT NULL,
    message_id BIGINT       DEFAULT NULL,
    actor      BIGINT       NOT NULL,
    kind       VARCHAR(32)  NOT NULL,
    category   VARCHAR(64)  DEFAULT NULL,
    note       VARCHAR(1024) DEFAULT NULL,
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_session (session_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '反馈（不注入模型）';
