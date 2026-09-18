-- ============================================================
-- ADR-23 · 受控只读 SQL：只读账号 GRANT 脚本
-- 背景：AI Agent v1 一律禁止业务库 SQL（工具走 ES/指标/MQ 管理端）；
--       v1.5 若接线"受控只读查询"（AST 校验 + READ ONLY 事务 + 行数/超时 + 脱敏/审计），
--       需先执行本脚本创建最小权限账号（只读、仅业务库）。
-- 执行：mysql -uroot -p < ai-readonly-grant.sql
-- 注意：密码请通过密钥管理下发，勿提交真实口令；账号名与 MYXHS_AI_RO_DB_USER 对齐。
-- ============================================================

-- CREATE USER IF NOT EXISTS 'ai_readonly'@'%' IDENTIFIED BY '<CHANGE_ME_VIA_SECRETS>';

-- 只读授权：业务库（按需裁剪；分库分表库一并列出）
-- GRANT SELECT ON my_xhs_content.*    TO 'ai_readonly'@'%';
-- GRANT SELECT ON my_xhs_product.*    TO 'ai_readonly'@'%';
-- GRANT SELECT ON my_xhs_inventory.*  TO 'ai_readonly'@'%';
-- GRANT SELECT ON my_xhs_payment.*    TO 'ai_readonly'@'%';
-- GRANT SELECT ON my_xhs_order.*      TO 'ai_readonly'@'%';
-- GRANT SELECT ON my_xhs_order_0.*    TO 'ai_readonly'@'%';
-- GRANT SELECT ON my_xhs_order_1.*    TO 'ai_readonly'@'%';
-- GRANT SELECT ON my_xhs_order_2.*    TO 'ai_readonly'@'%';
-- GRANT SELECT ON my_xhs_order_3.*    TO 'ai_readonly'@'%';
-- GRANT SELECT ON my_xhs_user.*       TO 'ai_readonly'@'%';

-- 明确不授权：AI 自身库（my_xhs_ai 由服务账号 myxhs_ai 全权管理，只读查询无需接入）
-- FLUSH PRIVILEGES;

-- 运行态现状（2026-09-18 核查）：
--   SHOW GRANTS FOR 'myxhs_ai'@'%' => USAGE on *.* + ALL on my_xhs_ai.*
--   => 服务账号当前对业务库完全不可达（比设计更严格）；本脚本为 v1.5 接线准备。
