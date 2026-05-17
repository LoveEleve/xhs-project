package com.myxhs.common.exception;

import lombok.Getter;

/**
 * 远程调用异常
 * <p>
 * Feign 调用下游服务失败时抛出，携带目标服务名、接口路径、耗时等信息，
 * 便于定位调用链问题。
 * </p>
 * <p>
 * 使用场景：
 * - Feign ErrorDecoder 解码非 2xx 响应时
 * - Feign 连接超时 / 读超时时
 * - 下游服务返回系统错误时
 * </p>
 * <p>
 * 由 {@link GlobalExceptionHandler} 统一捕获，记录 ERROR 日志 + P1 告警。
 * </p>
 */
@Getter
public class RemoteException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 目标服务名（如 my-xhs-inventory） */
    private final String targetService;

    /** 请求路径（如 /api/inventory/deduct） */
    private final String requestPath;

    /** HTTP 状态码（超时时为 -1） */
    private final int httpStatus;

    public RemoteException(String targetService, String requestPath, int httpStatus, String message) {
        super(message);
        this.targetService = targetService;
        this.requestPath = requestPath;
        this.httpStatus = httpStatus;
    }

    public RemoteException(String targetService, String requestPath, int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.targetService = targetService;
        this.requestPath = requestPath;
        this.httpStatus = httpStatus;
    }

    /**
     * 连接超时 / 读超时场景
     */
    public RemoteException(String targetService, String requestPath, Throwable cause) {
        super("远程调用超时: " + targetService + requestPath, cause);
        this.targetService = targetService;
        this.requestPath = requestPath;
        this.httpStatus = -1;
    }
}
