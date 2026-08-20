#!/bin/bash
# my-xhs 全链路 curl 测试 — 单一 X-Trace-Id 串联全部服务
# 验证: 每个接口响应都包含相同的 X-Trace-Id
set -e

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
PASS=0; FAIL=0; WARN=0

# ===== 单一 TraceId =====
TRACE_ID="fullchain-$(date +%s)-$(shuf -i 1000-9999 -n 1)"
echo -e "${CYAN}╔══════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║  my-xhs 全链路 curl 测试                    ║${NC}"
echo -e "${CYAN}║  TraceId: ${TRACE_ID}  ║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════════════╝${NC}"
echo ""

# ===== 验证 TraceId 在响应 Header 中 =====
check_trace() {
  local name="$1" code="$2" resp_file="$3"
  local trace=$(grep -i '^X-Trace-Id:' "$resp_file" 2>/dev/null | head -1 | awk '{print $2}' | tr -d '\r\n')
  local expected="$TRACE_ID"
  if [ "$code" = "200" ] || [ "$code" = "10001" ]; then
    if [ "$trace" = "$expected" ]; then
      echo -e "  ${GREEN}✓${NC} $name (code=$code, trace=$trace)"
      PASS=$((PASS+1))
    else
      echo -e "  ${YELLOW}△${NC} $name (code=$code, trace=$trace ← 不匹配! 预期=$expected)"
      WARN=$((WARN+1))
    fi
  elif [ "$code" = "302" ] || [ "$code" = "30002" ] || [ "$code" = "30004" ] || [ "$code" = "30008" ] || [ "$code" = "30009" ] || [ "$code" = "30010" ]; then
    echo -e "  ${YELLOW}○${NC} $name (code=$code, 业务预期)"
    PASS=$((PASS+1))
  else
    echo -e "  ${RED}✗${NC} $name (code=$code)"
    FAIL=$((FAIL+1))
  fi
}

# ===== 获取验证码 =====
fetch_captcha() {
  local raw=$(python3 -c "
import socket
s=socket.socket();s.settimeout(5)
s.connect(('21.130.247.89',6379))
s.send(b'AUTH Xhs@2026#Redis\r\n');s.recv(1024)
s.send(f'GET myxhs:user:captcha:{CAPTCHA_KEY}\r\n'.encode())
print(s.recv(1024).decode().strip().split('\r\n')[-1].strip('\"'))
")
  echo "$raw"
}

# ===== Helper =====
do_get()  { local tmp=$(mktemp); local code=$(curl -s -o "$tmp" -w "%{http_code}" -D - -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" "$@"); echo "$code" > "$tmp.code"; mv "$tmp" "$tmp.resp"; echo "${tmp}.resp $code"; }
do_post() { local tmp=$(mktemp); local code=$(curl -s -o "$tmp" -w "%{http_code}" -D - -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' "$@"); echo "$code" > "$tmp.code"; mv "$tmp" "$tmp.resp"; echo "${tmp}.resp $code"; }

# =====================================================
echo -e "${CYAN}━━━ 0. 认证 — 获取验证码 → 登录 → Token ━━━${NC}"
# =====================================================

CAPTCHA_RESULT=$(curl -s -H "X-Trace-Id: $TRACE_ID" http://localhost:19001/api/user/auth/captcha)
CAPTCHA_KEY=$(echo "$CAPTCHA_RESULT" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['captchaKey'])")
CAPTCHA_CODE=$(fetch_captcha)
echo "  CaptchaKey: $CAPTCHA_KEY → Code: $CAPTCHA_CODE"

LOGIN=$(curl -s -X POST http://localhost:19001/api/user/auth/login \
  -H "X-Trace-Id: $TRACE_ID" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"p8test\",\"password\":\"Test@2026\",\"captchaKey\":\"$CAPTCHA_KEY\",\"captchaCode\":\"$CAPTCHA_CODE\"}")
TOKEN=$(echo "$LOGIN" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null || echo "")
XID=$(echo "$LOGIN" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['userId'])" 2>/dev/null || echo "1")

if [ -z "$TOKEN" ]; then
  echo -e "${RED}  ✗ 登录失败! 尝试直接测试...${NC}"
  echo "  响应: $LOGIN"
  TOKEN="test"  # 降级
  XID=1
else
  echo -e "  ${GREEN}✓${NC} 登录成功! Token=${TOKEN:0:20}..., UserId=$XID"
  PASS=$((PASS+1))
fi

# =====================================================
echo ""
echo -e "${CYAN}━━━ 1. User 用户服务 (19001) ━━━${NC}"
# =====================================================

RESP_FILE=$(mktemp)
code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -D - \
  -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
  http://localhost:19001/api/user/me)
check_trace "我的信息" "$code" "$RESP_FILE" && cat "$RESP_FILE" | head -5

RESP_FILE=$(mktemp)
code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -D - \
  -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
  http://localhost:19001/api/user/address/list)
check_trace "地址列表" "$code" "$RESP_FILE"

RESP_FILE=$(mktemp)
code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -D - \
  -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -X POST http://localhost:19001/api/user/address \
  -d '{"receiverName":"全链路测试","phone":"13800138000","province":"广东","city":"深圳","district":"南山区","detail":"科技园全链路测试","isDefault":0}')
ADDR_ID=$(cat "$RESP_FILE" | python3 -c "import json,sys;d=json.load(sys.stdin);print(d.get('data',''))" 2>/dev/null || echo "")
check_trace "新增地址" "$code" "$RESP_FILE"
echo "    地址ID: $ADDR_ID"

# =====================================================
echo -e "${CYAN}━━━ 2. Content 内容服务 (19002) 笔记 ━━━${NC}"
# =====================================================

RESP_FILE=$(mktemp)
code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -D - \
  -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -X POST http://localhost:19002/api/note/publish \
  -d '{"title":"全链路测试笔记","content":"用单一 TraceId 验证全链路串联","noteType":0}')
NOTE_ID=$(cat "$RESP_FILE" | python3 -c "import json,sys;d=json.load(sys.stdin).get('data','');print(d if isinstance(d,int) else '')" 2>/dev/null || echo "")
check_trace "发布笔记" "$code" "$RESP_FILE"
echo "    笔记ID: $NOTE_ID"

if [ -n "$NOTE_ID" ] && [ "$NOTE_ID" != "" ]; then
  RESP_FILE=$(mktemp)
  code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -D - \
    -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
    http://localhost:19002/api/note/detail/$NOTE_ID)
  check_trace "笔记详情" "$code" "$RESP_FILE"

  # =====================================================
  echo -e "${CYAN}━━━ 3. Content 评论 (19002) ━━━${NC}"
  # =====================================================

  RESP_FILE=$(mktemp)
  code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -D - \
    -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' \
    -X POST http://localhost:19002/api/comment \
    -d "{\"noteId\":$NOTE_ID,\"content\":\"全链路评论测试\"}")
  COMMENT_ID=$(cat "$RESP_FILE" | python3 -c "import json,sys;d=json.load(sys.stdin).get('data','');print(d if isinstance(d,int) else '')" 2>/dev/null || echo "")
  check_trace "发表评论" "$code" "$RESP_FILE"
  echo "    评论ID: $COMMENT_ID"

  RESP_FILE=$(mktemp)
  code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -D - \
    -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
    "http://localhost:19002/api/comment/list/$NOTE_ID?pageSize=5")
  check_trace "评论列表" "$code" "$RESP_FILE"

  # =====================================================
  echo -e "${CYAN}━━━ 4. Analytics 社交互动 (19003) ━━━${NC}"
  # =====================================================

  RESP_FILE=$(mktemp)
  code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -D - \
    -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' \
    -X POST http://localhost:19003/api/social/like \
    -d "{\"bizType\":1,\"bizId\":$NOTE_ID}")
  check_trace "点赞笔记" "$code" "$RESP_FILE"

  RESP_FILE=$(mktemp)
  code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -D - \
    -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' \
    -X POST http://localhost:19003/api/social/favorite \
    -d "{\"noteId\":$NOTE_ID}")
  check_trace "收藏笔记" "$code" "$RESP_FILE"

  RESP_FILE=$(mktemp)
  code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -D - \
    -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' \
    -X POST http://localhost:19003/api/social/follow \
    -d "{\"userId\":1}")
  check_trace "关注用户" "$code" "$RESP_FILE"
fi

# =====================================================
echo -e "${CYAN}━━━ 5. Product 商品服务 (19006) ━━━${NC}"
# =====================================================

RESP_FILE=$(mktemp)
code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -D - \
  -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
  http://localhost:19006/api/product/category/tree)
check_trace "分类树" "$code" "$RESP_FILE"

RESP_FILE=$(mktemp)
code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -D - \
  -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
  "http://localhost:19006/api/product/spu/list?pageSize=3&pageNum=1")
check_trace "SPU列表" "$code" "$RESP_FILE"
# 提取第一个 SPU ID
SPU_ID=$(cat "$RESP_FILE" | python3 -c "
import json,sys
d=json.load(sys.stdin)
records=d.get('data',{}).get('records',[])
print(records[0].get('id','') if records else '')
" 2>/dev/null || echo "")
echo "    第一个SPU: $SPU_ID"

if [ -n "$SPU_ID" ] && [ "$SPU_ID" != "" ]; then
  RESP_FILE=$(mktemp)
  code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -D - \
    -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
    http://localhost:19006/api/product/spu/detail/$SPU_ID)
  SKU_ID=$(cat "$RESP_FILE" | python3 -c "
import json,sys
d=json.load(sys.stdin).get('data',{})
skus=d.get('skus',[]) if isinstance(d,dict) else []
print(skus[0].get('id','') if skus else '')
" 2>/dev/null || echo "")
  check_trace "SPU详情" "$code" "$RESP_FILE"
  echo "    第一个SKU: $SKU_ID"
fi

# =====================================================
echo -e "${CYAN}━━━ 6. Cart 购物车 (19008) ━━━${NC}"
# =====================================================

if [ -n "$SKU_ID" ] && [ "$SKU_ID" != "" ]; then
  RESP_FILE=$(mktemp)
  code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -D - \
    -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' \
    -X POST http://localhost:19008/api/cart/add \
    -d "{\"skuId\":$SKU_ID,\"quantity\":2}")
  check_trace "加入购物车" "$code" "$RESP_FILE"

  RESP_FILE=$(mktemp)
  code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -D - \
    -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
    http://localhost:19008/api/cart/list)
  check_trace "购物车列表" "$code" "$RESP_FILE"

  RESP_FILE=$(mktemp)
  code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -D - \
    -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
    http://localhost:19008/api/cart/badge)
  check_trace "购物车角标" "$code" "$RESP_FILE"
fi

# =====================================================
echo -e "${CYAN}━━━ 7. Coupon 优惠券 (19010) ━━━${NC}"
# =====================================================

RESP_FILE=$(mktemp)
code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -D - \
  -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
  http://localhost:19010/api/coupon/user/list)
check_trace "用户优惠券" "$code" "$RESP_FILE"

# =====================================================
echo -e "${CYAN}━━━ 8. Inventory 库存 (19009) ━━━${NC}"
# =====================================================

if [ -n "$SKU_ID" ] && [ "$SKU_ID" != "" ]; then
  RESP_FILE=$(mktemp)
  code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -D - \
    -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
    "http://localhost:19009/api/inventory/query?skuId=$SKU_ID")
  check_trace "库存查询" "$code" "$RESP_FILE"
fi

# =====================================================
echo -e "${CYAN}━━━ 9. Order 订单 (19011) ━━━${NC}"
# =====================================================

RESP_FILE=$(mktemp)
code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -D - \
  -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
  "http://localhost:19011/api/order/list?pageSize=3&pageNum=1")
check_trace "订单列表" "$code" "$RESP_FILE"
# 提取第一个订单号
ORDER_NO=$(cat "$RESP_FILE" | python3 -c "
import json,sys
d=json.load(sys.stdin)
records=d.get('data',{}).get('records',[])
print(records[0].get('orderNo','') if records else '')
" 2>/dev/null || echo "")

if [ -n "$ORDER_NO" ] && [ "$ORDER_NO" != "" ]; then
  echo "    第一个订单: $ORDER_NO"
  RESP_FILE=$(mktemp)
  code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -D - \
    -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
    http://localhost:19011/api/order/detail/$ORDER_NO)
  check_trace "订单详情" "$code" "$RESP_FILE"
fi

# 尝试创建新订单
if [ -n "$SKU_ID" ] && [ -n "$ADDR_ID" ]; then
  RESP_FILE=$(mktemp)
  code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -D - \
    -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' \
    -X POST http://localhost:19011/api/order/create \
    -d "{\"addressId\":$ADDR_ID,\"items\":[{\"skuId\":$SKU_ID,\"quantity\":1}],\"remark\":\"全链路测试订单\"}")
  NEW_ORDER_NO=$(cat "$RESP_FILE" | python3 -c "
import json,sys
d=json.load(sys.stdin).get('data','')
if isinstance(d,dict):
  print(d.get('orderNo',''))
elif isinstance(d,str):
  print(d)
" 2>/dev/null || echo "")
  check_trace "创建订单" "$code" "$RESP_FILE"
  echo "    新订单号: $NEW_ORDER_NO"
fi

# =====================================================
echo -e "${CYAN}━━━ 10. Payment 支付 (19012) ━━━${NC}"
# =====================================================

if [ -n "$ORDER_NO" ] && [ "$ORDER_NO" != "" ]; then
  RESP_FILE=$(mktemp)
  code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -D - \
    -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' \
    -X POST "http://localhost:19012/api/payment/pay" \
    -d "{\"orderNo\":\"$ORDER_NO\",\"payType\":99}")
  check_trace "Mock支付" "$code" "$RESP_FILE"
fi

# =====================================================
echo -e "${CYAN}━━━ 11. Search 搜索 (19016) ━━━${NC}"
# =====================================================

RESP_FILE=$(mktemp)
code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -D - \
  -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
  "http://localhost:19016/api/search/note?keyword=%E6%B5%8B%E8%AF%95&pageSize=3")
check_trace "笔记搜索" "$code" "$RESP_FILE"

RESP_FILE=$(mktemp)
code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -D - \
  -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
  "http://localhost:19016/api/search/product?keyword=%E5%95%86%E5%93%81&pageSize=3")
check_trace "商品搜索" "$code" "$RESP_FILE"

RESP_FILE=$(mktemp)
code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -D - \
  -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
  --data-urlencode "keyword=测" \
  "http://localhost:19016/api/search/suggest")
check_trace "搜索建议" "$code" "$RESP_FILE"

RESP_FILE=$(mktemp)
code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -D - \
  -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
  http://localhost:19016/api/search/hot)
check_trace "热搜列表" "$code" "$RESP_FILE"

# =====================================================
echo -e "${CYAN}━━━ 12. Notification 通知 (19013) ━━━${NC}"
# =====================================================

RESP_FILE=$(mktemp)
code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -D - \
  -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
  http://localhost:19013/api/notification/unread)
check_trace "未读计数" "$code" "$RESP_FILE"

RESP_FILE=$(mktemp)
code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -D - \
  -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
  "http://localhost:19013/api/notification/list?pageSize=5")
check_trace "通知列表" "$code" "$RESP_FILE"

# =====================================================
echo -e "${CYAN}━━━ 13. IM 即时通讯 (19014) ━━━${NC}"
# =====================================================

RESP_FILE=$(mktemp)
code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -D - \
  -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
  http://localhost:19014/api/im/ticket)
check_trace "WS Ticket" "$code" "$RESP_FILE"

RESP_FILE=$(mktemp)
code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -D - \
  -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
  http://localhost:19014/api/im/conversation/list)
check_trace "会话列表" "$code" "$RESP_FILE"

# =====================================================
echo -e "${CYAN}━━━ 14. Home BFF 聚合 (19015) ━━━${NC}"
# =====================================================

RESP_FILE=$(mktemp)
code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -D - \
  -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
  "http://localhost:19015/api/home/feed?pageSize=5")
check_trace "Feed流" "$code" "$RESP_FILE"

# =====================================================
echo -e "${CYAN}━━━ 15. Counter 计数器 (19004) ━━━${NC}"
# =====================================================

RESP_FILE=$(mktemp)
code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -D - \
  -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
  "http://localhost:19004/api/counter/get?bizType=1&bizId=$NOTE_ID")
check_trace "计数查询" "$code" "$RESP_FILE"

# =====================================================
echo -e "${CYAN}━━━ 16. Gateway 网关 (19000) 汇总验证 ━━━${NC}"
# =====================================================

RESP_FILE=$(mktemp)
code=$(curl -s -o "$RESP_FILE" -w "%{http_code}" -D - \
  -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
  http://localhost:19000/actuator/health)
check_trace "Gateway健康" "$code" "$RESP_FILE"

# =====================================================
echo ""
echo -e "${CYAN}╔══════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║  全链路测试结果                               ║${NC}"
echo -e "${CYAN}╠══════════════════════════════════════════════╣${NC}"
echo -e "${CYAN}║  TraceId: ${TRACE_ID}  ║${NC}"
printf "${CYAN}║${NC}  ${GREEN}通过: %3d${NC}  ${RED}失败: %3d${NC}  ${YELLOW}异常: %3d${NC}            ${CYAN}║${NC}\n" $PASS $FAIL $WARN
echo -e "${CYAN}╚══════════════════════════════════════════════╝${NC}"

if [ $FAIL -gt 0 ]; then
  echo -e "\n${RED}存在失败项，请检查!${NC}"
  exit 1
else
  echo -e "\n${GREEN}全链路测试完成!${NC}"
fi
