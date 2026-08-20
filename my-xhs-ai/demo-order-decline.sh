#!/bin/bash
# Demo 2：订单量下降归因
# 场景：Agent 自动查询订单量 → 对比基线 → 定位漏斗断点 → 输出归因
# 前置条件：AI App 运行中

AI_APP="http://21.214.97.212:19020"

echo "=== Demo 2: 订单量下降归因 ==="
echo ""

# 1. 提交诊断任务
echo "[1] 提交诊断任务..."
RESULT=$(curl -s -X POST "$AI_APP/api/runs" \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: demo-user' \
  -d '{"message": "为什么最近一周订单量下降了？帮我定位漏斗断点"}')

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
echo "=== Demo 2 完成 ==="
