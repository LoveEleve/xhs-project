#!/usr/bin/env bash
# 日志保留策略清理：
#   1) /data2/logs 按天滚动的 JSON 日志保留 N 天（默认 3 天）
#   2) ES 日志索引 myxhs-logs-* 保留 M 天（默认 7 天）
# 用法: bash scripts/log-cleanup.sh [文件保留天数] [ES保留天数]
set -u
KEEP_DAYS=${1:-3}
ES_KEEP_DAYS=${2:-7}
LOG_DIR=/data2/logs
ES_URL="http://192.168.0.142:19200"
ES_AUTH="elastic:Xhs@2026#Elastic"

[ -d "$LOG_DIR" ] || { echo "❌ 目录不存在: $LOG_DIR"; exit 1; }
BEFORE=$(du -sh "$LOG_DIR" | awk '{print $1}')
find "$LOG_DIR" -maxdepth 1 -name "*.json" -type f -mtime +"$KEEP_DAYS" -print -delete | wc -l | xargs echo "文件已删除数:"
AFTER=$(du -sh "$LOG_DIR" | awk '{print $1}')
echo "文件清理: $BEFORE -> $AFTER（保留 $KEEP_DAYS 天）"

CUTOFF=$(date -d "$ES_KEEP_DAYS days ago" +%Y.%m.%d)
echo "ES 索引清理（保留 >= $CUTOFF）:"
for idx in $(curl -s -u "$ES_AUTH" "$ES_URL/_cat/indices/myxhs-logs-*?h=index" 2>/dev/null); do
  d=${idx#myxhs-logs-}
  if [[ "$d" < "$CUTOFF" ]]; then
    curl -s -u "$ES_AUTH" -XDELETE "$ES_URL/$idx" >/dev/null && echo "  已删除: $idx"
  fi
done
echo "清理完成"
