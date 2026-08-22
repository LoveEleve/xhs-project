-- ==========================================
-- MyXHS MySQL 主从复制初始化（Slave 端）
-- ==========================================
-- Slave :3307 从 Master :3306 全量同步
-- Slave 不跑 init-all.sql（避免 GTID 冲突），数据由 binlog 全量同步
-- ==========================================

CHANGE MASTER TO
    MASTER_HOST = '127.0.0.1',
    MASTER_PORT = 3306,
    MASTER_USER = 'root',
    MASTER_PASSWORD = 'Xhs@2026#MySQL',
    MASTER_AUTO_POSITION = 1;

START SLAVE;
