package com.myxhs.common.version;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.servlet.mvc.condition.RequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;

/**
 * 多版本 API HandlerMapping
 * 
 * 继承 RequestMappingHandlerMapping，在 getCustomMethodCondition 中
 * 读取 @ApiVersion 注解，注入 ApiVersionCondition。
 * 
 * Spring 调用链：
 * RequestMappingHandlerMapping.afterPropertiesSet()
 *   → detectHandlerMethods()
 *     → getMappingForMethod()
 *       → getCustomMethodCondition()  ← 我们的扩展点
 */
public class ApiVersionHandlerMapping extends RequestMappingHandlerMapping {

    @Override
    protected RequestCondition<?> getCustomTypeCondition(Class<?> handlerType) {
        ApiVersion apiVersion = AnnotatedElementUtils.findMergedAnnotation(handlerType, ApiVersion.class);
        return apiVersion != null ? new ApiVersionCondition(apiVersion.value()) : null;
    }

    @Override
    protected RequestCondition<?> getCustomMethodCondition(Method method) {
        ApiVersion apiVersion = AnnotatedElementUtils.findMergedAnnotation(method, ApiVersion.class);
        return apiVersion != null ? new ApiVersionCondition(apiVersion.value()) : null;
    }
}
