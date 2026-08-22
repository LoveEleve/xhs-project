-- =============================================
-- MySQL Master 复制用户创建脚本
-- 在 Master 上执行，创建 replication 用户
-- 密码：repl_pass（开发环境）
-- =============================================

CREATE USER IF NOT EXISTS 'repl'@'%' IDENTIFIED BY 'repl_pass';
GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%';
FLUSH PRIVILEGES;
