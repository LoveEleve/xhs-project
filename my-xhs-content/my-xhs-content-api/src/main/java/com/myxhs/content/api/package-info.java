/**
 * 内容服务 API 模块
 * 
 * 本模块包含 Feign 接口和 DTO，供其他微服务通过 Feign 调用时引入。
 * 调用方只需依赖此 api 模块，不需要引入完整的服务实现模块。
 * 
 * 使用方式：
 * 1. 在调用方的 pom.xml 中添加依赖
 * 2. 注入 FeignClient 接口即可调用
 * 
 * @since 1.0.0
 */
package com.myxhs.content.api;
