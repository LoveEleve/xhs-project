#!/bin/bash
# MySQL 死锁事件采集 v2(textfile collector)
# 对比 SHOW ENGINE INNODB STATUS 的 LATEST 死锁时间戳 -> 累计死锁次数
OUT=/data/rocketmq-textfile/mysql_deadlock.prom
STATE=/data/rocketmq-textfile/.mysql_deadlock_state
TMP=${OUT}.tmp
status=$(docker exec my-xhs-mysql mysql -uroot -p'Xhs@2026#MySQL' -N -e "SHOW ENGINE INNODB STATUS\G" 2>/dev/null)
last_ts=""
latest_ts=""
if echo "$status" | grep -q "LATEST DETECTED DEADLOCK"; then
  latest_ts=$(echo "$status" | grep -A2 "LATEST DETECTED DEADLOCK" | grep -oE "20[0-9]{2}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}" | head -1)
fi
[ -f "$STATE" ] && last_ts=$(cat "$STATE")
count=0
if [ -n "$latest_ts" ]; then
  epoch=$(date -d "$latest_ts" +%s 2>/dev/null || echo 0)
  if [ "$epoch" != "$last_ts" ]; then
    count=1   # 新死锁(时间戳变化)
  fi
  echo "$epoch" > "$STATE"
else
  epoch=0
fi
# 累计次数(读旧值)
old_total=0
[ -f /data/rocketmq-textfile/.mysql_deadlock_total ] && old_total=$(cat /data/rocketmq-textfile/.mysql_deadlock_total)
total=$((old_total + count))
echo "$total" > /data/rocketmq-textfile/.mysql_deadlock_total

{
  echo "mysql_innodb_deadlock_total $total"
  echo "mysql_innodb_deadlock_new_events $count"
  echo "mysql_innodb_latest_deadlock_timestamp $epoch"
} > "$TMP"
mv "$TMP" "$OUT"
chmod 644 "$OUT"
