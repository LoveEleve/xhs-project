package com.myxhs.common.response;

import com.myxhs.common.exception.BizException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.lang.reflect.Method;
import java.util.Objects;

/**
 * 响应体自动包装切面
 * <p>
 * Controller 方法直接返回 {@code UserDTO}，本切面自动包装为 {@code R<UserDTO>}。
 * 业务代码不再需要手动调用 {@code R.ok(data)}。
 * </p>
 *
 * <h3>Spring 框架调用链</h3>
 * <pre>
 * DispatcherServlet.doDispatch()
 *   → AbstractHandlerMethodAdapter.handle()
 *     → ServletInvocableHandlerMethod.invokeAndHandle()
 *       → HandlerMethodReturnValueHandlerComposite.handleReturnValue()
 *         → RequestResponseBodyMethodProcessor.handleReturnValue()
 *           → AbstractMessageConverterMethodProcessor.writeWithMessageConverters()
 *             → ResponseBodyAdvice.beforeBodyWrite()  ← 我们的扩展点
 * </pre>
 *
 * <h3>包装规则</h3>
 * <ul>
 *   <li>返回值已是 {@code R} 类型：不包装（防止重复包装）</li>
 *   <li>返回值是 {@code String} 类型：不包装（String 的序列化路径不同，Spring 会用
 *       StringHttpMessageConverter 而非 MappingJackson2HttpMessageConverter）</li>
 *   <li>返回值是 {@code void}（null）：包装为 {@code R.ok()}</li>
 *   <li>其他类型：包装为 {@code R.ok(data)}</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 优化前
 * @GetMapping("/{id}")
 * public R<UserDTO> getUser(@PathVariable Long id) {
 *     return R.ok(userService.getUserInfo(id));
 * }
 *
 * // 优化后
 * @GetMapping("/{id}")
 * public UserDTO getUser(@PathVariable Long id) {
 *     return userService.getUserInfo(id);
 * }
 * }</pre>
 *
 * <h3>代价与边界</h3>
 * <ul>
 *   <li>String 返回值不包装，需手动 {@code return R.ok("message")}</li>
 *   <li>如果 Controller 返回 {@code R<T>} 包装后的数据，不会重复包装（通过
 *       {@code supports()} 过滤）</li>
 *   <li>与 {@code @ResponseBody} / {@code @RestController} 行为一致</li>
 *   <li>不影响异常处理（{@link GlobalExceptionHandler} 直接返回 {@code R}，不会被本切面再次包装）</li>
 * </ul>
 *
 * @see org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice
 * @see org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor
 */
@RestControllerAdvice
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ResponseAutoWrapper implements ResponseBodyAdvice<Object> {

    /**
     * 判断是否需要包装
     * <p>
     * 返回 true 表示需要执行 {@link #beforeBodyWrite}。
     * 过滤掉已经是 {@code R} 类型的返回值，防止重复包装。
     * </p>
     *
     * @param returnType    方法返回类型
     * @param converterType 消息转换器类型
     * @return true=需要包装, false=跳过
     */
    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        // 排除 actuator 端点（Prometheus/metrics 等不应被包装为 R<>）
        if (isActuatorEndpoint(returnType)) {
            return false;
        }
        // 排除已经是 R 类型的返回值（包括 GlobalExceptionHandler 的异常响应）
        if (R.class.isAssignableFrom(returnType.getParameterType())) {
            return false;
        }
        // 排除 String 类型（StringHttpMessageConverter 序列化路径不同，
        // 包装为 R<String> 后会导致 ClassCastException）
        if (String.class.isAssignableFrom(returnType.getParameterType())) {
            return false;
        }
        return true;
    }

    /** 检查是否为 actuator 端点 */
    private boolean isActuatorEndpoint(MethodParameter returnType) {
        Method method = returnType.getMethod();
        if (method == null) return false;
        Class<?> clazz = method.getDeclaringClass();
        return clazz.getName().startsWith("org.springframework.boot.actuate");
    }

    /**
     * 包装返回值
     * <p>
     * 在 Spring 将返回值写入 HTTP Response Body 之前调用。
     * 此时业务方法已执行完毕，异常已被 {@link GlobalExceptionHandler} 拦截。
     * </p>
     * <p>
     * 线程安全：Spring 为每个请求分配独立的线程，本方法在请求线程中执行，无并发问题。
     * </p>
     *
     * @param body                  业务方法的返回值（可能为 null）
     * @param returnType            方法返回类型
     * @param selectedContentType   选中的 Content-Type
     * @param selectedConverterType 选中的消息转换器
     * @param request               当前请求
     * @param response              当前响应
     * @return 包装后的 R 对象
     */
    @Override
    public Object beforeBodyWrite(Object body,
                                   MethodParameter returnType,
                                   MediaType selectedContentType,
                                   Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                   ServerHttpRequest request,
                                   ServerHttpResponse response) {
        // 排除 actuator 端点（Prometheus/metrics 返回原始类型，不能被包装为 R<>）
        if (request.getURI().getPath().startsWith("/actuator")) {
            return body;
        }
        // void 返回（null）包装为 R.ok()
        if (body == null) {
            return R.ok();
        }
        // 已是 R 类型则不包装（supports 已过滤，这里做二次防御）
        if (body instanceof R) {
            return body;
        }
        return R.ok(body);
    }
}
