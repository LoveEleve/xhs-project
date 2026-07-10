package com.myxhs.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.filter.ShallowEtagHeaderFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.WebContentInterceptor;

import java.util.concurrent.TimeUnit;

/**
 * HTTP 缓存配置
 * <p>
 * 启用 ETag（ShallowEtagHeaderFilter）和 Cache-Control（WebContentInterceptor），
 * 减少重复数据传输。客户端通过 If-None-Match / If-Modified-Since 进行缓存验证，
 * 内容未变时返回 304 Not Modified。
 * </p>
 * <p>
 * 注意：仅在 Servlet（WebMVC）环境下生效，Gateway（WebFlux）环境不加载。
 * </p>
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class HttpCacheConfig implements WebMvcConfigurer {

    /**
     * Shallow ETag Filter — 自动为响应体计算 ETag（MD5 哈希）
     * 客户端下次请求带 If-None-Match，内容未变返回 304
     */
    @Bean
    public FilterRegistrationBean<ShallowEtagHeaderFilter> shallowEtagHeaderFilter() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ShallowEtagHeaderFilter());
        registration.addUrlPatterns("/api/*");
        registration.setOrder(1);
        registration.setName("shallowEtagFilter");
        return registration;
    }

    /**
     * WebContentInterceptor — 为不同路径添加 Cache-Control 头
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        WebContentInterceptor cacheInterceptor = new WebContentInterceptor();

        // 静态资源：缓存 1 小时，可被公共 CDN 缓存
        cacheInterceptor.addCacheMapping(
                CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic(),
                "/static/**", "/assets/**", "/images/**");

        // API GET 查询接口：缓存 10 秒，私有缓存，必须每次验证
        cacheInterceptor.addCacheMapping(
                CacheControl.maxAge(10, TimeUnit.SECONDS).cachePrivate().mustRevalidate(),
                "/api/**");

        registry.addInterceptor(cacheInterceptor).addPathPatterns("/**");
    }
}
