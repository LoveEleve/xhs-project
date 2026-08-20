#!/bin/bash
# RocketMQ 中间件指标采集(textfile collector for node-exporter)
OUT=/data/rocketmq-textfile/rocketmq.prom
TMP=${OUT}.tmp
: > "$TMP"

# 1) broker 集群状态
docker exec my-xhs-mq-broker sh -c 'sh /home/rocketmq/rocketmq-5.1.4/bin/mqadmin clusterList -n 127.0.0.1:9876 2>/dev/null' | \
awk 'NR>1 && NF>=11 {
  inTPS=$6; sub(/\(.*/,"",inTPS); outTPS=$7; sub(/\(.*/,"",outTPS);
  printf "rocketmq_broker_intps{broker=\"%s\"} %s\n", $2, inTPS;
  printf "rocketmq_broker_outtps{broker=\"%s\"} %s\n", $2, outTPS;
  printf "rocketmq_broker_uptime_hours{broker=\"%s\"} %s\n", $2, $12;
  printf "rocketmq_broker_disk_ratio{broker=\"%s\"} %s\n", $2, $13;
}' >> "$TMP"

# 2) 消费积压(最后一列=Diff, 倒数第二=TPS)
docker exec my-xhs-mq-broker sh -c 'sh /home/rocketmq/rocketmq-5.1.4/bin/mqadmin consumerProgress -n 127.0.0.1:9876 2>/dev/null' | \
awk 'NR>1 && NF>=6 {
  group=$1; tps=$(NF-1); diff=$NF;
  printf "rocketmq_consumer_tps{group=\"%s\"} %s\n", group, tps;
  printf "rocketmq_consumer_lag{group=\"%s\"} %s\n", group, diff;
}' >> "$TMP"

# 3) topic 数量 + DLQ/重试死信监控（2026-08-13：DLQ 独立指标，原仅总数）
TOPIC_LIST=$(docker exec my-xhs-mq-broker sh -c 'sh /home/rocketmq/rocketmq-5.1.4/bin/mqadmin topicList -n 127.0.0.1:9876 2>/dev/null' | tail -n +2)
n=$(echo "$TOPIC_LIST" | grep -cE "TOPIC|%DLQ|%RETRY")
dlq=$(echo "$TOPIC_LIST" | grep -c "%DLQ%")
retry=$(echo "$TOPIC_LIST" | grep -c "%RETRY%")
echo "rocketmq_topics_count $n" >> "$TMP"
echo "rocketmq_dlq_topics $dlq" >> "$TMP"
echo "rocketmq_retry_topics $retry" >> "$TMP"

# 4) namesrv 存活(每次采集成功=1)
echo "rocketmq_up 1" >> "$TMP"

mv "$TMP" "$OUT"
chmod 644 "$OUT"
