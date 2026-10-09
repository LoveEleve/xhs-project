package com.myxhs.common.exception;

import com.myxhs.common.response.R;
import com.myxhs.common.response.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器
 * <p>
 * 统一捕获所有异常，返回标准化的 {@link R} 响应。
 * 异常分为五层（优先级从高到低）：
 * 1. 业务异常（BizException）：可预期，WARN 日志，返回业务错误码，不告警
 * 2. 远程调用异常（RemoteException）：Feign 调用失败，ERROR 日志，P1 告警
 * 3. 系统异常（SysException）：显式抛出的系统错误，ERROR 日志，P1 告警
 * 4. 参数校验异常：Spring Validation 抛出，返回 400
 * 5. 未知异常（Exception）：兜底，ERROR 日志，P0 告警
 * </p>
 */
@Slf4j
@RestControllerAdvice
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class GlobalExceptionHandler {

    // ==================== 业务异常 ====================

    /**
     * 业务异常处理
     * 可预期的业务错误，返回具体的错误码和消息
     */
    @ExceptionHandler(BizException.class)
    public org.springframework.http.ResponseEntity<R<Void>> handleBizException(BizException e, HttpServletRequest request) {
        log.warn("[业务异常] URI={}, code={}, message={}", request.getRequestURI(), e.getCode(), e.getMessage());
        // A3：限流语义规范化——业务限流返回 HTTP 429（与其他限流入口一致，便于监控/客户端识别）
        org.springframework.http.HttpStatus status =
                e.getCode() == ResultCode.RATE_LIMIT_REJECT.getCode()
                        ? org.springframework.http.HttpStatus.TOO_MANY_REQUESTS
                        : org.springframework.http.HttpStatus.OK;
        return org.springframework.http.ResponseEntity.status(status).body(R.fail(e.getCode(), e.getMessage()));
    }

    // ==================== 系统异常 ====================

    /**
     * 系统异常处理
     * <p>
     * 系统级不可预期异常（DB 连接失败、Redis 超时等），
     * 记录 ERROR 日志 + 完整堆栈，生产环境应触发 P1 告警。
     * 返回通用错误提示，不暴露内部细节。
     * </p>
     */
    /**
     * Redis 不可用（连接/超时）：统一 503 + 可重试语义
     * <p>2026-09-23：RedisOperator 超时改为抛 RedisUnavailableException 后必须有明确出口，
     * 否则会落入兜底 500（客户端无法区分"重试有效"）。</p>
     */
    @ExceptionHandler(RedisUnavailableException.class)
    public org.springframework.http.ResponseEntity<R<Void>> handleRedisUnavailable(RedisUnavailableException e,
                                                                                  HttpServletRequest request) {
        log.error("[Redis不可用] URI={}, message={}", request.getRequestURI(), e.getMessage());
        return org.springframework.http.ResponseEntity
                .status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                .body(R.fail(ResultCode.SERVICE_UNAVAILABLE.getCode(), "缓存/认证依赖暂不可用，请稍后重试"));
    }

    @ExceptionHandler(SysException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleSysException(SysException e, HttpServletRequest request) {
        log.error("[系统异常] URI={}, code={}, message={}",
                request.getRequestURI(), e.getCode(), e.getMessage(), e);
        return R.fail(e.getCode(), "系统繁忙，请稍后重试");
    }

    // ==================== 远程调用异常 ====================

    /**
     * 远程调用异常处理
     * <p>
     * Feign 调用下游服务失败时抛出，记录目标服务、接口路径、HTTP 状态码。
     * 生产环境应触发 P1 告警，便于快速定位调用链问题。
     * </p>
     */
    @ExceptionHandler(RemoteException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public R<Void> handleRemoteException(RemoteException e, HttpServletRequest request) {
        log.error("[远程调用异常] URI={}, targetService={}, requestPath={}, httpStatus={}, message={}",
                request.getRequestURI(), e.getTargetService(), e.getRequestPath(),
                e.getHttpStatus(), e.getMessage(), e);
        return R.fail(ResultCode.SERVICE_UNAVAILABLE, "服务暂时不可用，请稍后重试");
    }

    // ==================== 参数校验异常 ====================

    /**
     * @RequestBody 参数校验失败（@Valid + @RequestBody）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("[参数校验失败] {}", message);
        return R.fail(ResultCode.PARAM_INVALID, message);
    }

    /**
     * T-021: 缺少必填请求头（如 X-User-Id 被 GatewayAuthTrustFilter 剥离后）→ 400 而非 500
     */
    @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleMissingRequestHeader(org.springframework.web.bind.MissingRequestHeaderException e,
                                               HttpServletRequest request) {
        log.warn("[缺请求头] name={}, uri={}", e.getHeaderName(), request.getRequestURI());
        return R.fail(ResultCode.PARAM_INVALID, "缺少必要请求头: " + e.getHeaderName());
    }

    /**
     * @RequestParam / @PathVariable 参数校验失败
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        log.warn("[参数约束违反] {}", message);
        return R.fail(ResultCode.PARAM_INVALID, message);
    }

    /**
     * 表单绑定异常
     */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleBindException(BindException e) {
        String message = e.getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("[绑定异常] {}", message);
        return R.fail(ResultCode.PARAM_INVALID, message);
    }

    /**
     * 缺少必要参数
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("[缺少参数] {}", e.getMessage());
        return R.fail(ResultCode.PARAM_MISSING, "缺少参数: " + e.getParameterName());
    }

    /**
     * T-037 修复（2026-08-13）：文件上传超限（multipart 解析层，先于 service 校验）
     * 原返回 500（无映射），统一为 40002 参数错误 + 明确文案
     */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("[上传超限] {}", e.getMessage());
        return R.fail(ResultCode.PARAM_INVALID, "文件大小不能超过5MB");
    }

    /**
     * T-037/T-041 修复（2026-08-13）：multipart 解析失败（超限/格式错误）统一 40002
     */
    @ExceptionHandler(org.springframework.web.multipart.MultipartException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleMultipart(MultipartException e) {
        log.warn("[multipart解析失败] {}", e.getMessage());
        return R.fail(ResultCode.PARAM_INVALID, "上传文件解析失败或超过大小限制");
    }

    /**
     * 参数类型不匹配
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("[参数类型错误] name={}, value={}", e.getName(), e.getValue());
        return R.fail(ResultCode.PARAM_TYPE_ERROR, "参数类型错误: " + e.getName());
    }

    /**
     * 请求体不可读（JSON 格式错误等）
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("[请求体不可读] {}", e.getMessage());
        return R.fail(ResultCode.PARAM_INVALID, "请求体格式错误");
    }

    // ==================== HTTP 异常 ====================

    /**
     * 请求方法不支持
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public R<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("[方法不支持] method={}", e.getMethod());
        return R.fail(ResultCode.METHOD_NOT_ALLOWED);
    }

    /**
     * 资源不存在（Spring 6.x NoResourceFoundException）
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public R<Void> handleNoResourceFound(NoResourceFoundException e) {
        return R.fail(ResultCode.NOT_FOUND);
    }

    // ==================== 兜底异常 ====================

    /**
     * 所有未捕获的异常兜底处理
     * <p>
     * 记录完整堆栈日志，返回通用错误提示（不暴露内部细节）。
     * 这是最后一道防线，生产环境应触发 P0 告警。
     * </p>
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleException(Exception e, HttpServletRequest request) {
        log.error("[未知异常] URI={}, exceptionClass={}, message={}",
                request.getRequestURI(), e.getClass().getSimpleName(), e.getMessage(), e);
        return R.fail(ResultCode.INTERNAL_ERROR);
    }
}
