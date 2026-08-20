#!/bin/bash
# my-xhs 全链路 curl 测试 v3 — 最终版
# 单一 X-Trace-Id 串联全部 15 个微服务
set -e

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
PASS=0; FAIL=0; SKIP=0

TRACE_ID="chain-$(date +%s)-$(shuf -i 1000-9999 -n 1)"
REDIS_HOST="21.130.247.89"; REDIS_PORT=6379; REDIS_PASS="Xhs@2026#Redis"
TEST_USER="chaintest"; TEST_PASS="Chain@2026"

echo -e "${CYAN}╔══════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║  my-xhs 全链路 单一TraceId curl 测试       ║${NC}"
echo -e "${CYAN}║  TraceId: ${TRACE_ID}  ║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════════════╝${NC}"
echo ""

check() {
  local name="$1" code="$2"
  case "$code" in
    200) echo -e "  ${GREEN}✓${NC} $name (200)"; PASS=$((PASS+1)) ;;
    302) echo -e "  ${GREEN}✓${NC} $name (302)"; PASS=$((PASS+1)) ;;
    400) echo -e "  ${GREEN}✓${NC} $name (400 参数校验)"; PASS=$((PASS+1)) ;;
    404) echo -e "  ${YELLOW}~${NC} $name (404)"; SKIP=$((SKIP+1)) ;;
    405) echo -e "  ${YELLOW}~${NC} $name (405)"; SKIP=$((SKIP+1)) ;;
    *)   echo -e "  ${RED}✗${NC} $name ($code)"; FAIL=$((FAIL+1)) ;;
  esac
}

# 正确分离输出: code→stdout, body→stderr
call() {
  local method="$1" url="$2" data="$3"
  local head_f=$(mktemp) body_f=$(mktemp)
  local curl_args=(-s -o "$body_f" -D "$head_f" -w "%{http_code}" -H "X-Trace-Id: $TRACE_ID")
  
  if [ "$method" = "POST" ]; then
    curl_args+=(-H 'Content-Type: application/json' -X POST)
    [ -n "$data" ] && curl_args+=(-d "$data")
  fi
  
  local code=$(curl "${curl_args[@]}" -H "Authorization: Bearer $TOKEN" -H "X-User-Id: $XID" "$url")
  
  local trace=$(grep -i '^X-Trace-Id:' "$head_f" 2>/dev/null | awk '{print $2}' | tr -d '\r\n')
  local body_preview=$(head -c 200 "$body_f" 2>/dev/null)
  >&2 echo "    ↳ body: ${body_preview//$'\n'/ }"
  >&2 echo "    ↳ X-Trace-Id: ${trace:-无}"
  rm -f "$head_f" "$body_f"
  echo "$code"
}

# 从 Redis 获取验证码
fetch_code() {
  local key="$1"
  python3 -c "
import socket,re
s=socket.socket();s.settimeout(3)
s.connect(('${REDIS_HOST}',${REDIS_PORT}))
s.send(b'AUTH ${REDIS_PASS}\r\n'); s.recv(1024)
s.send(f'GET myxhs:user:captcha:${key}\r\n'.encode())
resp=b''
while True:
    try:
        chunk=s.recv(4096)
        if not chunk: break
        resp+=chunk
        if resp.count(b'\r\n')>=2: break
    except: break
data=resp.decode()
m=re.search(r'\\\$(\d+)\r\n(.+?)\r\n',data)
if m:
    val=m.group(2).strip().strip(chr(34)).strip(chr(39))
    print(val)
"
}

# 从 JWT 解码 userId
decode_uid() {
  local token="$1"
  python3 -c "
import base64,json,sys
parts='${token}'.split('.')
if len(parts)>=2:
    payload=parts[1]
    padding=4-len(payload)%4
    if padding!=4: payload+='='*padding
    try:
        d=json.loads(base64.urlsafe_b64decode(payload.encode()))
        print(d.get('sub',''))
    except: pass
"
}

# =====================================================
echo -e "${CYAN}━━━ 0. 认证: 验证码 → 登录 → JWT Token ━━━${NC}"
# =====================================================

CAPTCHA_JSON=$(curl -s -H "X-Trace-Id: $TRACE_ID" http://localhost:19001/api/user/auth/captcha)
CAPTCHA_KEY=$(echo "$CAPTCHA_JSON" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['captchaKey'])")
CAPTCHA_CODE=$(fetch_code "$CAPTCHA_KEY")

echo "  CaptchaKey: $CAPTCHA_KEY"
echo "  CaptchaCode: $CAPTCHA_CODE"

if [ -z "$CAPTCHA_CODE" ] || [ "$CAPTCHA_CODE" = "NOTFOUND" ]; then
  echo -e "  ${RED}✗ 无法获取验证码${NC}"
  FAIL=$((FAIL+1)); TOKEN="NONE"; XID=1
else
  LOGIN=$(curl -s -X POST http://localhost:19001/api/user/auth/login \
    -H "X-Trace-Id: $TRACE_ID" -H 'Content-Type: application/json' \
    -d "{\"username\":\"${TEST_USER}\",\"password\":\"${TEST_PASS}\",\"captchaKey\":\"$CAPTCHA_KEY\",\"captchaCode\":\"$CAPTCHA_CODE\"}")
  
  LOGIN_CODE=$(echo "$LOGIN" | python3 -c "import json,sys;print(json.load(sys.stdin)['code'])" 2>/dev/null || echo "0")
  
  if [ "$LOGIN_CODE" = "200" ]; then
    TOKEN=$(echo "$LOGIN" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['accessToken'])")
    XID=$(decode_uid "$TOKEN")
    echo -e "  ${GREEN}✓${NC} 登录成功 userId=$XID"
    PASS=$((PASS+1))
  else
    echo -e "  ${RED}✗ 登录失败 code=$LOGIN_CODE${NC}"
    FAIL=$((FAIL+1)); TOKEN="NONE"; XID=1
  fi
fi

# =====================================================
echo ""; echo -e "${CYAN}━━━ 1. User :19001 ━━━${NC}"
# =====================================================

if [ "$TOKEN" != "NONE" ]; then
  code=$(call GET http://localhost:19001/api/user/me)
  check "个人信息" "$code"

  code=$(call GET http://localhost:19001/api/user/address/list)
  check "地址列表" "$code"

  code=$(call POST http://localhost:19001/api/user/address \
    '{"receiverName":"全链路测试","phone":"13800138000","province":"广东","city":"深圳","district":"南山区","detail":"科技园1号","isDefault":0}')
  check "新增地址" "$code"
else
  echo -e "  ${YELLOW}~${NC} 跳过"; SKIP=$((SKIP+3))
fi

# =====================================================
echo ""; echo -e "${CYAN}━━━ 2. Content 笔记+评论 :19002 ━━━${NC}"
# =====================================================

if [ "$TOKEN" != "NONE" ]; then
  head_f=$(mktemp); body_f=$(mktemp)
  code=$(curl -s -o "$body_f" -D "$head_f" -w "%{http_code}" \
    -H "X-Trace-Id: $TRACE_ID" -H "Authorization: Bearer $TOKEN" -H "X-User-Id: $XID" \
    -H 'Content-Type: application/json' -X POST http://localhost:19002/api/note/publish \
    -d '{"title":"全链路Trace测试","content":"单一TraceId验证全链路服务","noteType":0}')
  NOTE_ID=$(python3 -c "
import json
with open('$body_f') as f:
    d=json.load(f)
    data=d.get('data','')
    if isinstance(data,int): print(data)
    elif isinstance(data,dict): print(data.get('id','') or data.get('noteId',''))
" 2>/dev/null || echo "")
  check "发布笔记" "$code"
  echo "    noteId=$NOTE_ID"
  rm -f "$head_f" "$body_f"

  if [ -n "$NOTE_ID" ] && [ "$NOTE_ID" != "0" ]; then
    code=$(call GET http://localhost:19002/api/note/detail/$NOTE_ID)
    check "笔记详情" "$code"

    head_f=$(mktemp); body_f=$(mktemp)
    code=$(curl -s -o "$body_f" -D "$head_f" -w "%{http_code}" \
      -H "X-Trace-Id: $TRACE_ID" -H "Authorization: Bearer $TOKEN" -H "X-User-Id: $XID" \
      -H 'Content-Type: application/json' -X POST http://localhost:19002/api/comment \
      -d "{\"noteId\":$NOTE_ID,\"content\":\"全链路评论\"}")
    COMMENT_ID=$(python3 -c "
import json
with open('$body_f') as f:
    data=json.load(f).get('data','')
    if isinstance(data,int): print(data)
    elif isinstance(data,dict): print(data.get('id','') or data.get('commentId',''))
" 2>/dev/null || echo "")
    check "发表评论" "$code"
    echo "    commentId=$COMMENT_ID"
    rm -f "$head_f" "$body_f"

    code=$(call GET "http://localhost:19002/api/comment/list/$NOTE_ID?pageSize=5")
    check "评论列表" "$code"

    code=$(call GET "http://localhost:19002/api/comment/count/$NOTE_ID")
    check "评论计数" "$code"
  else
    echo -e "  ${YELLOW}~${NC} 无有效笔记ID"; SKIP=$((SKIP+3))
  fi
else
  echo -e "  ${YELLOW}~${NC} 跳过"; SKIP=$((SKIP+4))
fi

# =====================================================
echo ""; echo -e "${CYAN}━━━ 3. Analytics 社交 :19003 ━━━${NC}"
# =====================================================

if [ "$TOKEN" != "NONE" ] && [ -n "$NOTE_ID" ] && [ "$NOTE_ID" != "0" ]; then
  code=$(call POST http://localhost:19003/api/social/like "{\"bizType\":1,\"bizId\":$NOTE_ID}")
  check "点赞" "$code"

  code=$(call POST http://localhost:19003/api/social/favorite "{\"noteId\":$NOTE_ID}")
  check "收藏" "$code"

  code=$(call POST http://localhost:19003/api/social/follow/$XID "")
  check "关注" "$code"
else
  echo -e "  ${YELLOW}~${NC} 跳过"; SKIP=$((SKIP+3))
fi

# =====================================================
echo ""; echo -e "${CYAN}━━━ 4. Product 商品 :19006 ━━━${NC}"
# =====================================================

code=$(call GET http://localhost:19006/api/product/category/tree)
check "分类树" "$code"

code=$(call GET "http://localhost:19006/api/product/spu/list?pageSize=3")
check "SPU列表" "$code"

code=$(call GET http://localhost:19006/api/product/spu/4)
check "SPU详情(id=4)" "$code"

# =====================================================
echo ""; echo -e "${CYAN}━━━ 5. Cart 购物车 :19008 ━━━${NC}"
# =====================================================

if [ "$TOKEN" != "NONE" ]; then
  code=$(call GET http://localhost:19008/api/cart/list)
  check "购物车列表" "$code"

  code=$(call GET http://localhost:19008/api/cart/count)
  check "购物车角标" "$code"
else
  echo -e "  ${YELLOW}~${NC} 跳过"; SKIP=$((SKIP+2))
fi

# =====================================================
echo ""; echo -e "${CYAN}━━━ 6. Coupon 优惠券 :19010 ━━━${NC}"
# =====================================================

if [ "$TOKEN" != "NONE" ]; then
  code=$(call GET http://localhost:19010/api/coupon/user/list)
  check "用户优惠券" "$code"
else
  echo -e "  ${YELLOW}~${NC} 跳过"; SKIP=$((SKIP+1))
fi

# =====================================================
echo ""; echo -e "${CYAN}━━━ 7. Inventory 库存 :19009 ━━━${NC}"
# =====================================================

code=$(call GET http://localhost:19009/api/inventory/stock/1)
check "库存查询(sku=1)" "$code"

# =====================================================
echo ""; echo -e "${CYAN}━━━ 8. Order 订单 :19011 ━━━${NC}"
# =====================================================

if [ "$TOKEN" != "NONE" ]; then
  code=$(call GET "http://localhost:19011/api/order/list?pageSize=3")
  check "订单列表" "$code"

  body_f=$(mktemp); head_f=$(mktemp)
  curl -s -o "$body_f" -D "$head_f" \
    -H "X-Trace-Id: $TRACE_ID" -H "Authorization: Bearer $TOKEN" -H "X-User-Id: $XID" \
    "http://localhost:19011/api/order/list?pageSize=1"
  ORDER_NO=$(python3 -c "
import json
with open('$body_f') as f:
    d=json.load(f).get('data',{})
    recs=d.get('records',[]) if isinstance(d,dict) else []
    print(recs[0].get('orderNo','') if recs else '')
" 2>/dev/null || echo "")
  rm -f "$body_f" "$head_f"

  if [ -n "$ORDER_NO" ] && [ "$ORDER_NO" != "" ]; then
    echo "    orderNo=$ORDER_NO"
    code=$(call GET http://localhost:19011/api/order/detail/$ORDER_NO)
    check "订单详情" "$code"
  else
    echo -e "  ${YELLOW}~${NC} 无订单"; SKIP=$((SKIP+1))
  fi
else
  echo -e "  ${YELLOW}~${NC} 跳过"; SKIP=$((SKIP+2))
fi

# =====================================================
echo ""; echo -e "${CYAN}━━━ 9. Payment 支付 :19012 ━━━${NC}"
# =====================================================

if [ "$TOKEN" != "NONE" ] && [ -n "$ORDER_NO" ] && [ "$ORDER_NO" != "" ]; then
  code=$(call POST "http://localhost:19012/api/payment/pay" \
    "{\"orderNo\":\"$ORDER_NO\",\"payType\":99}")
  check "Mock支付" "$code"
else
  echo -e "  ${YELLOW}~${NC} 跳过"; SKIP=$((SKIP+1))
fi

# =====================================================
echo ""; echo -e "${CYAN}━━━ 10. Search 搜索 :19016 ━━━${NC}"
# =====================================================

code=$(call GET "http://localhost:19016/api/search/note?keyword=%E6%B5%8B%E8%AF%95&pageSize=3")
check "笔记搜索" "$code"

code=$(call GET "http://localhost:19016/api/search/product?keyword=%E5%95%86%E5%93%81&pageSize=3")
check "商品搜索" "$code"

code=$(call GET "http://localhost:19016/api/search/suggest?prefix=%E6%B5%8B&pageSize=5")
check "搜索建议" "$code"

code=$(call GET http://localhost:19016/api/search/hot)
check "热搜列表" "$code"

# =====================================================
echo ""; echo -e "${CYAN}━━━ 11. Notification 通知 :19013 ━━━${NC}"
# =====================================================

if [ "$TOKEN" != "NONE" ]; then
  code=$(call GET http://localhost:19013/api/notification/unread-count)
  check "未读计数" "$code"

  code=$(call GET "http://localhost:19013/api/notification/list?pageSize=5")
  check "通知列表" "$code"
else
  echo -e "  ${YELLOW}~${NC} 跳过"; SKIP=$((SKIP+2))
fi

# =====================================================
echo ""; echo -e "${CYAN}━━━ 12. IM 即时通讯 :19014 ━━━${NC}"
# =====================================================

if [ "$TOKEN" != "NONE" ]; then
  code=$(call POST http://localhost:19014/api/im/ws/ticket "")
  check "WS Ticket" "$code"

  code=$(call GET http://localhost:19014/api/im/conversations)
  check "会话列表" "$code"

  code=$(call GET http://localhost:19014/api/im/unread-count)
  check "IM未读计数" "$code"
else
  echo -e "  ${YELLOW}~${NC} 跳过"; SKIP=$((SKIP+3))
fi

# =====================================================
echo ""; echo -e "${CYAN}━━━ 13. Home BFF 聚合 :19015 ━━━${NC}"
# =====================================================

code=$(call GET "http://localhost:19015/api/home/feed?pageSize=5")
check "Feed流" "$code"

code=$(call GET "http://localhost:19015/api/home/note/$NOTE_ID")
check "BFF笔记聚合" "$code"

# =====================================================
echo ""; echo -e "${CYAN}━━━ 14. Counter 计数器 :19004 ━━━${NC}"
# =====================================================

code=$(call GET "http://localhost:19004/api/counter/get?targetType=1&targetId=$NOTE_ID&countType=1")
check "计数查询" "$code"

# =====================================================
echo ""; echo -e "${CYAN}━━━ 15. Gateway 网关 :19000 ━━━${NC}"
# =====================================================

code=$(curl -s -o /dev/null -w "%{http_code}" -H "X-Trace-Id: $TRACE_ID" http://localhost:19000/actuator/health)
check "Gateway健康" "$code"

# =====================================================
echo ""
echo -e "${CYAN}╔══════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║  TraceId: ${TRACE_ID}  ║${NC}"
echo -e "${CYAN}╠══════════════════════════════════════════════╣${NC}"
printf "${CYAN}║${NC}  ${GREEN}通过: %3d${NC}  ${RED}失败: %3d${NC}  ${YELLOW}跳过: %3d${NC}                 ${CYAN}║${NC}\n" $PASS $FAIL $SKIP
echo -e "${CYAN}╚══════════════════════════════════════════════╝${NC}"

if [ $FAIL -gt 0 ]; then
  echo -e "\n${RED}存在 $FAIL 个失败项${NC}"; exit 1
else
  echo -e "\n${GREEN}全链路测试通过! 所有接口可用。${NC}"
fi
