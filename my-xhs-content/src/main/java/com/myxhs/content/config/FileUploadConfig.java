package com.myxhs.content.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 静态资源配置
 * <p>
 * 将本地磁盘的上传目录映射为 HTTP 可访问的静态资源路径。
 * 例如：http://localhost:19002/uploads/note/2026/05/13/xxx.jpg
 * </p>
 */
@Configuration
public class FileUploadConfig implements WebMvcConfigurer {

    @Value("${storage.local.path:/data/uploads}")
    private String uploadPath;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 将 /uploads/** 映射到本地磁盘目录
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + uploadPath + "/");
    }
}
