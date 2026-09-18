#!/usr/bin/env bash
# E2E 自动化（AI 服务）：fast=确定性用例（无 LLM）；--full 追加 LLM 对话/SSE
# 用法: bash scripts/e2e.sh [--full]
set -u
BASE=${AI_BASE:-http://127.0.0.1:19020}
TOKENS_FILE=${TOKENS_FILE:-$(dirname "$0")/../../.secrets/tokens.env}
if [ -f "$TOKENS_FILE" ]; then set -a; . "$TOKENS_FILE"; set +a; fi
ADMIN=${ADMIN_TOKEN:-}
PASS=0; FAIL=0; TOTAL=0
code(){ curl -s -o /dev/null -w '%{http_code}' "$@"; }
chk(){ TOTAL=$((TOTAL+1)); if [ "$2" = "$3" ]; then echo "  ✓ $1"; PASS=$((PASS+1)); else echo "  ✗ $1（期望 $3 实际 $2）"; FAIL=$((FAIL+1)); fi; }

echo "== E2E · 权限与负向（无 LLM）=="
chk "健康检查 200" "$(code "$BASE/actuator/health")" 200
chk "重索引（无令牌）401" "$(code -X POST "$BASE/api/ai/knowledge/reindex")" 401
chk "对话（无令牌）401" "$(code -X POST "$BASE/api/ai/chat" -H 'Content-Type: application/json' -d '{"message":"hi"}')" 401
chk "错误方法 405" "$(code -H "X-Admin-Call: $ADMIN" "$BASE/api/ai/chat")" 405
chk "缺少必填参数 400" "$(code -H "X-Admin-Call: $ADMIN" "$BASE/api/ai/approvals/diagnostics/consumer-progress")" 400
chk "超长消息 400" "$(code -X POST "$BASE/api/ai/agent/chat" -H "X-Admin-Call: $ADMIN" -H 'Content-Type: application/json' -d "{\"message\":\"$(printf 'x%.0s' $(seq 1 5000))\"}")" 400

echo "== E2E · 功能面（无 LLM，确定性）=="
chk "知识统计 200" "$(code -H "X-Admin-Call: $ADMIN" "$BASE/api/ai/knowledge/stats")" 200
chk "审批列表 200" "$(code -H "X-Admin-Call: $ADMIN" "$BASE/api/ai/approvals")" 200
chk "会话列表 200" "$(code -H "X-Admin-Call: $ADMIN" "$BASE/api/ai/sessions")" 200
chk "消费位点诊断（带 group）200" "$(code -H "X-Admin-Call: $ADMIN" "$BASE/api/ai/approvals/diagnostics/consumer-progress?group=cart-event-sink-group")" 200

if [ "${1:-}" = "--full" ]; then
  echo "== E2E · LLM 对话（消耗额度）=="
  R=$(curl -s -m 90 -X POST "$BASE/api/ai/chat" -H "X-Admin-Call: $ADMIN" -H 'Content-Type: application/json' -d '{"message":"一句话说明你是谁"}')
  TOTAL=$((TOTAL+1))
  if [ "${#R}" -gt 20 ]; then echo "  ✓ chat 返回非空（${#R}B）"; PASS=$((PASS+1)); else echo "  ✗ chat 返回异常: ${R:0:120}"; FAIL=$((FAIL+1)); fi
  CT=$(curl -s -m 90 -o /tmp/e2e_sse.txt -w '%{content_type}' -X POST "$BASE/api/ai/chat/stream" -H "X-Admin-Call: $ADMIN" -H 'Content-Type: application/json' -d '{"message":"你好"}')
  TOTAL=$((TOTAL+1))
  if echo "$CT" | grep -q "text/event-stream" && grep -q "data:" /tmp/e2e_sse.txt; then echo "  ✓ chat/stream SSE（content-type + data 事件）"; PASS=$((PASS+1)); else echo "  ✗ chat/stream SSE: ct=$CT"; FAIL=$((FAIL+1)); fi
fi

echo "== E2E: $PASS/$TOTAL 通过 =="
[ "$FAIL" -eq 0 ]
