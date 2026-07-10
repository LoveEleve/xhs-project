package com.myxhs.feign.config;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.response.R;
import feign.FeignException;
import feign.Response;
import feign.Util;
import feign.codec.Decoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.cloud.openfeign.support.SpringDecoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

/**
 * Feign 统一增强配置
 * <p>
 * 三项增强：
 * <ol>
 *   <li><b>BizException 还原</b>：下游返回 {@code R.fail()} 时，Feign 客户端自动抛出 {@link BizException}，
 *       调用方只需要 try-catch 业务异常，不需要手动解析 R.code</li>
 *   <li><b>R&lt;T&gt; 自动解包</b>：Feign 接口方法返回 {@code T} 而非 {@code R<T>}，
 *       框架自动从 {@code R.data} 中提取数据</li>
 *   <li><b>统一 RequestInterceptor</b>：染色标记 + 认证 Token 在 common 层统一注入</li>
 * </ol>
 * </p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 优化前
 * @FeignClient(name = "my-xhs-content")
 * public interface ContentFeignClient {
 *     @GetMapping("/api/note/detail/{id}")
 *     R<NoteDetailDTO> getNoteDetail(@PathVariable Long id);
 * }
 * // 调用方
 * R<NoteDetailDTO> result = contentFeignClient.getNoteDetail(id);
 * if (result.isSuccess()) {
 *     NoteDetailDTO dto = result.getData();
 * }
 *
 * // 优化后
 * @FeignClient(name = "my-xhs-content", configuration = FeignUnifiedConfig.class)
 * public interface ContentFeignClient {
 *     @GetMapping("/api/note/detail/{id}")
 *     NoteDetailDTO getNoteDetail(@PathVariable Long id);  // 直接返回 T
 * }
 * // 调用方
 * NoteDetailDTO dto = contentFeignClient.getNoteDetail(id);  // 异常自动抛 BizException
 * }</pre>
 *
 * <h3>Spring Cloud OpenFeign 调用链</h3>
 * <pre>
 * FeignInvocationHandler.invoke()
 *   → SynchronousMethodHandler.invoke()
 *     → SynchronousMethodHandler.executeAndDecode()
 *       → client.execute()              // HTTP 请求
 *       → ErrorDecoder.decode()          // 非 2xx 响应 → 我们的 BizException 还原
 *       → Decoder.decode()              // 2xx 响应 → 我们的 R<T> 自动解包
 * </pre>
 */
@Slf4j
@Configuration
public class FeignUnifiedConfig {

    /**
     * 创建默认的 SpringDecoder，使用延迟注入 HttpMessageConverters 避免循环依赖
     * <p>
     * 注意：使用 ObjectFactory 而非直接注入 HttpMessageConverters，
     * 因为 HttpMessageConverters 的创建链可能涉及 ObjectMapper 等 Bean 的初始化，
     * 而这些 Bean 又可能依赖 Feign 组件，形成循环。
     * </p>
     */
    @Bean
    public SpringDecoder feignSpringDecoder(ObjectFactory<HttpMessageConverters> messageConverters) {
        return new SpringDecoder(messageConverters);
    }

    /**
     * 增强版 Decoder：自动解包 {@code R<T>} 中的 data 字段
     * <p>
     * Feign 接口方法声明返回 {@code T} 时，本 Decoder 自动从下游的 {@code R<T>} 中提取 data。
     * 如果 data 为 null 或 code != 200，抛出 BizException。
     * </p>
     * <p>
     * 依赖链：feignUnpackDecoder → feignSpringDecoder → ObjectFactory<HttpMessageConverters>
     * 使用 ObjectFactory 延迟获取 HttpMessageConverters，避免在 Bean 创建期间形成循环。
     * </p>
     */
    @Bean
    public Decoder feignUnpackDecoder(SpringDecoder springDecoder, ObjectMapper objectMapper) {
        return new RUnpackDecoder(springDecoder, objectMapper);
    }

    /**
     * R&lt;T&gt; 自动解包 Decoder
     * <p>
     * Spring Cloud OpenFeign 的 {@link SpringDecoder} 会调用标准的
     * {@link org.springframework.http.converter.HttpMessageConverter} 进行反序列化。
     * 本 Decoder 在 SpringDecoder 的基础上，识别返回类型是否为 {@code R<T>}，
     * 如果是则自动提取 data 字段。
     * </p>
     * <p>
     * 线程安全：Spring 为每个 FeignClient 创建独立的 Decoder 实例，无并发问题。
     * </p>
     */
    static class RUnpackDecoder implements Decoder {

        private final Decoder delegate;
        private final ObjectMapper objectMapper;

        RUnpackDecoder(Decoder delegate, ObjectMapper objectMapper) {
            this.delegate = delegate;
            this.objectMapper = objectMapper;
        }

        @Override
        public Object decode(Response response, Type type) throws IOException, FeignException {
            // 如果返回类型声明为 R<T>，走默认解码（不需要解包）
            if (isRType(type)) {
                return delegate.decode(response, type);
            }

            // 读取响应体
            byte[] bodyBytes = Util.toByteArray(response.body().asInputStream());
            String body = new String(bodyBytes, StandardCharsets.UTF_8);

            // 解析 R 结构
            JavaType rType = objectMapper.getTypeFactory().constructParametricType(R.class, Object.class);
            R<?> r = objectMapper.readValue(body, rType);

            if (r == null) {
                throw new BizException(500, "下游服务返回空响应");
            }

            if (!r.isSuccess()) {
                throw new BizException(r.getCode(), r.getMessage());
            }

            // R.data 为 null 且返回类型不是 void → 抛异常
            if (r.getData() == null && type != Void.class && type != void.class) {
                throw new BizException(500, "下游服务返回空数据");
            }

            // 从 R.data 反序列化为目标类型
            if (r.getData() != null) {
                return objectMapper.convertValue(r.getData(), objectMapper.getTypeFactory().constructType(type));
            }

            return null;
        }

        /**
         * 判断返回类型是否为 R 或其子类型
         */
        private boolean isRType(Type type) {
            if (type instanceof Class) {
                return R.class.isAssignableFrom((Class<?>) type);
            }
            if (type instanceof ParameterizedType) {
                return R.class.isAssignableFrom((Class<?>) ((ParameterizedType) type).getRawType());
            }
            return false;
        }
    }
}
