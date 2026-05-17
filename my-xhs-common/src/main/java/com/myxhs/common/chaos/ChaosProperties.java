package com.myxhs.common.chaos;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 混沌工程配置
 * <p>
 * 通过 Nacos 配置中心动态控制故障注入，支持热更新（@RefreshScope）。
 * 生产环境默认关闭，演练时通过修改 Nacos 配置开启。
 * </p>
 * <p>
 * 配置示例（application.yml 或 Nacos）：
 * <pre>
 * chaos:
 *   enabled: false          # 总开关（默认关闭）
 *   faults:
 *     redis-delay:          # 故障名称
 *       type: DELAY         # 故障类型：DELAY / EXCEPTION / RETURN_NULL
 *       target: "RedisOperator.*"  # 目标方法（类名.方法名，支持通配符）
 *       delayMs: 3000       # 延迟毫秒数（DELAY 类型）
 *       probability: 100    # 触发概率（0~100）
 *       active: true        # 此故障是否激活
 *     db-exception:
 *       type: EXCEPTION
 *       target: "UserService.getUserById"
 *       exceptionClass: "java.sql.SQLException"
 *       exceptionMessage: "模拟数据库连接超时"
 *       probability: 50
 *       active: true
 * </pre>
 * </p>
 * <p>
 * 双层混沌工程架构：
 * 1. 基础设施级（ChaosBlade）：网络丢包/延迟、进程 kill、CPU 满载、磁盘 IO 等
 * 2. 应用级（本配置）：方法延迟、异常注入、返回 null，精确到具体方法和概率
 *
 * 两者互补：ChaosBlade 模拟真实故障（Redis 断连、MQ 宕机），
 * 应用级注入验证代码层面的容错逻辑（降级、熔断、超时处理）。
 * </p>
 */
@Data
@ConfigurationProperties(prefix = "chaos")
public class ChaosProperties {

    /** 总开关（默认关闭） */
    private boolean enabled = false;

    /** 故障配置列表 */
    private Map<String, FaultConfig> faults = new HashMap<>();

    /**
     * 单个故障配置
     */
    @Data
    public static class FaultConfig {

        /** 故障类型 */
        private FaultType type = FaultType.DELAY;

        /**
         * 目标方法匹配模式
         * 格式：类名.方法名（支持 * 通配符）
         * 示例：
         * - "RedisOperator.*" — RedisOperator 的所有方法
         * - "UserService.getUserById" — 精确匹配
         * - "*.create*" — 所有类的 create 开头方法
         */
        private String target = "";

        /** 延迟毫秒数（DELAY 类型使用） */
        private long delayMs = 1000;

        /** 异常类名（EXCEPTION 类型使用） */
        private String exceptionClass = "java.lang.RuntimeException";

        /** 异常消息（EXCEPTION 类型使用） */
        private String exceptionMessage = "混沌工程故障注入";

        /** 触发概率（0~100，100 表示必定触发） */
        private int probability = 100;

        /** 此故障是否激活 */
        private boolean active = true;
    }

    /**
     * 故障类型枚举
     */
    public enum FaultType {
        /** 方法延迟 */
        DELAY,
        /** 抛出异常 */
        EXCEPTION,
        /** 返回 null */
        RETURN_NULL
    }
}
