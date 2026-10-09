package com.myxhs.common.web;

/**
 * 可信客户端 IP 解析（跨服务统一口径）
 * <p>
 * 可信来源优先级：X-Real-IP（网关单值注入并覆盖客户端值）→ X-Forwarded-For 最后一段
 * （网关追加的真实连接 IP 在末尾，客户端伪造段只在前面）→ 连接 IP。
 * 历史问题：各服务各自解析，有的取 XFF 第一段（可伪造 → 可锁任意账号/绕过 IP 限流），
 * 有的取整串，匿名请求常退化为 "unknown"（全体共用限流/防刷维度）。
 * </p>
 *
 * @since 2026-09-23
 */
public final class ClientIpResolver {

    private ClientIpResolver() {
    }

    /**
     * @param realIp        X-Real-IP 头（网关注入，可信）
     * @param forwardedFor  X-Forwarded-For 头（取最后一段）
     * @param remoteAddr    连接 IP（兜底）
     * @return 可信客户端 IP；全部不可用时返回 null
     */
    public static String resolve(String realIp, String forwardedFor, String remoteAddr) {
        if (isUsable(realIp)) {
            return realIp.trim();
        }
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String[] parts = forwardedFor.split(",");
            for (int i = parts.length - 1; i >= 0; i--) {
                String part = parts[i].trim();
                if (isUsable(part)) {
                    return part;
                }
            }
        }
        return isUsable(remoteAddr) ? remoteAddr.trim() : null;
    }

    private static boolean isUsable(String value) {
        return value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value.trim());
    }
}
