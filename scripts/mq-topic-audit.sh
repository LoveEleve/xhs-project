#!/bin/bash
# MQ Topic/订阅治理巡检：消费组 LAG、孤儿 topic、代码-vs-broker 对照
# 用法：bash scripts/mq-topic-audit.sh
# 背景：2026-09-19 review——MQ 治理检查单固化为脚本（此前手工 mqadmin 查询易错列）
set -uo pipefail

NS="192.168.0.142:9876"
CLUSTER="DefaultCluster"
MQADMIN='cd /home/rocketmq/rocketmq-5.1.4/bin && ./mqadmin'
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "=== 1. 消费组 LAG（Diff 列求和，非 0 才列出） ==="
TOPIC_LIST=$(docker exec my-xhs-mq-broker sh -c "$MQADMIN topicList -n $NS -c $CLUSTER 2>/dev/null")
MQ_GROUPS=$(echo "$TOPIC_LIST" | awk 'NR>1 && $3!="" {print $3}' | grep -v "^CID_RMQ_SYS" | sort -u)
TOTAL=0
while IFS= read -r g; do
  [ -z "$g" ] && continue
  lag=$(docker exec my-xhs-mq-broker sh -c "$MQADMIN consumerProgress -n $NS -g $g 2>/dev/null" \
        | awk 'NR>1 && $1!="" && $1 !~ /%RETRY%/ {sum+=$6} END{printf "%d", sum+0}')
  TOTAL=$((TOTAL+1))
  [ "${lag:-0}" != "0" ] && echo "  ⚠️  $g lag=$lag"
done <<< "$MQ_GROUPS"
echo "  检查 $TOTAL 个消费组完成（无输出=全部 LAG=0）"

echo "=== 2. 孤儿/空 topic 候选（无消费组 且 MaxOffset=0） ==="
echo "$TOPIC_LIST" | awk 'NR>1 && $3=="" {print $2}' \
  | grep -vE "^%|^RMQ_|^rmq_|^SCHEDULE|^OFFSET|^Default|^broker" | sort -u | while read -r t; do
  row=$(docker exec my-xhs-mq-broker sh -c "$MQADMIN topicStatus -n $NS -t $t 2>/dev/null" | awk 'NR==2{print $4}')
  [ "${row:-0}" = "0" ] && echo "  🗑️  $t (0 消息, 无消费组)"
done

echo "=== 3. 代码-vs-broker 对照 ==="
CODE_TOPICS=$(grep -rhoE '"[A-Z][A-Z_0-9]*_TOPIC"' "$ROOT"/my-xhs-*/src/main/java --include="*.java" | tr -d '"' | sort -u)
BROKER_TOPICS=$(echo "$TOPIC_LIST" | awk 'NR>1{print $2}' | grep -E "_TOPIC$" | sort -u)
echo "  代码定义: $(echo "$CODE_TOPICS" | wc -l) 个 | broker 存在: $(echo "$BROKER_TOPICS" | wc -l) 个"
MISSING=$(comm -23 <(echo "$CODE_TOPICS") <(echo "$BROKER_TOPICS"))
[ -n "$MISSING" ] && echo "  ⚠️  代码有 broker 无: $MISSING"
ORPHAN=$(comm -13 <(echo "$CODE_TOPICS") <(echo "$BROKER_TOPICS") | grep -vE "^DEFAULT_RETRY_TOPIC$|^RETRY_TOPIC$|^RMQ_|^Default")
[ -n "$ORPHAN" ] && echo "  ⚠️  broker 有 代码无: $ORPHAN"

echo "=== 4. broker 磁盘 ==="
docker exec my-xhs-mq-broker sh -c 'du -sh /home/rocketmq/store 2>/dev/null'
