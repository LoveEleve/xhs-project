#!/usr/bin/env bash
# 蓝绿发布（符号链接切换 + 健康校验 + 自动回滚）
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

[ -n "$JAR_SRC" ] || JAR_SRC=$ROOT/my-xhs-$MODULE/target/my-xhs-$MODULE-1.0-SNAPSHOT.jar
[ -f "$JAR_SRC" ] || { echo "❌ jar 不存在: $JAR_SRC"; exit 1; }

mkdir -p "$NEW_DIR" /data2/logs
cp "$JAR_SRC" "$NEW_DIR/app.jar"
PREV=$(readlink -f "$CURRENT" 2>/dev/null || true)
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
  nohup setsid java -Xmx512m -jar "$1/app.jar" > "/data2/logs/release-$MODULE.log" 2>&1 < /dev/null &
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
  echo "== ✅ 发布成功: $MODULE (端口 $P) =="; exit 0
fi
echo "== ❌ 健康检查失败，准备回滚 =="
if [ -n "$PREV" ] && [ -d "$PREV" ]; then
  ln -sfn "$PREV" "$CURRENT"
  stop; start "$CURRENT"
  if health; then echo "== ↩️ 已回滚到 $PREV =="; exit 2; fi
  echo "== ❌❌ 回滚后仍不健康，需人工介入 =="; exit 3
fi
echo "== ❌ 无上一版可回滚 =="; exit 3
