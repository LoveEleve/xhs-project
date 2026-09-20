#!/usr/bin/env bash
# xhs-ai 评测门禁（需 xhs-ai 服务在跑）：KB 检索 hit@1≥90% + 答案级评测 gatePass 且 blocked==0
# 用法：
#   ADMIN_TOKEN=xxx bash scripts/ai-eval-gate.sh                # 默认跑 kb+sec 答案用例
#   ANSWER_TYPES=kb,diag,sec LIMIT=0 bash scripts/ai-eval-gate.sh  # 全量答案评测（约 30 分钟）
#   SKIP_ANSWERS=1 bash scripts/ai-eval-gate.sh                  # 只跑 KB 检索门禁
# 环境：AI_BASE（默认 http://127.0.0.1:19020）、ADMIN_TOKEN（必填）
set -uo pipefail

AI_BASE="${AI_BASE:-http://127.0.0.1:19020}"
ANSWER_TYPES="${ANSWER_TYPES:-kb,sec}"
LIMIT="${LIMIT:-0}"
SKIP_ANSWERS="${SKIP_ANSWERS:-0}"
FAIL=0

if [ -z "${ADMIN_TOKEN:-}" ]; then
  echo "❌ 需要 ADMIN_TOKEN（管理令牌）"; exit 1
fi

echo "== [1/2] KB 检索评测（hit@1/hit@3 门禁 ≥90%） =="
KB=$(curl -s -m 120 -X POST "$AI_BASE/api/ai/knowledge/eval" -H "X-Admin-Call: $ADMIN_TOKEN")
echo "$KB" | python3 -c '
import json,sys
b=json.load(sys.stdin); d=b.get("data") or {}
print(f"  total={d.get(\"total\")} hit@1={d.get(\"hit1Rate\")}% hit@3={d.get(\"hit3Rate\")}% gatePass={d.get(\"gatePass\")}")
sys.exit(0 if d.get("gatePass") else 1)
' || { echo "❌ KB 检索门禁未通过"; FAIL=1; }

if [ "$SKIP_ANSWERS" != "1" ]; then
  echo "== [2/2] 答案级评测（types=$ANSWER_TYPES limit=$LIMIT；要求 gatePass 且 blocked==0） =="
  for t in ${ANSWER_TYPES//,/ }; do
    ANS=$(curl -s -m 2400 -X POST "$AI_BASE/api/ai/knowledge/eval/answers?type=$t&limit=$LIMIT" -H "X-Admin-Call: $ADMIN_TOKEN")
    echo "$ANS" | python3 -c '
import json,sys
b=json.load(sys.stdin); d=b.get("data") or {}
print(f"  type={d.get(\"type\")} total={d.get(\"total\")} passed={d.get(\"passed\")} blocked={d.get(\"blocked\")} passRate={d.get(\"passRate\")}% citationValid={d.get(\"citationValidRate\")}% gatePass={d.get(\"gatePass\")}")
for c in (d.get("cases") or []):
    if not c.get("pass"): print("   -", c.get("id"), "blocked=" + str(c.get("blocked")), c.get("reasons"))
sys.exit(0 if d.get("gatePass") else 1)
' || { echo "❌ 答案评测门禁未通过（type=$t）"; FAIL=1; }
  done
fi

if [ "$FAIL" = "0" ]; then
  echo "== ✅ AI 评测门禁通过 =="
else
  echo "== ❌ AI 评测门禁失败 =="; exit 1
fi
