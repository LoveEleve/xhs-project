package com.myxhs.common.tx;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计日志服务模板 — 使用 REQUIRES_NEW 保证审计日志不受外层事务回滚影响
 *
 * 使用方式：继承此类，实现 doRecordAuditLog 方法
 */
@Slf4j
public abstract class AuditLogService {

    /**
     * 记录审计日志（独立事务，不受外层事务回滚影响）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public void recordAuditLog(Long userId, String action, String detail) {
        try {
            doRecordAuditLog(userId, action, detail);
        } catch (Exception e) {
            log.error("[AuditLog] 审计日志记录失败: userId={}, action={}", userId, action, e);
            // 审计日志失败不影响主业务流程
        }
    }

    /**
     * 子类实现：实际记录审计日志
     */
    protected abstract void doRecordAuditLog(Long userId, String action, String detail);
}
