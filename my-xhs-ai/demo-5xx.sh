#!/bin/bash
# Demo 3：服务 5xx 排障
# 场景：Agent 查询 HTTP 5xx 错误 → 关联日志 → 定位根因
# 前置条件：AI App 运行中

AI_APP="http://21.214.97.212:19020"

echo "=== Demo 3: 服务 5xx 排障 ==="
echo ""

# 1. 提交诊断任务
echo "[1] 提交诊断任务..."
RESULT=$(curl -s -X POST "$AI_APP/api/runs" \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: demo-user' \
  -d '{"message": "为什么最近有 HTTP 5xx 错误？帮我查一下具体原因"}')

RUN_ID=$(echo $RESULT | python3 -c "import sys,json; print(json.load(sys.stdin)['runId'])")
echo "    runId: $RUN_ID"

# 2. 轮询结果
echo "[2] 等待 Agent 执行..."
for i in $(seq 1 30); do
    sleep 5
    STATUS=$(curl -s "$AI_APP/api/runs/$RUN_ID" | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])")
    echo "    [$i] status: $STATUS"
    
    if [ "$STATUS" = "SUCCEEDED" ] || [ "$STATUS" = "COMPLETED" ]; then
        echo "[3] 完成！最终答案："
        curl -s "$AI_APP/api/runs/$RUN_ID" | python3 -c "
import sys,json
r=json.load(sys.stdin)
print(r.get('finalAnswer','')[:800])
print()
print('--- 证据链 ---')
for ev in r.get('evidence',[]):
    print(f'  [{ev}]')
"
        break
    fi
done

echo ""
echo "=== Demo 3 完成 ==="
