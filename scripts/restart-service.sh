#!/bin/bash
# ============================================================
# 单服务重启脚本（固化 pitfalls #4/#79-1 等重启坑——勿再手搓）
# 用法: bash restart-service.sh <模块名> [--all]
#   模块: gateway user content analytics counter product cart inventory
#         coupon order payment notification im home search
#   参数对照 start-all.sh（analytics 需 admin-token、home/notification dev profile、
#         gateway 特殊、inventory/order/search HEAVY）
# 安全: 使用 [j] 技巧避免 pgrep 自匹配；先杀后启；更新 pids；验证 health+令牌
# ============================================================
set -u
cd /data/workspace/my-xhs
source .secrets/tokens.env
export ADMIN_TOKEN INTERNAL_TOKEN

# ---- 模块 → 端口/JAVA_OPTS 映射（与 start-all.sh 完全一致）----
declare -A PORT OPTS
PORT[gateway]=19000; PORT[user]=19001; PORT[content]=19002; PORT[analytics]=19003
PORT[counter]=19004; PORT[product]=19006; PORT[cart]=19008; PORT[inventory]=19009
PORT[coupon]=19010; PORT[order]=19011; PORT[payment]=19012; PORT[notification]=19013
PORT[im]=19014; PORT[home]=19015; PORT[search]=19016

BASE="-javaagent:/data/workspace/my-xhs/skywalking-agent-9.6.0/skywalking-agent.jar -Dskywalking.agent.service_name=%M% -Dskywalking.collector.backend_service=21.130.247.89:11800 -Xms512m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:MaxMetaspaceSize=256m -Dserver.tomcat.mbeanregistry.enabled=true"
OPTS[gateway]="-javaagent:/data/workspace/my-xhs/skywalking-agent-9.6.0/skywalking-agent.jar -Dskywalking.agent.service_name=%M% -Dskywalking.collector.backend_service=21.130.247.89:11800 -Xms256m -Xmx256m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:MaxMetaspaceSize=256m -Dspring.data.redis.host=21.130.247.89"
OPTS[analytics]="-javaagent:/data/workspace/my-xhs/skywalking-agent-9.6.0/skywalking-agent.jar -Dskywalking.agent.service_name=my-xhs-analytics -Dskywalking.collector.backend_service=21.130.247.89:11800 -Xms512m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:MaxMetaspaceSize=256m -Dmanagement.admin-token=${ADMIN_TOKEN} -Dserver.tomcat.mbeanregistry.enabled=true"
OPTS[inventory]="${BASE//%M%/my-xhs-inventory}"; OPTS[order]="${BASE//%M%/my-xhs-order}"; OPTS[search]="${BASE//%M%/my-xhs-search}"
OPTS[inventory]="${OPTS[inventory]/-Xms512m -Xmx512m/-Xms1024m -Xmx1024m}"
OPTS[order]="${OPTS[order]/-Xms512m -Xmx512m/-Xms1024m -Xmx1024m}"
OPTS[search]="${OPTS[search]/-Xms512m -Xmx512m/-Xms1024m -Xmx1024m}"

# dev profile: home/notification（N09-test-send / FeedTestController）
DEV_MODULES="home notification"

restart_one() {
    local MODULE="$1"
    local PORT="${PORT[$MODULE]}"
    local OPTS_V="${OPTS[$MODULE]:-${BASE//%M%/my-xhs-$MODULE}}"
    local JAR="/data/workspace/my-xhs/my-xhs-$MODULE/target/my-xhs-$MODULE-1.0-SNAPSHOT.jar"
    [ -f "$JAR" ] || { echo "❌ $MODULE jar 不存在: $JAR"; return 1; }

    echo "=== 重启 $MODULE (端口 $PORT) ==="
    # 1. 杀旧进程（[j] 技巧防自匹配；按 java 命令行而非 pids 文件）
    local OLD_PID=$(ps aux | grep "[j]ava.*my-xhs-$MODULE-1.0" | awk '{print $2}' | head -1)
    if [ -n "$OLD_PID" ]; then kill "$OLD_PID" 2>/dev/null && echo "  旧进程 $OLD_PID 已终止"; sleep 4; fi

    # 2. 启动（setsid 脱离会话防 shell 退出误杀；dev profile 追加）
    local EXTRA=""
    case " $DEV_MODULES " in *" $MODULE "*) EXTRA="-Dspring.profiles.active=dev";; esac
    setsid java $OPTS_V $EXTRA -jar "$JAR" < /dev/null > "/data/workspace/my-xhs/logs/my-xhs-$MODULE.log" 2>&1 &
    disown

    # 3. 等 health（最多 150s）
    for i in $(seq 1 50); do
        curl -sf --max-time 2 "http://localhost:$PORT/actuator/health" >/dev/null 2>&1 && break
        sleep 3
    done
    curl -sf --max-time 2 "http://localhost:$PORT/actuator/health" >/dev/null 2>&1 \
        || { echo "❌ $MODULE 未就绪（日志尾部）"; tail -5 "/data/workspace/my-xhs/logs/my-xhs-$MODULE.log"; return 1; }

    # 4. 确认真实 java PID + 令牌 + 写 pids
    local JPID=$(ps aux | grep "[j]ava.*my-xhs-$MODULE-1.0" | grep -v grep | awk '{print $2}' | head -1)
    local TOK=$(tr '\0' '\n' < "/proc/$JPID/environ" 2>/dev/null | grep -c 'INTERNAL_TOKEN\|ADMIN_TOKEN')
    echo "$JPID" > "/data/workspace/my-xhs/pids/my-xhs-$MODULE.pid"
    echo "✅ $MODULE UP (pid=$JPID 令牌:$TOK/2) 耗时约 $((i*3))s"
    [ "$TOK" = "2" ] || echo "  ⚠️ 令牌缺失——检查 .secrets/tokens.env"
}

if [ "${2:-}" = "--all" ] || [ "$1" = "--all" ]; then
    for m in gateway user content analytics counter product cart inventory coupon order payment notification im home search; do
        restart_one "$m"
    done
else
    MODULE="$1"
    [ -n "${PORT[$MODULE]:-}" ] || { echo "❌ 未知模块: $MODULE（可用: ${!PORT[@]}）"; exit 1; }
    restart_one "$MODULE"
fi
