package com.myxhs.common.tcc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * TCC Fence 服务 — 解决 TCC 模式的空回滚、悬挂、幂等问题
 *
 * 设计参考 Alibaba Seata TCC Fence 机制：
 * - 幂等：INSERT fence 记录时用主键冲突保证 Try 只执行一次
 * - 空回滚：Cancel 时先 INSERT fence 记录（状态=CANCELLED），Try 还没执行则插入成功但什么都不做
 * - 悬挂：Try 时检查 fence 表是否有 Cancel 记录，有则拒绝执行
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TccFenceService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Try 阶段：挂 Fence，保证幂等 + 防悬挂
     * @return true-可以执行Try，false-被悬挂拒绝
     */
    public boolean tryFence(String xid, Long branchId, String actionName) {
        try {
            jdbcTemplate.update(
                "INSERT INTO t_tcc_fence (xid, branch_id, action_name, status) VALUES (?, ?, ?, 1)",
                xid, branchId, actionName
            );
            log.debug("[TCC Fence] Try fence inserted: xid={}, branchId={}", xid, branchId);
            return true;
        } catch (DuplicateKeyException e) {
            // 已存在记录，查询状态
            Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM t_tcc_fence WHERE xid = ? AND branch_id = ?",
                Integer.class, xid, branchId
            );
            if (status != null && status == 3) {
                // 悬挂：Cancel 比 Try 先到，拒绝 Try
                log.warn("[TCC Fence] 悬挂检测，拒绝Try: xid={}, branchId={}, status={}", xid, branchId, status);
                return false;
            }
            // 幂等：Try 已执行过
            log.debug("[TCC Fence] Try 幂等放行: xid={}, branchId={}", xid, branchId);
            return true;
        }
    }

    /**
     * Confirm 阶段：更新 Fence 状态为已确认
     */
    public void confirmFence(String xid, Long branchId) {
        int affected = jdbcTemplate.update(
            "UPDATE t_tcc_fence SET status = 2 WHERE xid = ? AND branch_id = ?",
            xid, branchId
        );
        if (affected == 0) {
            log.error("[TCC Fence] Confirm时Fence记录不存在: xid={}, branchId={}", xid, branchId);
        } else {
            log.debug("[TCC Fence] Confirm fence updated: xid={}, branchId={}", xid, branchId);
        }
    }

    /**
     * Cancel 阶段：空回滚处理
     * 如果 Try 还没执行，插入一条 CANCELLED 状态的记录
     */
    public void cancelFence(String xid, Long branchId, String actionName) {
        try {
            jdbcTemplate.update(
                "INSERT INTO t_tcc_fence (xid, branch_id, action_name, status) VALUES (?, ?, ?, 3)",
                xid, branchId, actionName
            );
            log.debug("[TCC Fence] Cancel fence inserted (空回滚): xid={}, branchId={}", xid, branchId);
        } catch (DuplicateKeyException e) {
            // 已存在记录，更新状态
            jdbcTemplate.update(
                "UPDATE t_tcc_fence SET status = 3 WHERE xid = ? AND branch_id = ?",
                xid, branchId
            );
            log.debug("[TCC Fence] Cancel fence updated: xid={}, branchId={}", xid, branchId);
        }
    }
}
