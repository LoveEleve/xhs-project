package com.myxhs.common.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置
 * <p>
 * Key 统一使用 String 序列化，Value 使用 JSON 序列化（支持 Java 8 时间类型）。
 * 避免使用 JDK 默认序列化（不可读 + 占用空间大）。
 * </p>
 * <p>
 * 启用 DefaultTyping：序列化时写入 @class 类型信息，反序列化时能还原为具体 Java 类型，
 * 而非 LinkedHashMap。这对于 CacheHelper 等需要泛型反序列化的场景是必须的。
 * </p>
 */
@Configuration
public class RedisConfig {

    /**
     * 默认 Redis 连接指向 Business Redis (16381, noeviction)
     * 业务数据走此连接，保护数据不丢失
     */
    @Bean
    @Primary
    public LettuceConnectionFactory defaultRedisConnectionFactory(
            @Value("${spring.data.redis.host:21.91.124.110}") String host,
            @Value("${spring.data.redis.business.port:16381}") int port,
            @Value("${spring.data.redis.password:Xhs@2026#Redis}") String password) {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
        config.setPassword(password);
        return new LettuceConnectionFactory(config);
    }

    @Bean
    @Primary
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Key 序列化：String
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Value 序列化：JSON（支持 LocalDateTime 等 Java 8 时间类型 + 类型信息）
        GenericJackson2JsonRedisSerializer jsonSerializer = createJsonSerializer();
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    /**
     * 创建 JSON 序列化器
     * <p>
     * 启用 DefaultTyping（NON_FINAL），序列化时写入 @class 字段，
     * 反序列化时能还原为具体 Java 类型（如 UserDTO），而非 LinkedHashMap。
     * </p>
     * <p>
     * 注意：父 pom 中通过 jackson-bom 统一了 Jackson 全家桶版本为 2.16.1，
     * 确保 jackson-annotations/core/databind 版本一致，避免 ClassNotFoundException。
     * </p>
     */
    private GenericJackson2JsonRedisSerializer createJsonSerializer() {
        ObjectMapper objectMapper = new ObjectMapper();
        // 支持 Java 8 时间类型
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // 设置可见性
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        // 忽略未知属性（向前兼容：字段增减不影响反序列化）
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // 启用类型信息（反序列化时能还原具体类型，而非 LinkedHashMap）
        // 安全策略：仅允许 com.myxhs. / java.util. / java.lang. / java.time. 包下的类型
        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType("com.myxhs.")
                .allowIfBaseType("java.util.")
                .allowIfBaseType("java.lang.")
                .allowIfBaseType("java.time.")
                .build();
        objectMapper.activateDefaultTyping(
                ptv,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );
        return new GenericJackson2JsonRedisSerializer(objectMapper);
    }
}
