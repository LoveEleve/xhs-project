package com.myxhs.common.exception;

import com.myxhs.common.response.ResultCode;
import lombok.Getter;

/**
 * 系统异常
 * <p>
 * 系统级不可预期异常（NPE、DB 连接失败、Redis 超时等），
 * 由 {@link GlobalExceptionHandler} 统一捕获，记录 ERROR 日志并触发告警。
 * </p>
 * <p>
 * 与 {@link BizException} 的区别：
 * - BizException：可预期的业务错误（库存不足、用户不存在），WARN 级别，不告警
 * - SysException：不可预期的系统错误（DB 挂了、OOM），ERROR 级别，必须告警
 * </p>
 */
@Getter
public class SysException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 错误码 */
    private final int code;

    /** 错误码枚举 */
    private final ResultCode resultCode;

    public SysException(String message) {
        super(message);
        this.code = ResultCode.INTERNAL_ERROR.getCode();
        this.resultCode = ResultCode.INTERNAL_ERROR;
    }

    public SysException(String message, Throwable cause) {
        super(message, cause);
        this.code = ResultCode.INTERNAL_ERROR.getCode();
        this.resultCode = ResultCode.INTERNAL_ERROR;
    }

    public SysException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
        this.resultCode = resultCode;
    }

    public SysException(ResultCode resultCode, Throwable cause) {
        super(resultCode.getMessage(), cause);
        this.code = resultCode.getCode();
        this.resultCode = resultCode;
    }

    public SysException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
        this.resultCode = resultCode;
    }
}
