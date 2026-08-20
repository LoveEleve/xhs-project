-- ==========================================
-- MyXHS MySQL 主从复制初始化（Slave 端）
-- Slave 端口 3307，从 Master 3306 同步
-- 使用 GTID 自动定位复制位点
-- ==========================================

CHANGE MASTER TO
    MASTER_HOST = '127.0.0.1',
    MASTER_PORT = 3306,
    MASTER_USER = 'root',
    MASTER_PASSWORD = 'Xhs@2026#MySQL',
    MASTER_AUTO_POSITION = 1;

START SLAVE;
