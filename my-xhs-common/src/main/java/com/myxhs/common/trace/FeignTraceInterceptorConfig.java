package com.myxhs.common.trace;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 调用全链路染色标记透传拦截器
 * <p>
 * 在 Feign 调用下游服务时，自动将当前线程 TraceContext 中的 6 个染色标记
 * 透传到下游 HTTP Header。确保跨服务调用链路中染色标记不断裂。
 * </p>
 * <p>
 * 透传策略：从 TraceContextHolder（ThreadLocal）读取，而非从 HttpServletRequest 读取。
 * 原因：MQ 消费者触发的 Feign 调用没有 HttpServletRequest，但有 TraceContext。
 * </p>
 */
@Configuration
@ConditionalOnClass(name = "feign.RequestInterceptor")
public class FeignTraceInterceptorConfig {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String GRAY_TAG_HEADER = "X-Gray-Tag";
    private static final String API_VERSION_HEADER = "X-Api-Version";
    private static final String AB_GROUP_HEADER = "X-AB-Group";
    private static final String PRESSURE_TEST_HEADER = "X-Pressure-Test";

    /**
     * 注册 Feign 请求拦截器
     * <p>
     * 从 TraceContextHolder 读取染色上下文，透传到 Feign 请求 Header。
     * 如果 TraceContext 为 null（如定时任务触发），则不透传。
     * </p>
     */
    @org.springframework.context.annotation.Bean
    public feign.RequestInterceptor traceIdFeignInterceptor() {
        return template -> {
            TraceContext ctx = TraceContextHolder.get();
            if (ctx == null) {
                return;
            }

            setHeaderIfPresent(template, TRACE_ID_HEADER, ctx.getTraceId());
            setHeaderIfPresent(template, USER_ID_HEADER, ctx.getUserId());
            setHeaderIfPresent(template, GRAY_TAG_HEADER, ctx.getGrayTag());
            setHeaderIfPresent(template, API_VERSION_HEADER, ctx.getApiVersion());
            setHeaderIfPresent(template, AB_GROUP_HEADER, ctx.getAbGroup());
            setHeaderIfPresent(template, PRESSURE_TEST_HEADER, ctx.getPressureTest());
        };
    }

    private void setHeaderIfPresent(feign.RequestTemplate template, String header, String value) {
        if (value != null && !value.isEmpty()) {
            template.header(header, value);
        }
    }
}
