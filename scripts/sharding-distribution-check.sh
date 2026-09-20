#!/bin/bash
# 分片分布与一致性审计（order 4×4=16 分片）
# 检查：① 各分片行数分布/倾斜 ② t_order 总数 vs t_order_no_mapping 一致性 ③ 路由抽查（user_id → 期望分片）
# 用法：bash scripts/sharding-distribution-check.sh [倾斜阈值百分比, 默认50]
# 背景：2026-09-20 review 发现 demo 数据 131 单全部集中 2 片（时间型 ID 低位偏差 + 直接取模）
set -uo pipefail

MYSQL="docker exec -i my-xhs-mysql mysql -uroot -pXhs@2026#MySQL -N"
THRESHOLD="${1:-50}"

echo "=== ① 各分片 t_order 行数 ==="
TOTAL=0; MAX=0; MIN=-1; MAX_SHARD=""; declare -A COUNTS
for i in 0 1 2 3; do
  line=""
  for j in 0 1 2 3; do
    n=$($MYSQL -e "SELECT COUNT(*) FROM my_xhs_order_$i.t_order_$j;" 2>/dev/null)
    n=${n:-0}
    COUNTS["ds$i.t$j"]=$n
    line+="t$j=$n "
    TOTAL=$((TOTAL+n))
    if [ "$n" -gt "$MAX" ]; then MAX=$n; MAX_SHARD="ds$i.t$j"; fi
    if [ "$MIN" -lt 0 ] || [ "$n" -lt "$MIN" ]; then MIN=$n; fi
  done
  echo "  ds$i: $line"
done
echo "  合计=$TOTAL 最大=$MAX($MAX_SHARD) 最小=$MIN"
if [ "$TOTAL" -gt 0 ]; then
  PCT=$((MAX*100/TOTAL))
  echo "  最大分片占比=${PCT}%（阈值 ${THRESHOLD}%）"
  SKEW=0; [ "$PCT" -gt "$THRESHOLD" ] && SKEW=1
else
  SKEW=0; PCT=0
fi

echo "=== ② 映射表一致性 ==="
MAP=$($MYSQL -e "SELECT COUNT(*) FROM my_xhs_order.t_order_no_mapping;" 2>/dev/null)
echo "  t_order 总数=$TOTAL | t_order_no_mapping=$MAP"
MISMATCH=0; [ "$TOTAL" != "$MAP" ] && MISMATCH=1

echo "=== ③ 路由抽查（算法：h=hashCode(user_id)&0x7fffffff; ds=h%4, table=(h/4)%4） ==="
ROUTE_BAD=0
UIDS=$($MYSQL -e "SELECT user_id FROM my_xhs_order_1.t_order_0 LIMIT 3;" 2>/dev/null; \
       $MYSQL -e "SELECT user_id FROM my_xhs_order_2.t_order_0 LIMIT 3;" 2>/dev/null)
for uid in $UIDS; do
  [ -z "$uid" ] && continue
  read -r exp_ds exp_t <<< "$(python3 -c "
uid=$uid
h=(uid ^ (uid >> 32)) & 0xFFFFFFFF
h = h - 0x100000000 if h >= 0x80000000 else h
h &= 0x7fffffff
print(h % 4, (h // 4) % 4)")"
  n=$($MYSQL -e "SELECT COUNT(*) FROM my_xhs_order_$exp_ds.t_order_$exp_t WHERE user_id=$uid;" 2>/dev/null)
  if [ "${n:-0}" -gt 0 ]; then echo "  ✅ user=$uid -> ds$exp_ds.t$exp_t 命中"; else echo "  ❌ user=$uid -> ds$exp_ds.t$exp_t 未命中"; ROUTE_BAD=1; fi
done

echo "=== 结论 ==="
[ "$SKEW" = "1" ] && echo "  ⚠️  分片倾斜：最大分片占比 ${PCT}% > ${THRESHOLD}%"
[ "$MISMATCH" = "1" ] && echo "  ⚠️  映射表与订单数不一致：$TOTAL vs $MAP"
[ "$ROUTE_BAD" = "1" ] && echo "  ⚠️  路由抽查失败"
[ "$SKEW$MISMATCH$ROUTE_BAD" = "000" ] && echo "  ✅ 分布/一致性/路由均通过"
exit $(( SKEW + MISMATCH + ROUTE_BAD ))
