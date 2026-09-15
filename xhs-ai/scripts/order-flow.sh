#!/usr/bin/env bash
# 全链路下单-支付流量（AI 实测配套）：登录 → 地址 → N 单创建 → Mock 支付
set -u
N=${1:-5}
# 支付接口仅限内部调用；令牌从 .secrets/tokens.env 读取（缺省则跳过支付）
if [ -f /data/workspace/xhs-project/.secrets/tokens.env ]; then
  set -a; . /data/workspace/xhs-project/.secrets/tokens.env; set +a
fi
REDIS_HOST=${REDIS_HOST:-192.168.0.142}; REDIS_PASS=${REDIS_PASS:-Xhs@2026#Redis}
USER_NAME=${USER_NAME:-chaintest}; USER_PASS=${USER_PASS:-Chain@2026}
get_captcha() {
  local key
  key=$(curl -s http://localhost:19001/api/user/auth/captcha | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['captchaKey'])" 2>/dev/null)
  [ -z "$key" ] && return 1
  echo "$key $(docker exec my-xhs-redis redis-cli -a "$REDIS_PASS" --no-auth-warning GET "myxhs:user:captcha:$key" 2>/dev/null | tr -d '"')"
}
login() {
  local pair ck cc
  pair=$(get_captcha) || return 1
  ck=${pair% *}; cc=${pair#* }
  curl -s -X POST http://localhost:19001/api/user/auth/login -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USER_NAME\",\"password\":\"$USER_PASS\",\"captchaKey\":\"$ck\",\"captchaCode\":\"$cc\"}" \
    | python3 -c "import json,sys;d=json.load(sys.stdin);print((d.get('data') or {}).get('accessToken',''))" 2>/dev/null
}
TOKEN=$(login); [ -z "$TOKEN" ] && { echo "登录失败"; exit 1; }
XID=$(python3 -c "import base64,json;t='$TOKEN'.split('.')[1];t+='='*(-len(t)%4);print(json.loads(base64.urlsafe_b64decode(t)).get('sub','1'))" 2>/dev/null)
H=(-H "Authorization: Bearer $TOKEN" -H "X-User-Id: $XID" -H 'Content-Type: application/json')

ADDR=$(curl -s "${H[@]}" http://localhost:19001/api/user/address/list | python3 -c "
import json,sys
d=json.load(sys.stdin).get('data') or []
items=d if isinstance(d,list) else d.get('records',[])
print(items[0]['id'] if items else '')" 2>/dev/null)
if [ -z "$ADDR" ]; then
  ADDR=$(curl -s "${H[@]}" -X POST http://localhost:19001/api/user/address \
    -d '{"receiverName":"AI实测","receiverPhone":"13800138000","province":"广东","city":"深圳","district":"南山区","detailAddress":"科技园1号","isDefault":true}' \
    | python3 -c "import json,sys;d=json.load(sys.stdin).get('data');print(d if isinstance(d,int) else (d or {}).get('id',''))" 2>/dev/null)
fi
echo "uid=$XID addressId=$ADDR"
TS=$(date +%s); OK=0
for i in $(seq 1 "$N"); do
  SKU=$(( (i - 1) % 5 + 1 ))
  ORDER=$(curl -s "${H[@]}" -X POST http://localhost:19011/api/order/create \
    -d "{\"skuItems\":[{\"skuId\":$SKU,\"quantity\":1}],\"addressId\":$ADDR,\"bizIdentifier\":\"aidrill-$TS-$i\"}")
  read -r OID ONO AMT < <(echo "$ORDER" | python3 -c "
import json,sys
d=json.load(sys.stdin).get('data') or {}
print(d.get('orderId') or '', d.get('orderNo') or '', d.get('payAmount') or d.get('totalAmount') or '')" 2>/dev/null)
  if [ -z "$OID" ]; then echo "order $i 创建失败: $(echo "$ORDER" | head -c 160)"; continue; fi
  PAY=$(curl -s "${H[@]}" -H "X-Internal-Call: ${INTERNAL_TOKEN:-}" -X POST http://localhost:19012/api/payment/pay -d "{\"orderId\":$OID,\"amount\":$AMT,\"payType\":99}")
  PCODE=$(echo "$PAY" | python3 -c "import json,sys;print(json.load(sys.stdin).get('code'))" 2>/dev/null)
  echo "order $i sku=$SKU orderId=$OID orderNo=$ONO amount=$AMT pay=$PCODE"
  [ "$PCODE" = "200" ] && OK=$((OK+1))
  sleep 1
done
echo "=== 完成 $OK/$N 单（含支付）"
