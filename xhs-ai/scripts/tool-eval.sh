#!/usr/bin/env bash
# 工具/轨迹评测：按 eval/tool-cases.yaml 提问 Agent，从 Redis 状态提取调用序列并评分
# 评分：期望工具为有序子序列（允许中间有探索性调用）→ 满分；命中部分 → 部分分；一次未中 → 0
# 用法：source .secrets/tokens.env && bash scripts/tool-eval.sh [cases] [repeat]
set -u
LIMIT=${1:-99}; REPEAT=${2:-1}
OUT=docs/reports/tool-eval-$(date +%Y%m%d-%H%M).json
EVAL_UID=${EVAL_UID:-1001}
STATE_TMPL="xhs-ai:state:$EVAL_UID/$EVAL_UID:%s:agent_state"
# 评测专用用户预置当日额度，避免消耗真实用户预算/被硬限拦截
docker exec my-xhs-redis redis-cli -a 'Xhs@2026#Redis' --no-auth-warning DEL "xhs-ai:budget:$EVAL_UID:$(date +%Y%m%d)" >/dev/null
python3 - "$LIMIT" <<'PY' > /tmp/opencode/tool-cases.tsv
import sys, yaml, os
limit=int(sys.argv[1]); data=yaml.safe_load(open('eval/tool-cases.yaml'))
only=set(filter(None, os.environ.get('CASES','').split(',')))
for c in data['cases'][:limit]:
    if only and c['id'] not in only: continue
    if 'sequence' in c:
        print(f"{c['id']}\tSEQ\t{'|'.join(c['sequence'])}\t{c['question']}")
    else:
        print(f"{c['id']}\tSET\t{'|'.join(c.get('expected',[]))}\t{c['question']}")
PY
echo -e "case\trun\tscore\tmatched/expected\textra\ttools\tms" > /tmp/opencode/tool-eval.tsv
SUM=0; CNT=0
while IFS=$'\t' read -r id mode expected question; do
  for run in $(seq 1 "$REPEAT"); do
    sid="te-$id-$(date +%s)-$run"
    T0=$(date +%s%3N)
    curl -s --max-time 240 -H "Content-Type: application/json" -H "X-Internal-Call: $INTERNAL_TOKEN" \
      -H "X-User-Id: $EVAL_UID" -X POST 127.0.0.1:19020/api/ai/agent/chat \
      -d "{\"sessionId\":\"$sid\",\"message\":\"$question\"}" > /dev/null
    T1=$(date +%s%3N)
    KEY=$(printf "$STATE_TMPL" "$sid")
    ACTUAL=$(docker exec my-xhs-redis redis-cli -a 'Xhs@2026#Redis' --no-auth-warning GET "$KEY" 2>/dev/null | python3 -c "
import json,sys
try: d=json.load(sys.stdin)
except Exception: print(''); raise SystemExit
seen=[]
for m in d.get('context',[]):
    for c in (m.get('content') or []):
        if c.get('type')=='tool_use':
            n=c.get('name')
            if n not in seen: seen.append(n)
print('|'.join(seen))")
    read -r SCORE MATCHED EXTRA < <(python3 - "$mode" "$expected" "$ACTUAL" <<'PY'
import sys
mode, exp_raw, act_raw = sys.argv[1], sys.argv[2], sys.argv[3]
if exp_raw == 'none':
    print('1 0/0 0' if not act_raw else '0 0/0 %d' % len(act_raw.split('|')))
    raise SystemExit
exp=[e for e in exp_raw.split('|') if e]; act=[a for a in act_raw.split('|') if a]
if mode == 'SEQ':
    i=0
    for a in act:
        if i < len(exp) and a == exp[i]: i += 1
    matched=i
else:
    matched=sum(1 for e in exp if e in act)
score = round(matched/len(exp), 2) if exp else 1.0
print(f"{score} {matched}/{len(exp)} {max(0,len(act)-matched)}")
PY
)
    echo -e "$id\t$run\t$SCORE\t$MATCHED\t$EXTRA\t$ACTUAL\t$((T1-T0))" | tee -a /tmp/opencode/tool-eval.tsv
    SUM=$(python3 -c "print(round($SUM+$SCORE,2))"); CNT=$((CNT+1))
  done
done < /tmp/opencode/tool-cases.tsv
AVG=$(python3 -c "print(round($SUM/$CNT,3))")
echo "=== 平均轨迹得分 $AVG（$CNT 次运行）"
python3 - "$OUT" "$AVG" "$CNT" <<'PY'
import json,sys
rows=[l.rstrip('\n').split('\t') for l in open('/tmp/opencode/tool-eval.tsv')][1:]
json.dump({'avgScore':float(sys.argv[2]),'runs':int(sys.argv[3]),'rows':[
    {'case':r[0],'run':int(r[1]),'score':float(r[2]),'matched':r[3],'extra':int(r[4]),'tools':r[5]} for r in rows if len(r)>5]},
    open(sys.argv[1],'w'), ensure_ascii=False, indent=2)
print('报告:', sys.argv[1])
PY
