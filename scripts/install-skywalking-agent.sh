#!/usr/bin/env bash
# SkyWalking Java Agent 安装/修复脚本（幂等）
# 用法: bash scripts/install-skywalking-agent.sh [版本] [目标目录]
#   默认 9.7.0 -> /data/workspace/skywalking-agent-9.7.0
# 内容：下载（TUNA 优先，archive 兜底）→ 解压 → Spring Boot 3 插件适配 → 清理 ._* 元数据
# 说明：release-service.sh / restart-service.sh 通过 SW_AGENT_DIR 使用该目录（默认同路径）
set -euo pipefail
VER=${1:-9.7.0}
DIR=${2:-/data/workspace/skywalking-agent-$VER}
TGZ=/tmp/apache-skywalking-java-agent-$VER.tgz
PKG=apache-skywalking-java-agent-$VER.tgz

if [ -f "$DIR/skywalking-agent.jar" ]; then
  echo "✅ agent 已存在: $DIR（如需重装请先删除目录）"
else
  echo "== 下载 agent $VER =="
  curl -fsSL -m 240 -o "$TGZ" "https://mirrors.tuna.tsinghua.edu.cn/apache/skywalking/java-agent/$VER/$PKG" \
    || curl -fsSL -m 600 --retry 3 -o "$TGZ" "https://archive.apache.org/dist/skywalking/java-agent/$VER/$PKG"
  mkdir -p "$DIR"
  tar xzf "$TGZ" -C "$DIR" --strip-components=1 2>&1 | grep -v "Ignoring unknown extended header" || true
  echo "✅ 解压完成: $DIR"
fi

cd "$DIR"
echo "== Spring Boot 3 插件适配 =="
mkdir -p expired-plugins
for p in apm-springmvc-annotation-3.x-plugin-*.jar apm-springmvc-annotation-4.x-plugin-*.jar apm-springmvc-annotation-5.x-plugin-*.jar; do
  [ -e "plugins/$p" ] && mv "plugins/$p" expired-plugins/ && echo "  移出: $p"
done
for p in apm-springmvc-annotation-6.x-plugin-*.jar apm-spring-webflux-6.x-plugin-*.jar apm-spring-cloud-gateway-4.x-plugin-*.jar; do
  if [ -e "optional-plugins/$p" ]; then mv "optional-plugins/$p" plugins/ && echo "  移入: $p"; fi
done
echo "== 清理 macOS 元数据文件 =="
find . -name "._*" -delete 2>/dev/null || true
echo "✅ 完成: $(du -sh "$DIR" | awk '{print $1}')  plugins=$(ls plugins/ | wc -l) 个"
