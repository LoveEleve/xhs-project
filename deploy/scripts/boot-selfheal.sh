#!/usr/bin/env bash
# my-xhs 开机自愈：根分区扩容（幂等） + 依赖等待 + 15 个 Java 服务拉起 + 重试补偿
# systemd: my-xhs-selfheal.service（开机执行），也可手动执行
set -u
REPO=/data/workspace/xhs-project
LOG=/var/log/my-xhs-selfheal.log
exec >>"$LOG" 2>&1
echo "===== $(date '+%F %T') boot-selfheal start ====="

cd "$REPO" || { echo "repo not found: $REPO"; exit 1; }
TOKENS_FILE="$REPO/.secrets/tokens.env"
[ -f "$TOKENS_FILE" ] || { echo "FATAL: tokens file missing"; exit 1; }

# ---- 1) 根分区扩容（仅当内核识别到容量 > 50GiB；幂等）----
CUR_SECTORS=$(cat /sys/block/vda/size 2>/dev/null || echo 0)
echo "vda size: ${CUR_SECTORS} sectors ($((CUR_SECTORS / 2097152)) GiB)"
if [ "$CUR_SECTORS" -gt 104857600 ]; then
  echo "detected disk grown, extending partition + filesystem..."
  growpart /dev/vda 1 || true
  resize2fs /dev/vda1 || true
  df -h / | tail -1
else
  echo "disk not grown yet (<= 50GiB), skip resize (冷启动后应可见新容量)"
fi

# ---- 2) 等待依赖（真实端口）----
wait_port() { # $1=port $2=name $3=max_seconds
  local i=0
  while [ "$i" -lt "$3" ]; do
    if (echo >"/dev/tcp/127.0.0.1/$1") 2>/dev/null; then echo "$2 ready"; return 0; fi
    sleep 2; i=$((i + 2))
  done
  echo "WARN: $2(:$1) not ready after ${3}s"
  return 1
}
wait_port 3306 mysql 300 || true
wait_port 18848 nacos 300 || true
wait_port 6379 redis 300 || true
wait_port 26379 redis-sentinel 300 || true
wait_port 9876 mq-namesrv 300 || true
wait_port 11911 mq-broker 300 || true
wait_port 19200 elasticsearch 300 || true
sleep 5

# ---- 3) 服务清单 ----
SERVICES="gateway:19000 user:19001 content:19002 analytics:19003 counter:19004 \
product:19006 cart:19008 inventory:19009 coupon:19010 order:19011 payment:19012 \
notification:19013 im:19014 home:19015 search:19016"

start_svc() { # $1=module $2=port
  local m=$1 port=$2
  if ss -lnt 2>/dev/null | grep -q ":$port "; then return 0; fi
  local jar="$REPO/my-xhs-$m/target/my-xhs-$m-1.0-SNAPSHOT.jar"
  [ -f "$jar" ] || { echo "WARN: jar missing $jar"; return 1; }
  # 注意：$(tr ...) 必须不加引号（词拆分出多个 NAME=VALUE）
  setsid -f env $(tr '\n' ' ' <"$TOKENS_FILE") \
    java -Xmx512m -jar "$jar" >"/tmp/my-xhs-$m.log" 2>&1 </dev/null
  echo "started $m (:$port)"
}

health_up() { # $1=port
  curl -sf --max-time 2 "http://127.0.0.1:$1/actuator/health" >/dev/null 2>&1
}

# xhs-ai（需要 tokens.env + .env.local；过滤注释行）
start_xhs_ai() {
  local port=19020
  if ss -lnt 2>/dev/null | grep -q ":$port "; then echo "xhs-ai already on :$port"; return 0; fi
  local jar="$REPO/xhs-ai/target/xhs-ai-0.1.0-SNAPSHOT.jar"
  [ -f "$jar" ] || { echo "WARN: xhs-ai jar missing"; return 1; }
  local envs
  envs="$(tr '\n' ' ' <"$TOKENS_FILE") $(grep -vE '^[[:space:]]*#|^[[:space:]]*$' "$REPO/.env.local" | tr '\n' ' ')"
  setsid -f env $envs java -Xmx1g -jar "$jar" > /tmp/xhs-ai.log 2>&1 </dev/null
  echo "started xhs-ai (:$port)"
}

# 首轮：启动未监听服务
for entry in $SERVICES; do start_svc "${entry%%:*}" "${entry##*:}"; done
start_xhs_ai

# 重试轮：最多 6 轮 × 30s；未监听的重启，直到 15/15 健康
for round in 1 2 3 4 5 6; do
  sleep 30
  UP=0
  for entry in $SERVICES; do health_up "${entry##*:}" && UP=$((UP + 1)); done
  health_up 19020 && UP=$((UP + 1))
  echo "round $round health: $UP/16 (incl. xhs-ai)"
  [ "$UP" -ge 16 ] && break
  for entry in $SERVICES; do
    m="${entry%%:*}"; p="${entry##*:}"
    health_up "$p" || start_svc "$m" "$p"
  done
  health_up 19020 || start_xhs_ai
done

echo "===== $(date '+%F %T') boot-selfheal done ====="
df -h /
