#!/bin/bash
# MySQL 全量备份（2026-09-20 review 重写）
# 修复历史问题：旧模板写死云环境 host/端口（21.91.124.110:13306-13309）→ mysqldump 失败但 2>/dev/null
# 吞错 + 无校验，长期静默产出 20 字节空备份。现改为走 DB 容器内 mysqldump 并强校验。
set -uo pipefail

BACKUP_DIR="/opt/backups/mysql"
DATE=$(date +%Y%m%d)
RETENTION=7
CONTAINER="my-xhs-mysql"
PASS="Xhs@2026#MySQL"
PORT="3306"          # 业务主库（6380 仅为 Redis；MySQL 主库 3306，3307 为从库不单独备份）

mkdir -p "$BACKUP_DIR"
OUT="$BACKUP_DIR/mysql_${PORT}_${DATE}.sql.gz"
TMP="$OUT.tmp"

echo "[$(date +%F' '%T)] 开始备份 MySQL(${CONTAINER}:${PORT}) -> $OUT"
if ! docker exec "$CONTAINER" mysqldump -h127.0.0.1 -P"$PORT" -uroot -p"$PASS" \
        --all-databases --single-transaction --routines --triggers --events \
        2> >(grep -v "Using a password" >&2) | gzip > "$TMP"; then
    echo "❌ mysqldump 失败" >&2; rm -f "$TMP"; exit 1
fi

SIZE=$(stat -c%s "$TMP" 2>/dev/null || echo 0)
if [ "$SIZE" -lt 100000 ]; then
    echo "❌ 备份过小(${SIZE}B)，判定失败（历史空备份教训）" >&2; rm -f "$TMP"; exit 1
fi
if ! gunzip -t "$TMP" 2>/dev/null; then
    echo "❌ 备份 gzip 校验失败" >&2; rm -f "$TMP"; exit 1
fi

mv "$TMP" "$OUT"
find "$BACKUP_DIR" -name "*.sql.gz" -mtime +$RETENTION -delete
echo "✅ 备份完成: $OUT ($(du -h "$OUT" | cut -f1))"
