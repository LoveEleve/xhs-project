#!/bin/bash
# my-xhs Docker Compose 部署脚本
# 用法: bash deploy.sh

set -e
DIR="/data/workspace/my-xhs"
cd "$DIR"

echo "=== 1. 编译 ==="
mvn package -DskipTests -q

echo "=== 2. 构建镜像 ==="
for m in my-xhs-gateway my-xhs-user my-xhs-content my-xhs-analytics my-xhs-counter \
         my-xhs-product my-xhs-cart my-xhs-inventory my-xhs-coupon \
         my-xhs-order my-xhs-payment my-xhs-notification my-xhs-im \
         my-xhs-home my-xhs-search; do
    [ -f "$m/Dockerfile" ] && echo "  $m ..."
done

echo "=== 3. 启动外部依赖 ==="
docker-compose up -d sentinel-dashboard

echo "=== 4. 等待依赖就绪 ==="
sleep 10

echo "=== 5. 启动顺序 ==="
echo "  1) user content analytics counter  (基础)"
echo "  2) product cart inventory coupon   (业务)"  
echo "  3) order payment                   (交易)"
echo "  4) notification im home search     (辅助)"
echo "  5) gateway                         (网关最后)"

echo "Done. 共 15 服务 + Sentinel Dashboard"
