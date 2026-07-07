#!/bin/bash
# Nacos 配置导入脚本
# 用法: bash scripts/nacos-config-import.sh

NACOS_URL="${NACOS_URL:-http://localhost:8848}"
NACOS_NAMESPACE="${NACOS_NAMESPACE:-my-xhs}"

import_config() {
  local data_id=$1
  local group=$2
  local content=$3
  echo "Importing ${data_id} (group=${group})..."
  curl -s -X POST "${NACOS_URL}/nacos/v1/cs/configs" \
    -d "dataId=${data_id}" \
    -d "group=${group}" \
    -d "content=${content}" \
    -d "tenant=${NACOS_NAMESPACE}" && echo " OK" || echo " FAILED"
}

echo "=== Nacos 配置导入 ==="
echo "Nacos URL: ${NACOS_URL}"
echo "Namespace: ${NACOS_NAMESPACE}"
echo ""

# Gateway 安全配置
import_config "my-xhs-gateway.yaml" "DEFAULT_GROUP" \
'gateway:
  auth:
    secret: "myxhs-gateway-secret-key-2024"
    hmac-secret: "myxhs-hmac-secret-key-2024"'

# 各服务限流 QPS 阈值
import_config "my-xhs-rate-limit.yaml" "DEFAULT_GROUP" \
'rate-limit:
  qps:
    user: 100
    product: 200
    order: 50
    cart: 100
    payment: 50
    inventory: 100
    coupon: 100
    content: 200
    counter: 500
    search: 100
    feed: 200
    analytics: 50
    notification: 100
    im: 200'

# 降级开关
import_config "my-xhs-degrade-switch.yaml" "DEFAULT_GROUP" \
'degrade:
  switch:
    payment: false
    inventory: false
    search: false
    coupon: false
    cache-evict: false'

# Redis 公共配置
import_config "my-xhs-redis.yaml" "DEFAULT_GROUP" \
'spring:
  data:
    redis:
      password: "Xhs@2026#Redis"'

echo ""
echo "=== 导入完成 ==="
