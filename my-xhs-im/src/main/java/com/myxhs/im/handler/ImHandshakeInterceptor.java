package com.myxhs.im.handler;

import com.myxhs.common.util.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WebSocket 握手拦截器
 * <p>
 * 从 URL 参数中提取 ticket（短期令牌），验证后将 userId 放入 attributes。
 * ticket 由 REST 接口 POST /api/im/ws/ticket 签发（JWT，5 分钟有效期），
 * 避免在 WebSocket URL 中暴露长期 Token。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImHandshakeInterceptor implements HandshakeInterceptor {

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${jwt.secret:MyXhs@2026#JwtSecretKey!ForTokenSign}")
    private String jwtSecret;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            String ticket = servletRequest.getServletRequest().getParameter("ticket");
            if (ticket == null || ticket.isBlank()) {
                log.warn("[IM握手] 缺少 ticket 参数");
                return false;
            }

            try {
                // ticket 是一个短期 JWT（5 分钟有效期），解析出 userId
                Claims claims = JwtUtil.parseToken(ticket, jwtSecret);
                // 校验 token 类型必须是 ws_ticket（防止用 access token 冒充）
                String tokenType = claims.get("type", String.class);
                if (!"ws_ticket".equals(tokenType)) {
                    log.warn("[IM握手] ticket 类型错误: expected=ws_ticket, actual={}", tokenType);
                    return false;
                }
                // 一次性消费：ticket 会出现在 WS URL（进访问日志），GETDEL 保证用过即废（防重放）
                String jti = claims.getId();
                if (jti == null) {
                    log.warn("[IM握手] ticket 无效: 缺少 jti");
                    return false;
                }
                String storedUserId = stringRedisTemplate.opsForValue().getAndDelete("myxhs:im:ticket:" + jti);
                if (storedUserId == null) {
                    log.warn("[IM握手] ticket 已被使用或不存在(疑似重放): jti={}", jti);
                    return false;
                }
                String subject = claims.getSubject();
                if (subject == null || !subject.equals(storedUserId)) {
                    log.warn("[IM握手] ticket 无效: subject 缺失或与登记不一致");
                    return false;
                }
                Long userId = Long.valueOf(subject);
                attributes.put("userId", userId);

                // 链路追踪：优先 HTTP 头 X-Trace-Id，其次 URL 参数 traceId，最后生成
                String traceId = servletRequest.getServletRequest().getHeader("X-Trace-Id");
                if (traceId == null || traceId.isBlank()) {
                    traceId = servletRequest.getServletRequest().getParameter("traceId");
                }
                if (traceId == null || traceId.isBlank()) {
                    traceId = java.util.UUID.randomUUID().toString().replace("-", "");
                }
                attributes.put("traceId", traceId);
                return true;
            } catch (Exception e) {
                log.warn("[IM握手] ticket 解析失败: {}", e.getMessage());
                return false;
            }
        }
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 握手后无需额外处理
    }
}
