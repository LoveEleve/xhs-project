package com.myxhs.common.exception;

/**
 * SQL 守护阻断异常
 * <p>
 * 当 {@code myxhs.sql-guard.block-on-open=true} 且命中断名单的 SQL
 * 处于熔断冷却期内被真正阻断时抛出。
 * </p>
 * <p>
 * 注意：默认配置（block-on-open=false）下不会抛出本异常——行为保持
 * "只判定、只告警"。该异常是显式开启保护后的 fail-fast 语义，
 * 上层可映射为 503/降级响应。
 * </p>
 */
public class SqlGuardBlockedException extends RuntimeException {

    private final String fingerprint;

    public SqlGuardBlockedException(String fingerprint, String message) {
        super(message);
        this.fingerprint = fingerprint;
    }

    public String getFingerprint() {
        return fingerprint;
    }
}
