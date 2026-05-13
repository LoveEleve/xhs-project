package com.myxhs.common.exception;

import com.myxhs.common.response.ResultCode;
import lombok.Getter;

/**
 * 业务异常
 * <p>
 * 所有可预期的业务异常都应抛出此异常，由 {@link GlobalExceptionHandler} 统一捕获并返回友好提示。
 * 不可预期的系统异常（如 NPE、DB 连接失败）不应使用此类，而是让全局异常处理器兜底。
 * </p>
 */
@Getter
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 错误码 */
    private final int code;

    /** 错误码枚举（可选，用于日志追踪） */
    private final ResultCode resultCode;

    public BizException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
        this.resultCode = resultCode;
    }

    public BizException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
        this.resultCode = resultCode;
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
        this.resultCode = null;
    }

    public BizException(ResultCode resultCode, Throwable cause) {
        super(resultCode.getMessage(), cause);
        this.code = resultCode.getCode();
        this.resultCode = resultCode;
    }
}
