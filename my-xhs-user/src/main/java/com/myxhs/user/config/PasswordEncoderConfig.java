package com.myxhs.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码编码器配置
 * <p>
 * 将 PasswordEncoder 声明为 Spring Bean，便于：
 * 1. 统一管理加密强度（通过 Bean 配置可调整 strength）
 * 2. 单元测试时 Mock 替换
 * 3. 未来切换其他加密算法只需修改此配置
 * </p>
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
