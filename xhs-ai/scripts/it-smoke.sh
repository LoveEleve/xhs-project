#!/usr/bin/env bash
# IT 冒烟（集成层最小集）：服务依赖 + 中间件可达 + 审计一致性
# 用法: bash scripts/it-smoke.sh   （需本机中间件在跑）
set -u
BASE=${AI_BASE:-http://127.0.0.1:19020}
OK=0; TOTAL=0
chk() { # $1=名称 $2=命令
  TOTAL=$((TOTAL+1))
  if bash -c "$2" >/dev/null 2>&1; then echo "  ✓ $1"; OK=$((OK+1)); else echo "  ✗ $1"; fi
}
echo "== IT 冒烟（$BASE）=="
chk "服务健康 200（含 DB/Redis 组件）" "curl -sf -m 3 $BASE/actuator/health"
chk "Redis PING" "docker exec my-xhs-redis redis-cli -a 'Xhs@2026#Redis' --no-auth-warning ping | grep -q PONG"
chk "Elasticsearch 集群可达" "curl -sf -m 3 -u elastic:'Xhs@2026#Elastic' http://192.168.0.142:19200/_cluster/health"
chk "Prometheus 健康" "curl -sf -m 3 http://192.168.0.142:19090/-/healthy"
chk "RocketMQ NameServer 可达(:9876)" "timeout 2 bash -c '</dev/null >/dev/tcp/127.0.0.1/9876'"
chk "审计一致性+哈希链" "bash $(dirname "$0")/audit-gate.sh"
echo "== IT 冒烟: $OK/$TOTAL =="
[ "$OK" -eq "$TOTAL" ]
