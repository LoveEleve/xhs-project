#!/bin/bash
# ============================================================
# 中间件机 ${HOST_IP} 部署改造脚本（在中间件机上执行）
# 场景：按量计费云部署前，先在远程机试验"关机开机自动恢复"改造
# 用法：复制本脚本到中间件机，bash remote-upgrade.sh
# 注意：需要 root 权限；先备份 compose
# ============================================================
set -e
COMPOSE_DIR="${COMPOSE_DIR:-/data/workspace/my-xhs-deploy-zip}"
COMPOSE="$COMPOSE_DIR/docker-compose.yml"

echo "=== [1/7] 备份当前 compose ==="
[ -f "$COMPOSE" ] || { echo "❌ 未找到 $COMPOSE，请设置 COMPOSE_DIR"; exit 1; }
cp "$COMPOSE" "$COMPOSE.$(date +%Y%m%d%H%M).bak"
echo "已备份: $COMPOSE.$(date +%Y%m%d%H%M).bak"

echo "=== [2/7] 与项目 config/docker-compose.yml（运行版基线）一致性校验 ==="
# 部署前强制校验：本目录 compose 必须与基线一致（否则从库可能误挂 init-all.sql / healthcheck 缺失）
BASELINE="/data/workspace/my-xhs/config/docker-compose.yml"
if [ -f "$BASELINE" ]; then
    if diff -q "$COMPOSE" "$BASELINE" > /dev/null 2>&1; then
        echo "✅ compose 与基线一致"
    else
        echo "❌ compose 与基线不一致（diff 结果如下）："
        diff "$COMPOSE" "$BASELINE" | head -30
        echo "请先同步基线（cp config/docker-compose.yml 到本目录）再继续，或设置 BASELINE 指向实际基线文件"
        echo "如需忽略校验继续：BASELINE_SKIP=1 bash remote-upgrade.sh"
        [ "$BASELINE_SKIP" = "1" ] || exit 1
    fi
else
    echo "⚠️  基线文件不存在（$BASELINE），跳过一致性校验"
fi

echo "=== [3/7] 应用 restart: always（22 服务）==="
sed -i 's/restart: unless-stopped/restart: always/' "$COMPOSE"
echo "当前 restart: always 数量: $(grep -c 'restart: always' "$COMPOSE")"
echo "当前 restart: unless-stopped 数量: $(grep -c 'restart: unless-stopped' "$COMPOSE")"

echo "=== [4/7] docker 开机自启 ==="
systemctl enable docker 2>/dev/null || echo "⚠️  systemctl enable docker 失败（可能非 systemd，需 rc.local 兜底）"
systemctl enable containerd 2>/dev/null || true
systemctl is-enabled docker 2>/dev/null || echo "⚠️  无法确认 docker enabled"

echo "=== [5/7] 应用 restart 策略（滚动生效，不重建容器）==="
cd "$COMPOSE_DIR"
docker compose up -d 2>&1 | tail -5 || echo "⚠️  docker compose up -d 失败，检查 compose 语法"

echo "=== [6/7] 验证 restart 策略生效 ==="
docker ps --format '{{.Names}}\t{{.Status}}' | head -25
echo "---"
echo "含 restart=always 的容器数: $(docker inspect --format '{{.Name}} {{.HostConfig.RestartPolicy.Name}}' $(docker ps -q) 2>/dev/null | grep -c always || true)"

echo "=== [7/7] 验证从库复制 ==="
mysql -h127.0.0.1 -P3307 -uroot -p'Xhs@2026#MySQL' -e "SHOW REPLICA STATUS\G" 2>/dev/null \
  | grep -E "Replica_IO_Running|Replica_SQL_Running|Seconds_Behind_Source" \
  || echo "⚠️  3307 从库不可达或复制未启动（需重建从库，见部署说明）"

echo ""
echo "=== 完成 ==="
echo "后续验证："
echo "  1. 关机→开机 → docker ps 应 22 个 Up（restart: always 自动拉起）"
echo "  2. 若从库 3307 不可用：按部署说明重建从库（勿跑 init-all.sql）"
echo "  3. 云主机部署前，可把本目录 docker-compose.yml 与项目 config/ 对照同步"
