#!/bin/bash
# Redis RDB 备份脚本
# crontab: 0 */6 * * * /opt/scripts/redis-backup.sh

PASS="Xhs@2026#Redis"
HOST="21.91.124.110"
BACKUP_DIR="/opt/backups/redis"
DATE=$(date +%Y%m%d%H%M)
RETENTION=7

mkdir -p "$BACKUP_DIR"

for port in 16379; do
    redis-cli -h $HOST -p $port -a "$PASS" --no-auth-warning BGSAVE > /dev/null 2>&1
    sleep 5
done

# 复制 RDB 文件（需知道容器内路径，此处为示例）
echo "$DATE: BGSAVE triggered"
