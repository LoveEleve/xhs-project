#!/usr/bin/env bash
# 审计哈希链校验：逐行重算 entry_hash，报告首个断链；返回非 0 表示链被破坏
set -u
TMP=$(mktemp)
docker exec my-xhs-mysql sh -c "mysql -uroot -p'Xhs@2026#MySQL' --default-character-set=utf8mb4 -N -B -e \"SELECT id, COALESCE(prev_hash,''), COALESCE(entry_hash,''), COALESCE(trace_id,''), COALESCE(actor,''), action, COALESCE(target,''), COALESCE(params, CAST('null' AS JSON)), COALESCE(result,'') FROM my_xhs_ai.ai_audit WHERE entry_hash IS NOT NULL ORDER BY id\"" 2>/dev/null > "$TMP"
python3 - "$TMP" <<'PY'
import sys, json, hashlib
checked=0; broken=[]; prev_expected='GENESIS'
for line in open(sys.argv[1], encoding='utf-8'):
    parts=line.rstrip('\n').split('\t')
    if len(parts)<9: continue
    rid,prev,entry,trace,actor,action,target,params,result=parts[:9]
    if params in ('null','NULL',''): params=None
    cp = json.dumps(json.loads(params), sort_keys=True, separators=(',',':'), ensure_ascii=False) if params is not None else ''
    if result=='NULL': result=''
    raw='|'.join([prev,trace,actor,action,target,cp,result])
    calc=hashlib.sha256(raw.encode()).hexdigest()
    if prev!=prev_expected or calc!=entry: broken.append(rid)
    prev_expected=entry; checked+=1
print(f"校验 {checked} 条链上审计；断链: {broken[:5] if broken else '无'}")
sys.exit(1 if broken else 0)
PY
RC=$?; rm -f "$TMP"; exit $RC
