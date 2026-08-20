#!/bin/bash
# my-xhs 全部微服务启动脚本
# 用法: ./start-all.sh
BASE_DIR="/data/workspace/my-xhs"
LOG_DIR="$BASE_DIR/logs"
PIDS_DIR="$BASE_DIR/pids"
mkdir -p "$LOG_DIR" "$PIDS_DIR"

# 安全令牌注入 (所有服务通过 ${ADMIN_TOKEN:}/ ${INTERNAL_TOKEN:} 读取)
# 【P-B4】令牌不再明文写死：优先读取环境变量，否则从 secrets 文件读取，
# 首次启动自动生成随机令牌并持久化（.secrets/tokens.env, chmod 600）。
# 生成后请勿提交该文件；重新生成 = 全部服务内部调用凭据更换。
TOKEN_FILE="${MYXHS_TOKEN_FILE:-$BASE_DIR/.secrets/tokens.env}"
if [ -z "${ADMIN_TOKEN:-}" ] || [ -z "${INTERNAL_TOKEN:-}" ]; then
    mkdir -p "$(dirname "$TOKEN_FILE")" && chmod 700 "$(dirname "$TOKEN_FILE")"
    if [ ! -f "$TOKEN_FILE" ]; then
        umask 077
        printf 'ADMIN_TOKEN=%s\nINTERNAL_TOKEN=%s\n' "$(openssl rand -hex 32)" "$(openssl rand -hex 32)" > "$TOKEN_FILE"
        chmod 600 "$TOKEN_FILE"
        echo "🔑 已生成随机管理/内部令牌: $TOKEN_FILE"
    fi
    . "$TOKEN_FILE"
fi
export ADMIN_TOKEN INTERNAL_TOKEN
[ -n "$ADMIN_TOKEN" ] && [ -n "$INTERNAL_TOKEN" ] || { echo "❌ 令牌缺失（ADMIN_TOKEN/INTERNAL_TOKEN），拒绝启动"; exit 1; }

# Redis 连接使用 Sentinel 模式（replica 已通过 replica-announce-ip 广播真实地址 21.130.247.89）
# 历史: 曾因 Docker slave 广播 127.0.0.1:6380 而临时 export SPRING_DATA_REDIS_SENTINEL_ENABLED=false 降级 standalone，现远程已修复，恢复 Sentinel 模式

JAVA_OPTS_BASE="-javaagent:/data/workspace/my-xhs/skywalking-agent-9.6.0/skywalking-agent.jar -Dskywalking.agent.service_name=SW_PLACEHOLDER -Dskywalking.collector.backend_service=21.130.247.89:11800 -Xms512m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:MaxMetaspaceSize=256m -Dserver.tomcat.mbeanregistry.enabled=true"
JAVA_OPTS_GW="-javaagent:/data/workspace/my-xhs/skywalking-agent-9.6.0/skywalking-agent.jar -Dskywalking.agent.service_name=SW_PLACEHOLDER -Dskywalking.collector.backend_service=21.130.247.89:11800 -Xms256m -Xmx256m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:MaxMetaspaceSize=256m -Dspring.data.redis.host=21.130.247.89"
JAVA_OPTS_HEAVY="-javaagent:/data/workspace/my-xhs/skywalking-agent-9.6.0/skywalking-agent.jar -Dskywalking.agent.service_name=SW_PLACEHOLDER -Dskywalking.collector.backend_service=21.130.247.89:11800 -Xms1024m -Xmx1024m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:MaxMetaspaceSize=256m -Dserver.tomcat.mbeanregistry.enabled=true"
# analytics 特殊: 需要 -Dmanagement.admin-token
JAVA_OPTS_ANALYTICS="-javaagent:/data/workspace/my-xhs/skywalking-agent-9.6.0/skywalking-agent.jar -Dskywalking.agent.service_name=my-xhs-analytics -Dskywalking.collector.backend_service=21.130.247.89:11800 -Xms512m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:MaxMetaspaceSize=256m -Dmanagement.admin-token=${ADMIN_TOKEN} -Dserver.tomcat.mbeanregistry.enabled=true"
# home/notification 需 dev profile 以启用测试端点(N09-test-send 等)
JAVA_OPTS_DEV="${JAVA_OPTS_BASE} -Dspring.profiles.active=dev"

echo "=== 启动所有 my-xhs 微服务 ==="

start_service() {
    local MODULE=$1
    local PORT=$2
    local JAVA_OPTS=${3:-$JAVA_OPTS_BASE}  # 默认 512MB，HEAVY 服务可覆盖为 1024MB
    
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
    setsid java ${JAVA_OPTS//SW_PLACEHOLDER/$MODULE} -jar "$JAR" < /dev/null > "$LOG" 2>&1 &
    local PID=$!
    echo $PID > "$PID_FILE"
    
    # 等待健康检查（60s 上限；失败时输出日志尾部快速定位）
    for i in $(seq 1 60); do
        if curl -sf --max-time 2 "http://localhost:$PORT/actuator/health" > /dev/null 2>&1; then
            echo "✅ (PID=$PID, ${i}s)"
            return 0
        fi
        sleep 1
    done
    echo "⚠️  未等到就绪信号 (PID=$PID)，日志尾部："
    tail -5 "$LOG" 2>/dev/null | sed 's/^/    /'
    return 1
}

echo ""

# ===== 核心基础服务（先启动） =====
start_service "my-xhs-user"         19001 &
start_service "my-xhs-content"      19002 &
start_service "my-xhs-analytics"    19003 "$JAVA_OPTS_ANALYTICS" &
start_service "my-xhs-counter"      19004 &
wait
sleep 2

# ===== 业务服务 =====
start_service "my-xhs-product"      19006 &
start_service "my-xhs-cart"         19008 &
start_service "my-xhs-inventory"    19009 "$JAVA_OPTS_HEAVY" &
start_service "my-xhs-coupon"       19010 &
wait
sleep 2

# ===== 交易链路 =====
start_service "my-xhs-order"        19011 "$JAVA_OPTS_HEAVY" &
start_service "my-xhs-payment"      19012 &
wait
sleep 2

# ===== 辅助服务 =====
start_service "my-xhs-notification" 19013 "$JAVA_OPTS_DEV" &
start_service "my-xhs-im"           19014 &
start_service "my-xhs-home"         19015 "$JAVA_OPTS_DEV" &
start_service "my-xhs-search"       19016 "$JAVA_OPTS_HEAVY" &
wait
sleep 2

# ===== Gateway 最后启动 =====
start_service "my-xhs-gateway"      19000 "$JAVA_OPTS_GW"

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
    STATUS=$(curl -sf --max-time 2 "http://localhost:$PORT/actuator/health" 2>/dev/null | grep -o '"status":"UP"' || echo "DOWN")
    echo "  :$PORT → $STATUS"
done

echo ""
echo "Sentinel Dashboard: http://21.130.247.89:8858"
echo "Nacos Console:      http://21.130.247.89:18848/nacos"
echo "内部令牌文件:       $TOKEN_FILE（chmod 600，勿提交/外传）"
