#!/bin/bash
# MySQL 全量备份脚本（4实例）
# crontab: 0 2 * * * /opt/scripts/mysql-backup.sh

BACKUP_DIR="/opt/backups/mysql"
DATE=$(date +%Y%m%d)
RETENTION=7
PASS="Xhs@2026#MySQL"
HOST="21.91.124.110"

mkdir -p "$BACKUP_DIR"

for port in 13306 13307 13308 13309; do
    echo "备份 :$port..."
    mysqldump -h $HOST -P $port -u root -p"$PASS" \
        --all-databases --single-transaction --routines --triggers \
        --compress 2>/dev/null | gzip > "$BACKUP_DIR/mysql_${port}_${DATE}.sql.gz"
done

# 清理7天前
find "$BACKUP_DIR" -name "*.sql.gz" -mtime +$RETENTION -delete
echo "$DATE: $(ls $BACKUP_DIR/*_$DATE.sql.gz | wc -l) files"
