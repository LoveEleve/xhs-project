-- M10 会话 DDL（my_xhs_ai 库，与 ai-run-store.sql 同库同账号）
-- 执行：mysql -h <host> -u root -p < conversation.sql

USE my_xhs_ai;

CREATE TABLE IF NOT EXISTS ai_conversation (
  conv_id           VARCHAR(32)  PRIMARY KEY,
  user_id           VARCHAR(64)  NOT NULL DEFAULT 'anonymous',
  title             VARCHAR(200) DEFAULT NULL COMMENT '首问截断',
  summary           TEXT         DEFAULT NULL COMMENT '会话级摘要（每轮更新）',
  message_count     INT          DEFAULT 0,
  created_at        DATETIME(3),
  last_activity_at  DATETIME(3),
  KEY idx_user_time (user_id, last_activity_at)
) COMMENT 'AI 诊断会话（多轮上下文载体）';

CREATE TABLE IF NOT EXISTS ai_message (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  conv_id       VARCHAR(32)  NOT NULL,
  role          VARCHAR(16)  NOT NULL COMMENT 'user/assistant',
  content       MEDIUMTEXT   NOT NULL,
  run_id        VARCHAR(32)  DEFAULT NULL COMMENT '产生该回答的 run（可追溯）',
  refs_json     TEXT         DEFAULT NULL COMMENT '证据引用（可选）',
  created_at    DATETIME(3),
  KEY idx_conv (conv_id, id)
) COMMENT '会话消息（user 提问 + assistant 结论）';
