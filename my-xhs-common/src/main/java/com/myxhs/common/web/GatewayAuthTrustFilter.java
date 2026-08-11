package com.myxhs.common.web;

import com.myxhs.common.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 端口信任模型过滤器（P1-3）
 * <p>
 * 背景：服务端口(19001+)直连可达时，客户端可任意伪造 X-User-Id 头实现水平越权；
 * 而服务端逐端点手工校验 X-Internal-Call 易漏配。本过滤器统一收紧信任边界：
 * </p>
 * <ol>
 *   <li><b>内部调用</b>（X-Internal-Call == myxhs.internal.token）：信任其 X-User-Id（Feign 内部调用）。</li>
 *   <li><b>网关透传的认证请求</b>（Authorization Bearer + 有效 access JWT）：
 *       用 <b>JWT subject 覆盖</b>请求中的 X-User-Id —— 即使攻击者直连端口伪造 X-User-Id，
 *       也会被其自己的 JWT 身份覆盖，无法越权。</li>
 *   <li><b>其他</b>（无有效 JWT 且非内部调用）：<b>剥离</b> X-User-Id（fail-closed），
 *       依赖 X-User-Id 的用户端点将因缺少该头而拒绝。</li>
 * </ol>
 * <p>
 * 仅 Servlet 环境生效（Gateway 是 WebFlux，自动排除）。
 * 各服务需配置 jwt.secret（与 gateway/user 相同），否则 JWT 分支不生效（退化为仅剥离伪造头）。
 * </p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class GatewayAuthTrustFilter extends OncePerRequestFilter {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String INTERNAL_CALL_HEADER = "X-Internal-Call";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    @Value("${jwt.secret:}")
    private String jwtSecret;

    @Value("${myxhs.internal.token:}")
    private String internalToken;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 1. 内部调用：信任（Feign 内部请求，X-User-Id 由调用方携带）
        String internalCall = request.getHeader(INTERNAL_CALL_HEADER);
        if (internalToken != null && !internalToken.isEmpty() && internalToken.equals(internalCall)) {
            chain.doFilter(request, response);
            return;
        }

        // 2. 尝试从网关透传的 JWT 解析真实 userId
        Long jwtUserId = resolveJwtUserId(request);
        if (jwtUserId != null) {
            // 以 JWT subject 覆盖 X-User-Id（防直连端口伪造越权）
            chain.doFilter(new HeaderOverwriteWrapper(request, USER_ID_HEADER, String.valueOf(jwtUserId)), response);
            return;
        }

        // 3. 无有效 JWT 且非内部调用：剥离伪造的 X-User-Id（fail-closed）
        if (request.getHeader(USER_ID_HEADER) != null) {
            log.warn("[安全] 剥离未认证请求的 X-User-Id(疑似伪造): uri={}, xuid={}",
                    request.getRequestURI(), request.getHeader(USER_ID_HEADER));
            chain.doFilter(new HeaderRemoveWrapper(request, USER_ID_HEADER), response);
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * 解析 JWT 获取真实 userId（仅接受 access 类型 token）
     */
    private Long resolveJwtUserId(HttpServletRequest request) {
        String auth = request.getHeader(AUTHORIZATION_HEADER);
        if (auth == null || !auth.startsWith(BEARER_PREFIX) || jwtSecret == null || jwtSecret.isEmpty()) {
            return null;
        }
        try {
            Claims claims = JwtUtil.parseToken(auth.substring(BEARER_PREFIX.length()), jwtSecret);
            if (!"access".equals(claims.get("type", String.class))) {
                return null;
            }
            return Long.valueOf(claims.getSubject());
        } catch (Exception e) {
            log.debug("[安全] JWT 解析失败(忽略): {}", e.getMessage());
            return null;
        }
    }

    /** 覆盖指定请求头的包装器 */
    private static class HeaderOverwriteWrapper extends HttpServletRequestWrapper {
        private final String name;
        private final String value;

        HeaderOverwriteWrapper(HttpServletRequest request, String name, String value) {
            super(request);
            this.name = name;
            this.value = value;
        }

        @Override
        public String getHeader(String name) {
            if (this.name.equalsIgnoreCase(name)) {
                return value;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (this.name.equalsIgnoreCase(name)) {
                return Collections.enumeration(List.of(value));
            }
            return super.getHeaders(name);
        }
    }

    /** 移除指定请求头的包装器 */
    private static class HeaderRemoveWrapper extends HttpServletRequestWrapper {
        private final String name;

        HeaderRemoveWrapper(HttpServletRequest request, String name) {
            super(request);
            this.name = name;
        }

        @Override
        public String getHeader(String name) {
            if (this.name.equalsIgnoreCase(name)) {
                return null;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (this.name.equalsIgnoreCase(name)) {
                return Collections.emptyEnumeration();
            }
            return super.getHeaders(name);
        }
    }
}
