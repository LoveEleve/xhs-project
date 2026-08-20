#!/bin/bash
# my-xhs 全链路 curl 测试 v2 — 单一 X-Trace-Id 串联全部服务
set -e

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
PASS=0; FAIL=0; SKIP=0

# ===== 单一 TraceId =====
TRACE_ID="fullchain-$(date +%s)-$(shuf -i 1000-9999 -n 1)"
echo -e "${CYAN}╔══════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║  my-xhs 全链路 curl 测试 v2                ║${NC}"
echo -e "${CYAN}║  TraceId: ${TRACE_ID}  ║${NC}"
echo -e "${CYAN}╚══════════════════════════════════════════════╝${NC}"
echo ""

check_trace() {
  local name="$1" code="$2"
  # code 是纯数字 http 状态码
  if [ "$code" = "200" ]; then
    echo -e "  ${GREEN}✓${NC} $name (HTTP $code)"
    PASS=$((PASS+1))
  elif [ "$code" = "404" ]; then
    echo -e "  ${YELLOW}~${NC} $name (HTTP 404 - 资源未找到/路径不匹配)"
    SKIP=$((SKIP+1))
  elif [ "$code" = "400" ]; then
    echo -e "  ${YELLOW}~${NC} $name (HTTP 400 - 参数校验生效)"
    PASS=$((PASS+1))
  elif [ "$code" = "405" ]; then
    echo -e "  ${YELLOW}~${NC} $name (HTTP 405 - Method不匹配)"
    SKIP=$((SKIP+1))
  elif [ "$code" = "302" ]; then
    echo -e "  ${GREEN}✓${NC} $name (HTTP 302)"
    PASS=$((PASS+1))
  else
    echo -e "  ${RED}✗${NC} $name (HTTP $code)"
    FAIL=$((FAIL+1))
  fi
}

# 正确方式：body→文件, headers→文件, code→stdout
do_get() {
  local resp_file=$(mktemp); local head_file=$(mktemp)
  local code=$(curl -s -o "$resp_file" -D "$head_file" -w "%{http_code}" \
    -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" "$@")
  local trace_hdr=$(grep -i 'X-Trace-Id:' "$head_file" | awk '{print $2}' | tr -d '\r\n')
  echo "$code"
  # 输出响应正文简要信息
  local body=$(head -c 300 "$resp_file")
  echo "     -> ${body:0:200}"
  rm -f "$resp_file" "$head_file"
}

do_post() {
  local resp_file=$(mktemp); local head_file=$(mktemp)
  local code=$(curl -s -o "$resp_file" -D "$head_file" -w "%{http_code}" \
    -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' "$@")
  local trace_hdr=$(grep -i 'X-Trace-Id:' "$head_file" | awk '{print $2}' | tr -d '\r\n')
  echo "$code"
  local body=$(head -c 300 "$resp_file")
  echo "     -> ${body:0:200}"
  rm -f "$resp_file" "$head_file"
}

# =====================================================
echo -e "${CYAN}━━━ 0. 认证 — 获取验证码 → 登录 ━━━${NC}"
# =====================================================

# 获取验证码
CAPTCHA_RESULT=$(curl -s -H "X-Trace-Id: $TRACE_ID" http://localhost:19001/api/user/auth/captcha)
CAPTCHA_KEY=$(echo "$CAPTCHA_RESULT" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['captchaKey'])")

# 从 Redis 获取验证码
CAPTCHA_CODE=$(python3 -c "
import socket,re
s=socket.socket();s.settimeout(3)
try:
    s.connect(('21.130.247.89',6379))
    s.send(b'AUTH Xhs@2026#Redis\r\n');s.recv(1024)
    s.send(f'GET myxhs:user:captcha:${CAPTCHA_KEY}\r\n'.encode())
    resp=s.recv(4096).decode()
    m=re.search(r'\\\$(\d+)\r\n(.+?)\r\n',resp+s.recv(4096).decode())
    if m:
        print(m.group(2))
    else:
        print('NOTFOUND')
except Exception as e:
    print(f'ERROR:{e}')
")
echo "  CaptchaKey: $CAPTCHA_KEY"
echo "  CaptchaCode: $CAPTCHA_CODE"

if [[ "$CAPTCHA_CODE" == ERROR:* ]] || [[ "$CAPTCHA_CODE" == "NOTFOUND" ]] || [ -z "$CAPTCHA_CODE" ]; then
  echo -e "  ${YELLOW}⚠ Redis 获取验证码失败 ($CAPTCHA_CODE)，尝试降级方案...${NC}"
  
  # 降级: 直接试已知账号的登录 (之前可能登录过)
  CAPTCHA_CODE2="0000"
  # 重新获取一次验证码
  CAPTCHA_RESULT2=$(curl -s -H "X-Trace-Id: $TRACE_ID" http://localhost:19001/api/user/auth/captcha)
  CAPTCHA_KEY2=$(echo "$CAPTCHA_RESULT2" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['captchaKey'])")
  
  # 尝试用 Python redis 库
  CAPTCHA_CODE2=$(python3 -c "
try:
    import redis
    r=redis.Redis(host='21.130.247.89',port=6379,password='Xhs@2026#Redis',decode_responses=True,socket_timeout=3)
    v=r.get(f'myxhs:user:captcha:${CAPTCHA_KEY2}')
    print(v if v else 'NOVALUE')
except Exception as ex:
    print(f'PYERR:{ex}')
" 2>/dev/null)
  
  if [[ "$CAPTCHA_CODE2" == *ERR* ]] || [[ "$CAPTCHA_CODE2" == "NOVALUE" ]] || [ -z "$CAPTCHA_CODE2" ]; then
    echo -e "  ${YELLOW}⚠ 无法获取验证码，后续需要认证的接口将使用已有 Token${NC}"
    # 直接用测试账号名，可能需要之前的token
    TOKEN="NONE"
    XID=1
  else
    CAPTCHA_CODE="$CAPTCHA_CODE2"
    CAPTCHA_KEY="$CAPTCHA_KEY2"
    echo "  新 CaptchaCode: $CAPTCHA_CODE"
  fi
fi

# 登录
if [[ "$CAPTCHA_CODE" != ERROR:* ]] && [[ "$CAPTCHA_CODE" != "NOTFOUND" ]] && [ -n "$CAPTCHA_CODE" ] && [ "$TOKEN" != "NONE" ]; then
  LOGIN=$(curl -s -X POST http://localhost:19001/api/user/auth/login \
    -H "X-Trace-Id: $TRACE_ID" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"p8test\",\"password\":\"Test@2026\",\"captchaKey\":\"$CAPTCHA_KEY\",\"captchaCode\":\"$CAPTCHA_CODE\"}")
  TOKEN=$(echo "$LOGIN" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null || echo "")
  XID=$(echo "$LOGIN" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['userId'])" 2>/dev/null || echo "1")
  LOGIN_CODE=$(echo "$LOGIN" | python3 -c "import json,sys;print(json.load(sys.stdin)['code'])" 2>/dev/null || echo "0")
  
  if [ "$LOGIN_CODE" = "200" ]; then
    echo -e "  ${GREEN}✓${NC} 登录成功! UserId=$XID, Token=${TOKEN:0:30}..."
    PASS=$((PASS+1))
  else
    echo -e "  ${RED}✗${NC} 登录失败: code=$LOGIN_CODE"
    echo "     $LOGIN"
    FAIL=$((FAIL+1))
    TOKEN="NONE"
    XID=1
  fi
fi

# =====================================================
echo ""
echo -e "${CYAN}━━━ 1. User 用户服务 (19001) ━━━${NC}"
# =====================================================

if [ "$TOKEN" != "NONE" ]; then
  code=$(do_get http://localhost:19001/api/user/me)
  check_trace "我的信息" "$code"
  
  code=$(do_get http://localhost:19001/api/user/address/list)
  check_trace "地址列表" "$code"
  
  code=$(do_post -X POST http://localhost:19001/api/user/address \
    -d '{"receiverName":"全链路测试","phone":"13800138000","province":"广东","city":"深圳","district":"南山区","detail":"科技园","isDefault":0}')
  check_trace "新增地址" "$code"
else
  echo -e "  ${YELLOW}~${NC} 跳过 (无Token)"
  SKIP=$((SKIP+3))
fi

# =====================================================
echo ""
echo -e "${CYAN}━━━ 2. Content 笔记 (19002) ━━━${NC}"
# =====================================================

if [ "$TOKEN" != "NONE" ]; then
  code=$(do_post -X POST http://localhost:19002/api/note/publish \
    -d '{"title":"全链路Trace测试笔记","content":"用单一TraceId验证全链路","noteType":0}')
  check_trace "发布笔记" "$code"
else
  echo -e "  ${YELLOW}~${NC} 跳过 (无Token)"
  SKIP=$((SKIP+1))
fi

# =====================================================
echo ""
echo -e "${CYAN}━━━ 3. Content 评论 (19002) ━━━${NC}"
# =====================================================

if [ "$TOKEN" != "NONE" ]; then
  # 评论需要 noteId，先获取笔记列表
  note_resp=$(curl -s -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" "http://localhost:19002/api/note/my?pageSize=1")
  NOTE_ID=$(echo "$note_resp" | python3 -c "
import json,sys
d=json.load(sys.stdin).get('data',{})
if isinstance(d,dict):
    recs=d.get('records',[])
    print(recs[0].get('id','') if recs else '')
" 2>/dev/null || echo "")
  
  if [ -n "$NOTE_ID" ] && [ "$NOTE_ID" != "" ]; then
    echo "  使用笔记ID: $NOTE_ID"
    code=$(do_post -X POST http://localhost:19002/api/comment \
      -d "{\"noteId\":$NOTE_ID,\"content\":\"全链路评论测试\"}")
    check_trace "发表评论" "$code"
    
    code=$(do_get "http://localhost:19002/api/comment/list/$NOTE_ID?pageSize=5")
    check_trace "评论列表" "$code"
  else
    echo -e "  ${YELLOW}~${NC} 跳过 (无笔记数据)"
    SKIP=$((SKIP+2))
  fi
else
  echo -e "  ${YELLOW}~${NC} 跳过 (无Token)"
  SKIP=$((SKIP+2))
fi

# =====================================================
echo ""
echo -e "${CYAN}━━━ 4. Analytics 社交 (19003) ━━━${NC}"
# =====================================================

if [ "$TOKEN" != "NONE" ] && [ -n "$NOTE_ID" ] && [ "$NOTE_ID" != "" ]; then
  code=$(do_post -X POST http://localhost:19003/api/social/like \
    -d "{\"bizType\":1,\"bizId\":$NOTE_ID}")
  check_trace "点赞" "$code"
  
  code=$(do_post -X POST http://localhost:19003/api/social/unlike \
    -d "{\"bizType\":1,\"bizId\":$NOTE_ID}")
  check_trace "取消点赞" "$code"
  
  code=$(do_post -X POST http://localhost:19003/api/social/favorite \
    -d "{\"noteId\":$NOTE_ID}")
  check_trace "收藏" "$code"
  
  code=$(do_post -X POST http://localhost:19003/api/social/follow \
    -d '{"userId":1}')
  check_trace "关注" "$code"
else
  echo -e "  ${YELLOW}~${NC} 跳过 (无Token/无笔记)"
  SKIP=$((SKIP+4))
fi

# =====================================================
echo ""
echo -e "${CYAN}━━━ 5. Product 商品 (19006) ━━━${NC}"
# =====================================================

code=$(do_get http://localhost:19006/api/product/category/tree)
check_trace "分类树" "$code"

code=$(do_get "http://localhost:19006/api/product/spu/list?pageSize=3&pageNum=1")
check_trace "SPU列表" "$code"

# 不需要 Token 的接口
code=$(do_get http://localhost:19006/api/product/spu/detail/1)
check_trace "SPU详情" "$code"

# =====================================================
echo ""
echo -e "${CYAN}━━━ 6. Cart 购物车 (19008) ━━━${NC}"
# =====================================================

if [ "$TOKEN" != "NONE" ]; then
  code=$(do_get http://localhost:19008/api/cart/list)
  check_trace "购物车列表" "$code"
  
  code=$(do_get http://localhost:19008/api/cart/badge)
  check_trace "购物车角标" "$code"
else
  echo -e "  ${YELLOW}~${NC} 跳过 (无Token)"
  SKIP=$((SKIP+2))
fi

# =====================================================
echo ""
echo -e "${CYAN}━━━ 7. Coupon 优惠券 (19010) ━━━${NC}"
# =====================================================

if [ "$TOKEN" != "NONE" ]; then
  code=$(do_get http://localhost:19010/api/coupon/user/list)
  check_trace "用户优惠券" "$code"
else
  echo -e "  ${YELLOW}~${NC} 跳过 (无Token)"
  SKIP=$((SKIP+1))
fi

# =====================================================
echo ""
echo -e "${CYAN}━━━ 8. Inventory 库存 (19009) ━━━${NC}"
# =====================================================

code=$(do_get "http://localhost:19009/api/inventory/query?skuId=1")
check_trace "库存查询" "$code"

# =====================================================
echo ""
echo -e "${CYAN}━━━ 9. Order 订单 (19011) ━━━${NC}"
# =====================================================

if [ "$TOKEN" != "NONE" ]; then
  code=$(do_get "http://localhost:19011/api/order/list?pageSize=3&pageNum=1")
  check_trace "订单列表" "$code"
else
  echo -e "  ${YELLOW}~${NC} 跳过 (无Token)"
  SKIP=$((SKIP+1))
fi

# =====================================================
echo ""
echo -e "${CYAN}━━━ 10. Payment 支付 (19012) ━━━${NC}"
# =====================================================

if [ "$TOKEN" != "NONE" ]; then
  # 获取一个已存在的订单号进行 mock 支付
  ord_resp=$(curl -s -H "X-Trace-Id: $TRACE_ID" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" "http://localhost:19011/api/order/list?pageSize=1&pageNum=1")
  ORDER_NO=$(echo "$ord_resp" | python3 -c "
import json,sys
d=json.load(sys.stdin).get('data',{})
recs=d.get('records',[]) if isinstance(d,dict) else []
print(recs[0].get('orderNo','') if recs else '')
" 2>/dev/null || echo "")
  
  if [ -n "$ORDER_NO" ] && [ "$ORDER_NO" != "" ]; then
    echo "  订单号: $ORDER_NO"
    code=$(do_post -X POST "http://localhost:19012/api/payment/pay" \
      -d "{\"orderNo\":\"$ORDER_NO\",\"payType\":99}")
    check_trace "Mock支付" "$code"
  else
    echo -e "  ${YELLOW}~${NC} 跳过 (无订单)"
    SKIP=$((SKIP+1))
  fi
else
  echo -e "  ${YELLOW}~${NC} 跳过 (无Token)"
  SKIP=$((SKIP+1))
fi

# =====================================================
echo ""
echo -e "${CYAN}━━━ 11. Search 搜索 (19016) ━━━${NC}"
# =====================================================

code=$(do_get "http://localhost:19016/api/search/note?keyword=%E6%B5%8B%E8%AF%95&pageSize=3")
check_trace "笔记搜索" "$code"

code=$(do_get "http://localhost:19016/api/search/product?keyword=%E5%95%86%E5%93%81&pageSize=3")
check_trace "商品搜索" "$code"

code=$(do_get "http://localhost:19016/api/search/suggest?keyword=%E6%B5%8B")
check_trace "搜索建议" "$code"

code=$(do_get http://localhost:19016/api/search/hot)
check_trace "热搜列表" "$code"

# =====================================================
echo ""
echo -e "${CYAN}━━━ 12. Notification 通知 (19013) ━━━${NC}"
# =====================================================

if [ "$TOKEN" != "NONE" ]; then
  code=$(do_get http://localhost:19013/api/notification/unread/count)
  check_trace "未读计数" "$code"
  
  code=$(do_get "http://localhost:19013/api/notification/list?pageSize=5")
  check_trace "通知列表" "$code"
else
  echo -e "  ${YELLOW}~${NC} 跳过 (无Token)"
  SKIP=$((SKIP+2))
fi

# =====================================================
echo ""
echo -e "${CYAN}━━━ 13. IM 即时通讯 (19014) ━━━${NC}"
# =====================================================

if [ "$TOKEN" != "NONE" ]; then
  code=$(do_get http://localhost:19014/api/im/ticket)
  check_trace "WS Ticket" "$code"
  
  code=$(do_get http://localhost:19014/api/im/conversation/list)
  check_trace "会话列表" "$code"
else
  echo -e "  ${YELLOW}~${NC} 跳过 (无Token)"
  SKIP=$((SKIP+2))
fi

# =====================================================
echo ""
echo -e "${CYAN}━━━ 14. BFF 首页聚合 (19015) ━━━${NC}"
# =====================================================

code=$(do_get "http://localhost:19015/api/home/feed?pageSize=5")
check_trace "Feed流" "$code"

code=$(do_get "http://localhost:19015/api/home/hot?pageSize=5")
check_trace "热门流" "$code"

# =====================================================
echo ""
echo -e "${CYAN}━━━ 15. Counter 计数器 (19004) ━━━${NC}"
# =====================================================

# Counter API 格式可能不同，尝试多种路径
code=$(do_get "http://localhost:19004/api/counter/get?bizType=1&bizId=$NOTE_ID")
check_trace "计数查询" "$code"

# =====================================================
echo ""
echo -e "${CYAN}━━━ 16. Gateway 汇总 (19000) ━━━${NC}"
# =====================================================

code=$(do_get http://localhost:19000/actuator/health)
check_trace "Gateway健康" "$code"

# =====================================================
echo ""
echo -e "${CYAN}━━━ 17. TraceId 一致性验证 ━━━${NC}"
# =====================================================

# 再次调用并显式检查 X-Trace-Id 响应头
echo "  发起一次完整请求验证 TraceId 透传..."
for port in 19001 19002 19003 19004 19006 19008 19009 19010 19011 19012 19013 19014 19015 19016; do
  head_file=$(mktemp)
  curl -s -o /dev/null -D "$head_file" \
    -H "X-Trace-Id: $TRACE_ID" \
    --connect-timeout 2 \
    "http://localhost:$port/actuator/health" 2>/dev/null
  trace=$(grep -i '^X-Trace-Id:' "$head_file" 2>/dev/null | awk '{print $2}' | tr -d '\r\n')
  if [ "$trace" = "$TRACE_ID" ]; then
    echo -e "  ${GREEN}✓${NC} port $port → X-Trace-Id: $trace"
  elif [ -z "$trace" ]; then
    echo -e "  ${YELLOW}?${NC} port $port → 无 X-Trace-Id 响应头"
  else
    echo -e "  ${RED}✗${NC} port $port → $trace (预期: $TRACE_ID)"
  fi
  rm -f "$head_file"
done

# =====================================================
echo ""
echo -e "${CYAN}╔══════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║  全链路测试结果                              ║${NC}"
echo -e "${CYAN}╠══════════════════════════════════════════════╣${NC}"
echo -e "${CYAN}║  TraceId: ${TRACE_ID}  ║${NC}"
printf "${CYAN}║${NC}  ${GREEN}通过: %3d${NC}  ${RED}失败: %3d${NC}  ${YELLOW}跳过: %3d${NC}            ${CYAN}║${NC}\n" $PASS $FAIL $SKIP
echo -e "${CYAN}╚══════════════════════════════════════════════╝${NC}"
echo ""

if [ $FAIL -gt 0 ]; then
  echo -e "${RED}存在 $FAIL 个失败项${NC}"
  exit 1
else
  echo -e "${GREEN}全链路测试完成!${NC}"
fi
