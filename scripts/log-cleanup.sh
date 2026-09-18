#!/usr/bin/env bash
# 日志保留策略清理：/data2/logs 下按天滚动的 JSON 日志保留 N 天（默认 3 天）
# 用法: bash scripts/log-cleanup.sh [保留天数]
set -u
KEEP_DAYS=${1:-3}
LOG_DIR=/data2/logs
[ -d "$LOG_DIR" ] || { echo "❌ 目录不存在: $LOG_DIR"; exit 1; }
BEFORE=$(du -sh "$LOG_DIR" | awk '{print $1}')
find "$LOG_DIR" -maxdepth 1 -name "*.json" -type f -mtime +"$KEEP_DAYS" -print -delete | wc -l | xargs echo "已删除文件数:"
AFTER=$(du -sh "$LOG_DIR" | awk '{print $1}')
echo "清理完成: $BEFORE -> $AFTER（保留 $KEEP_DAYS 天）"
