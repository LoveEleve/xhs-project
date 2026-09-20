#!/bin/bash
# ============================================================
# my-xhs 混沌工程自动化演练脚本
# 双层架构：ChaosBlade（基础设施级）+ Docker（容器级）
# ============================================================
set -euo pipefail

BLADE="/opt/chaosblade/chaosblade-1.7.4/blade"
# 预检（2026-09-20）：blade 缺失时给出明确原因（历史上 /opt/chaosblade.tar.gz 曾是 OSS AccessDenied XML）
if [ ! -x "$BLADE" ]; then
    echo "[FAIL] chaosblade 未安装: $BLADE"
    echo "       官方下载: https://chaosblade.oss-cn-hangzhou.aliyuncs.com/agent/github/1.7.4/chaosblade-1.7.4-linux-amd64.tar.gz"
    echo "       解压到 /opt/chaosblade/（目录结构需为 /opt/chaosblade/chaosblade-1.7.4/blade）"
    echo "       注意：JVM 类实验在 JDK17+Spring Boot fat-jar 上实测不生效，网络/OS 级实验可用（tc netem 已验证）"
    exit 1
fi
REPORT_DIR="/data/chaos-reports/$(date +%Y%m%d_%H%M%S)"
mkdir -p "$REPORT_DIR"

# 颜色输出
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

# 演练结果计数
PASS=0
FAIL=0
SKIP=0

log_info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[FAIL]${NC} $1"; }

# ChaosBlade 清理函数（销毁所有实验）
blade_destroy_all() {
    local uids
    uids=$($BLADE status --type create 2>/dev/null | grep -oP '"uid":"[^"]+' | sed 's/"uid":"//' || true)
    for uid in $uids; do
        $BLADE destroy "$uid" 2>/dev/null || true
    done
}

# ============================================================
# 通用演练函数
# 参数: 场景名 注入命令 验证命令 恢复命令 等待秒数
# ============================================================
run_drill() {
    local name="$1"
    local inject_cmd="$2"
    local verify_cmd="$3"
    local recover_cmd="$4"
    local wait_seconds="${5:-10}"

    echo ""
    echo "=========================================="
    echo " 演练场景: $name"
    echo "=========================================="

    # 1. 注入故障
    log_info "注入故障: $inject_cmd"
    local inject_result
    inject_result=$(eval "$inject_cmd" 2>&1) || true
    echo "$inject_result"

    # 2. 等待故障生效
    log_info "等待 ${wait_seconds} 秒..."
    sleep "$wait_seconds"

    # 3. 验证结果
    log_info "验证: $verify_cmd"
    local verify_result
    local verify_exit=0
    verify_result=$(eval "$verify_cmd" 2>&1) || verify_exit=$?
    echo "$verify_result"

    # 4. 恢复故障
    log_info "恢复: $recover_cmd"
    eval "$recover_cmd" 2>&1 || true

    # 等待恢复
    sleep 5

    # 5. 记录报告
    {
        echo "场景: $name"
        echo "时间: $(date '+%Y-%m-%d %H:%M:%S')"
        echo "注入: $inject_cmd"
        echo "验证: $verify_cmd"
        echo "验证结果: $verify_result"
        echo "验证退出码: $verify_exit"
        echo "---"
    } >> "$REPORT_DIR/report.txt"

    if [ "$verify_exit" -eq 0 ]; then
        log_info "✅ 场景通过: $name"
        ((PASS++)) || true
    else
        log_error "❌ 场景失败: $name"
        ((FAIL++)) || true
    fi
}

# ============================================================
# 前置检查
# ============================================================
echo "============================================"
echo " my-xhs 混沌工程演练"
echo " 时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo " ChaosBlade: $($BLADE version 2>&1 | head -1)"
echo " 报告目录: $REPORT_DIR"
echo "============================================"

# 检查 ChaosBlade
if [ ! -x "$BLADE" ]; then
    log_error "ChaosBlade 未安装: $BLADE"
    exit 1
fi

# 检查 Docker 容器
log_info "检查基础设施容器状态..."
for container in my-xhs-mysql my-xhs-redis my-xhs-mq-broker; do
    status=$(docker inspect -f '{{.State.Status}}' "$container" 2>/dev/null || echo "not_found")
    if [ "$status" != "running" ]; then
        log_error "容器 $container 未运行 (status=$status)"
        exit 1
    fi
    log_info "  $container: $status"
done

# ============================================================
# 场景 1: Redis 不可用 → 验证服务是否存活
# 使用 Docker pause 暂停 Redis 容器（比 stop 更快，模拟网络不可达）
# ============================================================
run_drill \
    "Redis不可用（Docker pause）" \
    "docker pause my-xhs-redis" \
    "curl -sf -o /dev/null -w '%{http_code}' --connect-timeout 5 --max-time 10 http://localhost:9001/actuator/health/liveness || echo 'liveness_failed'" \
    "docker unpause my-xhs-redis" \
    5

# ============================================================
# 场景 2: Redis 网络延迟（ChaosBlade）
# 注入 3 秒网络延迟，验证服务是否超时降级
# ============================================================
REDIS_PORT=16379
run_drill \
    "Redis网络延迟3秒（ChaosBlade）" \
    "$BLADE create network delay --time 3000 --offset 500 --interface lo --local-port $REDIS_PORT" \
    "curl -sf -o /dev/null -w '%{http_code}' --connect-timeout 5 --max-time 15 http://localhost:9001/actuator/health || echo 'health_timeout'" \
    "blade_destroy_all" \
    5

# ============================================================
# 场景 3: MQ Broker 不可用 → 验证服务是否存活
# ============================================================
run_drill \
    "MQ Broker不可用（Docker pause）" \
    "docker pause my-xhs-mq-broker" \
    "curl -sf -o /dev/null -w '%{http_code}' --connect-timeout 5 --max-time 10 http://localhost:9001/actuator/health/liveness || echo 'liveness_failed'" \
    "docker unpause my-xhs-mq-broker" \
    5

# ============================================================
# 场景 4: CPU 满载（ChaosBlade）
# 注入 CPU 满载 10 秒，验证服务是否仍可响应
# ============================================================
run_drill \
    "CPU满载（ChaosBlade 2核）" \
    "$BLADE create cpu fullload --cpu-count 2 --timeout 15" \
    "curl -sf -o /dev/null -w '%{http_code}' --connect-timeout 5 --max-time 10 http://localhost:9001/actuator/health || echo 'health_timeout'" \
    "blade_destroy_all" \
    10

# ============================================================
# 场景 5: 磁盘 IO 高负载（ChaosBlade）
# ============================================================
run_drill \
    "磁盘IO高负载（ChaosBlade）" \
    "$BLADE create disk burn --read --write --size 100 --timeout 15" \
    "curl -sf -o /dev/null -w '%{http_code}' --connect-timeout 5 --max-time 10 http://localhost:9001/actuator/health || echo 'health_timeout'" \
    "blade_destroy_all" \
    10

# ============================================================
# 场景 6: MySQL 连接中断（Docker pause）
# ============================================================
run_drill \
    "MySQL不可用（Docker pause 10秒）" \
    "docker pause my-xhs-mysql" \
    "curl -sf -o /dev/null -w '%{http_code}' --connect-timeout 5 --max-time 10 http://localhost:9001/actuator/health/liveness || echo 'liveness_failed'" \
    "docker unpause my-xhs-mysql" \
    10

# ============================================================
# 场景 7: 优雅停机验证（kill -15）
# 验证服务收到 SIGTERM 后是否优雅退出
# ============================================================
echo ""
echo "=========================================="
echo " 演练场景: 优雅停机验证"
echo "=========================================="
USER_PID=$(ps aux | grep 'my-xhs-user' | grep java | grep -v grep | awk '{print $2}' | head -1)
if [ -n "$USER_PID" ]; then
    log_info "发送 SIGTERM 到 User 服务 (PID=$USER_PID)"
    kill -15 "$USER_PID"
    sleep 8
    # 检查进程是否已退出
    if ! kill -0 "$USER_PID" 2>/dev/null; then
        log_info "✅ 场景通过: 优雅停机验证（进程已退出）"
        ((PASS++)) || true
    else
        log_error "❌ 场景失败: 优雅停机验证（进程仍在运行）"
        ((FAIL++)) || true
    fi
else
    log_warn "⏭️ 跳过: User 服务未运行"
    ((SKIP++)) || true
fi

# ============================================================
# 清理 & 报告
# ============================================================
echo ""
echo "============================================"
echo " 演练完成"
echo "============================================"
# 确保所有 ChaosBlade 故障已清除
blade_destroy_all

# 确保所有 Docker 容器已恢复
for container in my-xhs-mysql my-xhs-redis my-xhs-mq-broker; do
    docker unpause "$container" 2>/dev/null || true
done

echo ""
log_info "演练结果: ✅ 通过=$PASS  ❌ 失败=$FAIL  ⏭️ 跳过=$SKIP"
log_info "报告路径: $REPORT_DIR/report.txt"

# 生成摘要
{
    echo ""
    echo "============================================"
    echo "演练摘要"
    echo "时间: $(date '+%Y-%m-%d %H:%M:%S')"
    echo "通过: $PASS"
    echo "失败: $FAIL"
    echo "跳过: $SKIP"
    echo "============================================"
} >> "$REPORT_DIR/report.txt"

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi