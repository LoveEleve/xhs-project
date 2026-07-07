package com.myxhs.common.version;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcRegistrations;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 多版本 API 自动配置
 * 
 * 仅在 Servlet Web 环境下生效（Gateway 是 WebFlux，不受影响）。
 * 通过 @ConditionalOnWebApplication 确保不会在 Gateway 中注册。
 * 
 * 通过 WebMvcRegistrations 替换默认的 RequestMappingHandlerMapping，
 * Spring Boot 会保留其他默认自动配置（如 message converters、view resolvers 等）。
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ApiVersionAutoConfiguration implements WebMvcRegistrations {

    @Override
    public RequestMappingHandlerMapping getRequestMappingHandlerMapping() {
        ApiVersionHandlerMapping handlerMapping = new ApiVersionHandlerMapping();
        handlerMapping.setOrder(0);
        return handlerMapping;
    }
}
