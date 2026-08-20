package com.myxhs.gateway.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理器
 * <p>
 * 职责：捕获 Gateway 处理请求过程中的所有未处理异常，返回统一格式的错误响应。
 * <p>
 * 为什么需要全局异常处理？
 * - 默认情况下，Spring Cloud Gateway 异常会返回 Whitelabel Error Page（HTML 格式）
 * - 前端/客户端期望 JSON 格式的错误响应
 * - 需要统一错误码格式，与鉴权失败（401）、限流（429）的响应格式保持一致
 * <p>
 * 异常分类处理策略：
 * - 4xx 客户端错误：直接返回对应状态码 + 错误信息
 * - 5xx 服务端错误：返回 500 + 通用错误信息（不暴露内部细节）
 * - 连接超时/拒绝：返回 503 Service Unavailable
 * - 其他异常：兜底返回 500
 * <p>
 * 安全考虑：
 * - 5xx 错误不暴露堆栈信息，防止信息泄露
 * - 记录完整的异常堆栈到日志，方便排查
 * - 429 状态码特殊处理（被 Sentinel 限流的请求不会走到这里，
 *   但如果有其他限流组件触发了 429，也统一格式化）
 * <p>
 * 设计决策：
 * - 实现 ErrorWebExceptionHandler 而非 @ControllerAdvice
 *   原因：Gateway 基于 WebFlux，不使用 WebMVC 的 @ControllerAdvice
 * - 使用 @Order 设置最高优先级，确保在默认异常处理器之前执行
 * - 异常日志只记录 traceId 和 path，不记录完整堆栈（堆栈在 DEBUG 级别记录）
 */
@Slf4j
@Component
@Order(-1)
public class GlobalExceptionHandler implements ErrorWebExceptionHandler {

    private final ObjectMapper objectMapper;

    public GlobalExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();

        // 如果响应已提交（如下游已开始返回数据），则不再写入
        if (response.isCommitted()) {
            log.warn("[Gateway-异常] 响应已提交, 无法写入错误响应, path={}",
                    exchange.getRequest().getURI().getPath(), ex);
            return Mono.empty();
        }

        // 确定状态码
        HttpStatus status = determineHttpStatus(ex);

        // 构建统一错误响应
        String path = exchange.getRequest().getURI().getPath();
        String traceId = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
        String message = determineMessage(ex, status);

        // 记录异常日志（包含 traceId 方便全链路排查）
        if (status.is5xxServerError()) {
            log.error("[Gateway-异常] 服务端错误, status={}, path={}, traceId={}",
                    status.value(), path, traceId, ex);
        } else {
            log.warn("[Gateway-异常] 客户端错误, status={}, path={}, traceId={}, message={}",
                    status.value(), path, traceId, message);
        }

        // 设置响应头
        response.setStatusCode(status);
        response.getHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");

        // 构建响应体
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", status.value());
        result.put("message", message);
        result.put("data", null);

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(result);
        } catch (JsonProcessingException e) {
            bytes = ("{\"code\":" + status.value() + ",\"message\":\"" + message + "\",\"data\":null}")
                    .getBytes(StandardCharsets.UTF_8);
        }

        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    /**
     * 根据异常类型确定 HTTP 状态码
     */
    private HttpStatus determineHttpStatus(Throwable ex) {
        // 连接超时 / 连接拒绝 → 503
        if (ex instanceof java.net.ConnectException) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        if (ex instanceof java.util.concurrent.TimeoutException) {
            return HttpStatus.GATEWAY_TIMEOUT;
        }
        if (ex instanceof io.netty.channel.ConnectTimeoutException) {
            return HttpStatus.GATEWAY_TIMEOUT;
        }
        // Spring Cloud Gateway 路由相关异常
        if (ex instanceof org.springframework.cloud.gateway.support.NotFoundException) {
            return HttpStatus.NOT_FOUND;
        }
        // T-059（2026-08-14）：未知路径/静态资源未命中（WebFlux 版 NoResourceFoundException）
        // 未映射时落默认 500 → 扫描/探测流量污染 5xx 指标；应返回 404
        if (ex instanceof org.springframework.web.reactive.resource.NoResourceFoundException) {
            return HttpStatus.NOT_FOUND;
        }
        // 默认 500
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    /**
     * 根据异常类型和状态码确定错误信息
     * <p>
     * 安全原则：5xx 不暴露内部细节，4xx 可以返回具体的错误原因
     */
    private String determineMessage(Throwable ex, HttpStatus status) {
        if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            return "服务暂不可用，请稍后重试";
        }
        if (status == HttpStatus.GATEWAY_TIMEOUT) {
            return "请求超时，请稍后重试";
        }
        if (status == HttpStatus.NOT_FOUND) {
            return "请求的服务不存在";
        }
        if (status.is5xxServerError()) {
            // 5xx 错误不暴露内部异常信息
            return "服务器内部错误，请稍后重试";
        }
        // 4xx 可以返回异常信息
        return ex.getMessage() != null ? ex.getMessage() : "请求处理失败";
    }
}
