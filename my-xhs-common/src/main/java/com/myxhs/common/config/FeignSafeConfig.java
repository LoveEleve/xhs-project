package com.myxhs.common.config;

import com.myxhs.common.exception.RemoteException;
import feign.Response;
import feign.Retryer;
import feign.codec.ErrorDecoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 安全配置
 * <p>
 * 核心策略：
 * 1. 全局关闭重试（NEVER_RETRY）— 非幂等接口重试会导致重复操作（如重复下单）
 * 2. 自定义 ErrorDecoder — 将 Feign 错误转换为 RemoteException，携带目标服务信息
 * </p>
 * <p>
 * 为什么全局关闭重试？
 * - Feign 默认重试策略会在连接超时时自动重试
 * - 下单接口重试 → 创建两个订单 → 资损事故
 * - 幂等接口（如查询）即使不重试也不会有问题（客户端可以手动重试）
 * - 如果某些幂等接口确实需要重试，在该 FeignClient 上单独配置 Retryer
 * </p>
 * <p>
 * 超时配置在各服务的 application.yml 中：
 * feign.client.config.default.connect-timeout=3000~5000
 * feign.client.config.default.read-timeout=5000~10000
 * </p>
 */
@Slf4j
@Configuration
@ConditionalOnClass(name = "feign.Retryer")
public class FeignSafeConfig {

    /**
     * 全局关闭 Feign 重试
     * <p>
     * 生产环境绝对不能对非幂等接口重试。
     * 如果某个 FeignClient 的所有接口都是幂等的，可以在该 Client 的 configuration 中覆盖此 Bean。
     * </p>
     */
    @Bean
    public Retryer feignRetryer() {
        return Retryer.NEVER_RETRY;
    }

    /**
     * 自定义错误解码器
     * <p>
     * 将 Feign 的 HTTP 错误响应转换为 {@link RemoteException}，
     * 携带目标服务名、请求路径、HTTP 状态码等信息，便于定位调用链问题。
     * </p>
     */
    @Bean
    public ErrorDecoder feignErrorDecoder() {
        return new RemoteCallErrorDecoder();
    }

    /**
     * 远程调用错误解码器
     * <p>
     * 处理下游服务返回的非 2xx 响应：
     * - 4xx：客户端错误（参数错误等），不应重试
     * - 5xx：服务端错误，记录日志 + 告警
     * - 503：服务不可用，可能是下游正在重启
     * </p>
     */
    @Slf4j
    static class RemoteCallErrorDecoder implements ErrorDecoder {

        private final ErrorDecoder defaultDecoder = new Default();

        @Override
        public Exception decode(String methodKey, Response response) {
            String url = response.request().url();
            int status = response.status();

            // 从 methodKey 提取服务名（格式：ServiceName#method(ParamType)）
            String serviceName = extractServiceName(methodKey);
            String path = extractPath(url);

            // 5xx 错误 → RemoteException（触发告警）
            if (status >= 500) {
                String message = String.format("下游服务异常: %s %s → HTTP %d", serviceName, path, status);
                log.error("[Feign 调用失败] methodKey={}, url={}, status={}", methodKey, url, status);
                return new RemoteException(serviceName, path, status, message);
            }

            // 4xx 错误 → 使用默认解码器（FeignException）
            // 不转换为 RemoteException，因为 4xx 通常是调用方的问题
            log.warn("[Feign 调用 4xx] methodKey={}, url={}, status={}", methodKey, url, status);
            return defaultDecoder.decode(methodKey, response);
        }

        private String extractServiceName(String methodKey) {
            // methodKey 格式：com.myxhs.home.feign.ContentClient#getNoteDetail(Long)
            // 提取类名部分
            int hashIndex = methodKey.indexOf('#');
            if (hashIndex > 0) {
                String className = methodKey.substring(0, hashIndex);
                int lastDot = className.lastIndexOf('.');
                return lastDot > 0 ? className.substring(lastDot + 1) : className;
            }
            return methodKey;
        }

        private String extractPath(String url) {
            try {
                java.net.URI uri = java.net.URI.create(url);
                return uri.getPath();
            } catch (Exception e) {
                return url;
            }
        }
    }
}
