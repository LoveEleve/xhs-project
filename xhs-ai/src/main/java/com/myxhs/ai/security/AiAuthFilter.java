package com.myxhs.ai.security;

import com.myxhs.ai.web.TraceIdFilter;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;

/**
 * xhs-ai 鉴权过滤器（M1.6，对齐平台 {@code GatewayAuthTrustFilter} 信任模型）
 * <ol>
 *   <li>内部调用（X-Internal-Call = INTERNAL_TOKEN）：放行（Feign/运维）。</li>
 *   <li>管理调用（X-Admin-Call = ADMIN_TOKEN）：放行（运维直连排障）。</li>
 *   <li>有效 access JWT：以 JWT subject 覆盖 X-User-Id（防直连端口伪造）。</li>
 *   <li>其余：401 fail-closed。</li>
 * </ol>
 * <p>放行路径仅限健康/指标端点（自愈与 Prometheus 抓取）。</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AiAuthFilter extends OncePerRequestFilter {

    private static final Set<String> OPEN_PATHS = Set.of("/actuator/health");
    private static final String METRICS_PATH = "/actuator/prometheus";
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String INTERNAL_CALL_HEADER = "X-Internal-Call";
    private static final String ADMIN_CALL_HEADER = "X-Admin-Call";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    @Value("${jwt.secret:}")
    private String jwtSecret;

    @Value("${myxhs.internal.token:}")
    private String internalToken;

    @Value("${myxhs.admin.token:}")
    private String adminToken;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (OPEN_PATHS.contains(path)) {
            chain.doFilter(request, response);
            return;
        }
        if (METRICS_PATH.equals(path) && isPrivateAddress(request.getRemoteAddr())) {
            chain.doFilter(request, response);
            return;
        }

        if (matches(internalToken, request.getHeader(INTERNAL_CALL_HEADER))) {
            chain.doFilter(request, response);
            return;
        }
        if (matches(adminToken, request.getHeader(ADMIN_CALL_HEADER))) {
            chain.doFilter(request, response);
            return;
        }

        Long jwtUserId = resolveJwtUserId(request);
        if (jwtUserId != null) {
            chain.doFilter(new HeaderOverwriteWrapper(request, USER_ID_HEADER, String.valueOf(jwtUserId)), response);
            return;
        }

        log.warn("[安全] 未认证请求被拒: uri={}, method={}", path, request.getMethod());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        response.getWriter().write("{\"code\":401,\"message\":\"未认证或凭证无效\""
                + ",\"traceId\":\"" + (traceId == null ? "" : traceId) + "\",\"success\":false}");
    }

    private boolean matches(String expected, String actual) {
        if (expected == null || expected.isEmpty() || actual == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                actual.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 指标端点仅允许本机抓取（Prometheus host 网络模式抓 127.0.0.1） */
    private boolean isPrivateAddress(String remoteAddr) {
        if (remoteAddr == null) {
            return false;
        }
        try {
            return java.net.InetAddress.getByName(remoteAddr).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }

    /** 解析 JWT（仅接受 access 类型），返回 subject userId */
    private Long resolveJwtUserId(HttpServletRequest request) {
        String auth = request.getHeader(AUTHORIZATION_HEADER);
        if (auth == null || !auth.startsWith(BEARER_PREFIX) || jwtSecret == null || jwtSecret.isEmpty()) {
            return null;
        }
        try {
            SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(auth.substring(BEARER_PREFIX.length())).getPayload();
            if (!"access".equals(claims.get("type", String.class))) {
                return null;
            }
            return Long.valueOf(claims.getSubject());
        } catch (Exception e) {
            log.debug("[安全] JWT 解析失败(忽略): {}", e.getMessage());
            return null;
        }
    }

    /** 覆盖指定请求头（防伪造 X-User-Id） */
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
            return this.name.equalsIgnoreCase(name) ? value : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return this.name.equalsIgnoreCase(name)
                    ? Collections.enumeration(List.of(value))
                    : super.getHeaders(name);
        }
    }
}
