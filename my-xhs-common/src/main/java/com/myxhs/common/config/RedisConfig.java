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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.util.StringUtils;

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

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    /**
     * 默认 Redis 连接指向 Business Redis (16381, noeviction)
     * 业务数据走此连接，保护数据不丢失
     * <p>
     * 支持 Sentinel 模式和单节点模式自动切换：
     * <ul>
     *   <li>Sentinel 模式：配置 spring.data.redis.sentinel.nodes 后自动启用</li>
     *   <li>单节点模式：未配置 sentinel.nodes 时使用 host:port 直连（兼容现有配置）</li>
     * </ul>
     */
    @Bean
    @Primary
    public LettuceConnectionFactory defaultRedisConnectionFactory(
            @Value("${spring.data.redis.sentinel.master:myMaster}") String master,
            @Value("${spring.data.redis.sentinel.nodes:}") String sentinelNodes,
            @Value("${spring.data.redis.host:21.91.124.110}") String host,
            @Value("${spring.data.redis.business.port:16381}") int port,
            @Value("${spring.data.redis.password:Xhs@2026#Redis}") String password) {

        LettuceClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
                .build();

        if (StringUtils.hasText(sentinelNodes)) {
            // Sentinel 哨兵模式
            RedisSentinelConfiguration sentinelConfig = new RedisSentinelConfiguration()
                    .master(master);
            for (String node : sentinelNodes.split(",")) {
                String[] parts = node.trim().split(":");
                sentinelConfig.sentinel(parts[0], Integer.parseInt(parts[1]));
            }
            sentinelConfig.setPassword(RedisPassword.of(password));
            log.info("[Redis] 使用 Sentinel 模式, master={}, nodes={}", master, sentinelNodes);
            return new LettuceConnectionFactory(sentinelConfig, clientConfig);
        } else {
            // 单节点模式（兼容现有配置）
            RedisStandaloneConfiguration standaloneConfig = new RedisStandaloneConfiguration(host, port);
            standaloneConfig.setPassword(RedisPassword.of(password));
            log.info("[Redis] 使用单节点模式, host={}:{}", host, port);
            return new LettuceConnectionFactory(standaloneConfig, clientConfig);
        }
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
