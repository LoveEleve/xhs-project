#!/bin/bash
# my-xhs 全部微服务停止脚本
# 用法: ./stop-all.sh

BASE_DIR="/data/workspace/my-xhs"
LOG_DIR="$BASE_DIR/logs"
PIDS_DIR="$BASE_DIR/pids"

echo "=== 停止所有 my-xhs 微服务 ==="

for PID_FILE in "$PIDS_DIR"/*.pid; do
    [ -f "$PID_FILE" ] || continue
    SERVICE=$(basename "$PID_FILE" .pid)
    PID=$(cat "$PID_FILE")
    if kill -0 "$PID" 2>/dev/null; then
        echo -n "停止 $SERVICE (PID=$PID)... "
        kill -15 "$PID" 2>/dev/null
        # 等最多 15 秒优雅停机
        for i in $(seq 1 15); do
            kill -0 "$PID" 2>/dev/null || break
            sleep 1
        done
        kill -9 "$PID" 2>/dev/null
        echo "已停止"
    else
        echo "$SERVICE 未运行"
    fi
    rm -f "$PID_FILE"
done

# 确认清理
sleep 1
LEFT=$(ps aux | grep "my-xhs-" | grep -v grep | wc -l)
if [ "$LEFT" -gt 0 ]; then
    echo "⚠️  仍有 $LEFT 个进程残留，强制清理..."
    ps aux | grep "my-xhs-" | grep -v grep | awk '{print $2}' | xargs kill -9 2>/dev/null
fi

echo "=== 全部停止完毕 ==="
