package com.myxhs.common.zone.redis.config;

import com.myxhs.common.zone.redis.interceptor.EventPublishingRedisCommandInterceptor;
import com.myxhs.common.zone.redis.interceptor.RedisMethodInterceptor;
import com.myxhs.common.zone.redis.wrapper.RedisTemplateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Redis 命令拦截自动配置。
 *
 * @since 1.0.0
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(RedisTemplate.class)
@ConditionalOnProperty(prefix = "myxhs.redis.interceptor", name = "enabled", havingValue = "true", matchIfMissing = false)
public class RedisInterceptorAutoConfiguration {

    @Bean
    public EventPublishingRedisCommandInterceptor eventPublishingRedisCommandInterceptor() {
        return new EventPublishingRedisCommandInterceptor();
    }

    @Bean
    public static RedisTemplateWrapperBeanPostProcessor redisTemplateWrapperBeanPostProcessor() {
        return new RedisTemplateWrapperBeanPostProcessor();
    }

    /**
     * BeanPostProcessor — 将 RedisTemplate 替换为 RedisTemplateWrapper。
     */
    static class RedisTemplateWrapperBeanPostProcessor implements BeanPostProcessor, ApplicationContextAware {

        private ApplicationContext applicationContext;
        private List<RedisMethodInterceptor> interceptors;

        @Override
        public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
            this.applicationContext = applicationContext;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
            if (bean instanceof RedisTemplate && !(bean instanceof RedisTemplateWrapper)) {
                if (interceptors == null) {
                    initInterceptors();
                }
                String appName = applicationContext.getEnvironment().getProperty("spring.application.name", "unknown");
                RedisTemplateWrapper<?, ?> wrapper = new RedisTemplateWrapper<>(
                        beanName, (RedisTemplate<?, ?>) bean, appName, interceptors);
                log.info("Wrapped RedisTemplate '{}' with RedisTemplateWrapper (interceptors: {})",
                        beanName, interceptors.size());
                return wrapper;
            }
            return bean;
        }

        private void initInterceptors() {
            interceptors = new ArrayList<>();
            // 收集所有 RedisMethodInterceptor Bean
            String[] names = applicationContext.getBeanNamesForType(RedisMethodInterceptor.class);
            for (String name : names) {
                interceptors.add(applicationContext.getBean(name, RedisMethodInterceptor.class));
            }
            // 按 order 排序
            interceptors.sort((a, b) -> Integer.compare(a.getOrder(), b.getOrder()));
        }
    }
}
