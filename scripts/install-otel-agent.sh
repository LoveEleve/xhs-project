#!/usr/bin/env bash
# OTel Java Agent 安装脚本（幂等）：xhs-ai 观测用
# 用法: bash scripts/install-otel-agent.sh [版本] [目标目录]
#   默认 2.10.0 -> /data/workspace/otel-agent
# systemd 挂载模板: deploy/ops/xhs-ai-otel.conf（JAVA_TOOL_OPTIONS + OTEL_* 环境变量）
set -euo pipefail
VER=${1:-2.10.0}
DIR=${2:-/data/workspace/otel-agent}
JAR="$DIR/opentelemetry-javaagent.jar"
if [ -f "$JAR" ]; then echo "✅ OTel agent 已存在: $JAR"; exit 0; fi
mkdir -p "$DIR"
curl -fsSL -m 240 -o "$JAR" \
  "https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/$VER/opentelemetry-javaagent-$VER.jar"
ls -lh "$JAR" | awk '{print "✅ 安装完成:",$9,$5}'
