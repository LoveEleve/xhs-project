#!/bin/bash
# 将 deploy/docker/my-xhs-deploy-zip/config/nacos/ 下的配置导入 Nacos（my-xhs 命名空间）
# 用法：bash scripts/nacos-import-configs.sh
set -e
NACOS="http://192.168.0.142:18848"; TENANT="my-xhs"; GROUP="DEFAULT_GROUP"
DIR="$(dirname "$0")/../deploy/docker/my-xhs-deploy-zip/config/nacos"
for f in "$DIR"/*.yaml; do
  dataId=$(basename "$f")
  code=$(curl -s -o /tmp/nacos-import.out -w "%{http_code}" -X POST "$NACOS/nacos/v1/cs/configs" \
    --data-urlencode "dataId=$dataId" --data-urlencode "group=$GROUP" --data-urlencode "tenant=$TENANT" \
    --data-urlencode "type=yaml" --data-urlencode "content@$f")
  echo "$dataId -> http=$code $(cat /tmp/nacos-import.out)"
done
echo "--- 校验 ---"
for f in "$DIR"/*.yaml; do
  dataId=$(basename "$f")
  code=$(curl -s -o /dev/null -w "%{http_code}" "$NACOS/nacos/v1/cs/configs?dataId=$dataId&group=$GROUP&tenant=$TENANT")
  echo "$dataId 读取 http=$code"
done
