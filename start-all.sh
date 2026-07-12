#!/bin/bash
# my-xhs 全部微服务启动脚本
# 用法: ./start-all.sh
BASE_DIR="/data/workspace/my-xhs"
LOG_DIR="$BASE_DIR/logs"
PIDS_DIR="$BASE_DIR/pids"
mkdir -p "$LOG_DIR" "$PIDS_DIR"

JAVA_OPTS_BASE="-Xms512m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:MaxMetaspaceSize=256m"
JAVA_OPTS_GW="-Xms256m -Xmx256m"
JAVA_OPTS_ORDER="-Xms512m -Xmx1024m"
JAVA_OPTS_SEARCH="-Xms512m -Xmx1024m"
JAVA_OPTS_INVENTORY="-Xms512m -Xmx1024m"

echo "=== 启动所有 my-xhs 微服务 ==="

start_service() {
    local MODULE=$1
    local PORT=$2
    local EXTRA_OPTS=${3:-}
    
    local JAR="$BASE_DIR/$MODULE/target/$MODULE-1.0-SNAPSHOT.jar"
    local LOG="$LOG_DIR/$MODULE.log"
    local PID_FILE="$PIDS_DIR/$MODULE.pid"
    
    if [ ! -f "$JAR" ]; then
        echo "❌ $MODULE — JAR 不存在: $JAR"
        return 1
    fi
    
    # 检查是否已在运行
    if [ -f "$PID_FILE" ]; then
        local OLD_PID=$(cat "$PID_FILE")
        if kill -0 "$OLD_PID" 2>/dev/null; then
            echo "⚠️  $MODULE 已在运行 (PID=$OLD_PID)，跳过"
            return 0
        fi
    fi
    
    echo -n "启动 $MODULE (端口 $PORT)... "
    nohup java $JAVA_OPTS_BASE $EXTRA_OPTS -jar "$JAR" > "$LOG" 2>&1 &
    local PID=$!
    echo $PID > "$PID_FILE"
    
    # 等待健康检查
    for i in $(seq 1 60); do
        if curl -sf "http://localhost:$PORT/actuator/health" > /dev/null 2>&1; then
            echo "✅ (PID=$PID)"
            return 0
        fi
        sleep 1
    done
    echo "⚠️  未等到就绪信号 (PID=$PID)"
}

echo ""

# ===== 核心基础服务（先启动） =====
start_service "my-xhs-user"         19001 &
start_service "my-xhs-content"      19002 &
start_service "my-xhs-analytics"    19003 &
start_service "my-xhs-counter"      19004 &
wait
sleep 2

# ===== 业务服务 =====
start_service "my-xhs-product"      19006 &
start_service "my-xhs-cart"         19008 &
start_service "my-xhs-inventory"    19009 "$JAVA_OPTS_INVENTORY" &
start_service "my-xhs-coupon"       19010 &
wait
sleep 2

# ===== 交易链路 =====
start_service "my-xhs-order"        19011 "$JAVA_OPTS_ORDER" &
start_service "my-xhs-payment"      19012 &
wait
sleep 2

# ===== 辅助服务 =====
start_service "my-xhs-notification" 19013 &
start_service "my-xhs-im"           19014 &
start_service "my-xhs-home"         19015 &
start_service "my-xhs-search"       19016 "$JAVA_OPTS_SEARCH" &
wait
sleep 2

# ===== Gateway 最后启动 =====
start_service "my-xhs-gateway"      19000 "$JAVA_OPTS_GW"
wait

echo ""
echo "=== 启动完毕 ==="
echo ""
echo "各服务端口:"
echo "  Gateway:     http://localhost:19000"
echo "  User:        http://localhost:19001"
echo "  Content:     http://localhost:19002"
echo "  Home (BFF):  http://localhost:19015"
echo "  Search:      http://localhost:19016"
echo ""

echo "验证健康状态:"
sleep 5
for PORT in 19000 19001 19002 19015 19016 19011 19012; do
    STATUS=$(curl -sf "http://localhost:$PORT/actuator/health" 2>/dev/null | grep -o '"status":"UP"' || echo "DOWN")
    echo "  :$PORT → $STATUS"
done

echo ""
echo "Sentinel Dashboard: http://21.130.247.89:8858"
echo "Nacos Console:      http://21.91.124.110:18848/nacos"
