#!/bin/bash
set -euo pipefail

TARGET=${TEMPORAL_TARGET:-127.0.0.1:7233}
TASK_QUEUE=${TEMPORAL_TASK_QUEUE:-approval-restart-task-queue}
WORKFLOW_ID=${1:-approval-restart-demo}
RUN_ID=${2:-run-restart-demo}
PAYLOAD=${3:-payload-restart}

APP_DIR="/data/workspace/my-xhs/my-xhs-ai-app"
cd /data/workspace/my-xhs
mvn -q -pl my-xhs-ai-app dependency:build-classpath -Dmdep.outputFile=/tmp/temporal.cp >/dev/null
JAVA_CP="$APP_DIR/target/classes:$(cat /tmp/temporal.cp)"

echo "=== Temporal Restart Demo ==="
echo "target=$TARGET taskQueue=$TASK_QUEUE workflowId=$WORKFLOW_ID"

echo "[1] 请确保 Temporal dev server 已启动（默认 127.0.0.1:7233）"

echo "[2] 启动 worker..."
java -cp "$JAVA_CP" com.myxhs.ai.app.labs.temporal.TemporalWorkerBootstrap "$TARGET" "$TASK_QUEUE" >/tmp/temporal-worker.log 2>&1 &
WORKER_PID=$!
echo "worker pid=$WORKER_PID"
sleep 3

echo "[3] start workflow..."
TEMPORAL_TARGET="$TARGET" TEMPORAL_TASK_QUEUE="$TASK_QUEUE" \
java -cp "$JAVA_CP" com.myxhs.ai.app.labs.temporal.TemporalApprovalClient start "$WORKFLOW_ID" "$RUN_ID" "$PAYLOAD"

echo "[4] query state (expect WAITING_APPROVAL)..."
TEMPORAL_TARGET="$TARGET" TEMPORAL_TASK_QUEUE="$TASK_QUEUE" \
java -cp "$JAVA_CP" com.myxhs.ai.app.labs.temporal.TemporalApprovalClient query "$WORKFLOW_ID"

echo "[5] kill worker..."
kill "$WORKER_PID"
sleep 2

echo "[6] restart worker..."
java -cp "$JAVA_CP" com.myxhs.ai.app.labs.temporal.TemporalWorkerBootstrap "$TARGET" "$TASK_QUEUE" >/tmp/temporal-worker.log 2>&1 &
WORKER_PID=$!
echo "worker pid=$WORKER_PID"
sleep 3

echo "[7] approve workflow..."
TEMPORAL_TARGET="$TARGET" TEMPORAL_TASK_QUEUE="$TASK_QUEUE" \
java -cp "$JAVA_CP" com.myxhs.ai.app.labs.temporal.TemporalApprovalClient approve "$WORKFLOW_ID" tester restart-demo

echo "[8] get result..."
TEMPORAL_TARGET="$TARGET" TEMPORAL_TASK_QUEUE="$TASK_QUEUE" \
java -cp "$JAVA_CP" com.myxhs.ai.app.labs.temporal.TemporalApprovalClient result "$WORKFLOW_ID"

echo "[9] shutdown worker"
kill "$WORKER_PID" || true

echo "=== Temporal Restart Demo Done ==="
