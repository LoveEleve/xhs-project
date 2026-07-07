#!/bin/bash
# Nacos 配置导入脚本
# 将各服务的动态配置导入到 Nacos Config

NACOS_URL="http://localhost:8848"
NACOS_NAMESPACE="public"

# 函数：导入配置
import_config() {
  local data_id=$1
  local group=$2
  local content=$3

  curl -X POST "${NACOS_URL}/nacos/v1/cs/configs" \
    -d "dataId=${data_id}" \
    -d "group=${group}" \
    -d "content=${content}" \
    -d "tenant=${NACOS_NAMESPACE}"
}

echo "=== 导入 Gateway 配置 ==="
# Gateway 安全配置
import_config "my-xhs-gateway.yaml" "DEFAULT_GROUP" '
gateway:
  auth:
    secret: "myxhs-gateway-secret-key-2024"
    hmac-secret: "myxhs-hmac-secret-key-2024"
'

echo "=== 导入限流配置 ==="
# 各服务限流 QPS 阈值
import_config "my-xhs-rate-limit.yaml" "DEFAULT_GROUP" '
rate-limit:
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
  im: 200
'

echo "=== 导入 Redis 配置 ==="
import_config "my-xhs-redis.yaml" "DEFAULT_GROUP" '
spring:
  data:
    redis:
      password: "myxhs-redis-password"
      sentinel:
        password: "myxhs-sentinel-password"
'

echo "=== 导入 Sentinel 降级配置 ==="
import_config "my-xhs-sentinel.yaml" "DEFAULT_GROUP" '
sentinel:
  degrade:
    enabled: true
    max-rt-ms: 1000
    min-request-amount: 5
    stat-interval-ms: 1000
    slow-ratio-threshold: 0.5
    time-window-sec: 10
'

echo "=== 导入降级开关配置 ==="
import_config "my-xhs-degrade-switch.yaml" "DEFAULT_GROUP" '
degrade:
  switch:
    payment: false
    inventory: false
    search: false
    coupon: false
'

echo "Done! All configs imported to Nacos."
