package com.myxhs.common.shutdown;

/**
 * 优雅停机钩子接口
 * <p>
 * 各模块实现此接口并注册为 Spring Bean，即可在停机时自动执行清理逻辑。
 * 典型使用场景：
 * - Counter 模块：flush 内存 Buffer 到数据库
 * - IM 模块：关闭 WebSocket 连接并通知客户端
 * - Notification 模块：关闭 SSE 连接
 * </p>
 */
public interface GracefulShutdownHook {

    /**
     * 停机时执行的清理逻辑
     * <p>
     * 注意：
     * 1. 此方法应尽快执行完成（建议 5s 内）
     * 2. 不应抛出异常（异常会被捕获并记录日志，不影响其他 Hook 执行）
     * 3. 此时 Spring 容器仍然可用，可以注入其他 Bean
     * </p>
     */
    void onShutdown();
}
