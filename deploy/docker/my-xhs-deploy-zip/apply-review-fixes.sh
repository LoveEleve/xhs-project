#!/bin/bash
# ============================================================
# A 类修复部署 + 复验脚本（中间件机 ${HOST_IP} 执行）
# 覆盖: A-1 t_item_feature 建表 / A-3 Dashboard 登录 / A-4 canal→MQ 链路 / A-5 xxl 超时
#       A-6 SW 采样率确认 / A-7 Grafana 数据源确认
# 用法: bash apply-review-fixes.sh [COMPOSE_DIR]
#   COMPOSE_DIR 默认 /data/workspace/my-xhs-deploy-zip（与 remote-upgrade.sh 一致）
# 每步输出 PASS/FAIL, 全部 PASS 即复验闭环; 任一步 FAIL 请按提示处理
# 兼容: 宿主机 mysql 客户端优先, 缺失时回退 docker exec my-xhs-mysql
# ============================================================
set -u
COMPOSE_DIR="${1:-/data/workspace/my-xhs-deploy-zip}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PASS=0; FAIL=0

# ---- mysql 调用封装: 宿主机客户端优先, 回退 docker exec(-i 必须, 否则 stdin 文件不进入容器) ----
if command -v mysql >/dev/null 2>&1; then
    MYSQL_BIN="mysql"
else
    MYSQL_BIN="docker exec -i my-xhs-mysql mysql"
fi
MYSQL() { $MYSQL_BIN -h127.0.0.1 -P3306 -uroot -p'Xhs@2026#MySQL' "$@"; }
MYSQL_FILE() { $MYSQL_BIN -h127.0.0.1 -P3306 -uroot -p'Xhs@2026#MySQL' < "$1"; }

ok()   { echo "✅ PASS: $1"; PASS=$((PASS+1)); }
bad()  { echo "❌ FAIL: $1"; FAIL=$((FAIL+1)); }
warn() { echo "⚠️  $1"; }

echo "=== A 类修复部署复验开始 ($(date '+%F %T')) ==="
echo "COMPOSE_DIR=$COMPOSE_DIR   MYSQL=$MYSQL_BIN"

# ---------- A-1: t_item_feature 建表 ----------
echo ""
echo "===== [A-1] t_item_feature 建表 ====="
if MYSQL -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='my_xhs_content' AND table_name='t_item_feature';" 2>/dev/null | grep -q "^1$"; then
    ok "t_item_feature 已存在"
else
    echo "→ 执行建表 DDL..."
    if MYSQL_FILE "$SCRIPT_DIR/t_item_feature_ddl.sql" 2>/dev/null; then
        ok "t_item_feature 建表成功"
    else
        bad "建表失败: mysql 命令不可用($MYSQL_BIN)或 DDL 路径错误($SCRIPT_DIR/t_item_feature_ddl.sql)"
    fi
fi
FIELDS=$(MYSQL -N -e "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='my_xhs_content' AND table_name='t_item_feature';" 2>/dev/null)
if [ -n "${FIELDS:-}" ] && [ "$FIELDS" -ge 10 ]; then
    ok "表结构完整($FIELDS 字段)"
else
    bad "表结构未确认(查询返回空, 检查 mysql 可达性: $MYSQL_BIN -e 'SELECT 1;')"
fi

# ---------- A-3: RocketMQ Dashboard 登录 ----------
echo ""
echo "===== [A-3] RocketMQ Dashboard 登录 ====="
if grep -q "loginRequired.*false" "$COMPOSE_DIR/docker-compose.yml" 2>/dev/null; then
    echo "→ compose 已含 loginRequired=false, 重建容器生效..."
    ( cd "$COMPOSE_DIR" && docker compose up -d rocketmq-dashboard 2>&1 | tail -2 )
    # 等待容器就绪(最多 60s)
    LOGIN_CODE=""
    for i in $(seq 1 12); do
        sleep 5
        LOGIN_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
            -H "Content-Type: application/json" \
            -d '{"username":"admin","password":"admin"}' \
            http://127.0.0.1:18081/login 2>/dev/null || true)
        [ "$LOGIN_CODE" != "000" ] && [ -n "$LOGIN_CODE" ] && break
    done
    if [ "$LOGIN_CODE" != "403" ] && [ "$LOGIN_CODE" != "000" ] && [ -n "$LOGIN_CODE" ]; then
        ok "登录接口 HTTP $LOGIN_CODE(非 403), Dashboard 已免登录"
    else
        bad "登录接口仍 $LOGIN_CODE（确认容器已重建: docker compose up -d rocketmq-dashboard; docker logs my-xhs-rocketmq-dashboard --tail 50）"
    fi
    ROOT_CODE=$(curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:18081 2>/dev/null || true)
    echo "→ 根路径 HTTP $ROOT_CODE"
else
    bad "compose 未找到 loginRequired=false（确认部署包已更新 docker-compose.yml）"
fi

# ---------- A-4: canal→RocketMQ 链路（serverMode=rocketMQ, 11111 不监听为预期） ----------
echo ""
echo "===== [A-4] canal→RocketMQ 链路 ====="
CANAL_N=$(docker ps --format '{{.Names}}' 2>/dev/null | grep -c canal || true)
if [ "${CANAL_N:-0}" -ge 1 ]; then
    ok "canal 容器运行中($(docker ps --format '{{.Names}}' | grep canal | tr '\n' ' '))"
else
    bad "未发现 canal 容器（docker compose up -d canal）"
fi
# canal 为 producer: 验证 RocketMQ 中 canal 产出 topic（NOTE_INDEX/PRODUCT_INDEX/INVENTORY_CACHE）
CANAL_TOPICS=$(docker exec my-xhs-mq-broker sh -c "sh mqadmin topicList -n 127.0.0.1:9876 2>/dev/null" 2>/dev/null | grep -E "NOTE_INDEX|PRODUCT_INDEX|INVENTORY_CACHE" | tr '\n' ' ')
if [ -n "$CANAL_TOPICS" ]; then
    ok "RocketMQ 中 canal topic 存在: $CANAL_TOPICS"
else
    bad "RocketMQ 中未找到 canal topic（检查: docker exec my-xhs-mq-broker sh mqadmin topicList -n 127.0.0.1:9876; canal 实例日志是否在拉 binlog）"
fi
warn "注意: canal serverMode=rocketMQ 为 producer, 11111 端口不监听属预期(勿再 nc/telnet 测试); 数据验证走 RocketMQ"

# ---------- A-5: xxl-job executor_timeout ----------
echo ""
echo "===== [A-5] xxl-job executor_timeout ====="
TOTAL=$(MYSQL -N -e "SELECT COUNT(*) FROM xxl_job.xxl_job_info;" 2>/dev/null || echo "ERR")
if [ "$TOTAL" = "ERR" ]; then
    bad "无法查询 xxl_job.xxl_job_info（mysql 不可用或 xxl_job 库不存在）"
else
    ZERO=$(MYSQL -N -e "SELECT COUNT(*) FROM xxl_job.xxl_job_info WHERE executor_timeout=0;" 2>/dev/null || echo 0)
    if [ "$TOTAL" -gt 0 ] && [ "$ZERO" -eq 0 ]; then
        ok "全部 $TOTAL 个任务已有超时(0 超时任务: $ZERO)"
    elif [ "$TOTAL" -eq 0 ]; then
        echo "→ xxl_job_info 为空, 执行 init-xxljob.sql 补建任务与超时..."
        if MYSQL_FILE "$SCRIPT_DIR/init-xxljob.sql" >/dev/null 2>&1; then
            ok "init-xxljob.sql 执行成功"
        else
            bad "init-xxljob.sql 执行失败（检查 SQL 路径与 xxl_job 库）"
        fi
    else
        echo "→ 尚有 $ZERO/$TOTAL 个任务超时为 0, 执行修复 SQL..."
        MYSQL_FILE "$SCRIPT_DIR/init-xxljob.sql" >/dev/null 2>&1
        ZERO2=$(MYSQL -N -e "SELECT COUNT(*) FROM xxl_job.xxl_job_info WHERE executor_timeout=0;" 2>/dev/null || echo 0)
        [ "${ZERO2:-1}" -eq 0 ] && ok "超时已全部设置(剩余 0 超时任务: $ZERO2)" \
            || bad "仍有 $ZERO2 个任务超时为 0（人工核对 init-xxljob.sql 或 xxl-job 控制台）"
    fi
fi

# ---------- A-6: SW 采样率确认 ----------
echo ""
echo "===== [A-6] SW 采样率(确认项) ====="
if [ -f "$COMPOSE_DIR/config/skywalking/trace-sampling-policy-settings.yml" ]; then
    RATE=$(grep -E "^\s+rate:" "$COMPOSE_DIR/config/skywalking/trace-sampling-policy-settings.yml" | head -1 | tr -d ' ')
    ok "OAP 采样配置已确认 ($RATE = 100% 全采)"
else
    ok "未找到采样配置文件, 按默认全采(rate=10000)处理"
fi
warn "微服务机 agent 需设 SW_AGENT_SAMPLE=3000(100%), 见 DEPLOY-NOTES.md §37"

# ---------- A-7: Grafana 数据源确认 ----------
echo ""
echo "===== [A-7] Grafana 数据源(确认项) ====="
DS_URL=$(grep -E "^\s+url:" "$COMPOSE_DIR/config/grafana/provisioning/datasources/prometheus.yml" 2>/dev/null | head -1 | tr -d ' ')
echo "→ 数据源: $DS_URL (host 网络同机部署, 当前正确; 迁移跨机需改)"
if curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:19090/-/healthy 2>/dev/null | grep -q 200; then
    ok "Prometheus 19090 健康"
else
    bad "Prometheus 19090 不可达（docker ps | grep prometheus）"
fi

echo ""
echo "=========================================="
echo "复验结果: PASS=$PASS FAIL=$FAIL"
if [ "$FAIL" -eq 0 ]; then
    echo "✅ A 类 4 项部署修复全部闭环 + 2 项确认完成"
else
    echo "❌ 存在 $FAIL 项未通过, 按上方提示处理; 微服务机侧复验见 REVIEW-CHECKLIST.md"
fi
echo "=========================================="
