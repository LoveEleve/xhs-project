#!/usr/bin/env bash
# 版本化发布（停机切换 + 健康校验 + 自动回滚）
# 说明：当前是"停旧-起新"的有损发布（约 15-20s 窗口）；真蓝绿（双实例+流量切换）未实现。
# 用法: bash scripts/release-service.sh <module> [jar_path]
set -u
MODULE=${1:?usage: release-service.sh <module> [jar]}
JAR_SRC=${2:-}
ROOT=/data/workspace/xhs-project
RELEASE_ROOT=/data2/releases/$MODULE
TS=$(date +%Y%m%d-%H%M%S)
NEW_DIR=$RELEASE_ROOT/$TS
CURRENT=$RELEASE_ROOT/current

declare -A PORT
PORT[gateway]=19000; PORT[user]=19001; PORT[content]=19002; PORT[analytics]=19003
PORT[counter]=19004; PORT[product]=19006; PORT[cart]=19008; PORT[inventory]=19009
PORT[coupon]=19010; PORT[order]=19011; PORT[payment]=19012; PORT[notification]=19013
PORT[im]=19014; PORT[home]=19015; PORT[search]=19016
P=${PORT[$MODULE]:?unknown module: $MODULE}

declare -A XMX
XMX[inventory]=1024m; XMX[order]=1024m; XMX[search]=1024m
MX=${XMX[$MODULE]:-512m}

[ -n "$JAR_SRC" ] || JAR_SRC=$ROOT/my-xhs-$MODULE/target/my-xhs-$MODULE-1.0-SNAPSHOT.jar
[ -f "$JAR_SRC" ] || { echo "❌ jar 不存在: $JAR_SRC"; exit 1; }

mkdir -p "$NEW_DIR" /data2/logs
cp "$JAR_SRC" "$NEW_DIR/app.jar"
# 修复：readlink -f 对不存在路径会返回自身规范化路径 → 自引用软链；改为校验软链与目标存在
PREV=""
if [ -L "$CURRENT" ]; then
  RAW=$(readlink "$CURRENT" 2>/dev/null || true)
  if [ -n "$RAW" ] && [ -f "$RAW/app.jar" ] && [ "$RAW" != "$CURRENT" ]; then
    PREV="$RAW"
  fi
fi
ln -sfn "$NEW_DIR" "$CURRENT"
echo "== 发布 $MODULE -> $NEW_DIR（上一版: ${PREV:-无}） =="

cd "$ROOT"
if [ -f .secrets/tokens.env ]; then set -a; source .secrets/tokens.env; set +a; fi

stop() {
  pkill -f "[m]y-xhs-$MODULE-1.0-SNAPSHOT.jar" 2>/dev/null || true
  pkill -f "[r]eleases/$MODULE/.*app.jar" 2>/dev/null || true
  sleep 3
}
start() {
  local EXTRA=""
  case "$MODULE" in home|notification) EXTRA="-Dspring.profiles.active=dev";; esac
  nohup setsid java -Xmx$MX $EXTRA -jar "$1/app.jar" > "/data2/logs/release-$MODULE.log" 2>&1 < /dev/null &
  echo "   已启动: $1/app.jar"
}
health() {
  for _ in $(seq 1 20); do
    code=$(curl -s -m 2 -o /dev/null -w "%{http_code}" "http://localhost:$P/actuator/health" 2>/dev/null)
    [ "$code" = "200" ] && return 0
    sleep 2
  done
  return 1
}

stop; start "$CURRENT"
if health; then
  # 版本保留：仅保留最近 5 个版本
  mapfile -t OLD < <(find "$RELEASE_ROOT" -maxdepth 1 -mindepth 1 -type d ! -name current -printf '%f\n' | sort | head -n -5)
  for d in "${OLD[@]:-}"; do [ -n "$d" ] && rm -rf "$RELEASE_ROOT/$d"; done
  echo "== ✅ 发布成功: $MODULE (端口 $P, Xmx$MX) =="; exit 0
fi
echo "== ❌ 健康检查失败，准备回滚 =="
if [ -n "$PREV" ] && [ -d "$PREV" ]; then
  ln -sfn "$PREV" "$CURRENT"
  stop; start "$CURRENT"
  if health; then echo "== ↩️ 已回滚到 $PREV =="; exit 2; fi
  echo "== ❌❌ 回滚后仍不健康，需人工介入 =="; exit 3
fi
echo "== ❌ 无上一版可回滚 =="; exit 3
