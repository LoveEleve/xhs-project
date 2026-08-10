package com.myxhs.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * SSE Ticket 服务
 * <p>
 * 为什么需要 Ticket？
 * SSE 使用浏览器 EventSource API 建立连接，该 API 不支持自定义 Header。
 * 如果直接在 URL 中传 JWT Token，Token 会被记录在：
 * ① 浏览器历史 ② Nginx/Gateway 访问日志 ③ CDN/代理日志
 * 等于把凭证泄漏给了第三方系统。
 * </p>
 * <p>
 * 解决方案（两步法）：
 * 1. 先用 HTTP POST（Header 携带 Token）获取短期 Ticket（30 秒有效，一次性）
 * 2. 用 Ticket 建立 SSE 连接（Ticket 即使泄露也无法重用）
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SseTicketService {

    private final StringRedisTemplate stringRedisTemplate;

    private static final String TICKET_KEY_PREFIX = "myxhs:notification:sse:ticket:";
    private static final Duration TICKET_TTL = Duration.ofSeconds(30);

    /**
     * 生成 SSE 连接 Ticket
     *
     * @param userId 用户 ID
     * @return ticket 字符串（30 秒有效，一次性）
     */
    public String generateTicket(Long userId) {
        String ticket = UUID.randomUUID().toString().replace("-", "");
        stringRedisTemplate.opsForValue().set(
                TICKET_KEY_PREFIX + ticket, String.valueOf(userId), TICKET_TTL);
        log.debug("[SSE] 生成Ticket: userId={}, ticket={}", userId, ticket);
        return ticket;
    }

    /**
     * 验证并消费 Ticket（一次性使用）
     *
     * @param ticket Ticket 字符串
     * @return userId（验证成功），null（验证失败或已使用）
     */
    public Long validateAndConsume(String ticket) {
        if (ticket == null || ticket.isEmpty()) {
            return null;
        }
        String key = TICKET_KEY_PREFIX + ticket;
        // GET + DELETE 原子操作（getAndDelete）
        String userIdStr = stringRedisTemplate.opsForValue().getAndDelete(key);
        if (userIdStr == null) {
            return null; // Ticket 不存在或已使用
        }
        try { return Long.parseLong(userIdStr); } catch (NumberFormatException e) { log.warn("[SSE] Redis脏值: userIdStr={}", userIdStr, e); return null; }
    }
}
