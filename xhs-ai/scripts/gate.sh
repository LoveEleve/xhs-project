#!/usr/bin/env bash
# xhs-ai 一键门禁：单测 + 审计一致性（+ 可选 LLM 评测，消耗真实额度）
# 用法: bash scripts/gate.sh [standard|with-eval]
set -u
MODE=${1:-standard}
DIR="$(cd "$(dirname "$0")/.." && pwd)"
FAIL=0
echo "[1/3] 单元测试"
mvn -f "$DIR/pom.xml" test >/tmp/opencode/gate-test.log 2>&1 && echo "  ✓ 单测通过（$(grep -aoE 'Tests run: [0-9]+' /tmp/opencode/gate-test.log | tail -1)）" || { echo "  ✗ 单测失败（详见 /tmp/opencode/gate-test.log）"; FAIL=1; }
echo "[2/3] 审计一致性"
bash "$DIR/scripts/audit-gate.sh" || FAIL=1
echo "[3/3] 评测门禁"
if [ "$MODE" = "with-eval" ]; then
  set -a; [ -f /data/workspace/xhs-project/.secrets/tokens.env ] && . /data/workspace/xhs-project/.secrets/tokens.env; set +a
  bash "$DIR/scripts/tool-eval.sh" 4 || FAIL=1
else
  echo "  跳过（加 with-eval 参数开启，会消耗模型额度）"
fi
[ "$FAIL" = "0" ] && echo "=== 门禁通过 ✅" || echo "=== 门禁失败 ❌"
exit $FAIL
