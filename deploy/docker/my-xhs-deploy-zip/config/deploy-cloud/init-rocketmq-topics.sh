#!/bin/bash
# ============================================================
# RocketMQ 业务 Topic 全量初始化（幂等）
# 背景：broker autoCreateTopicEnable=false，所有 topic 必须显式创建。
# 历史问题：部署时从未创建业务 topic，namesrv 上仅有 INVENTORY_TOPIC，
# 导致 order/payment/coupon/cart/notification 等所有 MQ 发送报
# "No route info of this topic" 失败（2026-09-06 发现并补齐）。
# 用法：在 broker 容器内执行（mqadmin 连接 127.0.0.1:11911），
#      或 docker exec my-xhs-mq-broker sh init-rocketmq-topics.sh
# ============================================================

NAMESRV=192.168.0.142:9876
BROKER_ADDR=127.0.0.1:11911
MQADMIN=/home/rocketmq/rocketmq-5.1.4/bin/mqadmin

TOPICS="
FEED_TOPIC
SOCIAL_TOPIC
NOTIFICATION_TOPIC
REFUND_RESULT_TOPIC
PAY_RESULT_TOPIC
ORDER_COMPENSATION_TOPIC
ORDER_TRANSACTION_TOPIC
ORDER_CLOSE_TOPIC
COUPON_CLAIM_TOPIC
COUPON_RETURN_REDIS_REPAIR_TOPIC
CART_TOPIC
CACHE_EVICT_TOPIC
RECOMMEND_BEHAVIOR_TOPIC
RETRY_TOPIC
DEFAULT_RETRY_TOPIC
PRODUCT_INDEX_TOPIC
NOTE_INDEX_TOPIC
INVENTORY_TOPIC
INVENTORY_CACHE_TOPIC
"

for t in $TOPICS; do
  $MQADMIN updateTopic -n $NAMESRV -b $BROKER_ADDR -t "$t" -p 6 2>&1 | grep -oE 'create topic.*success|failed' | sed "s/^/$t: /"
done

echo "=== 当前 topic 列表 ==="
$MQADMIN topicList -n $NAMESRV 2>&1 | grep -E '_TOPIC' | grep -vE 'RMQ_SYS|SCHEDULE|OFFSET_MOVED|SELF_TEST|BenchmarkTest|DefaultCluster' | sort -u
