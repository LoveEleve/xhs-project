#!/bin/bash
# Demo 1：DLQ 死信诊断 + 重投
# 场景：Agent 自动查询死信消息 → 提取 ORIGIN_MESSAGE_ID → HITL 审批 → 重投 → CR_SUCCESS
# 前置条件：中间件机 21.130.247.89 可达，AI App 运行中

AI_APP="http://21.214.97.212:19020"

echo "=== Demo 1: DLQ 死信诊断 + 重投 ==="
echo ""

# 1. 提交诊断任务
echo "[1] 提交诊断任务..."
RESULT=$(curl -s -X POST "$AI_APP/api/runs" \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: demo-user' \
  -d '{"message": "帮我查一下 inventory-order-transaction-consumer-group 的死信消息，找到最近的那条，然后重投它"}')

RUN_ID=$(echo $RESULT | python3 -c "import sys,json; print(json.load(sys.stdin)['runId'])")
echo "    runId: $RUN_ID"

# 2. 轮询结果
echo "[2] 等待 Agent 执行..."
for i in $(seq 1 30); do
    sleep 5
    STATUS=$(curl -s "$AI_APP/api/runs/$RUN_ID" | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])")
    echo "    [$i] status: $STATUS"
    
    if [ "$STATUS" = "WAITING_APPROVAL" ]; then
        echo "[3] HITL 审批触发！执行 approve..."
        curl -s -X POST "$AI_APP/api/runs/$RUN_ID/approve" \
          -H 'Content-Type: application/json' \
          -d '{"decision":"approve","reason":"Demo E2E 验证","approver":"demo"}'
        echo ""
        echo "    已 approve，等待重投结果..."
    fi
    
    if [ "$STATUS" = "SUCCEEDED" ] || [ "$STATUS" = "COMPLETED" ]; then
        echo "[4] 完成！最终答案："
        curl -s "$AI_APP/api/runs/$RUN_ID" | python3 -c "
import sys,json
r=json.load(sys.stdin)
print(r.get('finalAnswer','')[:500])
"
        break
    fi
done

echo ""
echo "=== Demo 1 完成 ==="
