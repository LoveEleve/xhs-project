-- =============================================
-- MySQL Slave 主从复制初始化 — mysql-inventory
-- Master: mysql-inventory (13309) → Slave (13310)
-- 使用 GTID 自动定位
-- =============================================

STOP SLAVE;
CHANGE MASTER TO
  MASTER_HOST='127.0.0.1',
  MASTER_PORT=13309,
  MASTER_USER='repl',
  MASTER_PASSWORD='repl_pass',
  MASTER_AUTO_POSITION=1;
START SLAVE;
