package com.myxhs.gateway.config;

import com.myxhs.gateway.handler.CachingFilteringWebHandler;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.stereotype.Component;

/**
 * 替换 Spring Cloud Gateway 原生的 FilteringWebHandler 为缓存版本。
 */
@Component
public class FilteringWebHandlerBeanDefinitionRegistryPostProcessor implements BeanDefinitionRegistryPostProcessor {

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        if (registry.containsBeanDefinition("filteringWebHandler")) {
            registry.removeBeanDefinition("filteringWebHandler");
        }

        registry.registerBeanDefinition("filteringWebHandler",
                BeanDefinitionBuilder
                        .genericBeanDefinition(CachingFilteringWebHandler.class)
                        .addConstructorArgReference("globalFilters")
                        .getBeanDefinition());
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        // no-op
    }
}
