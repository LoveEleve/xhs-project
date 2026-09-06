#!/bin/bash
set -Eeuo pipefail
# RocketMQ 中间件指标采集(textfile collector for node-exporter)
OUT=/data/rocketmq-textfile/rocketmq.prom
TMP=${OUT}.tmp
NOW=$(date +%s)
: > "$TMP"

cluster_ok=0
consumer_ok=0
topic_ok=0

cluster_output=$(docker exec my-xhs-mq-broker sh -c 'sh /home/rocketmq/rocketmq-5.1.4/bin/mqadmin clusterList -n 127.0.0.1:9876 2>/dev/null' || true)
if [ -n "$cluster_output" ]; then
  cluster_ok=1
  printf '%s\n' "$cluster_output" | awk 'NR>1 && NF>=11 {
    inTPS=$6; sub(/\(.*/,"",inTPS); outTPS=$7; sub(/\(.*/,"",outTPS);
    printf "rocketmq_broker_intps{broker=\"%s\"} %s\n", $2, inTPS;
    printf "rocketmq_broker_outtps{broker=\"%s\"} %s\n", $2, outTPS;
    printf "rocketmq_broker_uptime_hours{broker=\"%s\"} %s\n", $2, $12;
    printf "rocketmq_broker_disk_ratio{broker=\"%s\"} %s\n", $2, $13;
  }' >> "$TMP"
fi

consumer_output=$(docker exec my-xhs-mq-broker sh -c 'sh /home/rocketmq/rocketmq-5.1.4/bin/mqadmin consumerProgress -n 127.0.0.1:9876 2>/dev/null' || true)
if [ -n "$consumer_output" ]; then
  consumer_ok=1
  printf '%s\n' "$consumer_output" | awk 'NR>1 && NF>=6 {
    group=$1; tps=$(NF-1); diff=$NF;
    printf "rocketmq_consumer_tps{group=\"%s\"} %s\n", group, tps;
    printf "rocketmq_consumer_lag{group=\"%s\"} %s\n", group, diff;
  }' >> "$TMP"
fi

topic_output=$(docker exec my-xhs-mq-broker sh -c 'sh /home/rocketmq/rocketmq-5.1.4/bin/mqadmin topicList -n 127.0.0.1:9876 2>/dev/null' || true)
if [ -n "$topic_output" ]; then
  topic_ok=1
  n=$(printf '%s\n' "$topic_output" | grep -cE 'TOPIC|%DLQ|%RETRY' || true)
  dlq=$(printf '%s\n' "$topic_output" | grep -c '%DLQ%' || true)
  retry=$(printf '%s\n' "$topic_output" | grep -c '%RETRY%' || true)
  echo "rocketmq_topics_count $n" >> "$TMP"
  echo "rocketmq_dlq_topics $dlq" >> "$TMP"
  echo "rocketmq_retry_topics $retry" >> "$TMP"
fi

if [ "$cluster_ok" -eq 1 ] && [ "$consumer_ok" -eq 1 ] && [ "$topic_ok" -eq 1 ]; then
  echo "rocketmq_up 1" >> "$TMP"
else
  echo "rocketmq_up 0" >> "$TMP"
fi

echo "rocketmq_scrape_success{step=\"cluster\"} $cluster_ok" >> "$TMP"
echo "rocketmq_scrape_success{step=\"consumer\"} $consumer_ok" >> "$TMP"
echo "rocketmq_scrape_success{step=\"topic\"} $topic_ok" >> "$TMP"
echo "rocketmq_scrape_timestamp_seconds $NOW" >> "$TMP"

mv "$TMP" "$OUT"
chmod 644 "$OUT"
