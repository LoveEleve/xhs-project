package com.myxhs.common.config;

import com.myxhs.common.util.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置
 * <p>
 * 注册 UserContext 拦截器，从 Gateway 注入的 X-User-Id Header 中提取用户 ID，
 * 存入 ThreadLocal，业务代码通过 UserContext.getUserId() 获取。
 * </p>
 * <p>
 * 注意：仅在 Servlet（WebMVC）环境下生效，Gateway（WebFlux）环境不加载。
 * </p>
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new UserContextInterceptor())
                .addPathPatterns("/**");
    }

    /**
     * 用户上下文拦截器
     * 从 X-User-Id Header 提取用户 ID → 存入 ThreadLocal → 请求结束后清理
     */
    private static class UserContextInterceptor implements HandlerInterceptor {

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            String userIdStr = request.getHeader("X-User-Id");
            if (userIdStr != null && !userIdStr.isEmpty()) {
                try {
                    UserContext.setUserId(Long.parseLong(userIdStr));
                } catch (NumberFormatException e) {
                    // userId 格式错误，忽略（不影响请求继续）
                }
            }
            return true;
        }

        @Override
        public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                    Object handler, Exception ex) {
            // 必须清理 ThreadLocal，防止内存泄漏（线程池复用场景）
            UserContext.clear();
        }
    }
}
