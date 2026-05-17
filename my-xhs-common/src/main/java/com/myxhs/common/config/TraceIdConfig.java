package com.myxhs.common.config;

import com.myxhs.common.trace.TraceContext;
import com.myxhs.common.trace.TraceContextHolder;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;

/**
 * 全链路流量染色 + TraceId 配置
 * <p>
 * 在每个请求的生命周期中：
 * 1. 从 HTTP Header 恢复完整的 TraceContext（6 个染色标记）
 * 2. 注入 TraceId 到 SLF4J MDC（日志自动携带）
 * 3. 设置 TraceId 到响应 Header（方便前端排查）
 * 4. 请求结束后清理 ThreadLocal + MDC（防止内存泄漏）
 * </p>
 * <p>
 * 染色标记来源：Gateway TrafficColoringFilter 注入到 HTTP Header，
 * 下游服务通过此拦截器从 Header 恢复到 ThreadLocal。
 * </p>
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class TraceIdConfig implements WebMvcConfigurer {

    /** Gateway/上游传递 TraceId 的 Header 名称 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String GRAY_TAG_HEADER = "X-Gray-Tag";
    public static final String API_VERSION_HEADER = "X-Api-Version";
    public static final String AB_GROUP_HEADER = "X-AB-Group";
    public static final String PRESSURE_TEST_HEADER = "X-Pressure-Test";

    /** MDC 中 TraceId 的 Key */
    public static final String TRACE_ID_MDC_KEY = "traceId";

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TraceContextInterceptor())
                .addPathPatterns("/**")
                .order(-100); // 优先级最高，确保在其他拦截器之前执行
    }

    /**
     * 全链路染色上下文拦截器
     * <p>
     * 请求进入时：从 HTTP Header 恢复 TraceContext + 设置 MDC
     * 请求结束后：清理 ThreadLocal + MDC
     * </p>
     */
    private static class TraceContextInterceptor implements HandlerInterceptor {

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            // 1. 从 HTTP Header 恢复完整的 TraceContext
            TraceContext ctx = new TraceContext();

            // TraceId：优先从 Header 获取（Gateway 传递），没有则自动生成
            String traceId = request.getHeader(TRACE_ID_HEADER);
            if (traceId == null || traceId.isEmpty()) {
                traceId = generateTraceId();
            }
            ctx.setTraceId(traceId);

            // 其他染色标记：从 Header 获取（可能为 null，表示未染色）
            ctx.setUserId(request.getHeader(USER_ID_HEADER));
            ctx.setGrayTag(request.getHeader(GRAY_TAG_HEADER));
            ctx.setApiVersion(request.getHeader(API_VERSION_HEADER));
            ctx.setAbGroup(request.getHeader(AB_GROUP_HEADER));
            ctx.setPressureTest(request.getHeader(PRESSURE_TEST_HEADER));

            // 2. 设置到 ThreadLocal
            TraceContextHolder.set(ctx);

            // 3. 注入 MDC（日志自动携带 traceId）
            MDC.put(TRACE_ID_MDC_KEY, traceId);

            // 4. 设置到响应 Header（方便前端/调用方排查）
            response.setHeader(TRACE_ID_HEADER, traceId);

            return true;
        }

        @Override
        public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                    Object handler, Exception ex) {
            // 必须清理，防止线程池复用时上下文串联
            TraceContextHolder.clear();
            MDC.remove(TRACE_ID_MDC_KEY);
        }

        /**
         * 生成 TraceId（32位无横线 UUID）
         */
        private String generateTraceId() {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }
}
