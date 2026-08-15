-- M5 Run Store DDL（my-xhs-ai 自有库，与业务只读账号物理隔离）
-- 执行：mysql -h <host> -u root -p < ai-run-store.sql
-- 账号 myxhs_ai_rw 仅授 my_xhs_ai 库（AI 自己的运行数据，可写）
CREATE DATABASE IF NOT EXISTS my_xhs_ai DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'myxhs_ai_rw'@'%' IDENTIFIED BY 'CHANGE_ME';
GRANT SELECT, INSERT, UPDATE, DELETE ON my_xhs_ai.* TO 'myxhs_ai_rw'@'%';
FLUSH PRIVILEGES;

USE my_xhs_ai;

CREATE TABLE IF NOT EXISTS ai_run (
  run_id             VARCHAR(32)  PRIMARY KEY COMMENT 'runId',
  user_id            VARCHAR(64)  NOT NULL DEFAULT 'anonymous' COMMENT '发起用户',
  session_id         VARCHAR(64)  DEFAULT NULL COMMENT '会话ID',
  query              TEXT         NOT NULL COMMENT '用户问题',
  status             VARCHAR(16)  NOT NULL COMMENT 'RUNNING/SUCCEEDED/PARTIAL/FAILED/CANCELLED/EXPIRED',
  termination_reason VARCHAR(32)  DEFAULT NULL COMMENT '终止原因(COMPLETED/BUDGET_STEPS/...)',
  budget_json        TEXT         DEFAULT NULL COMMENT '预算(JSON)',
  versions_json      TEXT         DEFAULT NULL COMMENT 'model/prompt/tool版本(JSON)',
  tokens_total       BIGINT       DEFAULT 0 COMMENT 'token 合计(输入+输出)',
  cost_est           DOUBLE       DEFAULT 0 COMMENT '成本估算',
  final_answer       MEDIUMTEXT   DEFAULT NULL COMMENT '最终答案(历史 run 追溯，M8-4 补列)',
  started_at         DATETIME(3)  DEFAULT NULL,
  ended_at           DATETIME(3)  DEFAULT NULL,
  last_activity_at   DATETIME(3)  DEFAULT NULL COMMENT '最后活动时间(每step更新，崩溃恢复判定)',
  KEY idx_status_started (status, started_at)
) COMMENT 'AI 诊断 Run 记录';

-- 历史补列（已建库环境执行一次）
-- ALTER TABLE ai_run ADD COLUMN final_answer MEDIUMTEXT DEFAULT NULL COMMENT '最终答案(历史 run 追溯)';

CREATE TABLE IF NOT EXISTS ai_step (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id            VARCHAR(32)  NOT NULL COMMENT 'runId',
  step_no           INT          NOT NULL COMMENT '步骤号',
  state             VARCHAR(24)  NOT NULL COMMENT 'THINK/TOOL/POLICY_DENIED/ANSWER',
  decision_json     TEXT         DEFAULT NULL COMMENT '模型决策(JSON)',
  tool_result       MEDIUMTEXT   DEFAULT NULL COMMENT '工具结果/拒绝原因',
  evidence_ids      VARCHAR(512) DEFAULT NULL COMMENT '证据id列表',
  messages_snapshot MEDIUMTEXT   DEFAULT NULL COMMENT 'LLM对话上下文checkpoint(JSON)',
  tokens_used       BIGINT       DEFAULT 0 COMMENT '该步 token 消耗(预算恢复用)',
  created_at        DATETIME(3)  DEFAULT NULL,
  KEY idx_run (run_id, step_no)
) COMMENT 'AI 诊断 Step 记录(checkpoint 粒度)';
