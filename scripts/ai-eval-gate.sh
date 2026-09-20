#!/usr/bin/env bash
# xhs-ai 评测门禁（需 xhs-ai 服务在跑）
#   阶段1：KB 检索 hit@1≥90%
#   阶段2：答案级评测（case 级判定：无 blocked、全 pass、无无效引用）
# 模型输出存在波动：失败/引用异常的用例默认自动重跑一次（RETRY_FAILED=0 关闭）
# 用法：
#   ADMIN_TOKEN=xxx bash scripts/ai-eval-gate.sh                       # 默认 kb+sec
#   ANSWER_TYPES=kb,diag,sec LIMIT=0 bash scripts/ai-eval-gate.sh      # 全量（约 30 分钟）
#   SKIP_ANSWERS=1 bash scripts/ai-eval-gate.sh                        # 只跑 KB 检索
# 环境：AI_BASE（默认 http://127.0.0.1:19020）、ADMIN_TOKEN（必填）、RETRY_FAILED（默认1）
set -uo pipefail

AI_BASE="${AI_BASE:-http://127.0.0.1:19020}"
ANSWER_TYPES="${ANSWER_TYPES:-kb,sec}"
LIMIT="${LIMIT:-0}"
SKIP_ANSWERS="${SKIP_ANSWERS:-0}"
RETRY_FAILED="${RETRY_FAILED:-1}"
FAIL=0
TMP="${TMPDIR:-/tmp}"

if [ -z "${ADMIN_TOKEN:-}" ]; then
  echo "❌ 需要 ADMIN_TOKEN（管理令牌）"; exit 1
fi

echo "== [1/2] KB 检索评测（hit@1/hit@3 门禁 ≥90%） =="
KB=$(curl -s -m 120 -X POST "$AI_BASE/api/ai/knowledge/eval" -H "X-Admin-Call: $ADMIN_TOKEN")
echo "$KB" | python3 -c '
import json,sys
d=(json.load(sys.stdin).get("data") or {})
print("  total=%s hit@1=%s%% hit@3=%s%% gatePass=%s" % (d.get("total"), d.get("hit1Rate"), d.get("hit3Rate"), d.get("gatePass")))
sys.exit(0 if d.get("gatePass") else 1)
' || { echo "❌ KB 检索门禁未通过"; FAIL=1; }

check_cases() { # $1=json文件 $2=标签
python3 - "$1" "$2" <<'PY'
import json,sys
d=json.load(open(sys.argv[1],encoding='utf-8'))
cases=d.get("cases") or []
blocked=[c for c in cases if c.get("blocked")]
failed=[c for c in cases if not c.get("pass") and not c.get("blocked")]
badcite=[c for c in cases if c.get("invalidCitations")]
print(f"  [{sys.argv[2]}] total={len(cases)} passed={sum(1 for c in cases if c.get('pass'))} blocked={len(blocked)} failed={len(failed)} badCite={len(badcite)}")
for c in failed+badcite:
    print("   -", c.get("id"), "reasons=", c.get("reasons"), "invalid=", c.get("invalidCitations"))
retry=sorted({c["id"] for c in failed+badcite})
print("RETRY_IDS="+",".join(retry))
PY
}

merge_cases() { # $1=原始json $2=重跑json $3=输出json：重跑通过且引用干净则替换
python3 - "$1" "$2" "$3" <<'PY'
import json,sys
d=json.load(open(sys.argv[1],encoding='utf-8'))
r=json.load(open(sys.argv[2],encoding='utf-8'))
rmap={c["id"]:c for c in (r.get("cases") or [])}
out=[]
for c in (d.get("cases") or []):
    rr=rmap.get(c.get("id"))
    if rr and rr.get("pass") and not rr.get("blocked") and not rr.get("invalidCitations"):
        out.append(rr)
    else:
        out.append(c)
d["cases"]=out
json.dump(d,open(sys.argv[3],'w',encoding='utf-8'),ensure_ascii=False)
print("merged")
PY
}

if [ "$SKIP_ANSWERS" != "1" ]; then
  echo "== [2/2] 答案级评测（types=$ANSWER_TYPES limit=$LIMIT retryFailed=$RETRY_FAILED） =="
  for t in ${ANSWER_TYPES//,/ }; do
    OUT="$TMP/ai-eval-$t.json"
    curl -s -m 2400 -X POST "$AI_BASE/api/ai/knowledge/eval/answers?type=$t&limit=$LIMIT" -H "X-Admin-Call: $ADMIN_TOKEN" > "$OUT"
    RETRY_IDS=$(check_cases "$OUT" "$t" | tee /dev/stderr | grep '^RETRY_IDS=' | cut -d= -f2)
    if [ -n "$RETRY_IDS" ] && [ "$RETRY_FAILED" = "1" ]; then
      echo "  ↩ 失败/引用异常用例重跑一次: $RETRY_IDS"
      curl -s -m 2400 -X POST "$AI_BASE/api/ai/knowledge/eval/answers?ids=$RETRY_IDS" -H "X-Admin-Call: $ADMIN_TOKEN" > "$TMP/ai-eval-$t-retry.json"
      merge_cases "$OUT" "$TMP/ai-eval-$t-retry.json" "$OUT"
      check_cases "$OUT" "$t(合并后)" || true
    fi
    python3 -c '
import json,sys
d=json.load(open(sys.argv[1],encoding="utf-8")); cases=d.get("cases") or []
ok = bool(cases) and not any(c.get("blocked") or not c.get("pass") or c.get("invalidCitations") for c in cases)
sys.exit(0 if ok else 1)
' "$OUT" || { echo "❌ 答案评测门禁未通过（type=$t）"; FAIL=1; }
  done
fi

if [ "$FAIL" = "0" ]; then
  echo "== ✅ AI 评测门禁通过 =="
else
  echo "== ❌ AI 评测门禁失败 =="; exit 1
fi
