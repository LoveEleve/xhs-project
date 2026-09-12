package com.myxhs.common.chaos;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 混沌工程 AOP 拦截器
 * <p>
 * 拦截所有 @Service / @Component Bean 的方法调用，
 * 根据 {@link ChaosProperties} 配置注入故障（延迟/异常/返回null）。
 * </p>
 * <p>
 * 性能影响：
 * - chaos.enabled=false 时，直接 proceed()，零开销
 * - chaos.enabled=true 时，遍历 faults 配置做字符串匹配，开销极小（微秒级）
 * </p>
 */
@Slf4j
@Aspect
@RequiredArgsConstructor
public class ChaosInterceptor {

    private final ChaosProperties chaosProperties;

    /**
     * 拦截所有 com.myxhs 包下的 Bean 方法
     * <p>
     * 只拦截 service/controller/mapper/listener 层，不拦截 config/aspect 等基础设施类。
     * </p>
     */
    @Around("execution(* com.myxhs..service..*(..)) || execution(* com.myxhs..controller..*(..)) || execution(* com.myxhs..mapper..*(..)) || execution(* com.myxhs..listener..*(..))")
    public Object intercept(ProceedingJoinPoint joinPoint) throws Throwable {
        // 总开关关闭时直接放行（零开销）
        if (!chaosProperties.isEnabled()) {
            return joinPoint.proceed();
        }

        String className = joinPoint.getTarget().getClass().getSimpleName();
        // MyBatis Mapper 是 JDK 动态代理（getTarget() 为 $ProxyN），需同时用接口声明类型匹配
        String declaringClassName = joinPoint.getSignature().getDeclaringType().getSimpleName();
        String methodName = joinPoint.getSignature().getName();
        String fullName = className + "." + methodName;
        String declaringFullName = declaringClassName + "." + methodName;

        // 遍历故障配置，查找匹配的故障
        for (var entry : chaosProperties.getFaults().entrySet()) {
            ChaosProperties.FaultConfig fault = entry.getValue();
            if (!fault.isActive()) {
                continue;
            }
            boolean targetMatched = matchTarget(fullName, fault.getTarget())
                    || matchTarget(declaringFullName, fault.getTarget());
            if (!targetMatched) {
                // MyBatis-Plus 继承方法（如 BaseMapper.insert）声明类型为 BaseMapper，
                // 需再按目标对象实现的接口名匹配（如 FollowMapper.insert）
                for (Class<?> itf : joinPoint.getTarget().getClass().getInterfaces()) {
                    if (matchTarget(itf.getSimpleName() + "." + methodName, fault.getTarget())) {
                        targetMatched = true;
                        break;
                    }
                }
            }
            if (!targetMatched) {
                continue;
            }
            // 概率判断
            if (fault.getProbability() < 100
                    && ThreadLocalRandom.current().nextInt(100) >= fault.getProbability()) {
                continue;
            }

            // 命中故障，执行注入
            return injectFault(joinPoint, entry.getKey(), fault, fullName);
        }

        return joinPoint.proceed();
    }

    /**
     * 注入故障
     */
    private Object injectFault(ProceedingJoinPoint joinPoint, String faultName,
                               ChaosProperties.FaultConfig fault, String fullName) throws Throwable {
        switch (fault.getType()) {
            case DELAY -> {
                log.warn("[混沌工程] 注入延迟: fault={}, target={}, delayMs={}",
                        faultName, fullName, fault.getDelayMs());
                Thread.sleep(fault.getDelayMs());
                return joinPoint.proceed();
            }
            case EXCEPTION -> {
                log.warn("[混沌工程] 注入异常: fault={}, target={}, exception={}",
                        faultName, fullName, fault.getExceptionClass());
                try {
                    Class<?> exClass = Class.forName(fault.getExceptionClass());
                    var constructor = exClass.getConstructor(String.class);
                    throw (Throwable) constructor.newInstance(fault.getExceptionMessage());
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(fault.getExceptionMessage());
                }
            }
            case RETURN_NULL -> {
                log.warn("[混沌工程] 注入返回null: fault={}, target={}", faultName, fullName);
                return null;
            }
            default -> {
                return joinPoint.proceed();
            }
        }
    }

    /**
     * 目标方法匹配（支持 * 通配符）
     * <p>
     * 示例：
     * - "RedisOperator.*" 匹配 RedisOperator 的所有方法
     * - "UserService.getUserById" 精确匹配
     * - "*.create*" 匹配所有类的 create 开头方法
     * </p>
     */
    private boolean matchTarget(String fullName, String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return false;
        }
        // 将通配符 * 转换为正则
        String regex = pattern.replace(".", "\\.").replace("*", ".*");
        return fullName.matches(regex);
    }
}
