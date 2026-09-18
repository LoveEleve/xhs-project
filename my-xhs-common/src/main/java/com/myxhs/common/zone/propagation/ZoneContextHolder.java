package com.myxhs.common.zone.propagation;

/**
 * 请求级 Zone 上下文（ThreadLocal）：保存上游传播过来的 X-Zone。
 * <p>请求结束必须清理，避免线程池串读。</p>
 *
 * @since 1.0.0
 */
public final class ZoneContextHolder {

    public static final String ZONE_HEADER = "X-Zone";

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private ZoneContextHolder() {
    }

    public static void set(String zone) {
        if (zone == null || zone.isBlank()) {
            HOLDER.remove();
        } else {
            HOLDER.set(zone.trim());
        }
    }

    public static String get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
