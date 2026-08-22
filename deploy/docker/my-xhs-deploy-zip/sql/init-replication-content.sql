-- =============================================
-- MySQL Slave 主从复制初始化 — mysql-content
-- Master: mysql-content (13307) → Slave (13308)
-- 使用 GTID 自动定位
-- =============================================

STOP SLAVE;
CHANGE MASTER TO
  MASTER_HOST='127.0.0.1',
  MASTER_PORT=13307,
  MASTER_USER='repl',
  MASTER_PASSWORD='repl_pass',
  MASTER_AUTO_POSITION=1;
START SLAVE;
