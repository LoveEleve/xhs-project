package com.myxhs.common.tx;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.Consumer;

/**
 * 事务钩子工具 — 简化事务后回调操作
 *
 * 使用方式：
 * TransactionHook.afterCommit(() -> cacheHelper.delayDoubleDelete(key));
 * TransactionHook.afterCompletion(status -> log.info("事务完成: status={}", status));
 */
@Slf4j
public final class TransactionHook {

    private TransactionHook() {}

    /** 事务提交后执行 */
    public static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("[TransactionHook] 无活跃事务，直接执行 afterCommit 回调");
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            action.run();
                        } catch (Exception e) {
                            log.error("[TransactionHook] afterCommit 回调异常", e);
                        }
                    }
                });
    }

    /** 事务完成后执行（无论提交还是回滚） */
    public static void afterCompletion(Consumer<Integer> action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("[TransactionHook] 无活跃事务，直接执行 afterCompletion 回调");
            action.accept(TransactionSynchronization.STATUS_UNKNOWN);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        try {
                            action.accept(status);
                        } catch (Exception e) {
                            log.error("[TransactionHook] afterCompletion 回调异常", e);
                        }
                    }
                });
    }

    /** 事务回滚后执行 */
    public static void afterRollback(Runnable action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("[TransactionHook] 无活跃事务，跳过 afterRollback 回调");
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                            try {
                                action.run();
                            } catch (Exception e) {
                                log.error("[TransactionHook] afterRollback 回调异常", e);
                            }
                        }
                    }
                });
    }
}
