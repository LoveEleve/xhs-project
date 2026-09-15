#!/usr/bin/env bash
# 平台真实流量生成（AI 实测配套）：登录 → 多服务读写混合 → 统计状态码
# 用法: traffic-gen.sh [rounds] [noteEvery]；依赖：Redis（验证码）、各服务端口
set -u
ROUNDS=${1:-300}; NOTE_EVERY=${2:-25}
REDIS_HOST=${REDIS_HOST:-192.168.0.142}; REDIS_PASS=${REDIS_PASS:-Xhs@2026#Redis}
USER_NAME=${USER_NAME:-chaintest}; USER_PASS=${USER_PASS:-Chain@2026}
OUT=${OUT:-/tmp/traffic-gen.log}

get_captcha() {
  local key code
  key=$(curl -s http://localhost:19001/api/user/auth/captcha | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['captchaKey'])" 2>/dev/null)
  [ -z "$key" ] && return 1
  code=$(docker exec my-xhs-redis redis-cli -a "$REDIS_PASS" --no-auth-warning GET "myxhs:user:captcha:$key" 2>/dev/null | tr -d '"')
  echo "$key $code"
}

login() {
  local pair ck cc
  pair=$(get_captcha) || return 1
  ck=${pair% *}; cc=${pair#* }
  curl -s -X POST http://localhost:19001/api/user/auth/login -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USER_NAME\",\"password\":\"$USER_PASS\",\"captchaKey\":\"$ck\",\"captchaCode\":\"$cc\"}" \
    | python3 -c "import json,sys;d=json.load(sys.stdin);print(d.get('data',{}) and d['data'].get('accessToken','') or '')" 2>/dev/null
}
TOKEN=$(login)
if [ -z "$TOKEN" ]; then
  echo "登录失败，尝试注册 $USER_NAME ..."
  pair=$(get_captcha); ck=${pair% *}; cc=${pair#* }
  curl -s -X POST http://localhost:19001/api/user/auth/register -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USER_NAME\",\"password\":\"$USER_PASS\",\"captchaKey\":\"$ck\",\"captchaCode\":\"$cc\"}" >/dev/null
  TOKEN=$(login)
fi
[ -z "$TOKEN" ] && { echo "登录/注册失败"; exit 1; }
XID=$(python3 -c "import base64,json,sys;t='$TOKEN'.split('.')[1];t+='='*(-len(t)%4);print(json.loads(base64.urlsafe_b64decode(t)).get('sub','1'))" 2>/dev/null)
echo "登录成功 user=$USER_NAME uid=$XID token=${TOKEN:0:20}..."

api() { curl -s -o /dev/null --max-time 15 -w "%{http_code}" -H "Authorization: Bearer $TOKEN" -H "X-User-Id: $XID" "$@"; }
declare -A CODES=()
count() { local c=$1; CODES[$c]=$(( ${CODES[$c]:-0} + 1 )); }
TID_PREFIX="traffic-$(date +%s)"

for i in $(seq 1 "$ROUNDS"); do
  count "$(api http://localhost:19001/api/user/me)"
  count "$(api "http://localhost:19006/api/product/spu/list?pageSize=3")"
  count "$(api "http://localhost:19006/api/product/spu/$((RANDOM % 8 + 1))")"
  count "$(api "http://localhost:19016/api/search/note?keyword=%E6%B5%8B%E8%AF%95&pageSize=3")"
  count "$(api "http://localhost:19016/api/search/hot")"
  count "$(api http://localhost:19008/api/cart/list)"
  count "$(api http://localhost:19008/api/cart/count)"
  count "$(api http://localhost:19010/api/coupon/user/list)"
  count "$(api http://localhost:19009/api/inventory/stock/1)"
  count "$(api "http://localhost:19011/api/order/list?pageSize=3")"
  count "$(api http://localhost:19015/api/notification/unread-count)"
  if [ $((i % 10)) -eq 0 ]; then
    count "$(api -X POST -H 'Content-Type: application/json' -d "{\"bizType\":1,\"bizId\":$((RANDOM % 5 + 1))}" http://localhost:19003/api/social/like)"
    count "$(api -X POST -H 'Content-Type: application/json' -d "{\"noteId\":$((RANDOM % 5 + 1))}" http://localhost:19003/api/social/favorite)"
  fi
  if [ "$NOTE_EVERY" -gt 0 ] && [ $((i % NOTE_EVERY)) -eq 0 ]; then
    count "$(api -X POST -H 'Content-Type: application/json' -d "{\"title\":\"流量构造 $TID_PREFIX-$i\",\"content\":\"AI 实测流量构造笔记\",\"noteType\":0}" http://localhost:19002/api/note/publish)"
    count "$(api -X POST -H 'Content-Type: application/json' -d "{\"noteId\":$((RANDOM % 5 + 1)),\"content\":\"流量评论 $i\"}" http://localhost:19002/api/comment)"
  fi
done

echo "=== 流量统计（rounds=$ROUNDS）==="
for k in "${!CODES[@]}"; do echo "$k: ${CODES[$k]}"; done | sort
