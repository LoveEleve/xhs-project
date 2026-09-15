#!/usr/bin/env bash
# 审计一致性门禁（只读断言）：敏感泄漏=0 / 跨用户会话=0 / effect 不超过审批数
set -u
MYSQL="docker exec my-xhs-mysql sh -c \"mysql -uroot -p'Xhs@2026#MySQL' -N -e"
q() { docker exec my-xhs-mysql sh -c "mysql -uroot -p'Xhs@2026#MySQL' -N -e \"$1\"" 2>/dev/null | tr -d '\r'; }
FAIL=0
SUS=$(q "SELECT COUNT(*) FROM my_xhs_ai.ai_audit WHERE params LIKE '%sk-%' OR params LIKE '%ark-%' OR params LIKE '%glsa_%' OR params LIKE '%Xhs@2026#%'")
CROSS=$(q "SELECT COUNT(*) FROM (SELECT session_id FROM my_xhs_ai.ai_session GROUP BY session_id HAVING COUNT(DISTINCT user_id)>1) t")
EFFECT=$(q "SELECT COUNT(*) FROM my_xhs_ai.ai_audit WHERE action='dlq.redeliver.effect.start'")
APPR=$(q "SELECT COUNT(*) FROM my_xhs_ai.ai_audit WHERE action IN ('approval.once','approval.always')")
echo "  敏感泄漏=$SUS 跨用户会话=$CROSS 重投执行=$EFFECT 审批通过=$APPR"
[ "${SUS:-1}" = "0" ] || { echo "  ✗ 审计中存在疑似明文密钥"; FAIL=1; }
[ "${CROSS:-1}" = "0" ] || { echo "  ✗ 存在跨用户会话"; FAIL=1; }
if [ "${EFFECT:-0}" -gt "${APPR:-0}" ]; then echo "  ✗ 存在未审批的执行"; FAIL=1; fi
[ "$FAIL" = "0" ] && echo "  ✓ 审计一致性通过"
exit $FAIL
