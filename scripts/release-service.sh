#!/usr/bin/env bash
# 版本化发布（停机切换 + 健康校验 + 自动回滚）
# 说明：当前是"停旧-起新"的有损发布（约 15-20s 窗口）；真蓝绿（双实例+流量切换）未实现。
# 用法: bash scripts/release-service.sh <module> [jar_path]
# 多实例（多活演练）：INSTANCE_ID=zone-b PORT_OVERRIDE=19026 JVM_EXTRA="..." bash scripts/release-service.sh product
#   - 仅启动 current 版本的第二实例，不创建版本目录、不影响主实例进程/软链
#   - 进程号记录在 pids/<module>-<instance>.pid，停止只杀本实例
set -u
MODULE=${1:?usage: release-service.sh <module> [jar]}
JAR_SRC=${2:-}
ROOT=/data/workspace/xhs-project
RELEASE_ROOT=/data2/releases/$MODULE
TS=$(date +%Y%m%d-%H%M%S)
NEW_DIR=$RELEASE_ROOT/$TS
CURRENT=$RELEASE_ROOT/current
INSTANCE_ID=${INSTANCE_ID:-}
PID_DIR=$ROOT/pids
mkdir -p "$PID_DIR" /data2/logs
PID_FILE=$PID_DIR/$MODULE${INSTANCE_ID:+-$INSTANCE_ID}.pid
LOG_FILE=/data2/logs/release-$MODULE${INSTANCE_ID:+-$INSTANCE_ID}.log

declare -A PORT
PORT[gateway]=19000; PORT[user]=19001; PORT[content]=19002; PORT[analytics]=19003
PORT[counter]=19004; PORT[product]=19006; PORT[cart]=19008; PORT[inventory]=19009
PORT[coupon]=19010; PORT[order]=19011; PORT[payment]=19012; PORT[notification]=19013
PORT[im]=19014; PORT[home]=19015; PORT[search]=19016
P=${PORT_OVERRIDE:-${PORT[$MODULE]:?unknown module: $MODULE}}

declare -A XMX
XMX[inventory]=1024m; XMX[order]=1024m; XMX[search]=1024m
MX=${XMX[$MODULE]:-512m}

# ---- SkyWalking Agent（APM）：默认自动探测，SW_AGENT_DISABLED=1 可关闭 ----
SW_AGENT_DIR=${SW_AGENT_DIR:-/data/workspace/skywalking-agent-9.7.0}
SW_COLLECTOR=${SW_COLLECTOR:-192.168.0.142:11800}
agent_opts() {
  [ "${SW_AGENT_DISABLED:-0}" = "1" ] && return 0
  [ -f "$SW_AGENT_DIR/skywalking-agent.jar" ] || return 0
  echo "-javaagent:$SW_AGENT_DIR/skywalking-agent.jar -Dskywalking.agent.service_name=my-xhs-$MODULE -Dskywalking.collector.backend_service=$SW_COLLECTOR -Dskywalking.logging.dir=/tmp/sw-logs/$MODULE"
}

[ -n "$JAR_SRC" ] || JAR_SRC=$ROOT/my-xhs-$MODULE/target/my-xhs-$MODULE-1.0-SNAPSHOT.jar
[ -f "$JAR_SRC" ] || { echo "❌ jar 不存在: $JAR_SRC"; exit 1; }

PREV=""
TARGET_DIR=$CURRENT
if [ -z "$INSTANCE_ID" ]; then
  mkdir -p "$NEW_DIR" /data2/logs
  cp "$JAR_SRC" "$NEW_DIR/app.jar"
  # 修复：readlink -f 对不存在路径会返回自身规范化路径 → 自引用软链；改为校验软链与目标存在
  if [ -L "$CURRENT" ]; then
    RAW=$(readlink "$CURRENT" 2>/dev/null || true)
    if [ -n "$RAW" ] && [ -f "$RAW/app.jar" ] && [ "$RAW" != "$CURRENT" ]; then
      PREV="$RAW"
    fi
  fi
  ln -sfn "$NEW_DIR" "$CURRENT"
  echo "== 发布 $MODULE -> $NEW_DIR（上一版: ${PREV:-无}） =="
else
  # 第二实例：优先使用最新构建产物（避免误用旧 current 导致"跑旧 jar"），否则回退 current
  INSTANCE_DIR=$RELEASE_ROOT/instance-$INSTANCE_ID
  mkdir -p "$INSTANCE_DIR"
  if [ -f "$JAR_SRC" ]; then
    cp "$JAR_SRC" "$INSTANCE_DIR/app.jar"
    echo "== 启动第二实例 $MODULE[$INSTANCE_ID] -> 最新构建（端口 $P） =="
  else
    [ -f "$CURRENT/app.jar" ] || { echo "❌ 无 current 版本，无法启动第二实例"; exit 1; }
    cp "$CURRENT/app.jar" "$INSTANCE_DIR/app.jar"
    echo "== 启动第二实例 $MODULE[$INSTANCE_ID] -> current（端口 $P） =="
  fi
  TARGET_DIR=$INSTANCE_DIR
fi

cd "$ROOT"
if [ -f .secrets/tokens.env ]; then set -a; source .secrets/tokens.env; set +a; fi

stop() {
  if [ -f "$PID_FILE" ]; then
    local pid
    pid=$(cat "$PID_FILE" 2>/dev/null || true)
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
    fi
    rm -f "$PID_FILE"
  elif [ -z "$INSTANCE_ID" ]; then
    # 无 pid 文件的旧进程兜底清理：跳过带 -Dserver.port= 的第二实例进程（绝不误杀）
    for pid in $(pgrep -f "\[m\]y-xhs-$MODULE-1.0-SNAPSHOT.jar|[r]eleases/$MODULE/.*app.jar" 2>/dev/null); do
      if ! tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | grep -q "server.port="; then
        kill "$pid" 2>/dev/null || true
      fi
    done
  fi
  # 确保端口释放：等待优雅停机；若仍有进程占用（pid 文件失效等），按端口精确清理占用者（校验是本模块 app.jar）
  for _ in $(seq 1 30); do
    holder=$(ss -ltnp 2>/dev/null | grep ":$P " | grep -o "pid=[0-9]*" | head -1 | cut -d= -f2)
    [ -z "$holder" ] && break
    if tr '\0' ' ' < "/proc/$holder/cmdline" 2>/dev/null | grep -qE "app\.jar|my-xhs-$MODULE"; then
      echo "   ⚠️ 端口 $P 仍被 pid=$holder 占用，清理中"
      kill "$holder" 2>/dev/null || true
    fi
    sleep 2
  done
  sleep 1
}
start() {
  local EXTRA=""
  case "$MODULE" in home|notification) EXTRA="-Dspring.profiles.active=dev";; esac
  [ -n "$INSTANCE_ID" ] && EXTRA="$EXTRA -Dserver.port=$P"
  local AGENT_OPTS
  AGENT_OPTS=$(agent_opts)
  [ -n "$AGENT_OPTS" ] && echo "   [SW] SkyWalking agent: service=my-xhs-$MODULE collector=$SW_COLLECTOR" || echo "   [SW] SkyWalking agent 未启用（缺 agent 包或已禁用）"
  nohup setsid env SW_MOUNT_FOLDERS=plugins,activations,bootstrap-plugins \
    java -Xms$MX -Xmx$MX ${AGENT_OPTS} ${JVM_EXTRA:-} $EXTRA -jar "$1/app.jar" > "$LOG_FILE" 2>&1 < /dev/null &
  echo $! > "$PID_FILE"
  # setsid 可能 fork（$! 非真实 java PID）：以端口实际监听者回写，确保 PID 校验可靠
  for _ in $(seq 1 25); do
    lp=$(listen_pid 2>/dev/null || true)
    if [ -n "$lp" ]; then
      echo "$lp" > "$PID_FILE"
      break
    fi
    sleep 1
  done
  echo "   已启动: $1/app.jar (pid $(cat "$PID_FILE"), port $P, log $LOG_FILE)"
}
health() {
  # 冷启动较慢的服务（DLQ 监控消费者初始化等）可能 >40s，窗口放宽到 60s
  for _ in $(seq 1 30); do
    code=$(curl -s -m 2 -o /dev/null -w "%{http_code}" "http://localhost:$P/actuator/health" 2>/dev/null)
    [ "$code" = "200" ] && return 0
    sleep 2
  done
  return 1
}
# 监听端口占用者 PID
listen_pid() {
  ss -ltnp 2>/dev/null | grep ":$P " | grep -o "pid=[0-9]*" | head -1 | cut -d= -f2
}
# 健康检查必须命中"本次启动的进程"，否则视为失败（防止旧进程占端口造成假成功）
verified_health() {
  health || return 1
  local actual started
  actual=$(listen_pid)
  started=$(cat "$PID_FILE" 2>/dev/null || true)
  if [ -n "$actual" ] && [ -n "$started" ] && [ "$actual" != "$started" ]; then
    echo "   ❌ 健康检查命中的进程 pid=$actual 不是本次启动的 pid=$started（疑似旧进程占端口）"
    return 1
  fi
  return 0
}

stop; start "$TARGET_DIR"
if verified_health; then
  # 版本保留：仅保留最近 3 个版本（与磁盘治理口径一致）
  mapfile -t OLD < <(find "$RELEASE_ROOT" -maxdepth 1 -mindepth 1 -type d ! -name current -printf '%f\n' | sort | head -n -3)
  for d in "${OLD[@]:-}"; do [ -n "$d" ] && rm -rf "$RELEASE_ROOT/$d"; done
  echo "== ✅ 发布成功: $MODULE${INSTANCE_ID:+[$INSTANCE_ID]} (端口 $P, Xmx$MX) =="; exit 0
fi
echo "== ❌ 健康检查失败，准备回滚 =="
if [ -n "$PREV" ] && [ -d "$PREV" ]; then
  ln -sfn "$PREV" "$CURRENT"
  stop; start "$TARGET_DIR"
  if verified_health; then echo "== ↩️ 已回滚到 $PREV =="; exit 2; fi
  echo "== ❌❌ 回滚后仍不健康，需人工介入 =="; exit 3
fi
echo "== ❌ 无上一版可回滚 =="; exit 3
