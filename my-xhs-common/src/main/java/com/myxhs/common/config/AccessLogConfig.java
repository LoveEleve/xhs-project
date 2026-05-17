package com.myxhs.common.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 请求访问日志配置
 * <p>
 * 记录每个 HTTP 请求的关键信息：
 * - 请求方法 + URI
 * - 响应状态码
 * - 请求耗时（毫秒）
 * - 客户端 IP
 * </p>
 * <p>
 * 用途：
 * 1. 快速定位慢接口（rt > 500ms 用 WARN 级别）
 * 2. 统计接口 QPS / P95 / P99
 * 3. 配合 TraceId 做全链路排查
 * </p>
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AccessLogConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AccessLogConfig.class);

    /** 慢请求阈值（毫秒），超过此值用 WARN 级别记录 */
    private static final long SLOW_REQUEST_THRESHOLD_MS = 500;

    /** 请求开始时间的 Attribute Key */
    private static final String START_TIME_ATTR = "accessLog.startTime";

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AccessLogInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns("/actuator/**") // 排除健康检查等端点
                .order(-90); // 在 TraceId 拦截器之后执行（TraceId order=-100）
    }

    /**
     * 请求耗时拦截器
     */
    private static class AccessLogInterceptor implements HandlerInterceptor {

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            request.setAttribute(START_TIME_ATTR, System.currentTimeMillis());
            return true;
        }

        @Override
        public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                    Object handler, Exception ex) {
            Long startTime = (Long) request.getAttribute(START_TIME_ATTR);
            if (startTime == null) {
                return;
            }

            long rt = System.currentTimeMillis() - startTime;
            int status = response.getStatus();
            String method = request.getMethod();
            String uri = request.getRequestURI();
            String clientIp = getClientIp(request);

            // 格式：[ACCESS] GET /api/user/profile, status=200, rt=56ms, ip=192.168.1.1
            String logMsg = String.format("[ACCESS] %s %s, status=%d, rt=%dms, ip=%s",
                    method, uri, status, rt, clientIp);

            if (ex != null) {
                log.error("{}, exception={}", logMsg, ex.getClass().getSimpleName());
            } else if (rt > SLOW_REQUEST_THRESHOLD_MS) {
                log.warn("{} [SLOW]", logMsg);
            } else if (status >= 400) {
                log.warn("{}", logMsg);
            } else {
                log.info("{}", logMsg);
            }
        }

        /**
         * 获取客户端真实 IP（支持代理场景）
         */
        private String getClientIp(HttpServletRequest request) {
            String ip = request.getHeader("X-Forwarded-For");
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                // X-Forwarded-For 可能包含多个 IP，取第一个
                return ip.split(",")[0].trim();
            }
            ip = request.getHeader("X-Real-IP");
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                return ip;
            }
            return request.getRemoteAddr();
        }
    }
}
