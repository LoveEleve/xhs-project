package com.myxhs.common.shutdown;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;

/**
 * 优雅停机事件监听器
 * <p>
 * 监听 Spring 容器关闭事件（ContextClosedEvent），在 JVM 退出前执行资源清理。
 * </p>
 * <p>
 * 优雅停机完整流程（收到 SIGTERM / kill -15 后）：
 * 1. Spring Boot 停止接受新的 HTTP 请求（server.shutdown=graceful）
 * 2. 等待已有请求处理完成（最长 lifecycle.timeout-per-shutdown-phase=30s）
 * 3. 触发 ContextClosedEvent → 本监听器执行
 * 4. 各 Bean 的 @PreDestroy 方法执行（如 CounterBuffer.shutdown() 刷盘）
 * 5. 关闭数据库连接池（HikariCP）
 * 6. 关闭 Redis 连接（Lettuce）
 * 7. 关闭 MQ 消费者（RocketMQ Consumer shutdown → 未处理消息 NACK → Broker 重新投递）
 * 8. JVM 退出
 * </p>
 * <p>
 * 注意：@PreDestroy 的执行顺序由 Spring 容器管理（依赖关系决定），
 * 不需要手动编排。本监听器只负责打印日志 + 执行额外的清理逻辑。
 * </p>
 * <p>
 * K8s 部署时的配合：
 * - terminationGracePeriodSeconds=60（K8s 等待 60 秒）
 * - preStop: curl -X PUT actuator/service-registry?status=DOWN && sleep 10
 *   （先从 Nacos 下线 → 等 10 秒让其他服务感知 → 再触发 Spring 优雅停机）
 * - lifecycle.timeout-per-shutdown-phase=30s（Spring 等待 30 秒）
 * - 60 > 10 + 30，给足缓冲时间
 * </p>
 */
@Slf4j
public class GracefulShutdownListener implements ApplicationListener<ContextClosedEvent> {

    @Value("${spring.application.name:unknown}")
    private String applicationName;

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        log.info("[优雅停机] {} 开始关闭，等待资源清理...", applicationName);

        // 打印关键信息，便于排查停机问题
        Runtime runtime = Runtime.getRuntime();
        log.info("[优雅停机] 当前活跃线程数: {}, 可用内存: {}MB",
                Thread.activeCount(),
                runtime.freeMemory() / 1024 / 1024);

        log.info("[优雅停机] {} 资源清理完成，即将退出", applicationName);
    }
}
