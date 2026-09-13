#!/bin/bash
# 成本周采集：阶段1=100 次轻量问答（并发5）；阶段2=10 次诊断（串行）；按 Prometheus 计数器取 token 增量
cd /data/workspace/xhs-project
set -a; source .secrets/tokens.env; set +a
OUT=/tmp/cost-week
mkdir -p $OUT

snap() {
  python3 - <<'PYEOF'
import urllib.request, re
try:
    txt=urllib.request.urlopen('http://127.0.0.1:19020/actuator/prometheus', timeout=5).read().decode()
except Exception:
    print('0 0 0'); raise SystemExit
inp=sum(float(m) for m in re.findall(r'ai_model_tokens_total\{[^}]*type="input"\}\s+([0-9.]+)', txt))
out=sum(float(m) for m in re.findall(r'ai_model_tokens_total\{[^}]*type="output"\}\s+([0-9.]+)', txt))
calls=sum(float(m) for m in re.findall(r'ai_model_calls_total\{[^}]*\}\s+([0-9.]+)', txt))
print(int(inp), int(out), int(calls))
PYEOF
}

# ---------- 阶段1：100 次轻量问答（并发5） ----------
S1=$(snap); echo "phase1_start: $S1"
seq 1 100 | xargs -P 5 -I{} curl -s --max-time 60 -X POST 127.0.0.1:19020/api/ai/chat \
  -H "X-Admin-Call: $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"用一句话说明 Redis Sentinel 的作用"}' -o $OUT/chat-{}.json
S2=$(snap); echo "phase1_end: $S2" > $OUT/phase1.counters
echo "$S2" >> $OUT/phase1.counters

# ---------- 阶段2：10 次诊断（串行，复用 MTTR 问题） ----------
S3=$(snap); echo "phase2_start: $S3" > $OUT/phase2.counters
i=0
while IFS='|' read -r id q; do
  case "$id" in dlq|prom|log|code|kb-order|kb-refund|kb-inventory|kb-txmsg|kb-compensation|kb-gateway) ;; *) continue;; esac
  i=$((i+1))
  curl -s --max-time 260 -X POST 127.0.0.1:19020/api/ai/agent/chat \
    -H "X-Admin-Call: $ADMIN_TOKEN" -H 'Content-Type: application/json' \
    -d "{\"sessionId\":\"cost-$id\",\"message\":\"$q\"}" -o $OUT/diag-$id.json
  echo "diag $i/10 done: $id"
done < /tmp/mttr/cases.txt
S4=$(snap); echo "$S4" >> $OUT/phase2.counters

python3 - <<'PYEOF'
def read(f):
    vals=[]
    for line in open(f):
        parts=line.strip().split(': ')[-1].split()
        vals.append(tuple(int(x) for x in parts))
    return vals
p1=read('/tmp/cost-week/phase1.counters')  # [start, end]
p2=read('/tmp/cost-week/phase2.counters')  # [start, end]
import json
result={
 'phase1': {'n':100, 'input_tokens':p1[1][0]-p1[0][0], 'output_tokens':p1[1][1]-p1[0][1], 'calls':p1[1][2]-p1[0][2]},
 'phase2': {'n':10, 'input_tokens':p2[1][0]-p2[0][0], 'output_tokens':p2[1][1]-p2[0][1], 'calls':p2[1][2]-p2[0][2]},
}
json.dump(result, open('/tmp/cost-week/result.json','w'), ensure_ascii=False, indent=2)
print(json.dumps(result, ensure_ascii=False))
PYEOF
echo DONE > /tmp/cost-week/done
