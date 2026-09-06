package com.myxhs.common.tcc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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
 *
 * 【关键约束】调用方必须根据返回值决定是否执行业务操作：
 * 幂等命中（重复请求）时必须跳过业务，否则会产生双冻结/双扣减/双解冻。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TccFenceService {

    /**
     * 懒解析：@ConditionalOnBean(JdbcTemplate.class) 在组件扫描期评估，
     * 此时 @Configuration 的 @Bean 方法尚未注册，条件恒为 false（Spring 已知陷阱）。
     * 改为运行时解析，无 JdbcTemplate 时在使用处 fail-closed。
     */
    private final ObjectProvider<JdbcTemplate> jdbcTemplateProvider;

    private JdbcTemplate jdbc() {
        JdbcTemplate template = jdbcTemplateProvider.getIfAvailable();
        if (template == null) {
            throw new IllegalStateException("JdbcTemplate 不可用，TCC Fence 无法工作，请检查数据源配置");
        }
        return template;
    }

    /** Try 阶段结果 */
    public enum TryFenceResult {
        /** 首次 Try，必须执行业务冻结 */
        FIRST,
        /** 重复 Try（幂等命中），必须跳过业务（否则双冻结） */
        DUPLICATE,
        /** 悬挂（Cancel 比 Try 先到），拒绝执行 */
        SUSPENDED
    }

    /** Confirm 阶段结果 */
    public enum ConfirmFenceResult {
        /** 状态 1→2 转换成功，必须执行业务确认 */
        EXECUTE,
        /** 重复 Confirm（幂等命中），必须跳过业务（否则双扣冻结库存） */
        SKIP_DUPLICATE,
        /** 异常（无 Try 记录或已 Cancel），拒绝执行 */
        REJECTED
    }

    /** Cancel 阶段结果 */
    public enum CancelFenceResult {
        /** 状态 1→3 转换成功，必须执行业务解冻 */
        EXECUTE,
        /** 空回滚（Try 从未执行）或重复 Cancel，必须跳过业务（否则双解冻/凭空加库存） */
        SKIP,
        /** 已 Confirm 后收到 Cancel（非法），拒绝执行并告警 */
        REJECTED_CONFIRMED
    }

    /**
     * Try 阶段：挂 Fence，保证幂等 + 防悬挂
     */
    public TryFenceResult tryFence(String xid, Long branchId, String actionName) {
        try {
            jdbc().update(
                "INSERT INTO t_tcc_fence (xid, branch_id, action_name, status) VALUES (?, ?, ?, 1)",
                xid, branchId, actionName
            );
            log.debug("[TCC Fence] Try fence inserted: xid={}, branchId={}", xid, branchId);
            return TryFenceResult.FIRST;
        } catch (DuplicateKeyException e) {
            // 已存在记录，查询状态
            Integer status = jdbc().queryForObject(
                "SELECT status FROM t_tcc_fence WHERE xid = ? AND branch_id = ?",
                Integer.class, xid, branchId
            );
            if (status != null && status == 3) {
                // 悬挂：Cancel 比 Try 先到，拒绝 Try
                log.warn("[TCC Fence] 悬挂检测，拒绝Try: xid={}, branchId={}, status={}", xid, branchId, status);
                return TryFenceResult.SUSPENDED;
            }
            // 幂等：Try 已执行过——调用方必须跳过业务，否则双冻结
            log.debug("[TCC Fence] Try 幂等命中(跳过业务): xid={}, branchId={}", xid, branchId);
            return TryFenceResult.DUPLICATE;
        }
    }

    /**
     * Confirm 阶段：更新 Fence 状态为已确认（仅允许从 Try 状态转换）
     */
    public ConfirmFenceResult confirmFence(String xid, Long branchId) {
        Integer currentStatus = jdbc().queryForObject(
            "SELECT status FROM t_tcc_fence WHERE xid = ? AND branch_id = ?",
            Integer.class, xid, branchId
        );
        if (currentStatus == null) {
            log.error("[TCC Fence] Confirm时Fence记录不存在(无Try): xid={}, branchId={}", xid, branchId);
            return ConfirmFenceResult.REJECTED;
        }
        if (currentStatus == 2) {
            log.debug("[TCC Fence] Confirm 幂等命中(跳过业务): xid={}, branchId={}", xid, branchId);
            return ConfirmFenceResult.SKIP_DUPLICATE;
        }
        if (currentStatus == 3) {
            log.error("[TCC Fence] Confirm 拒绝：当前已是已取消状态: xid={}, branchId={}", xid, branchId);
            return ConfirmFenceResult.REJECTED;
        }
        // status=1（Try），执行状态转换（乐观锁防并发双 Confirm）
        int affected = jdbc().update(
            "UPDATE t_tcc_fence SET status = 2 WHERE xid = ? AND branch_id = ? AND status = 1",
            xid, branchId
        );
        if (affected > 0) {
            log.debug("[TCC Fence] Confirm fence updated: xid={}, branchId={}", xid, branchId);
            return ConfirmFenceResult.EXECUTE;
        }
        // 并发场景：另一个 Confirm 已完成状态转换 → 本次为重复，跳过业务
        log.debug("[TCC Fence] Confirm 并发转换失败(视为重复,跳过业务): xid={}, branchId={}", xid, branchId);
        return ConfirmFenceResult.SKIP_DUPLICATE;
    }

    /**
     * Cancel 阶段：空回滚处理
     * 如果 Try 还没执行，插入一条 CANCELLED 状态的记录（空回滚，无业务可解冻）
     */
    public CancelFenceResult cancelFence(String xid, Long branchId, String actionName) {
        try {
            jdbc().update(
                "INSERT INTO t_tcc_fence (xid, branch_id, action_name, status) VALUES (?, ?, ?, 3)",
                xid, branchId, actionName
            );
            // 空回滚：Try 从未执行，无冻结库存可解冻，跳过业务
            log.debug("[TCC Fence] Cancel 空回滚(Try未执行,跳过业务): xid={}, branchId={}", xid, branchId);
            return CancelFenceResult.SKIP;
        } catch (DuplicateKeyException e) {
            Integer currentStatus = jdbc().queryForObject(
                "SELECT status FROM t_tcc_fence WHERE xid = ? AND branch_id = ?",
                Integer.class, xid, branchId
            );
            if (currentStatus == null) {
                log.warn("[TCC Fence] Cancel 并发异常：记录被删除: xid={}, branchId={}", xid, branchId);
                return CancelFenceResult.SKIP;
            }
            if (currentStatus == 3) {
                log.debug("[TCC Fence] Cancel 幂等命中(跳过业务): xid={}, branchId={}", xid, branchId);
                return CancelFenceResult.SKIP;
            }
            if (currentStatus == 2) {
                log.error("[TCC Fence] Cancel 拒绝：当前已是已确认状态，不可取消: xid={}, branchId={}", xid, branchId);
                return CancelFenceResult.REJECTED_CONFIRMED;
            }
            // status=1（Try），执行状态转换（乐观锁防并发双 Cancel）
            int affected = jdbc().update(
                "UPDATE t_tcc_fence SET status = 3 WHERE xid = ? AND branch_id = ? AND status = 1",
                xid, branchId
            );
            if (affected > 0) {
                log.debug("[TCC Fence] Cancel fence updated: xid={}, branchId={}", xid, branchId);
                return CancelFenceResult.EXECUTE;
            }
            // 并发场景：另一个 Cancel 已完成 → 本次为重复，跳过业务
            log.debug("[TCC Fence] Cancel 并发转换失败(视为重复,跳过业务): xid={}, branchId={}", xid, branchId);
            return CancelFenceResult.SKIP;
        }
    }
}
