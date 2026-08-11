#!/bin/bash
# my-xhs 测试前置数据初始化脚本
# 用法: ./pre-test-init.sh
# 必须在全部15服务 UP 后执行

MYSQL_HOST="21.130.247.89"
MYSQL_PORT="3306"
MYSQL_USER="root"
MYSQL_PASS="Xhs@2026#MySQL"
REDIS_HOST="21.130.247.89"
REDIS_PORT="6379"
REDIS_PASS="Xhs@2026#Redis"

TEST_USER1="chaintest_u1"
TEST_USER2="chaintest_u2"
TEST_PASS="Test@123456"

# 获取验证码 (captchaCode 存在 Redis 中, 直接 GET 不消费)
get_captcha() {
  RESP=$(curl -s http://localhost:19000/api/user/auth/captcha)
  KEY=$(echo "$RESP" | python3 -c "import json,sys; print(json.load(sys.stdin)['data']['captchaKey'])" 2>/dev/null)
  CODE=$(python3 -c "
import redis
r=redis.Redis(host='$REDIS_HOST',port=$REDIS_PORT,password='$REDIS_PASS')
val=r.get('myxhs:user:captcha:$KEY')
print(val.decode() if val else '')" 2>/dev/null)
}

# 登录获取 Token
do_login() {
  local username=$1 password=$2
  get_captcha
  [ -z "$KEY" ] && { echo "验证码获取失败"; return 1; }
  RESP=$(curl -s -X POST http://localhost:19000/api/user/auth/login \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$username\",\"password\":\"$password\",\"captchaKey\":\"$KEY\",\"captchaCode\":\"$CODE\"}")
  TOKEN=$(echo "$RESP" | python3 -c "import json,sys; print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null)
  echo "$TOKEN"
}

echo "========================================="
echo "  my-xhs pre-test-init 预置数据初始化"
echo "  $(date)"
echo "========================================="

# ============================================================
# Step 1: 清理旧数据 (MySQL + Redis)
# ============================================================
echo ""; echo "[Step 1] 清理旧测试数据..."

echo "  1.1 MySQL — 删除旧测试用户..."
mysql -h $MYSQL_HOST -P $MYSQL_PORT -u $MYSQL_USER -p"$MYSQL_PASS" -e "
USE my_xhs_user;
SELECT CONCAT('删除前: ',COUNT(*),' 个chaintest*用户') FROM t_user WHERE username LIKE 'chaintest%';
DELETE FROM t_user WHERE username LIKE 'chaintest%';
" 2>/dev/null

echo "  1.2 MySQL — TRUNCATE 测试表 (购物车分片/订单分片/支付/Outbox/TCC)..."
mysql -h $MYSQL_HOST -P $MYSQL_PORT -u $MYSQL_USER -p"$MYSQL_PASS" -e "
USE my_xhs_cart;        TRUNCATE TABLE t_cart_item;
USE my_xhs_order_0;     TRUNCATE TABLE t_order_0; TRUNCATE TABLE t_order_1;
                         TRUNCATE TABLE t_order_2; TRUNCATE TABLE t_order_3;
                         TRUNCATE TABLE t_order_item_0; TRUNCATE TABLE t_order_item_1;
                         TRUNCATE TABLE t_order_item_2; TRUNCATE TABLE t_order_item_3;
                         TRUNCATE TABLE t_local_message_0; TRUNCATE TABLE t_local_message_1;
                         TRUNCATE TABLE t_local_message_2; TRUNCATE TABLE t_local_message_3;
USE my_xhs_payment;     TRUNCATE TABLE t_payment;
USE my_xhs_coupon;      TRUNCATE TABLE t_coupon_outbox;
USE my_xhs_inventory;   TRUNCATE TABLE t_tcc_fence; TRUNCATE TABLE t_tcc_freeze_detail;
                         TRUNCATE TABLE t_inventory_outbox;
" 2>/dev/null || true

echo "  1.3 Redis — 清空旧缓存 (保留 inventory/product 的测试数据)..." 
python3 -c "
import redis; r=redis.Redis(host='$REDIS_HOST',port=$REDIS_PORT,password='$REDIS_PASS')
# 必须清理 (会干扰测试)
must_clean = ['myxhs:user:captcha:*','myxhs:user:token:*','myxhs:user:login:*',
              'myxhs:user:block:*','myxhs:counter:*','myxhs:counter:dedup:*',
              'myxhs:like:set:*','myxhs:order:*','myxhs:payment:*','myxhs:coupon:*',
              'myxhs:feed:*','myxhs:note:*','myxhs:comment:*','myxhs:notification:*',
              'myxhs:search:*','myxhs:follow:*','myxhs:favorite:*']
deleted=0
for pat in must_clean:
    ks = r.keys(pat)
    if ks: deleted += len(r.delete(*ks))
# 必须保留 (测试依赖)
kept = ['myxhs:inventory:*', 'myxhs:product:*']
keep_count=0
for pat in kept:
    ks = r.keys(pat)
    keep_count += len(ks)
    if ks: print(f'    [保留] {pat}={len(ks)} keys')
print(f'  Redis: 已清理={deleted} keys, 保留={keep_count} keys')
" 2>/dev/null || echo "  Redis清理跳过"

# ============================================================
# Step 2: 验证基础设施
# ============================================================
echo ""; echo "[Step 2] 验证基础设施..."

echo "  2.1 MySQL..."
mysql -h $MYSQL_HOST -P $MYSQL_PORT -u $MYSQL_USER -p"$MYSQL_PASS" -e "SELECT 1" 2>/dev/null >/dev/null && echo "  MySQL OK" || { echo "  MySQL FAIL"; exit 1; }

echo "  2.2 Redis..."
python3 -c "import redis; r=redis.Redis(host='$REDIS_HOST',port=$REDIS_PORT,password='$REDIS_PASS'); r.ping(); print('  Redis OK')" || { echo "  Redis FAIL"; exit 1; }

echo "  2.3 Nacos 服务注册..."
for svc in gateway user content analytics counter product cart inventory coupon order payment notification im home search; do
  cnt=$(curl -s "http://$MYSQL_HOST:18848/nacos/v1/ns/instance/list?serviceName=my-xhs-$svc&namespaceId=my-xhs" 2>/dev/null | python3 -c "import json,sys;print(len(json.load(sys.stdin)['hosts']))" 2>/dev/null || echo 0)
  echo "  $svc: $cnt"
done

echo "  2.4 Canal..."
curl -sf http://$MYSQL_HOST:11111 >/dev/null 2>&1 && echo "  Canal OK" || echo "  Canal DOWN (ES索引同步不可用)"

echo "  2.5 ES..."
curl -sf -u elastic:Xhs@2026#Elastic http://$MYSQL_HOST:19200/_cluster/health?local=true 2>/dev/null | python3 -c "import json,sys;d=json.load(sys.stdin);print(f'  ES={d[\"status\"]}')" || echo "  ES DOWN"

# ============================================================
# Step 3: 确认 DB 预置数据完整性
# ============================================================
echo ""; echo "[Step 3] 确认 DB 预置数据..."

echo -n "  用户: "; mysql -h $MYSQL_HOST -P $MYSQL_PORT -u $MYSQL_USER -p"$MYSQL_PASS" -N -e "USE my_xhs_user; SELECT COUNT(*) FROM t_user WHERE status=1 AND deleted=0;" 2>/dev/null
echo -n "  商品: "; mysql -h $MYSQL_HOST -P $MYSQL_PORT -u $MYSQL_USER -p"$MYSQL_PASS" -N -e "USE my_xhs_product; SELECT CONCAT('SPU=',COUNT(*),' SKU=',(SELECT COUNT(*) FROM t_sku WHERE status=1 AND deleted=0)) FROM t_spu WHERE status=1 AND deleted=0;" 2>/dev/null
echo -n "  库存: "; mysql -h $MYSQL_HOST -P $MYSQL_PORT -u $MYSQL_USER -p"$MYSQL_PASS" -N -e "USE my_xhs_inventory; SELECT COUNT(*) FROM t_inventory WHERE deleted=0;" 2>/dev/null
echo -n "  券:   "; mysql -h $MYSQL_HOST -P $MYSQL_PORT -u $MYSQL_USER -p"$MYSQL_PASS" -N -e "USE my_xhs_coupon; SELECT COUNT(*) FROM t_coupon_template WHERE status=1;" 2>/dev/null
echo -n "  推送: "; mysql -h $MYSQL_HOST -P $MYSQL_PORT -u $MYSQL_USER -p"$MYSQL_PASS" -N -e "USE my_xhs_notification; SELECT COUNT(*) FROM t_push_template WHERE status=1;" 2>/dev/null

# ============================================================
# Step 4: 重启 home + notification (需要 dev profile 才能用测试端点)
# ============================================================
echo ""; echo "[Step 4] 重启 home + notification (dev profile)..."

echo "  4.1 重启 my-xhs-home (--spring.profiles.active=dev)..."
pkill -f "my-xhs-home/target/my-xhs-home" 2>/dev/null; sleep 2
nohup java \
  -javaagent:/data/workspace/my-xhs/skywalking-agent-9.6.0/skywalking-agent.jar \
  -Dskywalking.agent.service_name=my-xhs-home \
  -Dskywalking.collector.backend_service=$MYSQL_HOST:11800 \
  -Xms512m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:MaxMetaspaceSize=256m \
  -Dspring.data.redis.sentinel.enabled=false \
  --spring.profiles.active=dev \
  -jar /data/workspace/my-xhs/my-xhs-home/target/my-xhs-home-1.0-SNAPSHOT.jar \
  > /tmp/r_home.log 2>&1 &
echo "  home PID=$!"

echo "  4.2 重启 my-xhs-notification (--spring.profiles.active=dev)..."
pkill -f "my-xhs-notification/target/my-xhs-notification" 2>/dev/null; sleep 2
nohup java \
  -javaagent:/data/workspace/my-xhs/skywalking-agent-9.6.0/skywalking-agent.jar \
  -Dskywalking.agent.service_name=my-xhs-notification \
  -Dskywalking.collector.backend_service=$MYSQL_HOST:11800 \
  -Xms512m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:MaxMetaspaceSize=256m \
  -Dspring.data.redis.sentinel.enabled=false \
  --spring.profiles.active=dev \
  -jar /data/workspace/my-xhs/my-xhs-notification/target/my-xhs-notification-1.0-SNAPSHOT.jar \
  > /tmp/r_notification.log 2>&1 &
echo "  notification PID=$!"

echo "  4.3 等待 dev profile 服务就绪..."
for i in $(seq 1 20); do
  s1=$(curl -sf --connect-timeout 2 localhost:19015/actuator/health 2>/dev/null | python3 -c "import json,sys;print(json.load(sys.stdin)['status'])" 2>/dev/null || echo "DOWN")
  s2=$(curl -sf --connect-timeout 2 localhost:19013/actuator/health 2>/dev/null | python3 -c "import json,sys;print(json.load(sys.stdin)['status'])" 2>/dev/null || echo "DOWN")
  [ "$s1" = "UP" ] && [ "$s2" = "UP" ] && echo "  home=$s1 notification=$s2" && break
  sleep 3
done

# ============================================================
# Step 5: 登录 → 获得 Token (用于后续管理操作)
# ============================================================
echo ""; echo "[Step 5] 登录 testuser 获取 Token..."

TOKEN=$(do_login "testuser" "Test@123456")
if [ -z "$TOKEN" ]; then
  echo "  testuser 登录失败, 跳过后续需要Token的步骤"
  TOKEN=""
else
  echo "  Token: ${TOKEN:0:30}..."
fi

# ============================================================
# Step 6: 初始化 Redis 库存桶 (inventory I01)
# ============================================================
echo ""; echo "[Step 6] 初始化 Redis 库存桶 (inventory I01)..."

if [ -z "$TOKEN" ]; then
  echo "  [SKIP] 无 Token"
else
  SKUS=$(mysql -h $MYSQL_HOST -P $MYSQL_PORT -u $MYSQL_USER -p"$MYSQL_PASS" -N -e "USE my_xhs_product; SELECT id,stock FROM t_sku WHERE status=1 AND deleted=0 AND stock>0 LIMIT 10;" 2>/dev/null)
  while read -r sku_id sku_stock; do
    resp=$(curl -s -X POST "http://localhost:19009/api/inventory/init" \
      -H "X-Admin-Call: my-xhs-admin-token-2026" \
      -H "Content-Type: application/json" \
      -d "{\"skuId\":$sku_id,\"totalStock\":$sku_stock,\"bucketCount\":4}")
    code=$(echo "$resp" | python3 -c "import json,sys;print(json.load(sys.stdin).get('code','?'))" 2>/dev/null)
    echo "  SKU $sku_id (stock=$sku_stock) → $code"
  done <<< "$SKUS"
fi

# ============================================================
# Step 7: 验证布隆过滤器
# ============================================================
echo ""; echo "[Step 7] 验证布隆..."

python3 -c "
import redis; r=redis.Redis(host='$REDIS_HOST',port=$REDIS_PORT,password='$REDIS_PASS')
try:
    c=r.execute_command('BF.CARD','myxhs:product:bloom:spu')
    print(f'  已加载, 元素数={c}')
except Exception as e:
    print(f'  未初始化: {e} — product启动时自动加载, 如product刚重启需等待')
" 2>/dev/null || echo "  布隆检查跳过"

# ============================================================
# Step 8: 预灌 Feed 数据 (dev profile /api/home/test/)
# ============================================================
echo ""; echo "[Step 8] 预灌 Feed 数据 (dev端点 /api/home/test/)..."

if [ -z "$TOKEN" ]; then
  echo "  [SKIP] 无 Token"
else
  # push-inbox: 推送到收件箱 (userId=10001=testuser / 10002=testuser2 各30条)
  for uid in 10001 10002; do
    for nid in $(seq 10001 10030); do
      curl -s -X POST "http://localhost:19015/api/home/test/push-inbox?userId=$uid&noteId=$nid" \
        -H "Authorization: Bearer $TOKEN" > /dev/null 2>&1
    done
    cnt=$(python3 -c "import redis; r=redis.Redis(host='$REDIS_HOST',port=$REDIS_PORT,password='$REDIS_PASS'); print(r.zcard('myxhs:feed:inbox:$uid'))" 2>/dev/null)
    echo "  inbox user=$uid → $cnt 条"
  done

  # push-outbox: 写入大V发件箱 (authorId=10001 20条)
  for nid in $(seq 20001 20020); do
    curl -s -X POST "http://localhost:19015/api/home/test/push-outbox?authorId=10001&noteId=$nid" \
      -H "Authorization: Bearer $TOKEN" > /dev/null 2>&1
  done
  cnt=$(python3 -c "import redis; r=redis.Redis(host='$REDIS_HOST',port=$REDIS_PORT,password='$REDIS_PASS'); print(r.zcard('myxhs:feed:outbox:10001'))" 2>/dev/null)
  echo "  outbox author=10001 → $cnt 条"
fi

# ============================================================
# Step 9: 注册测试用户 + 获取 Token
# ============================================================
echo ""; echo "[Step 9] 注册测试用户 + 获取 Token..."

echo "  9.1 注册 $TEST_USER1..."
get_captcha
if [ -n "$KEY" ] && [ -n "$CODE" ]; then
  resp=$(curl -s -X POST http://localhost:19000/api/user/auth/register \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$TEST_USER1\",\"password\":\"$TEST_PASS\",\"phone\":\"13900000001\",\"captchaKey\":\"$KEY\",\"captchaCode\":\"$CODE\"}")
  code=$(echo "$resp" | python3 -c "import json,sys;print(json.load(sys.stdin)['code'])" 2>/dev/null)
  echo "  register $TEST_USER1: code=$code"
else
  echo "  验证码获取失败"
fi

echo "  9.2 登录 $TEST_USER1 → /tmp/test_token.txt..."
TOKEN=$(do_login "$TEST_USER1" "$TEST_PASS")
if [ -n "$TOKEN" ]; then
  echo "$TOKEN" > /tmp/test_token.txt
  echo "  Token saved: $(head -c 25 /tmp/test_token.txt)..."
else
  echo "  登录失败 — 检查用户是否已注册"
fi

echo "  9.3 注册 $TEST_USER2 (第二用户, block/关注/通知依赖)..."
get_captcha
if [ -n "$KEY" ] && [ -n "$CODE" ]; then
  resp=$(curl -s -X POST http://localhost:19000/api/user/auth/register \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$TEST_USER2\",\"password\":\"$TEST_PASS\",\"phone\":\"13900000002\",\"captchaKey\":\"$KEY\",\"captchaCode\":\"$CODE\"}")
  code=$(echo "$resp" | python3 -c "import json,sys;print(json.load(sys.stdin)['code'])" 2>/dev/null)
  echo "  register $TEST_USER2: code=$code"
else
  echo "  验证码获取失败"
fi

echo ""; echo "========================================="
echo "  pre-test-init 完成 ($(date))"
echo "========================================="
echo ""
echo "预置检查清单:"
echo "  [x] 旧数据清理 (MySQL user/order/cart/payment/outbox/TCC + Redis cache)"
echo "  [x] 基础设施验证 (MySQL/Redis/Nacos/ES/Canal)"
echo "  [x] DB 预置确认 (用户/商品/库存/券/推送模板)"
echo "  [x] home + notification 重启为 dev profile (测试端点可用)"
echo "  [x] Redis 库存桶初始化 (inventory I01)"
echo "  [x] 布隆过滤器验证"
echo "  [x] Feed 数据预制 (inbox×60 + outbox×20)"
echo "  [x] 测试用户注册 ($TEST_USER1 + $TEST_USER2)"
echo "  [x] Token → /tmp/test_token.txt"
echo ""
echo "下一步: 链1 测试开始"