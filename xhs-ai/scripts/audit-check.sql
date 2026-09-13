-- 审计与审批一致性检查（红队/运维自查）；只读
-- 用法: mysql -uroot -p'...' < scripts/audit-check.sql

-- 1) 审批状态分布（pending 长期不处理 = 超时 fail-closed 未实现的已知缺口）
SELECT status, COUNT(*) AS cnt FROM my_xhs_ai.ai_approval GROUP BY status;

-- 2) 变更动作链：每次 effect.start 都应有对应的审批通过（once/always）记录
SELECT
  (SELECT COUNT(*) FROM my_xhs_ai.ai_audit WHERE action='dlq.redeliver.effect.start') AS effect_starts,
  (SELECT COUNT(*) FROM my_xhs_ai.ai_audit WHERE action='approval.once' OR action='approval.always') AS approvals_granted;

-- 3) 核验结论分布（重投后是否再次进入 DLQ）
SELECT
  SUM(result LIKE '%verified_no_reentry%') AS verified_ok,
  SUM(result LIKE '%reentered_dlq%') AS reentered,
  SUM(result LIKE '%pending_verification%') AS pending_verify
FROM my_xhs_ai.ai_approval WHERE tool='dlq.redeliver';

-- 4) 审计 traceId 覆盖（跨线程贯通率）
SELECT COUNT(*) AS total, SUM(trace_id IS NOT NULL AND trace_id<>'') AS with_trace
FROM my_xhs_ai.ai_audit WHERE created_at > NOW() - INTERVAL 1 DAY;

-- 5) 敏感信息泄漏抽查（审计参数中不应出现明文密钥形态）
SELECT COUNT(*) AS suspicious FROM my_xhs_ai.ai_audit
WHERE params LIKE '%sk-%' OR params LIKE '%ark-%' OR params LIKE '%glsa_%' OR params LIKE '%Xhs@2026#%';

-- 6) 会话与消息归属一致性（同一 session 不应跨 user）
SELECT COUNT(*) AS cross_user_sessions FROM (
  SELECT session_id FROM my_xhs_ai.ai_session GROUP BY session_id HAVING COUNT(DISTINCT user_id) > 1
) t;

-- 7) 高风险工具调用是否出现在审计（白名单外的工具不应有执行记录）
SELECT action, COUNT(*) FROM my_xhs_ai.ai_audit
WHERE action LIKE '%delete_series%' OR action LIKE '%clean_tombstones%' OR action LIKE '%reload%'
GROUP BY action;
