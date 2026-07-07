package com.myxhs.common.version;

import java.lang.annotation.*;

/**
 * API 版本注解
 * 
 * 标记 Controller 方法支持的 API 版本。
 * 同一 URL 可以有多个不同版本的处理方法。
 * 
 * 使用示例：
 * @GetMapping("/api/user/{id}")
 * @ApiVersion("1.0")
 * public UserVO getUserV1(@PathVariable Long id) { ... }
 * 
 * @GetMapping("/api/user/{id}")
 * @ApiVersion("2.0")
 * public UserVO getUserV2(@PathVariable Long id) { ... }
 * 
 * 请求时通过 Header 指定版本：Accept-Version: 2.0
 * 如果未指定版本，默认使用最新版本。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ApiVersion {
    /**
     * API 版本号，格式：主版本.次版本（如 1.0, 2.0）
     */
    String value();
}
