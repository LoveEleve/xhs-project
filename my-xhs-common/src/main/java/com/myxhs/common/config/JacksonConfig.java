package com.myxhs.common.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Jackson 全局配置
 * <p>
 * 统一 JSON 序列化/反序列化规则：
 * 1. LocalDateTime 格式：yyyy-MM-dd HH:mm:ss
 * 2. LocalDate 格式：yyyy-MM-dd
 * 3. 忽略未知属性（前端多传字段不报错）
 * 4. 空对象不报错
 * </p>
 */
@Configuration
public class JacksonConfig {

    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    private static final String DATE_PATTERN = "yyyy-MM-dd";

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jackson2ObjectMapperBuilderCustomizer() {
        return builder -> {
            // LocalDateTime 序列化/反序列化
            builder.serializerByType(LocalDateTime.class,
                    new LocalDateTimeSerializer(DateTimeFormatter.ofPattern(DATE_TIME_PATTERN)));
            builder.deserializerByType(LocalDateTime.class,
                    new LocalDateTimeDeserializer(DateTimeFormatter.ofPattern(DATE_TIME_PATTERN)));

            // LocalDate 序列化/反序列化
            builder.serializerByType(LocalDate.class,
                    new LocalDateSerializer(DateTimeFormatter.ofPattern(DATE_PATTERN)));
            builder.deserializerByType(LocalDate.class,
                    new LocalDateDeserializer(DateTimeFormatter.ofPattern(DATE_PATTERN)));

            // Long/long 序列化为 String：
            // 雪花ID（19位）超过 JS Number.MAX_SAFE_INTEGER(2^53)，作为 JSON 数字会被前端丢失精度，
            // 统一转字符串保持精度。注：作用于 HTTP 响应序列化，不影响 Redis/MQ 内部 ObjectMapper；
            // BigDecimal 价格/金额、Integer 等不受影响。用 modulesToInstall 强制覆盖默认 Long 序列化。
            SimpleModule longToStringModule = new SimpleModule();
            longToStringModule.addSerializer(Long.class, ToStringSerializer.instance);
            longToStringModule.addSerializer(Long.TYPE, ToStringSerializer.instance);
            builder.modulesToInstall(longToStringModule);

            // 忽略未知属性
            builder.featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            // 空对象不报错
            builder.featuresToDisable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        };
    }
}
