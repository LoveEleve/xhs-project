#!/bin/bash
AGENT=/data/workspace/my-xhs/skywalking-agent-9.6.0/skywalking-agent.jar
cd /data/workspace/my-xhs

# 日志统一写到 logs/ 目录（与 start-all.sh 保持一致），避免写到 /tmp/ 重启丢失
LOG_DIR=/data/workspace/my-xhs/logs
mkdir -p "$LOG_DIR"

SERVICES="19000:my-xhs-gateway 19001:my-xhs-user 19002:my-xhs-content 19003:my-xhs-analytics 19004:my-xhs-counter 19006:my-xhs-product 19008:my-xhs-cart 19009:my-xhs-inventory 19010:my-xhs-coupon 19011:my-xhs-order 19012:my-xhs-payment 19013:my-xhs-notification 19014:my-xhs-im 19015:my-xhs-home 19016:my-xhs-search"

for info in $SERVICES; do
  PORT="${info%%:*}"
  MOD="${info##*:}"
  kill -9 $(lsof -ti:$PORT) 2>/dev/null
  sleep 1
  setsid env SW_MOUNT_FOLDERS=plugins,activations,bootstrap-plugins java \
    -Dskywalking.agent.service_name=${MOD} \
    -Dskywalking.logging.dir=/tmp/sw-logs/${MOD} \
    -Dskywalking.agent.sample_n_per_3_secs=50 \
    -Dserver.tomcat.mbeanregistry.enabled=true \
    -Dmanagement.metrics.distribution.percentiles-histogram.http.server.requests=true \
    -Dmanagement.metrics.distribution.percentiles-histogram.spring.cloud.gateway.requests=true \
    -javaagent:$AGENT -Xmx256m \
    -jar ${MOD}/target/${MOD}-1.0-SNAPSHOT.jar \
    --spring.profiles.active=dev > "$LOG_DIR/${MOD}.log" 2>&1 < /dev/null &
  disown
  echo "$MOD 已启动（日志: $LOG_DIR/${MOD}.log）"
done
echo "全部重启完成（SW_MOUNT_FOLDERS=bootstrap-plugins 已启用）"
