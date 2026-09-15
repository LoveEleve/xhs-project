#!/usr/bin/env bash
# 工具选择评测：按 eval/tool-cases.yaml 逐题问 Agent，从 Redis 状态提取实际调用工具并比对期望
# 用法：source .secrets/tokens.env && bash scripts/tool-eval.sh [limit]
set -u
LIMIT=${1:-99}
CASES=eval/tool-cases.yaml
OUT=docs/reports/tool-eval-$(date +%Y%m%d-%H%M).json
STATE_TMPL='xhs-ai:state:99/99:tool-eval-%s:agent_state'
python3 - "$LIMIT" "$CASES" <<'PY' > /tmp/opencode/tool-cases.tsv
import sys, yaml
limit=int(sys.argv[1]); data=yaml.safe_load(open(sys.argv[2]))
for c in data['cases'][:limit]:
    print(f"{c['id']}\t{'|'.join(c.get('expected',[]))}\t{c['question']}")
PY
PASS=0; TOTAL=0; echo -e "id\tresult\texpected\tactual\tms" > /tmp/opencode/tool-eval.tsv
while IFS=$'\t' read -r id expected question; do
  TOTAL=$((TOTAL+1))
  sid="tool-eval-$id"
  T0=$(date +%s%3N)
  for attempt in 1 2; do
    curl -s --max-time 240 -H "Content-Type: application/json" -H "X-Internal-Call: $INTERNAL_TOKEN" -H "X-User-Id: 99" \
      -X POST 127.0.0.1:19020/api/ai/agent/chat -d "{\"sessionId\":\"$sid$([ $attempt -gt 1 ] && echo -r$attempt)\",\"message\":\"$question\"}" > /dev/null
    T1=$(date +%s%3N)
    KEY=$(printf "$STATE_TMPL" "$id$([ $attempt -gt 1 ] && echo -r$attempt)")
    ACTUAL=$(docker exec my-xhs-redis redis-cli -a 'Xhs@2026#Redis' --no-auth-warning GET "$KEY" 2>/dev/null | python3 -c "
import json,sys
try: d=json.load(sys.stdin)
except Exception: print('none'); raise SystemExit
tools=[]
for m in d.get('context',[]):
    for c in (m.get('content') or []):
        if c.get('type')=='tool_use': tools.append(c.get('name'))
print('|'.join(dict.fromkeys(tools)) or 'none')")
    [ "$ACTUAL" != "none" ] && break
  done
  if [ "$expected" = "none" ]; then
    OK=$([ "$ACTUAL" = "none" ] && echo PASS || echo FAIL)
  else
    OK=FAIL
    IFS='|' read -ra EXP <<< "$expected"
    for e in "${EXP[@]}"; do case "|$ACTUAL|" in *"|$e|"*) OK=PASS; break;; esac; done
  fi
  [ "$OK" = "PASS" ] && PASS=$((PASS+1))
  echo -e "$id\t$OK\t$expected\t$ACTUAL\t$((T1-T0))" | tee -a /tmp/opencode/tool-eval.tsv
done < /tmp/opencode/tool-cases.tsv
echo "=== 准确率 $PASS/$TOTAL"
cp /tmp/opencode/tool-eval.tsv "$OUT"; echo "报告: $OUT"
