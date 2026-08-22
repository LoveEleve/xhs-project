#!/bin/bash
# ============================================================
# P-D19: MySQL 每日备份（全量 mysqldump + binlog 增量提示）
# 在中间件机执行；建议 crontab -e 添加：
#   0 2 * * * /data/workspace/my-xhs-deploy-zip/mysql-backup.sh >> /var/log/mysql-backup.log 2>&1
# 依赖：mysql/mysqldump 客户端（或 docker exec my-xhs-mysql ...）
# ============================================================
set -e
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASS="${MYSQL_PASS:-Xhs@2026#MySQL}"
BACKUP_DIR="${BACKUP_DIR:-/data/backups/mysql}"
KEEP_DAYS="${KEEP_DAYS:-7}"

mkdir -p "$BACKUP_DIR"
STAMP=$(date +%Y%m%d-%H%M%S)
DUMP="$BACKUP_DIR/mysql-full-$STAMP.sql.gz"

echo "[$(date '+%F %T')] 开始全量备份 → $DUMP"
if command -v mysqldump >/dev/null 2>&1; then
  mysqldump -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" -p"$MYSQL_PASS" \
    --all-databases --single-transaction --source-data=2 --routines --triggers --events \
    | gzip > "$DUMP"
else
  echo "（宿主机无 mysqldump，改用容器内执行）"
  docker exec my-xhs-mysql sh -c "mysqldump -uroot -p'$MYSQL_PASS' --all-databases --single-transaction --source-data=2 --routines --triggers --events 2>/dev/null" \
    | gzip > "$DUMP"
fi

echo "备份完成: $(du -h "$DUMP" | cut -f1)"

echo "清理 $KEEP_DAYS 天前的全量备份:"
find "$BACKUP_DIR" -name 'mysql-full-*.sql.gz' -mtime +"$KEEP_DAYS" -delete -print

echo "[$(date '+%F %T')] 完成。当前备份列表:"
ls -lh "$BACKUP_DIR" | tail -8

echo ""
echo "【提示】binlog 增量：主库 binlog 保留 30 天，若需增量恢复请确保"
echo "  - /var/lib/mysql 的 mysql-bin.* 未被清理（binlog_expire_logs_seconds 控制）"
echo "  - 恢复演练：zcat $DUMP | mysql -h127.0.0.1 -P3306 -uroot -p'...'"
