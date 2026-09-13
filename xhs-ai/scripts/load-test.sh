#!/usr/bin/env bash
# 会话级轻量压测（M4）：N 个请求 / C 并发，输出原始 CSV + P50/P95/P99
# 用法：source .secrets/tokens.env && bash scripts/load-test.sh [N] [C] [出参目录]
set -u
N=${1:-100}; C=${2:-20}; OUT_DIR=${3:-docs/reports/load-test-$(date +%Y%m%d)}
: "${INTERNAL_TOKEN:?请先 source .secrets/tokens.env}"
URL=${CHAT_URL:-http://127.0.0.1:19020/api/ai/chat}
mkdir -p "$OUT_DIR"
CSV="$OUT_DIR/raw.csv"; ERR="$OUT_DIR/errors.log"
echo 'id,http_code,time_total_s' > "$CSV"; : > "$ERR"
START=$(date +%s)
seq 1 "$N" | xargs -P "$C" -I{} bash -c '
  id={}; url="$0"; csv="$1"; err="$2"
  body='"'"'{"message":"一句话说明死信队列的作用"}'"'"'
  start_ns=$(date +%s%N)
  out=$(curl -sS --max-time 120 -o /tmp/load-test-body-$$ -w "%{http_code}" \
    -H "Content-Type: application/json" -H "X-Internal-Call: $INTERNAL_TOKEN" -H "X-User-Id: 99" \
    -X POST "$url" -d "$body" 2>>"$err")
  rc=$?
  end_ns=$(date +%s%N)
  t=$(awk -v a="$start_ns" -v b="$end_ns" "BEGIN{printf \"%.3f\", (b-a)/1000000000}")
  [ $rc -ne 0 ] && out="000"
  echo "$id,${out:-000},$t" >> "$csv"
  if [ "${out:-000}" != "200" ]; then
    echo "$id rc=$rc http=${out:-000} elapsed=${t}s body=$(head -c 200 /tmp/load-test-body-$$ 2>/dev/null)" >> "$err"
  fi
  rm -f /tmp/load-test-body-$$' "$URL" "$CSV" "$ERR"
DUR=$(( $(date +%s) - START ))
python3 - "$CSV" "$OUT_DIR" "$N" "$C" "$DUR" <<'PY'
import csv, sys, statistics
csv_path, out_dir, n, c, dur = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5])
rows=[]
with open(csv_path) as f:
    for r in csv.DictReader(f):
        try: rows.append((int(r['http_code']), float(r['time_total_s'])))
        except Exception: pass
ok=[t for code,t in rows if code==200]
bad=[r for r in rows if r[0]!=200]
ok.sort()
def pct(p):
    if not ok: return 0.0
    k=min(len(ok)-1, int(round((p/100.0)*len(ok)+0.5))-1)
    return ok[max(k,0)]
summary=(f"N={n} C={c} ok={len(ok)} fail={len(bad)} duration={dur}s\n"
         f"P50={pct(50):.3f}s P95={pct(95):.3f}s P99={pct(99):.3f}s "
         f"mean={statistics.mean(ok):.3f}s max={max(ok) if ok else 0:.3f}s\n")
if ok:
    summary += f"throughput={len(ok)/dur:.2f} req/s\n"
open(f"{out_dir}/summary.txt","w").write(summary)
print(summary)
PY
echo "CSV: $CSV"; echo "错误样本(前5):"; head -5 "$ERR" 2>/dev/null
