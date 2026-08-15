-- M11 HITL DDL：ai_run 增加审批列（审批审计：谁/何时/理由）
-- 执行：mysql -h <host> -u root -p < hitl-approval.sql

USE my_xhs_ai;

ALTER TABLE ai_run ADD COLUMN approval_json TEXT DEFAULT NULL COMMENT 'HITL 审批 JSON：tool/args/status(PENDING|APPROVED|REJECTED)/approver/reason/requestedAt/decidedAt' AFTER session_id;
