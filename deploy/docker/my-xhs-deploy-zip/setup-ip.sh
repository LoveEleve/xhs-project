#!/bin/bash
# ========================================================================
# MyXHS IP 配置一键替换脚本
# 用途：将部署包中所有 ${HOST_IP} 和 ${MICROSERVICE_IP} 占位符替换为实际 IP
# 用法：bash setup-ip.sh <中间件机IP> <微服务机IP>
#        或: bash setup-ip.sh <IP>  # 如果中间件和微服务在同一台机器
# ========================================================================

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 打印带颜色的信息
info() { echo -e "${GREEN}[INFO]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }

# 检查参数
if [ $# -lt 1 ] || [ $# -gt 2 ]; then
    echo "用法: $0 <中间件机IP> [微服务机IP]"
    echo "示例: $0 10.0.0.1  # 中间件和微服务在同一台机器"
    echo "示例: $0 10.0.0.1 10.0.0.2  # 中间件和微服务在不同机器"
    exit 1
fi

HOST_IP="$1"
MICROSERVICE_IP="${2:-$1}"  # 如果未指定微服务机IP，则使用中间件机IP

info "=========================================="
info "MyXHS IP 配置替换工具"
info "=========================================="
info "中间件机 IP: ${HOST_IP}"
info "微服务机 IP: ${MICROSERVICE_IP}"
echo ""

# 检查是否在正确的目录
if [ ! -f "docker-compose.yml" ]; then
    error "未找到 docker-compose.yml，请在部署包根目录执行此脚本"
    exit 1
fi

# 备份原始文件
BACKUP_DIR="backup_$(date +%Y%m%d_%H%M%S)"
info "创建备份目录: ${BACKUP_DIR}"
mkdir -p "${BACKUP_DIR}"

# 备份需要修改的文件
FILES_TO_BACKUP=(
    "docker-compose.yml"
    "config/rocketmq/broker.conf"
    "config/redis/sentinel.conf"
    "config/prometheus/prometheus.yml"
    "sql/04-nacos-config-seed.sql"
    "config/nacos/my-xhs-redis.yaml"
    "config/deploy-cloud/init-xxljob.sql"
    "start-all.sh"
    "sql/test-data-init.sql"
    "config/grafana/provisioning/datasources/prometheus.yml"
    "apply-review-fixes.sh"
    "config/deploy-cloud/apply-review-fixes.sh"
    "config/deploy-cloud/remote-upgrade.sh"
)

for file in "${FILES_TO_BACKUP[@]}"; do
    if [ -f "$file" ]; then
        cp "$file" "${BACKUP_DIR}/"
    fi
done

info "原始文件已备份到 ${BACKUP_DIR}/"
echo ""

# 替换函数
replace_ip() {
    local file="$1"
    local search="$2"
    local replace="$3"
    
    if [ -f "$file" ]; then
        if grep -q "$search" "$file" 2>/dev/null; then
            sed -i "s|$search|$replace|g" "$file"
            info "已替换: $file"
        fi
    fi
}

# 执行替换
info "开始替换 IP 配置..."
echo ""

# docker-compose.yml
replace_ip "docker-compose.yml" '${HOST_IP}' "${HOST_IP}"
replace_ip "docker-compose.yml" '${MICROSERVICE_IP}' "${MICROSERVICE_IP}"

# broker.conf
replace_ip "config/rocketmq/broker.conf" '${HOST_IP}' "${HOST_IP}"

# sentinel.conf
replace_ip "config/redis/sentinel.conf" '${HOST_IP}' "${HOST_IP}"

# prometheus.yml
replace_ip "config/prometheus/prometheus.yml" '${HOST_IP}' "${HOST_IP}"
replace_ip "config/prometheus/prometheus.yml" '${MICROSERVICE_IP}' "${MICROSERVICE_IP}"

# 04-nacos-config-seed.sql
replace_ip "sql/04-nacos-config-seed.sql" '${HOST_IP}' "${HOST_IP}"
replace_ip "sql/04-nacos-config-seed.sql" '${MICROSERVICE_IP}' "${MICROSERVICE_IP}"

# nacos配置文件
replace_ip "config/nacos/my-xhs-redis.yaml" '${HOST_IP}' "${HOST_IP}"

# init-xxljob.sql
replace_ip "config/deploy-cloud/init-xxljob.sql" '${MICROSERVICE_IP}' "${MICROSERVICE_IP}"

# start-all.sh
replace_ip "start-all.sh" '${HOST_IP}' "${HOST_IP}"

# test-data-init.sql
replace_ip "sql/test-data-init.sql" '${HOST_IP}' "${HOST_IP}"

# grafana datasources
replace_ip "config/grafana/provisioning/datasources/prometheus.yml" '${HOST_IP}' "${HOST_IP}"

# apply-review-fixes.sh
replace_ip "apply-review-fixes.sh" '${HOST_IP}' "${HOST_IP}"
replace_ip "config/deploy-cloud/apply-review-fixes.sh" '${HOST_IP}' "${HOST_IP}"
replace_ip "config/deploy-cloud/remote-upgrade.sh" '${HOST_IP}' "${HOST_IP}"

echo ""
info "=========================================="
info "IP 配置替换完成！"
info "=========================================="
echo ""
info "修改的文件列表："
for file in "${FILES_TO_BACKUP[@]}"; do
    if [ -f "$file" ]; then
        echo "  - $file"
    fi
done

echo ""
info "原始文件备份在: ${BACKUP_DIR}/"
echo ""
warn "重要提示："
echo "  1. 如果需要回滚，可使用: cp ${BACKUP_DIR}/* ."
echo "    2. 启动前请确认 Canal JDK8 路径存在: /opt/openjdk8
"
echo "  3. 首次部署请执行初始化步骤（见 00-AI-DEPLOY-GUIDE.md 2.4节）"
echo ""
info "部署步骤："
echo "  1. docker compose up -d"
echo "  2. docker compose ps  # 确认所有容器 healthy"
echo "  3. 执行初始化脚本（Nacos种子、XXL-Job种子、RocketMQ Topic等）"
echo ""
info "完成！"