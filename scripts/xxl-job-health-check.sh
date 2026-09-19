#!/bin/bash
# XXL-Job 健康巡检：统计近 15 分钟调度/执行失败，输出 Prometheus textfile 指标
# 背景：xxl_job_log 有失败记录，但无指标/告警（2026-09-19 对账复核发现的观测缺口）
# 由 /etc/cron.d/myxhs-xxl-job-health 每 5 分钟执行；node-exporter textfile 采集到 xxl_job_* 指标
set -uo pipefail

OUT_DIR="/data/rocketmq-textfile"
OUT_FILE="$OUT_DIR/xxl_job_health.prom"
MYSQL_CMD="docker exec -i my-xhs-mysql mysql -uroot -pXhs@2026#MySQL -N -B"

mkdir -p "$OUT_DIR"

SQL="SELECT
  COALESCE(SUM(CASE WHEN trigger_code != 200 THEN 1 ELSE 0 END),0) AS trigger_fail,
  COALESCE(SUM(CASE WHEN trigger_code = 200 AND handle_code != 200 THEN 1 ELSE 0 END),0) AS handle_fail
FROM xxl_job.xxl_job_log
WHERE trigger_time >= NOW() - INTERVAL 15 MINUTE;"

RESULT=$($MYSQL_CMD -e "$SQL" 2>/dev/null | tail -1)

if [ -z "$RESULT" ]; then
  cat > "$OUT_FILE" <<EOF
# HELP xxl_job_health_ok XXL-Job 巡检可用性（1=正常，0=巡检失败：MySQL 不可达）
# TYPE xxl_job_health_ok gauge
xxl_job_health_ok 0
EOF
  echo "xxl-job health check FAILED: MySQL unreachable"
  exit 1
fi

TRIGGER_FAIL=$(echo "$RESULT" | awk '{print $1}')
HANDLE_FAIL=$(echo "$RESULT" | awk '{print $2}')

cat > "$OUT_FILE" <<EOF
# HELP xxl_job_trigger_failures_15m 近15分钟调度触发失败数（trigger_code!=200）
# TYPE xxl_job_trigger_failures_15m gauge
xxl_job_trigger_failures_15m ${TRIGGER_FAIL:-0}
# HELP xxl_job_handle_failures_15m 近15分钟执行失败数（trigger=200 且 handle!=200）
# TYPE xxl_job_handle_failures_15m gauge
xxl_job_handle_failures_15m ${HANDLE_FAIL:-0}
# HELP xxl_job_health_ok XXL-Job 巡检可用性（1=正常，0=巡检失败）
# TYPE xxl_job_health_ok gauge
xxl_job_health_ok 1
EOF

echo "xxl-job health: trigger_fail=${TRIGGER_FAIL:-0}, handle_fail=${HANDLE_FAIL:-0}"
