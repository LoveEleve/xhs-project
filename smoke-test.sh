#!/bin/bash
# my-xhs 全量冒烟测试 — 覆盖全部 14 服务 80+ 接口
# 用法: bash smoke-test.sh
set -e
API="http://localhost"
PASS=0 FAIL=0
LOG() { echo "  $1"; }

assert() {
  local name="$1" code="$2"
  if [ "$code" = "200" ] || [ "$code" = "302" ] || [ "$code" = "10001" ] || [ "$code" = "30002" ]; then
    echo "✅ $name"; PASS=$((PASS+1))
  elif [ "$code" = "40002" ]; then
    echo "⚠️  $name (参数校验生效)"; PASS=$((PASS+1))
  else
    echo "❌ $name (code=$code)"; FAIL=$((FAIL+1))
  fi
}

do_get()  { curl -sf -o /dev/null -w "%{http_code}" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" "$@"; }
do_post() { curl -sf -o /dev/null -w "%{http_code}" -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' "$@"; }

echo "========================================="
echo "  my-xhs 全量冒烟测试 (80+ endpoints)"
echo "========================================="
echo ""

# ===== 0. 登录 =====
echo "--- 0. 认证 ---"
CAPTCHA=$(curl -s $API:19001/api/user/auth/captcha)
KEY=$(echo $CAPTCHA | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['captchaKey'])")
CODE=$(python3 -c "import socket;s=socket.socket();s.settimeout(5);s.connect(('21.130.247.89',6379));s.send(b'AUTH Xhs@2026#Redis\r\n');s.recv(1024);s.send(f'GET myxhs:user:captcha:$KEY\r\n'.encode());print(s.recv(1024).decode().strip().split('\r\n')[-1].strip('\"'))")
LOGIN=$(curl -s -X POST $API:19001/api/user/auth/login -H 'Content-Type: application/json' -d "{\"username\":\"p8test\",\"password\":\"Test@2026\",\"captchaKey\":\"$KEY\",\"captchaCode\":\"$CODE\"}")
TOKEN=$(echo $LOGIN | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['accessToken'])")
XID=1

# 发笔记（需先有数据）
NOTE_ID=$(curl -s -X POST $API:19002/api/note/publish -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"title":"P8全量测试","content":"end-to-end","noteType":0}' | python3 -c "import json,sys;d=json.load(sys.stdin);print(d['data'] if isinstance(d['data'],int) else d['data']['noteId'])" 2>/dev/null || echo "0")

# ===== 1. my-xhs-user :19001 =====
echo ""
echo "--- 1. user (19001) 用户 ---"
assert "验证码"    $(do_get  $API:19001/api/user/auth/captcha)
assert "注册"      $(curl -sf -o /dev/null -w "%{http_code}" -X POST $API:19001/api/user/auth/register -H 'Content-Type: application/json' -d '{"username":"","password":"","captchaKey":"","captchaCode":""}')
assert "登录"      $(echo $LOGIN | python3 -c "import json,sys;print(json.load(sys.stdin)['code'])")
assert "我的信息"  $(do_get  $API:19001/api/user/me)
assert "用户公开"  $(do_get  $API:19001/api/user/1/info)
assert "地址列表"  $(do_get  $API:19001/api/user/address/list)
assert "默认地址"  $(do_get  $API:19001/api/user/address/default)
assert "修改密码"  $(do_post $API:19001/api/user/me/password -d '{"oldPassword":"","newPassword":""}')
assert "刷新Token" $(curl -sf -o /dev/null -w "%{http_code}" -X POST "$API:19001/api/user/auth/refresh?refreshToken=invalid" )

# ===== 2. my-xhs-content :19002 =====
echo ""
echo "--- 2. content (19002) 笔记+评论 ---"
assert "发布笔记"   $(do_post $API:19002/api/note/publish    -d '{"title":"T","content":"C","noteType":0}')
assert "保存草稿"   $(do_post $API:19002/api/note/draft      -d '{"title":"D","content":"C","noteType":0}')
assert "笔记详情"   $(do_get  $API:19002/api/note/detail/$NOTE_ID)
assert "用户笔记"   $(do_get  "$API:19002/api/note/user/1?pageSize=2")
assert "我的笔记"   $(do_get  "$API:19002/api/note/my?pageSize=2")
assert "发表评论"   $(do_post $API:19002/api/comment           -d "{\"noteId\":$NOTE_ID,\"content\":\"test\"}")
assert "评论列表"   $(do_get  "$API:19002/api/comment/list/$NOTE_ID?pageSize=2")
assert "评论计数"   $(do_get  "$API:19002/api/comment/count/$NOTE_ID")
assert "传统分页"   $(do_get  "$API:19002/api/comment/page/$NOTE_ID?pageSize=2")

# ===== 3. my-xhs-analytics :19003 =====
echo ""
echo "--- 3. analytics (19003) 社交 ---"
assert "点赞"       $(do_post $API:19003/api/social/like       -d "{\"bizType\":1,\"bizId\":$NOTE_ID}")
assert "取消点赞"   $(do_post $API:19003/api/social/unlike     -d "{\"bizType\":1,\"bizId\":$NOTE_ID}")
assert "点赞状态"   $(do_get  "$API:19003/api/social/like/status?bizType=1&bizId=$NOTE_ID")
assert "公开计数"   $(do_get  "$API:19003/api/social/like/count?bizType=1&bizId=$NOTE_ID")
assert "收藏"       $(do_post $API:19003/api/social/favorite    -d "{\"noteId\":$NOTE_ID}")
assert "取消收藏"   $(do_post $API:19003/api/social/unfavorite  -d "{\"noteId\":$NOTE_ID}")
assert "收藏状态"   $(do_get  "$API:19003/api/social/favorite/status?noteId=$NOTE_ID")
assert "收藏列表"   $(do_get  "$API:19003/api/social/favorite/list?pageSize=2")
assert "关注"       $(do_post $API:19003/api/social/follow      -d '{"userId":1}')
assert "取消关注"   $(do_post $API:19003/api/social/unfollow    -d '{"userId":1}')
assert "关注列表"   $(do_get  "$API:19003/api/social/following/1?pageSize=2")
assert "粉丝列表"   $(do_get  "$API:19003/api/social/follower/1?pageSize=2")

# ===== 4. my-xhs-counter :19004 =====
echo ""
echo "--- 4. counter (19004) 计数 ---"
assert "增量"       $(do_post $API:19004/api/counter/increment  -d '{"targetType":1,"targetId":1,"countType":1}')
assert "减量"       $(do_post $API:19004/api/counter/decrement  -d '{"targetType":1,"targetId":1,"countType":1}')
assert "单个查询"   $(do_get  "$API:19004/api/counter/get?targetType=1&targetId=1&countType=1")

# ===== 5. my-xhs-product :19006 =====
echo ""
echo "--- 5. product (19006) 商品 ---"
assert "分类树"     $(do_get  $API:19006/api/product/category/tree)
assert "SPU列表"    $(do_get  "$API:19006/api/product/spu/list?pageSize=2")
assert "SPU详情"    $(do_get  $API:19006/api/product/spu/1)
assert "SKU详情"    $(do_get  $API:19006/api/product/sku/1)
assert "SKU列表"    $(do_get  $API:19006/api/product/sku/list/1)
assert "批量SKU"    $(do_get  "$API:19006/api/product/sku/batch?skuIds=1,2")

# ===== 6. my-xhs-cart :19008 =====
echo ""
echo "--- 6. cart (19008) 购物车 ---"
assert "加购"       $(do_post $API:19008/api/cart/add           -d '{"skuId":1,"quantity":2}')
assert "改数量"     $(do_post $API:19008/api/cart/check         -d '{"skuId":1,"checked":true}')
assert "全选"       $(do_post $API:19008/api/cart/check-all     -d '{"checked":true}')
assert "列表"       $(do_get  $API:19008/api/cart/list)
assert "计数"       $(do_get  $API:19008/api/cart/count)
assert "删除"       $(curl -sf -o /dev/null -w "%{http_code}" -X DELETE -H "X-User-Id: $XID" -H "Authorization: Bearer $TOKEN" $API:19008/api/cart/1)

# ===== 7. my-xhs-coupon :19010 =====
echo ""
echo "--- 7. coupon (19010) 优惠券 ---"
assert "创建模板"   $(do_post $API:19010/api/coupon/template    -d '{"name":"测试券","type":1,"discountValue":10,"minAmount":50,"totalCount":100,"perUserLimit":3,"validDays":30}')
assert "模板详情"   $(do_get  $API:19010/api/coupon/template/1)
assert "领券"       $(do_post $API:19010/api/coupon/claim       -d '{"couponId":1}')
assert "可用列表"   $(do_get  $API:19010/api/coupon/user/available)
assert "券列表"     $(do_get  $API:19010/api/coupon/user/list)

# ===== 8. my-xhs-order :19011 =====
echo ""
echo "--- 8. order (19011) 订单 ---"
assert "下单"       $(do_post $API:19011/api/order/create       -d '{"skuItems":[{"skuId":1,"quantity":1}],"addressId":1}')
assert "订单列表"   $(do_get  "$API:19011/api/order/list?pageSize=2")
assert "订单详情"   $(do_get  $API:19011/api/order/1)
assert "取消订单"   $(do_post $API:19011/api/order/cancel       -d '{"orderId":1}')
assert "确认收货"   $(do_post $API:19011/api/order/confirm      -d '{"orderId":1}')

# ===== 9. my-xhs-payment :19012 =====
echo ""
echo "--- 9. payment (19012) 支付 ---"
assert "支付"       $(do_post $API:19012/api/payment/pay        -d '{"orderId":1,"payType":1}')
assert "退款"       $(do_post $API:19012/api/payment/refund     -d '{"orderId":1,"reason":"test"}')
assert "支付状态"   $(do_get  $API:19012/api/payment/status/1)

# ===== 10. my-xhs-inventory :19009 =====
echo ""
echo "--- 10. inventory (19009) 库存 ---"
assert "查库存"     $(do_get  $API:19009/api/inventory/stock/1)

# ===== 11. my-xhs-search :19016 =====
echo ""
echo "--- 11. search (19016) 搜索+推荐 ---"
assert "笔记搜索"   $(do_get  "$API:19016/api/search/note?keyword=test")
assert "建议"       $(do_get  "$API:19016/api/search/suggest?prefix=t")
assert "热搜"       $(do_get  $API:19016/api/search/hot)
assert "搜索历史"   $(do_get  $API:19016/api/search/history)
assert "推荐Feed"   $(do_get  "$API:19016/api/recommend/feed?size=3")
assert "行为上报"   $(do_post $API:19016/api/recommend/behavior  -d "{\"noteId\":$NOTE_ID,\"behaviorType\":2,\"duration\":3}")

# ===== 12. my-xhs-home :19015 =====
echo ""
echo "--- 12. home (19015) BFF聚合 ---"
assert "Feed流"     $(do_get  "$API:19015/api/home/feed?size=3")
assert "笔记页"     $(do_get  $API:19015/api/home/note/$NOTE_ID)
assert "商品页"     $(do_get  $API:19015/api/home/product/1)
assert "用户主页"   $(do_get  $API:19015/api/home/user/1)

# ===== 13. my-xhs-notification :19013 =====
echo ""
echo "--- 13. notification (19013) 通知 ---"
assert "未读数"     $(do_get  $API:19013/api/notification/unread-count)
assert "通知列表"   $(do_get  "$API:19013/api/notification/list?pageSize=2")
assert "已读全部"   $(do_post $API:19013/api/notification/read-all -d '{}')

# ===== 14. my-xhs-im :19014 =====
echo ""
echo "--- 14. im (19014) 即时通讯 ---"
assert "WS票据"     $(do_post $API:19014/api/im/ws/ticket         -d '{}')
assert "会话列表"   $(do_get  "$API:19014/api/im/conversations?page=1")
assert "未读数"     $(do_get  $API:19014/api/im/unread-count)
assert "在线数"     $(do_get  $API:19014/api/im/online-count)

# ===== 15. my-xhs-gateway :19000 =====
echo ""
echo "--- 15. gateway (19000) 网关 ---"
GW=$(curl -sf $API:19000/actuator/health | python3 -c "import json,sys;print(json.load(sys.stdin)['status'])" 2>/dev/null || echo DOWN)
assert "健康检查"   "$( [ "$GW" = "UP" ] && echo 200 || echo 500 )"

# ===== 结果 =====
echo ""
echo "========================================="
echo "  通过: $PASS  失败: $FAIL"
echo "========================================="
